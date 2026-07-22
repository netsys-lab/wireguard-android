package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.BuildConfig
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowListResponseDto
import com.wireguard.android.model.parseFlowListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FlowRepository(private val backend: GoBackend) {
    suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>> = withContext(Dispatchers.IO) {
        try {
            val raw: String = backend.getFlows(tunnel) ?: return@withContext Result.failure(
                BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
            )
            if (BuildConfig.DEBUG) Log.d("FlowRepository", "Raw response: $raw")
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
}
