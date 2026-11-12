package com.wireguard.android.util

import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowPathState

interface FlowPathRepository {
    suspend fun getFlowPathState(tunnel: Tunnel, flowId: Long): Result<FlowPathState>
    suspend fun setOverride(tunnel: Tunnel, flowId: Long, fingerprint: String): Result<Unit>
    suspend fun clearOverride(tunnel: Tunnel, flowId: Long): Result<Unit>
}
