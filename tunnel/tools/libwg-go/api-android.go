/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
// #include <stdlib.h>
import "C"

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/flow"
	"golang.zx2c4.com/wireguard/ipc"
	"golang.zx2c4.com/wireguard/scionlog"
	bootstrap "golang.zx2c4.com/wireguard/translator/bootstrap"
	"golang.zx2c4.com/wireguard/tun"
)

// Buffer and chunk limits are defined in logcat_writer.go.

// logTag is the process-global logcat tag, allocated once via C.CString.
var (
	logTag     *C.char
	logTagOnce sync.Once
)

func getLogTag() *C.char {
	logTagOnce.Do(func() {
		logTag = C.CString("WireGuard/GoBackend")
	})
	return logTag
}

// Core WireGuard log level, set from Java via wgSetCoreLogLevel before wgTurnOn.
// 0 = verbose (default), 1 = error only, 2 = silent.
var wireguardCoreLogLevel atomic.Int32

//export wgSetCoreLogLevel
func wgSetCoreLogLevel(level C.int) {
	wireguardCoreLogLevel.Store(int32(level))
}

// defaultLogFunc writes a single null-terminated record to __android_log_write.
// The package-level logFunc variable (in logcat_writer.go) defaults to this.
func defaultLogFunc(msg string) {
	cMsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cMsg))
	C.__android_log_write(C.ANDROID_LOG_DEBUG, getLogTag(), cMsg)
}

// logWriter is the package-level singleton passed to log.SetOutput.
var logWriter = &androidLogWriter{}

// Flush writes any buffered partial log line to logcat.
// Call at process shutdown only; the writer is process-global.
func Flush() { logWriter.Flush() }

type AndroidLogger struct {
	level C.int
	tag   *C.char
}

func cstring(s string) *C.char {
	b, err := unix.BytePtrFromString(s)
	if err != nil {
		b := [1]C.char{}
		return &b[0]
	}
	return (*C.char)(unsafe.Pointer(b))
}

func (l AndroidLogger) Printf(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	cMsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cMsg))
	C.__android_log_write(l.level, l.tag, cMsg)
}

type TunnelHandle struct {
	device     *device.Device
	uapi       net.Listener
	mockDevice *MockDevice
}

var (
	tunnelHandles    map[int32]TunnelHandle
	mockModeEnabled  atomic.Bool
	mockScenario     atomic.Value // string
	globalMockDevice *MockDevice
	mockMu           sync.Mutex
)

//export wgSetMockMode
func wgSetMockMode(enabled C.int, scenario string) {
	scenario = strings.Clone(scenario)
	if scenario == "" {
		scenario = ScenarioDefaultMultiPath
	}
	mockMu.Lock()
	defer mockMu.Unlock()
	if enabled != 0 {
		mockModeEnabled.Store(true)
		mockScenario.Store(scenario)
		if globalMockDevice == nil {
			globalMockDevice = NewMockDevice(scenario)
		} else {
			globalMockDevice.SetScenario(scenario)
		}
	} else {
		mockModeEnabled.Store(false)
		globalMockDevice = nil
		// Clean up any remaining mock handles
		for k, v := range tunnelHandles {
			if v.device == nil {
				delete(tunnelHandles, k)
			}
		}
	}
}

func init() {
	logFunc = defaultLogFunc
	log.SetOutput(logWriter)
	tunnelHandles = make(map[int32]TunnelHandle)
	signals := make(chan os.Signal)
	signal.Notify(signals, unix.SIGUSR2)
	go func() {
		buf := make([]byte, os.Getpagesize())
		for {
			select {
			case <-signals:
				n := runtime.Stack(buf, true)
				if n == len(buf) {
					n--
				}
				buf[n] = 0
				C.__android_log_write(C.ANDROID_LOG_ERROR, cstring("WireGuard/GoBackend/Stacktrace"), (*C.char)(unsafe.Pointer(&buf[0])))
			}
		}
	}()
}

