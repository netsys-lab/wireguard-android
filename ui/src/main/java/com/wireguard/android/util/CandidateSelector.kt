package com.wireguard.android.util

import com.wireguard.android.model.FlowPathDomain
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathPreviewUiModel

object CandidateSelector {

    fun deriveBadges(path: FlowPathDomain, allPaths: List<FlowPathDomain>): List<PathBadge> {
        val badges = mutableListOf<PathBadge>()
        val winners = deriveQuickBadgeWinners(allPaths)
        winners[path.fingerprint]?.let { badges.addAll(it) }
        if (path.isIntraAs) badges.add(PathBadge.INTRA_AS)
        return badges.distinct()
    }

    fun quickCandidates(paths: List<PathPreviewUiModel>): List<PathPreviewUiModel> {
        if (paths.isEmpty()) return emptyList()
        val badgeWinners = deriveQuickBadgeWinnersFromPreviews(paths)
        val candidates = mutableListOf<PathPreviewUiModel>()
        val seen = mutableSetOf<String>()

        val current = paths.find { it.isCurrent }
        if (current != null) {
            val currentBadges = badgeWinners[current.fingerprint].orEmpty()
            candidates.add(current.copy(badges = listOf(PathBadge.CURRENT) + currentBadges))
            seen.add(current.fingerprint)
        }

        val sortedByBadges = badgeWinners.entries
            .filter { it.key !in seen }
            .sortedByDescending { it.value.size }
        for ((fingerprint, badges) in sortedByBadges) {
            if (candidates.size >= 3) break
            val path = paths.find { it.fingerprint == fingerprint } ?: continue
            candidates.add(path.copy(badges = badges))
            seen.add(fingerprint)
        }

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

    fun deriveQuickBadgeWinners(paths: List<FlowPathDomain>): Map<String, List<PathBadge>> {
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, MutableList<PathBadge>>()

        val withLatency = paths.filter { it.totalLatencyMicros != null }
        val minLatency = withLatency.minOfOrNull { it.totalLatencyMicros!! }
        if (minLatency != null) {
            for (p in withLatency.filter { it.totalLatencyMicros == minLatency }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.LOWEST_LATENCY)
            }
        }

        val withBw = paths.filter { it.bottleneckBandwidthKbps != null }
        val maxBw = withBw.maxOfOrNull { it.bottleneckBandwidthKbps!! }
        if (maxBw != null) {
            for (p in withBw.filter { it.bottleneckBandwidthKbps == maxBw }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.HIGHEST_BANDWIDTH)
            }
        }

        val minLinks = paths.minOfOrNull { it.interAsLinks }
        if (minLinks != null) {
            for (p in paths.filter { it.interAsLinks == minLinks && !it.isIntraAs }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.SHORTEST_PATH)
            }
        }

        return result
    }

    private fun deriveQuickBadgeWinnersFromPreviews(paths: List<PathPreviewUiModel>): Map<String, List<PathBadge>> {
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, MutableList<PathBadge>>()

        val withLatency = paths.filter { it.totalLatencyMs != null }
        val minLatency = withLatency.minOfOrNull { it.totalLatencyMs!! }
        if (minLatency != null) {
            for (p in withLatency.filter { it.totalLatencyMs == minLatency }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.LOWEST_LATENCY)
            }
        }

        val withBw = paths.filter { it.bottleneckBandwidthBps != null }
        val maxBw = withBw.maxOfOrNull { it.bottleneckBandwidthBps!! }
        if (maxBw != null) {
            for (p in withBw.filter { it.bottleneckBandwidthBps == maxBw }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.HIGHEST_BANDWIDTH)
            }
        }

        val minLinks = paths.minOfOrNull { it.interAsLinks }
        if (minLinks != null) {
            for (p in paths.filter { it.interAsLinks == minLinks }) {
                result.getOrPut(p.fingerprint) { mutableListOf() }.add(PathBadge.SHORTEST_PATH)
            }
        }

        return result
    }
}
