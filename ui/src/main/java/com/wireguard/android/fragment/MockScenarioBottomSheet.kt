/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.fragment

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend

class MockScenarioDialogFragment : DialogFragment() {

    var onScenarioChanged: (() -> Unit)? = null
    var onDismissCallback: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.mock_scenario_dialog, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val targetWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(
                (480 * displayMetrics.density).toInt()
            )
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setGravity(Gravity.CENTER)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<ImageView>(R.id.btn_close_dialog)
        val tvStatus = view.findViewById<TextView>(R.id.tv_current_status)
        val btnCancel = view.findViewById<Button>(R.id.btn_cancel)
        val btnApply = view.findViewById<Button>(R.id.btn_apply)

        val isMock = GoBackend.isMockMode()

        if (isMock) {
            tvStatus.text = "Current mode: 🟣 Simulated Mock Traffic Active"
            btnApply.text = "Switch to Live Traffic"
        } else {
            tvStatus.text = "Current mode: 🟢 Live Network Traffic Active"
            btnApply.text = "Start Simulation"
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnApply.setOnClickListener {
            val newMockState = !isMock
            GoBackend.setMockMode(newMockState, "default")
            onScenarioChanged?.invoke()
            dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    companion object {
        const val TAG = "MockScenarioDialogFragment"

        fun newInstance(): MockScenarioDialogFragment {
            return MockScenarioDialogFragment()
        }
    }
}

/** Backward compatibility alias */
typealias MockScenarioBottomSheet = MockScenarioDialogFragment