//export wgTurnOn
func wgTurnOn(interfaceName string, tunFd int32, settings string) int32 {
	// Defensive: clone all strings from cgo to ensure Go owns independent copies.
	// cgo //export string params reference caller memory that may be freed after return.
	interfaceName = strings.Clone(interfaceName)
	settings = strings.Clone(settings)

	if mockModeEnabled.Load() || tunFd == -2 {
		scen, _ := mockScenario.Load().(string)
		if scen == "" {
			scen = ScenarioDefaultMultiPath
		}
		mockDev := NewMockDevice(scen)
		var i int32
		for i = 0; i < math.MaxInt32; i++ {
			if _, exists := tunnelHandles[i]; !exists {
				break
			}
		}
		if i == math.MaxInt32 {
			return -1
		}
		tunnelHandles[i] = TunnelHandle{mockDevice: mockDev}
		return i
	}

	tag := C.CString("WireGuard/GoBackend/" + interfaceName)
	androidDebug := AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf
	androidError := AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf

	var verbosef func(format string, args ...any)
	var errorf func(format string, args ...any)

	switch wireguardCoreLogLevel.Load() {
	case 2: // silent
		verbosef = device.DiscardLogf
		errorf = device.DiscardLogf
	case 1: // error only
		verbosef = device.DiscardLogf
		errorf = androidError
	default: // verbose (0)
		verbosef = androidDebug
		errorf = androidError
	}

	logger := &device.Logger{
		Verbosef: verbosef,
		Errorf:   errorf,
	}

	tun, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}

	logger.Verbosef("Attaching to interface %v", name)
	scionConfig := device.ScionDeviceConfig{
		Enabled:       false,
		InterfaceName: name,
	}
	//SCION start disabled is enabled in app later.
	device := device.NewDevice(tun, conn.NewStdNetBind(), logger, scionConfig)

	err = device.IpcSet(settings)
	if err != nil {
		unix.Close(int(tunFd))
		logger.Errorf("IpcSet: %v", err)
		return -1
	}
	device.DisableSomeRoamingForBrokenMobileSemantics()

	var uapi net.Listener

	uapiFile, err := ipc.UAPIOpen(name)
	if err != nil {
		logger.Errorf("UAPIOpen: %v", err)
	} else {
		uapi, err = ipc.UAPIListen(name, uapiFile)
		if err != nil {
			uapiFile.Close()
			logger.Errorf("UAPIListen: %v", err)
		} else {
			go func() {
				for {
					conn, err := uapi.Accept()
					if err != nil {
						return
					}
					go device.IpcHandle(conn)
				}
			}()
		}
	}

	err = device.Up()
	if err != nil {
		logger.Errorf("Unable to bring up device: %v", err)
		if uapiFile != nil {
			uapiFile.Close()
		}
		device.Close()
		return -1
	}
	logger.Verbosef("Device started")

	var i int32
	for i = 0; i < math.MaxInt32; i++ {
		if _, exists := tunnelHandles[i]; !exists {
			break
		}
	}
	if i == math.MaxInt32 {
		logger.Errorf("Unable to find empty handle")
		if uapiFile != nil {
			uapiFile.Close()
		}
		device.Close()
		return -1
	}
	tunnelHandles[i] = TunnelHandle{device: device, uapi: uapi}
	return i
}

//export wgTurnOff
func wgTurnOff(tunnelHandle int32) {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return
	}
	delete(tunnelHandles, tunnelHandle)
	if handle.uapi != nil {
		handle.uapi.Close()
	}
	if handle.device != nil {
		handle.device.Close()
	}
}

//export wgGetSocketV4
func wgGetSocketV4(tunnelHandle int32) int32 {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return -1
	}
	bind, _ := handle.device.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd4()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export wgGetSocketV6
func wgGetSocketV6(tunnelHandle int32) int32 {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return -1
	}
	bind, _ := handle.device.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd6()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export wgGetConfig
func wgGetConfig(tunnelHandle int32) *C.char {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return nil
	}
	if mockModeEnabled.Load() && handle.mockDevice != nil {
		elapsed := int64(time.Since(handle.mockDevice.startTime).Seconds())
		rx := 3800000 + elapsed*78200
		tx := 1250000 + elapsed*24500
		handshake := time.Now().Unix() - 12
		uapi := fmt.Sprintf("public_key=4b486134376c48325a725176466f363068774738724d4554376759715a315033\nlisten_port=51820\npublic_key=666f6f62617262617a717578313233343536373839306162636465666768696a\nrx_bytes=%d\ntx_bytes=%d\nlast_handshake_time_sec=%d\nlast_handshake_time_nsec=0\n\n", rx, tx, handshake)
		return C.CString(uapi)
	}
	if handle.device == nil {
		return nil
	}
	settings, err := handle.device.IpcGet()
	if err != nil {
		return nil
	}
	return C.CString(settings)
}

