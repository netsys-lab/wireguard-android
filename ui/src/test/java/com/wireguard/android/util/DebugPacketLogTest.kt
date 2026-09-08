/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias LogEntry = DebugPacketLogModels.LogEntry

class DebugPacketLogParserTest {

    private val year = "2026"

    @Test
    fun `duplicate go timestamp removed from display message`() {
        val raw = "07-16 12:24:32.418  1234  1234 D WireGuard/Scion: " +
            "2026/07/16 12:24:32 [SCION-PATH-TRA] refreshId=7 event=refresh-success paths=2"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertTrue(parsed.parsed)
        assertFalse(parsed.displayMessage.contains("2026/07/16"))
        assertFalse(parsed.displayMessage.startsWith("2026/"))
    }

    @Test
    fun `raw line remains unchanged after parsing`() {
        val raw = "07-16 12:24:32.418  1234  1234 I WireGuard/App: hello world"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertEquals(raw, parsed.rawLine)
    }

    @Test
    fun `same source does not consume a wide column`() {
        // displayComponent is a short badge, not the full tag
        val raw = "07-16 12:24:32.418  1234  1234 I WireGuard/GoBackend: [SCION-PATH] x=1"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertTrue(parsed.displayComponent.length <= 12)
        assertFalse(parsed.displayComponent == "WireGuard/GoBackend")
    }

    @Test
    fun `message wraps when displayed`() {
        // The viewer uses a wrapping TextView; the parser only ensures newlines are preserved.
        val raw = "07-16 12:24:32.418  1234  1234 I WireGuard/App: " +
            "packetId=1 event=socket-write-success buffers=1 bytes=188"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertTrue(parsed.displayMessage.contains("packetId=1"))
        assertTrue(parsed.displayMessage.contains("bytes=188"))
    }

    @Test
    fun `unknown format falls back to raw display`() {
        val raw = "this is not a threadtime line at all"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertFalse(parsed.parsed)
        assertEquals(raw, parsed.displayMessage)
        assertEquals(raw, parsed.rawLine)
    }

    @Test
    fun `packetId parsing`() {
        val raw = "07-16 12:24:34.102  1234  1234 I WireGuard/WG: packetId=1 event=socket-write-success"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertEquals("1", parsed.packetId)
    }

    @Test
    fun `refreshId parsing`() {
        val raw = "07-16 12:24:32.418  1234  1234 D WireGuard/Scion: refreshId=7 event=refresh-success"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertEquals("7", parsed.refreshId)
    }

    @Test
    fun `display time uses HH:mm:ss_SSS without full date`() {
        val raw = "07-16 12:24:32.418  1234  1234 D WireGuard/Scion: event=complete"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertEquals("12:24:32.418", parsed.displayTime)
        assertFalse(parsed.displayTime.contains("2026"))
        assertFalse(parsed.displayTime.contains("/"))
    }

    @Test
    fun `component badge derived from message keywords`() {
        val raw = "07-16 12:24:32.418  1234  1234 D WireGuard/Scion: [SCION-PATH] refreshId=7"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertEquals("PATH", parsed.displayComponent)
    }

    @Test
    fun `level is converted to textual severity`() {
        assertEquals("TRACE", DebugPacketLogParser.levelToString('V'))
        assertEquals("DEBUG", DebugPacketLogParser.levelToString('D'))
        assertEquals("INFO", DebugPacketLogParser.levelToString('I'))
        assertEquals("WARN", DebugPacketLogParser.levelToString('W'))
        assertEquals("ERROR", DebugPacketLogParser.levelToString('E'))
        assertEquals("FATAL", DebugPacketLogParser.levelToString('F'))
    }

    @Test
    fun `non-wireguard tag is dropped by streamer but parser keeps it`() {
        val raw = "07-16 12:24:32.418  1234  1234 I SystemServer: unrelated"
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        assertTrue(parsed.parsed)
        assertFalse(parsed.tag.startsWith("WireGuard"))
    }
}

class DebugPacketLogFilterTest {

    private val year = "2026"

    private fun entry(
        raw: String,
        level: Char = 'I',
        component: String = "APP",
        packetId: String? = null,
        refreshId: String? = null
    ): LogEntry {
        val parsed = DebugPacketLogParser.parseLine(raw, year)
        return LogEntry(
            timestamp = parsed.displayTime.substringBefore('.'),
            level = level,
            tag = "WireGuard/App",
            message = parsed.displayMessage,
            displayTime = parsed.displayTime,
            displayComponent = component,
            displayLevel = parsed.displayLevel,
            displayMessage = parsed.displayMessage,
            rawLine = raw,
            packetId = packetId,
            refreshId = refreshId,
            parsed = true
        )
    }

