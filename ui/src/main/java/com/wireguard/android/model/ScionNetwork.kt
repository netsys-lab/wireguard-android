/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

/**
 * Represents a SCION Autonomous System network configuration.
 */
data class ScionNetwork(
    val name: String,
    val isdAs: String,
    val bootstrapUrl: String
) {
    companion object {
        /**
         * Returns a list of sample/hardcoded SCION networks for development.
         */
        fun getSampleNetworks(): List<ScionNetwork> = listOf(
            ScionNetwork("AS 110", "1-ff00:0:110", "http://10.0.2.2:8041"),
            ScionNetwork("AS 111", "1-ff00:0:111", "http://10.0.2.2:8042"),
            ScionNetwork("AS 112", "1-ff00:0:112", "http://10.0.2.2:8043"),
            ScionNetwork("SCIONLab EU Core", "1-ff00:0:110", "https://bootstrap.scionlab.org")
        )
    }
}
