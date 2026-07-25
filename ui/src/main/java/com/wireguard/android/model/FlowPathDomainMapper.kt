package com.wireguard.android.model

import java.time.Instant

object FlowPathDomainMapper {

    fun toDomain(response: FlowPathsResponseDto): FlowPathState {
        val paths = response.paths.map { toDomain(it) }
        return FlowPathState(
            flowId = response.flowId,
            queryState = toQueryState(response.state),
            paths = paths,
            effectiveFingerprint = response.effectiveFingerprint,
            overrideState = toOverrideState(response.overrideState),
            overrideFingerprint = response.overrideFingerprint,
            policyState = toPolicyState(response.policyMode, response.policyFallbackApplied),
            policyName = response.policyName,
            policyFallbackApplied = response.policyFallbackApplied,
        )
    }

    fun toDomain(dto: FlowPathDto): FlowPathDomain {
        val ifaces = dto.interfaces ?: emptyList()
        val rawLatencyMicros = dto.latencyMicros ?: dto.latencyMs?.map { (it * 1000).toLong() }
        val latencyMicros = rawLatencyMicros?.filter { it >= 0 }?.takeIf { it.isNotEmpty() }
        val rawBandwidthKbps = dto.bandwidthKbps ?: dto.bandwidth?.map { it / 1000 }
        val bandwidthKbps = rawBandwidthKbps?.filter { it >= 0 }?.takeIf { it.isNotEmpty() }

        val totalLatencyMicros = dto.totalLatencyMicros ?: latencyMicros?.let { lats ->
            if (lats.isEmpty()) null else lats.sum()
        }
        val latencyComplete = dto.latencyComplete ?: (rawLatencyMicros?.let { lats ->
            lats.isNotEmpty() && lats.all { it >= 0 }
        } ?: false)
        val bottleneckBandwidthKbps = dto.bottleneckKbps ?: bandwidthKbps?.let { bw ->
            if (bw.isEmpty()) null else bw.min()
        }
        val bandwidthComplete = dto.bandwidthComplete ?: (rawBandwidthKbps?.let { bw ->
            bw.isNotEmpty() && bw.all { it > 0 }
        } ?: false)
        val interAsLinks = if (dto.interAsLinks > 0) dto.interAsLinks else if (ifaces.size > 1) ifaces.size - 1 else 0

        val geo = dto.geo?.map { g ->
            PathGeoDomain(
                latitude = g.latitude,
                longitude = g.longitude,
                label = g.address,
            )
        }

        val expiry = dto.expiry?.let { e ->
            try {
                Instant.parse(e)
            } catch (_: Exception) {
                null
            }
        }

        return FlowPathDomain(
            fingerprint = dto.fingerprint,
            display = dto.display,
            interfaces = ifaces,
            latencyMicros = latencyMicros,
            bandwidthKbps = bandwidthKbps,
            geo = geo,
            linkType = dto.linkType,
            internalHops = dto.internalHops,
            mtu = dto.mtu,
            nextHop = dto.nextHop,
            expiry = expiry,
            notes = dto.notes,
            totalLatencyMicros = totalLatencyMicros,
            latencyComplete = latencyComplete,
            bottleneckBandwidthKbps = bottleneckBandwidthKbps,
            bandwidthComplete = bandwidthComplete,
            interAsLinks = interAsLinks,
        )
    }

    private fun toQueryState(state: FlowPathsState): PathQueryState = when (state) {
        FlowPathsState.READY -> PathQueryState.READY
        FlowPathsState.PENDING -> PathQueryState.PENDING
        FlowPathsState.EMPTY -> PathQueryState.EMPTY
        FlowPathsState.ERROR -> PathQueryState.ERROR
        FlowPathsState.UNKNOWN -> PathQueryState.UNKNOWN
    }

    private fun toPolicyState(mode: String?, fallback: Boolean): PolicyState = when {
        fallback -> PolicyState.FALLBACK
        mode == null || mode.isEmpty() -> PolicyState.UNKNOWN
        mode == "none" -> PolicyState.NONE
        mode == "default" -> PolicyState.DEFAULT
        mode == "configured" -> PolicyState.CONFIGURED
        else -> PolicyState.UNKNOWN
    }

    private fun toOverrideState(state: String?): OverrideState = when (state) {
        "active" -> OverrideState.ACTIVE
        "stale" -> OverrideState.STALE
        else -> OverrideState.INACTIVE
    }
}
