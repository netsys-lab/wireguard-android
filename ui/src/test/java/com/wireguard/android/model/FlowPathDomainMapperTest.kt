package com.wireguard.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPathDomainMapperTest {

    @Test
    fun `maps READY state correctly`() {
        val dto = FlowPathsResponseDto(
            flowId = 42,
            state = FlowPathsState.READY,
            paths = listOf(pathDto("fp1", true)),
            effectiveFingerprint = "fp1",
            policyName = "Lowest Latency",
            policyMode = "configured",
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(42L, domain.flowId)
        assertEquals(PathQueryState.READY, domain.queryState)
        assertEquals("fp1", domain.effectiveFingerprint)
        assertEquals(1, domain.paths.size)
        assertEquals(PolicyState.CONFIGURED, domain.policyState)
        assertEquals("Lowest Latency", domain.policyName)
    }

    @Test
    fun `maps PENDING state correctly`() {
        val dto = FlowPathsResponseDto(
            flowId = 1,
            state = FlowPathsState.PENDING,
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(PathQueryState.PENDING, domain.queryState)
        assertTrue(domain.paths.isEmpty())
    }

    @Test
    fun `maps EMPTY state correctly`() {
        val dto = FlowPathsResponseDto(
            flowId = 1,
            state = FlowPathsState.EMPTY,
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(PathQueryState.EMPTY, domain.queryState)
    }

    @Test
    fun `maps ERROR state correctly`() {
        val dto = FlowPathsResponseDto(
            flowId = 1,
            state = FlowPathsState.ERROR,
            error = "something went wrong",
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(PathQueryState.ERROR, domain.queryState)
    }

    @Test
    fun `maps UNKNOWN state correctly`() {
        val dto = FlowPathsResponseDto(
            flowId = 1,
            state = FlowPathsState.UNKNOWN,
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(PathQueryState.UNKNOWN, domain.queryState)
    }

    @Test
    fun `maps policy states correctly`() {
        val testCases = mapOf(
            "none" to PolicyState.NONE,
            "default" to PolicyState.DEFAULT,
            "configured" to PolicyState.CONFIGURED,
            "" to PolicyState.UNKNOWN,
            "unknown" to PolicyState.UNKNOWN,
        )
        for ((mode, expected) in testCases) {
            val dto = FlowPathsResponseDto(
                flowId = 1, state = FlowPathsState.READY,
                policyMode = mode,
            )
            val domain = FlowPathDomainMapper.toDomain(dto)
            assertEquals("policyMode=$mode", expected, domain.policyState)
        }
    }

    @Test
    fun `policy fallback overrides policy state`() {
        val dto = FlowPathsResponseDto(
            flowId = 1, state = FlowPathsState.READY,
            policyMode = "configured",
            policyFallbackApplied = true,
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(PolicyState.FALLBACK, domain.policyState)
    }

    @Test
    fun `maps override states correctly`() {
        val testCases = mapOf(
            "active" to OverrideState.ACTIVE,
            "stale" to OverrideState.STALE,
            null to OverrideState.INACTIVE,
            "" to OverrideState.INACTIVE,
        )
        for ((state, expected) in testCases) {
            val dto = FlowPathsResponseDto(
                flowId = 1, state = FlowPathsState.READY,
                overrideState = state,
                overrideFingerprint = if (state != null) "fp" else null,
            )
            val domain = FlowPathDomainMapper.toDomain(dto)
            assertEquals("overrideState=$state", expected, domain.overrideState)
        }
    }

    @Test
    fun `maps FlowPathDto to FlowPathDomain`() {
        val dto = pathDto(
            fingerprint = "fp1",
            current = true,
            display = "1-ff00:0:110 → 1-ff00:0:111",
            latencyMs = listOf(5.0, 3.0),
            bandwidth = listOf(10_000_000_000L, 20_000_000_000L),
            interfaces = listOf("1-ff00:0:110", "1-ff00:0:111"),
            totalLatencyMicros = 8000,
            bottleneckKbps = 10_000_000,
            interAsLinks = 1,
        )
        val domain = FlowPathDomainMapper.toDomain(dto)

        assertEquals("fp1", domain.fingerprint)
        assertEquals(2, domain.interfaces.size)
        assertEquals(listOf(5000L, 3000L), domain.latencyMicros)
        assertEquals(listOf(10_000_000L, 20_000_000L), domain.bandwidthKbps)
        assertEquals(8000L, domain.totalLatencyMicros)
        assertEquals(10_000_000L, domain.bottleneckBandwidthKbps)
        assertEquals(1, domain.interAsLinks)
        assertTrue(domain.latencyComplete)
        assertTrue(domain.bandwidthComplete)
    }

    @Test
    fun `maps missing metadata correctly`() {
        val dto = FlowPathDto(
            fingerprint = "fp1",
            display = "ISA-1 → ISA-2",
            interfaces = listOf("ISA-1", "ISA-2"),
            latencyMs = null,
            bandwidth = listOf(-1L, 0L),
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertTrue(domain.latencyMicros == null || domain.latencyMicros.isEmpty())
        assertTrue(domain.bandwidthKbps == null || domain.bandwidthKbps.isEmpty())
    }

    @Test
    fun `intra-AS path has zero inter-AS links`() {
        val dto = FlowPathDto(
            fingerprint = "intra",
            display = "1-ff00:0:110",
            interfaces = listOf("1-ff00:0:110"),
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals(0, domain.interAsLinks)
        assertTrue(domain.isIntraAs)
    }

    @Test
    fun `overrideFingerprint propagates correctly`() {
        val dto = FlowPathsResponseDto(
            flowId = 1,
            state = FlowPathsState.READY,
            overrideState = "active",
            overrideFingerprint = "fp-ovr",
            effectiveFingerprint = "fp-ovr",
        )
        val domain = FlowPathDomainMapper.toDomain(dto)
        assertEquals("fp-ovr", domain.overrideFingerprint)
        assertEquals("fp-ovr", domain.effectiveFingerprint)
        assertEquals(OverrideState.ACTIVE, domain.overrideState)
    }

    @Test
    fun `multiple paths are all mapped`() {
        val dtos = (1..5).map { pathDto("fp$it", it == 1) }
        val response = FlowPathsResponseDto(
            flowId = 1,
            state = FlowPathsState.READY,
            paths = dtos,
            effectiveFingerprint = "fp1",
        )
        val domain = FlowPathDomainMapper.toDomain(response)
        assertEquals(5, domain.paths.size)
        assertEquals("fp1", domain.effectiveFingerprint)
    }

    private fun pathDto(
        fingerprint: String,
        current: Boolean = false,
        display: String = "ISA-1 → ISA-2",
        latencyMs: List<Double>? = listOf(5.0, 3.0),
        bandwidth: List<Long>? = listOf(10_000_000_000L, 20_000_000_000L),
        interfaces: List<String> = listOf("ISA-1", "ISA-2"),
        totalLatencyMicros: Long? = null,
        bottleneckKbps: Long? = null,
        interAsLinks: Int = 1,
    ) = FlowPathDto(
        fingerprint = fingerprint,
        display = display,
        current = current,
        interfaces = interfaces,
        latencyMs = latencyMs,
        bandwidth = bandwidth,
        totalLatencyMicros = totalLatencyMicros,
        bottleneckKbps = bottleneckKbps,
        interAsLinks = interAsLinks,
    )
}
