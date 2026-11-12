/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import com.wireguard.android.fragment.TunnelEditorFragment
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapUrlExtractorTest {

    @Test
    fun testPlainHttpsUrl() {
        val input = "https://bootstrap.scion.org/topology.json"
        val result = TunnelEditorFragment.extractBootstrapUrl(input)
        assertEquals("https://bootstrap.scion.org/topology.json", result)
    }

    @Test
    fun testPlainHttpUrlWithWhitespaceAndNewlines() {
        val input = "  \n  http://192.168.1.100:8080/bootstrap  \n\t "
        val result = TunnelEditorFragment.extractBootstrapUrl(input)
        assertEquals("http://192.168.1.100:8080/bootstrap", result)
    }

    @Test
    fun testQuotedUrl() {
        val input = "\"https://bootstrap.scion.org/api\""
        val result = TunnelEditorFragment.extractBootstrapUrl(input)
        assertEquals("https://bootstrap.scion.org/api", result)
    }

    @Test
    fun testJsonPayloadWithBootstrapUrl() {
        val json = """{"bootstrap_url": "https://bootstrap.example.com/scion"}"""
        val result = TunnelEditorFragment.extractBootstrapUrl(json)
        assertEquals("https://bootstrap.example.com/scion", result)
    }

    @Test
    fun testJsonPayloadWithCamelCaseBootstrapUrl() {
        val json = """{"bootstrapUrl": "https://scion.org/bootstrap.json"}"""
        val result = TunnelEditorFragment.extractBootstrapUrl(json)
        assertEquals("https://scion.org/bootstrap.json", result)
    }

    @Test
    fun testJsonPayloadWithUrlKey() {
        val json = """{"url": "https://fallback.scion.org"}"""
        val result = TunnelEditorFragment.extractBootstrapUrl(json)
        assertEquals("https://fallback.scion.org", result)
    }

    @Test
    fun testWireGuardConfigContent() {
        val conf = """
            [Interface]
            PrivateKey = aGVsbG93b3JsZCE=
            Address = 10.0.0.2/24
            BootstrapUrl = https://bootstrap.scionlab.org/v1
            ListenPort = 51820

            [Peer]
            PublicKey = c2Npb250ZXN0IQ==
            Endpoint = 198.51.100.1:51820
        """.trimIndent()
        val result = TunnelEditorFragment.extractBootstrapUrl(conf)
        assertEquals("https://bootstrap.scionlab.org/v1", result)
    }

    @Test
    fun testEmptyAndBlankStrings() {
        assertEquals("", TunnelEditorFragment.extractBootstrapUrl(""))
        assertEquals("", TunnelEditorFragment.extractBootstrapUrl("   \n\t "))
    }
}

