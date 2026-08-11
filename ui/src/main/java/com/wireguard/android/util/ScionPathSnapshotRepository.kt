/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import com.wireguard.android.backend.Backend
import com.wireguard.android.model.ScionIaPairSnapshotDto
import com.wireguard.android.model.ScionPathCacheSnapshotDto
import com.wireguard.android.model.ScionPathCacheUiState
import com.wireguard.android.model.ScionPathSnapshotItemDto
import org.json.JSONObject

class ScionPathSnapshotRepository(
    private val backend: Backend,
) {
    fun fetchSnapshot(): ScionPathCacheUiState {
        val rawJson = try {
            backend.scionStatus
        } catch (e: Exception) {
            return ScionPathCacheUiState.Empty
        }

        val snapshot = parseSnapshotJson(rawJson)
        return if (snapshot.isEmpty) {
            ScionPathCacheUiState.Empty
        } else {
            ScionPathCacheUiState.Content(snapshot = snapshot)
        }
    }

    companion object {
        fun parseSnapshotJson(rawJson: String?): ScionPathCacheSnapshotDto {
            if (rawJson.isNullOrBlank()) return ScionPathCacheSnapshotDto()

            return try {
                val root = JSONObject(rawJson)
                val strategy = root.optString("strategy", "")
                val lastRefresh = root.optString("lastRefresh", root.optString("last_refresh", null))

                val pairsArray = root.optJSONArray("pairs") ?: root.optJSONArray("path_cache")
                val pairs = mutableListOf<ScionIaPairSnapshotDto>()

                if (pairsArray != null) {
                    for (i in 0 until pairsArray.length()) {
                        val pairObj = pairsArray.optJSONObject(i) ?: continue
                        val srcIa = pairObj.optString("srcIa", pairObj.optString("src_ia", pairObj.optString("src", "")))
                        val dstIa = pairObj.optString("dstIa", pairObj.optString("dst_ia", pairObj.optString("dst", "")))
                        val selectedFp = pairObj.optString("selectedFingerprint", pairObj.optString("selected_fingerprint", null))

                        val pathsArray = pairObj.optJSONArray("paths")
                        val paths = mutableListOf<ScionPathSnapshotItemDto>()

                        if (pathsArray != null) {
                            for (j in 0 until pathsArray.length()) {
                                val pathObj = pathsArray.optJSONObject(j) ?: continue
                                val fp = pathObj.optString("fingerprint", "")
                                val nextHop = pathObj.optString("nextHop", pathObj.optString("next_hop", null))
                                val expiry = pathObj.optString("expiry", null)
                                val mtu = if (pathObj.has("mtu")) pathObj.optInt("mtu") else null
                                val isSelected = pathObj.optBoolean("isSelected", pathObj.optBoolean("is_selected", false)) || (fp == selectedFp)

                                val hopsArray = pathObj.optJSONArray("hops")
                                val hops = mutableListOf<String>()
                                if (hopsArray != null) {
                                    for (k in 0 until hopsArray.length()) {
                                        hops.add(hopsArray.optString(k, ""))
                                    }
                                }

                                paths.add(
                                    ScionPathSnapshotItemDto(
                                        fingerprint = fp,
                                        nextHop = nextHop,
                                        expiry = expiry,
                                        mtu = mtu,
                                        hops = hops,
                                        isSelected = isSelected,
                                    )
                                )
                            }
                        }

                        val availableCount = pairObj.optInt("availablePathsCount", pairObj.optInt("available_paths_count", paths.size))

                        pairs.add(
                            ScionIaPairSnapshotDto(
                                srcIa = srcIa,
                                dstIa = dstIa,
                                selectedFingerprint = selectedFp,
                                availablePathsCount = availableCount,
                                paths = paths,
                            )
                        )
                    }
                }

                ScionPathCacheSnapshotDto(
                    strategy = strategy,
                    lastRefresh = lastRefresh,
                    pairs = pairs,
                )
            } catch (e: Exception) {
                ScionPathCacheSnapshotDto()
            }
        }
    }
}

private inline fun String?.isNullOrBlank(): Boolean = this == null || this.trim().isEmpty()
