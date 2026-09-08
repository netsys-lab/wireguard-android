/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package main

import (
	"encoding/json"
	"testing"
)

func TestMockDevice_SCIONInfoJSON(t *testing.T) {
	mock := NewMockDevice(ScenarioDefaultMultiPath)
	infoJSON := mock.SCIONInfoJSON()

	var info MockSCIONInfo
	if err := json.Unmarshal([]byte(infoJSON), &info); err != nil {
		t.Fatalf("Failed to parse SCIONInfo JSON: %v", err)
	}

	if info.LocalIA != "64-2:0:49" {
		t.Errorf("Expected LocalIA 64-2:0:49, got %s", info.LocalIA)
	}
	if info.LocalIPv4 == "" {
		t.Errorf("Expected non-empty LocalIPv4")
	}
}

func TestMockDevice_FlowSnapshotsJSON(t *testing.T) {
	mock := NewMockDevice(ScenarioDefaultMultiPath)
	flowsJSON := mock.FlowSnapshotsJSON()

	var resp MockFlowListResponse
	if err := json.Unmarshal([]byte(flowsJSON), &resp); err != nil {
		t.Fatalf("Failed to parse FlowSnapshots JSON: %v", err)
	}

	if len(resp.Flows) == 0 {
		t.Fatalf("Expected flows in default scenario, got 0")
	}

	scionFlow := resp.Flows[0]
	if scionFlow.EgressKind != "scion" {
		t.Errorf("Expected egressKind scion, got %s", scionFlow.EgressKind)
	}
	if scionFlow.TxBytes <= 0 || scionFlow.RxBytes <= 0 {
		t.Errorf("Expected non-zero traffic bytes")
	}
}

func TestMockDevice_PathOverrideCycle(t *testing.T) {
	mock := NewMockDevice(ScenarioDefaultMultiPath)

	// Check default paths for flow 1
	pathsJSON := mock.SCIONPathsForFlowJSON(1)
	var resp MockFlowPathsResponse
	if err := json.Unmarshal([]byte(pathsJSON), &resp); err != nil {
		t.Fatalf("Failed to parse paths JSON: %v", err)
	}
	if resp.EffectiveFingerprint != "fp_zurich_direct" {
		t.Errorf("Expected default effective fingerprint fp_zurich_direct, got %s", resp.EffectiveFingerprint)
	}

	// Apply override
	if err := mock.SetFlowPathOverride(1, "fp_frankfurt_transit"); err != nil {
		t.Fatalf("Failed to set override: %v", err)
	}

	pathsJSON2 := mock.SCIONPathsForFlowJSON(1)
	var resp2 MockFlowPathsResponse
	if err := json.Unmarshal([]byte(pathsJSON2), &resp2); err != nil {
		t.Fatalf("Failed to parse updated paths JSON: %v", err)
	}
	if resp2.EffectiveFingerprint != "fp_frankfurt_transit" {
		t.Errorf("Expected effective fingerprint fp_frankfurt_transit, got %s", resp2.EffectiveFingerprint)
	}
	if resp2.OverrideState != "active" {
		t.Errorf("Expected overrideState active, got %s", resp2.OverrideState)
	}

	// Clear override
	if err := mock.ClearFlowPathOverride(1); err != nil {
		t.Fatalf("Failed to clear override: %v", err)
	}

	pathsJSON3 := mock.SCIONPathsForFlowJSON(1)
	var resp3 MockFlowPathsResponse
	if err := json.Unmarshal([]byte(pathsJSON3), &resp3); err != nil {
		t.Fatalf("Failed to parse cleared paths JSON: %v", err)
	}
	if resp3.EffectiveFingerprint != "fp_zurich_direct" {
		t.Errorf("Expected effective fingerprint reset to fp_zurich_direct, got %s", resp3.EffectiveFingerprint)
	}
	if resp3.OverrideState != "" {
		t.Errorf("Expected empty overrideState after clearing, got %s", resp3.OverrideState)
	}
}

func TestMockDevice_EmptyScenario(t *testing.T) {
	mock := NewMockDevice(ScenarioEmptyFlows)
	flowsJSON := mock.FlowSnapshotsJSON()

	var resp MockFlowListResponse
	if err := json.Unmarshal([]byte(flowsJSON), &resp); err != nil {
		t.Fatalf("Failed to parse empty flows JSON: %v", err)
	}
	if len(resp.Flows) != 0 {
		t.Errorf("Expected 0 flows in empty scenario, got %d", len(resp.Flows))
	}
}

func TestMockDevice_PolicyFallback(t *testing.T) {
	mock := NewMockDevice(ScenarioPolicyFallback)
	pathsJSON := mock.SCIONPathsForFlowJSON(1)

	var resp MockFlowPathsResponse
	if err := json.Unmarshal([]byte(pathsJSON), &resp); err != nil {
		t.Fatalf("Failed to parse paths JSON: %v", err)
	}
	if !resp.PolicyFallbackApplied {
		t.Errorf("Expected PolicyFallbackApplied to be true")
	}
	if resp.PolicyMode != "configured" {
		t.Errorf("Expected policyMode configured, got %s", resp.PolicyMode)
	}
	if resp.EffectiveFingerprint != "fp_frankfurt_transit" {
		t.Errorf("Expected fallback winner fp_frankfurt_transit, got %s", resp.EffectiveFingerprint)
	}
}

