/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package main

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"
)

// MockDevice manages simulated WireGuard & SCION runtime state
type MockDevice struct {
	mu                sync.RWMutex
	scenario          string
	startTime         time.Time
	activeOverrides   map[int64]string
	overrideTimestamp map[int64]time.Time
	pendingCounts     map[int64]int
	localIA           string
	localIPv4         string
	localIPv6         string
	borderRouter      string
	portRange         string
}

// NewMockDevice creates a new simulated device
func NewMockDevice(scenario string) *MockDevice {
	if scenario == "" {
		scenario = ScenarioDefaultMultiPath
	}
	return &MockDevice{
		scenario:          scenario,
		startTime:         time.Now(),
		activeOverrides:   make(map[int64]string),
		overrideTimestamp: make(map[int64]time.Time),
		pendingCounts:     make(map[int64]int),
		localIA:           "64-2:0:49",
		localIPv4:         "192.168.1.100",
		localIPv6:         "fd00:f00d::1",
		borderRouter:      "192.168.1.1:30042",
		portRange:         "30042-30045",
	}
}

// SetScenario dynamically changes the simulated scenario
func (m *MockDevice) SetScenario(scenario string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.scenario = scenario
	m.pendingCounts = make(map[int64]int)
}

// SCIONInfoJSON returns the local identity JSON
func (m *MockDevice) SCIONInfoJSON() string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	info := MockSCIONInfo{
		LocalIA:   m.localIA,
		LocalIPv4: m.localIPv4,
		LocalIPv6: m.localIPv6,
		BrAddr:    m.borderRouter,
		PortRange: m.portRange,
	}
	data, err := json.Marshal(info)
	if err != nil {
		return "{}"
	}
	return string(data)
}

// SCIONPathSnapshotJSON returns full topology snapshot JSON
func (m *MockDevice) SCIONPathSnapshotJSON() string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	snapshot := MockScionPathCacheSnapshot{
		Strategy:    "LowestLatency",
		LastRefresh: time.Now().UTC().Format(time.RFC3339),
		Pairs: []MockScionIaPairSnapshot{
			{
				SrcIa:               m.localIA,
				DstIa:               "64-1:0:12",
				SelectedFingerprint: "fp_zurich_direct",
				AvailablePathsCount: 3,
				Paths: []MockScionPathSnapshotItem{
					{
						Fingerprint: "fp_zurich_direct",
						NextHop:     "192.168.1.1:30042",
						Expiry:      time.Now().Add(4 * time.Hour).UTC().Format(time.RFC3339),
						MTU:         1472,
						Hops:        []string{"64-2:0:49#1", "64-1:0:12#2"},
						IsSelected:  true,
					},
					{
						Fingerprint: "fp_frankfurt_transit",
						NextHop:     "192.168.1.1:30042",
						Expiry:      time.Now().Add(3 * time.Hour).UTC().Format(time.RFC3339),
						MTU:         1472,
						Hops:        []string{"64-2:0:49#2", "64-2:0:50#1", "64-1:0:12#3"},
						IsSelected:  false,
					},
					{
						Fingerprint: "fp_geneva_backup",
						NextHop:     "192.168.1.1:30042",
						Expiry:      time.Now().Add(2 * time.Hour).UTC().Format(time.RFC3339),
						MTU:         1280,
						Hops:        []string{"64-2:0:49#3", "64-2:0:55#4", "64-1:0:12#1"},
						IsSelected:  false,
					},
				},
			},
			{
				SrcIa:               m.localIA,
				DstIa:               "64-3:0:88",
				SelectedFingerprint: "fp_tokyo_primary",
				AvailablePathsCount: 1,
				Paths: []MockScionPathSnapshotItem{
					{
						Fingerprint: "fp_tokyo_primary",
						NextHop:     "192.168.1.1:30042",
						Expiry:      time.Now().Add(5 * time.Hour).UTC().Format(time.RFC3339),
						MTU:         1472,
						Hops:        []string{"64-2:0:49#1", "64-3:0:88#2"},
						IsSelected:  true,
					},
				},
			},
		},
	}
	data, err := json.Marshal(snapshot)
	if err != nil {
		return "{}"
	}
	return string(data)
}

