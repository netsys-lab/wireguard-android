/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.model

import com.wireguard.android.util.ScionPathSnapshotRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoMockBackendJsonTest {

    @Test
    fun testParseMockSCIONInfo() {
        val json = """
            {
                "localIA": "64-2:0:49",
                "localIPv4": "192.168.1.100",
                "localIPv6": "fd00:f00d::1",
                "brAddr": "192.168.1.1:30042",
                "portRange": "30042-30045"
            }
        """.trimIndent()

        val info = parseSCIONInfo(json)
        assertEquals("64-2:0:49", info.localIA)
        assertEquals("192.168.1.100", info.localIPv4)
        assertEquals("fd00:f00d::1", info.localIPv6)
        assertEquals("192.168.1.1:30042", info.brAddr)
        assertEquals("30042-30045", info.portRange)
    }

    @Test
    fun testParseMockFlows() {
        val json = """
            {
                "flows": [
                    {
                        "id": 1,
                        "ipVersion": 4,
                        "protocol": 6,
                        "endpointA": {"address": "192.168.1.100", "port": 51234},
                        "endpointB": {"address": "198.51.100.10", "port": 443},
                        "status": "active",
                        "txPackets": 1041,
                        "txBytes": 1250000,
                        "rxPackets": 2714,
                        "rxBytes": 3800000,
                        "egressKind": "scion",
                        "srcIA": "64-2:0:49",
                        "dstIA": "64-1:0:12",
                        "createdAt": "2026-08-17T12:00:00Z",
                        "lastSeen": "2026-08-17T12:05:00Z",
                        "localIP": "192.168.1.100",
                        "localPort": 51234,
                        "remoteIP": "198.51.100.10",
                        "remotePort": 443,
                        "scionDstIP": "198.51.100.10"
                    }
                ]
            }
        """.trimIndent()

        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        val flow = response.flows[0]
        assertEquals(1L, flow.id)
        assertEquals(FlowEgressKind.SCION, flow.egressKindEnum)
        assertEquals("64-2:0:49", flow.srcIA)
        assertEquals("64-1:0:12", flow.dstIA)
        assertTrue(flow.txBytes > 0)
    }

    @Test
    fun testParseMockFlowPaths() {
        val json = """
            {
                "flowId": 1,
                "state": "ready",
                "policyName": "LowestLatency",
                "policyMode": "automatic",
                "policyFallbackApplied": false,
                "overrideState": "active",
                "overrideFingerprint": "fp_frankfurt_transit",
                "effectiveFingerprint": "fp_frankfurt_transit",
                "paths": [
                    {
                        "fingerprint": "fp_zurich_direct",
                        "display": "Zurich Direct [64-2:0:49 ➔ 64-1:0:12]",
                        "current": false,
                        "nextHop": "192.168.1.1:30042",
                        "expiry": "2026-08-17T16:00:00Z",
                        "mtu": 1472,
                        "interfaces": ["1", "2"],
                        "latencyMs": [6.2, 6.2],
                        "bandwidth": [100000000, 100000000],
                        "latencyMicros": [6200, 6200],
                        "bandwidthKbps": [100000, 100000],
                        "totalLatencyMicros": 12400,
                        "latencyComplete": true,
                        "bottleneckKbps": 100000,
                        "bandwidthComplete": true,
                        "interAsLinks": 1,
                        "linkType": ["direct"],
                        "internalHops": [1]
                    },
                    {
                        "fingerprint": "fp_frankfurt_transit",
                        "display": "Frankfurt Transit [64-2:0:49 ➔ 64-2:0:50 ➔ 64-1:0:12]",
                        "current": true,
                        "nextHop": "192.168.1.1:30042",
                        "expiry": "2026-08-17T15:00:00Z",
                        "mtu": 1472,
                        "interfaces": ["2", "1", "3"],
                        "latencyMs": [7.2, 7.2, 7.2],
                        "bandwidth": [500000000, 200000000, 500000000],
                        "latencyMicros": [7200, 7200, 7200],
                        "bandwidthKbps": [500000, 200000, 500000],
                        "totalLatencyMicros": 21800,
                        "latencyComplete": true,
                        "bottleneckKbps": 200000,
                        "bandwidthComplete": true,
                        "interAsLinks": 2,
                        "linkType": ["transit", "transit"],
                        "internalHops": [2, 1]
                    }
                ]
            }
        """.trimIndent()

        val response = parseFlowPathsResponse(json)
        assertEquals(1L, response.flowId)
        assertEquals(FlowPathsState.READY, response.state)
        assertEquals("active", response.overrideState)
        assertEquals("fp_frankfurt_transit", response.effectiveFingerprint)
        assertEquals(2, response.paths.size)
        assertFalse(response.paths[0].current)
        assertTrue(response.paths[1].current)
    }

    @Test
    fun testParseMockScionSnapshot() {
        val json = """
            {
                "strategy": "LowestLatency",
                "lastRefresh": "2026-08-17T12:00:00Z",
                "pairs": [
                    {
                        "srcIa": "64-2:0:49",
                        "dstIa": "64-1:0:12",
                        "selectedFingerprint": "fp_zurich_direct",
                        "availablePathsCount": 1,
                        "paths": [
                            {
                                "fingerprint": "fp_zurich_direct",
                                "nextHop": "192.168.1.1:30042",
                                "expiry": "2026-08-17T16:00:00Z",
                                "mtu": 1472,
                                "hops": ["64-2:0:49#1", "64-1:0:12#2"],
                                "isSelected": true
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val snapshot = ScionPathSnapshotRepository.parseSnapshotJson(json)
        assertEquals("LowestLatency", snapshot.strategy)
        assertEquals(1, snapshot.pairs.size)
        assertEquals("64-2:0:49", snapshot.pairs[0].srcIa)
        assertEquals("64-1:0:12", snapshot.pairs[0].dstIa)
        assertTrue(snapshot.pairs[0].paths[0].isSelected)
    }
}
