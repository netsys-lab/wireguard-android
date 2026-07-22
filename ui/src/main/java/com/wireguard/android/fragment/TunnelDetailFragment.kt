/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.widget.ToggleSwitch
import com.wireguard.android.fragment.PathSelectionBottomSheet
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelDetailPeerBinding
import com.wireguard.android.model.FlowDto
import com.wireguard.android.model.FlowEgressKind
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.FlowRepository
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Fragment that shows the connection status hub for a specific tunnel.
 * Displays connection state, uptime timer, speed stats, and tracked network flows.
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

    // Flow tracking
    private var flowRepository: FlowRepository? = null
    private var lastFlowCount: Int = 0
    private var showAllFlows: Boolean = false
    private var lastAllFlows: List<FlowDto> = emptyList()

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
                updateFlows()
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
        // Reset timer, speed, and flows on tunnel change
        lastState = Tunnel.State.TOGGLE
        connectedSinceMillis = 0L
        lastRxBytes = 0L
        lastTxBytes = 0L
        lastStatsTimeMillis = 0L
        lastFlowCount = 0
        lastAllFlows = emptyList()
        showAllFlows = false
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

    fun onFlowItemClicked(flow: FlowDto) {
        if (flow.egressKindEnum != FlowEgressKind.SCION) return
        val label = "Flow #${flow.id}"
        val sheet = PathSelectionBottomSheet.newInstance(label)
        sheet.show(childFragmentManager, "path_selection")
    }

    fun onToggleFlowFilter(view: View) {
        showAllFlows = !showAllFlows
        renderFilteredFlows(lastAllFlows)
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

    // ─── Flow Polling ──────────────────────────────────────

    private suspend fun updateFlows() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        if (tunnel.state != Tunnel.State.UP) {
            if (lastFlowCount != -1) {
                updateFilterButton(binding)
                showFlowEmptyState(binding, R.string.tunnel_not_running)
                lastFlowCount = -1
            }
            return
        }

        val backend = resolveBackend()
        if (backend == null) {
            if (lastFlowCount != -1) {
                updateFilterButton(binding)
                showFlowEmptyState(binding, R.string.tunnel_not_running)
                lastFlowCount = -1
            }
            return
        }
        val repo = flowRepository ?: FlowRepository(backend).also { flowRepository = it }

        try {
            val result = repo.getFlows(tunnel)
            val flows = result.getOrNull()
            if (flows == null) {
                showFlowEmptyState(binding, R.string.no_active_flows)
                lastFlowCount = -1
                return
            }
            lastAllFlows = flows
            renderFilteredFlows(flows)
        } catch (_: Exception) {
            showFlowEmptyState(binding, R.string.no_active_flows)
            lastFlowCount = -1
        }
    }

    private fun renderFilteredFlows(flows: List<FlowDto>) {
        val binding = binding ?: return
        if (binding.flowsContainer == null) return
        updateFilterButton(binding)

        val filtered = if (showAllFlows) flows else flows.filter { it.egressKindEnum == FlowEgressKind.SCION }
        val container = binding.flowsContainer
        container.removeAllViews()

        if (filtered.isEmpty()) {
            val msgRes = when {
                flows.isEmpty() -> R.string.no_active_flows
                !showAllFlows -> R.string.no_active_scion_flows
                else -> R.string.no_active_flows
            }
            showFlowEmptyState(binding, msgRes)
            lastFlowCount = -1
            return
        }

        for (flow in filtered) {
            container.addView(createFlowRowView(flow))
        }
        lastFlowCount = filtered.size
    }

    private fun updateFilterButton(binding: TunnelDetailFragmentBinding) {
        binding.flowFilterText.text = if (showAllFlows) {
            context?.getString(R.string.filter_show_scion)
        } else {
            context?.getString(R.string.filter_show_all)
        }
    }

    private fun showFlowEmptyState(binding: TunnelDetailFragmentBinding, msgResId: Int) {
        binding.flowsContainer.removeAllViews()
        val context = binding.flowsContainer.context
        val msg = context.getString(msgResId)
        val tv = TextView(context).apply {
            text = msg
            setTextColor(ContextCompat.getColor(context, R.color.scitra_on_surface_variant))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
        }
        binding.flowsContainer.addView(tv)
    }

    private suspend fun resolveBackend(): GoBackend? {
        return try {
            val backend = com.wireguard.android.Application.getBackend()
            backend as? GoBackend
        } catch (_: Exception) {
            null
        }
    }

    private fun createFlowRowView(flow: FlowDto): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 6 }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(12, 8, 12, 8)
        row.setBackgroundResource(R.drawable.scitra_flow_item_bg)

        val proto = "${formatProtocolShort(flow.protocol)}·${formatIPVersionShort(flow.ipVersion)}"
        val protoView = TextView(ctx).apply {
            text = proto
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_primary))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(protoView)

        addGap(row, ctx, 6)

        val endpoints = "${formatEndpoint(flow.endpointA)}${ctx.getString(R.string.flow_endpoint_arrow)}${formatEndpoint(flow.endpointB)}"
        val epView = TextView(ctx).apply {
            text = endpoints
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(epView)

        addGap(row, ctx, 6)

        val statusView = TextView(ctx).apply {
            text = flow.status.uppercase()
            setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(statusView)

        addGap(row, ctx, 6)

        val txView = TextView(ctx).apply {
            text = "TX ${formatBytesCompact(flow.txBytes)}·${flow.txPackets}"
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface_variant))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(txView)

        addGap(row, ctx, 4)

        val rxView = TextView(ctx).apply {
            text = "RX ${formatBytesCompact(flow.rxBytes)}·${flow.rxPackets}"
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface_variant))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(rxView)

        // Click behavior: only SCION flows are clickable
        if (flow.egressKindEnum == FlowEgressKind.SCION) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onFlowItemClicked(flow) }
        }

        // Accessibility
        val epShort = formatEndpoint(flow.endpointA).take(30) + " → " + formatEndpoint(flow.endpointB).take(30)
        val desc = "${formatProtocolShort(flow.protocol)} ${formatIPVersionShort(flow.ipVersion)}, $epShort, ${flow.status}, TX ${flow.txPackets} packets ${flow.txBytes} bytes, RX ${flow.rxPackets} packets ${flow.rxBytes} bytes"
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        row.contentDescription = if (flow.egressKindEnum == FlowEgressKind.SCION) "$desc. Selectable" else desc

        return row
    }

    private fun addGap(parent: LinearLayout, ctx: android.content.Context, widthDp: Int) {
        val gap = View(ctx)
        gap.layoutParams = LinearLayout.LayoutParams(
            (widthDp * ctx.resources.displayMetrics.density).toInt(),
            0
        )
        parent.addView(gap)
    }

    // ─── Flow Formatting Helpers ────────────────────────────

    private fun formatProtocolShort(protocol: Int): String = when (protocol) {
        6 -> "TCP"
        17 -> "UDP"
        else -> "P$protocol"
    }

    private fun formatIPVersionShort(version: Int): String = "v$version"

    private fun formatEndpoint(endpoint: com.wireguard.android.model.FlowEndpointDto): String {
        val addr = endpoint.address
        return if (addr.contains(":")) "[$addr]:${endpoint.port}" else "$addr:${endpoint.port}"
    }

    private fun formatBytesCompact(bytes: Long): String {
        return when {
            bytes < 0 -> "0B"
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fK", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fM", bytes / (1024.0 * 1024.0))
            bytes < 1024L * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1fG", bytes / (1024.0 * 1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.1fT", bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0))
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
