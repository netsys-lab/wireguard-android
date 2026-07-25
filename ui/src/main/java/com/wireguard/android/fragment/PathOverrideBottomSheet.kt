package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.viewmodel.FlowPathViewModel
import com.wireguard.android.util.CandidateSelector
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.util.PathPreviewCardBinder
import kotlinx.coroutines.launch

class PathOverrideFragment : BaseFragment() {

    private var selectedFingerprint: String? = null
    private var candidates: List<PathPreviewUiModel> = emptyList()
    private var allPaths: List<PathPreviewUiModel> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val v = inflater.inflate(R.layout.path_override_fragment, container, false)
        v.findViewById<View>(R.id.btn_back).setOnClickListener { navigateBack() }
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
        lifecycleScope.launch {
            vm.pathSection.collect { section ->
                if (section is com.wireguard.android.model.PathSectionState.Ready) {
                    allPaths = section.paths
                    candidates = CandidateSelector.quickCandidates(section.paths)
                    renderCandidates()
                    renderViewAll()
                    renderContext()
                }
            }
        }

        renderContext()
        renderCandidates()
        renderViewAll()
        updateConfirmButton()

        view.findViewById<View>(R.id.btn_use_selected_path).setOnClickListener {
            applyOverride()
        }
        view.findViewById<View>(R.id.btn_cancel_override).setOnClickListener { navigateBack() }
    }

    private fun renderContext() {
        val view = view ?: return
        val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
        val ctx = vm.flowContext.value
        val section = vm.pathSection.value

        val dest = if (ctx is com.wireguard.android.model.FlowContextState.Ready) {
            "${ctx.destinationIA ?: ""} · ${ctx.remoteEndpoint}"
        } else ""

        val policy = if (section is com.wireguard.android.model.PathSectionState.Ready) {
            section.policyName ?: section.policyState.name
        } else ""

        view.findViewById<TextView>(R.id.ob_context_destination).text = dest
        view.findViewById<TextView>(R.id.ob_context_policy).text = policy
    }

    private fun renderCandidates() {
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

    private fun renderViewAll() {
        val view = view ?: return
        val viewAllBtn = view.findViewById<TextView>(R.id.btn_view_all_paths)
        if (allPaths.size > candidates.size) {
            viewAllBtn.visibility = View.VISIBLE
            viewAllBtn.text = getString(R.string.view_all_n_paths, allPaths.size)
            viewAllBtn.setOnClickListener { openAllPaths() }
        } else {
            viewAllBtn.visibility = View.GONE
        }
    }

    private fun onCandidateClick(path: PathPreviewUiModel) {
        selectedFingerprint = if (selectedFingerprint == path.fingerprint) null else path.fingerprint
        renderCandidates()
        updateConfirmButton()
    }

    private fun updateConfirmButton() {
        val btn = view?.findViewById<TextView>(R.id.btn_use_selected_path)
        btn?.isEnabled = selectedFingerprint != null
        btn?.alpha = if (selectedFingerprint != null) 1.0f else 0.5f
    }

    private fun applyOverride() {
        val fingerprint = selectedFingerprint ?: return
        val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
        vm.applyOverride(fingerprint)
        navigateBack()
    }

    private fun openPathDetails(path: PathPreviewUiModel) {
        val fragment = PathDetailsFragment.newInstance(path.fingerprint)
        parentFragmentManager.beginTransaction()
            ?.replace(getContainerId(), fragment)
            ?.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            ?.addToBackStack(null)
            ?.commit()
    }

    private fun openAllPaths() {
        val fragment = AllPathsFragment.newInstance()
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

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {}

    companion object {
        fun newInstance(): PathOverrideFragment {
            return PathOverrideFragment()
        }
    }
}
