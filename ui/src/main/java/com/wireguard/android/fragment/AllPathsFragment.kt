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
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.PathPreviewUiModel
import com.wireguard.android.viewmodel.FlowPathViewModel
import com.wireguard.android.util.PathPreviewCardBinder
import kotlinx.coroutines.launch

class AllPathsFragment : BaseFragment() {

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

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
        lifecycleScope.launch {
            vm.pathSection.collect { section ->
                if (section is com.wireguard.android.model.PathSectionState.Ready) {
                    paths = section.paths
                    renderPaths()
                    renderContext(section)
                }
            }
        }

        renderPaths()
        updateConfirmButton()
        view.findViewById<View>(R.id.btn_use_selected_path).setOnClickListener {
            applyOverride()
        }
    }

    private fun renderContext(section: com.wireguard.android.model.PathSectionState.Ready) {
        val view = view ?: return
        val ctx = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java].flowContext.value
        val destination = if (ctx is com.wireguard.android.model.FlowContextState.Ready) {
            "${ctx.destinationIA ?: ""} · ${ctx.remoteEndpoint}"
        } else ""
        view.findViewById<TextView>(R.id.ap_context_destination).text = destination
        view.findViewById<TextView>(R.id.ap_context_policy).text = section.policyName ?: section.policyState.name
        view.findViewById<TextView>(R.id.ap_context_override).text =
            if (section.overrideState != com.wireguard.android.model.OverrideState.INACTIVE)
                getString(R.string.per_flow_override_active)
            else getString(R.string.not_active)
        view.findViewById<TextView>(R.id.ap_path_count).text =
            getString(R.string.unique_effective_paths, paths.size)
    }

    private fun renderPaths() {
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

    private fun onPathClick(path: PathPreviewUiModel) {
        selectedFingerprint = if (selectedFingerprint == path.fingerprint) null else path.fingerprint
        renderPaths()
        updateConfirmButton()
    }

    private fun updateConfirmButton() {
        val btn = view?.findViewById<TextView>(R.id.btn_use_selected_path)
        btn?.isEnabled = selectedFingerprint != null
        btn?.alpha = if (selectedFingerprint != null) 1.0f else 0.5f
    }

    private fun applyOverride() {
        val fingerprint = selectedFingerprint ?: return
        ViewModelProvider(requireActivity())[FlowPathViewModel::class.java].applyOverride(fingerprint)
        activity?.onBackPressedDispatcher?.onBackPressed()
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

    companion object {
        fun newInstance(): AllPathsFragment {
            return AllPathsFragment()
        }
    }
}
