/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package main

const (
	ScenarioDefaultMultiPath = "default"
	ScenarioGlobeShowcase    = "globe_showcase"
	ScenarioPolicyFallback   = "policy_fallback"
	ScenarioPendingDiscovery = "pending"
	ScenarioStaleOverride    = "stale_override"
	ScenarioMultiFlow        = "multi_flow"
	ScenarioHighLatency      = "high_latency"
	ScenarioEmptyFlows       = "empty"
)

// MockEndpoint represents flow endpoints
type MockEndpoint struct {
	Address string `json:"address"`
	Port    int    `json:"port"`
}

// MockFlowDTO represents an active or tracked flow
type MockFlowDTO struct {
	ID          int64        `json:"id"`
	IPVersion   int          `json:"ipVersion"`
	Protocol    int          `json:"protocol"`
	EndpointA   MockEndpoint `json:"endpointA"`
	EndpointB   MockEndpoint `json:"endpointB"`
	Status      string       `json:"status"`
	TxPackets   int64        `json:"txPackets"`
	TxBytes     int64        `json:"txBytes"`
	RxPackets   int64        `json:"rxPackets"`
	RxBytes     int64        `json:"rxBytes"`
	EgressKind  string       `json:"egressKind"`
	SrcIA       string       `json:"srcIA,omitempty"`
	DstIA       string       `json:"dstIA,omitempty"`
	CreatedAt   string       `json:"createdAt,omitempty"`
	LastSeen    string       `json:"lastSeen,omitempty"`
	LocalIP     string       `json:"localIP,omitempty"`
	LocalPort   int          `json:"localPort,omitempty"`
	RemoteIP    string       `json:"remoteIP,omitempty"`
	RemotePort  int          `json:"remotePort,omitempty"`
	ScionDstIP  string       `json:"scionDstIP,omitempty"`
}

// MockFlowListResponse represents the response for wgGetFlows
type MockFlowListResponse struct {
	Flows []MockFlowDTO `json:"flows"`
	Error string        `json:"error,omitempty"`
}

// MockGeoDTO represents geolocation of hops
type MockGeoDTO struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	Address   string  `json:"address,omitempty"`
}

// MockFlowPathDTO represents a single path option
type MockFlowPathDTO struct {
	Fingerprint        string       `json:"fingerprint"`
	Display            string       `json:"display"`
	Current            bool         `json:"current"`
	NextHop            string       `json:"nextHop,omitempty"`
	Expiry             string       `json:"expiry,omitempty"`
	MTU                int          `json:"mtu,omitempty"`
	Interfaces         []string     `json:"interfaces,omitempty"`
	LatencyMs          []float64    `json:"latencyMs,omitempty"`
	Bandwidth          []int64      `json:"bandwidth,omitempty"`
	Geo                []MockGeoDTO `json:"geo,omitempty"`
	LinkType           []string     `json:"linkType,omitempty"`
	InternalHops       []int        `json:"internalHops,omitempty"`
	Notes              []string     `json:"notes,omitempty"`
	LatencyMicros      []int64      `json:"latencyMicros,omitempty"`
	BandwidthKbps      []int64      `json:"bandwidthKbps,omitempty"`
	TotalLatencyMicros int64        `json:"totalLatencyMicros,omitempty"`
	LatencyComplete    bool         `json:"latencyComplete,omitempty"`
	BottleneckKbps     int64        `json:"bottleneckKbps,omitempty"`
	BandwidthComplete  bool         `json:"bandwidthComplete,omitempty"`
	InterAsLinks       int          `json:"interAsLinks,omitempty"`
}

// MockFlowPathsResponse represents the response for wgGetFlowPaths
type MockFlowPathsResponse struct {
	FlowID                 int64             `json:"flowId"`
	State                  string            `json:"state"` // "ready", "pending", "empty", "error"
	Paths                  []MockFlowPathDTO `json:"paths"`
	Error                  string            `json:"error,omitempty"`
	PolicyName             string            `json:"policyName,omitempty"`
	PolicyMode             string            `json:"policyMode,omitempty"`
	PolicyFallbackApplied  bool              `json:"policyFallbackApplied"`
	OverrideState          string            `json:"overrideState,omitempty"` // "active", "stale"
	OverrideFingerprint    string            `json:"overrideFingerprint,omitempty"`
	EffectiveFingerprint   string            `json:"effectiveFingerprint,omitempty"`
}

// MockSCIONInfo represents local SCION configuration
type MockSCIONInfo struct {
	LocalIA   string `json:"localIA"`
	LocalIPv4 string `json:"localIPv4,omitempty"`
	LocalIPv6 string `json:"localIPv6,omitempty"`
	BrAddr    string `json:"brAddr,omitempty"`
	PortRange string `json:"portRange,omitempty"`
}

// MockScionPathSnapshotItem represents an item in the SCION topology snapshot
type MockScionPathSnapshotItem struct {
	Fingerprint string   `json:"fingerprint"`
	NextHop     string   `json:"nextHop,omitempty"`
	Expiry      string   `json:"expiry,omitempty"`
	MTU         int      `json:"mtu,omitempty"`
	Hops        []string `json:"hops"`
	IsSelected  bool     `json:"isSelected"`
}

// MockScionIaPairSnapshot represents an ISD-AS pair snapshot
type MockScionIaPairSnapshot struct {
	SrcIa                string                      `json:"srcIa"`
	DstIa                string                      `json:"dstIa"`
	SelectedFingerprint  string                      `json:"selectedFingerprint,omitempty"`
	AvailablePathsCount  int                         `json:"availablePathsCount"`
	Paths                []MockScionPathSnapshotItem `json:"paths"`
}

// MockScionPathCacheSnapshot represents the full snapshot JSON
type MockScionPathCacheSnapshot struct {
	Strategy    string                    `json:"strategy"`
	LastRefresh string                    `json:"lastRefresh"`
	Pairs       []MockScionIaPairSnapshot `json:"pairs"`
}
