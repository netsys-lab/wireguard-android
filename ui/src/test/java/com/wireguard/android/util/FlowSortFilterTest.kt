package com.wireguard.android.util

import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEndpointDto
import com.wireguard.android.model.FlowEgressKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowSortFilterTest {

    @Test
    fun `filterSCIONOnly returns only scion flows`() {
        val scion = makeFlow(id = 1, egressKind = "scion")
        val ip = makeFlow(id = 2, egressKind = "ip")
        val result = FlowSortFilter.filterSCIONOnly(listOf(scion, ip))
        assertEquals(1, result.size)
        assertEquals(FlowEgressKind.SCION, result[0].egressKindEnum)
    }

    @Test
    fun `sortFlows by combined bits descending then pkts descending then lastSeen then id`() {
        val f1 = makeFlow(id = 1, egressKind = "scion")
        val f2 = makeFlow(id = 2, egressKind = "scion")
        val f3 = makeFlow(id = 3, egressKind = "scion")

        val rates = mapOf(
            1L to FlowRates(1000.0, 0.0, 5.0, 0.0),
            2L to FlowRates(2000.0, 0.0, 3.0, 0.0),
            3L to FlowRates(1000.0, 0.0, 10.0, 0.0),
        )

        val sorted = FlowSortFilter.sortFlows(listOf(f1, f2, f3), rates)
        // f2: 2000 bps first, then f3: 1000 bps + 10 pps, then f1: 1000 bps + 5 pps
        assertEquals(2L, sorted[0].id)
        assertEquals(3L, sorted[1].id)
        assertEquals(1L, sorted[2].id)
    }

    @Test
    fun `sortFlows uses id ascending as tiebreaker`() {
        val f1 = makeFlow(id = 1, egressKind = "scion")
        val f2 = makeFlow(id = 2, egressKind = "scion")

        val rates = mapOf(
            1L to FlowRates(0.0, 0.0, 0.0, 0.0),
            2L to FlowRates(0.0, 0.0, 0.0, 0.0),
        )

        val sorted = FlowSortFilter.sortFlows(listOf(f2, f1), rates)
        assertEquals(1L, sorted[0].id)
        assertEquals(2L, sorted[1].id)
    }

    @Test
    fun `computeRowData handles empty list`() = runBlocking {
        val calc = FlowRateCalculator()
        val result = FlowSortFilter.computeRowData(emptyList(), calc, emptyMap())
        assertTrue(result.isEmpty())
    }

    private fun makeFlow(id: Long, egressKind: String): FlowDto = FlowDto(
        id = id,
        ipVersion = 4,
        protocol = 6,
        endpointA = FlowEndpointDto("10.0.0.2", 49152),
        endpointB = FlowEndpointDto("10.0.0.3", 443),
        status = "active",
        txPackets = 0,
        txBytes = 0,
        rxPackets = 0,
        rxBytes = 0,
        egressKind = egressKind,
    )
}
