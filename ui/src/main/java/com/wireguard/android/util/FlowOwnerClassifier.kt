/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import com.wireguard.android.backend.FlowOwner
import com.wireguard.android.backend.FlowOwnerTrackingState
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowOwnerStatus
import java.time.Instant

/**
 * Group a flow belongs to in the flow list (level 1). [key] is stable and is used to
 * remember the selected group for the drill-down (level 2).
 */
sealed class FlowGroup(val key: String) {
    /** Flow owned by the app(s) with this Android UID. */
    data class App(val uid: Int) : FlowGroup("uid:$uid")

    /** Flow with a package name delivered by the backend (mock backend only). */
    data class Package(val packageName: String) : FlowGroup("pkg:$packageName")

    /** DNS traffic of the system resolver (UID 1051) or DNS without a known owner. */
    data object SystemDns : FlowGroup("group:dns")

    /** Flows without a socket that could be attributed (ICMP, ICMPv6, mapped SCMP). */
    data object NetworkDiagnostics : FlowGroup("group:diagnostics")

    /** Flows whose owner is not (yet) known. */
    data object Unknown : FlowGroup("group:unknown")
}

/**
 * Pure logic for app attribution of flows (no Android dependencies, unit-testable).
 */
object FlowOwnerClassifier {
    /** Android's UID of the system DNS resolver (AID_DNS). */
    const val DNS_RESOLVER_UID = 1051

    /** A TCP/UDP flow without owner entry counts as PENDING for this long, afterwards UNKNOWN. */
    const val PENDING_GRACE_MS = 5_000L

    private val DNS_PORTS = setOf(53, 853)
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17

    /**
     * Merges the owner determined by FlowOwnerTracker into a flow from wgGetFlows.
     *
     * @param owner entry from Backend.getFlowOwners, or null if none exists
     * @param tracking current tracking state of the backend
     * @param nowMillis current time, used to tell PENDING from UNKNOWN
     */
    fun withOwner(flow: FlowDto, owner: FlowOwner?, tracking: FlowOwnerTrackingState, nowMillis: Long): FlowDto {
        if (owner != null) {
            return when (owner.status) {
                FlowOwner.Status.RESOLVED -> flow.copy(ownerUid = owner.uid, ownerStatus = FlowOwnerStatus.RESOLVED, ownerReason = null)
                FlowOwner.Status.UNKNOWN -> flow.copy(ownerUid = null, ownerStatus = FlowOwnerStatus.UNKNOWN, ownerReason = owner.reason)
            }
        }
        if (flow.protocol != PROTO_TCP && flow.protocol != PROTO_UDP) {
            return flow.copy(ownerUid = null, ownerStatus = FlowOwnerStatus.NOT_APPLICABLE, ownerReason = null)
        }
        if (tracking != FlowOwnerTrackingState.ACTIVE) {
            return flow.copy(ownerUid = null, ownerStatus = FlowOwnerStatus.UNSUPPORTED, ownerReason = tracking.name.lowercase())
        }
        val created = parseEpochMillis(flow.createdAt)
        val pending = created == null || nowMillis - created < PENDING_GRACE_MS
        return if (pending) {
            flow.copy(ownerUid = null, ownerStatus = FlowOwnerStatus.PENDING, ownerReason = null)
        } else {
            flow.copy(ownerUid = null, ownerStatus = FlowOwnerStatus.UNKNOWN, ownerReason = "not_reported")
        }
    }

    /** Assigns a flow to its group in the flow list. */
    fun classify(flow: FlowDto): FlowGroup {
        if (!flow.packageName.isNullOrEmpty()) return FlowGroup.Package(flow.packageName)
        val uid = flow.ownerUid
        return when {
            flow.ownerStatus == FlowOwnerStatus.RESOLVED && uid == DNS_RESOLVER_UID -> FlowGroup.SystemDns
            flow.ownerStatus == FlowOwnerStatus.RESOLVED && uid != null -> FlowGroup.App(uid)
            flow.ownerStatus == FlowOwnerStatus.NOT_APPLICABLE -> FlowGroup.NetworkDiagnostics
            flow.remotePort in DNS_PORTS -> FlowGroup.SystemDns
            else -> FlowGroup.Unknown
        }
    }

    private fun parseEpochMillis(timestamp: String?): Long? {
        if (timestamp.isNullOrEmpty()) return null
        return try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
