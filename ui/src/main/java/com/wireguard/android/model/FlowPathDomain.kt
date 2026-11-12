package com.wireguard.android.model

import java.time.Instant

data class FlowPathDomain(
    val fingerprint: String,
    val display: String? = null,
    val interfaces: List<String>,
    val latencyMicros: List<Long>? = null,
    val bandwidthKbps: List<Long>? = null,
    val geo: List<PathGeoDomain>? = null,
    val linkType: List<String>? = null,
    val internalHops: List<Int>? = null,
    val mtu: Int? = null,
    val nextHop: String? = null,
    val expiry: Instant? = null,
    val notes: List<String>? = null,
    val totalLatencyMicros: Long? = null,
    val latencyComplete: Boolean = false,
    val bottleneckBandwidthKbps: Long? = null,
    val bandwidthComplete: Boolean = false,
    val interAsLinks: Int = 0,
) {
    val isIntraAs: Boolean get() = interfaces.size <= 1

    val route: List<PathHopDomain>
        get() = interfaces.map { PathHopDomain(isa = it) }
}

data class PathHopDomain(
    val isa: String,
) {
    val asn: String get() = isa.substringBeforeLast(":")
}

data class PathGeoDomain(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val label: String? = null,
)
