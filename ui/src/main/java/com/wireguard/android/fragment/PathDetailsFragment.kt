package com.wireguard.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.viewmodel.FlowPathViewModel
import com.wireguard.android.model.HopDetailUiModel
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathDetailsUiModel
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.launch

class PathDetailsFragment : BaseFragment() {

    private var pathDetails: PathDetailsUiModel? = null
    private var fingerprint: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.path_details_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: return
        fingerprint = args.getString(ARG_FINGERPRINT) ?: return

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
        lifecycleScope.launch {
            vm.selectPathDetails(fingerprint!!)
            vm.selectedPathDetails.collect { details ->
                if (details != null) {
                    pathDetails = details
                    render(details)
                }
            }
        }
    }

    private fun render(details: PathDetailsUiModel) {
        val view = view ?: return

        val badgeRow = view.findViewById<LinearLayout>(R.id.pd_badge_row)
        badgeRow.removeAllViews()
        for (badge in details.badges) {
            badgeRow.addView(createBadgeChip(badge))
        }

        view.findViewById<TextView>(R.id.pd_full_route).text = details.fullRoute
        view.findViewById<TextView>(R.id.pd_selection_source).text = details.selectionSource

        view.findViewById<TextView>(R.id.pd_latency).text = formatLatency(details.latencyMs)
        val bwValue = view.findViewById<TextView>(R.id.pd_bandwidth)
        val bwUnit = view.findViewById<TextView>(R.id.pd_bandwidth_unit)
        if (details.bandwidthBps != null) {
            val (value, unit) = formatBandwidthParts(details.bandwidthBps)
            bwValue.text = value
            bwUnit.text = unit
        } else {
            bwValue.text = getString(R.string.bandwidth_unavailable)
            bwUnit.text = ""
        }
        view.findViewById<TextView>(R.id.pd_links).text = details.interAsLinks.toString()
        view.findViewById<TextView>(R.id.pd_mtu).text = details.mtu.toString()

        view.findViewById<TextView>(R.id.pd_geo_summary).text =
            details.geoSummary.ifEmpty { getString(R.string.expires_unknown) }

        view.findViewById<TextView>(R.id.pd_fingerprint).text = details.fingerprint
        view.findViewById<View>(R.id.btn_copy_fingerprint).setOnClickListener {
            copyToClipboard(details.fingerprint)
        }

        val asRouteContainer = view.findViewById<LinearLayout>(R.id.pd_as_route_container)
        asRouteContainer.removeAllViews()
        for (hop in details.hops) {
            asRouteContainer.addView(createAsRouteNode(asRouteContainer, hop))
        }

        val hopList = view.findViewById<LinearLayout>(R.id.pd_hop_list)
        hopList.removeAllViews()
        for (hop in details.hops) {
            hopList.addView(createHopCard(hopList, hop))
        }
    }

    private fun createBadgeChip(badge: PathBadge): View {
        val chip = LayoutInflater.from(requireContext())
            .inflate(R.layout.badge_chip, null) as TextView
        chip.text = getString(badge.labelResId)
        chip.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scitra_on_primary))
        chip.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.scitra_status_badge_bg)
        chip.layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = 4; bottomMargin = 2 }
        return chip
    }

    private fun createAsRouteNode(parent: ViewGroup, hop: HopDetailUiModel): View {
        val node = LayoutInflater.from(requireContext())
            .inflate(R.layout.as_route_node, parent, false) as LinearLayout

        node.findViewById<TextView>(R.id.arn_isa).text = hop.isa
        val metaParts = mutableListOf<String>()
        if (hop.egressInterface != null && hop.hopNumber == 1) {
            metaParts.add("egress interface ${hop.egressInterface}")
        }
        if (hop.ingressInterface != null && hop.egressInterface != null && hop.hopNumber > 1) {
            metaParts.add("ingress ${hop.ingressInterface} · egress ${hop.egressInterface}")
        }
        if (hop.ingressInterface != null && hop.hopNumber == pathDetails?.hops?.size) {
            metaParts.add("ingress interface ${hop.ingressInterface}")
        }
        if (hop.internalHops != null) {
            metaParts.add("${hop.internalHops} internal hops")
        }
        node.findViewById<TextView>(R.id.arn_meta).text = metaParts.joinToString(" · ")
        node.findViewById<TextView>(R.id.arn_role).text = when (hop.role) {
            "Local AS" -> "Local AS"
            "Destination AS" -> "Destination AS"
            else -> "Intermediate AS"
        }
        return node
    }

    private fun createHopCard(parent: ViewGroup, hop: HopDetailUiModel): View {
        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.hop_detail_card, parent, false) as LinearLayout

        card.findViewById<TextView>(R.id.hop_number).text =
            String.format(java.util.Locale.US, "%02d", hop.hopNumber)
        card.findViewById<TextView>(R.id.hop_isa).text = hop.isa
        card.findViewById<TextView>(R.id.hop_role_badge).text = hop.role
        card.findViewById<TextView>(R.id.hop_role_badge).setTextColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.scitra_on_primary)
        )
        card.findViewById<TextView>(R.id.hop_role_badge).background =
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.scitra_status_badge_bg)

        val hopItems = mutableListOf<Pair<String, String>>()

        val role = hop.role
        when {
            role == "Local AS" && hop.egressInterface != null ->
                hopItems.add("Egress interface" to hop.egressInterface.toString())
            role == "Destination AS" && hop.ingressInterface != null ->
                hopItems.add("Ingress interface" to hop.ingressInterface.toString())
            role == "Transit" && hop.ingressInterface != null && hop.egressInterface != null ->
                hopItems.add("Ingress / egress" to "${hop.ingressInterface} → ${hop.egressInterface}")
        }

        if (hop.latencyMs != null) hopItems.add("Advertised latency" to "${hop.latencyMs} ms")
        if (hop.bandwidthBps != null) {
            val (value, unit) = formatBandwidthParts(hop.bandwidthBps)
            hopItems.add("Bandwidth" to "$value $unit")
        }
        if (hop.internalHops != null) hopItems.add("Internal hops" to hop.internalHops.toString())
        if (hop.location != null) hopItems.add("Location" to hop.location)
        if (hop.linkType != null) hopItems.add("Link type" to hop.linkType)

        val grid = card.findViewById<LinearLayout>(R.id.hop_grid)
        for ((label, value) in hopItems) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.hop_grid_row, grid, false)
            row.findViewById<TextView>(R.id.hgr_label).text = label
            row.findViewById<TextView>(R.id.hgr_value).text = value
            grid.addView(row)
        }

        if (grid.childCount == 0) {
            val emptyRow = LayoutInflater.from(requireContext())
                .inflate(R.layout.hop_grid_row, grid, false)
            emptyRow.findViewById<TextView>(R.id.hgr_label).text = "No metadata available"
            emptyRow.findViewById<TextView>(R.id.hgr_value).visibility = View.GONE
            grid.addView(emptyRow)
        }

        val noteView = card.findViewById<TextView>(R.id.hop_note)
        if (hop.hopNumber == 1) {
            noteView.visibility = View.VISIBLE
            noteView.text = "Advertised path metadata from the SCION control plane. No congestion or health state is inferred."
        } else {
            noteView.visibility = View.GONE
        }

        return card
    }

    private fun formatLatency(ms: Long?): String {
        if (ms == null) return getString(R.string.latency_unavailable)
        return ms.toString()
    }

    private fun formatBandwidthParts(bps: Long): Pair<String, String> {
        return when {
            bps >= 1_000_000_000 -> (bps / 1_000_000_000).toString() to "Gbit/s"
            bps >= 1_000_000 -> (bps / 1_000_000).toString() to "Mbit/s"
            bps >= 1_000 -> (bps / 1_000).toString() to "Kbit/s"
            else -> bps.toString() to "bit/s"
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("path_fingerprint", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard, "Fingerprint"), Toast.LENGTH_SHORT).show()
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {}

    companion object {
        private const val ARG_FINGERPRINT = "fingerprint"

        fun newInstance(fingerprint: String): PathDetailsFragment {
            return PathDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FINGERPRINT, fingerprint)
                }
            }
        }
    }
}
