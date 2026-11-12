package com.wireguard.android.util

import com.wireguard.android.model.FlowContextState
import com.wireguard.android.model.FlowPathDomain
import com.wireguard.android.model.FlowPathState
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.HopDetailUiModel
import com.wireguard.android.model.OverrideState
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathDetailsUiModel
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.model.PathSectionState
import com.wireguard.android.model.PolicyState
import java.time.Duration
import java.time.Instant

object FlowPathUiMapper {

    fun toFlowContext(flow: FlowDto): FlowContextState.Ready {
        return FlowContextState.Ready(
            flowId = flow.id,
            protocol = protocolName(flow.protocol),
            ipVersion = flow.ipVersion,
            endpointA = formatEndpoint(flow.endpointA),
            endpointB = formatEndpoint(flow.endpointB),
            localEndpoint = formatHostPort(flow.localIP, flow.localPort),
            remoteEndpoint = formatHostPort(flow.remoteIP, flow.remotePort),
            destinationIA = flow.dstIA,
            scionDstHost = flow.scionDstIP,
            scionDstPort = null,
            txBytes = flow.txBytes,
            rxBytes = flow.rxBytes,
            txPackets = flow.txPackets,
            rxPackets = flow.rxPackets,
            txRateBitsPerSec = 0,
            rxRateBitsPerSec = 0,
            txRatePacketsPerSec = 0,
            rxRatePacketsPerSec = 0,
            lastActivity = parseInstant(flow.lastSeen),
            flowStatus = flow.status,
        )
    }

    fun toPathSection(
        flowPathState: FlowPathState,
        effectiveDomain: FlowPathDomain?,
    ): PathSectionState {
        when (flowPathState.queryState) {
            com.wireguard.android.model.PathQueryState.PENDING -> return PathSectionState.Pending
            com.wireguard.android.model.PathQueryState.EMPTY -> return PathSectionState.Empty
            com.wireguard.android.model.PathQueryState.ERROR -> return PathSectionState.Error(
                "Path lookup failed"
            )
            com.wireguard.android.model.PathQueryState.READY -> {}
            com.wireguard.android.model.PathQueryState.UNKNOWN -> return PathSectionState.Loading
        }

        val effectiveFp = flowPathState.effectiveFingerprint
            ?: (flowPathState.overrideFingerprint?.takeIf { flowPathState.overrideState == com.wireguard.android.model.OverrideState.ACTIVE })
            ?: flowPathState.paths.firstOrNull()?.fingerprint

        val paths = flowPathState.paths.map { toPreview(it, emptyList()) }
        val previewsWithBadges = paths.map { p ->
            val domain = flowPathState.paths.find { it.fingerprint == p.fingerprint }!!
            val badges = CandidateSelector.deriveBadges(domain, flowPathState.paths)
            val isCurrentPath = p.fingerprint == effectiveFp
            p.copy(
                badges = badges + overrideBadge(p.fingerprint, flowPathState),
                isCurrent = isCurrentPath,
            )
        }

        val effectivePath = (effectiveFp?.let { fp -> previewsWithBadges.find { it.fingerprint == fp } }
            ?: previewsWithBadges.firstOrNull { it.isCurrent }
            ?: previewsWithBadges.firstOrNull())
            ?.copy(isCurrent = true)

        val quickCandidates = CandidateSelector.quickCandidates(previewsWithBadges)

        return PathSectionState.Ready(
            paths = previewsWithBadges,
            effectiveFingerprint = effectiveFp ?: flowPathState.effectiveFingerprint,
            overrideState = flowPathState.overrideState,
            overrideFingerprint = flowPathState.overrideFingerprint,
            policyState = flowPathState.policyState,
            policyName = flowPathState.policyName,
            effectivePath = effectivePath,
            quickCandidates = quickCandidates,
        )
    }

    fun toPreview(domain: FlowPathDomain, badges: List<PathBadge>): PathPreviewUiModel {
        val compactRoute = deriveCompactRoute(domain.interfaces, domain.display)
        val totalLatencyMs = domain.totalLatencyMicros?.let { it / 1000 }
        val bottleneckBps = domain.bottleneckBandwidthKbps?.let { it * 1000 }
        val expirySecs = domain.expiry?.let { expiry ->
            val now = Instant.now()
            val seconds = Duration.between(now, expiry).seconds
            if (seconds > 0) seconds else null
        }

        return PathPreviewUiModel(
            fingerprint = domain.fingerprint,
            badges = badges,
            compactRoute = compactRoute,
            totalLatencyMs = totalLatencyMs,
            bottleneckBandwidthBps = bottleneckBps,
            interAsLinks = domain.interAsLinks,
            mtu = domain.mtu ?: 0,
            expirySeconds = expirySecs,
            isCurrent = false,
            isIntraAs = domain.isIntraAs,
        )
    }

