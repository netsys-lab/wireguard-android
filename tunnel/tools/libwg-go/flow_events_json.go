// SPDX-License-Identifier: Apache-2.0
//
// Pure-Go helpers for the new-flow event bridge (wgWaitNewFlows), separated
// from cgo so they can be unit-tested on any platform.

package main

import (
	"encoding/json"
	"time"

	"golang.zx2c4.com/wireguard/flow"
)

const (
	// defaultNewFlowWaitTimeout is used when Java passes a non-positive timeout.
	defaultNewFlowWaitTimeout = 500 * time.Millisecond
	// maxNewFlowWaitTimeout caps the blocking time of one wgWaitNewFlows call,
	// so a stopping tunnel is noticed quickly.
	maxNewFlowWaitTimeout = 5 * time.Second
	// defaultNewFlowMaxEvents is used when Java passes a non-positive maximum.
	defaultNewFlowMaxEvents = 256
)

// newFlowEventsResponse is the JSON object returned by wgWaitNewFlows.
//
//	{"events":[{"flowId":1,"protocol":6,"localIP":"…","localPort":…,"remoteIP":"…","remotePort":…}],
//	 "dropped":0,"error":"device_not_found"}
type newFlowEventsResponse struct {
	Events  []flow.NewFlowEvent `json:"events"`
	Dropped uint64              `json:"dropped"`
	Error   string              `json:"error,omitempty"`
}

// buildNewFlowEventsJSON serializes the result of one wgWaitNewFlows call.
// "events" is always an array, never null.
func buildNewFlowEventsJSON(events []flow.NewFlowEvent, dropped uint64, errMsg string) string {
	if events == nil {
		events = []flow.NewFlowEvent{}
	}
	raw, err := json.Marshal(newFlowEventsResponse{Events: events, Dropped: dropped, Error: errMsg})
	if err != nil {
		return `{"events":[],"dropped":0,"error":"serialization_failed"}`
	}
	return string(raw)
}

// newFlowWaitParams normalizes the timeout (milliseconds) and maximum number
// of events passed in from Java.
func newFlowWaitParams(timeoutMs, maxEvents int32) (time.Duration, int) {
	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = defaultNewFlowWaitTimeout
	}
	if timeout > maxNewFlowWaitTimeout {
		timeout = maxNewFlowWaitTimeout
	}
	max := int(maxEvents)
	if max <= 0 {
		max = defaultNewFlowMaxEvents
	}
	return timeout, max
}