//export wgVersion
func wgVersion() *C.char {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return C.CString("unknown")
	}
	for _, dep := range info.Deps {
		if dep.Path == "golang.zx2c4.com/wireguard" {
			parts := strings.Split(dep.Version, "-")
			if len(parts) == 3 && len(parts[2]) == 12 {
				return C.CString(parts[2][:7])
			}
			return C.CString(dep.Version)
		}
	}
	return C.CString("unknown")
}

//export wgScionTestBridge
func wgScionTestBridge(inputPath string) *C.char {
	inputPath = strings.Clone(inputPath)
	outStr := filepath.Join(inputPath, "scion_configs", "certs")
	return C.CString(fmt.Sprintf("Greetings from Go! Your SCION config path is: %s", outStr))
}

//export wgScionBootstrap
func wgScionBootstrap(configDir string, bootstrapURL string) *C.char {
	configDir = strings.Clone(configDir)
	bootstrapURL = strings.Clone(bootstrapURL)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := bootstrap.BootstrapFetch(ctx, bootstrapURL, configDir); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("ok")
}

//export wgInitScion
func wgInitScion(tunnelHandle int32, configDir string, interfaceName string) *C.char {
	configDir = strings.Clone(configDir)
	interfaceName = strings.Clone(interfaceName)

	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return C.CString("invalid handle")
	}
	if mockModeEnabled.Load() && handle.mockDevice != nil {
		return C.CString("ok")
	}
	if handle.device == nil {
		return C.CString("device_not_found")
	}
	scionConfig := device.ScionDeviceConfig{
		Enabled:       true,
		ConfigDir:     configDir,
		InterfaceName: interfaceName,
	}
	if err := handle.device.InitSCION(scionConfig); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("ok")
}

//export wgInitScionWithBootstrapRetry
func wgInitScionWithBootstrapRetry(
	tunnelHandle int32,
	configDir string,
	interfaceName string,
	bootstrapURL string,
	localIPv4 string,
	localIPv6 string,
	logLevel string,
	logComponents string,
	logFullTopology bool,
	logPacketBytes bool,
	logPathBytes bool,
	logInternalStructs bool,
) *C.char {
	configDir = strings.Clone(configDir)
	interfaceName = strings.Clone(interfaceName)
	bootstrapURL = strings.Clone(bootstrapURL)
	localIPv4 = strings.Clone(localIPv4)
	localIPv6 = strings.Clone(localIPv6)
	logLevel = strings.Clone(logLevel)
	logComponents = strings.Clone(logComponents)

	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return C.CString("invalid handle")
	}
	if mockModeEnabled.Load() && handle.mockDevice != nil {
		return C.CString("ok")
	}
	if handle.device == nil {
		return C.CString("device_not_found")
	}

	if configDir == "" {
		configDir = filepath.Join(os.TempDir(), "wg-scion")
	}

	logCfg := scionlog.ParseConfig(logLevel, logComponents)

	scionConfig := device.ScionDeviceConfig{
		Enabled:       true,
		ConfigDir:     configDir,
		InterfaceName: interfaceName,
		LogConfig:     &logCfg,
	}

	if localIPv4 != "" {
		if addr, err := netip.ParseAddr(localIPv4); err == nil {
			scionConfig.LocalIPv4 = addr
		}
	}
	if localIPv6 != "" {
		if addr, err := netip.ParseAddr(localIPv6); err == nil {
			scionConfig.LocalIPv6 = addr
		}
	}

	err := handle.device.InitSCIONWithBootstrapRetry(
		context.Background(),
		scionConfig,
		bootstrapURL,
		device.DefaultSCIONInitRetryOptions(),
	)
	if err != nil {
		return C.CString(err.Error())
	}

	return C.CString("ok")
}

//export wgGetScionStatus
func wgGetScionStatus(tunnelHandle int32) *C.char {
	if mockModeEnabled.Load() {
		handle, ok := tunnelHandles[tunnelHandle]
		if ok && handle.mockDevice != nil {
			return C.CString(handle.mockDevice.SCIONPathSnapshotJSON())
		}
		if globalMockDevice != nil {
			return C.CString(globalMockDevice.SCIONPathSnapshotJSON())
		}
	}
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return C.CString("{}")
	}
	json, err := handle.device.SCIONPathSnapshotJSON()
	if err != nil {
		return C.CString("{}")
	}
	return C.CString(json)
}

