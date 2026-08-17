package com.wireguard.android.model

data class PathDetailsUiModel(
    val fingerprint: String,
    val badges: List<PathBadge>,
    val fullRoute: String,
    val selectionSource: String,
    val latencyMs: Long?,
    val bandwidthBps: Long?,
    val interAsLinks: Int,
    val mtu: Int,
    val expirySeconds: Long?,
    val geoSummary: String,
    val hops: List<HopDetailUiModel>,
    val geoCoordinates: List<PathGeoDomain> = emptyList(),
)

data class HopDetailUiModel(
    val hopNumber: Int,
    val isa: String,
    val ingressInterface: Int?,
    val egressInterface: Int?,
    val latencyMs: Double?,
    val bandwidthBps: Long?,
    val internalHops: Int?,
    val location: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val linkType: String?,
    val role: String,
    val notes: String? = null,
)