/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.wireguard.android.model.FlowDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppFlowInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
)

data class FlowAppGroup(
    val appInfo: AppFlowInfo,
    val flows: List<FlowDto>,
    val rowDataList: List<FlowRowData>,
    val txBytes: Long,
    val rxBytes: Long,
    val txRateText: String,
    val rxRateText: String,
    val combinedBitsPerSec: Double,
)

class FlowAppResolver(private val context: Context) {

    private var candidateApps: List<AppFlowInfo> = emptyList()
    private var initialized = false

    suspend fun ensureInitialized() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installed = try {
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackagesHoldingPermissions(
                        arrayOf(Manifest.permission.INTERNET),
                        PackageManager.PackageInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackagesHoldingPermissions(
                        arrayOf(Manifest.permission.INTERNET), 0
                    )
                }

                packages.mapNotNull { pkgInfo ->
                    val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                    // Distinguish user-installed apps from pure system apps
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { pkgInfo.packageName }
                    val icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null }
                    Pair(isSystem, AppFlowInfo(pkgInfo.packageName, label, icon))
                }.sortedWith(compareBy({ it.first }, { it.second.appName }))
                .map { it.second }
            } catch (_: Exception) {
                emptyList()
            }

            candidateApps = installed.takeIf { it.isNotEmpty() } ?: listOf(
                AppFlowInfo("com.android.chrome", "Chrome", null),
                AppFlowInfo("com.google.android.youtube", "YouTube", null),
                AppFlowInfo("org.matrix.android", "Element", null),
                AppFlowInfo("com.wireguard.android", "SCIONtra", null)
            )
            initialized = true
        }
    }

    fun resolveAppForPackage(packageName: String): AppFlowInfo {
        val match = candidateApps.firstOrNull { it.packageName == packageName }
        if (match != null) return match
        return AppFlowInfo(packageName, packageName, null)
    }

    fun resolveAppForFlow(flow: FlowDto, index: Int = 0): AppFlowInfo {
        if (!initialized && candidateApps.isEmpty()) {
            return AppFlowInfo(flow.packageName ?: "com.android.chrome", "App ${flow.id}", null)
        }

        // Match by package name if present
        if (!flow.packageName.isNullOrEmpty()) {
            val match = candidateApps.firstOrNull { it.packageName == flow.packageName }
            if (match != null) return match
        }

        // Map deterministically by index or flow ID to candidate apps
        val slot = ((if (flow.id > 0) flow.id.toInt() - 1 else index) % candidateApps.size).coerceAtLeast(0)
        return candidateApps[slot]
    }

    suspend fun groupFlowsByApp(
        flows: List<FlowDto>,
        calculator: FlowRateCalculator,
        statusColorMap: Map<String, Int>
    ): List<FlowAppGroup> {
        ensureInitialized()
        if (flows.isEmpty()) return emptyList()

        val rowDataList = FlowSortFilter.computeRowData(flows, calculator, statusColorMap)
        val rowDataByFlowId = rowDataList.associateBy { it.flow.id }

        val grouped = mutableMapOf<String, Pair<AppFlowInfo, MutableList<FlowDto>>>()
        flows.forEachIndexed { index, flow ->
            val app = resolveAppForFlow(flow, index)
            val entry = grouped.getOrPut(app.packageName) { Pair(app, mutableListOf()) }
            entry.second.add(flow)
        }

        return grouped.values.map { (app, appFlows) ->
            val appRowData = appFlows.mapNotNull { rowDataByFlowId[it.id] }
            val txBytes = appFlows.sumOf { it.txBytes }
            val rxBytes = appFlows.sumOf { it.rxBytes }
            val txBitsPerSec = appRowData.sumOf { it.rates.txBitsPerSec }
            val rxBitsPerSec = appRowData.sumOf { it.rates.rxBitsPerSec }
            val combined = txBitsPerSec + rxBitsPerSec

            FlowAppGroup(
                appInfo = app,
                flows = appFlows,
                rowDataList = appRowData,
                txBytes = txBytes,
                rxBytes = rxBytes,
                txRateText = formatRate(txBitsPerSec),
                rxRateText = formatRate(rxBitsPerSec),
                combinedBitsPerSec = combined
            )
        }.sortedByDescending { it.combinedBitsPerSec }
    }

    private fun formatRate(bitsPerSec: Double): String {
        return when {
            bitsPerSec >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f Gbit/s", bitsPerSec / 1_000_000_000)
            bitsPerSec >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbit/s", bitsPerSec / 1_000_000)
            bitsPerSec >= 1_000 -> String.format(java.util.Locale.US, "%.1f Kbit/s", bitsPerSec / 1_000)
            else -> String.format(java.util.Locale.US, "%.0f bit/s", bitsPerSec)
        }
    }
}
