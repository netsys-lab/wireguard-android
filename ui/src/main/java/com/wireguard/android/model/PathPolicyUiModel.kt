package com.wireguard.android.model

data class PathPolicyUiModel(
    val name: String,
    val state: PathPolicyState,
    val description: String,
)

enum class PathPolicyState {
    AUTOMATIC,
    NAMED_POLICY,
    OVERRIDE_ACTIVE,
    FALLBACK,
    NONE,
}