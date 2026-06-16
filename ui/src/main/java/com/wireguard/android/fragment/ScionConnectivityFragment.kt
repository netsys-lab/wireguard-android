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
import com.wireguard.android.databinding.FragmentScionConnectivityBinding

/**
 * Fragment displaying SCION connection status for a selected network.
 */
class ScionConnectivityFragment : Fragment() {

    private var _binding: FragmentScionConnectivityBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScionConnectivityBinding.inflate(inflater, container, false)

        // Populate from arguments
        arguments?.let { args ->
            binding.asName.text = args.getString(ARG_NETWORK_NAME, "Unknown")
            binding.isdAsAddress.text = args.getString(ARG_ISD_AS, "")
            binding.bootstrapUrl.text = args.getString(ARG_BOOTSTRAP_URL, "")
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.disconnectButton.setOnClickListener {
            // Pop back to the network list (or tunnel list if network list was skipped)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_NETWORK_NAME = "network_name"
        const val ARG_ISD_AS = "isd_as"
        const val ARG_BOOTSTRAP_URL = "bootstrap_url"
    }
}