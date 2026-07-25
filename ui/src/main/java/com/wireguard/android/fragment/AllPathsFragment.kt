package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentTransaction
import com.wireguard.android.R
import com.wireguard.android.util.MockScenario
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.MockFlowPathDataSource
import com.wireguard.android.util.PathPreviewCardBinder

class AllPathsFragment : BaseFragment() {

    private var scenario: MockScenario = MockScenario.AUTO_SINGLE_PATH
    private var paths: List<PathPreviewUiModel> = emptyList()
    private var selectedFingerprint: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.all_paths_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: return
        val scenarioIndex = args.getInt(ARG_SCENARIO, 0)
        scenario = MockScenario.entries.getOrElse(scenarioIndex) { MockScenario.AUTO_SINGLE_PATH }

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        loadPaths()
    }

    private fun loadPaths() {
        val view = view ?: return
        paths = MockFlowPathDataSource.getPaths(scenario)

        // Context
        val details = MockFlowPathDataSource.getFlowDetails(scenario)
        val overrideActive = MockFlowPathDataSource.mockOverrideFingerprint != null
        view.findViewById<TextView>(R.id.ap_context_destination).text =
            "${details.destinationIA} · ${details.destinationHost}"
        view.findViewById<TextView>(R.id.ap_context_policy).text = details.policy.name
        view.findViewById<TextView>(R.id.ap_context_override).text =
            if (overrideActive) getString(R.string.per_flow_override_active)
            else getString(R.string.not_active)

        // Path count
        view.findViewById<TextView>(R.id.ap_path_count).text =
            getString(R.string.unique_effective_paths, paths.size)

        // Render paths
        val listContainer = view.findViewById<LinearLayout>(R.id.ap_path_list)
        listContainer.removeAllViews()
        for (path in paths) {
            val isSelected = path.fingerprint == selectedFingerprint
            val card = PathPreviewCardBinder.inflateSelectable(
                listContainer,
                path,
                isSelected,
                LayoutInflater.from(requireContext()),
                requireContext(),
                onItemClick = { onPathClick(it) },
                onDetailsClick = { openPathDetails(it) },
            )
            listContainer.addView(card)
        }

        // Confirm button
        updateConfirmButton()
        view.findViewById<View>(R.id.btn_use_selected_path).setOnClickListener {
            applyOverride()
        }
    }

    private fun onPathClick(path: PathPreviewUiModel) {
        selectedFingerprint = if (selectedFingerprint == path.fingerprint) null else path.fingerprint
        renderSelection()
        updateConfirmButton()
    }

    private fun renderSelection() {
        val view = view ?: return
        val listContainer = view.findViewById<LinearLayout>(R.id.ap_path_list)
        listContainer.removeAllViews()
        for (path in paths) {
            val isSelected = path.fingerprint == selectedFingerprint
            val card = PathPreviewCardBinder.inflateSelectable(
                listContainer,
                path,
                isSelected,
                LayoutInflater.from(requireContext()),
                requireContext(),
                onItemClick = { onPathClick(it) },
                onDetailsClick = { openPathDetails(it) },
            )
            listContainer.addView(card)
        }
    }

    private fun updateConfirmButton() {
        val btn = view?.findViewById<TextView>(R.id.btn_use_selected_path)
        btn?.isEnabled = selectedFingerprint != null
        btn?.alpha = if (selectedFingerprint != null) 1.0f else 0.5f
    }

    private fun applyOverride() {
        val fingerprint = selectedFingerprint ?: return
        // Mock: apply override locally, no JNI call
        MockFlowPathDataSource.applyOverride(fingerprint)
        activity?.onBackPressedDispatcher?.onBackPressed()
    }

    private fun openPathDetails(path: PathPreviewUiModel) {
        val fragment = PathDetailsFragment.newInstance(path.fingerprint, scenario)
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

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        // No-op: All paths view is mock-data driven, not tunnel-bound.
    }

    companion object {
        private const val ARG_SCENARIO = "scenario_index"

        fun newInstance(scenario: MockScenario): AllPathsFragment {
            return AllPathsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SCENARIO, scenario.ordinal)
                }
            }
        }
    }
}