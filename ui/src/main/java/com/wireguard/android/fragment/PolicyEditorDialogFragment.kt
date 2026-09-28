/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.wireguard.android.R
import com.wireguard.android.model.ScitraPolicyEntry
import com.wireguard.android.model.ScitraRequirements
import com.wireguard.android.util.PathPolicyParser
import org.json.JSONArray
import org.json.JSONObject

class PolicyEditorDialogFragment : DialogFragment() {

    private lateinit var etPolicyName: EditText
    private lateinit var badgeDefaultPolicy: View
    private lateinit var spinnerExtends: Spinner
    private lateinit var spinnerFailover: Spinner
    private lateinit var spinnerSelector: Spinner

    private lateinit var etMinMtu: EditText
    private lateinit var etMaxLatency: EditText
    private lateinit var etMinBandwidth: EditText

    private lateinit var layoutAclContainer: LinearLayout
    private lateinit var etSequence: EditText

    private lateinit var layoutOrderingContainer: LinearLayout
    private lateinit var tvOrderingEmpty: TextView

    private var originalPolicyName: String? = null
    private var isDefaultPolicy: Boolean = false
    private var existingPolicies: ArrayList<String> = arrayListOf()

    private val aclRules = mutableListOf<Pair<String, String>>() // Pair("+", "pattern")
    private val activeOrdering = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.PathPolicyDialogTheme)

        arguments?.let {
            originalPolicyName = it.getString(KEY_ORIGINAL_POLICY_NAME)
            isDefaultPolicy = it.getBoolean(KEY_IS_DEFAULT, false) || originalPolicyName == "default"
            existingPolicies = it.getStringArrayList(KEY_EXISTING_POLICIES) ?: arrayListOf()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_policy_editor, container, false)

        etPolicyName = root.findViewById(R.id.et_policy_name)
        badgeDefaultPolicy = root.findViewById(R.id.badge_default_policy)
        spinnerExtends = root.findViewById(R.id.spinner_policy_extends)
        spinnerFailover = root.findViewById(R.id.spinner_policy_failover)
        spinnerSelector = root.findViewById(R.id.spinner_policy_selector)

        etMinMtu = root.findViewById(R.id.et_min_mtu)
        etMaxLatency = root.findViewById(R.id.et_max_latency)
        etMinBandwidth = root.findViewById(R.id.et_min_bandwidth)

        layoutAclContainer = root.findViewById(R.id.layout_acl_container)
        etSequence = root.findViewById(R.id.et_sequence)

        layoutOrderingContainer = root.findViewById(R.id.layout_ordering_container)
        tvOrderingEmpty = root.findViewById(R.id.tv_ordering_empty)

        // Close / Cancel buttons
        root.findViewById<View>(R.id.btn_close_editor).setOnClickListener { dismiss() }
        root.findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }

        // Add ACL button
        root.findViewById<View>(R.id.btn_add_acl).setOnClickListener {
            addAclView("+", "")
        }

        // Add Ordering button
        root.findViewById<View>(R.id.btn_add_ordering).setOnClickListener {
            showAddOrderingDialog()
        }

        // Save button
        root.findViewById<View>(R.id.btn_save_policy).setOnClickListener {
            onSaveClicked()
        }

        // Initialize spinners and data
        setupIdentityFields(root)
        populateDataFromArguments()

        return root
    }

    private fun setupIdentityFields(root: View) {
        val titleView = root.findViewById<TextView>(R.id.tv_policy_title)
        val subtitleView = root.findViewById<TextView>(R.id.tv_policy_subtitle)

        if (isDefaultPolicy) {
            titleView.text = "Default Fallback Policy"
            subtitleView.text = "Configures the baseline policy applied when no traffic matcher matches."
            etPolicyName.setText("default")
            etPolicyName.isEnabled = false
            badgeDefaultPolicy.visibility = View.VISIBLE
        } else if (!originalPolicyName.isNullOrEmpty()) {
            titleView.text = "Edit Policy '$originalPolicyName'"
            subtitleView.text = "Modify path routing rules, constraints, inheritance, and ordering."
            etPolicyName.setText(originalPolicyName)
            badgeDefaultPolicy.visibility = View.GONE
        } else {
            titleView.text = "Create Path Policy"
            subtitleView.text = "Define a named policy rule set to be targeted by traffic matchers."
            badgeDefaultPolicy.visibility = View.GONE
        }

        // Populate Extends Spinner (combobox of preceding policies)
        // SCION spec: Policies may only extend policies that precede them in document order!
        val allowedExtends = mutableListOf("(None)")
        if (originalPolicyName != null) {
            val currIndex = existingPolicies.indexOf(originalPolicyName)
            if (currIndex > 0) {
                for (i in 0 until currIndex) {
                    allowedExtends.add(existingPolicies[i])
                }
            }
        } else {
            // New policy will be appended to the end, so all existing policies precede it
            allowedExtends.addAll(existingPolicies)
        }

        val extendsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, allowedExtends)
        spinnerExtends.adapter = extendsAdapter

        // Populate Failover Spinner (combobox of other existing policies)
        val allowedFailover = mutableListOf("(None)")
        existingPolicies.filter { it != originalPolicyName }.forEach { allowedFailover.add(it) }
        val failoverAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, allowedFailover)
        spinnerFailover.adapter = failoverAdapter

        // Populate Selector Spinner
        val selectorOptions = listOf("(None / Default)", "lowest_rtt", "highest_bandwidth", "random")
        val selectorAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, selectorOptions)
        spinnerSelector.adapter = selectorAdapter
    }

    private fun populateDataFromArguments() {
        val entryJsonStr = arguments?.getString(KEY_POLICY_ENTRY_JSON) ?: return
        try {
            val pObj = JSONObject(entryJsonStr)

            // Extends
            val extendsVal = pObj.optString("extends", "")
            if (extendsVal.isNotEmpty()) {
                val adapter = spinnerExtends.adapter as? ArrayAdapter<String>
                val pos = (0 until (adapter?.count ?: 0)).firstOrNull { adapter?.getItem(it) == extendsVal } ?: 0
                spinnerExtends.setSelection(pos)
            }

            // Failover
            val failoverVal = pObj.optString("failover", "")
            if (failoverVal.isNotEmpty()) {
                val adapter = spinnerFailover.adapter as? ArrayAdapter<String>
                val pos = (0 until (adapter?.count ?: 0)).firstOrNull { adapter?.getItem(it) == failoverVal } ?: 0
                spinnerFailover.setSelection(pos)
            }

            // Selector
            val selectorVal = pObj.optString("selector", "")
            if (selectorVal.isNotEmpty()) {
                val adapter = spinnerSelector.adapter as? ArrayAdapter<String>
                val pos = (0 until (adapter?.count ?: 0)).firstOrNull { adapter?.getItem(it) == selectorVal } ?: 0
                spinnerSelector.setSelection(pos)
            }

            // Requirements
            val reqObj = pObj.optJSONObject("requirements")
            if (reqObj != null) {
                if (reqObj.has("min_mtu")) etMinMtu.setText(reqObj.getInt("min_mtu").toString())
                if (reqObj.has("max_meta_lat")) etMaxLatency.setText(reqObj.getInt("max_meta_lat").toString())
                else if (reqObj.has("max_latency")) etMaxLatency.setText(reqObj.getInt("max_latency").toString())
                if (reqObj.has("min_meta_bw")) etMinBandwidth.setText(reqObj.getLong("min_meta_bw").toString())
                else if (reqObj.has("min_bandwidth")) etMinBandwidth.setText(reqObj.getLong("min_bandwidth").toString())
            }

            // Sequence
            val seq = pObj.optString("sequence", "")
            if (seq.isNotEmpty()) etSequence.setText(seq)

            // ACL
            val aclArr = pObj.optJSONArray("acl")
            if (aclArr != null) {
                for (i in 0 until aclArr.length()) {
                    val rule = aclArr.getString(i).trim()
                    if (rule.startsWith("-")) {
                        addAclView("-", rule.removePrefix("-").trim())
                    } else {
                        addAclView("+", rule.removePrefix("+").trim())
                    }
                }
            }

            // Ordering
            val ordArr = pObj.optJSONArray("ordering")
            activeOrdering.clear()
            if (ordArr != null) {
                for (i in 0 until ordArr.length()) {
                    val key = ordArr.getString(i).trim()
                    if (key.isNotEmpty()) activeOrdering.add(key)
                }
            }
            renderOrderingList()

        } catch (e: Exception) {
            // Keep default empty state
        }
    }

    // =========================================================================
    // ACL Management
    // =========================================================================

    private fun addAclView(type: String, value: String) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.item_acl_entry, layoutAclContainer, false)

        val tvType = view.findViewById<TextView>(R.id.tv_acl_type)
        val etValue = view.findViewById<EditText>(R.id.et_acl_value)
        val btnDelete = view.findViewById<ImageView>(R.id.btn_delete_acl)

        tvType.text = type
        if (type == "+") {
            tvType.setTextColor(requireContext().getColor(R.color.scitra_success))
        } else {
            tvType.setTextColor(requireContext().getColor(R.color.md_theme_dark_error))
        }

        tvType.setOnClickListener {
            if (tvType.text == "+") {
                tvType.text = "-"
                tvType.setTextColor(requireContext().getColor(R.color.md_theme_dark_error))
            } else {
                tvType.text = "+"
                tvType.setTextColor(requireContext().getColor(R.color.scitra_success))
            }
        }

        etValue.setText(value)

        btnDelete.setOnClickListener {
            layoutAclContainer.removeView(view)
        }

        layoutAclContainer.addView(view)
    }

    // =========================================================================
    // Ordering Management (Interactive Reordering!)
    // =========================================================================

    private fun renderOrderingList() {
        layoutOrderingContainer.removeAllViews()

        if (activeOrdering.isEmpty()) {
            tvOrderingEmpty.visibility = View.VISIBLE
            return
        }
        tvOrderingEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(context)
        val count = activeOrdering.size

        for (i in 0 until count) {
            val key = activeOrdering[i]
            val item = inflater.inflate(R.layout.item_policy_ordering_entry, layoutOrderingContainer, false)

            val tvRank = item.findViewById<TextView>(R.id.tv_order_rank)
            val tvTitle = item.findViewById<TextView>(R.id.tv_order_title)
            val tvKey = item.findViewById<TextView>(R.id.tv_order_key)
            val btnUp = item.findViewById<ImageButton>(R.id.btn_order_up)
            val btnDown = item.findViewById<ImageButton>(R.id.btn_order_down)
            val btnDelete = item.findViewById<ImageButton>(R.id.btn_order_delete)

            tvRank.text = "#${i + 1}"
            tvTitle.text = getOrderingLabel(key)
            tvKey.text = key

            // Reorder Up
            if (i == 0) {
                btnUp.alpha = 0.25f
                btnUp.isEnabled = false
            } else {
                btnUp.alpha = 1.0f
                btnUp.isEnabled = true
                val prevIndex = i - 1
                val currIndex = i
                btnUp.setOnClickListener {
                    val temp = activeOrdering[currIndex]
                    activeOrdering[currIndex] = activeOrdering[prevIndex]
                    activeOrdering[prevIndex] = temp
                    renderOrderingList()
                }
            }

            // Reorder Down
            if (i == count - 1) {
                btnDown.alpha = 0.25f
                btnDown.isEnabled = false
            } else {
                btnDown.alpha = 1.0f
                btnDown.isEnabled = true
                val nextIndex = i + 1
                val currIndex = i
                btnDown.setOnClickListener {
                    val temp = activeOrdering[currIndex]
                    activeOrdering[currIndex] = activeOrdering[nextIndex]
                    activeOrdering[nextIndex] = temp
                    renderOrderingList()
                }
            }

            // Delete
            val indexToDelete = i
            btnDelete.setOnClickListener {
                activeOrdering.removeAt(indexToDelete)
                renderOrderingList()
            }

            layoutOrderingContainer.addView(item)
        }
    }

    private fun showAddOrderingDialog() {
        val availableCriteria = listOf(
            "meta_latency_asc" to "Lowest Latency (meta_latency_asc)",
            "hops_asc" to "Fewest Hops (hops_asc)",
            "meta_bandwidth_desc" to "Highest Bandwidth (meta_bandwidth_desc)",
            "random" to "Random Load Balancing (random)",
            "meta_latency_desc" to "Highest Latency (meta_latency_desc)",
            "hops_desc" to "Most Hops (hops_desc)",
            "meta_bandwidth_asc" to "Lowest Bandwidth (meta_bandwidth_asc)",
        ).filter { (key, _) -> !activeOrdering.contains(key) }

        if (availableCriteria.isEmpty()) {
            Toast.makeText(requireContext(), "All sorting criteria have already been added.", Toast.LENGTH_SHORT).show()
            return
        }

        val displayLabels = availableCriteria.map { it.second }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Add Sorting Criterion")
            .setItems(displayLabels) { _, which ->
                val selectedKey = availableCriteria[which].first
                activeOrdering.add(selectedKey)
                renderOrderingList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getOrderingLabel(key: String): String {
        return when (key) {
            "meta_latency_asc" -> "Lowest Latency"
            "meta_latency_desc" -> "Highest Latency"
            "meta_bandwidth_desc" -> "Highest Bandwidth"
            "meta_bandwidth_asc" -> "Lowest Bandwidth"
            "hops_asc" -> "Fewest Hops"
            "hops_desc" -> "Most Hops"
            "random" -> "Random Shuffle"
            else -> key
        }
    }

    // =========================================================================
    // Save & Validation
    // =========================================================================

    private fun onSaveClicked() {
        val name = etPolicyName.text.toString().trim()

        if (name.isEmpty()) {
            etPolicyName.error = "Policy name is required"
            etPolicyName.requestFocus()
            return
        }

        if (!isDefaultPolicy) {
            if (name == "default") {
                etPolicyName.error = "Name 'default' is reserved for the fallback policy"
                etPolicyName.requestFocus()
                return
            }
            if (originalPolicyName != name && existingPolicies.contains(name)) {
                etPolicyName.error = "A policy named '$name' already exists"
                etPolicyName.requestFocus()
                return
            }
        }

        // Extends
        val selectedExtendsItem = spinnerExtends.selectedItem?.toString() ?: ""
        val extendsVal = if (selectedExtendsItem == "(None)") null else selectedExtendsItem.takeIf { it.isNotBlank() }

        // Failover
        val selectedFailoverItem = spinnerFailover.selectedItem?.toString() ?: ""
        val failoverVal = if (selectedFailoverItem == "(None)") null else selectedFailoverItem.takeIf { it.isNotBlank() }

        // Selector
        val selectedSelectorItem = spinnerSelector.selectedItem?.toString() ?: ""
        val selectorVal = if (selectedSelectorItem.startsWith("(None")) null else selectedSelectorItem.takeIf { it.isNotBlank() }

        // Requirements
        val minMtu = etMinMtu.text.toString().trim().toIntOrNull()
        val maxLatency = etMaxLatency.text.toString().trim().toIntOrNull()
        val minBw = etMinBandwidth.text.toString().trim().toLongOrNull()
        val reqs = ScitraRequirements(minMtu = minMtu, maxMetaLat = maxLatency, minMetaBw = minBw)

        // Sequence
        val seq = etSequence.text.toString().trim().takeIf { it.isNotBlank() }

        // ACL
        val aclList = mutableListOf<String>()
        for (i in 0 until layoutAclContainer.childCount) {
            val child = layoutAclContainer.getChildAt(i)
            val type = child.findViewById<TextView>(R.id.tv_acl_type)?.text?.toString() ?: "+"
            val value = child.findViewById<EditText>(R.id.et_acl_value)?.text?.toString()?.trim() ?: ""
            if (value.isNotEmpty()) {
                aclList.add("$type $value")
            } else {
                aclList.add(type)
            }
        }

        val entry = ScitraPolicyEntry(
            extends = extendsVal,
            failover = failoverVal,
            acl = aclList,
            sequence = seq,
            requirements = reqs,
            ordering = activeOrdering.toList(),
            selector = selectorVal,
        )

        // Serialize entry to pass back
        val entryObj = JSONObject()
        entry.extends?.let { entryObj.put("extends", it) }
        entry.failover?.let { entryObj.put("failover", it) }
        if (entry.acl.isNotEmpty()) {
            val arr = JSONArray()
            entry.acl.forEach { arr.put(it) }
            entryObj.put("acl", arr)
        }
        entry.sequence?.let { entryObj.put("sequence", it) }
        if (entry.requirements.hasConstraints) {
            val rObj = JSONObject()
            entry.requirements.minMtu?.let { rObj.put("min_mtu", it) }
            entry.requirements.maxMetaLat?.let { rObj.put("max_meta_lat", it) }
            entry.requirements.minMetaBw?.let { rObj.put("min_meta_bw", it) }
            entryObj.put("requirements", rObj)
        }
        if (entry.ordering.isNotEmpty()) {
            val oArr = JSONArray()
            entry.ordering.forEach { oArr.put(it) }
            entryObj.put("ordering", oArr)
        }
        entry.selector?.let { entryObj.put("selector", it) }

        setFragmentResult(
            REQUEST_KEY_POLICY_ENTRY,
            bundleOf(
                KEY_ORIGINAL_POLICY_NAME to originalPolicyName,
                KEY_POLICY_NAME to name,
                KEY_POLICY_ENTRY_JSON to entryObj.toString(),
                KEY_IS_DEFAULT to isDefaultPolicy,
            )
        )

        dismiss()
    }

    companion object {
        const val TAG = "PolicyEditorDialogFragment"
        const val REQUEST_KEY_POLICY_ENTRY = "request_key_policy_entry"
        const val KEY_ORIGINAL_POLICY_NAME = "key_original_policy_name"
        const val KEY_POLICY_NAME = "key_policy_name"
        const val KEY_POLICY_ENTRY_JSON = "key_policy_entry_json"
        const val KEY_EXISTING_POLICIES = "key_existing_policies"
        const val KEY_IS_DEFAULT = "key_is_default"

        fun newInstance(
            originalPolicyName: String? = null,
            policyEntry: ScitraPolicyEntry? = null,
            existingPolicies: List<String> = emptyList(),
            isDefault: Boolean = false,
        ): PolicyEditorDialogFragment {
            val args = Bundle().apply {
                putString(KEY_ORIGINAL_POLICY_NAME, originalPolicyName)
                putBoolean(KEY_IS_DEFAULT, isDefault)
                putStringArrayList(KEY_EXISTING_POLICIES, ArrayList(existingPolicies))

                if (policyEntry != null) {
                    val entryObj = JSONObject()
                    policyEntry.extends?.let { entryObj.put("extends", it) }
                    policyEntry.failover?.let { entryObj.put("failover", it) }
                    if (policyEntry.acl.isNotEmpty()) {
                        val arr = JSONArray()
                        policyEntry.acl.forEach { arr.put(it) }
                        entryObj.put("acl", arr)
                    }
                    policyEntry.sequence?.let { entryObj.put("sequence", it) }
                    if (policyEntry.requirements.hasConstraints) {
                        val rObj = JSONObject()
                        policyEntry.requirements.minMtu?.let { rObj.put("min_mtu", it) }
                        policyEntry.requirements.maxMetaLat?.let { rObj.put("max_meta_lat", it) }
                        policyEntry.requirements.minMetaBw?.let { rObj.put("min_meta_bw", it) }
                        entryObj.put("requirements", rObj)
                    }
                    if (policyEntry.ordering.isNotEmpty()) {
                        val oArr = JSONArray()
                        policyEntry.ordering.forEach { oArr.put(it) }
                        entryObj.put("ordering", oArr)
                    }
                    policyEntry.selector?.let { entryObj.put("selector", it) }
                    putString(KEY_POLICY_ENTRY_JSON, entryObj.toString())
                }
            }

            return PolicyEditorDialogFragment().apply { arguments = args }
        }
    }
}
