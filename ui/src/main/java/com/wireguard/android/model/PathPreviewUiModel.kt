package com.wireguard.android.model

data class PathPreviewUiModel(
    val fingerprint: String,
    val badges: List<PathBadge>,
    val compactRoute: String,
    val totalLatencyMs: Long?,
    val bottleneckBandwidthBps: Long?,
    val interAsLinks: Int,
    val mtu: Int,
    val expirySeconds: Long?,
    val isCurrent: Boolean,
    val isIntraAs: Boolean,
)