package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.BuildConfig
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowPathDomainMapper
import com.wireguard.android.model.FlowPathState

class RealFlowPathRepository(
    private val provider: FlowPathProvider,
) : FlowPathRepository {

    override suspend fun getFlowPathState(tunnel: Tunnel, flowId: Long): Result<FlowPathState> {
        val result = provider.getFlowPaths(tunnel, flowId)
        if (BuildConfig.DEBUG) {
            result.onSuccess { Log.d(TAG, "getFlowPathState(flowId=$flowId): state=${it.state} paths=${it.paths.size}") }
            result.onFailure { Log.w(TAG, "getFlowPathState(flowId=$flowId) failed: ${it.message}") }
        }
        return result.map { response -> FlowPathDomainMapper.toDomain(response) }
    }

    override suspend fun setOverride(tunnel: Tunnel, flowId: Long, fingerprint: String): Result<Unit> {
        val result = provider.setFlowPathOverride(tunnel, flowId, fingerprint)
        if (BuildConfig.DEBUG) {
            result.onSuccess { Log.d(TAG, "setOverride(flowId=$flowId, fingerprint=$fingerprint) succeeded") }
            result.onFailure { Log.w(TAG, "setOverride(flowId=$flowId) failed: ${it.message}") }
        }
        return result
    }

    override suspend fun clearOverride(tunnel: Tunnel, flowId: Long): Result<Unit> {
        val result = provider.clearFlowPathOverride(tunnel, flowId)
        if (BuildConfig.DEBUG) {
            result.onSuccess { Log.d(TAG, "clearOverride(flowId=$flowId) succeeded") }
            result.onFailure { Log.w(TAG, "clearOverride(flowId=$flowId) failed: ${it.message}") }
        }
        return result
    }

    companion object {
        private const val TAG = "RealFlowPathRepository"
    }
}
