package com.wireguard.android.viewmodel

import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowContextState
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEndpointDto
import com.wireguard.android.model.FlowPathDomain
import com.wireguard.android.model.FlowPathState
import com.wireguard.android.model.OverrideState
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathQueryState
import com.wireguard.android.model.PathSectionState
import com.wireguard.android.model.PolicyState
import com.wireguard.android.util.FlowPathRepository
import com.wireguard.android.util.FlowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPathViewModelTest {

    private val testTunnel = Tunnel("test-tunnel")

    @Test
    fun `init loads flow context and paths`() {
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = pathState(flowId = 42))
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        val ctx = runBlocking { vm.flowContext.first { it !is FlowContextState.Loading } }
        assertTrue(ctx is FlowContextState.Ready)
        assertEquals(42L, (ctx as FlowContextState.Ready).flowId)
    }

    @Test
    fun `init with flowSnapshot skips loading flow`() {
        val snapshot = makeFlow(42)
        val flowRepo = FakeFlowRepository(flow = makeFlow(99))
        val pathRepo = FakeFlowPathRepository()
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, snapshot)

        val ctx = runBlocking { vm.flowContext.first() }
        assertTrue(ctx is FlowContextState.Ready)
        assertEquals(42L, (ctx as FlowContextState.Ready).flowId)
    }

    @Test
    fun `loadFlowContext emits error on failure`() {
        val flowRepo = FakeFlowRepository(error = true)
        val pathRepo = FakeFlowPathRepository()
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        val ctx = runBlocking { vm.flowContext.first { it !is FlowContextState.Loading } }
        assertTrue(ctx is FlowContextState.Error)
    }

    @Test
    fun `loadPaths emits Ready with paths`() {
        val path = FlowPathDomain(
            fingerprint = "fp1",
            interfaces = listOf("A", "B"),
            totalLatencyMicros = 5000,
            bottleneckBandwidthKbps = 100_000,
            interAsLinks = 1,
        )
        val domain = pathState(
            flowId = 42,
            paths = listOf(path),
            effectiveFingerprint = "fp1",
        )
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        val section = runBlocking { vm.pathSection.first { it !is PathSectionState.Loading } }
        assertTrue(section is PathSectionState.Ready)
        val ready = section as PathSectionState.Ready
        assertEquals(1, ready.paths.size)
        assertEquals("fp1", ready.effectiveFingerprint)
    }

    @Test
    fun `loadPaths emits Empty for no paths`() {
        val domain = pathState(flowId = 42, queryState = PathQueryState.EMPTY)
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        val section = runBlocking { vm.pathSection.first { it !is PathSectionState.Loading } }
        assertTrue(section is PathSectionState.Empty)
    }

    @Test
    fun `loadPaths emits Error on failure`() {
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(error = true)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        val section = runBlocking { vm.pathSection.first { it !is PathSectionState.Loading } }
        assertTrue(section is PathSectionState.Error)
    }

    @Test
    fun `selectPathDetails returns correct details`() {
        val path = FlowPathDomain(
            fingerprint = "fp1",
            interfaces = listOf("A", "B"),
            display = "A → B",
            totalLatencyMicros = 5000,
            bottleneckBandwidthKbps = 100_000,
            interAsLinks = 1,
        )
        val domain = pathState(
            flowId = 42,
            paths = listOf(path),
            effectiveFingerprint = "fp1",
        )
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        vm.selectPathDetails("fp1")
        val details = runBlocking { vm.selectedPathDetails.first { it != null } }
        assertNotNull(details)
        assertEquals("fp1", details!!.fingerprint)
        assertEquals("A → B", details.fullRoute)
    }

    @Test
    fun `selectPathDetails applies override badge`() {
        val path = FlowPathDomain(
            fingerprint = "fp1",
            interfaces = listOf("A", "B"),
        )
        val domain = pathState(
            flowId = 42,
            paths = listOf(path),
            effectiveFingerprint = "fp1",
            overrideState = OverrideState.ACTIVE,
            overrideFingerprint = "fp1",
        )
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        vm.selectPathDetails("fp1")
        val details = runBlocking { vm.selectedPathDetails.first { it != null } }
        assertTrue(details!!.badges.contains(PathBadge.OVERRIDE))
    }

    @Test
    fun `clearSelectedPathDetails resets details`() {
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository()
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        vm.clearSelectedPathDetails()
        val details = runBlocking { vm.selectedPathDetails.first() }
        assertNull(details)
    }

    @Test
    fun `isEffectivePath returns true for effective fingerprint`() {
        val path = FlowPathDomain(fingerprint = "fp1", interfaces = listOf("A", "B"))
        val domain = pathState(
            flowId = 42,
            paths = listOf(path),
            effectiveFingerprint = "fp1",
        )
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        runBlocking { vm.pathSection.first { it !is PathSectionState.Loading } }
        assertTrue(vm.isEffectivePath("fp1"))
    }

    @Test
    fun `isEffectivePath returns false for non-effective fingerprint`() {
        val path = FlowPathDomain(fingerprint = "fp1", interfaces = listOf("A", "B"))
        val domain = pathState(flowId = 42, paths = listOf(path), effectiveFingerprint = "fp1")
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        runBlocking { vm.pathSection.first { it !is PathSectionState.Loading } }
        assertTrue(!vm.isEffectivePath("fp2"))
    }

    @Test
    fun `policy and override state propagate to path section`() {
        val path = FlowPathDomain(fingerprint = "fp1", interfaces = listOf("A", "B"))
        val domain = FlowPathState(
            flowId = 42,
            queryState = PathQueryState.READY,
            paths = listOf(path),
            effectiveFingerprint = "fp1",
            overrideState = OverrideState.STALE,
            overrideFingerprint = "fp1",
            policyState = PolicyState.FALLBACK,
            policyName = "Fallback Policy",
            policyFallbackApplied = true,
        )
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        val section = runBlocking { vm.pathSection.first { it !is PathSectionState.Loading } }
        val ready = section as PathSectionState.Ready
        assertEquals(OverrideState.STALE, ready.overrideState)
        assertEquals("fp1", ready.overrideFingerprint)
        assertEquals(PolicyState.FALLBACK, ready.policyState)
        assertEquals("Fallback Policy", ready.policyName)
    }

    @Test
    fun `deriveSelectionSource shows override source`() {
        val path = FlowPathDomain(fingerprint = "fp1", interfaces = listOf("A", "B"))
        val domain = pathState(
            flowId = 42,
            paths = listOf(path),
            effectiveFingerprint = "fp1",
            overrideState = OverrideState.ACTIVE,
            overrideFingerprint = "fp1",
        )
        val flowRepo = FakeFlowRepository(flow = makeFlow(42))
        val pathRepo = FakeFlowPathRepository(state = domain)
        val vm = FlowPathViewModel(flowRepo, pathRepo, testTunnel, 42, null)

        vm.selectPathDetails("fp1")
        val details = runBlocking { vm.selectedPathDetails.first { it != null } }
        assertTrue(details!!.selectionSource.contains("manual override"))
    }

    private fun makeFlow(id: Long): FlowDto = FlowDto(
        id = id,
        ipVersion = 6,
        protocol = 17,
        endpointA = FlowEndpointDto("10.0.0.2", 52914),
        endpointB = FlowEndpointDto("10.0.0.5", 443),
        status = "ACTIVE",
        txPackets = 100,
        txBytes = 1000,
        rxPackets = 50,
        rxBytes = 500,
        egressKind = "scion",
        srcIA = "1-ff00:0:110",
        dstIA = "1-ff00:0:111",
    )

    private fun pathState(
        flowId: Long = 42,
        queryState: PathQueryState = PathQueryState.READY,
        paths: List<FlowPathDomain> = emptyList(),
        effectiveFingerprint: String? = null,
        overrideState: OverrideState = OverrideState.INACTIVE,
        overrideFingerprint: String? = null,
        policyState: PolicyState = PolicyState.DEFAULT,
        policyName: String? = null,
    ) = FlowPathState(
        flowId = flowId,
        queryState = queryState,
        paths = paths,
        effectiveFingerprint = effectiveFingerprint,
        overrideState = overrideState,
        overrideFingerprint = overrideFingerprint,
        policyState = policyState,
        policyName = policyName,
    )

    private class FakeFlowRepository(
        private val flow: FlowDto? = null,
        private val error: Boolean = false,
    ) : FlowRepository {
        override suspend fun getFlows(tunnel: Tunnel): Result<List<FlowDto>> {
            if (error) return Result.failure(Exception("flow fetch failed"))
            return Result.success(flow?.let { listOf(it) } ?: emptyList())
        }

        override suspend fun getFlowById(tunnel: Tunnel, flowId: Long): Result<FlowDto> {
            if (error) return Result.failure(Exception("flow not found"))
            return flow?.let { Result.success(it) }
                ?: Result.failure(Exception("flow not found"))
        }

        override suspend fun getFlowPaths(
            tunnel: Tunnel, flowId: Long,
        ): Result<com.wireguard.android.model.FlowPathsResponseDto> {
            return Result.failure(UnsupportedOperationException())
        }
    }

    private class FakeFlowPathRepository(
        private val state: FlowPathState? = null,
        private val error: Boolean = false,
    ) : FlowPathRepository {
        override suspend fun getFlowPathState(
            tunnel: Tunnel, flowId: Long,
        ): Result<FlowPathState> {
            if (error) return Result.failure(Exception("path fetch failed"))
            return state?.let { Result.success(it) }
                ?: Result.success(
                    FlowPathState(
                        flowId = flowId,
                        queryState = PathQueryState.EMPTY,
                    )
                )
        }
        override suspend fun setOverride(tunnel: Tunnel, flowId: Long, fingerprint: String): Result<Unit> = Result.success(Unit)
        override suspend fun clearOverride(tunnel: Tunnel, flowId: Long): Result<Unit> = Result.success(Unit)
    }
}

private fun Tunnel(name: String): Tunnel = object : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(state: Tunnel.State?) {}
}