func TestMockDevice_GlobeShowcase(t *testing.T) {
	mock := NewMockDevice(ScenarioGlobeShowcase)
	pathsJSON := mock.SCIONPathsForFlowJSON(1)

	var resp MockFlowPathsResponse
	if err := json.Unmarshal([]byte(pathsJSON), &resp); err != nil {
		t.Fatalf("Failed to parse paths JSON: %v", err)
	}
	if resp.EffectiveFingerprint != "fp_transatlantic_globe" {
		t.Errorf("Expected globe winner fp_transatlantic_globe, got %s", resp.EffectiveFingerprint)
	}

	foundGlobe := false
	for _, p := range resp.Paths {
		if p.Fingerprint == "fp_transatlantic_globe" {
			foundGlobe = true
			if len(p.Geo) < 4 {
				t.Errorf("Expected at least 4 geo hops for globe path, got %d", len(p.Geo))
			}
		}
	}
	if !foundGlobe {
		t.Errorf("Expected to find fp_transatlantic_globe path")
	}
}

func TestMockDevice_PendingDiscovery(t *testing.T) {
	mock := NewMockDevice(ScenarioPendingDiscovery)

	// Poll 1: should be pending
	p1 := mock.SCIONPathsForFlowJSON(1)
	var r1 MockFlowPathsResponse
	_ = json.Unmarshal([]byte(p1), &r1)
	if r1.State != "pending" {
		t.Errorf("Expected poll 1 to be pending, got %s", r1.State)
	}

	// Poll 2: should be pending
	p2 := mock.SCIONPathsForFlowJSON(1)
	var r2 MockFlowPathsResponse
	_ = json.Unmarshal([]byte(p2), &r2)
	if r2.State != "pending" {
		t.Errorf("Expected poll 2 to be pending, got %s", r2.State)
	}

	// Poll 3: should transition to ready
	p3 := mock.SCIONPathsForFlowJSON(1)
	var r3 MockFlowPathsResponse
	_ = json.Unmarshal([]byte(p3), &r3)
	if r3.State != "ready" {
		t.Errorf("Expected poll 3 to be ready, got %s", r3.State)
	}
	if len(r3.Paths) == 0 {
		t.Errorf("Expected paths when ready")
	}
}

func TestMockDevice_MultiFlow(t *testing.T) {
	mock := NewMockDevice(ScenarioMultiFlow)
	flowsJSON := mock.FlowSnapshotsJSON()

	var resp MockFlowListResponse
	_ = json.Unmarshal([]byte(flowsJSON), &resp)
	if len(resp.Flows) < 3 {
		t.Fatalf("Expected at least 3 flows in multi_flow scenario, got %d", len(resp.Flows))
	}

	// Check Flow 2 (Tokyo) paths
	p2 := mock.SCIONPathsForFlowJSON(2)
	var r2 MockFlowPathsResponse
	_ = json.Unmarshal([]byte(p2), &r2)
	if r2.State != "ready" || len(r2.Paths) < 2 {
		t.Errorf("Expected ready paths for flow 2, got state=%s, count=%d", r2.State, len(r2.Paths))
	}
	if r2.EffectiveFingerprint != "fp_tokyo_primary" {
		t.Errorf("Expected fp_tokyo_primary for flow 2, got %s", r2.EffectiveFingerprint)
	}
}

func TestMockDevice_OverrideAllCandidates(t *testing.T) {
	mock := NewMockDevice(ScenarioDefaultMultiPath)
	candidates := []string{
		"fp_zurich_direct",
		"fp_frankfurt_transit",
		"fp_geneva_direct",
		"fp_transatlantic_globe",
	}

	for _, candidate := range candidates {
		if err := mock.SetFlowPathOverride(1, candidate); err != nil {
			t.Fatalf("Failed to set override %s: %v", candidate, err)
		}
		raw := mock.SCIONPathsForFlowJSON(1)
		var resp MockFlowPathsResponse
		if err := json.Unmarshal([]byte(raw), &resp); err != nil {
			t.Fatalf("Failed to parse JSON: %v", err)
		}
		if resp.EffectiveFingerprint != candidate {
			t.Errorf("Override %s: expected EffectiveFingerprint=%s, got %s", candidate, candidate, resp.EffectiveFingerprint)
		}
		if resp.OverrideState != "active" {
			t.Errorf("Override %s: expected OverrideState=active, got %s", candidate, resp.OverrideState)
		}
		foundCurrent := false
		for _, p := range resp.Paths {
			if p.Current {
				foundCurrent = true
				if p.Fingerprint != candidate {
					t.Errorf("Override %s: path marked current has fingerprint %s", candidate, p.Fingerprint)
				}
			}
		}
		if !foundCurrent {
			t.Errorf("Override %s: no path marked Current", candidate)
		}
	}
}

func TestMockDevice_UnknownOverrideFallback(t *testing.T) {
	mock := NewMockDevice(ScenarioDefaultMultiPath)
	if err := mock.SetFlowPathOverride(1, "fp_non_existent_xyz"); err != nil {
		t.Fatalf("Failed to set override: %v", err)
	}
	raw := mock.SCIONPathsForFlowJSON(1)
	var resp MockFlowPathsResponse
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("Failed to parse JSON: %v", err)
	}
	// Since fp_non_existent_xyz is not in paths, overrideState should be stale and effective should fall back to policyWinner
	if resp.OverrideState != "stale" {
		t.Errorf("Expected stale override state for unknown fingerprint, got %s", resp.OverrideState)
	}
	if resp.EffectiveFingerprint != "fp_zurich_direct" {
		t.Errorf("Expected effective fingerprint fallback to fp_zurich_direct, got %s", resp.EffectiveFingerprint)
	}
}
