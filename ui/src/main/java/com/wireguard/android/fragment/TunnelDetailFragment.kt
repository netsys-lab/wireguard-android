/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.widget.ToggleSwitch
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelDetailPeerBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Fragment that shows the connection status hub for a specific tunnel.
 * Displays connection state, uptime timer, speed stats, and mock flow data.
 */
class TunnelDetailFragment : BaseFragment() {
    private var binding: TunnelDetailFragmentBinding? = null
    private var lastState = Tunnel.State.TOGGLE
    private var timerActive = true

    // Simple connection timer
    private var connectedSinceMillis: Long = 0L

    // Speed calculation
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastStatsTimeMillis: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelDetailFragmentBinding.inflate(inflater, container, false)
        binding?.executePendingBindings()
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadMockFlowIcons()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        timerActive = true
        lifecycleScope.launch {
            while (timerActive) {
                updateStats()
                updateTimer()
                delay(1000)
            }
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        if (newTunnel == null) {
            binding.config = null
        } else {
            lifecycleScope.launch {
                try {
                    val config = newTunnel.getConfigAsync()
                    binding.config = config
                } catch (_: Throwable) {
                    binding.config = null
                }
            }
        }
        // Reset timer and speed on tunnel change
        lastState = Tunnel.State.TOGGLE
        connectedSinceMillis = 0L
        lastRxBytes = 0L
        lastTxBytes = 0L
        lastStatsTimeMillis = 0L
        lifecycleScope.launch { updateStats() }
    }

    override fun onStop() {
        timerActive = false
        super.onStop()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        binding ?: return
        binding!!.fragment = this
        onSelectedTunnelChanged(null, selectedTunnel)
        super.onViewStateRestored(savedInstanceState)
    }

    // ─── Navigation ─────────────────────────────────────────

    fun onBackPressed(@Suppress("UNUSED_PARAMETER") view: View) {
        activity?.onBackPressedDispatcher?.onBackPressed()
    }

    fun onEditClick(view: View) {
        val parentFm = parentFragmentManager
        val containerId =
            if (parentFm.findFragmentById(R.id.detail_container) != null) {
                R.id.detail_container
            } else {
                R.id.list_detail_container
            }

        parentFm.beginTransaction()
            .replace(containerId, TunnelEditorFragment())
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .addToBackStack(null)
            .commit()
    }

    fun onSeeAllFlows(@Suppress("UNUSED_PARAMETER") view: View) {
        // TODO: Navigate to full flows screen
        Toast.makeText(context, "Path Information coming soon", Toast.LENGTH_SHORT).show()
    }

    fun onFlowItemClicked(view: View) {
        // Show Path Selection bottom sheet
        val flowName = when (view.id) {
            R.id.flow_item_1 -> "Google Chrome"
            R.id.flow_item_2 -> "Slack"
            R.id.flow_item_3 -> "System Update"
            else -> "Unknown"
        }
        val sheet = PathSelectionBottomSheet.newInstance(flowName)
        sheet.show(childFragmentManager, "path_selection")
    }

    // ─── Mock Flow Icons ────────────────────────────────────

