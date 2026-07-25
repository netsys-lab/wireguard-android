package com.wireguard.android.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.R
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathPreviewUiModel

class PathPreviewAdapter(
    private val paths: List<PathPreviewUiModel>,
    private val selectedFingerprint: String? = null,
    private val onItemClick: ((PathPreviewUiModel) -> Unit)? = null,
    private val onDetailsClick: ((PathPreviewUiModel) -> Unit)? = null,
) : RecyclerView.Adapter<PathPreviewAdapter.ViewHolder>() {

    private val inflater: LayoutInflater? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.path_preview_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = paths[position]
        val isSelected = path.fingerprint == selectedFingerprint
        holder.bind(path, isSelected)
    }

    override fun getItemCount(): Int = paths.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context = itemView.context
        private val selectionIndicator: ImageView = itemView.findViewById(R.id.pc_selection_indicator)
        private val badgeRow: LinearLayout = itemView.findViewById(R.id.pc_badge_row)
        private val routeView: TextView = itemView.findViewById(R.id.pc_route)
        private val routeVisual: View = itemView.findViewById(R.id.pc_route_visual)
        private val midLabel: TextView = itemView.findViewById(R.id.pc_route_mid_label)
        private val latencyValue: TextView = itemView.findViewById(R.id.pc_metric_latency_value)
        private val bandwidthValue: TextView = itemView.findViewById(R.id.pc_metric_bandwidth_value)
        private val linksValue: TextView = itemView.findViewById(R.id.pc_metric_links_value)
        private val mtuValue: TextView = itemView.findViewById(R.id.pc_metric_mtu_value)
        private val expiryView: TextView = itemView.findViewById(R.id.pc_expiry)
        private val detailsAction: TextView = itemView.findViewById(R.id.pc_details_action)

        fun bind(model: PathPreviewUiModel, isSelected: Boolean) {
            // Selection indicator
            selectionIndicator.visibility = View.VISIBLE
            selectionIndicator.setImageResource(
                if (isSelected) R.drawable.ic_radio_checked
                else R.drawable.ic_radio_unchecked
            )
            selectionIndicator.setColorFilter(
                if (isSelected) ContextCompat.getColor(context, R.color.scitra_primary)
                else ContextCompat.getColor(context, R.color.scitra_outline)
            )

            // Background
            itemView.setBackgroundResource(
                if (isSelected) R.drawable.scitra_selected_card_bg
                else R.drawable.scitra_flow_item_bg
            )

            // Badges
            badgeRow.removeAllViews()
            for (badge in model.badges) {
                badgeRow.addView(createBadgeChip(badge))
            }

            // Route
            routeView.text = model.compactRoute

            // Route visual
            if (model.interAsLinks > 1 && !model.isIntraAs) {
                routeVisual.visibility = View.VISIBLE
                midLabel.text = context.resources.getQuantityString(
                    R.plurals.n_intermediate_ases,
                    model.interAsLinks - 1,
                    model.interAsLinks - 1,
                )
            } else if (model.isIntraAs) {
                routeVisual.visibility = View.VISIBLE
                midLabel.text = context.getString(R.string.intra_as_path)
            } else {
                routeVisual.visibility = View.GONE
            }

            // Metrics
            latencyValue.text = formatLatency(model.totalLatencyMs)
            bandwidthValue.text = formatBandwidth(model.bottleneckBandwidthBps)
            linksValue.text = model.interAsLinks.toString()
            mtuValue.text = model.mtu.toString()

            // Expiry
            expiryView.text = formatExpiry(model.expirySeconds)

            // Details action
            if (onDetailsClick != null) {
                detailsAction.visibility = View.VISIBLE
                detailsAction.text = context.getString(R.string.flow_endpoint_arrow).let { "Details $it" }
                detailsAction.setOnClickListener { onDetailsClick(model) }
            } else {
                detailsAction.visibility = View.GONE
            }

            // Click
            if (onItemClick != null) {
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener { onItemClick(model) }
            }
        }

        private fun createBadgeChip(badge: PathBadge): View {
            val chip = LayoutInflater.from(context).inflate(R.layout.badge_chip, null) as TextView
            chip.text = context.getString(badge.labelResId)
            chip.setTextColor(ContextCompat.getColor(context, R.color.scitra_on_primary))
            chip.background = ContextCompat.getDrawable(context, R.drawable.scitra_badge_chip_bg)
            return chip
        }

        private fun formatLatency(ms: Long?): String {
            if (ms == null) return context.getString(R.string.latency_unavailable)
            return "$ms ms"
        }

        private fun formatBandwidth(bps: Long?): String {
            if (bps == null) return context.getString(R.string.bandwidth_unavailable)
            return when {
                bps >= 1_000_000_000 -> "${bps / 1_000_000_000} Gbit/s"
                bps >= 1_000_000 -> "${bps / 1_000_000} Mbit/s"
                bps >= 1_000 -> "${bps / 1_000} Kbit/s"
                else -> "$bps bit/s"
            }
        }

        private fun formatExpiry(seconds: Long?): String {
            if (seconds == null) return context.getString(R.string.expires_unknown)
            val minutes = (seconds + 59) / 60
            return context.getString(R.string.expires_in, minutes.toInt())
        }
    }
}