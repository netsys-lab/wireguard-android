/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend

class MockScenarioBottomSheet : BottomSheetDialogFragment() {

    var onScenarioChanged: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.mock_scenario_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val modeGroup = view.findViewById<RadioGroup>(R.id.mode_radio_group)
        val radioLive = view.findViewById<RadioButton>(R.id.radio_live_mode)
        val radioMock = view.findViewById<RadioButton>(R.id.radio_mock_mode)

        val scenariosHeader = view.findViewById<TextView>(R.id.scenarios_header)
        val scenarioGroup = view.findViewById<RadioGroup>(R.id.scenario_radio_group)
        val radioDefault = view.findViewById<RadioButton>(R.id.scenario_default)
        val radioGlobeShowcase = view.findViewById<RadioButton>(R.id.scenario_globe_showcase)
        val radioPolicyFallback = view.findViewById<RadioButton>(R.id.scenario_policy_fallback)
        val radioPending = view.findViewById<RadioButton>(R.id.scenario_pending)
        val radioStaleOverride = view.findViewById<RadioButton>(R.id.scenario_stale_override)
        val radioMultiFlow = view.findViewById<RadioButton>(R.id.scenario_multi_flow)
        val radioHighLatency = view.findViewById<RadioButton>(R.id.scenario_high_latency)
        val radioEmpty = view.findViewById<RadioButton>(R.id.scenario_empty)

        val isMock = GoBackend.isMockMode()
        val currentScenario = GoBackend.getCurrentMockScenario()

        if (isMock) {
            radioMock.isChecked = true
            scenariosHeader.visibility = View.VISIBLE
            scenarioGroup.visibility = View.VISIBLE
        } else {
            radioLive.isChecked = true
            scenariosHeader.visibility = View.GONE
            scenarioGroup.visibility = View.GONE
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
        }

        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }

        view.findViewById<Button>(R.id.btn_apply).setOnClickListener {
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

    companion object {
        const val TAG = "MockScenarioBottomSheet"

        fun newInstance(): MockScenarioBottomSheet {
            return MockScenarioBottomSheet()
        }
    }
}