    private fun loadMockFlowIcons() {
        val binding = binding ?: return
        val pm = context?.packageManager ?: return

        // Try to load real app icons for 3 popular apps
        val appIconPairs = listOf(
            Triple("com.android.chrome", binding.flowIcon1, binding.flowName1),
            Triple("com.slack", binding.flowIcon2, binding.flowName2),
            Triple("com.google.android.gms", binding.flowIcon3, binding.flowName3)
        )

        // Get installed apps to pick icons from
        val installedApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForApp(it.packageName) != null }
                .take(10)
        } catch (_: Exception) {
            emptyList()
        }

        for ((index, triple) in appIconPairs.withIndex()) {
            val (packageName, iconView, nameView) = triple
            try {
                val icon = pm.getApplicationIcon(packageName)
                iconView.setImageDrawable(icon)
            } catch (_: PackageManager.NameNotFoundException) {
                // Try to use an installed app instead
                if (index < installedApps.size) {
                    try {
                        val appInfo = installedApps[index]
                        iconView.setImageDrawable(pm.getApplicationIcon(appInfo))
                        nameView.text = pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        // Keep default globe icon
                    }
                }
            }
        }
    }

    private fun PackageManager.getLaunchIntentForApp(packageName: String): android.content.Intent? {
        return getLaunchIntentForPackage(packageName)
    }

    // ─── Tunnel State ───────────────────────────────────────

    fun setTunnelState(checked: Boolean) {
        val tunnel = binding?.tunnel ?: return
        val state = if (checked) Tunnel.State.UP else Tunnel.State.DOWN
        lifecycleScope.launch {
            try {
                tunnel.setStateAsync(state)
            } catch (e: Throwable) {
                Toast.makeText(context, com.wireguard.android.util.ErrorMessages[e], Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─── Timer ──────────────────────────────────────────────

    private fun updateTimer() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return

        if (tunnel.state == Tunnel.State.UP) {
            if (connectedSinceMillis == 0L) {
                connectedSinceMillis = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - connectedSinceMillis
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / (1000 * 60)) % 60
            val hours = (elapsed / (1000 * 60 * 60))
            binding.uptimeTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            connectedSinceMillis = 0L
            binding.uptimeTimer.text = "00:00:00"
        }
    }

    // ─── Stats Polling ──────────────────────────────────────

    private suspend fun updateStats() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        val state = tunnel.state
        if (state != Tunnel.State.UP && lastState == state) return
        lastState = state

        if (state != Tunnel.State.UP) {
            binding.downloadSpeed.text = "↓ 0.0"
            binding.uploadSpeed.text = "↑ 0.0"
            binding.hubWifiIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scitra_outline))
            
            // Animate shadow off
            binding.hubContainer.animate().translationZ(0f).setDuration(300).start()
            
            lastRxBytes = 0L
            lastTxBytes = 0L
            lastStatsTimeMillis = 0L
            return
        } else {
            binding.hubWifiIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scitra_primary))
            
            // Animate shadow on (light glow effect)
            binding.hubContainer.animate().translationZ(24f).setDuration(300).start()
        }

        try {
            val statistics = tunnel.getStatisticsAsync()

            // Aggregate all peer stats
            var totalRx = 0L
            var totalTx = 0L
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                val publicKey = peer.item!!.publicKey
                val peerStats = statistics.peer(publicKey) ?: continue
                totalRx += peerStats.rxBytes
                totalTx += peerStats.txBytes
            }

            // Calculate speed (bytes per second)
            val now = System.currentTimeMillis()
            if (lastStatsTimeMillis > 0 && lastRxBytes > 0) {
                val timeDelta = (now - lastStatsTimeMillis) / 1000.0
                if (timeDelta > 0) {
                    val rxSpeed = (totalRx - lastRxBytes) / timeDelta
                    val txSpeed = (totalTx - lastTxBytes) / timeDelta
                    binding.downloadSpeed.text = "↓ ${formatSpeed(rxSpeed)}"
                    binding.uploadSpeed.text = "↑ ${formatSpeed(txSpeed)}"
                }
            }
            lastRxBytes = totalRx
            lastTxBytes = totalTx
            lastStatsTimeMillis = now

        } catch (_: Throwable) {
            // Silently ignore
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond < 0 -> "0.0"
            bytesPerSecond < 1024 -> String.format(Locale.US, "%.1f B/s", bytesPerSecond)
            bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024)
            else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024))
        }
    }

    // ─── SCION Toggle ───────────────────────────────────────

    fun toggleScionMode(view: View, checked: Boolean) {
        val toggleSwitch = view as? ToggleSwitch ?: return
        val tunnel = binding?.tunnel ?: return
        toggleSwitch.isEnabled = false
        lifecycleScope.launch {
            try {
                val currentConfig = tunnel.getConfigAsync()
                val currentMode = currentConfig.`interface`.tunnelMode
                val newMode = if (currentMode.isScion) "IP" else "SCION"

                val newInterfaceBuilder = Interface.Builder()
                    .addAddresses(currentConfig.`interface`.addresses)
                    .addDnsServers(currentConfig.`interface`.dnsServers)
                    .addDnsSearchDomains(currentConfig.`interface`.dnsSearchDomains)
                    .excludeApplications(currentConfig.`interface`.excludedApplications)
                    .includeApplications(currentConfig.`interface`.includedApplications)
                    .setKeyPair(currentConfig.`interface`.keyPair)
                    .setBootstrapUrl(currentConfig.`interface`.bootstrapUrl)
                    .setPathPolicy(currentConfig.`interface`.pathPolicy)
                    .setTunnelMode(Interface.TunnelMode.valueOf(newMode))

                currentConfig.`interface`.listenPort.ifPresent { newInterfaceBuilder.setListenPort(it) }
                currentConfig.`interface`.mtu.ifPresent { newInterfaceBuilder.setMtu(it) }

                val newConfig = Config.Builder()
                    .setInterface(newInterfaceBuilder.build())
                    .addPeers(currentConfig.peers)
                    .build()

                tunnel.setConfigAsync(newConfig)
                binding?.config = newConfig
                toggleSwitch.setCheckedInternal(checked)
                val label = if (newMode == "SCION") "SCION" else "IP"
                Toast.makeText(context, "Switched to $label mode", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                toggleSwitch.setCheckedInternal(!checked)
                Toast.makeText(context, "Error switching mode: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
