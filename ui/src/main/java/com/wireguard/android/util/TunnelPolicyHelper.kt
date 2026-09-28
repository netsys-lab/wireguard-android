/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.ScitraPolicyConfig
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TunnelPolicySummary(
    val hasPolicyConfigured: Boolean,
    val matcherCount: Int,
    val customPolicyCount: Int,
    val policyNames: List<String>,
    val matchersSummary: String,
    val rawJson: String,
)

object TunnelPolicyHelper {

    suspend fun getPolicyJson(tunnel: ObservableTunnel): String = withContext(Dispatchers.IO) {
        try {
            val config = tunnel.getConfigAsync()
            config?.`interface`?.pathPolicy ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun getPolicyConfig(tunnel: ObservableTunnel): ScitraPolicyConfig = withContext(Dispatchers.IO) {
        val json = getPolicyJson(tunnel)
        PathPolicyParser.parseConfig(json)
    }

    suspend fun getPolicySummary(tunnel: ObservableTunnel): TunnelPolicySummary = withContext(Dispatchers.IO) {
        val json = getPolicyJson(tunnel)
        val config = PathPolicyParser.parseConfig(json)

        val customPolicies = config.policies.keys.filter { it != "default" }
        val matcherCount = config.matchers.size
        val hasConfigured = json.isNotBlank() && (config.matchers.isNotEmpty() || config.policies.isNotEmpty())

        val matchersSummary = when {
            matcherCount == 0 -> "No matchers (All traffic uses default)"
            matcherCount == 1 -> {
                val m = config.matchers.first()
                val target = m.appName ?: m.destination ?: m.protocol ?: "Rule #1"
                "1 active rule ($target)"
            }
            else -> {
                val firstTargets = config.matchers.take(2).mapNotNull { it.appName ?: it.destination ?: it.protocol }
                if (firstTargets.isNotEmpty()) {
                    "$matcherCount rules (${firstTargets.joinToString(", ")}...)"
                } else {
                    "$matcherCount active rules configured"
                }
            }
        }

        TunnelPolicySummary(
            hasPolicyConfigured = hasConfigured,
            matcherCount = matcherCount,
            customPolicyCount = customPolicies.size,
            policyNames = config.policies.keys.toList(),
            matchersSummary = matchersSummary,
            rawJson = json
        )
    }

    suspend fun savePolicyJson(tunnel: ObservableTunnel, newJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentConfig = tunnel.getConfigAsync()
            val newInterfaceBuilder = Interface.Builder()
                .addAddresses(currentConfig.`interface`.addresses)
                .addDnsServers(currentConfig.`interface`.dnsServers)
                .addDnsSearchDomains(currentConfig.`interface`.dnsSearchDomains)
                .excludeApplications(currentConfig.`interface`.excludedApplications)
                .includeApplications(currentConfig.`interface`.includedApplications)
                .setKeyPair(currentConfig.`interface`.keyPair)
                .setBootstrapUrl(currentConfig.`interface`.bootstrapUrl)
                .setPathPolicy(newJson)
                .setTunnelMode(currentConfig.`interface`.tunnelMode)

            currentConfig.`interface`.listenPort.ifPresent { newInterfaceBuilder.setListenPort(it) }
            currentConfig.`interface`.mtu.ifPresent { newInterfaceBuilder.setMtu(it) }

            val newConfig = Config.Builder()
                .setInterface(newInterfaceBuilder.build())
                .addPeers(currentConfig.peers)
                .build()

            tunnel.setConfigAsync(newConfig)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun savePolicyConfig(tunnel: ObservableTunnel, config: ScitraPolicyConfig): Boolean {
        val serialized = PathPolicyParser.serializeConfig(config, 2)
        return savePolicyJson(tunnel, serialized)
    }
}
