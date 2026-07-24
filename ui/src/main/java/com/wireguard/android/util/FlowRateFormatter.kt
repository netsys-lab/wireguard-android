package com.wireguard.android.util

import java.util.Locale

object FlowRateFormatter {
    fun formatBitsPerSec(bps: Double): String = when {
        bps <= 0 -> "0 bit/s"
        bps < 1_000 -> "%.0f bit/s".format(Locale.US, bps)
        bps < 1_000_000 -> "%.1f Kbit/s".format(Locale.US, bps / 1_000)
        bps < 1_000_000_000 -> "%.1f Mbit/s".format(Locale.US, bps / 1_000_000)
        else -> "%.2f Gbit/s".format(Locale.US, bps / 1_000_000_000)
    }

    fun formatPktsPerSec(pps: Double): String = when {
        pps <= 0 -> "0 pkt/s"
        pps < 1_000 -> "%.0f pkt/s".format(Locale.US, pps)
        else -> "%.1f Kpkt/s".format(Locale.US, pps / 1_000)
    }

    fun formatCombinedRate(rate: FlowRates): String {
        val bits = formatBitsPerSec(rate.combinedBitsPerSec)
        val pkts = formatPktsPerSec(rate.combinedPktsPerSec)
        return "$bits · $pkts"
    }

    fun formatDirectionRate(bitsPerSec: Double, pktsPerSec: Double): String {
        val bits = formatBitsPerSec(bitsPerSec)
        val pkts = formatPktsPerSec(pktsPerSec)
        return "$bits\n$pkts"
    }
}
