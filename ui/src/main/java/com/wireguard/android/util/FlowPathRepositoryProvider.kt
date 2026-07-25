package com.wireguard.android.util

import com.wireguard.android.backend.Tunnel

interface FlowPathRepositoryProvider {
    suspend fun createFlowRepository(tunnel: Tunnel): FlowRepository
    suspend fun createFlowPathRepository(tunnel: Tunnel): FlowPathRepository
}
