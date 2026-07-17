/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

/**
 * Pure (Android-free) data model for the Debug Packet Log Viewer.
 *
 * Kept free of Android imports so it can be unit-tested on the JVM.
 */
object DebugPacketLogModels {

    /**
     * Bounded in-memory line count. When exceeded, the oldest entries are dropped and the
     * trimmed count is incremented so the viewer can indicate older lines were removed.
     */
    const val MAX_ENTRIES = 5000

    data class LogEntry(
        val timestamp: String,
        val level: Char,
        val tag: String,
        val message: String,
        val displayTime: String,
        val displayComponent: String,
        val displayLevel: String,
        val displayMessage: String,
        val rawLine: String,
        val packetId: String?,
        val refreshId: String?,
        val parsed: Boolean
    )
}
