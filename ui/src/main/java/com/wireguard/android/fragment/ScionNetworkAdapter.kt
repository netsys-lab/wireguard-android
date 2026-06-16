/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.R
import com.wireguard.android.model.ScionNetwork

/**
 * RecyclerView adapter for displaying SCION network items.
 */
class ScionNetworkAdapter(
    private val networks: List<ScionNetwork>,
    private val onNetworkClick: (ScionNetwork) -> Unit
) : RecyclerView.Adapter<ScionNetworkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val networkName: TextView = view.findViewById(R.id.network_name)
        val networkIsdAs: TextView = view.findViewById(R.id.network_isd_as)
        val bootstrapUrl: TextView = view.findViewById(R.id.network_bootstrap_url)
        val navigateArrow: ImageView = view.findViewById(R.id.navigate_arrow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scion_network, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val network = networks[position]
        holder.networkName.text = network.name
        holder.networkIsdAs.text = network.isdAs
        holder.bootstrapUrl.text = network.bootstrapUrl
        holder.itemView.setOnClickListener { onNetworkClick(network) }
        holder.navigateArrow.setOnClickListener { onNetworkClick(network) }
    }

    override fun getItemCount(): Int = networks.size
}
