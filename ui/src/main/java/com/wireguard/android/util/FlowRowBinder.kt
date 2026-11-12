package com.wireguard.android.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import com.wireguard.android.model.FlowEgressKind

object FlowRowBinder {

    fun bindFlowRow(
        parent: LinearLayout,
        data: FlowRowData,
        inflater: LayoutInflater,
        context: Context,
        onClick: ((FlowRowData) -> Unit)? = null,
    ): View {
        val row = inflater.inflate(R.layout.flow_row, parent, false) as LinearLayout

        val statusDot = row.findViewById<ImageView>(R.id.flow_row_status_dot)
        val protoView = row.findViewById<TextView>(R.id.flow_row_protocol)
        val scionBadge = row.findViewById<TextView>(R.id.flow_row_scion_badge)
        val dstView = row.findViewById<TextView>(R.id.flow_row_destination)
        val secondaryDstView = row.findViewById<TextView>(R.id.flow_row_secondary_dst)
        val txView = row.findViewById<TextView>(R.id.flow_row_tx)
        val rxView = row.findViewById<TextView>(R.id.flow_row_rx)
        val chevron = row.findViewById<ImageView>(R.id.flow_row_chevron)

        statusDot.setColorFilter(ContextCompat.getColor(context, data.statusDotColorRes))

        val protoLabel = buildProtocolLabel(data)
        protoView.text = protoLabel
        protoView.setTextColor(ContextCompat.getColor(context, R.color.scitra_primary))

        val isSCION = data.flow.egressKindEnum == FlowEgressKind.SCION
        scionBadge.visibility = if (isSCION) View.VISIBLE else View.GONE

        dstView.text = data.displayDestination ?: formatEndpoint(data.flow.endpointA)
        dstView.setTextColor(ContextCompat.getColor(context, R.color.scitra_on_surface))

        if (data.displaySecondary != null) {
            secondaryDstView.text = data.displaySecondary
            secondaryDstView.setTextColor(ContextCompat.getColor(context, R.color.scitra_on_surface_variant))
            secondaryDstView.visibility = View.VISIBLE
        } else {
            secondaryDstView.visibility = View.GONE
        }

        txView.text = FlowRateFormatter.formatDirectionRate(data.rates.txBitsPerSec, data.rates.txPktsPerSec)
        txView.setTextColor(ContextCompat.getColor(context, R.color.scitra_on_surface_variant))

        rxView.text = FlowRateFormatter.formatDirectionRate(data.rates.rxBitsPerSec, data.rates.rxPktsPerSec)
        rxView.setTextColor(ContextCompat.getColor(context, R.color.scitra_on_surface_variant))

        chevron.visibility = if (isSCION) View.VISIBLE else View.INVISIBLE

        if (onClick != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick(data) }
        }

        val statusStr = context.getString(R.string.flow_status_description, data.statusLabel)
        row.contentDescription = context.getString(
            R.string.flow_row_content_description,
            protoLabel,
            data.displayDestination.orEmpty(),
            statusStr,
            data.txRateText,
            data.rxRateText,
        )
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        return row
    }

    private fun buildProtocolLabel(data: FlowRowData): String {
        return if (data.scionBadgeVisible) {
            data.protocolLabel
        } else {
            val ipVer = "v${data.flow.ipVersion}"
            "${data.protocolLabel}·$ipVer"
        }
    }

    private fun formatEndpoint(ep: com.wireguard.android.model.FlowEndpointDto): String {
        val addr = ep.address
        return if (addr.contains(":")) "[$addr]:${ep.port}" else "$addr:${ep.port}"
    }
}
