// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/flow"
)

func TestBuildNewFlowEventsJSONEmpty(t *testing.T) {
	got := buildNewFlowEventsJSON(nil, 0, "")
	if got != `{"events":[],"dropped":0}` {
		t.Fatalf("got %s", got)
	}
}

func TestBuildNewFlowEventsJSONWithEvents(t *testing.T) {
	events := []flow.NewFlowEvent{{
		FlowID: 42, Protocol: 6,
		LocalIP: "10.0.0.2", LocalPort: 51234,
		RemoteIP: "142.250.1.1", RemotePort: 443,
	}}
	raw := buildNewFlowEventsJSON(events, 3, "")

	var decoded newFlowEventsResponse
	if err := json.Unmarshal([]byte(raw), &decoded); err != nil {
		t.Fatalf("invalid JSON %s: %v", raw, err)
	}
	if len(decoded.Events) != 1 || decoded.Events[0] != events[0] || decoded.Dropped != 3 || decoded.Error != "" {
		t.Fatalf("round trip mismatch: %+v", decoded)
	}
}

func TestBuildNewFlowEventsJSONError(t *testing.T) {
	got := buildNewFlowEventsJSON(nil, 0, "device_not_found")
	if got != `{"events":[],"dropped":0,"error":"device_not_found"}` {
		t.Fatalf("got %s", got)
	}
}

func TestNewFlowWaitParams(t *testing.T) {
	cases := []struct {
		timeoutMs, max int32
		wantTimeout    time.Duration
		wantMax        int
	}{
		{0, 0, defaultNewFlowWaitTimeout, defaultNewFlowMaxEvents},
		{-1, -1, defaultNewFlowWaitTimeout, defaultNewFlowMaxEvents},
		{250, 10, 250 * time.Millisecond, 10},
		{60000, 10, maxNewFlowWaitTimeout, 10},
	}
	for _, c := range cases {
		timeout, max := newFlowWaitParams(c.timeoutMs, c.max)
		if timeout != c.wantTimeout || max != c.wantMax {
			t.Errorf("newFlowWaitParams(%d, %d) = %v, %d; want %v, %d", c.timeoutMs, c.max, timeout, max, c.wantTimeout, c.wantMax)
		}
	}
}
