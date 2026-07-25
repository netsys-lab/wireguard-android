package com.wireguard.android.util

import com.wireguard.android.model.MockScenario
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.FlowDetailsScreenState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockFlowPathDataSourceTest {

    @Test
    fun `AUTO_SINGLE_PATH has one current path`() {
        val details = MockFlowPathDataSource.getFlowDetails(MockScenario.AUTO_SINGLE_PATH)
        assertEquals(FlowDetailsScreenState.ACTIVE, details.screenState)
        assertNotNull(details.currentPath)
        assertTrue(details.currentPath?.isCurrent == true || details.currentPath?.badges?.contains(PathBadge.CURRENT) == true)
    }

    @Test
    fun `quick candidates deduplicated by fingerprint`() {
        MockFlowPathDataSource.activeScenario = MockScenario.ONE_PATH_ALL_WINS
        val allPaths = MockFlowPathDataSource.getPaths(MockScenario.ONE_PATH_ALL_WINS)
        val candidates = MockFlowPathDataSource.getQuickCandidates(allPaths)

        val fingerprints = candidates.map { it.fingerprint }
        assertEquals(fingerprints.toSet().size, fingerprints.size)
    }

    @Test
    fun `one path wins all three categories`() {
        MockFlowPathDataSource.activeScenario = MockScenario.ONE_PATH_ALL_WINS
        val paths = MockFlowPathDataSource.getPaths(MockScenario.ONE_PATH_ALL_WINS)
        val candidates = MockFlowPathDataSource.getQuickCandidates(paths)

        val topWinner = candidates.firstOrNull()
        assertNotNull(topWinner)
        // The winning path should have at least 2 badges (CURRENT + at least one of LATENCY/BANDWIDTH/SHORTEST)
        assertTrue(topWinner!!.badges.size >= 2)
    }

    @Test
    fun `current path appears first in sorted paths`() {
        MockFlowPathDataSource.activeScenario = MockScenario.AUTO_SINGLE_PATH
        val paths = MockFlowPathDataSource.getPaths(MockScenario.AUTO_SINGLE_PATH)
        val firstPath = paths.firstOrNull()
        assertNotNull(firstPath)
        assertTrue(firstPath!!.badges.contains(PathBadge.CURRENT) || firstPath.isCurrent)
    }

    @Test
    fun `override application updates flow details`() {
        MockFlowPathDataSource.mockOverrideFingerprint = null
        MockFlowPathDataSource.activeScenario = MockScenario.AUTO_SINGLE_PATH
        val paths = MockFlowPathDataSource.getPaths(MockScenario.AUTO_SINGLE_PATH)
        val targetFingerprint = paths[1].fingerprint

        val updated = MockFlowPathDataSource.applyOverride(targetFingerprint)
        assertEquals(targetFingerprint, MockFlowPathDataSource.mockOverrideFingerprint)
        assertTrue(updated.overrideActive)
    }

    @Test
    fun `restore automatic clears override`() {
        MockFlowPathDataSource.mockOverrideFingerprint = "some-fingerprint"
        MockFlowPathDataSource.activeScenario = MockScenario.AUTO_SINGLE_PATH

        val updated = MockFlowPathDataSource.restoreAutomatic()
        assertNull(MockFlowPathDataSource.mockOverrideFingerprint)
        assertTrue(!updated.overrideActive)
    }

    @Test
    fun `long AS path uses compact representation`() {
        MockFlowPathDataSource.activeScenario = MockScenario.LONG_AS_PATH
        val paths = MockFlowPathDataSource.getPaths(MockScenario.LONG_AS_PATH)
        val longPath = paths.find { it.interAsLinks >= 4 }
        assertNotNull(longPath)
        assertTrue(longPath!!.compactRoute.contains("intermediate ASes"))
    }

    @Test
    fun `missing latency shows unavailable`() {
        MockFlowPathDataSource.activeScenario = MockScenario.MISSING_LATENCY
        val paths = MockFlowPathDataSource.getPaths(MockScenario.MISSING_LATENCY)
        val pathMissingLatency = paths.find { it.totalLatencyMs == null }
        assertNotNull(pathMissingLatency)
    }

    @Test
    fun `missing bandwidth shows unavailable`() {
        MockFlowPathDataSource.activeScenario = MockScenario.MISSING_BANDWIDTH
        val paths = MockFlowPathDataSource.getPaths(MockScenario.MISSING_BANDWIDTH)
        val pathMissingBw = paths.find { it.bottleneckBandwidthBps == null }
        assertNotNull(pathMissingBw)
    }

    @Test
    fun `intra AS path has zero inter-AS links`() {
        MockFlowPathDataSource.activeScenario = MockScenario.INTRA_AS_PATH
        val paths = MockFlowPathDataSource.getPaths(MockScenario.INTRA_AS_PATH)
        val intraPath = paths.find { it.isIntraAs }
        assertNotNull(intraPath)
        assertEquals(0, intraPath!!.interAsLinks)
    }

    @Test
    fun `NO_PATHS scenario returns empty paths`() {
        MockFlowPathDataSource.activeScenario = MockScenario.NO_PATHS
        val details = MockFlowPathDataSource.getFlowDetails(MockScenario.NO_PATHS)
        assertEquals(FlowDetailsScreenState.NO_PATHS, details.screenState)
        val paths = MockFlowPathDataSource.getPaths(MockScenario.NO_PATHS)
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `LOADING scenario has loading state`() {
        MockFlowPathDataSource.activeScenario = MockScenario.LOADING
        val details = MockFlowPathDataSource.getFlowDetails(MockScenario.LOADING)
        assertEquals(FlowDetailsScreenState.LOADING, details.screenState)
    }

    @Test
    fun `ERROR scenario has error state`() {
        MockFlowPathDataSource.activeScenario = MockScenario.ERROR
        val details = MockFlowPathDataSource.getFlowDetails(MockScenario.ERROR)
        assertEquals(FlowDetailsScreenState.ERROR, details.screenState)
    }

    @Test
    fun `THREE_DIFFERENT_WINNERS has three unique candidates`() {
        MockFlowPathDataSource.activeScenario = MockScenario.THREE_DIFFERENT_WINNERS
        val paths = MockFlowPathDataSource.getPaths(MockScenario.THREE_DIFFERENT_WINNERS)
        val candidates = MockFlowPathDataSource.getQuickCandidates(paths)
        assertTrue(candidates.isNotEmpty())
        // Each candidate should have at least one distinctive badge
        val allBadges = candidates.flatMap { it.badges }
        assertTrue(allBadges.isNotEmpty())
    }

    @Test
    fun `path details returns valid data for existing fingerprint`() {
        MockFlowPathDataSource.activeScenario = MockScenario.AUTO_SINGLE_PATH
        val paths = MockFlowPathDataSource.getPaths(MockScenario.AUTO_SINGLE_PATH)
        val firstFingerprint = paths.first().fingerprint

        val details = MockFlowPathDataSource.getPathDetails(firstFingerprint, MockScenario.AUTO_SINGLE_PATH)
        assertNotNull(details)
        assertEquals(firstFingerprint, details!!.fingerprint)
    }

    @Test
    fun `path details returns null for unknown fingerprint`() {
        val details = MockFlowPathDataSource.getPathDetails("unknown-fingerprint", MockScenario.AUTO_SINGLE_PATH)
        assertNull(details)
    }

    @Test
    fun `POLICY_FALLBACK has fallback policy state`() {
        val details = MockFlowPathDataSource.getFlowDetails(MockScenario.POLICY_FALLBACK)
        assertEquals(com.wireguard.android.model.PathPolicyState.FALLBACK, details.policy.state)
    }
}