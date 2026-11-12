package com.wireguard.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowRateFormatterTest {

    @Test
    fun `zero bits per second`() {
        assertEquals("0 bit/s", FlowRateFormatter.formatBitsPerSec(0.0))
    }

    @Test
    fun `negative bits per second`() {
        assertEquals("0 bit/s", FlowRateFormatter.formatBitsPerSec(-1.0))
    }

    @Test
    fun `bits per second under 1000`() {
        assertEquals("500 bit/s", FlowRateFormatter.formatBitsPerSec(500.0))
    }

    @Test
    fun `kilobits per second`() {
        assertEquals("5.0 Kbit/s", FlowRateFormatter.formatBitsPerSec(5000.0))
    }

    @Test
    fun `megabits per second`() {
        assertEquals("10.5 Mbit/s", FlowRateFormatter.formatBitsPerSec(10_500_000.0))
    }

    @Test
    fun `gigabits per second`() {
        assertEquals("1.50 Gbit/s", FlowRateFormatter.formatBitsPerSec(1_500_000_000.0))
    }

    @Test
    fun `zero packets per second`() {
        assertEquals("0 pkt/s", FlowRateFormatter.formatPktsPerSec(0.0))
    }

    @Test
    fun `packets per second under 1000`() {
        assertEquals("500 pkt/s", FlowRateFormatter.formatPktsPerSec(500.0))
    }

    @Test
    fun `kilopackets per second`() {
        assertEquals("1.5 Kpkt/s", FlowRateFormatter.formatPktsPerSec(1500.0))
    }

    @Test
    fun `combined rate format`() {
        val rates = FlowRates(txBitsPerSec = 1_000_000.0, rxBitsPerSec = 2_000_000.0, txPktsPerSec = 100.0, rxPktsPerSec = 200.0)
        val result = FlowRateFormatter.formatCombinedRate(rates)
        assertEquals("3.0 Mbit/s · 300 pkt/s", result)
    }
}
