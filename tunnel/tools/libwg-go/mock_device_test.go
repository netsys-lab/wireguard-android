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
