/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import com.wireguard.config.Attribute
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.ParseException
import com.wireguard.config.Peer

object ScionTunnelHelper {
    const val SCION_ALLOWED_IP_STRING = "fc00::/8"

    val scionInetNetwork: InetNetwork by lazy {
        InetNetwork.parse(SCION_ALLOWED_IP_STRING)
    }

    /**
     * Checks if [current] allowed IPs string contains "fc00::/8".
     * If not present, appends it and returns the updated string and `true`.
     * If already present, returns the original string and `false`.
     */
    fun ensureScionAllowedIp(current: String): Pair<String, Boolean> {
        val trimmed = current.trim()
        if (trimmed.isEmpty()) {
            return SCION_ALLOWED_IP_STRING to true
        }

        val tokens = Attribute.split(trimmed).map { it.trim() }.filter { it.isNotEmpty() }
        val targetNetwork = try {
            scionInetNetwork
        } catch (_: Throwable) {
            null
        }

        val alreadyPresent = tokens.any { token ->
            if (targetNetwork != null) {
                try {
                    InetNetwork.parse(token) == targetNetwork
                } catch (_: ParseException) {
                    token.equals(SCION_ALLOWED_IP_STRING, ignoreCase = true)
                }
            } else {
                token.equals(SCION_ALLOWED_IP_STRING, ignoreCase = true)
            }
        }

        if (alreadyPresent) {
            return current to false
        }

        val updated = "${tokens.joinToString(", ")}, $SCION_ALLOWED_IP_STRING"
        return updated to true
    }

    /**
     * Ensures all peers in [peers] contain "fc00::/8" in their allowed IPs.
     * Returns the collection of peers and whether any peer was modified.
     */
    fun ensureScionPeers(peers: Collection<Peer>): Pair<List<Peer>, Boolean> {
        val targetNetwork = try {
            scionInetNetwork
        } catch (_: Throwable) {
            return peers.toList() to false
        }

        var anyModified = false
        val updated = peers.map { peer ->
            if (!peer.allowedIps.contains(targetNetwork)) {
                anyModified = true
                val builder = Peer.Builder()
                    .addAllowedIps(peer.allowedIps)
                    .addAllowedIp(targetNetwork)
                    .setPublicKey(peer.publicKey)
                peer.endpoint.ifPresent { builder.setEndpoint(it) }
                peer.persistentKeepalive.ifPresent { builder.setPersistentKeepalive(it) }
                peer.preSharedKey.ifPresent { builder.setPreSharedKey(it) }
                builder.build()
            } else {
                peer
            }
        }
        return updated to anyModified
    }

    /**
     * If the config is in SCION mode, ensures all peers contain "fc00::/8".
     */
    fun ensureScionConfig(config: Config): Config {
        if (config.`interface`.tunnelMode != Interface.TunnelMode.SCION) {
            return config
        }
        val (updatedPeers, modified) = ensureScionPeers(config.peers)
        if (!modified) {
            return config
        }
        return Config.Builder()
            .setInterface(config.`interface`)
            .addPeers(updatedPeers)
            .build()
    }
}
