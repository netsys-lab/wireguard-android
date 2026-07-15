/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class DebugPacketLogStreamer {

    data class LogEntry(
        val timestamp: String,
        val level: Char,
        val tag: String,
        val message: String
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val rawLines = mutableListOf<String>()
    private var process: Process? = null

    private val year by lazy {
        SimpleDateFormat("yyyy", Locale.US).format(Date())
    }

    private val timeFormatter by lazy {
        SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder().command("logcat", "-b", "all", "-v", "threadtime", "*:V")
        builder.environment()["LC_ALL"] = "C"
        try {
            process = try {
                builder.start()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start logcat", e)
                return@withContext
            }
            val stdout = BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))

            while (true) {
                val line = stdout.readLine() ?: break
                val entry = parseLine(line) ?: continue
                if (!entry.tag.startsWith(TAG_PREFIX)) continue

                synchronized(rawLines) {
                    rawLines.add(line)
                }

                synchronized(_entries) {
                    val current = _entries.value.toMutableList()
                    current.add(entry)
                    _entries.value = current
                }
            }
        } finally {
            process?.destroy()
            process = null
        }
    }

    fun getRawLines(): List<String> {
        synchronized(rawLines) {
            return rawLines.toList()
        }
    }

    fun clear() {
        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to clear logcat", e)
        }
        synchronized(rawLines) {
            rawLines.clear()
        }
        synchronized(_entries) {
            _entries.value = emptyList()
        }
    }

    fun close() {
        process?.destroy()
        process = null
    }

    private fun parseTime(timeStr: String): String {
        return try {
            val fullDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .parse("$year-$timeStr")
            if (fullDate != null) {
                timeFormatter.format(fullDate)
            } else {
                timeStr.substring(0, 8)
            }
        } catch (e: ParseException) {
            timeStr.substring(0, 8)
        }
    }

    private fun parseLine(line: String): LogEntry? {
        val m = THREADTIME_LINE.matcher(line)
        return if (m.matches()) {
            LogEntry(
                timestamp = parseTime(m.group(1)!!),
                level = m.group(4)!![0],
                tag = m.group(5)!!,
                message = m.group(6)!!
            )
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "WireGuard/DebugPacketLog"
        private const val TAG_PREFIX = "WireGuard"

        private val THREADTIME_LINE: Pattern =
            Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3})(?:\\s+[0-9A-Za-z]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$")
    }
}
