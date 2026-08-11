/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wireguard.android.R
import com.wireguard.android.model.ScionIaPairSnapshotDto
import com.wireguard.android.model.ScionPathCacheUiState
import com.wireguard.android.model.ScionPathSnapshotItemDto
import com.wireguard.android.viewmodel.ScionPathSnapshotViewModel
import kotlinx.coroutines.launch

class ScionPathSnapshotDialogFragment : DialogFragment() {

    var viewModel: ScionPathSnapshotViewModel? = null

    private lateinit var containerEmpty: LinearLayout
    private lateinit var containerContent: LinearLayout
    private lateinit var tvStrategy: TextView
    private lateinit var tvLastRefresh: TextView
    private lateinit var tvAvailablePaths: TextView
    private lateinit var spinnerIaPairs: Spinner
    private lateinit var spinnerPaths: Spinner
    private lateinit var tvFingerprint: TextView
    private lateinit var tvNextHop: TextView
    private lateinit var tvExpiry: TextView
    private lateinit var tvMtu: TextView
    private lateinit var tvHops: TextView

    private var currentPairs: List<ScionIaPairSnapshotDto> = emptyList()
    private var currentPaths: List<ScionPathSnapshotItemDto> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.scion_path_snapshot_dialog, container, false)

        containerEmpty = view.findViewById(R.id.container_empty)
        containerContent = view.findViewById(R.id.container_content)
        tvStrategy = view.findViewById(R.id.tv_strategy)
        tvLastRefresh = view.findViewById(R.id.tv_last_refresh)
        tvAvailablePaths = view.findViewById(R.id.tv_available_paths)
        spinnerIaPairs = view.findViewById(R.id.spinner_ia_pairs)
        spinnerPaths = view.findViewById(R.id.spinner_paths)
        tvFingerprint = view.findViewById(R.id.tv_fingerprint)
        tvNextHop = view.findViewById(R.id.tv_next_hop)
        tvExpiry = view.findViewById(R.id.tv_expiry)
        tvMtu = view.findViewById(R.id.tv_mtu)
        tvHops = view.findViewById(R.id.tv_hops)

        setupSpinners()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel?.uiState?.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun setupSpinners() {
        spinnerIaPairs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel?.selectPair(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerPaths.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel?.selectPath(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun renderUiState(state: ScionPathCacheUiState) {
        when (state) {
            is ScionPathCacheUiState.Loading -> {
                containerEmpty.visibility = View.GONE
                containerContent.visibility = View.VISIBLE
                tvStrategy.text = "Global Selection Strategie: Loading..."
            }
            is ScionPathCacheUiState.Empty -> {
                containerEmpty.visibility = View.VISIBLE
                containerContent.visibility = View.GONE
            }
            is ScionPathCacheUiState.Content -> {
                containerEmpty.visibility = View.GONE
                containerContent.visibility = View.VISIBLE

                val snapshot = state.snapshot
                tvStrategy.text = "Global Selection Strategie: ${snapshot.strategy.ifBlank { "Default" }}"
                tvLastRefresh.text = "Last Refresh: ${snapshot.lastRefresh ?: "Just now"}"

                // Update IA Pairs Spinner if changed
                if (currentPairs != snapshot.pairs) {
                    currentPairs = snapshot.pairs
                    val pairLabels = snapshot.pairs.map { it.pairLabel }
                    val pairAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, pairLabels)
                    pairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinnerIaPairs.adapter = pairAdapter
                }
                if (spinnerIaPairs.selectedItemPosition != state.selectedPairIndex && state.selectedPairIndex < currentPairs.size) {
                    spinnerIaPairs.setSelection(state.selectedPairIndex)
                }

                val pair = state.currentPair
                tvAvailablePaths.text = "Available Paths: ${pair?.availablePathsCount ?: 0}"

                val paths = pair?.paths ?: emptyList()
                if (currentPaths != paths) {
                    currentPaths = paths
                    val pathLabels = paths.mapIndexed { idx, p ->
                        val selTag = if (p.isSelected) " [Selected]" else ""
                        "Path $idx$selTag (${p.fingerprint.take(8)})"
                    }
                    val pathAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, pathLabels)
                    pathAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinnerPaths.adapter = pathAdapter
                }
                if (spinnerPaths.selectedItemPosition != state.selectedPathIndex && state.selectedPathIndex < currentPaths.size) {
                    spinnerPaths.setSelection(state.selectedPathIndex)
                }

                val path = state.currentPath
                renderPathDetails(path)
            }
        }
    }

    private fun renderPathDetails(path: ScionPathSnapshotItemDto?) {
        if (path == null) {
            tvFingerprint.text = "Fingerprint: -"
            tvNextHop.text = "Next Hop: -"
            tvExpiry.text = "Expiry: -"
            tvMtu.text = "MTU: -"
            tvHops.text = "Hops:\n  -"
            return
        }

        tvFingerprint.text = "Fingerprint: ${path.fingerprint}"
        tvNextHop.text = "Next Hop: ${path.nextHop ?: "Direct"}"
        tvExpiry.text = "Expiry: ${path.expiry ?: "N/A"}"
        tvMtu.text = "MTU: ${path.mtu ?: "Default"}"

        val hopsStr = if (path.hops.isEmpty()) {
            "  - (Direct)"
        } else {
            path.hops.joinToString("\n") { "  • $it" }
        }
        tvHops.text = "Hops:\n$hopsStr"
    }
}