//export wgGetSCIONInfo
func wgGetSCIONInfo(tunnelHandle int32) *C.char {
	if mockModeEnabled.Load() {
		handle, ok := tunnelHandles[tunnelHandle]
		if ok && handle.mockDevice != nil {
			return C.CString(handle.mockDevice.SCIONInfoJSON())
		}
		if globalMockDevice != nil {
			return C.CString(globalMockDevice.SCIONInfoJSON())
		}
	}
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return C.CString("{}")
	}
	json, err := handle.device.SCIONInfoJSON()
	if err != nil {
		return C.CString("{}")
	}
	return C.CString(json)
}

//export wgGetFlows
func wgGetFlows(tunnelHandle int32) *C.char {
	if mockModeEnabled.Load() {
		handle, ok := tunnelHandles[tunnelHandle]
		if ok && handle.mockDevice != nil {
			return C.CString(handle.mockDevice.FlowSnapshotsJSON())
		}
		if globalMockDevice != nil {
			return C.CString(globalMockDevice.FlowSnapshotsJSON())
		}
	}
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		resp, _ := json.Marshal(flow.FlowListResponse{Error: "device_not_found"})
		return C.CString(string(resp))
	}
	snapshots := handle.device.FlowSnapshots()
	dtos := make([]flow.FlowDTO, len(snapshots))
	for i, s := range snapshots {
		dtos[i] = flow.MapSnapshotToDTO(s)
	}
	resp := flow.FlowListResponse{Flows: dtos}
	raw, err := json.Marshal(resp)
	if err != nil {
		resp := flow.FlowListResponse{Error: "serialization_failed"}
		raw, _ = json.Marshal(resp)
	}
	return C.CString(string(raw))
}

//export wgGetFlowPaths
func wgGetFlowPaths(tunnelHandle int32, flowID int64) *C.char {
	if mockModeEnabled.Load() {
		handle, ok := tunnelHandles[tunnelHandle]
		if ok && handle.mockDevice != nil {
			return C.CString(handle.mockDevice.SCIONPathsForFlowJSON(flowID))
		}
		if globalMockDevice != nil {
			return C.CString(globalMockDevice.SCIONPathsForFlowJSON(flowID))
		}
	}
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return C.CString(`{"error":"device_not_found"}`)
	}
	if flowID < 0 {
		return C.CString(`{"error":"invalid_flow_id"}`)
	}
	result := handle.device.SCIONPathsForFlow(flow.ID(flowID))
	raw, err := json.Marshal(result)
	if err != nil {
		return C.CString(`{"error":"serialization_failed"}`)
	}
	return C.CString(string(raw))
}

//export wgSetFlowPathOverride
func wgSetFlowPathOverride(tunnelHandle int32, flowID int64, fingerprint string) *C.char {
	if mockModeEnabled.Load() {
		handle, ok := tunnelHandles[tunnelHandle]
		if ok && handle.mockDevice != nil {
			err := handle.mockDevice.SetFlowPathOverride(flowID, fingerprint)
			if err != nil {
				return C.CString(`{"error":"` + err.Error() + `"}`)
			}
			return C.CString("")
		}
		if globalMockDevice != nil {
			err := globalMockDevice.SetFlowPathOverride(flowID, fingerprint)
			if err != nil {
				return C.CString(`{"error":"` + err.Error() + `"}`)
			}
			return C.CString("")
		}
	}
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return C.CString(`{"error":"device_not_found"}`)
	}
	if flowID < 0 {
		return C.CString(`{"error":"invalid_flow_id"}`)
	}
	err := handle.device.SetFlowPathOverride(flow.ID(flowID), fingerprint)
	if err != nil {
		return C.CString(`{"error":"` + err.Error() + `"}`)
	}
	return C.CString("")
}

//export wgClearFlowPathOverride
func wgClearFlowPathOverride(tunnelHandle int32, flowID int64) *C.char {
	if mockModeEnabled.Load() {
		handle, ok := tunnelHandles[tunnelHandle]
		if ok && handle.mockDevice != nil {
			err := handle.mockDevice.ClearFlowPathOverride(flowID)
			if err != nil {
				return C.CString(`{"error":"` + err.Error() + `"}`)
			}
			return C.CString("")
		}
		if globalMockDevice != nil {
			err := globalMockDevice.ClearFlowPathOverride(flowID)
			if err != nil {
				return C.CString(`{"error":"` + err.Error() + `"}`)
			}
			return C.CString("")
		}
	}
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok || handle.device == nil {
		return C.CString(`{"error":"device_not_found"}`)
	}
	if flowID < 0 {
		return C.CString(`{"error":"invalid_flow_id"}`)
	}
	handle.device.ClearFlowPathOverride(flow.ID(flowID))
	return C.CString("")
}

func main() {}
