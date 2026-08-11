/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.model

data class ScionPathCacheSnapshotDto(
    val strategy: String = "",
    val lastRefresh: String? = null,
    val pairs: List<ScionIaPairSnapshotDto> = emptyList(),
) {
    val isEmpty: Boolean get() = strategy.isBlank() && pairs.isEmpty()
}

data class ScionIaPairSnapshotDto(
    val srcIa: String,
    val dstIa: String,
    val selectedFingerprint: String? = null,
    val availablePathsCount: Int = 0,
    val paths: List<ScionPathSnapshotItemDto> = emptyList(),
) {
    val pairLabel: String get() = "$srcIa ➔ $dstIa"
}

data class ScionPathSnapshotItemDto(
    val fingerprint: String,
    val nextHop: String? = null,
    val expiry: String? = null,
    val mtu: Int? = null,
    val hops: List<String> = emptyList(),
    val isSelected: Boolean = false,
)

sealed interface ScionPathCacheUiState {
    object Loading : ScionPathCacheUiState
    object Empty : ScionPathCacheUiState
    data class Content(
        val snapshot: ScionPathCacheSnapshotDto,
        val selectedPairIndex: Int = 0,
        val selectedPathIndex: Int = 0,
    ) : ScionPathCacheUiState {
        val currentPair: ScionIaPairSnapshotDto?
            get() = snapshot.pairs.getOrNull(selectedPairIndex)

        val currentPath: ScionPathSnapshotItemDto?
            get() = currentPair?.paths?.getOrNull(selectedPathIndex)
    }
}