// FlowSnapshotsJSON returns dynamic flows with ticking counters
func (m *MockDevice) FlowSnapshotsJSON() string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if m.scenario == ScenarioEmptyFlows {
		resp, _ := json.Marshal(MockFlowListResponse{Flows: []MockFlowDTO{}})
		return string(resp)
	}

	elapsedSec := int64(time.Since(m.startTime).Seconds())
	if elapsedSec < 1 {
		elapsedSec = 1
	}

	// Dynamic streaming bytes simulation
	scionTx1 := 1_250_000 + elapsedSec*24_500
	scionRx1 := 3_800_000 + elapsedSec*78_200
	scionTx2 := 840_000 + elapsedSec*14_200
	scionRx2 := 1_920_000 + elapsedSec*36_800
	ipTx := 420_000 + elapsedSec*4_200
	ipRx := 980_000 + elapsedSec*9_600

	flows := []MockFlowDTO{
		{
			ID:         1,
			IPVersion:  4,
			Protocol:   6, // TCP (HTTPS)
			EndpointA:  MockEndpoint{Address: "192.168.1.100", Port: 51234},
			EndpointB:  MockEndpoint{Address: "198.51.100.10", Port: 443},
			Status:     "active",
			TxPackets:  scionTx1 / 1200,
			TxBytes:    scionTx1,
			RxPackets:  scionRx1 / 1400,
			RxBytes:    scionRx1,
			EgressKind: "scion",
			SrcIA:      m.localIA,
			DstIA:      "64-1:0:12",
			CreatedAt:  m.startTime.Format(time.RFC3339),
			LastSeen:   time.Now().Format(time.RFC3339),
			LocalIP:    "192.168.1.100",
			LocalPort:  51234,
			RemoteIP:   "198.51.100.10",
			RemotePort: 443,
			ScionDstIP: "198.51.100.10",
		},
		{
			ID:         2,
			IPVersion:  4,
			Protocol:   6, // TCP (Matrix / Sync)
			EndpointA:  MockEndpoint{Address: "192.168.1.100", Port: 54321},
			EndpointB:  MockEndpoint{Address: "198.51.100.25", Port: 8448},
			Status:     "active",
			TxPackets:  scionTx2 / 800,
			TxBytes:    scionTx2,
			RxPackets:  scionRx2 / 1100,
			RxBytes:    scionRx2,
			EgressKind: "scion",
			SrcIA:      m.localIA,
			DstIA:      "64-3:0:88",
			CreatedAt:  m.startTime.Format(time.RFC3339),
			LastSeen:   time.Now().Format(time.RFC3339),
			LocalIP:    "192.168.1.100",
			LocalPort:  54321,
			RemoteIP:   "198.51.100.25",
			RemotePort: 8448,
			ScionDstIP: "198.51.100.25",
		},
		{
			ID:         3,
			IPVersion:  4,
			Protocol:   17, // UDP (DNS / WireGuard)
			EndpointA:  MockEndpoint{Address: "192.168.1.100", Port: 53535},
			EndpointB:  MockEndpoint{Address: "1.1.1.1", Port: 53},
			Status:     "active",
			TxPackets:  ipTx / 250,
			TxBytes:    ipTx,
			RxPackets:  ipRx / 350,
			RxBytes:    ipRx,
			EgressKind: "ip",
			CreatedAt:  m.startTime.Format(time.RFC3339),
			LastSeen:   time.Now().Format(time.RFC3339),
			LocalIP:    "192.168.1.100",
			LocalPort:  53535,
			RemoteIP:   "1.1.1.1",
			RemotePort: 53,
		},
	}

	resp := MockFlowListResponse{Flows: flows}
	data, _ := json.Marshal(resp)
	return string(data)
}

