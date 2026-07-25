package com.wireguard.android.util

import com.wireguard.android.BuildConfig
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowListResponseDto
import com.wireguard.android.model.FlowPathsResponseDto
import com.wireguard.android.model.parseFlowListResponse
import com.wireguard.android.model.parseFlowPathsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FlowPathProviderImpl(private val backend: GoBackend) : FlowPathProvider {

    override suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>> {
        return withContext(Dispatchers.IO) {
            try {
                val raw: String = backend.getFlows(tunnel) ?: return@withContext Result.failure(
                    BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
                )
                if (BuildConfig.FLOW_CAPTURE_ENABLED) FlowCaptureUtil.capture(raw, 0L)
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

    override suspend fun getFlowPaths(tunnel: Tunnel, flowId: Long): Result<FlowPathsResponseDto> {
        return withContext(Dispatchers.IO) {
            try {
                val raw: String = backend.getFlowPaths(tunnel, flowId) ?: return@withContext Result.failure(
                    BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
                )
                if (BuildConfig.FLOW_CAPTURE_ENABLED) FlowCaptureUtil.capture(raw, flowId)
                val response: FlowPathsResponseDto = parseFlowPathsResponse(raw)
                if (response.error != null) {
                    Result.failure(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, response.error))
                } else {
                    Result.success(response)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun setFlowPathOverride(tunnel: Tunnel, flowId: Long, fingerprint: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val raw: String = backend.setFlowPathOverride(tunnel, flowId, fingerprint)
                    ?: return@withContext Result.failure(
                        BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
                    )
                if (raw.isEmpty()) {
                    Result.success(Unit)
                } else {
                    val json = JSONObject(raw)
                    val err = json.optString("error", null)
                    if (err != null) {
                        Result.failure(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, err))
                    } else {
                        Result.success(Unit)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun clearFlowPathOverride(tunnel: Tunnel, flowId: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val raw: String = backend.clearFlowPathOverride(tunnel, flowId)
                    ?: return@withContext Result.failure(
                        BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
                    )
                if (raw.isEmpty()) {
                    Result.success(Unit)
                } else {
                    val json = JSONObject(raw)
                    val err = json.optString("error", null)
                    if (err != null) {
                        Result.failure(BackendException(BackendException.Reason.GO_ACTIVATION_ERROR_CODE, err))
                    } else {
                        Result.success(Unit)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private companion object {
        private const val TAG = "FlowPathProviderImpl"
    }
}
