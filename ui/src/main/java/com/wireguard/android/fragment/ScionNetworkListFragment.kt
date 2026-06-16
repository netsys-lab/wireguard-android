/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import com.wireguard.android.R
import com.wireguard.android.databinding.FragmentScionNetworkListBinding
import com.wireguard.android.model.ScionNetwork

/**
 * Fragment for selecting a SCION Autonomous System network.
 */
class ScionNetworkListFragment : Fragment() {

    private var _binding: FragmentScionNetworkListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScionNetworkListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val networks = ScionNetwork.getSampleNetworks()
        val adapter = ScionNetworkAdapter(networks) { network ->
            navigateToConnection(network)
        }

        binding.networkList.layoutManager = LinearLayoutManager(requireContext())
        binding.networkList.adapter = adapter

        binding.backButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun navigateToConnection(network: ScionNetwork) {
        val fragment = ScionConnectivityFragment().apply {
            arguments = Bundle().apply {
                putString(ScionConnectivityFragment.ARG_NETWORK_NAME, network.name)
                putString(ScionConnectivityFragment.ARG_ISD_AS, network.isdAs)
                putString(ScionConnectivityFragment.ARG_BOOTSTRAP_URL, network.bootstrapUrl)
            }
        }
        parentFragmentManager.commit {
            replace(R.id.list_detail_container, fragment)
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