// SCIONPathsForFlowJSON returns rich path candidates for a flow
func (m *MockDevice) SCIONPathsForFlowJSON(flowID int64) string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	// Empty scenario or non-SCION flow
	if m.scenario == ScenarioEmptyFlows || flowID == 3 || (flowID != 1 && flowID != 2) {
		resp := MockFlowPathsResponse{
			FlowID: flowID,
			State:  "empty",
			Paths:  []MockFlowPathDTO{},
		}
		data, _ := json.Marshal(resp)
		return string(data)
	}

	// Pending discovery simulation: first 2 requests return "pending", subsequent return "ready"
	if m.scenario == ScenarioPendingDiscovery {
		m.mu.RUnlock()
		m.mu.Lock()
		m.pendingCounts[flowID]++
		cnt := m.pendingCounts[flowID]
		m.mu.Unlock()
		m.mu.RLock()

		if cnt <= 2 {
			resp := MockFlowPathsResponse{
				FlowID: flowID,
				State:  "pending",
				Paths:  []MockFlowPathDTO{},
			}
			data, _ := json.Marshal(resp)
			return string(data)
		}
	}

	latScale := 1.0
	if m.scenario == ScenarioHighLatency {
		latScale = 15.0
	}

	var paths []MockFlowPathDTO
	policyWinner := "fp_zurich_direct"
	policyName := "LowestLatency"
	policyMode := "default"
	policyFallback := false

	if flowID == 1 {
		latZurich := 12.4 * latScale
		latFrankfurt := 28.5 * latScale
		latGeneva := 32.0 * latScale
		latGlobe := 185.0 * latScale

		paths = []MockFlowPathDTO{
			{
				Fingerprint:        "fp_zurich_direct",
				Display:            "Zurich Direct [64-2:0:49 ➔ 64-1:0:12]",
				NextHop:            "192.168.1.1:30042",
				Expiry:             time.Now().Add(4 * time.Hour).Format(time.RFC3339),
				MTU:                1472,
				Interfaces:         []string{"1", "2"},
				LatencyMs:          []float64{latZurich / 2, latZurich / 2},
				Bandwidth:          []int64{100_000_000, 100_000_000},
				LatencyMicros:      []int64{int64(latZurich * 500), int64(latZurich * 500)},
				BandwidthKbps:      []int64{100_000, 100_000},
				TotalLatencyMicros: int64(latZurich * 1000),
				LatencyComplete:    true,
				BottleneckKbps:     100_000,
				BandwidthComplete:  true,
				InterAsLinks:       1,
				LinkType:           []string{"direct"},
				InternalHops:       []int{1},
				Geo: []MockGeoDTO{
					{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
					{Latitude: 47.5596, Longitude: 7.5886, Address: "Basel, Switzerland"},
				},
			},
			{
				Fingerprint:        "fp_frankfurt_transit",
				Display:            "Frankfurt High-Speed [64-2:0:49 ➔ 64-2:0:50 ➔ 64-1:0:12]",
				NextHop:            "192.168.1.1:30042",
				Expiry:             time.Now().Add(3 * time.Hour).Format(time.RFC3339),
				MTU:                1472,
				Interfaces:         []string{"2", "1", "3"},
				LatencyMs:          []float64{latFrankfurt / 3, latFrankfurt / 3, latFrankfurt / 3},
				Bandwidth:          []int64{10_000_000_000, 10_000_000_000, 10_000_000_000},
				LatencyMicros:      []int64{int64(latFrankfurt * 333), int64(latFrankfurt * 333), int64(latFrankfurt * 334)},
				BandwidthKbps:      []int64{10_000_000, 10_000_000, 10_000_000},
				TotalLatencyMicros: int64(latFrankfurt * 1000),
				LatencyComplete:    true,
				BottleneckKbps:     10_000_000, // 10 Gbps -> wins HIGHEST_BANDWIDTH
				BandwidthComplete:  true,
				InterAsLinks:       2,
				LinkType:           []string{"transit", "transit"},
				InternalHops:       []int{2, 1},
				Geo: []MockGeoDTO{
					{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
					{Latitude: 50.1109, Longitude: 8.6821, Address: "Frankfurt, Germany"},
					{Latitude: 47.5596, Longitude: 7.5886, Address: "Basel, Switzerland"},
				},
			},
			{
				Fingerprint:        "fp_geneva_direct",
				Display:            "Geneva Express [64-2:0:49 ➔ 64-1:0:12]",
				NextHop:            "192.168.1.1:30042",
				Expiry:             time.Now().Add(2 * time.Hour).Format(time.RFC3339),
				MTU:                1472,
				Interfaces:         []string{"3", "1"},
				LatencyMs:          []float64{latGeneva / 2, latGeneva / 2},
				Bandwidth:          []int64{500_000_000, 500_000_000},
				LatencyMicros:      []int64{int64(latGeneva * 500), int64(latGeneva * 500)},
				BandwidthKbps:      []int64{500_000, 500_000},
				TotalLatencyMicros: int64(latGeneva * 1000),
				LatencyComplete:    true,
				BottleneckKbps:     500_000,
				BandwidthComplete:  true,
				InterAsLinks:       1,
				LinkType:           []string{"direct"},
				InternalHops:       []int{0}, // 0 internal hops -> wins SHORTEST_PATH
				Geo: []MockGeoDTO{
					{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
					{Latitude: 46.2044, Longitude: 6.1432, Address: "Geneva, Switzerland"},
					{Latitude: 47.5596, Longitude: 7.5886, Address: "Basel, Switzerland"},
				},
			},
			{
				Fingerprint:        "fp_transatlantic_globe",
				Display:            "Global Transatlantic [64-2:0:49 ➔ London ➔ NYC ➔ Tokyo ➔ 64-1:0:12]",
				NextHop:            "192.168.1.1:30042",
				Expiry:             time.Now().Add(6 * time.Hour).Format(time.RFC3339),
				MTU:                1500,
				Interfaces:         []string{"1", "5", "8", "9", "2"},
				LatencyMs:          []float64{latGlobe * 0.2, latGlobe * 0.35, latGlobe * 0.3, latGlobe * 0.15},
				Bandwidth:          []int64{1_000_000_000, 1_000_000_000, 1_000_000_000, 1_000_000_000},
				LatencyMicros:      []int64{35000, 65000, 55000, 30000},
				BandwidthKbps:      []int64{1_000_000, 1_000_000, 1_000_000, 1_000_000},
				TotalLatencyMicros: int64(latGlobe * 1000),
				LatencyComplete:    true,
				BottleneckKbps:     1_000_000,
				BandwidthComplete:  true,
				InterAsLinks:       4,
				LinkType:           []string{"transit", "core", "core", "transit"},
				InternalHops:       []int{1, 2, 2, 1},
				Geo: []MockGeoDTO{
					{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
					{Latitude: 51.5074, Longitude: -0.1278, Address: "London, UK"},
					{Latitude: 40.7128, Longitude: -74.0060, Address: "New York, USA"},
					{Latitude: 35.6762, Longitude: 139.6503, Address: "Tokyo, Japan"},
					{Latitude: 47.5596, Longitude: 7.5886, Address: "Basel, Switzerland"},
				},
			},
		}

		if m.scenario == ScenarioPolicyFallback {
			policyName = "HighBandwidthFailover"
			policyMode = "configured"
			policyFallback = true
			policyWinner = "fp_frankfurt_transit"
		} else if m.scenario == ScenarioGlobeShowcase {
			policyName = "GlobalIntercontinental"
			policyMode = "configured"
			policyWinner = "fp_transatlantic_globe"
		}
	} else if flowID == 2 {
		policyWinner = "fp_tokyo_primary"
		policyName = "DirectAsia"
		policyMode = "default"

		paths = []MockFlowPathDTO{
			{
				Fingerprint:        "fp_tokyo_primary",
				Display:            "Tokyo Direct [64-2:0:49 ➔ 64-3:0:88]",
				NextHop:            "192.168.1.1:30042",
				Expiry:             time.Now().Add(5 * time.Hour).Format(time.RFC3339),
				MTU:                1472,
				Interfaces:         []string{"1", "2"},
				LatencyMs:          []float64{80.0, 80.0},
				Bandwidth:          []int64{1_000_000_000, 1_000_000_000},
				LatencyMicros:      []int64{80000, 80000},
				BandwidthKbps:      []int64{1_000_000, 1_000_000},
				TotalLatencyMicros: 160000,
				LatencyComplete:    true,
				BottleneckKbps:     1_000_000,
				BandwidthComplete:  true,
				InterAsLinks:       1,
				LinkType:           []string{"direct"},
				InternalHops:       []int{1},
				Geo: []MockGeoDTO{
					{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
					{Latitude: 35.6762, Longitude: 139.6503, Address: "Tokyo, Japan"},
				},
			},
			{
				Fingerprint:        "fp_tokyo_singapore",
				Display:            "Tokyo via Singapore [64-2:0:49 ➔ Singapore ➔ 64-3:0:88]",
				NextHop:            "192.168.1.1:30042",
				Expiry:             time.Now().Add(3 * time.Hour).Format(time.RFC3339),
				MTU:                1472,
				Interfaces:         []string{"1", "4", "2"},
				LatencyMs:          []float64{110.0, 100.0},
				Bandwidth:          []int64{2_500_000_000, 2_500_000_000},
				LatencyMicros:      []int64{110000, 100000},
				BandwidthKbps:      []int64{2_500_000, 2_500_000},
				TotalLatencyMicros: 210000,
				LatencyComplete:    true,
				BottleneckKbps:     2_500_000,
				BandwidthComplete:  true,
				InterAsLinks:       2,
				LinkType:           []string{"transit", "transit"},
				InternalHops:       []int{2, 1},
				Geo: []MockGeoDTO{
					{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
					{Latitude: 1.3521, Longitude: 103.8198, Address: "Singapore"},
					{Latitude: 35.6762, Longitude: 139.6503, Address: "Tokyo, Japan"},
				},
			},
		}
	}

	overrideFp := m.activeOverrides[flowID]
	overrideState := ""
	if overrideFp != "" {
		if m.scenario == ScenarioStaleOverride {
			overrideState = "stale"
		} else {
			overrideState = "active"
		}
	}

	effectiveFp := policyWinner
	if overrideFp != "" && overrideState == "active" {
		effectiveFp = overrideFp
	}

	for i := range paths {
		paths[i].Current = (paths[i].Fingerprint == effectiveFp)
	}

	resp := MockFlowPathsResponse{
		FlowID:                flowID,
		State:                 "ready",
		Paths:                 paths,
		PolicyName:            policyName,
		PolicyMode:            policyMode,
		PolicyFallbackApplied: policyFallback,
		OverrideState:         overrideState,
		OverrideFingerprint:   overrideFp,
		EffectiveFingerprint:  effectiveFp,
	}

	data, _ := json.Marshal(resp)
	return string(data)
}

// SetFlowPathOverride updates active path override
func (m *MockDevice) SetFlowPathOverride(flowID int64, fingerprint string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if fingerprint == "" {
		return fmt.Errorf("empty fingerprint")
	}
	m.activeOverrides[flowID] = fingerprint
	m.overrideTimestamp[flowID] = time.Now()
	return nil
}

// ClearFlowPathOverride clears path override for flow
func (m *MockDevice) ClearFlowPathOverride(flowID int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	delete(m.activeOverrides, flowID)
	delete(m.overrideTimestamp, flowID)
	return nil
}
