package com.wireguard.android.model

import org.json.JSONArray
import org.json.JSONObject

enum class FlowEgressKind {
    UNKNOWN,
    IP,
    SCION,
}

fun String.toFlowEgressKind(): FlowEgressKind = when (lowercase()) {
    "ip" -> FlowEgressKind.IP
    "scion" -> FlowEgressKind.SCION
    else -> FlowEgressKind.UNKNOWN
}

data class FlowEndpointDto(
    val address: String,
    val port: Int,
)

data class FlowDto(
    val id: Long,
    val ipVersion: Int,
    val protocol: Int,
    val endpointA: FlowEndpointDto,
    val endpointB: FlowEndpointDto,
    val status: String,
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
    val egressKind: String = "unknown",
    val srcIA: String? = null,
    val dstIA: String? = null,
    val createdAt: String? = null,
    val lastSeen: String? = null,
) {
    val egressKindEnum: FlowEgressKind get() = egressKind.toFlowEgressKind()
}

data class SCIONInfoDto(
    val localIA: String = "",
    val localIPv4: String? = null,
    val localIPv6: String? = null,
    val brAddr: String? = null,
    val portRange: String? = null,
)

data class FlowListResponseDto(
    val flows: List<FlowDto> = emptyList(),
    val error: String? = null,
)

fun parseFlowListResponse(json: String): FlowListResponseDto {
    val obj = JSONObject(json)
    val error = obj.optString("error", null) ?: obj.optString("error", null)
    val errorVal = if (obj.has("error") && !obj.isNull("error")) obj.getString("error") else null
    val flowsArray = obj.optJSONArray("flows") ?: JSONArray()
    val flows = (0 until flowsArray.length()).map { i ->
        val f = flowsArray.getJSONObject(i)
        val egressKind = parseEgressKind(f)
        FlowDto(
            id = f.optLong("id", 0),
            ipVersion = f.optInt("ipVersion", 0),
            protocol = f.optInt("protocol", 0),
            endpointA = parseEndpoint(f.getJSONObject("endpointA")),
            endpointB = parseEndpoint(f.getJSONObject("endpointB")),
            status = f.optString("status", ""),
            txPackets = f.optLong("txPackets", 0),
            txBytes = f.optLong("txBytes", 0),
            rxPackets = f.optLong("rxPackets", 0),
            rxBytes = f.optLong("rxBytes", 0),
            egressKind = egressKind,
            srcIA = f.optString("srcIA", null)?.takeIf { it.isNotEmpty() },
            dstIA = f.optString("dstIA", null)?.takeIf { it.isNotEmpty() },
            createdAt = f.optString("createdAt", null),
            lastSeen = f.optString("lastSeen", null),
        )
    }
    val err = if (errorVal != null && errorVal.isNotEmpty()) errorVal else null
    return FlowListResponseDto(flows = flows, error = err)
}

private fun parseEgressKind(obj: JSONObject): String {
    if (obj.has("egressKind") && !obj.isNull("egressKind")) {
        return obj.getString("egressKind")
    }
    // Legacy fallback: isScion == true maps to SCION
    if (obj.has("isScion") && obj.optBoolean("isScion", false)) {
        return "scion"
    }
    return "unknown"
}

fun parseSCIONInfo(json: String): SCIONInfoDto {
    val obj = JSONObject(json)
    return SCIONInfoDto(
        localIA = obj.optString("localIA", ""),
        localIPv4 = obj.optString("localIPv4", null)?.takeIf { it.isNotEmpty() },
        localIPv6 = obj.optString("localIPv6", null)?.takeIf { it.isNotEmpty() },
        brAddr = obj.optString("brAddr", null)?.takeIf { it.isNotEmpty() },
        portRange = obj.optString("portRange", null)?.takeIf { it.isNotEmpty() },
    )
}

private fun parseEndpoint(obj: JSONObject): FlowEndpointDto {
    return FlowEndpointDto(
        address = obj.optString("address", ""),
        port = obj.optInt("port", 0),
    )
}
