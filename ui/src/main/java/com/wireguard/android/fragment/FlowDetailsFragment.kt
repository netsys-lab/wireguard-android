package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentTransaction
import com.wireguard.android.R
import com.wireguard.android.model.FlowDetailsScreenState
import com.wireguard.android.model.FlowDetailsUiModel
import com.wireguard.android.util.MockScenario
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathPolicyState
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.MockFlowPathDataSource
import com.wireguard.android.util.PathPreviewCardBinder

class FlowDetailsFragment : BaseFragment() {

    private var flowDetails: FlowDetailsUiModel? = null
    private var selectedFingerprint: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.flow_details_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scenarioIndex = arguments?.getInt(ARG_SCENARIO, 0) ?: 0
        val scenario = MockScenario.entries.getOrElse(scenarioIndex) { MockScenario.AUTO_SINGLE_PATH }

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        loadFlowDetails(scenario)
    }

    private fun loadFlowDetails(scenario: MockScenario) {
        val details = MockFlowPathDataSource.getFlowDetails(scenario)
        flowDetails = details
        render(details)
    }

    private fun render(details: FlowDetailsUiModel) {
        val view = view ?: return
        val loadingView = view.findViewById<View>(R.id.flow_loading_view)
        val errorView = view.findViewById<View>(R.id.flow_error_view)
        val emptyView = view.findViewById<View>(R.id.flow_empty_view)
        val contentView = view.findViewById<View>(R.id.flow_content_view)

        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        emptyView.visibility = View.GONE
        contentView.visibility = View.GONE

        when (details.screenState) {
            FlowDetailsScreenState.LOADING -> loadingView.visibility = View.VISIBLE
            FlowDetailsScreenState.ERROR -> errorView.visibility = View.VISIBLE
            FlowDetailsScreenState.NO_PATHS -> emptyView.visibility = View.VISIBLE
            FlowDetailsScreenState.ACTIVE -> {
                contentView.visibility = View.VISIBLE
                renderContent(details)
            }
        }
    }

    private fun renderContent(details: FlowDetailsUiModel) {
        val view = view ?: return

        // Flow info
        view.findViewById<TextView>(R.id.flow_title).text =
            getString(R.string.scion_flow_label, details.flowId)
        view.findViewById<TextView>(R.id.flow_status_badge).text =
            getString(R.string.flow_state_active)
        view.findViewById<TextView>(R.id.flow_tx_rate).text = details.txBitRate
        view.findViewById<TextView>(R.id.flow_tx_packets).text = details.txPacketRate
        view.findViewById<TextView>(R.id.flow_rx_rate).text = details.rxBitRate
        view.findViewById<TextView>(R.id.flow_rx_packets).text = details.rxPacketRate
        view.findViewById<TextView>(R.id.flow_protocol).text = details.protocol
        view.findViewById<TextView>(R.id.flow_local_endpoint).text = details.localEndpoint
        view.findViewById<TextView>(R.id.flow_dst_ia).text = details.destinationIA
        view.findViewById<TextView>(R.id.flow_dst_host).text = details.destinationHost
        view.findViewById<TextView>(R.id.flow_last_activity).text = getString(R.string.latest_handshake_ago, "Just now")

        // Policy
        view.findViewById<TextView>(R.id.policy_name).text = details.policy.name
        view.findViewById<TextView>(R.id.policy_description).text = details.policy.description

        val policyBadge = view.findViewById<TextView>(R.id.policy_state_badge)
        val restoreBtn = view.findViewById<TextView>(R.id.btn_restore_automatic)
        when (details.policy.state) {
            PathPolicyState.AUTOMATIC -> {
                policyBadge.text = getString(R.string.recommended)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.GONE
            }
            PathPolicyState.NAMED_POLICY -> {
                policyBadge.text = getString(R.string.flow_state_active)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.GONE
            }
            PathPolicyState.OVERRIDE_ACTIVE -> {
                policyBadge.text = getString(R.string.per_flow_override_active)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.VISIBLE
            }
            PathPolicyState.FALLBACK -> {
                policyBadge.text = getString(R.string.policy_fallback_notice)
                policyBadge.visibility = View.VISIBLE
                restoreBtn.visibility = View.GONE
            }
            PathPolicyState.NONE -> {
                policyBadge.visibility = View.GONE
                restoreBtn.visibility = View.GONE
            }
        }

        // Paths available count
        view.findViewById<TextView>(R.id.paths_available_count).text =
            getString(R.string.paths_available, details.availablePathCount)

        // Current path
        val pathContainer = view.findViewById<LinearLayout>(R.id.current_path_container)
        pathContainer.removeAllViews()
        if (details.currentPath != null) {
            val scenario = getScenario()
            val cardView = PathPreviewCardBinder.inflateDisplay(
                pathContainer,
                details.currentPath,
                LayoutInflater.from(requireContext()),
                requireContext(),
                onDetailsClick = { openPathDetails(it, scenario) },
            )
            pathContainer.addView(cardView)
        }

        // Override path button
        view.findViewById<View>(R.id.btn_override_path).setOnClickListener {
            val scenario = getScenario()
            val fragment = PathOverrideFragment().apply {
                arguments = Bundle().apply {
                    putInt("scenario_index", scenario.ordinal)
                }
                setOnOverrideApplied { updatedDetails, selectedFp ->
                    flowDetails = updatedDetails
                    selectedFingerprint = selectedFp
                    render(updatedDetails)
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(getContainerId(), fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .addToBackStack(null)
                .commit()
        }

        // Configure policy button
        view.findViewById<View>(R.id.btn_configure_policy).setOnClickListener {
            // Delegate to the existing PathPolicyDialogFragment
            val dialog = PathPolicyDialogFragment()
            dialog.show(childFragmentManager, "path_policy")
        }

        // Restore automatic selection
        restoreBtn.setOnClickListener {
            val updated = MockFlowPathDataSource.restoreAutomatic()
            selectedFingerprint = null
            flowDetails = updated
            render(updated)
        }

        // Override notice visibility
        view.findViewById<View>(R.id.override_notice).visibility =
            if (details.overrideActive) View.VISIBLE else View.GONE
    }

    private fun openPathDetails(path: PathPreviewUiModel, scenario: MockScenario) {
        val fragment = PathDetailsFragment.newInstance(path.fingerprint, scenario)
        parentFragmentManager.beginTransaction()
            .replace(getContainerId(), fragment)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .addToBackStack(null)
            .commit()
    }

    private fun getScenario(): MockScenario {
        val index = arguments?.getInt(ARG_SCENARIO, 0) ?: 0
        return MockScenario.entries.getOrElse(index) { MockScenario.AUTO_SINGLE_PATH }
    }

    private fun getContainerId(): Int {
        return if (parentFragmentManager.findFragmentById(R.id.detail_container) != null) {
            R.id.detail_container
        } else {
            R.id.list_detail_container
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        // No-op: Flow details are mock-data driven, not tunnel-bound.
    }

    companion object {
        private const val ARG_SCENARIO = "scenario_index"

        fun newInstance(scenarioIndex: Int = 0): FlowDetailsFragment {
            return FlowDetailsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SCENARIO, scenarioIndex)
                }
            }
        }
    }
}