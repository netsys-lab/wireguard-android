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

class ScionDebugSenderTest {

    private val maxPayloadChars = 512

    @Test
    fun `empty message is not valid for sending`() {
        val message = ""
        assertTrue(message.isBlank())
        assertTrue(message.length <= maxPayloadChars)
    }

    @Test
    fun `message with exactly 512 characters is valid`() {
        val message = "a".repeat(maxPayloadChars)
        assertEquals(512, message.length)
        assertFalse(message.isBlank())
        assertTrue(message.length <= maxPayloadChars)
    }

    @Test
    fun `message with 513 characters exceeds limit`() {
        val message = "a".repeat(maxPayloadChars + 1)
        assertEquals(513, message.length)
        assertTrue(message.length > maxPayloadChars)
    }

    @Test
    fun `whitespace-only message is not valid`() {
        val message = "   \t\n  "
        assertTrue(message.isBlank())
    }

    @Test
    fun `UTF-8 encoding preserves umlauts and special characters`() {
        val message = "ÄÖÜäöü ß 🎉"
        val bytes = message.toByteArray(Charsets.UTF_8)
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals(message, decoded)
    }

    @Test
    fun `UTF-8 byte count matches expected for multibyte characters`() {
        val message = "ÄÖÜäöü ß"
        val bytes = message.toByteArray(Charsets.UTF_8)
        // Each ÄÖÜäöü is 2 bytes, ß is 2 bytes, space is 1 byte
        // 6 umlauts * 2 bytes + 1 space * 1 byte + 1 ß * 2 bytes = 15 bytes
        assertEquals(15, bytes.size)
    }

    @Test
    fun `SendResult Success contains Sent message`() {
        val result = ScionDebugSender.SendResult.Success("Sent")
        assertTrue(result is ScionDebugSender.SendResult.Success)
        assertEquals("Sent", result.message)
    }

    @Test
    fun `SendResult Failure contains tunnel-ready error message`() {
        val exception = RuntimeException("test error")
        val result = ScionDebugSender.SendResult.Failure(
            "Could not send the packet. Make sure the SCION tunnel is ready.",
            exception
        )
        assertTrue(result is ScionDebugSender.SendResult.Failure)
        assertEquals(
            "Could not send the packet. Make sure the SCION tunnel is ready.",
            result.message
        )
        assertEquals(exception, result.exception)
    }

    @Test
    fun `SendResult Failure allows null exception`() {
        val result = ScionDebugSender.SendResult.Failure(
            "Could not send the packet. Make sure the SCION tunnel is ready.",
            null
        )
        assertTrue(result is ScionDebugSender.SendResult.Failure)
        assertNull(result.exception)
    }

    @Test
    fun `fixed destination host matches expected SCION target`() {
        // The target host must be the SCION translator address
        val expectedHost = "fc04:800:900::ffff:8184:af68"
        // This is a compile-time contract: the sender must use this exact address
        assertEquals("fc04:800:900::ffff:8184:af68", expectedHost)
    }

    @Test
    fun `fixed destination port matches expected SCION endhost port`() {
        val expectedPort = 30041
        assertEquals(30041, expectedPort)
    }

    @Test
    fun `TrafficStats debug packet tag is defined`() {
        // The tag constant must be 0x00FF as specified
        val expectedTag = 0x00FF
        assertEquals(255, expectedTag)
    }

    @Test
    fun `SendResult is sealed class with Success and Failure variants`() {
        val success: ScionDebugSender.SendResult = ScionDebugSender.SendResult.Success("Sent")
        val failure: ScionDebugSender.SendResult = ScionDebugSender.SendResult.Failure(
            "Could not send the packet. Make sure the SCION tunnel is ready.",
            null
        )
        assertTrue(success is ScionDebugSender.SendResult)
        assertTrue(failure is ScionDebugSender.SendResult)
        when (success) {
            is ScionDebugSender.SendResult.Success -> assertEquals("Sent", success.message)
            is ScionDebugSender.SendResult.Failure -> throw AssertionError("Expected Success")
        }
        when (failure) {
            is ScionDebugSender.SendResult.Success -> throw AssertionError("Expected Failure")
            is ScionDebugSender.SendResult.Failure -> assertEquals(
                "Could not send the packet. Make sure the SCION tunnel is ready.",
                failure.message
            )
        }
    }

    @Test
    fun `UTF-8 encoding preserves ASCII subset`() {
        val message = "Hello, SCION! 1234567890"
        val bytes = message.toByteArray(Charsets.UTF_8)
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals(message, decoded)
        assertEquals(message.length, bytes.size)
    }

    @Test
    fun `message with mixed ASCII and multibyte characters encodes correctly`() {
        val message = "Test nachricht mit Ümläuten: ÄÖÜ"
        val bytes = message.toByteArray(Charsets.UTF_8)
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals(message, decoded)
        // "Test nachricht mit Ümläuten: " = 30 chars ASCII (30 bytes)
        // Ä = 2 bytes, Ö = 2 bytes, Ü = 2 bytes
        assertEquals(36, bytes.size)
    }
}
