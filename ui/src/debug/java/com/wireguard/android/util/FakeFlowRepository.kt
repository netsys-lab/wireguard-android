package com.wireguard.android.util

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.parseFlowListResponse

class FakeFlowRepository(
    private val context: Context,
) : FlowRepository {

    override suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>> = runCatching {
        val json = context.assets.open("flow_path_fixtures/flows.json")
            .bufferedReader().use { it.readText() }
        val response = parseFlowListResponse(json)
        response.flows
    }

    override suspend fun getFlowById(tunnel: Tunnel, flowId: Long): Result<FlowDto> {
        return getFlows(tunnel).mapCatching { flows ->
            flows.find { it.id == flowId }
                ?: flows.firstOrNull { it.egressKind == "scion" }
                ?: error("no SCION flows in fixture")
        }
    }

    @Deprecated("Use FlowPathProvider.getFlowPaths instead")
    override suspend fun getFlowPaths(tunnel: Tunnel, flowId: Long): Result<com.wireguard.android.model.FlowPathsResponseDto> {
        throw UnsupportedOperationException("FakeFlowRepository does not support getFlowPaths")
    }

    companion object {
        private const val TAG = "FakeFlowRepository"
    }
}
