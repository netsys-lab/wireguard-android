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
import com.wireguard.android.model.SCIONInfoDto
import com.wireguard.android.model.parseSCIONInfo
import com.wireguard.android.util.FlowRepository
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var flowSnapshotCache: MutableMap<Long, FlowDto> = mutableMapOf()
    private var lastFlowPollTime: Long = 0L

    // SCION info
    private var lastSCIONInfo: SCIONInfoDto? = null

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
                updateSCIONInfo()
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
        flowSnapshotCache.clear()
        lastFlowPollTime = 0L
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
        val tunnel = binding?.tunnel ?: return
        val sheet = PathSelectionBottomSheet.newInstance(flow.id).apply {
            this.tunnel = tunnel
        }
        lifecycleScope.launch {
            try {
                val backend = com.wireguard.android.Application.getBackend() as? GoBackend ?: return@launch
                val repo = flowRepository ?: FlowRepository(backend).also { flowRepository = it }
                sheet.flowRepository = repo
            } catch (_: Exception) {
                return@launch
            }
            sheet.show(childFragmentManager, "path_selection")
        }
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
            // Update snapshot cache for rate computation
            for (f in flows) {
                flowSnapshotCache[f.id] = f
            }
            lastFlowPollTime = System.currentTimeMillis()
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

        val now = System.currentTimeMillis()
        val dt = if (lastFlowPollTime > 0) (now - lastFlowPollTime) / 1000.0 else 1.0

        for (flow in filtered) {
            val prev = flowSnapshotCache[flow.id]
            val rates: FlowRates
            if (dt > 0 && prev != null) {
                rates = FlowRates(
                    txBytesPerSec = clampDelta(flow.txBytes, prev.txBytes) / dt,
                    rxBytesPerSec = clampDelta(flow.rxBytes, prev.rxBytes) / dt,
                    txPktsPerSec = clampDelta(flow.txPackets, prev.txPackets) / dt,
                    rxPktsPerSec = clampDelta(flow.rxPackets, prev.rxPackets) / dt,
                )
            } else {
                rates = FlowRates(0.0, 0.0, 0.0, 0.0)
            }
            container.addView(createFlowRowView(flow, rates))
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

    private fun createFlowRowView(flow: FlowDto, rates: FlowRates): View {
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

        if (flow.egressKindEnum == FlowEgressKind.SCION) {
            buildSCIONRow(row, ctx, flow, rates)
        } else {
            buildIPRow(row, ctx, flow)
        }

        return row
    }

    private fun buildSCIONRow(row: LinearLayout, ctx: android.content.Context, flow: FlowDto, rates: FlowRates) {
        // SCION badge
        val badge = TextView(ctx).apply {
            text = "SCION"
            textSize = 8f
            setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            setBackgroundResource(R.drawable.scitra_status_badge_bg)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 4 }
        }
        row.addView(badge)

        // Protocol label (no IP version)
        val protoView = TextView(ctx).apply {
            text = formatProtocolShort(flow.protocol)
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_primary))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
        }
        row.addView(protoView)

        // Destination: dstIA if available, otherwise endpointB
        val dstLabel = if (!flow.dstIA.isNullOrEmpty()) flow.dstIA else formatEndpoint(flow.endpointB)
        val epView = TextView(ctx).apply {
            text = dstLabel
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(epView)

        // TX rate
        val txText = "TX ${formatBitRate(rates.txBytesPerSec)}·${formatPktRate(rates.txPktsPerSec)}"
        val txView = TextView(ctx).apply {
            text = txText
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface_variant))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 6; marginEnd = 4 }
        }
        row.addView(txView)

        // RX rate
        val rxText = "RX ${formatBitRate(rates.rxBytesPerSec)}·${formatPktRate(rates.rxPktsPerSec)}"
        val rxView = TextView(ctx).apply {
            text = rxText
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface_variant))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
        }
        row.addView(rxView)

        // State badge
        val stateLabel = if (flow.txBytes > 0 && flow.rxBytes > 0) "ESTABLISHED" else "INITIAL"
        val stateView = TextView(ctx).apply {
            text = stateLabel
            setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(stateView)

        // Clickable
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onFlowItemClicked(flow) }

        // Accessibility
        val desc = "SCION ${formatProtocolShort(flow.protocol)}, destination $dstLabel, $stateLabel, TX ${formatBitRate(rates.txBytesPerSec)} ${formatPktRate(rates.txPktsPerSec)}, RX ${formatBitRate(rates.rxBytesPerSec)} ${formatPktRate(rates.rxPktsPerSec)}. Selectable"
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        row.contentDescription = desc
    }

    private fun buildIPRow(row: LinearLayout, ctx: android.content.Context, flow: FlowDto) {
        // Protocol + IP version
        val proto = "${formatProtocolShort(flow.protocol)}·${formatIPVersionShort(flow.ipVersion)}"
        val protoView = TextView(ctx).apply {
            text = proto
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_primary))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
        }
        row.addView(protoView)

        // Endpoints
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

        // Status
        addGap(row, ctx, 6)

        val statusView = TextView(ctx).apply {
            text = flow.status.uppercase()
            setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
        }
        row.addView(statusView)

        // TX cumulative
        val txView = TextView(ctx).apply {
            text = "TX ${formatBytesCompact(flow.txBytes)}·${flow.txPackets}"
            setTextColor(ContextCompat.getColor(ctx, R.color.scitra_on_surface_variant))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 4 }
        }
        row.addView(txView)

        // RX cumulative
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

        // Accessibility
        val epShort = formatEndpoint(flow.endpointA).take(30) + " → " + formatEndpoint(flow.endpointB).take(30)
        val desc = "${formatProtocolShort(flow.protocol)} ${formatIPVersionShort(flow.ipVersion)}, $epShort, ${flow.status}, TX ${flow.txPackets} packets ${flow.txBytes} bytes, RX ${flow.rxPackets} packets ${flow.rxBytes} bytes"
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        row.contentDescription = desc
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

    // ─── Flow Rate Helpers ────────────────────────────────

    private data class FlowRates(
        val txBytesPerSec: Double,
        val rxBytesPerSec: Double,
        val txPktsPerSec: Double,
        val rxPktsPerSec: Double,
    )

    private fun clampDelta(current: Long, prev: Long): Double =
        if (current >= prev) (current - prev).toDouble() else 0.0

    private fun formatBitRate(bytesPerSecond: Double): String = when {
        bytesPerSecond <= 0 -> "0bps"
        bytesPerSecond < 125 -> String.format(Locale.US, "%.0f bps", bytesPerSecond * 8)
        bytesPerSecond < 125_000 -> String.format(Locale.US, "%.0f Kbps", bytesPerSecond * 8 / 1000)
        else -> String.format(Locale.US, "%.1f Mbps", bytesPerSecond * 8 / 1_000_000)
    }

    private fun formatPktRate(pktsPerSecond: Double): String = when {
        pktsPerSecond <= 0 -> "0pps"
        pktsPerSecond < 1000 -> String.format(Locale.US, "%.0fpps", pktsPerSecond)
        else -> String.format(Locale.US, "%.1fkpps", pktsPerSecond / 1000)
    }

    // ─── SCION Info Polling ────────────────────────────────

    private suspend fun updateSCIONInfo() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        if (tunnel.state != Tunnel.State.UP) {
            binding.scionInfoSection.visibility = View.GONE
            return
        }
        if (binding.config?.`interface`?.tunnelMode?.isScion != true) {
            binding.scionInfoSection.visibility = View.GONE
            return
        }

        val backend = resolveBackend() ?: return
        try {
            val raw = withContext(Dispatchers.IO) { backend.getSCIONInfo() }
            val info = parseSCIONInfo(raw)
            lastSCIONInfo = info

            binding.scionInfoSection.visibility = View.VISIBLE
            binding.scionLocalIa.text = info.localIA

            val bindParts = mutableListOf<String>()
            info.localIPv4?.let { bindParts.add(it) }
            info.localIPv6?.let { bindParts.add(it) }
            info.portRange?.let { bindParts.add("ports $it") }
            binding.scionBindAddr.text = bindParts.joinToString(" · ")

            binding.scionBrAddr.text = if (!info.brAddr.isNullOrEmpty()) {
                "BR ${info.brAddr}"
            } else {
                ""
            }

            // Compute flow totals from lastAllFlows
            var totalTx = 0L
            var totalRx = 0L
            for (f in lastAllFlows) {
                totalTx += f.txBytes
                totalRx += f.rxBytes
            }
            binding.scionFlowTotals.text = "TX ${formatBytesCompact(totalTx)} · RX ${formatBytesCompact(totalRx)}"
        } catch (_: Exception) {
            binding.scionInfoSection.visibility = View.GONE
        }
    }

    // ─── SCION Toggle ───────────────────────────────────────

    fun toggleScionMode(view: View, checked: Boolean) {
        val toggleSwitch = view as? ToggleSwitch ?: return
        val tunnel = binding?.tunnel ?: return
        val newMode = if (scion) "SCION" else "IP"
        lifecycleScope.launch {
            try {
                val currentConfig = tunnel.getConfigAsync()
                if (currentConfig.`interface`.tunnelMode.name == newMode) return@launch

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
                Toast.makeText(context, "Switched to $newMode mode", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(context, "Error switching mode: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
