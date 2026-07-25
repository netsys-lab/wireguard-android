package com.wireguard.android.util

import com.wireguard.android.model.FlowDetailsScreenState
import com.wireguard.android.model.FlowDetailsUiModel
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEndpointDto
import com.wireguard.android.model.FlowPathDto
import com.wireguard.android.model.GeoDto
import com.wireguard.android.model.HopDetailUiModel
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathDetailsUiModel
import com.wireguard.android.model.PathPolicyState
import com.wireguard.android.model.PathPolicyUiModel
import com.wireguard.android.model.PathPreviewUiModel

enum class MockScenario(val label: String) {
    AUTO_SINGLE_PATH("Automatic policy, one current path"),
    ONE_PATH_ALL_WINS("One path wins all three categories"),
    THREE_DIFFERENT_WINNERS("Three different paths win the three categories"),
    OVERRIDE_ACTIVE("Manual override active"),
    POLICY_FALLBACK("Policy fallback active"),
    MISSING_LATENCY("Missing latency metadata"),
    MISSING_BANDWIDTH("Missing bandwidth metadata"),
    LONG_AS_PATH("Long AS path"),
    INTRA_AS_PATH("Intra-AS path"),
    NO_PATHS("No available paths"),
    LOADING("Loading"),
    ERROR("Error"),
}

object MockFlowPathDataSource {

    var activeScenario: MockScenario = MockScenario.AUTO_SINGLE_PATH
    var mockOverrideFingerprint: String? = null

    // ── Mock Flow ─────────────────────────────────────────────

    private val mockFlow = FlowDto(
        id = 1842L,
        ipVersion = 6,
        protocol = 17,
        endpointA = FlowEndpointDto("10.0.0.2", 52914),
        endpointB = FlowEndpointDto("10.0.0.5", 443),
        status = "ACTIVE",
        txPackets = 38000,
        txBytes = 30480000L,
        rxPackets = 4000,
        rxBytes = 2275000L,
        egressKind = "scion",
        srcIA = "1-ff00:0:110",
        dstIA = "1-ff00:0:111",
        createdAt = "2026-07-24T10:00:00Z",
        lastSeen = "2026-07-24T10:30:00Z",
        localIP = "10.0.0.2",
        localPort = 52914,
        remoteIP = "10.0.0.5",
        remotePort = 443,
        scionDstIP = "10.0.0.5",
    )

    // ── Mock Paths ────────────────────────────────────────────

