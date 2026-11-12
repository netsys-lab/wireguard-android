package com.wireguard.android.util

import com.wireguard.android.model.FlowPathsResponseDto
import com.wireguard.android.model.OverrideState

class FakeBackendState(
    var baseFixtureName: String = "flow_paths_multi.json",
) {
    var effectiveFingerprint: String? = null
    var overrideFingerprint: String? = null
    var overrideState: OverrideState = OverrideState.INACTIVE
    var policyMode: String? = null
    var policyFallbackApplied: Boolean = false

    fun applyToDto(dto: FlowPathsResponseDto): FlowPathsResponseDto {
        return dto.copy(
            effectiveFingerprint = effectiveFingerprint ?: dto.effectiveFingerprint,
            overrideFingerprint = overrideFingerprint ?: dto.overrideFingerprint,
            overrideState = when (overrideState) {
                OverrideState.ACTIVE -> "active"
                OverrideState.STALE -> "stale"
                OverrideState.INACTIVE -> null
            },
            policyMode = policyMode ?: dto.policyMode,
            policyFallbackApplied = dto.policyFallbackApplied || policyFallbackApplied,
        )
    }

    fun applyOverride(fingerprint: String) {
        overrideFingerprint = fingerprint
        overrideState = OverrideState.ACTIVE
    }

    fun clearOverride() {
        overrideFingerprint = null
        overrideState = OverrideState.INACTIVE
    }

    fun markOverrideStale() {
        overrideState = OverrideState.STALE
    }
}
