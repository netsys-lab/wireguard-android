package com.wireguard.android.util

import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
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

class RealFlowRepository(private val backend: GoBackend) : FlowRepository {

    override suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>> = withContext(Dispatchers.IO) {
        try {
            val raw: String = backend.getFlows(tunnel) ?: return@withContext Result.failure(
                BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
            )
            val response: FlowListResponseDto = parseFlowListResponse(raw)
            if (response.error != null) {
                Result.failure(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, response.error))
            } else {
                Result.success(response.flows)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFlowById(tunnel: Tunnel, flowId: Long): Result<FlowDto> {
        return getFlows(tunnel).mapCatching { flows ->
            flows.find { it.id == flowId }
                ?: throw BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, "flow_not_found")
        }
    }

    @Deprecated("Use FlowPathProvider.getFlowPaths instead")
    override suspend fun getFlowPaths(tunnel: Tunnel, flowId: Long): Result<com.wireguard.android.model.FlowPathsResponseDto> {
        return FlowPathProviderImpl(backend).getFlowPaths(tunnel, flowId)
    }

    companion object {
        private const val TAG = "RealFlowRepository"
    }
}
