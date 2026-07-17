/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses raw logcat lines into a compact diagnostic representation for the Debug Packet
 * Log Viewer.
 *
 * The viewer must make the diagnostic message the visual priority. To do that we derive:
 *  - displayTime:        local time HH:mm:ss.SSS (no full date on every row)
 *  - displayComponent:   a compact badge such as SCION-PATH, WG, APP, JNI
 *  - displayMessage:     the message with duplicated timestamp/date prefixes removed and
 *                        key fields (packetId, refreshId, event, phase, ...) surfaced
 *  - rawLine:            the untouched original line (never mutated)
 *
 * If a line cannot be parsed, [parse] returns a record that keeps [rawLine] as the
 * message so the viewer still shows it safely instead of dropping it.
 */
object DebugPacketLogParser {

    data class ParsedLog(
        val displayTime: String,
        val displayComponent: String,
        val displayLevel: String,
        val displayMessage: String,
        val rawLine: String,
        val level: Char,
        val tag: String,
        val packetId: String?,
        val refreshId: String?,
        val parsed: Boolean
    )

    private const val TAG_PREFIX = "WireGuard"

    private val THREADTIME_LINE: Pattern =
        Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3})(?:\\s+[0-9A-Za-z]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$")

    // Go-style log prefix: 2026/07/16 12:24:32  or  2026/07/16 12:24:32.418
    private val GO_DATE_PREFIX: Pattern =
        Pattern.compile("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?\\s*")

    // Component bracket such as [SCION-PATH-TRA...]  or [SCION-PATH]
    private val COMPONENT_BRACKET: Pattern =
        Pattern.compile("^\\[([A-Za-z0-9_\\-]+)\\]\\s*")

    private val PACKET_ID: Pattern = Pattern.compile("packetId[=:](\\S+)")
    private val REFRESH_ID: Pattern = Pattern.compile("refreshId[=:](\\S+)")

    // Known component keywords used to derive a compact badge.
    private val COMPONENT_KEYWORDS = listOf(
        "scion-path" to "PATH",
        "scion-path-engine" to "PATH-ENGINE",
        "path-engine" to "PATH-ENGINE",
        "pending" to "PENDING",
        "translate-egress" to "EGRESS",
        "wireguard-egress" to "WG",
        "egress-lifecycle" to "EGRESS",
        "wireguard" to "WG",
        "jni" to "JNI",
        "app" to "APP"
    )

    private val fullTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val displayTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun parseLine(rawLine: String, year: String): ParsedLog {
        val m = THREADTIME_LINE.matcher(rawLine)
        return if (m.matches()) {
            val timeStr = m.group(1)!!
            val level = m.group(4)!![0]
            val tag = m.group(5)!!
            val message = m.group(6)!!

            val displayTime = try {
                val full = fullTimeFormat.parse("$year-${timeStr.substring(0, 2)}-${timeStr.substring(3, 5)} ${timeStr.substring(6)}")
                if (full != null) displayTimeFormat.format(full) else timeStr.substring(6)
            } catch (e: ParseException) {
                timeStr.substring(6)
            }

            val component = deriveComponent(tag, message)
            val cleaned = cleanMessage(message)

            ParsedLog(
                displayTime = displayTime,
                displayComponent = component,
                displayLevel = levelToString(level),
                displayMessage = cleaned,
                rawLine = rawLine,
                level = level,
                tag = tag,
                packetId = extract(PACKET_ID, message) ?: extract(PACKET_ID, tag),
                refreshId = extract(REFRESH_ID, message) ?: extract(REFRESH_ID, tag),
                parsed = true
            )
        } else {
            // Not a threadtime line: keep raw but still try to surface time/component.
            val displayTime = extractLeadingGoTime(rawLine) ?: ""
            val component = deriveComponent("", rawLine)
            val cleaned = cleanMessage(rawLine)
            ParsedLog(
                displayTime = displayTime,
                displayComponent = component,
                displayLevel = "I",
                displayMessage = cleaned,
                rawLine = rawLine,
                level = 'I',
                tag = "",
                packetId = extract(PACKET_ID, rawLine),
                refreshId = extract(REFRESH_ID, rawLine),
                parsed = false
            )
        }
    }

    /**
     * Removes duplicated timestamp/date prefixes and component brackets from the displayed
     * message so the same time is not shown twice.
     */
    fun cleanMessage(message: String): String {
        var s = message
        // Strip a leading Go-style date+time prefix (the duplicate timestamp).
        s = GO_DATE_PREFIX.matcher(s).replaceFirst("")
        // Strip a leading [COMPONENT] bracket if present.
        s = COMPONENT_BRACKET.matcher(s).replaceFirst("")
        return s.trim()
    }

    private fun extractLeadingGoTime(line: String): String? {
        val m = Pattern.compile("^\\d{4}/\\d{2}/\\d{2} (\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)").matcher(line)
        return if (m.find()) m.group(1) else null
    }

    private fun deriveComponent(tag: String, message: String): String {
        val haystack = "$tag $message".lowercase(Locale.US)
        for ((keyword, badge) in COMPONENT_KEYWORDS) {
            if (haystack.contains(keyword)) return badge
        }
        // Fall back to a short tag fragment.
        val base = tag.substringAfter("${TAG_PREFIX}/").takeIf { it.isNotEmpty() } ?: tag
        return if (base.length > 12) base.substring(0, 12) else base
    }

    private fun extract(pattern: Pattern, text: String): String? {
        val m = pattern.matcher(text)
        return if (m.find()) m.group(1)?.trim()?.trimEnd('.', ',') else null
    }

    fun levelToString(level: Char): String = when (level) {
        'V' -> "TRACE"
        'D' -> "DEBUG"
        'I' -> "INFO"
        'W' -> "WARN"
        'E' -> "ERROR"
        'F' -> "FATAL"
        else -> level.toString()
    }
}
