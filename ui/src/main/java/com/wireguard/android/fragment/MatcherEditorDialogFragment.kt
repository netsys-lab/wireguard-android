/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.wireguard.android.R
import com.wireguard.android.model.ScitraMatcher
import org.json.JSONObject

class MatcherEditorDialogFragment : DialogFragment() {

    private lateinit var spinnerPolicy: Spinner
    private lateinit var tvPolicyWarning: TextView

    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppLabel: TextView
    private lateinit var tvAppPackage: TextView
    private lateinit var btnClearApp: ImageButton
    private lateinit var btnChooseApp: View

    private lateinit var chipProtoAll: TextView
    private lateinit var chipProtoTcp: TextView
    private lateinit var chipProtoUdp: TextView

    private lateinit var etDestination: EditText
    private lateinit var etSource: EditText
    private lateinit var etTrafficClass: EditText
    private lateinit var btnSaveMatcher: View

    private var matcherIndex: Int = -1
    private var availablePolicies: ArrayList<String> = arrayListOf()

    private var selectedAppName: String? = null
    private var selectedAppUid: Int? = null
    private var selectedProtocol: String = "" // "" (all), "tcp", "udp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.PathPolicyDialogTheme)

        arguments?.let {
            matcherIndex = it.getInt(KEY_MATCHER_INDEX, -1)
            availablePolicies = it.getStringArrayList(KEY_AVAILABLE_POLICIES) ?: arrayListOf()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_matcher_editor, container, false)

        spinnerPolicy = root.findViewById(R.id.spinner_matcher_policy)
        tvPolicyWarning = root.findViewById(R.id.tv_policy_warning)

        ivAppIcon = root.findViewById(R.id.iv_app_icon)
        tvAppLabel = root.findViewById(R.id.tv_app_label)
        tvAppPackage = root.findViewById(R.id.tv_app_package)
        btnClearApp = root.findViewById(R.id.btn_clear_app)
        btnChooseApp = root.findViewById(R.id.btn_choose_app)

        chipProtoAll = root.findViewById(R.id.chip_proto_all)
        chipProtoTcp = root.findViewById(R.id.chip_proto_tcp)
        chipProtoUdp = root.findViewById(R.id.chip_proto_udp)

        etDestination = root.findViewById(R.id.et_destination)
        etSource = root.findViewById(R.id.et_source)
        etTrafficClass = root.findViewById(R.id.et_traffic_class)
        btnSaveMatcher = root.findViewById(R.id.btn_save_matcher)

        root.findViewById<View>(R.id.btn_close_matcher).setOnClickListener { dismiss() }
        root.findViewById<View>(R.id.btn_cancel_matcher).setOnClickListener { dismiss() }

        // Setup Header Title
        val titleView = root.findViewById<TextView>(R.id.tv_matcher_title)
        val subtitleView = root.findViewById<TextView>(R.id.tv_matcher_subtitle)
        if (matcherIndex >= 0) {
            titleView.text = "Edit Matcher #${matcherIndex + 1}"
            subtitleView.text = "Update flow matching conditions and target policy assignment."
        } else {
            titleView.text = "New Traffic Matcher"
            subtitleView.text = "Create a rule to route specific flows or applications through a selected policy."
        }

        // Setup Target Policy Spinner
        setupPolicySpinner()

        // Setup App Matcher Picker
        setupAppMatcher()

        // Setup Protocol Chips
        setupProtocolChips()

        // Setup Save Button
        btnSaveMatcher.setOnClickListener { onSaveClicked() }

        // Populate Existing Data
        populateExistingData()

