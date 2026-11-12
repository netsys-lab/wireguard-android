package com.wireguard.android.util

import com.wireguard.android.model.FlowPathDomain
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathPreviewUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectorTest {

    @Test
    fun `deriveBadges returns LOWEST_LATENCY for lowest latency path`() {
        val paths = listOf(
            domain("fp1", latency = 5000),
            domain("fp2", latency = 3000),
            domain("fp3", latency = 7000),
        )
        val badges = CandidateSelector.deriveBadges(paths[1], paths)
        assertTrue(badges.contains(PathBadge.LOWEST_LATENCY))
    }

    @Test
    fun `deriveBadges returns HIGHEST_BANDWIDTH for highest bandwidth path`() {
        val paths = listOf(
            domain("fp1", bandwidth = 100_000),
            domain("fp2", bandwidth = 500_000),
            domain("fp3", bandwidth = 200_000),
        )
        val badges = CandidateSelector.deriveBadges(paths[1], paths)
        assertTrue(badges.contains(PathBadge.HIGHEST_BANDWIDTH))
    }

    @Test
    fun `deriveBadges returns SHORTEST_PATH for path with fewest links`() {
        val paths = listOf(
            domain("fp1", links = 3),
            domain("fp2", links = 1),
            domain("fp3", links = 2),
        )
        val badges = CandidateSelector.deriveBadges(paths[1], paths)
        assertTrue(badges.contains(PathBadge.SHORTEST_PATH))
    }

    @Test
    fun `deriveBadges excludes intra-AS from SHORTEST_PATH`() {
        val paths = listOf(
            domain("fp1", links = 1, intraAs = true),
            domain("fp2", links = 1, intraAs = false),
        )
        val badges = CandidateSelector.deriveBadges(paths[0], paths)
        assertTrue(badges.contains(PathBadge.INTRA_AS))
        assertTrue(!badges.contains(PathBadge.SHORTEST_PATH))
    }

    @Test
    fun `deriveBadges can return multiple badges`() {
        val paths = listOf(
            domain("fp1", latency = 3000, bandwidth = 500_000, links = 1),
            domain("fp2", latency = 5000, bandwidth = 100_000, links = 3),
        )
        val badges = CandidateSelector.deriveBadges(paths[0], paths)
        assertTrue(badges.contains(PathBadge.LOWEST_LATENCY))
        assertTrue(badges.contains(PathBadge.HIGHEST_BANDWIDTH))
        assertTrue(badges.contains(PathBadge.SHORTEST_PATH))
    }

    @Test
    fun `deriveBadges returns empty for no competition`() {
        val badges = CandidateSelector.deriveBadges(domain("fp1"), listOf(domain("fp1")))
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `deriveBadges returns empty when metadata missing`() {
        val paths = listOf(
            FlowPathDomain(fingerprint = "fp1", interfaces = listOf("A", "B")),
            FlowPathDomain(fingerprint = "fp2", interfaces = listOf("A", "C")),
        )
        val badges = CandidateSelector.deriveBadges(paths[0], paths)
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `quickCandidates currrent path first`() {
        val previews = listOf(
            preview("fp1", isCurrent = false),
            preview("fp2", isCurrent = true),
            preview("fp3", isCurrent = false),
        )
        val candidates = CandidateSelector.quickCandidates(previews)
        assertEquals("fp2", candidates.first().fingerprint)
    }

    @Test
    fun `quickCandidates limited to three`() {
        val previews = (1..10).map { preview("fp$it") }
        val candidates = CandidateSelector.quickCandidates(previews)
        assertTrue(candidates.size <= 3)
    }

    @Test
    fun `quickCandidates returns empty for empty input`() {
        assertTrue(CandidateSelector.quickCandidates(emptyList()).isEmpty())
    }

    @Test
    fun `quickCandidates current gets CURRENT badge`() {
        val candidates = CandidateSelector.quickCandidates(
            listOf(preview("fp1", isCurrent = true))
        )
        assertEquals(1, candidates.size)
        assertTrue(candidates[0].badges.contains(PathBadge.CURRENT))
    }

    @Test
    fun `deriveQuickBadgeWinners uses latency in micros`() {
        val paths = listOf(
            domain("fp1", latency = 5000),
            domain("fp2", latency = 2000),
        )
        val winners = CandidateSelector.deriveQuickBadgeWinners(paths)
        val badges = winners["fp2"] ?: emptyList()
        assertTrue(badges.contains(PathBadge.LOWEST_LATENCY))
    }

    @Test
    fun `deriveQuickBadgeWinners handles ties in latency`() {
        val paths = listOf(
            domain("fp1", latency = 3000),
            domain("fp2", latency = 3000),
            domain("fp3", latency = 5000),
        )
        val winners = CandidateSelector.deriveQuickBadgeWinners(paths)
        assertTrue(winners.containsKey("fp1"))
        assertTrue(winners.containsKey("fp2"))
    }

    @Test
    fun `deriveQuickBadgeWinners handles null latency gracefully`() {
        val paths = listOf(
            FlowPathDomain(fingerprint = "fp1", interfaces = listOf("A", "B")),
            domain("fp2", latency = 3000),
        )
        val winners = CandidateSelector.deriveQuickBadgeWinners(paths)
        assertNotNull(winners["fp2"])
        assertTrue(!winners.containsKey("fp1"))
    }

    private fun domain(
        fingerprint: String,
        latency: Long? = null,
        bandwidth: Long? = null,
        links: Int = 1,
        intraAs: Boolean = false,
    ) = FlowPathDomain(
        fingerprint = fingerprint,
        interfaces = if (intraAs) listOf("A") else listOf("A", "B"),
        totalLatencyMicros = latency,
        bottleneckBandwidthKbps = bandwidth,
        interAsLinks = links,
    )

    private fun preview(fingerprint: String, isCurrent: Boolean = false) = PathPreviewUiModel(
        fingerprint = fingerprint,
        badges = emptyList(),
        compactRoute = "A → B",
        totalLatencyMs = null,
        bottleneckBandwidthBps = null,
        interAsLinks = 1,
        mtu = 1500,
        expirySeconds = null,
        isCurrent = isCurrent,
        isIntraAs = false,
    )
}
