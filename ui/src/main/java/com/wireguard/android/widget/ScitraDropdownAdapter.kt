/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import com.wireguard.android.R

data class ScitraDropdownItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val iconRes: Int = R.drawable.ic_policy_shield,
    val badgeText: String? = null,
)

object PolicySemanticInference {
    fun inferIcon(name: String?): Int {
        val n = name?.lowercase()?.trim() ?: ""
        return when {
            n.isEmpty() || n == "(none)" || n == "none" -> R.drawable.ic_close
            n == "default" -> R.drawable.ic_policy_star
            n.contains("game") || n.contains("steam") || n.contains("discord") -> R.drawable.ic_policy_gamepad
            n.contains("video") || n.contains("stream") || n.contains("media") || n.contains("yt") || n.contains("net") -> R.drawable.ic_policy_video
            n.contains("bank") || n.contains("sec") || n.contains("auth") || n.contains("vpn") || n.contains("safe") -> R.drawable.ic_policy_shield
            n.contains("lat") || n.contains("fast") || n.contains("speed") || n.contains("ping") || n.contains("perf") -> R.drawable.ic_policy_zap
            n.contains("path") || n.contains("route") || n.contains("transit") -> R.drawable.ic_globe
            else -> R.drawable.ic_policy_shield
        }
    }

    fun inferSubtitle(name: String?): String {
        val n = name?.lowercase()?.trim() ?: ""
        return when {
            n.isEmpty() || n == "(none)" || n == "none" -> "No policy selected"
            n == "default" -> "Standard fallback policy"
            n.contains("game") -> "Latency optimized • Low jitter"
            n.contains("video") || n.contains("stream") -> "Bandwidth optimized • High throughput"
            n.contains("bank") || n.contains("sec") -> "Strict isolation • ACL filtering"
            n.contains("lat") || n.contains("fast") -> "Latency prioritized routing"
            n == "round_robin" -> "Alternates between available paths"
            n == "lowest_rtt" -> "Selects paths with minimal round-trip time"
            else -> "Custom path policy"
        }
    }
}

class ScitraDropdownAdapter(
    private val context: Context,
    private val items: List<ScitraDropdownItem>,
    private val selectedPositionProvider: () -> Int = { -1 },
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): ScitraDropdownItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_scitra_spinner_selected, parent, false)
        val item = getItem(position)

        val iconIv = view.findViewById<ImageView>(R.id.iv_selected_icon)
        val titleTv = view.findViewById<TextView>(R.id.tv_selected_title)
        val badgeTv = view.findViewById<TextView>(R.id.tv_selected_badge)

        iconIv.setImageResource(item.iconRes)
        titleTv.text = item.title

        if (!item.badgeText.isNullOrEmpty()) {
            badgeTv.text = item.badgeText
            badgeTv.visibility = View.VISIBLE
        } else {
            badgeTv.visibility = View.GONE
        }

        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_scitra_spinner_dropdown, parent, false)
        val item = getItem(position)

        val iconIv = view.findViewById<ImageView>(R.id.iv_dropdown_icon)
        val titleTv = view.findViewById<TextView>(R.id.tv_dropdown_title)
        val subtitleTv = view.findViewById<TextView>(R.id.tv_dropdown_subtitle)
        val checkIv = view.findViewById<ImageView>(R.id.iv_dropdown_check)

        iconIv.setImageResource(item.iconRes)
        titleTv.text = item.title

        if (!item.subtitle.isNullOrEmpty()) {
            subtitleTv.text = item.subtitle
            subtitleTv.visibility = View.VISIBLE
        } else {
            subtitleTv.visibility = View.GONE
        }

        val isSelected = (position == selectedPositionProvider())
        checkIv.visibility = if (isSelected) View.VISIBLE else View.GONE

        return view
    }

    fun attachTo(spinner: Spinner) {
        spinner.adapter = this
        spinner.setBackgroundResource(R.drawable.scitra_select_field_bg)
        spinner.setPopupBackgroundResource(R.drawable.scitra_dropdown_popup_bg)
    }

    companion object {
        fun forPolicies(
            context: Context,
            policyNames: List<String>,
            activePolicyName: String? = null,
            spinner: Spinner? = null,
        ): ScitraDropdownAdapter {
            val items = policyNames.map { name ->
                ScitraDropdownItem(
                    id = name,
                    title = name,
                    subtitle = PolicySemanticInference.inferSubtitle(name),
                    iconRes = PolicySemanticInference.inferIcon(name),
                    badgeText = if (name == activePolicyName) "Active" else null
                )
            }
            val adapter = ScitraDropdownAdapter(
                context = context,
                items = items,
                selectedPositionProvider = { spinner?.selectedItemPosition ?: -1 }
            )
            spinner?.let { adapter.attachTo(it) }
            return adapter
        }

        fun forGenericOptions(
            context: Context,
            options: List<String>,
            subtitles: Map<String, String> = emptyMap(),
            spinner: Spinner? = null,
        ): ScitraDropdownAdapter {
            val items = options.map { opt ->
                ScitraDropdownItem(
                    id = opt,
                    title = opt,
                    subtitle = subtitles[opt] ?: PolicySemanticInference.inferSubtitle(opt),
                    iconRes = PolicySemanticInference.inferIcon(opt)
                )
            }
            val adapter = ScitraDropdownAdapter(
                context = context,
                items = items,
                selectedPositionProvider = { spinner?.selectedItemPosition ?: -1 }
            )
            spinner?.let { adapter.attachTo(it) }
            return adapter
        }
    }
}
