package com.wireguard.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowContextState
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowPathDomain
import com.wireguard.android.model.FlowPathState
import com.wireguard.android.model.OverrideState
import com.wireguard.android.model.PathDetailsUiModel
import com.wireguard.android.model.PathSectionState
import com.wireguard.android.util.CandidateSelector
import com.wireguard.android.util.FlowPathRepository
import com.wireguard.android.util.FlowPathUiMapper
import com.wireguard.android.util.FlowRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FlowPathViewModel(
    private val flowRepository: FlowRepository,
    private val flowPathRepository: FlowPathRepository,
    private val tunnel: Tunnel,
    private val flowId: Long,
    private val flowSnapshot: FlowDto?,
) : ViewModel() {

    private var previousFlowDto: FlowDto? = null
    private var previousPollTime: Long = 0L
    private var flowContextLoaded = false
    private var pathsLoaded = false

    private val _flowContext = MutableStateFlow<FlowContextState>(
        flowSnapshot?.let { FlowPathUiMapper.toFlowContext(it) as FlowContextState }
            ?: FlowContextState.Loading
    )
    val flowContext: StateFlow<FlowContextState> = _flowContext.asStateFlow()

    private val _pathSection = MutableStateFlow<PathSectionState>(PathSectionState.Loading)
    val pathSection: StateFlow<PathSectionState> = _pathSection.asStateFlow()

    private val _selectedPathDetails = MutableStateFlow<PathDetailsUiModel?>(null)
    val selectedPathDetails: StateFlow<PathDetailsUiModel?> = _selectedPathDetails.asStateFlow()

    private var lastDomainState: FlowPathState? = null
    private var contextPollJob: Job? = null
    private var pathPollJob: Job? = null

    init {
        if (flowSnapshot == null) {
            loadFlowContext()
        }
        loadPaths()
        startPolling()
    }

    fun loadFlowContext() {
        viewModelScope.launch {
            if (!flowContextLoaded) _flowContext.value = FlowContextState.Loading
            flowRepository.getFlowById(tunnel, flowId).onSuccess { flow ->
                val now = System.currentTimeMillis()
                val prev = previousFlowDto
                val prevTime = previousPollTime
                previousFlowDto = flow
                previousPollTime = now
                val state = if (prev != null && prevTime > 0) {
                    val dt = ((now - prevTime) / 1000).coerceAtLeast(1)
                    FlowPathUiMapper.toFlowContext(flow).copy(
                        txRateBitsPerSec = (flow.txBytes - prev.txBytes) * 8 / dt,
                        rxRateBitsPerSec = (flow.rxBytes - prev.rxBytes) * 8 / dt,
                        txRatePacketsPerSec = (flow.txPackets - prev.txPackets) / dt,
                        rxRatePacketsPerSec = (flow.rxPackets - prev.rxPackets) / dt,
                    )
                } else {
                    FlowPathUiMapper.toFlowContext(flow)
                }
                flowContextLoaded = true
                _flowContext.value = state
            }.onFailure { e ->
                _flowContext.value = FlowContextState.Error(e.message ?: "Failed to load flow")
            }
        }
    }

    fun loadPaths() {
        viewModelScope.launch {
            if (!pathsLoaded) _pathSection.value = PathSectionState.Loading
            flowPathRepository.getFlowPathState(tunnel, flowId).onSuccess { state ->
                lastDomainState = state
                val effectiveDomain = state.effectiveFingerprint?.let { fp ->
                    state.paths.find { it.fingerprint == fp }
                }
                pathsLoaded = true
                _pathSection.value = FlowPathUiMapper.toPathSection(state, effectiveDomain)
            }.onFailure { e ->
                _pathSection.value = PathSectionState.Error(e.message ?: "Failed to load paths")
            }
        }
    }

    fun selectPathDetails(fingerprint: String) {
        val domain = lastDomainState?.paths?.find { it.fingerprint == fingerprint } ?: return
        val allDomains = lastDomainState?.paths ?: return
        val badges = CandidateSelector.deriveBadges(domain, allDomains)
        val source = deriveSelectionSource(fingerprint)
        _selectedPathDetails.value = FlowPathUiMapper.toPathDetails(domain, badges, source)
    }

    fun clearSelectedPathDetails() {
        _selectedPathDetails.value = null
    }

    fun isEffectivePath(fingerprint: String): Boolean {
        val section = _pathSection.value
        if (section !is PathSectionState.Ready) return false
        return section.effectiveFingerprint == fingerprint
    }

    override fun onCleared() {
        super.onCleared()
        contextPollJob?.cancel()
        pathPollJob?.cancel()
    }

    private fun startPolling() {
        contextPollJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                loadFlowContext()
            }
        }
        pathPollJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                loadPaths()
            }
        }
    }

    fun applyOverride(fingerprint: String) {
        viewModelScope.launch {
            flowPathRepository.setOverride(tunnel, flowId, fingerprint).onSuccess {
                loadPaths()
            }
        }
    }

    fun restoreOverrideFlow() {
        viewModelScope.launch {
            flowPathRepository.clearOverride(tunnel, flowId).onSuccess {
                loadPaths()
            }
        }
    }

    private fun deriveSelectionSource(fingerprint: String): String {
        val section = _pathSection.value as? PathSectionState.Ready ?: return ""
        return when {
            section.overrideFingerprint == fingerprint && section.overrideState == OverrideState.ACTIVE ->
                "Selected by manual override"
            section.effectiveFingerprint == fingerprint -> {
                val name = section.policyName ?: "automatic"
                "Selected automatically by the \"$name\" policy"
            }
            else -> ""
        }
    }

    class Factory(
        private val flowRepository: FlowRepository,
        private val flowPathRepository: FlowPathRepository,
        private val tunnel: Tunnel,
        private val flowId: Long,
        private val flowSnapshot: FlowDto?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FlowPathViewModel(
                flowRepository = flowRepository,
                flowPathRepository = flowPathRepository,
                tunnel = tunnel,
                flowId = flowId,
                flowSnapshot = flowSnapshot,
            ) as T
        }
    }
}
