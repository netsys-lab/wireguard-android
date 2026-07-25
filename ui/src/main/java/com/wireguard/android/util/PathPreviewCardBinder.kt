package com.wireguard.android.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import com.wireguard.android.model.PathBadge
import com.wireguard.android.model.PathPreviewUiModel

object PathPreviewCardBinder {

    private const val ONE_MINUTE_SECONDS = 60L

    fun inflateDisplay(
        parent: ViewGroup,
        model: PathPreviewUiModel,
        inflater: LayoutInflater,
        context: Context,
        onDetailsClick: ((PathPreviewUiModel) -> Unit)? = null,
    ): View {
        val card = inflater.inflate(R.layout.path_preview_card, parent, false) as LinearLayout
        bindContent(card, model, context, selectable = false)
        bindDetailsAction(card, model, context, onDetailsClick, selectable = false)
        return card
    }

    fun inflateSelectable(
        parent: ViewGroup,
        model: PathPreviewUiModel,
        isSelected: Boolean,
        inflater: LayoutInflater,
        context: Context,
        onItemClick: ((PathPreviewUiModel) -> Unit)? = null,
        onDetailsClick: ((PathPreviewUiModel) -> Unit)? = null,
    ): View {
        val card = inflater.inflate(R.layout.path_preview_card, parent, false) as LinearLayout
        bindContent(card, model, context, selectable = true)
        bindSelection(card, model, isSelected, context, onItemClick)
        bindDetailsAction(card, model, context, onDetailsClick, selectable = true)
        return card
    }

    private fun bindContent(card: View, model: PathPreviewUiModel, context: Context, selectable: Boolean) {
        // Selection indicator
        val selectionIndicator = card.findViewById<ImageView>(R.id.pc_selection_indicator)
        selectionIndicator.visibility = if (selectable) View.VISIBLE else View.GONE

        // Badges
        val badgeRow = card.findViewById<LinearLayout>(R.id.pc_badge_row)
        badgeRow.removeAllViews()
        for (badge in model.badges) {
            badgeRow.addView(createBadgeChip(context, badge))
        }

        // Route
        val routeView = card.findViewById<TextView>(R.id.pc_route)
        routeView.text = model.compactRoute

        // Route visual (show for multi-AS paths with intermediate hops)
        val routeVisual = card.findViewById<View>(R.id.pc_route_visual)
        val midLabel = card.findViewById<TextView>(R.id.pc_route_mid_label)
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
        bindMetric(card, R.id.pc_metric_latency_value, formatLatency(context, model.totalLatencyMs))
        bindMetric(card, R.id.pc_metric_bandwidth_value, formatBandwidth(context, model.bottleneckBandwidthBps))
        bindMetric(card, R.id.pc_metric_links_value, model.interAsLinks.toString())
        bindMetric(card, R.id.pc_metric_mtu_value, model.mtu.toString())

        // Expiry
        val expiryView = card.findViewById<TextView>(R.id.pc_expiry)
        expiryView.text = formatExpiry(context, model.expirySeconds)
    }

    private fun bindSelection(
        card: View,
        model: PathPreviewUiModel,
        isSelected: Boolean,
        context: Context,
        onItemClick: ((PathPreviewUiModel) -> Unit)?,
    ) {
        val indicator = card.findViewById<ImageView>(R.id.pc_selection_indicator)
        indicator.setImageResource(
            if (isSelected) R.drawable.ic_radio_checked
            else R.drawable.ic_radio_unchecked
        )
        indicator.setColorFilter(
            if (isSelected) ContextCompat.getColor(context, R.color.scitra_primary)
            else ContextCompat.getColor(context, R.color.scitra_outline)
        )

        if (isSelected) {
            card.setBackgroundResource(R.drawable.scitra_selected_card_bg)
        } else {
            card.setBackgroundResource(R.drawable.scitra_flow_item_bg)
        }

        if (onItemClick != null) {
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { onItemClick(model) }
        }
    }

    private fun bindDetailsAction(
        card: View,
        model: PathPreviewUiModel,
        context: Context,
        onDetailsClick: ((PathPreviewUiModel) -> Unit)?,
        selectable: Boolean,
    ) {
        val detailsAction = card.findViewById<TextView>(R.id.pc_details_action)
        detailsAction.text = context.getString(R.string.flow_endpoint_arrow).let { "Details $it" }
        if (onDetailsClick != null) {
            detailsAction.isClickable = true
            detailsAction.isFocusable = true
            detailsAction.setOnClickListener { onDetailsClick(model) }
        } else {
            detailsAction.visibility = View.GONE
        }
    }

    private fun createBadgeChip(context: Context, badge: PathBadge): View {
        val chip = LayoutInflater.from(context).inflate(R.layout.badge_chip, null) as TextView
        chip.text = context.getString(badge.labelResId)
        chip.setTextColor(ContextCompat.getColor(context, R.color.scitra_on_primary))
        chip.background = ContextCompat.getDrawable(context, R.drawable.scitra_status_badge_bg)
        return chip
    }

    private fun bindMetric(card: View, viewId: Int, value: String) {
        card.findViewById<TextView>(viewId)?.text = value
    }

    private fun formatLatency(context: Context, ms: Long?): String {
        if (ms == null) return context.getString(R.string.latency_unavailable)
        return "$ms ms"
    }

    private fun formatBandwidth(context: Context, bps: Long?): String {
        if (bps == null) return context.getString(R.string.bandwidth_unavailable)
        return when {
            bps >= 1_000_000_000 -> "${bps / 1_000_000_000} Gbit/s"
            bps >= 1_000_000 -> "${bps / 1_000_000} Mbit/s"
            bps >= 1_000 -> "${bps / 1_000} Kbit/s"
            else -> "$bps bit/s"
        }
    }

    private fun formatExpiry(context: Context, seconds: Long?): String {
        if (seconds == null) return context.getString(R.string.expires_unknown)
        val minutes = (seconds + ONE_MINUTE_SECONDS - 1) / ONE_MINUTE_SECONDS // ceiling
        return context.getString(R.string.expires_in, minutes.toInt())
    }
}