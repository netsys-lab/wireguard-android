/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import com.wireguard.android.backend.FlowOwner
import com.wireguard.android.backend.FlowOwnerTrackingState
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEndpointDto
import com.wireguard.android.model.FlowOwnerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FlowOwnerClassifierTest {

    private val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    private fun flow(
        id: Long = 1,
        protocol: Int = 6,
        remotePort: Int = 443,
        createdAt: String = "2026-11-10T11:59:59Z",
        packageName: String? = null,
        ownerUid: Int? = null,
        ownerStatus: FlowOwnerStatus = FlowOwnerStatus.UNSUPPORTED,
    ) = FlowDto(
        id = id,
        ipVersion = 4,
        protocol = protocol,
        endpointA = FlowEndpointDto("10.0.0.2", 40000),
        endpointB = FlowEndpointDto("1.1.1.1", remotePort),
        status = "active",
        txPackets = 1,
        txBytes = 60,
        rxPackets = 0,
        rxBytes = 0,
        egressKind = "ip",
        createdAt = createdAt,
        localIP = "10.0.0.2",
        localPort = 40000,
        remoteIP = "1.1.1.1",
        remotePort = remotePort,
        packageName = packageName,
        ownerUid = ownerUid,
        ownerStatus = ownerStatus,
    )

    // --- withOwner: status derivation ---

    @Test
    fun `resolved owner sets uid`() {
        val merged = FlowOwnerClassifier.withOwner(flow(), FlowOwner.resolved(10123, now), FlowOwnerTrackingState.ACTIVE, now)
        assertEquals(FlowOwnerStatus.RESOLVED, merged.ownerStatus)
        assertEquals(10123, merged.ownerUid)
    }

    @Test
    fun `unknown owner keeps reason`() {
        val merged = FlowOwnerClassifier.withOwner(flow(), FlowOwner.unknown("no_socket", now), FlowOwnerTrackingState.ACTIVE, now)
        assertEquals(FlowOwnerStatus.UNKNOWN, merged.ownerStatus)
        assertNull(merged.ownerUid)
        assertEquals("no_socket", merged.ownerReason)
    }

    @Test
    fun `young tcp flow without entry is pending`() {
        val merged = FlowOwnerClassifier.withOwner(flow(createdAt = "2026-11-10T11:59:58Z"), null, FlowOwnerTrackingState.ACTIVE, now)
        assertEquals(FlowOwnerStatus.PENDING, merged.ownerStatus)
    }

    @Test
    fun `old tcp flow without entry is unknown`() {
        val merged = FlowOwnerClassifier.withOwner(flow(createdAt = "2026-11-10T11:59:00Z"), null, FlowOwnerTrackingState.ACTIVE, now)
        assertEquals(FlowOwnerStatus.UNKNOWN, merged.ownerStatus)
        assertEquals("not_reported", merged.ownerReason)
    }

    @Test
    fun `icmp flow is not applicable`() {
        val merged = FlowOwnerClassifier.withOwner(flow(protocol = 58), null, FlowOwnerTrackingState.ACTIVE, now)
        assertEquals(FlowOwnerStatus.NOT_APPLICABLE, merged.ownerStatus)
    }

    @Test
    fun `entry wins over protocol for native SCMP`() {
        // Native SCMP flows have protocol 1 but an attributable outer UDP socket.
        val merged = FlowOwnerClassifier.withOwner(flow(protocol = 1), FlowOwner.resolved(10200, now), FlowOwnerTrackingState.ACTIVE, now)
        assertEquals(FlowOwnerStatus.RESOLVED, merged.ownerStatus)
    }

    @Test
    fun `tracking off is unsupported`() {
        val merged = FlowOwnerClassifier.withOwner(flow(), null, FlowOwnerTrackingState.UNSUPPORTED, now)
        assertEquals(FlowOwnerStatus.UNSUPPORTED, merged.ownerStatus)
    }

    // --- classify: groups ---

    @Test
    fun `resolved uid becomes app group`() {
        val group = FlowOwnerClassifier.classify(flow(ownerUid = 10123, ownerStatus = FlowOwnerStatus.RESOLVED))
        assertEquals(FlowGroup.App(10123), group)
        assertEquals("uid:10123", group.key)
    }

    @Test
    fun `dns resolver uid becomes system dns`() {
        val group = FlowOwnerClassifier.classify(flow(ownerUid = 1051, ownerStatus = FlowOwnerStatus.RESOLVED, remotePort = 53))
        assertEquals(FlowGroup.SystemDns, group)
    }

    @Test
    fun `unattributed dns and dot become system dns`() {
        assertEquals(FlowGroup.SystemDns, FlowOwnerClassifier.classify(flow(protocol = 17, remotePort = 53, ownerStatus = FlowOwnerStatus.UNKNOWN)))
        assertEquals(FlowGroup.SystemDns, FlowOwnerClassifier.classify(flow(remotePort = 853, ownerStatus = FlowOwnerStatus.PENDING)))
    }

    @Test
    fun `not applicable becomes network diagnostics`() {
        assertEquals(FlowGroup.NetworkDiagnostics, FlowOwnerClassifier.classify(flow(protocol = 58, ownerStatus = FlowOwnerStatus.NOT_APPLICABLE)))
    }

    @Test
    fun `unknown and pending become unknown group`() {
        assertEquals(FlowGroup.Unknown, FlowOwnerClassifier.classify(flow(ownerStatus = FlowOwnerStatus.UNKNOWN)))
        assertEquals(FlowGroup.Unknown, FlowOwnerClassifier.classify(flow(ownerStatus = FlowOwnerStatus.PENDING)))
        assertEquals(FlowGroup.Unknown, FlowOwnerClassifier.classify(flow(ownerStatus = FlowOwnerStatus.UNSUPPORTED)))
    }

    @Test
    fun `package name from mock backend wins`() {
        val group = FlowOwnerClassifier.classify(flow(packageName = "org.example.app", ownerStatus = FlowOwnerStatus.UNKNOWN))
        assertEquals(FlowGroup.Package("org.example.app"), group)
    }
}
