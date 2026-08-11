/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.android.model.ScionPathCacheUiState
import com.wireguard.android.util.ScionPathSnapshotRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScionPathSnapshotViewModel(
    private val repository: ScionPathSnapshotRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScionPathCacheUiState>(ScionPathCacheUiState.Loading)
    val uiState: StateFlow<ScionPathCacheUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
        startPolling()
    }

    fun refresh() {
        viewModelScope.launch {
            val newState = repository.fetchSnapshot()
            val current = _uiState.value
            if (current is ScionPathCacheUiState.Content && newState is ScionPathCacheUiState.Content) {
                // Preserve user-selected pair/path index if valid
                val pairIdx = current.selectedPairIndex.coerceAtMost((newState.snapshot.pairs.size - 1).coerceAtLeast(0))
                val maxPathIdx = (newState.snapshot.pairs.getOrNull(pairIdx)?.paths?.size?.minus(1)) ?: 0
                val pathIdx = current.selectedPathIndex.coerceAtMost(maxPathIdx.coerceAtLeast(0))
                _uiState.value = newState.copy(selectedPairIndex = pairIdx, selectedPathIndex = pathIdx)
            } else {
                _uiState.value = newState
            }
        }
    }

    fun selectPair(pairIndex: Int) {
        val current = _uiState.value
        if (current is ScionPathCacheUiState.Content) {
            val validPairIdx = pairIndex.coerceIn(0, (current.snapshot.pairs.size - 1).coerceAtLeast(0))
            _uiState.value = current.copy(selectedPairIndex = validPairIdx, selectedPathIndex = 0)
        }
    }

    fun selectPath(pathIndex: Int) {
        val current = _uiState.value
        if (current is ScionPathCacheUiState.Content) {
            val currentPair = current.currentPair
            val maxPathIdx = ((currentPair?.paths?.size ?: 1) - 1).coerceAtLeast(0)
            val validPathIdx = pathIndex.coerceIn(0, maxPathIdx)
            _uiState.value = current.copy(selectedPathIndex = validPathIdx)
        }
    }

    fun startPolling(intervalMs: Long = 2000L) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMs)
                refresh()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
