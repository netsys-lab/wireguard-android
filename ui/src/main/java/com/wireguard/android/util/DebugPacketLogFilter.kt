/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

private typealias LogEntry = DebugPacketLogModels.LogEntry

/**
 * Pure, testable filtering for the Debug Packet Log Viewer.
 *
 * Supports:
 *  - free-text search (matched against the raw line so packetId=, refreshId=, socket-write,
 *    connector.Paths, context-deadline and SCION-PATH style queries all work)
 *  - level filtering (TRACE/DEBUG/INFO/WARN/ERROR/FATAL)
 *  - component filtering (PATH, WG, APP, JNI, EGRESS, PENDING, PATH-ENGINE, ...)
 *
 * Unknown components are always displayed (we never drop a line just because we do not
 * recognise its component).
 */
object DebugPacketLogFilter {

    data class Criteria(
        var query: String = "",
        var levels: Set<Char> = emptySet(),
        var components: Set<String> = emptySet()
    )

    fun matches(entry: LogEntry, criteria: Criteria): Boolean {
        if (criteria.levels.isNotEmpty() && entry.level !in criteria.levels) return false
        if (criteria.components.isNotEmpty() &&
            criteria.components.none { it.equals(entry.displayComponent, ignoreCase = true) }
        ) return false
        val q = criteria.query.trim()
        if (q.isNotEmpty()) {
            if (!entry.rawLine.contains(q, ignoreCase = true)) return false
        }
        return true
    }

    fun apply(entries: List<LogEntry>, criteria: Criteria): List<LogEntry> {
        if (criteria.query.isBlank() && criteria.levels.isEmpty() && criteria.components.isEmpty()) {
            return entries
        }
        return entries.filter { matches(it, criteria) }
    }

    /** Collects all distinct components currently present in the entries. */
    fun distinctComponents(entries: List<LogEntry>): List<String> {
        return entries.mapNotNull { it.displayComponent.takeIf { c -> c.isNotEmpty() } }
            .distinct()
            .sorted()
    }

    /** Collects nearby lines sharing the same packetId, used for packet-trace copy. */
    fun collectPacketTrace(
        entries: List<LogEntry>,
        packetId: String
    ): List<String> {
        return entries.filter { it.packetId == packetId }.map { it.rawLine }
    }
}