    private val pathA = FlowPathDto(
        fingerprint = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
        display = "1-ff00:0:110 → 1-ff00:0:120 → 1-ff00:0:111",
        current = true,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:120", "1-ff00:0:111"),
        latencyMs = listOf(1.2, 8.4, 2.4),
        bandwidth = listOf(40_000_000_000L, 10_000_000_000L, 100_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(50.11, 8.68, "Frankfurt"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Destination"),
        internalHops = listOf(1, 2, 0),
        mtu = 1472,
        expiry = "2026-07-24T10:48:00Z",
        nextHop = "192.168.1.1",
    )

    private val pathB = FlowPathDto(
        fingerprint = "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3",
        display = "1-ff00:0:110 → 1-ff00:0:130 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:130", "1-ff00:0:111"),
        latencyMs = listOf(3.0, 18.0, 3.0),
        bandwidth = listOf(100_000_000_000L, 40_000_000_000L, 100_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(52.37, 4.90, "Amsterdam"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Destination"),
        internalHops = listOf(1, 3, 0),
        mtu = 1500,
        expiry = "2026-07-24T10:41:00Z",
        nextHop = "192.168.1.2",
    )

    private val pathC = FlowPathDto(
        fingerprint = "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4",
        display = "1-ff00:0:110 → 1-ff00:0:140 → 1-ff00:0:150 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:140", "1-ff00:0:150", "1-ff00:0:111"),
        latencyMs = listOf(2.0, 15.0, 10.0, 4.0),
        bandwidth = listOf(20_000_000_000L, 20_000_000_000L, 25_000_000_000L, 100_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(48.86, 2.35, "Paris"), GeoDto(50.85, 4.35, "Brussels"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Transit", "Destination"),
        internalHops = listOf(1, 2, 2, 0),
        mtu = 9000,
        expiry = "2026-07-24T10:55:00Z",
    )

    private val pathD = FlowPathDto(
        fingerprint = "d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5",
        display = "1-ff00:0:110 → 1-ff00:0:160 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:160", "1-ff00:0:111"),
        latencyMs = listOf(4.0, 11.0, 4.0),
        bandwidth = listOf(8_000_000_000L, 10_000_000_000L, 50_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(45.46, 9.19, "Milan"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Destination"),
        internalHops = listOf(1, 1, 0),
        mtu = 1472,
        expiry = "2026-07-24T10:39:00Z",
    )

    private val pathE = FlowPathDto(
        fingerprint = "e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6",
        display = "1-ff00:0:110 → 1-ff00:0:170 → 1-ff00:0:180 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:170", "1-ff00:0:180", "1-ff00:0:111"),
        latencyMs = null,
        bandwidth = listOf(15_000_000_000L, 20_000_000_000L, 25_000_000_000L, 20_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(0.0, 0.0), GeoDto(48.21, 16.37, "Vienna"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Transit", "Destination"),
        internalHops = listOf(1, 3, 2, 0),
        mtu = 1500,
        expiry = "2026-07-24T10:44:00Z",
    )

    private val pathF = FlowPathDto(
        fingerprint = "f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7",
        display = "1-ff00:0:110 → 1-ff00:0:190 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:190", "1-ff00:0:111"),
        latencyMs = listOf(5.0, 19.0, 4.0),
        bandwidth = listOf(6_000_000_000L, 8_000_000_000L, 20_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(52.52, 13.41, "Berlin"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Destination"),
        internalHops = listOf(1, 2, 0),
        mtu = 1472,
        expiry = "2026-07-24T11:12:00Z",
    )

    private val pathG = FlowPathDto(
        fingerprint = "a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2",
        display = "1-ff00:0:110",
        current = false,
        interfaces = listOf("1-ff00:0:110"),
        latencyMs = listOf(5.0),
        bandwidth = listOf(100_000_000_000L),
        geo = listOf(GeoDto(47.38, 8.54, "Zurich")),
        linkType = listOf("Direct"),
        internalHops = listOf(0),
        mtu = 9000,
        expiry = "2026-07-24T11:00:00Z",
    )

    private val longPath = FlowPathDto(
        fingerprint = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7",
        display = "1-ff00:0:110 → 1-ff00:0:210 → 1-ff00:0:220 → 1-ff00:0:230 → 1-ff00:0:240 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:210", "1-ff00:0:220", "1-ff00:0:230", "1-ff00:0:240", "1-ff00:0:111"),
        latencyMs = listOf(1.0, 8.0, 7.0, 9.0, 6.0, 3.0),
        bandwidth = listOf(40_000_000_000L, 10_000_000_000L, 10_000_000_000L, 5_000_000_000L, 10_000_000_000L, 50_000_000_000L),
        geo = listOf(
            GeoDto(47.38, 8.54, "Zurich"), GeoDto(48.14, 11.58, "Munich"),
            GeoDto(50.08, 14.44, "Prague"), GeoDto(52.23, 21.01, "Warsaw"),
            GeoDto(59.93, 30.34, "Saint Petersburg"), GeoDto(51.51, -0.13, "London")
        ),
        linkType = listOf("Direct", "Transit", "Transit", "Transit", "Transit", "Destination"),
        internalHops = listOf(1, 2, 1, 2, 3, 0),
        mtu = 1472,
        expiry = "2026-07-24T10:50:00Z",
    )

    // Path with null bandwidth (for MISSING_BANDWIDTH scenario)
    private val pathNoBw = FlowPathDto(
        fingerprint = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3",
        display = "1-ff00:0:110 → 1-ff00:0:125 → 1-ff00:0:111",
        current = false,
        interfaces = listOf("1-ff00:0:110", "1-ff00:0:125", "1-ff00:0:111"),
        latencyMs = listOf(2.0, 10.0, 3.0),
        bandwidth = null,
        geo = listOf(GeoDto(47.38, 8.54, "Zurich"), GeoDto(48.86, 2.35, "Paris"), GeoDto(51.51, -0.13, "London")),
        linkType = listOf("Direct", "Transit", "Destination"),
        internalHops = listOf(1, 2, 0),
        mtu = 1472,
        expiry = "2026-07-24T10:45:00Z",
    )

    private val allRegularPaths = listOf(pathA, pathB, pathC, pathD, pathE, pathF)
    private val allPathsWithIntra = listOf(pathA, pathB, pathC, pathD, pathE, pathF, pathG)
    private val allPathsWithLong = listOf(pathA, pathB, pathC, pathD, pathE, pathF, longPath)
    private val allPathsWithNoBw = listOf(pathA, pathNoBw, pathC, pathD, pathE, pathF)

    // ── Public API ────────────────────────────────────────────

    fun getFlowDetails(scenario: MockScenario = activeScenario): FlowDetailsUiModel {
        val config = getScenarioConfig(scenario)
        val paths = getScenarioPaths(scenario)
        val now = System.currentTimeMillis()

        val currentPath = if (config.screenState == FlowDetailsScreenState.ACTIVE && paths.isNotEmpty()) {
            val effectivePaths = if (mockOverrideFingerprint != null && scenario == activeScenario) {
                val overridden = paths.find { it.fingerprint == mockOverrideFingerprint }
                if (overridden != null) {
                    paths.map { it.copy(current = it.fingerprint == mockOverrideFingerprint) }
                } else paths
            } else paths

            val currentDto = effectivePaths.find { it.current }
            if (currentDto != null) {
                val badges = mutableListOf<PathBadge>()
                if (mockOverrideFingerprint != null && currentDto.fingerprint == mockOverrideFingerprint) {
                    badges.add(PathBadge.OVERRIDE)
                } else if (currentDto.current) {
                    badges.add(PathBadge.CURRENT)
                }
                badges.addAll(deriveCandidateBadges(currentDto, effectivePaths))
                toPathPreview(currentDto, badges)
            } else null
        } else null

        val policyState = when {
            mockOverrideFingerprint != null && scenario == activeScenario && config.policyState == PathPolicyState.AUTOMATIC ->
                PathPolicyState.OVERRIDE_ACTIVE
            else -> config.policyState
        }

        val policyDescription = when (policyState) {
            PathPolicyState.OVERRIDE_ACTIVE -> "Per-flow override active. The configured automatic policy remains available and can be restored at any time."
            PathPolicyState.FALLBACK -> "Policy engine is in fallback mode. Some automatic selection features may be unavailable."
            PathPolicyState.AUTOMATIC -> "Automatically chooses the path with the lowest complete advertised latency. No per-flow override is active."
            PathPolicyState.NAMED_POLICY -> "Using the configured policy to automatically select the best path for this flow."
            PathPolicyState.NONE -> "No automatic policy is configured."
        }

        return FlowDetailsUiModel(
            flowId = mockFlow.id,
            screenState = config.screenState,
            protocol = "UDP",
            ipVersion = 6,
            localEndpoint = "10.0.0.2:52914",
            destinationIA = mockFlow.dstIA ?: "",
            destinationHost = "10.0.0.5:443",
            txBitRate = "243.9 kbit/s",
            txPacketRate = "38 pkt/s",
            rxBitRate = "18.2 kbit/s",
            rxPacketRate = "4 pkt/s",
            totalTxBytes = mockFlow.txBytes,
            totalRxBytes = mockFlow.rxBytes,
            policy = PathPolicyUiModel(
                name = config.policyName,
                state = policyState,
                description = policyDescription,
            ),
            currentPath = currentPath,
            overrideActive = mockOverrideFingerprint != null,
            availablePathCount = paths.size,
        )
    }

    fun getPaths(scenario: MockScenario = activeScenario): List<PathPreviewUiModel> {
        val paths = getScenarioPaths(scenario)
        if (paths.isEmpty()) return emptyList()
        val candidates = deriveCandidateBadgesForAll(paths)
        val sorted = candidates.sortedByDescending { it.first.current }
        return sorted.map { (dto, badges) -> toPathPreview(dto, badges) }
    }

    /**
     * Derives quick candidates (up to 3 unique paths) based on:
     * - Lowest total latency  → LOWEST_LATENCY badge
     * - Highest bottleneck bandwidth → HIGHEST_BANDWIDTH badge
     * - Fewest inter-AS links → SHORTEST_PATH badge
     * Deduplicates by fingerprint. Sorts current path first.
     */
    fun getQuickCandidates(paths: List<PathPreviewUiModel>): List<PathPreviewUiModel> {
        if (paths.isEmpty()) return emptyList()
        val badgeWinners = deriveQuickBadgeWinners(paths)
        val candidates = mutableListOf<PathPreviewUiModel>()
        val seen = mutableSetOf<String>()
        // Current first
        val current = paths.find { it.isCurrent }
        if (current != null) {
            val currentBadges = badgeWinners[current.fingerprint].orEmpty()
            candidates.add(current.copy(badges = listOf(PathBadge.CURRENT) + currentBadges))
            seen.add(current.fingerprint)
        }
        // Then by badge count descending
        val sortedByBadges = badgeWinners.entries
            .filter { it.key !in seen }
            .sortedByDescending { it.value.size }
        for ((fingerprint, badges) in sortedByBadges) {
            if (candidates.size >= 3) break
            val path = paths.find { it.fingerprint == fingerprint } ?: continue
            candidates.add(path.copy(badges = badges))
            seen.add(fingerprint)
        }
        // Fill remaining slots with non-winners
        if (candidates.size < 3) {
            for (path in paths) {
                if (candidates.size >= 3) break
                if (path.fingerprint !in seen) {
                    candidates.add(path.copy(badges = emptyList()))
                    seen.add(path.fingerprint)
                }
            }
        }
        return candidates
    }

    fun getPathDetails(fingerprint: String, scenario: MockScenario = activeScenario): PathDetailsUiModel? {
        val paths = getScenarioPaths(scenario)
        val dto = paths.find { it.fingerprint == fingerprint } ?: return null
        val badges = deriveCandidateBadges(dto, paths)
        val source = when {
            mockOverrideFingerprint == fingerprint && mockOverrideFingerprint != null ->
                "Selected by manual override"
            dto.current -> {
                val policy = getScenarioConfig(scenario).policyName
                "Selected automatically by the \"$policy\" policy"
            }
            else -> ""
        }
        return toPathDetails(dto, badges, source)
    }

    /** Applies a mock override for the given fingerprint. Call only on ACTIVE scenarios. */
    fun applyOverride(fingerprint: String): FlowDetailsUiModel {
        mockOverrideFingerprint = fingerprint
        return getFlowDetails(activeScenario)
    }

    /** Clears the mock override and returns updated flow details. */
    fun restoreAutomatic(): FlowDetailsUiModel {
        mockOverrideFingerprint = null
        return getFlowDetails(activeScenario)
    }

    // ── Scenario Configuration ────────────────────────────────

    private data class ScenarioConfig(
        val screenState: FlowDetailsScreenState = FlowDetailsScreenState.ACTIVE,
        val policyName: String = "Lowest Latency",
        val policyState: PathPolicyState = PathPolicyState.AUTOMATIC,
    )

    private fun getScenarioConfig(scenario: MockScenario): ScenarioConfig = when (scenario) {
        MockScenario.AUTO_SINGLE_PATH -> ScenarioConfig()
        MockScenario.ONE_PATH_ALL_WINS -> ScenarioConfig(
            policyState = PathPolicyState.NAMED_POLICY,
            policyName = "Best Overall",
        )
        MockScenario.THREE_DIFFERENT_WINNERS -> ScenarioConfig()
        MockScenario.OVERRIDE_ACTIVE -> ScenarioConfig(
            policyState = PathPolicyState.AUTOMATIC,
        )
        MockScenario.POLICY_FALLBACK -> ScenarioConfig(policyState = PathPolicyState.FALLBACK)
        MockScenario.MISSING_LATENCY -> ScenarioConfig()
        MockScenario.MISSING_BANDWIDTH -> ScenarioConfig()
        MockScenario.LONG_AS_PATH -> ScenarioConfig(policyName = "Latency Optimizer")
        MockScenario.INTRA_AS_PATH -> ScenarioConfig()
        MockScenario.NO_PATHS -> ScenarioConfig(
            screenState = FlowDetailsScreenState.NO_PATHS,
        )
        MockScenario.LOADING -> ScenarioConfig(
            screenState = FlowDetailsScreenState.LOADING,
        )
        MockScenario.ERROR -> ScenarioConfig(
            screenState = FlowDetailsScreenState.ERROR,
        )
    }

    private fun getScenarioPaths(scenario: MockScenario): List<FlowPathDto> = when (scenario) {
        MockScenario.AUTO_SINGLE_PATH -> allRegularPaths
        MockScenario.ONE_PATH_ALL_WINS -> allRegularPaths
        MockScenario.THREE_DIFFERENT_WINNERS -> allRegularPaths.take(3)
        MockScenario.OVERRIDE_ACTIVE -> allRegularPaths
        MockScenario.POLICY_FALLBACK -> allRegularPaths
        MockScenario.MISSING_LATENCY -> allRegularPaths // pathE has null latency
        MockScenario.MISSING_BANDWIDTH -> allPathsWithNoBw
        MockScenario.LONG_AS_PATH -> allPathsWithLong
        MockScenario.INTRA_AS_PATH -> allPathsWithIntra
        MockScenario.NO_PATHS -> emptyList()
        MockScenario.LOADING -> emptyList()
        MockScenario.ERROR -> emptyList()
    }

    // ── Badge Derivation ──────────────────────────────────────

    private fun deriveCandidateBadges(path: FlowPathDto, allPaths: List<FlowPathDto>): List<PathBadge> {
        val badges = mutableListOf<PathBadge>()
        val winners = deriveQuickBadgeWinners(allPaths.map { toPathPreview(it, emptyList()) })
        winners[path.fingerprint]?.let { badges.addAll(it) }
        return badges.distinct()
    }

    private fun deriveCandidateBadgesForAll(paths: List<FlowPathDto>): List<Pair<FlowPathDto, List<PathBadge>>> {
        val previews = paths.map { toPathPreview(it, emptyList()) }
        val winners = deriveQuickBadgeWinners(previews)
        return paths.map { dto ->
            val badges = mutableListOf<PathBadge>()
            if (dto.current) badges.add(PathBadge.CURRENT)
            winners[dto.fingerprint]?.let { badges.addAll(it) }
            if (badges.contains(PathBadge.CURRENT) && mockOverrideFingerprint == dto.fingerprint) {
                badges.add(PathBadge.OVERRIDE)
            }
            if (badges.contains(PathBadge.CURRENT) && mockOverrideFingerprint != null && mockOverrideFingerprint != dto.fingerprint) {
                badges.remove(PathBadge.CURRENT)
            }
            dto to badges.distinct()
        }
    }

    private fun deriveQuickBadgeWinners(paths: List<PathPreviewUiModel>): Map<String, List<PathBadge>> {
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, MutableList<PathBadge>>()

        // Lowest latency
        val withLatency = paths.filter { it.totalLatencyMs != null }
        val minLatency = withLatency.minOfOrNull { it.totalLatencyMs!! }
        if (minLatency != null) {
            for (p in withLatency.filter { it.totalLatencyMs == minLatency }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.LOWEST_LATENCY)
            }
        }

        // Highest bandwidth
        val withBw = paths.filter { it.bottleneckBandwidthBps != null }
        val maxBw = withBw.maxOfOrNull { it.bottleneckBandwidthBps!! }
        if (maxBw != null) {
            for (p in withBw.filter { it.bottleneckBandwidthBps == maxBw }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.HIGHEST_BANDWIDTH)
            }
        }

        // Shortest path (fewest inter-AS links)
        val minLinks = paths.minOfOrNull { it.interAsLinks }
        if (minLinks != null) {
            for (p in paths.filter { it.interAsLinks == minLinks && !it.isIntraAs }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.SHORTEST_PATH)
            }
        }

        return result
    }

    // ── Conversions ───────────────────────────────────────────

    fun toPathPreview(dto: FlowPathDto, badges: List<PathBadge>): PathPreviewUiModel {
        val ifaces = dto.interfaces ?: emptyList()
        val isIntraAs = ifaces.size <= 1
        val compactRoute = deriveCompactRoute(ifaces, dto.display)
        val totalLatency = dto.latencyMs?.let { lats ->
            if (lats.isEmpty()) null else lats.sum().toLong()
        }
        val bottleneck = dto.bandwidth?.let { bw ->
            if (bw.isEmpty()) null else bw.min()
        }
        val links = if (ifaces.size > 1) ifaces.size - 1 else 0
        val expirySecs = dto.expiry?.let { parseExpirySeconds(it) }
        return PathPreviewUiModel(
            fingerprint = dto.fingerprint,
            badges = badges,
            compactRoute = compactRoute,
            totalLatencyMs = totalLatency,
            bottleneckBandwidthBps = bottleneck,
            interAsLinks = links,
            mtu = dto.mtu ?: 0,
            expirySeconds = expirySecs,
            isCurrent = dto.current,
            isIntraAs = isIntraAs,
        )
    }

    private fun toPathDetails(dto: FlowPathDto, badges: List<PathBadge>, source: String): PathDetailsUiModel {
        val ifaces = dto.interfaces ?: emptyList()
        val totalLatency = dto.latencyMs?.let { lats ->
            if (lats.isEmpty()) null else lats.sum().toLong()
        }
        val bottleneck = dto.bandwidth?.let { bw ->
            if (bw.isEmpty()) null else bw.min()
        }
        val links = if (ifaces.size > 1) ifaces.size - 1 else 0
        val expirySecs = dto.expiry?.let { parseExpirySeconds(it) }

        val geoParts = dto.geo?.mapNotNull { it.address?.takeIf { addr -> addr.isNotBlank() } } ?: emptyList()
        val geoSummary = if (geoParts.isEmpty()) "" else geoParts.joinToString(" → ")

        val hops = ifaces.mapIndexed { index, isa ->
            val latencyAtHop = dto.latencyMs?.getOrNull(index)
            val bwAtHop = dto.bandwidth?.getOrNull(index)
            val geoAtHop = dto.geo?.getOrNull(index)
            val linkTypeAtHop = dto.linkType?.getOrNull(index)
            val internalHopsAtHop = dto.internalHops?.getOrNull(index)
            val notes = dto.notes?.getOrNull(index)

            val role = when {
                index == 0 -> "Local AS"
                index == ifaces.size - 1 -> "Destination AS"
                else -> "Transit"
            }

            HopDetailUiModel(
                hopNumber = index + 1,
                isa = isa,
                ingressInterface = if (index > 0) index * 10 else null,
                egressInterface = if (index < ifaces.size - 1) (index + 1) * 10 else null,
                latencyMs = latencyAtHop,
                bandwidthBps = bwAtHop,
                internalHops = internalHopsAtHop,
                location = geoAtHop?.address?.takeIf { it.isNotBlank() },
                linkType = linkTypeAtHop,
                role = role,
                notes = notes,
            )
        }

        return PathDetailsUiModel(
            fingerprint = dto.fingerprint,
            badges = badges,
            fullRoute = dto.display,
            selectionSource = source,
            latencyMs = totalLatency,
            bandwidthBps = bottleneck,
            interAsLinks = links,
            mtu = dto.mtu ?: 0,
            expirySeconds = expirySecs,
            geoSummary = geoSummary,
            hops = hops,
        )
    }

    // ── Formatting Helpers ────────────────────────────────────

    private fun deriveCompactRoute(interfaces: List<String>, display: String): String {
        if (interfaces.isEmpty()) return display
        if (interfaces.size <= 3) return display
        val source = interfaces.first()
        val dest = interfaces.last()
        val intermediateCount = interfaces.size - 2
        return "$source → $intermediateCount intermediate ASes → $dest"
    }

    private fun parseExpirySeconds(expiry: String): Long? {
        return try {
            val instant = java.time.Instant.parse(expiry)
            val now = java.time.Instant.now()
            val seconds = java.time.Duration.between(now, instant).seconds
            if (seconds > 0) seconds else null
        } catch (_: Exception) {
            null
        }
    }
}