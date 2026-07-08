/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
	"context"
	"fmt"
	"math"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/ipc"
	bootstrap "golang.zx2c4.com/wireguard/translator/bootstrap"
	"golang.zx2c4.com/wireguard/tun"
)

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
	C.__android_log_write(l.level, l.tag, cstring(fmt.Sprintf(format, args...)))
}

type TunnelHandle struct {
	device *device.Device
	uapi   net.Listener
}

var tunnelHandles map[int32]TunnelHandle

func init() {
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
	tag := cstring("WireGuard/GoBackend/" + interfaceName)
	logger := &device.Logger{
		Verbosef: AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf,
		Errorf:   AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf,
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
	handle.device.Close()
}

//export wgGetSocketV4
func wgGetSocketV4(tunnelHandle int32) int32 {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
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
	if !ok {
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
	outStr := filepath.Join(inputPath, "scion_configs", "certs")
	return C.CString(fmt.Sprintf("Greetings from Go! Your SCION config path is: %s", outStr))
}

//export wgScionBootstrap
/*
Downloads SCION topology + certificates from a bootstrap server. It:
1. Fetches topology.json from <bootstrapURL>/topology
2. Fetches the TRC certificate list from <bootstrapURL>/trcs
3. Downloads each TRC blob and saves to <configDir>/certs/ISD1-B1-S1.trc
4. Returns "ok" on success, or an error string
This gives the Go backend the SCION network topology it needs to know which paths exist.
*/
func wgScionBootstrap(configDir string, bootstrapURL string) *C.char {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := bootstrap.BootstrapFetch(ctx, bootstrapURL, configDir); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("ok")
}

//export wgInitScion
/*
Initializes the SCION translator on an already running WireGuard device. It:
1. Reads topology.json from configDir to learn the local ISD-AS and border router address
2. Creates a SCION daemon retriever (for looking up paths)
3. Creates a path pool (caches SCION paths)
4. Creates the translator (intercepts fc00::/8 packets → SCION packets)
5. Wires everything into the running device
This is the deferred init — the tunnel is already up, now SCION is enabled on it.
*/
func wgInitScion(tunnelHandle int32, configDir string, interfaceName string) *C.char {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return C.CString("invalid handle")
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
func wgInitScionWithBootstrapRetry(tunnelHandle int32, configDir string, interfaceName string, bootstrapURL string) *C.char {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return C.CString("invalid handle")
	}

	if configDir == "" {
		configDir = filepath.Join(os.TempDir(), "wg-scion")
	}

	scionConfig := device.ScionDeviceConfig{
		Enabled:       true,
		ConfigDir:     configDir,
		InterfaceName: interfaceName,
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
/*
Returns a JSON snapshot of the SCION path pool:
which ISD-AS pairs have cached paths, their latencies, expiry, etc.
This is for the UI to display SCION status.
*/
func wgGetScionStatus(tunnelHandle int32) *C.char {
	handle, ok := tunnelHandles[tunnelHandle]
	if !ok {
		return C.CString("{}")
	}
	json, err := handle.device.SCIONPathSnapshotJSON()
	if err != nil {
		return C.CString("{}")
	}
	return C.CString(json)
}

func main() {}