    fun toPathDetails(
        domain: FlowPathDomain,
        badges: List<PathBadge>,
        source: String,
    ): PathDetailsUiModel {
        val totalLatencyMs = domain.totalLatencyMicros?.let { it / 1000 }
        val bottleneckBps = domain.bottleneckBandwidthKbps?.let { it * 1000 }
        val expirySecs = domain.expiry?.let { expiry ->
            val now = Instant.now()
            val seconds = Duration.between(now, expiry).seconds
            if (seconds > 0) seconds else null
        }

        val geoParts = domain.geo?.mapNotNull { g ->
            val clean = g.label?.replace("\n", ", ")?.trim()?.trimEnd(',')?.trim()
            clean?.takeIf { it.isNotEmpty() && (g.latitude != null && g.latitude != 0.0) && (g.longitude != null && g.longitude != 0.0) }
        } ?: emptyList()
        val geoSummary = if (geoParts.isEmpty()) "" else geoParts.joinToString(" → ")

        val hops = domain.interfaces.mapIndexed { index, isa ->
            val latencyAtHop = domain.latencyMicros?.getOrNull(index)?.let { it / 1000.0 }
            val bwAtHop = domain.bandwidthKbps?.getOrNull(index)?.let { it * 1000 }
            val geoAtHop = domain.geo?.getOrNull(index)
            val linkTypeAtHop = domain.linkType?.getOrNull(index)
            val internalHopsAtHop = domain.internalHops?.getOrNull(index)
            val notes = domain.notes?.getOrNull(index)

            val role = when {
                index == 0 -> "Local AS"
                index == domain.interfaces.size - 1 -> "Destination AS"
                else -> "Transit"
            }

            HopDetailUiModel(
                hopNumber = index + 1,
                isa = isa,
                ingressInterface = if (index > 0) index * 10 else null,
                egressInterface = if (index < domain.interfaces.size - 1) (index + 1) * 10 else null,
                latencyMs = latencyAtHop,
                bandwidthBps = bwAtHop,
                internalHops = internalHopsAtHop,
                location = geoAtHop?.label?.replace("\n", ", ")?.trim()?.trimEnd(',')?.trim()
                    ?.takeIf { it.isNotEmpty() && geoAtHop.latitude != null && geoAtHop.latitude != 0.0 && geoAtHop.longitude != null && geoAtHop.longitude != 0.0 },
                latitude = geoAtHop?.latitude,
                longitude = geoAtHop?.longitude,
                linkType = linkTypeAtHop,
                role = role,
                notes = notes,
            )
        }

        val validCoordinates = domain.geo?.filter {
            it.latitude != null && it.latitude != 0.0 && it.longitude != null && it.longitude != 0.0
        } ?: emptyList()

        return PathDetailsUiModel(
            fingerprint = domain.fingerprint,
            badges = badges,
            fullRoute = domain.display ?: domain.fingerprint,
            selectionSource = source,
            latencyMs = totalLatencyMs,
            bandwidthBps = bottleneckBps,
            interAsLinks = domain.interAsLinks,
            mtu = domain.mtu ?: 0,
            expirySeconds = expirySecs,
            geoSummary = geoSummary,
            hops = hops,
            geoCoordinates = validCoordinates,
        )
    }

    private fun overrideBadge(fingerprint: String, state: FlowPathState): List<PathBadge> {
        if (state.overrideFingerprint != fingerprint) return emptyList()
        if (state.overrideState == OverrideState.ACTIVE) return listOf(PathBadge.OVERRIDE)
        return emptyList()
    }

    private fun deriveCompactRoute(interfaces: List<String>, display: String?): String {
        if (interfaces.isEmpty()) return display ?: ""
        if (interfaces.size <= 3) return display ?: interfaces.joinToString(" → ")
        val source = interfaces.first()
        val dest = interfaces.last()
        val intermediateCount = interfaces.size - 2
        return "$source → $intermediateCount intermediate ASes → $dest"
    }

    private fun protocolName(protocol: Int): String = when (protocol) {
        6 -> "TCP"
        17 -> "UDP"
        else -> "Protocol $protocol"
    }

    private fun formatEndpoint(ep: com.wireguard.android.model.FlowEndpointDto): String =
        "${ep.address}:${ep.port}"

    private fun formatHostPort(host: String?, port: Int?): String =
        if (host != null && port != null) "$host:$port" else host ?: ""

    private fun parseInstant(timestamp: String?): Instant? {
        if (timestamp == null) return null
        return try {
            Instant.parse(timestamp)
        } catch (_: Exception) {
            null
        }
    }
}
