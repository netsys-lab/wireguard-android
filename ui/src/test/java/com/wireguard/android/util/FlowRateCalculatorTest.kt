package com.wireguard.android.util

import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEndpointDto
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowRateCalculatorTest {

    @Test
    fun `first sample returns zero rates`() {
        val calc = FlowRateCalculator()
        val flow = makeFlow(id = 1, txBytes = 100, rxBytes = 200, txPkts = 10, rxPkts = 20)
        val rates = calc.push(flow)
        assertEquals(0.0, rates.txBitsPerSec, 0.001)
        assertEquals(0.0, rates.rxBitsPerSec, 0.001)
        assertEquals(0.0, rates.txPktsPerSec, 0.001)
        assertEquals(0.0, rates.rxPktsPerSec, 0.001)
    }

    @Test
    fun `counter reset re-baselines and returns zero`() {
        val calc = FlowRateCalculator()
        calc.push(makeFlow(id = 1, txBytes = 1000))
        val rates = calc.push(makeFlow(id = 1, txBytes = 500))
        assertEquals(0.0, rates.txBitsPerSec, 0.001)
    }

    @Test
    fun `same counters return zero`() {
        val calc = FlowRateCalculator()
        calc.push(makeFlow(id = 1, txBytes = 1000))
        val rates = calc.push(makeFlow(id = 1, txBytes = 1000))
        assertEquals(0.0, rates.txBitsPerSec, 0.001)
    }

    @Test
    fun `reset clears baseline`() {
        val calc = FlowRateCalculator()
        calc.push(makeFlow(id = 1, txBytes = 100))
        calc.reset()
        val rates = calc.push(makeFlow(id = 1, txBytes = 200))
        assertEquals(0.0, rates.txBitsPerSec, 0.001)
    }

    @Test
    fun `combinedBitsPerSec sums tx and rx`() {
        val rates = FlowRates(txBitsPerSec = 1000.0, rxBitsPerSec = 2000.0, txPktsPerSec = 0.0, rxPktsPerSec = 0.0)
        assertEquals(3000.0, rates.combinedBitsPerSec, 0.001)
    }

    @Test
    fun `combinedPktsPerSec sums tx and rx`() {
        val rates = FlowRates(txBitsPerSec = 0.0, rxBitsPerSec = 0.0, txPktsPerSec = 5.0, rxPktsPerSec = 10.0)
        assertEquals(15.0, rates.combinedPktsPerSec, 0.001)
    }

    private fun makeFlow(
        id: Long = 1,
        txBytes: Long = 0,
        rxBytes: Long = 0,
        txPkts: Long = 0,
        rxPkts: Long = 0,
    ): FlowDto = FlowDto(
        id = id,
        ipVersion = 4,
        protocol = 6,
        endpointA = FlowEndpointDto("10.0.0.2", 49152),
        endpointB = FlowEndpointDto("10.0.0.3", 443),
        status = "active",
        txPackets = txPkts,
        txBytes = txBytes,
        rxPackets = rxPkts,
        rxBytes = rxBytes,
        egressKind = "ip",
    )
}
