package com.wireguard.android.model

import java.time.Instant

sealed interface FlowContextState {
    data object Loading : FlowContextState
    data class Ready(
        val flowId: Long,
        val protocol: String,
        val ipVersion: Int,
        val endpointA: String,
        val endpointB: String,
        val localEndpoint: String,
        val remoteEndpoint: String,
        val destinationIA: String?,
        val scionDstHost: String?,
        val scionDstPort: Int?,
        val txBytes: Long,
        val rxBytes: Long,
        val txPackets: Long,
        val rxPackets: Long,
        val txRateBitsPerSec: Long,
        val rxRateBitsPerSec: Long,
        val txRatePacketsPerSec: Long,
        val rxRatePacketsPerSec: Long,
        val lastActivity: Instant?,
        val flowStatus: String,
    ) : FlowContextState
    data class Error(val message: String) : FlowContextState
}

sealed interface PathSectionState {
    data object Loading : PathSectionState
    data object Pending : PathSectionState
    data object Empty : PathSectionState
    data class Error(val message: String) : PathSectionState
    data class Ready(
        val paths: List<PathPreviewUiModel>,
        val effectiveFingerprint: String?,
        val overrideState: OverrideState,
        val overrideFingerprint: String?,
        val policyState: PolicyState,
        val policyName: String?,
        val effectivePath: PathPreviewUiModel?,
        val quickCandidates: List<PathPreviewUiModel>,
    ) : PathSectionState
}
