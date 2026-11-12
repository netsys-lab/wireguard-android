package com.wireguard.android.model

data class FlowDetailsUiModel(
    val flowId: Long,
    val screenState: FlowDetailsScreenState,
    val protocol: String,
    val ipVersion: Int,
    val localEndpoint: String,
    val destinationIA: String,
    val destinationHost: String,
    val txBitRate: String,
    val txPacketRate: String,
    val rxBitRate: String,
    val rxPacketRate: String,
    val totalTxBytes: Long,
    val totalRxBytes: Long,
    val policy: PathPolicyUiModel,
    val currentPath: PathPreviewUiModel?,
    val overrideActive: Boolean,
    val availablePathCount: Int,
)

enum class FlowDetailsScreenState {
    ACTIVE,
    LOADING,
    NO_PATHS,
    ERROR,
}