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
	scionTx := 1_250_000 + elapsedSec*24_500
	scionRx := 3_800_000 + elapsedSec*78_200
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
			TxPackets:  scionTx / 1200,
			TxBytes:    scionTx,
			RxPackets:  scionRx / 1400,
			RxBytes:    scionRx,
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

	if flowID != 1 {
		// Non-SCION flow or flow with no SCION paths
		resp := MockFlowPathsResponse{
			FlowID: flowID,
			State:  "empty",
			Paths:  []MockFlowPathDTO{},
		}
		data, _ := json.Marshal(resp)
		return string(data)
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

	effectiveFp := "fp_zurich_direct"
	if overrideFp != "" && overrideState == "active" {
		effectiveFp = overrideFp
	}

	latZurich := 12.4
	latFrankfurt := 21.8
	latGeneva := 34.2
	if m.scenario == ScenarioHighLatency {
		latZurich = 345.8
		latFrankfurt = 290.4
	}

	paths := []MockFlowPathDTO{
		{
			Fingerprint:        "fp_zurich_direct",
			Display:            "Zurich Direct [64-2:0:49 ➔ 64-1:0:12]",
			Current:            effectiveFp == "fp_zurich_direct",
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
			Display:            "Frankfurt Transit [64-2:0:49 ➔ 64-2:0:50 ➔ 64-1:0:12]",
			Current:            effectiveFp == "fp_frankfurt_transit",
			NextHop:            "192.168.1.1:30042",
			Expiry:             time.Now().Add(3 * time.Hour).Format(time.RFC3339),
			MTU:                1472,
			Interfaces:         []string{"2", "1", "3"},
			LatencyMs:          []float64{latFrankfurt / 3, latFrankfurt / 3, latFrankfurt / 3},
			Bandwidth:          []int64{500_000_000, 200_000_000, 500_000_000},
			LatencyMicros:      []int64{int64(latFrankfurt * 333), int64(latFrankfurt * 333), int64(latFrankfurt * 333)},
			BandwidthKbps:      []int64{500_000, 200_000, 500_000},
			TotalLatencyMicros: int64(latFrankfurt * 1000),
			LatencyComplete:    true,
			BottleneckKbps:     200_000,
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
			Fingerprint:        "fp_geneva_backup",
			Display:            "Geneva Backup [64-2:0:49 ➔ 64-2:0:55 ➔ 64-1:0:12]",
			Current:            effectiveFp == "fp_geneva_backup",
			NextHop:            "192.168.1.1:30042",
			Expiry:             time.Now().Add(2 * time.Hour).Format(time.RFC3339),
			MTU:                1280,
			Interfaces:         []string{"3", "4", "1"},
			LatencyMs:          []float64{latGeneva / 3, latGeneva / 3, latGeneva / 3},
			Bandwidth:          []int64{50_000_000, 50_000_000, 50_000_000},
			LatencyMicros:      []int64{int64(latGeneva * 333), int64(latGeneva * 333), int64(latGeneva * 333)},
			BandwidthKbps:      []int64{50_000, 50_000, 50_000},
			TotalLatencyMicros: int64(latGeneva * 1000),
			LatencyComplete:    true,
			BottleneckKbps:     50_000,
			BandwidthComplete:  true,
			InterAsLinks:       2,
			LinkType:           []string{"backup", "transit"},
			InternalHops:       []int{1, 1},
			Geo: []MockGeoDTO{
				{Latitude: 47.3769, Longitude: 8.5417, Address: "Zurich, Switzerland"},
				{Latitude: 46.2044, Longitude: 6.1432, Address: "Geneva, Switzerland"},
				{Latitude: 47.5596, Longitude: 7.5886, Address: "Basel, Switzerland"},
			},
		},
	}

	resp := MockFlowPathsResponse{
		FlowID:                flowID,
		State:                 "ready",
		Paths:                 paths,
		PolicyName:            "LowestLatency",
		PolicyMode:            "automatic",
		PolicyFallbackApplied: false,
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
