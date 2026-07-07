/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import android.widget.Toast
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelDetailPeerBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fragment that shows details about a specific tunnel.
 */
class TunnelDetailFragment : BaseFragment() {
    private var binding: TunnelDetailFragmentBinding? = null
    private var lastState = Tunnel.State.TOGGLE
    private var timerActive = true
    private var selectedPathChip = PathChip.SHORTEST

    private enum class PathChip { SHORTEST, MOST_RELIABLE, LONGEST }

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
                    updateNetworkInfo(config)
                } catch (_: Throwable) {
                    binding.config = null
                }
            }
        }
        lastState = Tunnel.State.TOGGLE
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

    /**
     * Back button pressed — navigate back to the tunnel list.
     */
    fun onBackPressed(@Suppress("UNUSED_PARAMETER") view: View) {
        activity?.onBackPressedDispatcher?.onBackPressed()
    }

    /**
     * Settings gear pressed — navigate to the tunnel editor.
     */
    fun onEditTunnel(@Suppress("UNUSED_PARAMETER") view: View) {
        val activity = activity ?: return
        val isTwoPaneLayout = activity.findViewById<View?>(R.id.master_detail_wrapper) != null
        parentFragmentManager.commit {
            replace(
                if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container,
                TunnelEditorFragment()
            )
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }
    }

    // ─── Path Selection Chips ───────────────────────────────

    /**
     * Path chip clicked — update selection visual state.
     */
    fun onPathChipClicked(view: View) {
        val binding = binding ?: return
        selectedPathChip = when (view.id) {
            R.id.chip_shortest -> PathChip.SHORTEST
            R.id.chip_most_reliable -> PathChip.MOST_RELIABLE
            R.id.chip_longest -> PathChip.LONGEST
            else -> return
        }
        updatePathChips()
    }

    private fun updatePathChips() {
        val binding = binding ?: return
        val chips = listOf(
            binding.chipShortest to PathChip.SHORTEST,
            binding.chipMostReliable to PathChip.MOST_RELIABLE,
            binding.chipLongest to PathChip.LONGEST
        )
        for ((chip, type) in chips) {
            if (type == selectedPathChip) {
                chip.setBackgroundResource(R.drawable.scitra_chip_selected)
                chip.setTextColor(resources.getColor(R.color.scitra_on_primary, null))
                chip.paint.isFakeBoldText = true
            } else {
                chip.setBackgroundResource(R.drawable.scitra_chip_unselected)
                chip.setTextColor(resources.getColor(R.color.scitra_on_surface_variant, null))
                chip.paint.isFakeBoldText = false
            }
        }
        // Update the "CURRENT PATH:" label
        val pathName = when (selectedPathChip) {
            PathChip.SHORTEST -> "SHORTEST"
            PathChip.MOST_RELIABLE -> "MOST RELIABLE"
            PathChip.LONGEST -> "LONGEST"
        }
        binding.currentPathText.text = getString(R.string.current_path_label) + "  " + pathName
    }

    // ─── Network Info ───────────────────────────────────────

    private fun updateNetworkInfo(config: Config) {
        val binding = binding ?: return
        // Gateway: use the first peer's endpoint if available
        val firstEndpoint = config.peers.firstOrNull()?.endpoint
        if (firstEndpoint != null && firstEndpoint.isPresent) {
            val ep = firstEndpoint.get()
            binding.gatewayText.text = ep.host
            binding.gatewayText.visibility = View.VISIBLE
        } else {
            binding.gatewayText.text = "—"
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
        try {
            val statistics = tunnel.getStatisticsAsync()
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                val publicKey = peer.item!!.publicKey
                val peerStats = statistics.peer(publicKey)
                if (peerStats == null || (peerStats.rxBytes == 0L && peerStats.txBytes == 0L)) {
                    peer.transferLabel.visibility = View.GONE
                    peer.transferText.visibility = View.GONE
                } else {
                    peer.transferText.text = getString(
                        R.string.transfer_rx_tx,
                        QuantityFormatter.formatBytes(peerStats.rxBytes),
                        QuantityFormatter.formatBytes(peerStats.txBytes)
                    )
                    peer.transferLabel.visibility = View.VISIBLE
                    peer.transferText.visibility = View.VISIBLE
                }
                if (peerStats == null || peerStats.latestHandshakeEpochMillis == 0L) {
                    peer.latestHandshakeLabel.visibility = View.GONE
                    peer.latestHandshakeText.visibility = View.GONE
                } else {
                    peer.latestHandshakeText.text = QuantityFormatter.formatEpochAgo(peerStats.latestHandshakeEpochMillis)
                    peer.latestHandshakeLabel.visibility = View.VISIBLE
                    peer.latestHandshakeText.visibility = View.VISIBLE
                }
            }
        } catch (e: Throwable) {
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                peer.transferLabel.visibility = View.GONE
                peer.transferText.visibility = View.GONE
                peer.latestHandshakeLabel.visibility = View.GONE
                peer.latestHandshakeText.visibility = View.GONE
            }
        }
    }

    fun onRequestConfigurePathPolicy(view: View?) {
        val pathPolicyJson = binding?.config?.`interface`?.pathPolicy ?: ""
        val dialog = PathPolicyDialogFragment.newInstance(pathPolicyJson)
        childFragmentManager.setFragmentResultListener(PathPolicyDialogFragment.REQUEST_KEY_POLICY, viewLifecycleOwner) { _, bundle ->
            val resultJson = bundle.getString(PathPolicyDialogFragment.KEY_RESULT_JSON)
            if (resultJson != null) {
                savePathPolicy(resultJson)
            }
        }
        dialog.show(childFragmentManager, null)
    }

    private fun savePathPolicy(newJson: String) {
        val tunnel = binding?.tunnel ?: return
        lifecycleScope.launch {
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
                
                currentConfig.`interface`.listenPort.ifPresent { newInterfaceBuilder.setListenPort(it) }
                currentConfig.`interface`.mtu.ifPresent { newInterfaceBuilder.setMtu(it) }

                val newConfig = Config.Builder()
                    .setInterface(newInterfaceBuilder.build())
                    .addPeers(currentConfig.peers)
                    .build()

                tunnel.setConfigAsync(newConfig)
                binding?.config = newConfig
                Toast.makeText(context, "Path Policy updated successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(context, "Error saving Path Policy: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
