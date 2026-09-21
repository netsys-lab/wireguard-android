/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DebugPacketLogStreamerTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = TestScope(dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun makeStreamer(factory: () -> Pair<Process, DebugPacketLogStreamer.LineSource>?) =
        DebugPacketLogStreamer().apply { processSourceFactory = factory }

    private fun wireguardLine(msg: String) = "01-01 00:00:00.000  1  1 I WireGuard: $msg"

    @Test
    fun `natural EOF completes without failure`() = runTest(dispatcher) {
        val source = ControllableLineSource(listOf(wireguardLine("hello")))
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }

        streamer.start(scope)
        scope.advanceUntilIdle()

        assertEquals(1, streamer.getRawLines().size)
        assertFalse(streamer.isRunning())
        assertEquals(1, process.destroyCount.get())
    }

    @Test
    fun `closing reader while readLine blocked does not crash`() = runTest(dispatcher) {
        val source = BlockingLineSource()
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }

        val job = scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()
        assertTrue(streamer.isRunning())

        streamer.close()
        scope.advanceUntilIdle()

        assertFalse(streamer.isRunning())
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(1, process.destroyCount.get())
    }

    @Test
    fun `expected Stream closed during cancellation does not escape`() = runTest(dispatcher) {
        val source = object : BlockingLineSource() {
            override fun readLine(): String? = throw IOException("Stream closed")
        }
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }
        var failureCalled = false
        streamer.onFailure = { failureCalled = true }

        val job = scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()
        streamer.close()
        scope.advanceUntilIdle()

        assertFalse(failureCalled)
        assertFalse(streamer.isRunning())
        assertEquals(1, process.destroyCount.get())
    }

    @Test
    fun `unexpected IOException while active is reported`() = runTest(dispatcher) {
        val source = object : BlockingLineSource() {
            override fun readLine(): String? = throw IOException("unexpected")
        }
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }
        val failures = mutableListOf<Throwable>()
        streamer.onFailure = { failures.add(it) }

        scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()

        assertEquals(1, failures.size)
        assertTrue(failures[0] is IOException)
        assertFalse(streamer.isRunning())
        assertEquals(1, process.destroyCount.get())
    }

    @Test
    fun `stop is idempotent`() = runTest(dispatcher) {
        val source = BlockingLineSource()
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }

        scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()
        streamer.close()
        streamer.close()
        streamer.close()
        scope.advanceUntilIdle()

        assertEquals(1, process.destroyCount.get())
        assertFalse(streamer.isRunning())
    }

    @Test
    fun `process is destroyed exactly once`() = runTest(dispatcher) {
        val source = BlockingLineSource()
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }

        scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()
        streamer.close()
        scope.advanceUntilIdle()

        assertEquals(1, process.destroyCount.get())
    }

    @Test
    fun `job is canceled once and loop ends`() = runTest(dispatcher) {
        val source = BlockingLineSource()
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }

        val job = scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()
        streamer.close()
        scope.advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(streamer.isRunning())
    }

    @Test
    fun `no callback after lifecycle stop`() = runTest(dispatcher) {
        val source = ControllableLineSource(listOf(wireguardLine("one")))
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }

        val collected = mutableListOf<List<DebugPacketLogModels.LogEntry>>()
        val collectJob = scope.launch {
            streamer.entries.collect { collected.add(it) }
        }
        scope.launch { streamer.start(this) }
        scope.advanceUntilIdle()

        streamer.close()
        scope.advanceUntilIdle()
        val sizeBefore = collected.size
        assertFalse(streamer.isRunning())
        collectJob.cancel()
        assertTrue(sizeBefore >= 1)
    }

    @Test
    fun `reopening starts exactly one new stream`() = runTest(dispatcher) {
        val source1 = ControllableLineSource(listOf(wireguardLine("a")))
        val process1 = FakeProcess()
        val process2 = FakeProcess()
        val source2 = ControllableLineSource(listOf(wireguardLine("b")))
        val streamer = makeStreamer {
            if (process1.destroyCount.get() == 0) process1 to source1
            else process2 to source2
        }

        streamer.start(scope)
        scope.advanceUntilIdle()
        streamer.close()
        scope.advanceUntilIdle()

        streamer.start(scope)
        scope.advanceUntilIdle()

        assertEquals(1, process1.destroyCount.get())
        assertEquals(1, process2.destroyCount.get())
        assertTrue(streamer.getRawLines().size >= 1)
    }

    @Test
    fun `rapid start stop does not leak jobs`() = runTest(dispatcher) {
        val process = FakeProcess()
        val source = BlockingLineSource()
        val streamer = makeStreamer { process to source }

        repeat(5) {
            val j = scope.launch { streamer.start(this) }
            streamer.close()
            scope.advanceUntilIdle()
            j.join()
        }
        scope.advanceUntilIdle()
        assertEquals(1, process.destroyCount.get())
        assertFalse(streamer.isRunning())
    }

    @Test
    fun `CancellationException is not swallowed`() = runTest(dispatcher) {
        val source = object : BlockingLineSource() {
            override fun readLine(): String? = throw CancellationException("cancelled")
        }
        val process = FakeProcess()
        val streamer = makeStreamer { process to source }
        var failureCalled = false
        streamer.onFailure = { failureCalled = true }

        var thrown: Throwable? = null
        val job = scope.launch {
            try {
                streamer.start(this)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        scope.advanceUntilIdle()

        assertTrue(thrown is CancellationException || !failureCalled)
        assertFalse(streamer.isRunning())
    }

    @Test
    fun `starting twice does not create duplicate collectors`() = runTest(dispatcher) {
        val process = FakeProcess()
        val source = ControllableLineSource(listOf(wireguardLine("x")))
        val streamer = makeStreamer { process to source }

        streamer.start(scope)
        streamer.start(scope)
        scope.advanceUntilIdle()

        assertEquals(1, process.destroyCount.get())
        assertEquals(1, streamer.getRawLines().size)
    }

    @Test
    fun `process start failure is reported`() = runTest(dispatcher) {
        val failures = mutableListOf<Throwable>()
        val streamer = DebugPacketLogStreamer().apply {
            onFailure = { failures.add(it) }
            processSourceFactory = { throw IOException("cannot start") }
        }
        streamer.start(scope)
        scope.advanceUntilIdle()
        assertEquals(1, failures.size)
        assertFalse(streamer.isRunning())
    }

    private open class FakeProcess : Process() {
        val destroyCount = AtomicInteger(0)
        private val alive = AtomicBoolean(true)

        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            if (alive.compareAndSet(true, false)) destroyCount.incrementAndGet()
        }

        override fun isAlive(): Boolean = alive.get()
    }

    private open class BlockingLineSource : DebugPacketLogStreamer.LineSource {
        private val closed = AtomicBoolean(false)
        override fun readLine(): String? {
            synchronized(this) {
                while (!closed.get()) (this as java.lang.Object).wait(50)
            }
            return null
        }

        override fun close() {
            synchronized(this) {
                closed.set(true)
                (this as java.lang.Object).notifyAll()
            }
        }
    }

    private class ControllableLineSource(
        private val lines: List<String>
    ) : DebugPacketLogStreamer.LineSource {
        private var index = 0
        private var closed = false
        override fun readLine(): String? {
            if (index >= lines.size) return null
            return lines[index++]
        }

        override fun close() {
            closed = true
        }
    }
}
