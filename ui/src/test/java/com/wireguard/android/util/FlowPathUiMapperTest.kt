package com.wireguard.android.util

import com.wireguard.android.model.FlowPathDomain
import com.wireguard.android.model.FlowPathState
import com.wireguard.android.model.OverrideState
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathQueryState
import com.wireguard.android.model.PathSectionState
import com.wireguard.android.model.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPathUiMapperTest {

    private fun samplePath(fp: String, ifaces: List<String> = listOf("1", "2")): FlowPathDomain {
        return FlowPathDomain(
            fingerprint = fp,
            display = "Path $fp",
            interfaces = ifaces,
            totalLatencyMicros = 10_000,
            bottleneckBandwidthKbps = 100_000,
            interAsLinks = ifaces.size - 1,
        )
    }

    @Test
    fun testActiveOverrideMapsToEffectivePathAndBadges() {
        val p1 = samplePath("fp_zurich_direct")
        val p2 = samplePath("fp_frankfurt_transit")
        val state = FlowPathState(
            flowId = 1,
            queryState = PathQueryState.READY,
            paths = listOf(p1, p2),
            effectiveFingerprint = "fp_frankfurt_transit",
            overrideState = OverrideState.ACTIVE,
            overrideFingerprint = "fp_frankfurt_transit",
            policyState = PolicyState.DEFAULT,
            policyName = "LowestLatency",
        )

        val section = FlowPathUiMapper.toPathSection(state, null)
        assertTrue(section is PathSectionState.Ready)
        val ready = section as PathSectionState.Ready

        assertNotNull("Effective path must not be null", ready.effectivePath)
        assertEquals("fp_frankfurt_transit", ready.effectivePath!!.fingerprint)
        assertTrue("Effective path must be marked isCurrent", ready.effectivePath!!.isCurrent)
        assertTrue("Effective path must have OVERRIDE badge", ready.effectivePath!!.badges.contains(PathBadge.OVERRIDE))
    }

    @Test
    fun testMissingEffectiveFingerprintFallsBackGracefully() {
        val p1 = samplePath("fp_zurich_direct")
        val p2 = samplePath("fp_frankfurt_transit")
        // Scenario where effectiveFingerprint is null, but overrideFingerprint is active
        val state = FlowPathState(
            flowId = 1,
            queryState = PathQueryState.READY,
            paths = listOf(p1, p2),
            effectiveFingerprint = null,
            overrideState = OverrideState.ACTIVE,
            overrideFingerprint = "fp_frankfurt_transit",
            policyState = PolicyState.DEFAULT,
            policyName = "LowestLatency",
        )

        val section = FlowPathUiMapper.toPathSection(state, null)
        assertTrue(section is PathSectionState.Ready)
        val ready = section as PathSectionState.Ready

        assertNotNull("Effective path must not be null even with null effectiveFingerprint", ready.effectivePath)
        assertEquals("fp_frankfurt_transit", ready.effectivePath!!.fingerprint)
        assertTrue(ready.effectivePath!!.isCurrent)
    }

    @Test
    fun testCompletelyMismatchedFingerprintFallsBackToFirstPath() {
        val p1 = samplePath("fp_zurich_direct")
        val p2 = samplePath("fp_frankfurt_transit")
        // Scenario where effectiveFingerprint matches neither path
        val state = FlowPathState(
            flowId = 1,
            queryState = PathQueryState.READY,
            paths = listOf(p1, p2),
            effectiveFingerprint = "fp_unknown_mystery",
            overrideState = OverrideState.INACTIVE,
            policyState = PolicyState.DEFAULT,
        )

        val section = FlowPathUiMapper.toPathSection(state, null)
        assertTrue(section is PathSectionState.Ready)
        val ready = section as PathSectionState.Ready

        assertNotNull("Effective path should fallback to first available path", ready.effectivePath)
        assertEquals("fp_zurich_direct", ready.effectivePath!!.fingerprint)
        assertTrue(ready.effectivePath!!.isCurrent)
    }

    @Test
    fun testQuickCandidatesHasCurrentPathWithCurrentBadge() {
        val p1 = samplePath("fp_zurich_direct")
        val p2 = samplePath("fp_frankfurt_transit")
        val state = FlowPathState(
            flowId = 1,
            queryState = PathQueryState.READY,
            paths = listOf(p1, p2),
            effectiveFingerprint = "fp_frankfurt_transit",
            overrideState = OverrideState.ACTIVE,
            overrideFingerprint = "fp_frankfurt_transit",
            policyState = PolicyState.DEFAULT,
            policyName = "LowestLatency",
        )

        val section = FlowPathUiMapper.toPathSection(state, null) as PathSectionState.Ready
        val quickCandidates = section.quickCandidates
        assertTrue("Quick candidates must not be empty", quickCandidates.isNotEmpty())
        assertEquals("First candidate should be the effective current path", "fp_frankfurt_transit", quickCandidates[0].fingerprint)
        assertTrue("First candidate should have CURRENT badge", quickCandidates[0].badges.contains(PathBadge.CURRENT))
    }
}
