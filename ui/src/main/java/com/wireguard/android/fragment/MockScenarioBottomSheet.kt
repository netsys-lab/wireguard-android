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
import android.widget.RadioButton
import android.widget.RadioGroup
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
                (460 * displayMetrics.density).toInt()
            )
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setGravity(Gravity.CENTER)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<ImageView>(R.id.btn_close_dialog)
        val modeGroup = view.findViewById<RadioGroup>(R.id.mode_radio_group)
        val radioLive = view.findViewById<RadioButton>(R.id.radio_live_mode)
        val radioMock = view.findViewById<RadioButton>(R.id.radio_mock_mode)

        val scenariosHeader = view.findViewById<TextView>(R.id.scenarios_header)
        val scenarioGroup = view.findViewById<RadioGroup>(R.id.scenario_radio_group)
        val radioDefault = view.findViewById<RadioButton>(R.id.scenario_default)
        val radioGlobeShowcase = view.findViewById<RadioButton>(R.id.scenario_globe_showcase)
        val radioMultiFlow = view.findViewById<RadioButton>(R.id.scenario_multi_flow)
        val radioPolicyFallback = view.findViewById<RadioButton>(R.id.scenario_policy_fallback)
        val radioHighLatency = view.findViewById<RadioButton>(R.id.scenario_high_latency)
        val radioPending = view.findViewById<RadioButton>(R.id.scenario_pending)
        val radioStaleOverride = view.findViewById<RadioButton>(R.id.scenario_stale_override)
        val radioEmpty = view.findViewById<RadioButton>(R.id.scenario_empty)

        val btnCancel = view.findViewById<Button>(R.id.btn_cancel)
        val btnApply = view.findViewById<Button>(R.id.btn_apply)

        val isMock = GoBackend.isMockMode()
        val currentScenario = GoBackend.getCurrentMockScenario()

        // When opened from switching or detail view, default to mock mode checked
        if (isMock) {
            radioMock.isChecked = true
            scenariosHeader.visibility = View.VISIBLE
            scenarioGroup.visibility = View.VISIBLE
            btnApply.text = "Apply Scenario"
        } else {
            // User initiated switch to Mock traffic
            radioMock.isChecked = true
            scenariosHeader.visibility = View.VISIBLE
            scenarioGroup.visibility = View.VISIBLE
            btnApply.text = "Apply & Switch"
        }

        when (currentScenario) {
            "globe_showcase" -> radioGlobeShowcase.isChecked = true
            "policy_fallback" -> radioPolicyFallback.isChecked = true
            "pending" -> radioPending.isChecked = true
            "stale_override" -> radioStaleOverride.isChecked = true
            "multi_flow" -> radioMultiFlow.isChecked = true
            "high_latency" -> radioHighLatency.isChecked = true
            "empty" -> radioEmpty.isChecked = true
            else -> radioDefault.isChecked = true
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mockSelected = checkedId == R.id.radio_mock_mode
            scenariosHeader.visibility = if (mockSelected) View.VISIBLE else View.GONE
            scenarioGroup.visibility = if (mockSelected) View.VISIBLE else View.GONE
            btnApply.text = if (mockSelected) "Apply & Switch" else "Switch to Live"
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnApply.setOnClickListener {
            val enableMock = radioMock.isChecked
            val scenario = when (scenarioGroup.checkedRadioButtonId) {
                R.id.scenario_globe_showcase -> "globe_showcase"
                R.id.scenario_policy_fallback -> "policy_fallback"
                R.id.scenario_pending -> "pending"
                R.id.scenario_stale_override -> "stale_override"
                R.id.scenario_multi_flow -> "multi_flow"
                R.id.scenario_high_latency -> "high_latency"
                R.id.scenario_empty -> "empty"
                else -> "default"
            }

            GoBackend.setMockMode(enableMock, scenario)
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
