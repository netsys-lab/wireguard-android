/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.wireguard.android.R

/**
 * Bottom sheet dialog for selecting a SCION path for a specific traffic flow.
 * Currently shows mock path options; real path data will be wired in later.
 */
class PathSelectionBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.path_selection_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val flowName = arguments?.getString(ARG_FLOW_NAME) ?: "Unknown"

        // Set subtitle
        view.findViewById<android.widget.TextView>(R.id.path_subtitle)?.text =
            getString(R.string.optimizing_connection, flowName)

        // Handle path option clicks
        view.findViewById<View>(R.id.path_option_1)?.setOnClickListener {
            selectPath("Direct Low-Latency")
        }
        view.findViewById<View>(R.id.path_option_2)?.setOnClickListener {
            selectPath("Relay via Zurich")
        }
        view.findViewById<View>(R.id.path_option_3)?.setOnClickListener {
            selectPath("High Bandwidth Tunnel")
        }

        // Confirm button
        view.findViewById<View>(R.id.btn_confirm_path)?.setOnClickListener {
            Toast.makeText(context, "Path selection will be implemented later", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun selectPath(pathName: String) {
        Toast.makeText(context, "Selected: $pathName", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_FLOW_NAME = "flow_name"

        fun newInstance(flowName: String): PathSelectionBottomSheet {
            return PathSelectionBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_FLOW_NAME, flowName)
                }
            }
        }
    }
}
