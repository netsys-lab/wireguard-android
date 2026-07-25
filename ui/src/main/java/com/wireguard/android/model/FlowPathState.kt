package com.wireguard.android.model

data class FlowPathState(
    val flowId: Long,
    val queryState: PathQueryState,
    val paths: List<FlowPathDomain> = emptyList(),
    val effectiveFingerprint: String? = null,
    val overrideState: OverrideState = OverrideState.INACTIVE,
    val overrideFingerprint: String? = null,
    val policyState: PolicyState = PolicyState.UNKNOWN,
    val policyName: String? = null,
    val policyFallbackApplied: Boolean = false,
)

enum class PathQueryState {
    READY,
    PENDING,
    EMPTY,
    ERROR,
    UNKNOWN,
}

enum class OverrideState {
    INACTIVE,
    ACTIVE,
    STALE,
}

enum class PolicyState {
    UNKNOWN,
    NONE,
    DEFAULT,
    CONFIGURED,
    FALLBACK,
}
