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
    val localIP: String? = null,
    val localPort: Int? = null,
    val remoteIP: String? = null,
    val remotePort: Int? = null,
    val scionDstIP: String? = null,
) {
    val egressKindEnum: FlowEgressKind get() = egressKind.toFlowEgressKind()

    val displayDestination: String?
        get() = when {
            egressKindEnum == FlowEgressKind.SCION && !dstIA.isNullOrEmpty() -> dstIA
            egressKindEnum == FlowEgressKind.SCION && scionDstIP != null -> scionDstIP
            egressKindEnum == FlowEgressKind.IP && remoteIP != null -> formatHostPort(remoteIP, remotePort)
            egressKindEnum == FlowEgressKind.SCION -> null
            else -> null
        }

    val displaySecondaryDestination: String?
        get() = when {
            egressKindEnum == FlowEgressKind.SCION && !dstIA.isNullOrEmpty() && scionDstIP != null ->
                scionDstIP
            else -> null
        }

    val isSCION: Boolean get() = egressKindEnum == FlowEgressKind.SCION

    private fun formatHostPort(host: String, port: Int?): String =
        if (port != null) "$host:$port" else host
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
            localIP = f.optString("localIP", null)?.takeIf { it.isNotEmpty() },
            localPort = if (f.has("localPort") && !f.isNull("localPort")) f.optInt("localPort", 0) else null,
            remoteIP = f.optString("remoteIP", null)?.takeIf { it.isNotEmpty() },
            remotePort = if (f.has("remotePort") && !f.isNull("remotePort")) f.optInt("remotePort", 0) else null,
            scionDstIP = f.optString("scionDstIP", null)?.takeIf { it.isNotEmpty() },
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

data class FlowPathDto(
    val fingerprint: String,
    val display: String,
    val current: Boolean = false,
    val nextHop: String? = null,
    val expiry: String? = null,
    val mtu: Int? = null,
    val interfaces: List<String>? = null,
    val latencyMs: List<Double>? = null,
    val bandwidth: List<Long>? = null,
    val geo: List<GeoDto>? = null,
    val linkType: List<String>? = null,
    val internalHops: List<Int>? = null,
    val notes: List<String>? = null,
    val latencyMicros: List<Long>? = null,
    val bandwidthKbps: List<Long>? = null,
    val totalLatencyMicros: Long? = null,
    val latencyComplete: Boolean? = null,
    val bottleneckKbps: Long? = null,
    val bandwidthComplete: Boolean? = null,
    val interAsLinks: Int = 0,
)

data class GeoDto(
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
)

enum class FlowPathsState {
    READY,
    PENDING,
    EMPTY,
    ERROR,
    UNKNOWN,
}

data class FlowPathsResponseDto(
    val flowId: Long,
    val state: FlowPathsState,
    val paths: List<FlowPathDto> = emptyList(),
    val error: String? = null,
    val policyName: String? = null,
    val policyMode: String? = null,
    val policyFallbackApplied: Boolean = false,
    val overrideState: String? = null,
    val overrideFingerprint: String? = null,
    val effectiveFingerprint: String? = null,
)

fun parseFlowPathsResponse(json: String): FlowPathsResponseDto {
    val obj = JSONObject(json)
    val flowId = obj.optLong("flowId", 0)
    val state = parseFlowPathsState(obj.optString("state", ""))
    val error = obj.optString("error", null)?.takeIf { it.isNotEmpty() }
    val policyName = obj.optString("policyName", null)?.takeIf { it.isNotEmpty() }
    val policyMode = obj.optString("policyMode", null)?.takeIf { it.isNotEmpty() }
    val policyFallbackApplied = obj.optBoolean("policyFallbackApplied", false)
    val overrideState = obj.optString("overrideState", null)?.takeIf { it.isNotEmpty() }
    val overrideFingerprint = obj.optString("overrideFingerprint", null)?.takeIf { it.isNotEmpty() }
    val effectiveFingerprint = obj.optString("effectiveFingerprint", null)?.takeIf { it.isNotEmpty() }
    val pathsArray = obj.optJSONArray("paths") ?: JSONArray()
    val paths = (0 until pathsArray.length()).map { i ->
        val p = pathsArray.getJSONObject(i)
        FlowPathDto(
            fingerprint = p.optString("fingerprint", ""),
            display = p.optString("display", ""),
            current = p.optBoolean("current", false),
            nextHop = p.optString("nextHop", null)?.takeIf { it.isNotEmpty() },
            expiry = p.optString("expiry", null)?.takeIf { it.isNotEmpty() },
            mtu = if (p.has("mtu") && !p.isNull("mtu")) p.optInt("mtu", 0) else null,
            interfaces = p.optJSONArray("interfaces")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            latencyMs = p.optJSONArray("latencyMs")?.let { arr ->
                (0 until arr.length()).map { arr.getDouble(it) }
            },
            bandwidth = p.optJSONArray("bandwidth")?.let { arr ->
                (0 until arr.length()).map { arr.getLong(it) }
            },
            latencyMicros = p.optJSONArray("latencyMicros")?.let { arr ->
                (0 until arr.length()).map { arr.getLong(it) }
            },
            bandwidthKbps = p.optJSONArray("bandwidthKbps")?.let { arr ->
                (0 until arr.length()).map { arr.getLong(it) }
            },
            totalLatencyMicros = if (p.has("totalLatencyMicros") && !p.isNull("totalLatencyMicros"))
                p.optLong("totalLatencyMicros", 0) else null,
            latencyComplete = if (p.has("latencyComplete") && !p.isNull("latencyComplete"))
                p.optBoolean("latencyComplete", false) else null,
            bottleneckKbps = if (p.has("bottleneckKbps") && !p.isNull("bottleneckKbps"))
                p.optLong("bottleneckKbps", 0) else null,
            bandwidthComplete = if (p.has("bandwidthComplete") && !p.isNull("bandwidthComplete"))
                p.optBoolean("bandwidthComplete", false) else null,
            interAsLinks = p.optInt("interAsLinks", 0),
            geo = p.optJSONArray("geo")?.let { arr ->
                (0 until arr.length()).map { j ->
                    val g = arr.getJSONObject(j)
                    GeoDto(
                        latitude = g.optDouble("latitude", 0.0),
                        longitude = g.optDouble("longitude", 0.0),
                        address = g.optString("address", null)?.takeIf { it.isNotEmpty() },
                    )
                }
            },
            linkType = p.optJSONArray("linkType")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            internalHops = p.optJSONArray("internalHops")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }
            },
            notes = p.optJSONArray("notes")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
        )
    }
    return FlowPathsResponseDto(
        flowId = flowId, state = state, paths = paths, error = error,
        policyName = policyName, policyMode = policyMode,
        policyFallbackApplied = policyFallbackApplied,
        overrideState = overrideState, overrideFingerprint = overrideFingerprint,
        effectiveFingerprint = effectiveFingerprint,
    )
}

private fun parseFlowPathsState(state: String): FlowPathsState = when (state.lowercase()) {
    "ready" -> FlowPathsState.READY
    "pending" -> FlowPathsState.PENDING
    "empty" -> FlowPathsState.EMPTY
    "error" -> FlowPathsState.ERROR
    else -> FlowPathsState.UNKNOWN
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
