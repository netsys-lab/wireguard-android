/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.wireguard.android.R
import com.wireguard.android.model.FlowDto
import java.util.concurrent.ConcurrentHashMap

/**
 * Display information of one flow group (an app or a special group).
 *
 * @param packageName stable group key (see [FlowGroup.key]); used to remember the selection
 * @param appName label shown in the list
 * @param subtitle secondary text, e.g. the real package name
 */
data class AppFlowInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val subtitle: String = "",
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

/**
 * Groups flows by the app that owns them.
 *
 * The owner (Android UID) is determined by FlowOwnerTracker in the tunnel module and merged
 * into each [FlowDto] by RealFlowRepository. This class only classifies flows
 * ([FlowOwnerClassifier]) and turns groups into labels and icons via [PackageManager].
 */
class FlowAppResolver(private val context: Context) {

    private val labelCache = ConcurrentHashMap<String, AppFlowInfo>()

    /** Kept for API compatibility; labels are resolved lazily and cached. */
    suspend fun ensureInitialized() = Unit

    /** Display information for a group key previously returned in [AppFlowInfo.packageName]. */
    fun resolveAppForPackage(packageName: String): AppFlowInfo =
        labelCache[packageName] ?: AppFlowInfo(packageName, packageName, null)

    /** Display information of the group a flow belongs to. */
    @Suppress("UNUSED_PARAMETER")
    fun resolveAppForFlow(flow: FlowDto, index: Int = 0): AppFlowInfo =
        resolveGroup(FlowOwnerClassifier.classify(flow))

    suspend fun groupFlowsByApp(
        flows: List<FlowDto>,
        calculator: FlowRateCalculator,
        statusColorMap: Map<String, Int>
    ): List<FlowAppGroup> {
        FlowLog.d("[FLOW-DEBUG] [AppResolver] groupFlowsByApp called with ${flows.size} flows")
        if (flows.isEmpty()) return emptyList()

        val rowDataList = FlowSortFilter.computeRowData(flows, calculator, statusColorMap)
        val rowDataByFlowId = rowDataList.associateBy { it.flow.id }

        val grouped = linkedMapOf<String, Pair<AppFlowInfo, MutableList<FlowDto>>>()
        for (flow in flows) {
            val app = resolveAppForFlow(flow)
            val entry = grouped.getOrPut(app.packageName) { Pair(app, mutableListOf()) }
            entry.second.add(flow)
        }

        val groups = grouped.values.map { (app, appFlows) ->
            val appRowData = appFlows.mapNotNull { rowDataByFlowId[it.id] }
            val txBitsPerSec = appRowData.sumOf { it.rates.txBitsPerSec }
            val rxBitsPerSec = appRowData.sumOf { it.rates.rxBitsPerSec }
            FlowAppGroup(
                appInfo = app,
                flows = appFlows,
                rowDataList = appRowData,
                txBytes = appFlows.sumOf { it.txBytes },
                rxBytes = appFlows.sumOf { it.rxBytes },
                txRateText = formatRate(txBitsPerSec),
                rxRateText = formatRate(rxBitsPerSec),
                combinedBitsPerSec = txBitsPerSec + rxBitsPerSec,
            )
        }.sortedByDescending { it.combinedBitsPerSec }

        FlowLog.d("[FLOW-DEBUG] [AppResolver] groupFlowsByApp result: ${groups.map { "${it.appInfo.packageName}(${it.flows.size})" }}")
        return groups
    }

    private fun resolveGroup(group: FlowGroup): AppFlowInfo =
        labelCache.getOrPut(group.key) {
            when (group) {
                is FlowGroup.App -> resolveUid(group)
                is FlowGroup.Package -> resolvePackage(group)
                FlowGroup.SystemDns -> special(group, R.string.flow_group_system_dns)
                FlowGroup.NetworkDiagnostics -> special(group, R.string.flow_group_network_diagnostics)
                FlowGroup.Unknown -> special(group, R.string.flow_group_unknown)
            }
        }

    private fun special(group: FlowGroup, labelRes: Int): AppFlowInfo =
        AppFlowInfo(group.key, context.getString(labelRes), null)

    /** UID → app label and icon. Shared UIDs show the first app plus the number of others. */
    private fun resolveUid(group: FlowGroup.App): AppFlowInfo {
        val pm = context.packageManager
        val packages = try {
            pm.getPackagesForUid(group.uid)?.sorted()
        } catch (_: Exception) {
            null
        }
        if (packages.isNullOrEmpty()) {
            val name = try {
                pm.getNameForUid(group.uid)
            } catch (_: Exception) {
                null
            }
            val label = when {
                group.uid == ROOT_UID -> context.getString(R.string.flow_group_root)
                group.uid == SYSTEM_UID -> context.getString(R.string.flow_group_android_system)
                !name.isNullOrEmpty() -> name
                else -> context.getString(R.string.flow_group_uid, group.uid)
            }
            return AppFlowInfo(group.key, label, null, context.getString(R.string.flow_group_uid, group.uid))
        }
        val first = packages.first()
        val (label, icon) = loadLabelAndIcon(pm, first, group.uid)
        val shownLabel = when {
            group.uid == SYSTEM_UID -> context.getString(R.string.flow_group_android_system)
            packages.size > 1 -> context.getString(R.string.flow_group_shared_uid, label, packages.size - 1)
            else -> label
        }
        return AppFlowInfo(group.key, shownLabel, icon, first)
    }

    /** Mock backend: the flow already carries a package name. */
    private fun resolvePackage(group: FlowGroup.Package): AppFlowInfo {
        val pm = context.packageManager
        val (label, icon) = loadLabelAndIcon(pm, group.packageName, null)
        return AppFlowInfo(group.key, label, icon, group.packageName)
    }

    private fun loadLabelAndIcon(pm: PackageManager, packageName: String, uid: Int?): Pair<String, Drawable?> {
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            val label = info.loadLabel(pm).toString()
            var icon: Drawable? = info.loadIcon(pm)
            if (icon != null && uid != null) {
                // Adds the work-profile badge for apps of another Android user/profile.
                icon = pm.getUserBadgedIcon(icon, UserHandle.getUserHandleForUid(uid))
            }
            Pair(label, icon)
        } catch (_: Exception) {
            Pair(packageName, null)
        }
    }

    private companion object {
        const val ROOT_UID = 0
        const val SYSTEM_UID = 1000
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