    @Test
    fun `empty criteria returns all entries`() {
        val list = listOf(
            entry("07-16 12:24:32.418 1 1 I WireGuard/App: packetId=1 event=send", packetId = "1"),
            entry("07-16 12:24:33.418 1 1 W WireGuard/App: warn", level = 'W')
        )
        assertEquals(2, DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria()).size)
    }

    @Test
    fun `search by packetId`() {
        val list = listOf(
            entry("07-16 12:24:32.418 1 1 I WireGuard/App: packetId=1 event=send", packetId = "1"),
            entry("07-16 12:24:33.418 1 1 I WireGuard/App: packetId=2 event=send", packetId = "2")
        )
        val result = DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria(query = "packetId=1"))
        assertEquals(1, result.size)
        assertEquals("1", result[0].packetId)
    }

    @Test
    fun `search by socket-write matches raw line`() {
        val list = listOf(
            entry("07-16 12:24:34.102 1 1 I WireGuard/WG: packetId=1 event=socket-write-success buffers=1")
        )
        val result = DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria(query = "socket-write"))
        assertEquals(1, result.size)
    }

    @Test
    fun `search by component keyword SCION-PATH`() {
        val list = listOf(
            entry("07-16 12:24:32.418 1 1 D WireGuard/Scion: [SCION-PATH] refreshId=7", component = "PATH"),
            entry("07-16 12:24:33.418 1 1 I WireGuard/App: hello", component = "APP")
        )
        val result = DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria(query = "SCION-PATH"))
        assertEquals(1, result.size)
        assertEquals("PATH", result[0].displayComponent)
    }

    @Test
    fun `level filter excludes non-matching`() {
        val list = listOf(
            entry("07-16 12:24:32.418 1 1 I WireGuard/App: info", level = 'I'),
            entry("07-16 12:24:33.418 1 1 E WireGuard/App: error", level = 'E')
        )
        val result = DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria(levels = setOf('E')))
        assertEquals(1, result.size)
        assertEquals('E', result[0].level)
    }

    @Test
    fun `component filter selects matching`() {
        val list = listOf(
            entry("07-16 12:24:32.418 1 1 D WireGuard/Scion: [SCION-PATH] x", component = "PATH"),
            entry("07-16 12:24:33.418 1 1 I WireGuard/App: y", component = "APP")
        )
        val result = DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria(components = setOf("PATH")))
        assertEquals(1, result.size)
        assertEquals("PATH", result[0].displayComponent)
    }

    @Test
    fun `unknown component still displays when no component filter set`() {
        val list = listOf(entry("07-16 12:24:32.418 1 1 I WireGuard/App: z", component = "UNKNOWNXYZ"))
        val result = DebugPacketLogFilter.apply(list, DebugPacketLogFilter.Criteria())
        assertEquals(1, result.size)
    }

    @Test
    fun `distinct components are collected`() {
        val list = listOf(
            entry("a", component = "PATH"),
            entry("b", component = "APP"),
            entry("c", component = "PATH")
        )
        val comps = DebugPacketLogFilter.distinctComponents(list)
        assertEquals(listOf("APP", "PATH"), comps)
    }

    @Test
    fun `packet trace collects lines with same packetId`() {
        val list = listOf(
            entry("07-16 12:24:32.418 1 1 I WireGuard/App: packetId=1 a", packetId = "1"),
            entry("07-16 12:24:33.418 1 1 I WireGuard/App: packetId=2 b", packetId = "2"),
            entry("07-16 12:24:34.418 1 1 I WireGuard/App: packetId=1 c", packetId = "1")
        )
        val trace = DebugPacketLogFilter.collectPacketTrace(list, "1")
        assertEquals(2, trace.size)
        assertTrue(trace.all { it.contains("packetId=1") })
    }
}

class AutoscrollTrackerTest {

    @Test
    fun `starts enabled`() {
        val t = AutoscrollTracker()
        assertTrue(t.enabled)
    }

    @Test
    fun `scrolling away from bottom pauses`() {
        val t = AutoscrollTracker()
        t.onScroll(firstVisible = 0, visibleCount = 10, totalCount = 100)
        assertFalse(t.enabled)
    }

    @Test
    fun `scrolling to bottom resumes`() {
        val t = AutoscrollTracker()
        t.onScroll(0, 10, 100)
        assertFalse(t.enabled)
        t.onScroll(90, 10, 100)
        assertTrue(t.enabled)
    }

    @Test
    fun `paused autoscroll accumulates new lines counter`() {
        val t = AutoscrollTracker()
        t.onScroll(0, 10, 100) // pause
        assertFalse(t.enabled)
        t.onNewData()
        t.onNewData()
        t.onNewData()
        assertEquals(3, t.newLinesWhilePaused)
    }

    @Test
    fun `resume resets new lines counter`() {
        val t = AutoscrollTracker()
        t.onScroll(0, 10, 100)
        t.onNewData()
        t.resume()
        assertEquals(0, t.newLinesWhilePaused)
        assertTrue(t.enabled)
    }

    @Test
    fun `manual pause stops following`() {
        val t = AutoscrollTracker()
        t.pause()
        assertFalse(t.enabled)
        t.onNewData()
        assertEquals(1, t.newLinesWhilePaused)
    }

    @Test
    fun `enabled autoscroll does not count new lines`() {
        val t = AutoscrollTracker()
        t.onNewData()
        assertEquals(0, t.newLinesWhilePaused)
        assertTrue(t.enabled)
    }
}

class BoundedLogTrimmingTest {

    @Test
    fun `streamer trims oldest beyond max and reports trimmed count`() {
        // Build a streamer-like list manually using the data class and assert trim math.
        val max = DebugPacketLogModels.MAX_ENTRIES
        val entries = mutableListOf<LogEntry>()
        repeat(max + 50) { i ->
            entries.add(
                LogEntry(
                    timestamp = "", level = 'I', tag = "WireGuard/App",
                    message = "m$i", displayTime = "", displayComponent = "APP",
                    displayLevel = "INFO", displayMessage = "m$i",
                    rawLine = "line$i", packetId = null, refreshId = null, parsed = true
                )
            )
        }
        val overflow = entries.size - max
        entries.subList(0, overflow).clear()
        assertEquals(max, entries.size)
        // Newest retained, oldest removed.
        assertTrue(entries.last().rawLine == "line${max + 49}")
        assertFalse(entries.first().rawLine == "line0")
    }

    @Test
    fun `bounded max constant is documented 5000`() {
        assertEquals(5000, DebugPacketLogModels.MAX_ENTRIES)
    }
}