        return root
    }

    private fun setupPolicySpinner() {
        val policiesToDisplay = availablePolicies.filter { it != "default" }

        if (policiesToDisplay.isEmpty()) {
            tvPolicyWarning.visibility = View.VISIBLE
            btnSaveMatcher.isEnabled = false
            spinnerPolicy.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                listOf("(No custom policies available)")
            )
        } else {
            tvPolicyWarning.visibility = View.GONE
            btnSaveMatcher.isEnabled = true
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                policiesToDisplay
            )
            spinnerPolicy.adapter = adapter
        }
    }

    private fun setupAppMatcher() {
        btnChooseApp.setOnClickListener {
            val picker = AppPickerDialogFragment.newInstance()
            childFragmentManager.setFragmentResultListener(
                AppPickerDialogFragment.REQUEST_KEY_APP_SELECTED,
                viewLifecycleOwner
            ) { _, bundle ->
                val pkgName = bundle.getString(AppPickerDialogFragment.KEY_PACKAGE_NAME) ?: return@setFragmentResultListener
                val appLabel = bundle.getString(AppPickerDialogFragment.KEY_APP_LABEL) ?: pkgName
                val uid = bundle.getInt(AppPickerDialogFragment.KEY_APP_UID, -1).takeIf { it != -1 }

                updateSelectedAppUi(pkgName, appLabel, uid)
            }
            picker.show(childFragmentManager, AppPickerDialogFragment.TAG)
        }

        btnClearApp.setOnClickListener {
            clearSelectedApp()
        }
    }

    private fun updateSelectedAppUi(pkgName: String, label: String, uid: Int?) {
        selectedAppName = pkgName
        selectedAppUid = uid
        tvAppLabel.text = label
        tvAppPackage.text = pkgName
        btnClearApp.visibility = View.VISIBLE

        try {
            val pm = requireContext().packageManager
            val icon = pm.getApplicationIcon(pkgName)
            ivAppIcon.setImageDrawable(icon)
        } catch (_: Exception) {
            ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }

    private fun clearSelectedApp() {
        selectedAppName = null
        selectedAppUid = null
        tvAppLabel.text = "Any Application"
        tvAppPackage.text = "Matches all flows (no app filter)"
        ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        btnClearApp.visibility = View.GONE
    }

    private fun setupProtocolChips() {
        val ctx = requireContext()

        fun updateChips() {
            when (selectedProtocol.lowercase()) {
                "tcp" -> {
                    chipProtoTcp.setBackgroundResource(R.drawable.scitra_chip_selected)
                    chipProtoTcp.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                    chipProtoUdp.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipProtoUdp.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    chipProtoAll.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipProtoAll.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                }
                "udp" -> {
                    chipProtoUdp.setBackgroundResource(R.drawable.scitra_chip_selected)
                    chipProtoUdp.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                    chipProtoTcp.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipProtoTcp.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    chipProtoAll.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipProtoAll.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                }
                else -> {
                    chipProtoAll.setBackgroundResource(R.drawable.scitra_chip_selected)
                    chipProtoAll.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                    chipProtoTcp.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipProtoTcp.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    chipProtoUdp.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipProtoUdp.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                }
            }
        }

        chipProtoAll.setOnClickListener {
            selectedProtocol = ""
            updateChips()
        }
        chipProtoTcp.setOnClickListener {
            selectedProtocol = "tcp"
            updateChips()
        }
        chipProtoUdp.setOnClickListener {
            selectedProtocol = "udp"
            updateChips()
        }

        updateChips()
    }

    private fun populateExistingData() {
        val jsonStr = arguments?.getString(KEY_MATCHER_JSON) ?: return
        try {
            val obj = JSONObject(jsonStr)

            // Policy selection
            val policyTarget = obj.optString("policy", "")
            val adapter = spinnerPolicy.adapter as? ArrayAdapter<String>
            if (adapter != null && policyTarget.isNotEmpty()) {
                val pos = (0 until adapter.count).firstOrNull { adapter.getItem(it) == policyTarget } ?: 0
                spinnerPolicy.setSelection(pos)
            }

            // App matching
            val appName = obj.optString("app_name", "").takeIf { it.isNotEmpty() }
            val appUid = if (obj.has("app_uid")) obj.getInt("app_uid") else null
            if (appName != null) {
                val label = try {
                    val pm = requireContext().packageManager
                    val info = pm.getApplicationInfo(appName, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    appName
                }
                updateSelectedAppUi(appName, label, appUid)
            }

            // Protocol
            selectedProtocol = obj.optString("protocol", "")
            setupProtocolChips()

            // Source, Destination, DSCP
            val src = obj.optString("source", "")
            if (src.isNotEmpty()) etSource.setText(src)

            val dest = obj.optString("destination", "")
            if (dest.isNotEmpty()) etDestination.setText(dest)

            if (obj.has("traffic_class")) {
                etTrafficClass.setText(obj.getInt("traffic_class").toString())
            }

        } catch (e: Exception) {
            // Keep defaults
        }
    }

    private fun onSaveClicked() {
        val selectedPolicy = spinnerPolicy.selectedItem?.toString()?.trim() ?: ""

        if (selectedPolicy.isEmpty() || selectedPolicy.startsWith("(No custom")) {
            Toast.makeText(requireContext(), "A valid target policy must be selected.", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedPolicy == "default") {
            Toast.makeText(
                requireContext(),
                "Directly targeting 'default' is not allowed. 'default' is applied automatically when no matcher matches.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Validate Traffic Class (DSCP)
        val tcStr = etTrafficClass.text.toString().trim()
        val tc = tcStr.toIntOrNull()
        if (tcStr.isNotEmpty() && (tc == null || tc !in 0..63)) {
            etTrafficClass.error = "Traffic class (DSCP) must be between 0 and 63"
            etTrafficClass.requestFocus()
            return
        }

        val source = etSource.text.toString().trim().takeIf { it.isNotEmpty() }
        val dest = etDestination.text.toString().trim().takeIf { it.isNotEmpty() }
        val proto = selectedProtocol.lowercase().takeIf { it.isNotEmpty() }

        val matcher = ScitraMatcher(
            policy = selectedPolicy,
            source = source,
            destination = dest,
            protocol = proto,
            trafficClass = tc,
            appName = selectedAppName,
            appUid = selectedAppUid,
        )

        // Serialize matcher
        val mObj = JSONObject()
        mObj.put("policy", matcher.policy)
        matcher.source?.let { mObj.put("source", it) }
        matcher.destination?.let { mObj.put("destination", it) }
        matcher.protocol?.let { mObj.put("protocol", it) }
        matcher.trafficClass?.let { mObj.put("traffic_class", it) }
        matcher.appName?.let { mObj.put("app_name", it) }
        matcher.appUid?.let { mObj.put("app_uid", it) }

        setFragmentResult(
            REQUEST_KEY_MATCHER_ENTRY,
            bundleOf(
                KEY_MATCHER_INDEX to matcherIndex,
                KEY_MATCHER_JSON to mObj.toString()
            )
        )

        dismiss()
    }

    companion object {
        const val TAG = "MatcherEditorDialogFragment"
        const val REQUEST_KEY_MATCHER_ENTRY = "request_key_matcher_entry"
        const val KEY_MATCHER_INDEX = "key_matcher_index"
        const val KEY_MATCHER_JSON = "key_matcher_json"
        const val KEY_AVAILABLE_POLICIES = "key_available_policies"

        fun newInstance(
            matcherIndex: Int = -1,
            matcher: ScitraMatcher? = null,
            availablePolicies: List<String> = emptyList(),
        ): MatcherEditorDialogFragment {
            val args = Bundle().apply {
                putInt(KEY_MATCHER_INDEX, matcherIndex)
                putStringArrayList(KEY_AVAILABLE_POLICIES, ArrayList(availablePolicies))

                if (matcher != null) {
                    val mObj = JSONObject()
                    mObj.put("policy", matcher.policy)
                    matcher.source?.let { mObj.put("source", it) }
                    matcher.destination?.let { mObj.put("destination", it) }
                    matcher.protocol?.let { mObj.put("protocol", it) }
                    matcher.trafficClass?.let { mObj.put("traffic_class", it) }
                    matcher.appName?.let { mObj.put("app_name", it) }
                    matcher.appUid?.let { mObj.put("app_uid", it) }
                    putString(KEY_MATCHER_JSON, mObj.toString())
                }
            }

            return MatcherEditorDialogFragment().apply { arguments = args }
        }
    }
}
