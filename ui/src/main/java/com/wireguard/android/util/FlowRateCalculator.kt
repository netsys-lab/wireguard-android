package com.wireguard.android.util

import com.wireguard.android.model.FlowDto

data class FlowRates(
    val txBitsPerSec: Double,
    val rxBitsPerSec: Double,
    val txPktsPerSec: Double,
    val rxPktsPerSec: Double,
) {
    val combinedBitsPerSec: Double get() = txBitsPerSec + rxBitsPerSec
    val combinedPktsPerSec: Double get() = txPktsPerSec + rxPktsPerSec
}

data class FlowRateSample(
    val txBytes: Long,
    val rxBytes: Long,
    val txPackets: Long,
    val rxPackets: Long,
    val timeEpochMs: Long,
)

class FlowRateCalculator {
    private var baseline: FlowRateSample? = null

    fun push(current: FlowDto): FlowRates {
        val now = System.currentTimeMillis()
        val sample = FlowRateSample(
            txBytes = current.txBytes,
            rxBytes = current.rxBytes,
            txPackets = current.txPackets,
            rxPackets = current.rxPackets,
            timeEpochMs = now,
        )

        val prev = baseline
        baseline = sample

        if (prev == null) return FlowRates(0.0, 0.0, 0.0, 0.0)

        val dtMs = now - prev.timeEpochMs
        if (dtMs <= 0) return FlowRates(0.0, 0.0, 0.0, 0.0)

        val dtSec = dtMs / 1000.0

        val txBytesDelta = sample.txBytes - prev.txBytes
        val rxBytesDelta = sample.rxBytes - prev.rxBytes

        if (txBytesDelta < 0 || rxBytesDelta < 0) {
            baseline = sample
            return FlowRates(0.0, 0.0, 0.0, 0.0)
        }

        val txPktsDelta = sample.txPackets - prev.txPackets
        val rxPktsDelta = sample.rxPackets - prev.rxPackets

        if (txPktsDelta < 0 || rxPktsDelta < 0) {
            baseline = sample
            return FlowRates(0.0, 0.0, 0.0, 0.0)
        }

        return FlowRates(
            txBitsPerSec = (txBytesDelta * 8) / dtSec,
            rxBitsPerSec = (rxBytesDelta * 8) / dtSec,
            txPktsPerSec = txPktsDelta / dtSec,
            rxPktsPerSec = rxPktsDelta / dtSec,
        )
    }

    fun reset() {
        baseline = null
    }
}
