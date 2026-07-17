/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DebugPacketLogStreamer {

    private typealias LogEntry = DebugPacketLogModels.LogEntry

    /**
     * Bounded in-memory line count. When exceeded, the oldest entries are dropped and
     * [getTrimmedCount] is incremented so the viewer can indicate older lines were removed.
     */
    companion object {
        const val MAX_ENTRIES = DebugPacketLogModels.MAX_ENTRIES
        private const val TAG = "WireGuard/DebugPacketLog"
        private const val TAG_PREFIX = "WireGuard"
    }

    sealed interface StartResult {
        object Started : StartResult
        data class Failed(val cause: Throwable) : StartResult
    }

    /**
     * A closable source of log lines. Production uses a [BufferedReader] over the logcat
     * process input stream; tests inject a controllable fake.
     */
    interface LineSource : Closeable {
        @Throws(IOException::class)
        fun readLine(): String?
    }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var trimmedCount = 0

    private val rawLines = mutableListOf<String>()
    private val year by lazy {
        SimpleDateFormat("yyyy", Locale.US).format(Date())
    }

    private val job = AtomicReference<Job?>(null)
    private val processRef = AtomicReference<Process?>(null)
    private val readerRef = AtomicReference<LineSource?>(null)
    private val closed = AtomicBoolean(false)

    @Volatile
    internal var processSourceFactory: (() -> Pair<Process, LineSource>?)? = null

    var onFailure: ((Throwable) -> Unit)? = null
        @Synchronized get
        @Synchronized set

    fun isRunning(): Boolean = job.get()?.isActive == true

    fun start(scope: CoroutineScope): StartResult {
        if (closed.get()) return StartResult.Started
        val existing = job.get()
        if (existing != null && existing.isActive) return StartResult.Started
        val launchJob = scope.launch(Dispatchers.IO) {
            runLogLoop()
        }
        job.set(launchJob)
        return StartResult.Started
    }

    private fun openProcessSource(): Pair<Process, LineSource>? {
        val factory = processSourceFactory
        if (factory != null) return factory()

        val builder = ProcessBuilder().command("logcat", "-b", "all", "-v", "threadtime", "*:V")
        builder.environment()["LC_ALL"] = "C"
        val process: Process = try {
            builder.start()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start logcat", e)
            reportUnexpectedFailure(e)
            return null
        }

        if (!processRef.compareAndSet(null, process)) {
            // Another start() won the race; destroy the redundant process.
            process.destroy()
            return null
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        val source = object : LineSource {
            override fun readLine(): String? = reader.readLine()
            override fun close() = reader.close()
        }
        if (!readerRef.compareAndSet(null, source)) {
            processRef.compareAndSet(process, null)
            process.destroy()
            return null
        }
        return process to source
    }

    private suspend fun runLogLoop() {
        val opened = openProcessSource() ?: return
        val (process, reader) = opened
        if (process !== processRef.get()) {
            // Duplicate stream already torn down; nothing to do here.
            return
        }
        try {
            consumeLines(reader)
        } finally {
            closeResourcesOnce()
        }
    }

    private fun isLoopActive(): Boolean = !closed.get() && job.get()?.isActive == true

    private suspend fun consumeLines(reader: LineSource) {
        try {
            while (isLoopActive()) {
                val line: String? = try {
                    reader.readLine()
                } catch (io: IOException) {
                    if (!isLoopActive()) {
                        Log.d(TAG, "Log stream closed during shutdown")
                        break
                    }
                    throw io
                }
                if (line == null) break
                val parsed = DebugPacketLogParser.parseLine(line, year)
                if (!parsed.tag.startsWith(TAG_PREFIX) && parsed.parsed) continue

                synchronized(rawLines) {
                    rawLines.add(line)
                }
                addEntry(parsed)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            if (!isLoopActive()) {
                Log.d(TAG, "Log stream closed during shutdown")
            } else {
                reportUnexpectedFailure(io)
            }
        }
    }

    private fun reportUnexpectedFailure(e: Throwable) {
        val cb = synchronized(this) { onFailure }
        cb?.invoke(e) ?: Log.e(TAG, "Unexpected log stream failure", e)
    }

    private fun addEntry(parsed: DebugPacketLogParser.ParsedLog) {
        synchronized(_entries) {
            val current = _entries.value.toMutableList()
            current.add(
                DebugPacketLogModels.LogEntry(
                    timestamp = parsed.displayTime.substringBefore('.'),
                    level = parsed.level,
                    tag = parsed.tag,
                    message = parsed.displayMessage,
                    displayTime = parsed.displayTime,
                    displayComponent = parsed.displayComponent,
                    displayLevel = parsed.displayLevel,
                    displayMessage = parsed.displayMessage,
                    rawLine = parsed.rawLine,
                    packetId = parsed.packetId,
                    refreshId = parsed.refreshId,
                    parsed = parsed.parsed
                )
            )
            if (current.size > MAX_ENTRIES) {
                val overflow = current.size - MAX_ENTRIES
                current.subList(0, overflow).clear()
                trimmedCount += overflow
            }
            _entries.value = current
        }
    }

    fun getRawLines(): List<String> {
        synchronized(rawLines) {
            return rawLines.toList()
        }
    }

    fun getTrimmedCount(): Int = synchronized(_entries) { trimmedCount }

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
            trimmedCount = 0
        }
    }

    fun close() {
        closed.set(true)
        job.get()?.cancel()
        closeResourcesOnce()
    }

    private fun closeResourcesOnce() {
        readerRef.getAndSet(null)?.let { reader ->
            try {
                reader.close()
            } catch (e: IOException) {
                Log.d(TAG, "Reader already closed", e)
            }
        }
        processRef.getAndSet(null)?.let { process ->
            if (process.isAlive) {
                process.destroy()
            }
        }
    }
}
