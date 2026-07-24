package com.wireguard.android.util

import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEgressKind
import org.json.JSONArray
import org.json.JSONObject

data class FlowRowData(
    val flow: FlowDto,
    val rates: FlowRates,
    val displayDestination: String?,
    val displaySecondary: String?,
    val statusLabel: String,
    val statusDotColorRes: Int,
    val scionBadgeVisible: Boolean,
    val protocolLabel: String,
    val txRateText: String,
    val rxRateText: String,
    val combinedRateText: String,
)

object FlowSortFilter {

    fun filterSCIONOnly(flows: List<FlowDto>): List<FlowDto> =
        flows.filter { it.egressKindEnum == FlowEgressKind.SCION }

    suspend fun computeRowData(
        flows: List<FlowDto>,
        calculator: FlowRateCalculator,
        statusColorMap: Map<String, Int>,
    ): List<FlowRowData> {
        if (flows.isEmpty()) return emptyList()

        val ratesMap = mutableMapOf<Long, FlowRates>()
        for (f in flows) {
            ratesMap[f.id] = calculator.push(f)
        }

        return flows.map { f ->
            val rates = ratesMap[f.id] ?: FlowRates(0.0, 0.0, 0.0, 0.0)
            val statusUpper = f.status.uppercase(java.util.Locale.ROOT)
            val dotColor = statusColorMap[statusUpper] ?: statusColorMap["UNKNOWN"] ?: android.R.color.darker_gray
            val scionBadge = f.egressKindEnum == FlowEgressKind.SCION
            val protoLabel = when (f.protocol) {
                6 -> "TCP"
                17 -> "UDP"
                else -> "P${f.protocol}"
            }

            FlowRowData(
                flow = f,
                rates = rates,
                displayDestination = f.displayDestination,
                displaySecondary = f.displaySecondaryDestination,
                statusLabel = statusUpper,
                statusDotColorRes = dotColor,
                scionBadgeVisible = scionBadge,
                protocolLabel = protoLabel,
                txRateText = FlowRateFormatter.formatBitsPerSec(rates.txBitsPerSec),
                rxRateText = FlowRateFormatter.formatBitsPerSec(rates.rxBitsPerSec),
                combinedRateText = FlowRateFormatter.formatCombinedRate(rates),
            )
        }
    }

    fun sortFlows(flows: List<FlowDto>, ratesMap: Map<Long, FlowRates>): List<FlowDto> {
        return flows.sortedWith(compareByDescending<FlowDto> { f ->
            ratesMap[f.id]?.combinedBitsPerSec ?: 0.0
        }.thenByDescending { f ->
            ratesMap[f.id]?.combinedPktsPerSec ?: 0.0
        }.thenByDescending { f ->
            parseLastSeenEpoch(f.lastSeen)
        }.thenBy { f ->
            f.id
        })
    }

    private fun parseLastSeenEpoch(lastSeen: String?): Long {
        if (lastSeen == null) return 0L
        return try {
            java.time.Instant.parse(lastSeen).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
