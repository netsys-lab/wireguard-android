/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScionTunnelHelperTest {

    @Test
    fun testEnsureScionAllowedIp_emptyString() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("")
        assertTrue(changed)
        assertEquals("fc00::/8", result)
    }

    @Test
    fun testEnsureScionAllowedIp_whitespaceString() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("   ")
        assertTrue(changed)
        assertEquals("fc00::/8", result)
    }

    @Test
    fun testEnsureScionAllowedIp_singleIpv4() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("10.0.0.2/32")
        assertTrue(changed)
        assertEquals("10.0.0.2/32, fc00::/8", result)
    }

    @Test
    fun testEnsureScionAllowedIp_multipleExistingSubnets() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("0.0.0.0/0, ::/0")
        assertTrue(changed)
        assertEquals("0.0.0.0/0, ::/0, fc00::/8", result)
    }

    @Test
    fun testEnsureScionAllowedIp_alreadyContainsExact() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("10.0.0.2/32, fc00::/8")
        assertFalse(changed)
        assertEquals("10.0.0.2/32, fc00::/8", result)
    }

    @Test
    fun testEnsureScionAllowedIp_alreadyContainsCaseInsensitive() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("10.0.0.2/32, FC00::/8")
        assertFalse(changed)
        assertEquals("10.0.0.2/32, FC00::/8", result)
    }

    @Test
    fun testEnsureScionAllowedIp_alreadyContainsAlone() {
        val (result, changed) = ScionTunnelHelper.ensureScionAllowedIp("fc00::/8")
        assertFalse(changed)
        assertEquals("fc00::/8", result)
    }

    @Test
    fun testEnsureScionPeers() {
        val keyPair = KeyPair()
        val peerWithout = Peer.Builder()
            .setPublicKey(keyPair.publicKey)
            .addAllowedIp(InetNetwork.parse("10.0.0.2/32"))
            .build()

        val peerWith = Peer.Builder()
            .setPublicKey(keyPair.publicKey)
            .addAllowedIp(InetNetwork.parse("10.0.0.3/32"))
            .addAllowedIp(InetNetwork.parse("fc00::/8"))
            .build()

        val (updatedPeers, anyModified) = ScionTunnelHelper.ensureScionPeers(listOf(peerWithout, peerWith))
        assertTrue(anyModified)
        assertEquals(2, updatedPeers.size)
        assertTrue(updatedPeers[0].allowedIps.contains(InetNetwork.parse("fc00::/8")))
        assertTrue(updatedPeers[1].allowedIps.contains(InetNetwork.parse("fc00::/8")))

        // Re-running on already satisfied peers
        val (_, secondModified) = ScionTunnelHelper.ensureScionPeers(updatedPeers)
        assertFalse(secondModified)
    }

    @Test
    fun testEnsureScionConfig_scionMode() {
        val keyPair = KeyPair()
        val iface = Interface.Builder()
            .setKeyPair(keyPair)
            .setTunnelMode(Interface.TunnelMode.SCION)
            .build()
        val peer = Peer.Builder()
            .setPublicKey(keyPair.publicKey)
            .addAllowedIp(InetNetwork.parse("192.168.1.1/32"))
            .build()
        val config = Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()

        val updated = ScionTunnelHelper.ensureScionConfig(config)
        val updatedPeer = updated.peers.first()
        assertTrue(updatedPeer.allowedIps.contains(InetNetwork.parse("fc00::/8")))
    }

    @Test
    fun testEnsureScionConfig_ipModeUnchanged() {
        val keyPair = KeyPair()
        val iface = Interface.Builder()
            .setKeyPair(keyPair)
            .setTunnelMode(Interface.TunnelMode.IP)
            .build()
        val peer = Peer.Builder()
            .setPublicKey(keyPair.publicKey)
            .addAllowedIp(InetNetwork.parse("192.168.1.1/32"))
            .build()
        val config = Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()

        val updated = ScionTunnelHelper.ensureScionConfig(config)
        val updatedPeer = updated.peers.first()
        assertFalse(updatedPeer.allowedIps.contains(InetNetwork.parse("fc00::/8")))
    }
}
