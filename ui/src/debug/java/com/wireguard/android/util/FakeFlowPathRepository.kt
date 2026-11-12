package com.wireguard.android.util

import android.content.Context
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowPathDomainMapper
import com.wireguard.android.model.FlowPathState
import com.wireguard.android.model.parseFlowPathsResponse

class FakeFlowPathRepository(
    private val context: Context,
    private val state: FakeBackendState,
) : FlowPathRepository {

    override suspend fun getFlowPathState(tunnel: Tunnel, flowId: Long): Result<FlowPathState> {
        return try {
            val json = context.assets.open("flow_path_fixtures/${state.baseFixtureName}")
                .bufferedReader().use { it.readText() }
            val dto = parseFlowPathsResponse(json)
            val patched = state.applyToDto(dto)
            Result.success(FlowPathDomainMapper.toDomain(patched))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setOverride(tunnel: Tunnel, flowId: Long, fingerprint: String): Result<Unit> {
        state.applyOverride(fingerprint)
        return Result.success(Unit)
    }

    override suspend fun clearOverride(tunnel: Tunnel, flowId: Long): Result<Unit> {
        state.clearOverride()
        return Result.success(Unit)
    }
}
