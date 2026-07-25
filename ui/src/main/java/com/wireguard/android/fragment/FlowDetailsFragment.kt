package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentTransaction
import com.wireguard.android.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.model.FlowContextState
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.model.PathSectionState
import com.wireguard.android.model.PolicyState
import com.wireguard.android.util.PathPreviewCardBinder
import com.wireguard.android.util.createFlowPathRepositoryProvider
import com.wireguard.android.viewmodel.FlowPathViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FlowDetailsFragment : BaseFragment() {

    var viewModel: FlowPathViewModel? = null
        private set

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.flow_details_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        val tunnel = selectedTunnel
        val flowId = arguments?.getLong(ARG_FLOW_ID, -1L) ?: -1L
        if (tunnel == null || flowId < 0) {
            showState("error")
            return
        }

        val (flowRepo, flowPathRepo) = runBlocking {
            val provider = createFlowPathRepositoryProvider(requireContext())
            Pair(provider.createFlowRepository(tunnel), provider.createFlowPathRepository(tunnel))
        }
        val factory = FlowPathViewModel.Factory(
            flowRepository = flowRepo,
            flowPathRepository = flowPathRepo,
            tunnel = tunnel,
            flowId = flowId,
            flowSnapshot = null,
        )
        viewModel = ViewModelProvider(requireActivity(), factory).get(FlowPathViewModel::class.java)
        viewModel!!.reinit(tunnel, flowId)

        observeViewModel()
    }

    private fun observeViewModel() {
        val vm = viewModel ?: return
        lifecycleScope.launch {
            vm.flowContext.collect { ctx ->
                when (ctx) {
                    is FlowContextState.Loading -> showState("loading")
                    is FlowContextState.Error -> showState("error")
                    is FlowContextState.Ready -> renderFlowContext(ctx)
                }
            }
        }
        lifecycleScope.launch {
            vm.pathSection.collect { section ->
                when (section) {
                    is PathSectionState.Loading -> showState("loading")
                    is PathSectionState.Pending -> showState("loading")
                    is PathSectionState.Empty -> showState("no_paths")
                    is PathSectionState.Error -> showState("error")
                    is PathSectionState.Ready -> renderPathSection(section)
                }
            }
        }
    }

    private fun showState(state: String) {
        val v = view ?: return
        v.findViewById<View>(R.id.flow_loading_view)?.visibility =
            if (state == "loading") View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.flow_error_view)?.visibility =
            if (state == "error") View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.flow_empty_view)?.visibility =
            if (state == "no_paths") View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.flow_content_view)?.visibility =
            if (state == "active") View.VISIBLE else View.GONE
    }

    private fun renderFlowContext(ctx: FlowContextState.Ready) {
        val v = view ?: return
        showState("active")
        v.findViewById<TextView>(R.id.flow_title).text =
            getString(R.string.scion_flow_label, ctx.flowId)
        v.findViewById<TextView>(R.id.flow_status_badge).text =
            getString(R.string.flow_state_active)
        v.findViewById<TextView>(R.id.flow_tx_rate).text = formatBitRate(ctx.txRateBitsPerSec)
        v.findViewById<TextView>(R.id.flow_tx_packets).text = formatPacketRate(ctx.txRatePacketsPerSec)
        v.findViewById<TextView>(R.id.flow_tx_total).text = getString(R.string.flow_traffic_total,
            formatBytes(ctx.txBytes), ctx.txPackets)
        v.findViewById<TextView>(R.id.flow_rx_rate).text = formatBitRate(ctx.rxRateBitsPerSec)
        v.findViewById<TextView>(R.id.flow_rx_packets).text = formatPacketRate(ctx.rxRatePacketsPerSec)
        v.findViewById<TextView>(R.id.flow_rx_total).text = getString(R.string.flow_traffic_total,
            formatBytes(ctx.rxBytes), ctx.rxPackets)
        v.findViewById<TextView>(R.id.flow_protocol).text = ctx.protocol
        v.findViewById<TextView>(R.id.flow_local_endpoint).text = ctx.localEndpoint
        v.findViewById<TextView>(R.id.flow_dst_ia).text = ctx.destinationIA ?: ""
        v.findViewById<TextView>(R.id.flow_dst_host).text = ctx.remoteEndpoint
        v.findViewById<TextView>(R.id.flow_last_activity).text =
            getString(R.string.latest_handshake_ago, "Just now")
    }

    private fun renderPathSection(section: PathSectionState.Ready) {
        val v = view ?: return
        showState("active")

        v.findViewById<TextView>(R.id.policy_name).text = section.policyName ?: section.policyState.name
        v.findViewById<TextView>(R.id.policy_description).text = policyDescription(section)

        val policyBadge = v.findViewById<TextView>(R.id.policy_state_badge)
        val restoreBtn = v.findViewById<TextView>(R.id.btn_restore_automatic)
        when (section.policyState) {
            PolicyState.NONE, PolicyState.UNKNOWN -> {
                policyBadge.visibility = View.GONE
                restoreBtn.visibility = View.GONE
            }
            PolicyState.DEFAULT, PolicyState.CONFIGURED -> {
                policyBadge.text = getString(R.string.recommended)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.GONE
            }
            PolicyState.FALLBACK -> {
                policyBadge.text = getString(R.string.policy_fallback_notice)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.GONE
            }
        }

        when (section.overrideState) {
            com.wireguard.android.model.OverrideState.ACTIVE -> {
                policyBadge.text = getString(R.string.per_flow_override_active)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.VISIBLE
            }
            com.wireguard.android.model.OverrideState.STALE -> {
                policyBadge.text = "Override unavailable"
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.VISIBLE
            }
            else -> {}
        }

        v.findViewById<TextView>(R.id.paths_available_count).text =
            getString(R.string.paths_available, section.paths.size)

        val pathContainer = v.findViewById<LinearLayout>(R.id.current_path_container)
        pathContainer.removeAllViews()
        if (section.effectivePath != null) {
            val cardView = PathPreviewCardBinder.inflateDisplay(
                pathContainer,
                section.effectivePath,
                LayoutInflater.from(requireContext()),
                requireContext(),
                onDetailsClick = { openPathDetails(it) },
            )
            pathContainer.addView(cardView)
        }

        v.findViewById<View>(R.id.btn_override_path).setOnClickListener {
            val fragment = PathOverrideFragment()
            parentFragmentManager.beginTransaction()
                .replace(getContainerId(), fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .addToBackStack(null)
                .commit()
        }

        v.findViewById<View>(R.id.btn_configure_policy).setOnClickListener {
            val dialog = com.wireguard.android.fragment.PathPolicyDialogFragment()
            dialog.show(childFragmentManager, "path_policy")
        }

        restoreBtn.setOnClickListener {
            viewModel?.restoreOverrideFlow()
        }

        v.findViewById<View>(R.id.override_notice).visibility =
            if (section.overrideState != com.wireguard.android.model.OverrideState.INACTIVE) View.VISIBLE else View.GONE
    }

    private fun openPathDetails(path: PathPreviewUiModel) {
        val fragment = PathDetailsFragment.newInstance(path.fingerprint)
        parentFragmentManager.beginTransaction()
            .replace(getContainerId(), fragment)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .addToBackStack(null)
            .commit()
    }

    private fun getContainerId(): Int {
        return if (parentFragmentManager.findFragmentById(R.id.detail_container) != null) {
            R.id.detail_container
        } else {
            R.id.list_detail_container
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {}

    private fun formatBitRate(bps: Long): String = when {
        bps >= 1_000_000_000 -> "${bps / 1_000_000_000}.${(bps % 1_000_000_000) / 100_000_000} Gbit/s"
        bps >= 1_000_000 -> "${bps / 1_000_000}.${(bps % 1_000_000) / 100_000} Mbit/s"
        bps >= 1_000 -> "${bps / 1_000} Kbit/s"
        else -> "$bps bit/s"
    }

    private fun formatPacketRate(pps: Long): String = "$pps pkt/s"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun policyDescription(section: PathSectionState.Ready): String = when (section.overrideState) {
        com.wireguard.android.model.OverrideState.ACTIVE ->
            "Per-flow override active. The configured automatic policy remains available and can be restored at any time."
        com.wireguard.android.model.OverrideState.STALE ->
            "Override path is no longer available. Using automatic fallback."
        else -> when (section.policyState) {
            PolicyState.FALLBACK -> "Policy engine is in fallback mode. Some automatic selection features may be unavailable."
            PolicyState.CONFIGURED -> "Using the configured policy to automatically select the best path for this flow."
            PolicyState.DEFAULT -> "Automatically choosing the best path. No per-flow override is active."
            else -> ""
        }
    }

    companion object {
        const val ARG_FLOW_ID = "flow_id"

        fun newInstance(flowId: Long): FlowDetailsFragment {
            return FlowDetailsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_FLOW_ID, flowId)
                }
            }
        }
    }
}
