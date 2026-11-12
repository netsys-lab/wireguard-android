/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import com.wireguard.android.model.ScionPathCacheUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScionPathSnapshotRepositoryTest {

    @Test
    fun testParseValidScionPathSnapshotJson() {
        val json = """
        {
            "strategy": "LowestLatency",
            "lastRefresh": "2026-08-11T20:30:00Z",
            "pairs": [
                {
                    "srcIa": "65413",
                    "dstIa": "65414",
                    "selectedFingerprint": "fp12345",
                    "availablePathsCount": 2,
                    "paths": [
                        {
                            "fingerprint": "fp12345",
                            "nextHop": "192.168.1.1:30041",
                            "expiry": "2026-08-11T22:30:00Z",
                            "mtu": 1472,
                            "hops": ["65413#12", "65414#1"],
                            "isSelected": true
                        },
                        {
                            "fingerprint": "fp67890",
                            "nextHop": "192.168.1.2:30041",
                            "expiry": "2026-08-11T22:30:00Z",
                            "mtu": 1500,
                            "hops": ["65413#14", "65415#3", "65414#2"],
                            "isSelected": false
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val snapshot = ScionPathSnapshotRepository.parseSnapshotJson(json)

        assertEquals("LowestLatency", snapshot.strategy)
        assertEquals("2026-08-11T20:30:00Z", snapshot.lastRefresh)
        assertEquals(1, snapshot.pairs.size)

        val pair = snapshot.pairs[0]
        assertEquals("65413", pair.srcIa)
        assertEquals("65414", pair.dstIa)
        assertEquals("65413 ➔ 65414", pair.pairLabel)
        assertEquals(2, pair.paths.size)

        val path1 = pair.paths[0]
        assertEquals("fp12345", path1.fingerprint)
        assertEquals("192.168.1.1:30041", path1.nextHop)
        assertEquals(1472, path1.mtu)
        assertTrue(path1.isSelected)
        assertEquals(2, path1.hops.size)
    }

    @Test
    fun testParseEmptyScionPathSnapshotJson() {
        val emptyJson1 = """{ "strategy": "", "pairs": [] }"""
        val snapshot1 = ScionPathSnapshotRepository.parseSnapshotJson(emptyJson1)
        assertTrue(snapshot1.isEmpty)

        val emptyJson2 = "{}"
        val snapshot2 = ScionPathSnapshotRepository.parseSnapshotJson(emptyJson2)
        assertTrue(snapshot2.isEmpty)

        val emptyJson3 = ""
        val snapshot3 = ScionPathSnapshotRepository.parseSnapshotJson(emptyJson3)
        assertTrue(snapshot3.isEmpty)
    }

    @Test
    fun testMalformedJsonReturnsEmptySnapshot() {
        val malformedJson = "{ strategy: invalid_json"
        val snapshot = ScionPathSnapshotRepository.parseSnapshotJson(malformedJson)
        assertNotNull(snapshot)
        assertTrue(snapshot.isEmpty)
    }
}
