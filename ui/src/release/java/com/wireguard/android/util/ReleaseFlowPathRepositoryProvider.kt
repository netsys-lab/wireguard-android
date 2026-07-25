package com.wireguard.android.util

import com.wireguard.android.Application
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

class ReleaseFlowPathRepositoryProvider : FlowPathRepositoryProvider {
    private suspend fun backend(): GoBackend = Application.getBackend() as GoBackend

    override suspend fun createFlowRepository(tunnel: Tunnel): FlowRepository {
        return RealFlowRepository(backend())
    }

    override suspend fun createFlowPathRepository(tunnel: Tunnel): FlowPathRepository {
        return RealFlowPathRepository(FlowPathProviderImpl(backend()))
    }
}
