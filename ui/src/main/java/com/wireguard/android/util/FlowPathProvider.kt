package com.wireguard.android.util

import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowPathsResponseDto

interface FlowPathProvider {
    suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>>
    suspend fun getFlowPaths(tunnel: Tunnel, flowId: Long): Result<FlowPathsResponseDto>
    suspend fun setFlowPathOverride(tunnel: Tunnel, flowId: Long, fingerprint: String): Result<Unit>
    suspend fun clearFlowPathOverride(tunnel: Tunnel, flowId: Long): Result<Unit>
}
