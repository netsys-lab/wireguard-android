package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowListResponseDto
import com.wireguard.android.model.parseFlowListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface FlowRepository {
    suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>>
    suspend fun getFlowById(tunnel: Tunnel, flowId: Long): Result<FlowDto>

    @Deprecated("Use FlowPathProvider instead")
    suspend fun getFlowPaths(tunnel: Tunnel, flowId: Long): Result<com.wireguard.android.model.FlowPathsResponseDto>
}

class RealFlowRepository(private val backend: Backend) : FlowRepository {

    override suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>> = withContext(Dispatchers.IO) {
        try {
            FlowLog.d("[FLOW-DEBUG] [FlowRepo] getFlows called for tunnel: ${tunnel.name}")
            val raw: String? = backend.getFlows(tunnel)
            if (raw == null) {
                FlowLog.e("[FLOW-DEBUG] [FlowRepo] backend.getFlows returned NULL for tunnel: ${tunnel.name}")
                return@withContext Result.failure(
                    BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
                )
            }
            FlowLog.d("[FLOW-DEBUG] [FlowRepo] backend.getFlows response received (length=${raw.length})")
            val response: FlowListResponseDto = parseFlowListResponse(raw)
            if (response.error != null) {
                FlowLog.w("[FLOW-DEBUG] [FlowRepo] parseFlowListResponse returned error: '${response.error}'")
                Result.failure(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, response.error))
            } else {
                val scionCount = response.flows.count { it.isSCION }
                FlowLog.d("[FLOW-DEBUG] [FlowRepo] Successfully parsed ${response.flows.size} flows ($scionCount SCION)")
                Result.success(response.flows)
            }
        } catch (e: Exception) {
            FlowLog.e("[FLOW-DEBUG] [FlowRepo] Exception in getFlows for tunnel ${tunnel.name}: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getFlowById(tunnel: Tunnel, flowId: Long): Result<FlowDto> {
        FlowLog.d("[FLOW-DEBUG] [FlowRepo] getFlowById called for flowId=$flowId")
        return getFlows(tunnel).mapCatching { flows ->
            val match = flows.find { it.id == flowId }
            if (match == null) {
                FlowLog.w("[FLOW-DEBUG] [FlowRepo] getFlowById: flowId=$flowId NOT FOUND in ${flows.size} flows")
                throw BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, "flow_not_found")
            } else {
                FlowLog.d("[FLOW-DEBUG] [FlowRepo] getFlowById: flowId=$flowId found (egressKind=${match.egressKind}, isSCION=${match.isSCION})")
                match
            }
        }
    }

    @Deprecated("Use FlowPathProvider.getFlowPaths instead")
    override suspend fun getFlowPaths(tunnel: Tunnel, flowId: Long): Result<com.wireguard.android.model.FlowPathsResponseDto> {
        return FlowPathProviderImpl(backend).getFlowPaths(tunnel, flowId)
    }

    companion object {
        private const val TAG = "WireGuard/FlowDebug"
    }
}
