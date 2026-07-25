package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentTransaction
import com.wireguard.android.R
import com.wireguard.android.model.FlowDetailsUiModel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.util.MockFlowPathDataSource
import com.wireguard.android.util.MockScenario
import com.wireguard.android.util.PathPreviewCardBinder

class PathOverrideFragment : BaseFragment() {

    private var scenario: MockScenario = MockScenario.AUTO_SINGLE_PATH
    private var selectedFingerprint: String? = null
    private var candidates: List<PathPreviewUiModel> = emptyList()
    private var allPaths: List<PathPreviewUiModel> = emptyList()
    private var onOverrideApplied: ((FlowDetailsUiModel, String?) -> Unit)? = null
    private var flowDetails: FlowDetailsUiModel? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.path_override_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: return
        val scenarioIndex = args.getInt(ARG_SCENARIO, 0)
        scenario = MockScenario.entries.getOrElse(scenarioIndex) { MockScenario.AUTO_SINGLE_PATH }

        flowDetails = MockFlowPathDataSource.getFlowDetails(scenario)
        allPaths = MockFlowPathDataSource.getPaths(scenario)
        candidates = MockFlowPathDataSource.getQuickCandidates(allPaths)

        // Context
        view.findViewById<TextView>(R.id.ob_context_destination).text =
            "${flowDetails?.destinationIA} · ${flowDetails?.destinationHost}"
        view.findViewById<TextView>(R.id.ob_context_policy).text =
            flowDetails?.policy?.name

        // Render candidates
        val container = view.findViewById<LinearLayout>(R.id.ob_candidates_container)
        container.removeAllViews()
        for (candidate in candidates) {
            val isSelected = candidate.fingerprint == selectedFingerprint
            val card = PathPreviewCardBinder.inflateSelectable(
                container,
                candidate,
                isSelected,
                LayoutInflater.from(requireContext()),
                requireContext(),
                onItemClick = { onCandidateClick(it) },
                onDetailsClick = { openPathDetails(it) },
            )
            container.addView(card)
        }

        // View all paths
        val viewAllBtn = view.findViewById<TextView>(R.id.btn_view_all_paths)
        if (allPaths.size > candidates.size) {
            viewAllBtn.visibility = View.VISIBLE
            viewAllBtn.text = getString(R.string.view_all_n_paths, allPaths.size)
            viewAllBtn.setOnClickListener { openAllPaths() }
        } else {
            viewAllBtn.visibility = View.GONE
        }

        // Use Selected Path
        updateConfirmButton()

        view.findViewById<View>(R.id.btn_use_selected_path).setOnClickListener {
            applyOverride()
        }

        view.findViewById<View>(R.id.btn_close).setOnClickListener { navigateBack() }
        view.findViewById<View>(R.id.btn_cancel_override).setOnClickListener { navigateBack() }
    }

    private fun onCandidateClick(path: PathPreviewUiModel) {
        selectedFingerprint = if (selectedFingerprint == path.fingerprint) null else path.fingerprint
        updateCandidates()
        updateConfirmButton()
    }

    private fun updateCandidates() {
        val view = view ?: return
        val container = view.findViewById<LinearLayout>(R.id.ob_candidates_container)
        container.removeAllViews()
        for (candidate in candidates) {
            val isSelected = candidate.fingerprint == selectedFingerprint
            val card = PathPreviewCardBinder.inflateSelectable(
                container,
                candidate,
                isSelected,
                LayoutInflater.from(requireContext()),
                requireContext(),
                onItemClick = { onCandidateClick(it) },
                onDetailsClick = { openPathDetails(it) },
            )
            container.addView(card)
        }
    }

    private fun updateConfirmButton() {
        val btn = view?.findViewById<TextView>(R.id.btn_use_selected_path)
        btn?.isEnabled = selectedFingerprint != null
        btn?.alpha = if (selectedFingerprint != null) 1.0f else 0.5f
    }

    private fun applyOverride() {
        val fingerprint = selectedFingerprint ?: return
        val updated = MockFlowPathDataSource.applyOverride(fingerprint)
        onOverrideApplied?.invoke(updated, fingerprint)
        navigateBack()
    }

    private fun openPathDetails(path: PathPreviewUiModel) {
        val fragment = PathDetailsFragment.newInstance(path.fingerprint, scenario)
        parentFragmentManager.beginTransaction()
            ?.replace(
                getContainerId(),
                fragment,
            )
            ?.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            ?.addToBackStack(null)
            ?.commit()
    }

    private fun openAllPaths() {
        val fragment = AllPathsFragment.newInstance(scenario)
        parentFragmentManager.beginTransaction()
            ?.replace(getContainerId(), fragment)
            ?.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            ?.addToBackStack(null)
            ?.commit()
    }

    private fun getContainerId(): Int {
        val fm = parentFragmentManager
        return if (fm.findFragmentById(R.id.detail_container) != null) {
            R.id.detail_container
        } else {
            R.id.list_detail_container
        }
    }

    private fun navigateBack() {
        parentFragmentManager.popBackStack()
    }

    fun setOnOverrideApplied(callback: (FlowDetailsUiModel, String?) -> Unit) {
        onOverrideApplied = callback
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        // No-op: mock-data driven
    }

    companion object {
        private const val ARG_SCENARIO = "scenario_index"

        fun newInstance(scenario: MockScenario): PathOverrideFragment {
            return PathOverrideFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SCENARIO, scenario.ordinal)
                }
            }
        }
    }
}