package com.wireguard.android.util

import android.content.Context
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

class DebugFlowPathRepositoryProvider(
    private val context: Context,
) : FlowPathRepositoryProvider {

    @JvmField
    val fakeState = FakeBackendState()

    var useFake: Boolean = BuildConfig.FLOW_FAKE_PROVIDER_ENABLED

    override suspend fun createFlowRepository(tunnel: Tunnel): FlowRepository {
        return if (useFake) {
            FakeFlowRepository(context)
        } else {
            RealFlowRepository(Application.getBackend() as GoBackend)
        }
    }

    override suspend fun createFlowPathRepository(tunnel: Tunnel): FlowPathRepository {
        return if (useFake) {
            FakeFlowPathRepository(context, fakeState)
        } else {
            val backend = Application.getBackend() as GoBackend
            RealFlowPathRepository(FlowPathProviderImpl(backend))
        }
    }
}
