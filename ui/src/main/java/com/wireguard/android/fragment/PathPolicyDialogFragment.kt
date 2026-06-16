/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.tabs.TabLayout
import com.wireguard.android.R
import org.json.JSONArray
import org.json.JSONObject

class PathPolicyDialogFragment : DialogFragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var containerMatchers: View
    private lateinit var containerPolicies: View
    private lateinit var matchersList: LinearLayout
    private lateinit var policiesList: LinearLayout

    private var currentJson = DEFAULT_SAMPLE_JSON

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.PathPolicyDialogTheme)
        arguments?.getString(KEY_POLICY_JSON)?.let {
            if (it.isNotEmpty()) {
                currentJson = it
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.path_policy_dialog_fragment, container, false)

        tabLayout = root.findViewById(R.id.tab_layout)
        containerMatchers = root.findViewById(R.id.container_matchers)
        containerPolicies = root.findViewById(R.id.container_policies)
        matchersList = root.findViewById(R.id.matchers_list)
        policiesList = root.findViewById(R.id.policies_list)

        // Setup Tabs
        tabLayout.addTab(tabLayout.newTab().setText("Matchers"))
        tabLayout.addTab(tabLayout.newTab().setText("Policies"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        containerMatchers.visibility = View.VISIBLE
                        containerPolicies.visibility = View.GONE
                    }
                    1 -> {
                        containerMatchers.visibility = View.GONE
                        containerPolicies.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        // Setup Buttons
        root.findViewById<View>(R.id.btn_add_matcher).setOnClickListener {
            addEmptyMatcherCard()
        }

        root.findViewById<View>(R.id.btn_add_policy).setOnClickListener {
            addEmptyPolicyCard()
        }

        root.findViewById<View>(R.id.btn_dialog_cancel).setOnClickListener {
            dismiss()
        }

        root.findViewById<View>(R.id.close_dialog).setOnClickListener {
            dismiss()
        }

        root.findViewById<View>(R.id.btn_dialog_save).setOnClickListener {
            saveAndDismiss()
        }

        // Initialize values
        syncUiFromCurrentJson()

        return root
    }

    private fun syncUiFromCurrentJson() {
        matchersList.removeAllViews()
        policiesList.removeAllViews()

        try {
            val rootObj = JSONObject(currentJson)
            
            // Populate Matchers
            val matchers = rootObj.optJSONArray("matchers")
            if (matchers != null) {
                for (i in 0 until matchers.length()) {
                    val matcher = matchers.getJSONObject(i)
                    addMatcherCard(
                        matcher.optString("source"),
                        matcher.optString("destination"),
                        matcher.optString("protocol"),
                        matcher.optInt("traffic_class", -1),
                        matcher.optString("policy")
                    )
                }
            }

            // Populate Policies
            val policies = rootObj.optJSONObject("policies")
            if (policies != null) {
                val keys = policies.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val policy = policies.getJSONObject(key)
                    
                    // Parse ACL array
                    val aclArr = policy.optJSONArray("acl")
                    val aclRules = mutableListOf<String>()
                    if (aclArr != null) {
                        for (j in 0 until aclArr.length()) {
                            aclRules.add(aclArr.getString(j))
                        }
                    }

                    // Parse Requirements
                    val reqs = policy.optJSONObject("requirements")
                    val minMtu = reqs?.optInt("min_mtu", -1) ?: -1
                    val maxLat = reqs?.optInt("max_meta_lat", -1) ?: -1
                    val minBw = reqs?.optInt("min_meta_bw", -1) ?: -1

                    // Parse Ordering
                    val orderingArr = policy.optJSONArray("ordering")
                    val ordering = mutableListOf<String>()
                    if (orderingArr != null) {
                        for (j in 0 until orderingArr.length()) {
                            ordering.add(orderingArr.getString(j))
                        }
                    }

                    addPolicyCard(
                        key,
                        policy.optString("extends"),
                        policy.optString("failover"),
                        aclRules.joinToString(", "),
                        policy.optString("sequence"),
                        minMtu,
                        maxLat,
                        minBw,
                        ordering.joinToString(", ")
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error reading JSON to UI fields", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncJsonFromCurrentUi() {
        val rootObj = JSONObject()
        val matchersArr = JSONArray()
        val policiesObj = JSONObject()

        // Gather Matchers
        for (i in 0 until matchersList.childCount) {
            val card = matchersList.getChildAt(i)
            val source = card.findViewById<EditText>(R.id.et_matcher_source).text.toString().trim()
            val dest = card.findViewById<EditText>(R.id.et_matcher_destination).text.toString().trim()
            val proto = card.findViewById<EditText>(R.id.et_matcher_protocol).text.toString().trim()
            val tcStr = card.findViewById<EditText>(R.id.et_matcher_traffic_class).text.toString().trim()
            val policyName = card.findViewById<EditText>(R.id.et_matcher_policy).text.toString().trim()

            if (policyName.isEmpty()) continue

            val matcherObj = JSONObject()
            if (source.isNotEmpty()) matcherObj.put("source", source)
            if (dest.isNotEmpty()) matcherObj.put("destination", dest)
            if (proto.isNotEmpty()) matcherObj.put("protocol", proto)
            if (tcStr.isNotEmpty()) matcherObj.put("traffic_class", tcStr.toIntOrNull() ?: 0)
            matcherObj.put("policy", policyName)

            matchersArr.put(matcherObj)
        }
        rootObj.put("matchers", matchersArr)

        // Gather Policies
        for (i in 0 until policiesList.childCount) {
            val card = policiesList.getChildAt(i)
            val name = card.findViewById<EditText>(R.id.et_policy_name).text.toString().trim()
            val ext = card.findViewById<EditText>(R.id.et_policy_extends).text.toString().trim()
            val failover = card.findViewById<EditText>(R.id.et_policy_failover).text.toString().trim()
            val aclStr = card.findViewById<EditText>(R.id.et_policy_acl).text.toString().trim()
            val seq = card.findViewById<EditText>(R.id.et_policy_sequence).text.toString().trim()
            val mtuStr = card.findViewById<EditText>(R.id.et_policy_min_mtu).text.toString().trim()
            val latStr = card.findViewById<EditText>(R.id.et_policy_max_latency).text.toString().trim()
            val bwStr = card.findViewById<EditText>(R.id.et_policy_min_bandwidth).text.toString().trim()
            val orderingStr = card.findViewById<EditText>(R.id.et_policy_ordering).text.toString().trim()

            if (name.isEmpty()) continue

            val policyObj = JSONObject()
            if (ext.isNotEmpty()) policyObj.put("extends", ext)
            if (failover.isNotEmpty()) policyObj.put("failover", failover)
            
            // ACL Array
            if (aclStr.isNotEmpty()) {
                val aclArr = JSONArray()
                aclStr.split(",").forEach {
                    val item = it.trim()
                    if (item.isNotEmpty()) aclArr.put(item)
                }
                policyObj.put("acl", aclArr)
            }

            if (seq.isNotEmpty()) policyObj.put("sequence", seq)

            // Requirements
            val reqsObj = JSONObject()
            var hasReqs = false
            if (mtuStr.isNotEmpty()) {
                reqsObj.put("min_mtu", mtuStr.toIntOrNull() ?: 0)
                hasReqs = true
            }
            if (latStr.isNotEmpty()) {
                reqsObj.put("max_meta_lat", latStr.toIntOrNull() ?: 0)
                hasReqs = true
            }
            if (bwStr.isNotEmpty()) {
                reqsObj.put("min_meta_bw", bwStr.toIntOrNull() ?: 0)
                hasReqs = true
            }
            if (hasReqs) {
                policyObj.put("requirements", reqsObj)
            }

            // Ordering Array
            if (orderingStr.isNotEmpty()) {
                val ordArr = JSONArray()
                orderingStr.split(",").forEach {
                    val item = it.trim()
                    if (item.isNotEmpty()) ordArr.put(item)
                }
                policyObj.put("ordering", ordArr)
            }

            policiesObj.put(name, policyObj)
        }
        rootObj.put("policies", policiesObj)

        currentJson = rootObj.toString(2)
    }

    private fun addEmptyMatcherCard() {
        addMatcherCard("", "", "", -1, "")
    }

    private fun addMatcherCard(src: String, dest: String, proto: String, tc: Int, policy: String) {
        val inflater = LayoutInflater.from(context)
        val card = inflater.inflate(R.layout.item_path_policy_matcher, matchersList, false)
        
        val indexText = card.findViewById<TextView>(R.id.tv_matcher_index)
        indexText.text = "Matcher #${matchersList.childCount + 1}"

        card.findViewById<EditText>(R.id.et_matcher_source).setText(src)
        card.findViewById<EditText>(R.id.et_matcher_destination).setText(dest)
        card.findViewById<EditText>(R.id.et_matcher_protocol).setText(proto)
        if (tc != -1) {
            card.findViewById<EditText>(R.id.et_matcher_traffic_class).setText(tc.toString())
        }
        card.findViewById<EditText>(R.id.et_matcher_policy).setText(policy)

        card.findViewById<ImageView>(R.id.btn_delete_matcher).setOnClickListener {
            matchersList.removeView(card)
            updateMatcherIndexes()
        }

        matchersList.addView(card)
    }

    private fun updateMatcherIndexes() {
        for (i in 0 until matchersList.childCount) {
            val card = matchersList.getChildAt(i)
            card.findViewById<TextView>(R.id.tv_matcher_index).text = "Matcher #${i + 1}"
        }
    }

    private fun addEmptyPolicyCard() {
        addPolicyCard("", "", "", "", "", -1, -1, -1, "")
    }

    private fun addPolicyCard(name: String, ext: String, failover: String, acl: String, seq: String, mtu: Int, lat: Int, bw: Int, ord: String) {
        val inflater = LayoutInflater.from(context)
        val card = inflater.inflate(R.layout.item_path_policy_policy, policiesList, false)

        val headerText = card.findViewById<TextView>(R.id.tv_policy_header)
        headerText.text = if (name.isNotEmpty()) "Policy: $name" else "New Policy"

        val etName = card.findViewById<EditText>(R.id.et_policy_name)
        etName.setText(name)
        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                headerText.text = if (!s.isNullOrEmpty()) "Policy: $s" else "New Policy"
            }
        })

        card.findViewById<EditText>(R.id.et_policy_extends).setText(ext)
        card.findViewById<EditText>(R.id.et_policy_failover).setText(failover)
        card.findViewById<EditText>(R.id.et_policy_acl).setText(acl)
        card.findViewById<EditText>(R.id.et_policy_sequence).setText(seq)
        
        if (mtu != -1) card.findViewById<EditText>(R.id.et_policy_min_mtu).setText(mtu.toString())
        if (lat != -1) card.findViewById<EditText>(R.id.et_policy_max_latency).setText(lat.toString())
        if (bw != -1) card.findViewById<EditText>(R.id.et_policy_min_bandwidth).setText(bw.toString())
        
        card.findViewById<EditText>(R.id.et_policy_ordering).setText(ord)

        card.findViewById<ImageView>(R.id.btn_delete_policy).setOnClickListener {
            policiesList.removeView(card)
        }

        policiesList.addView(card)
    }

    private fun saveAndDismiss() {
        syncJsonFromCurrentUi()

        setFragmentResult(
            REQUEST_KEY_POLICY,
            bundleOf(KEY_RESULT_JSON to currentJson)
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY_POLICY = "request_key_policy"
        const val KEY_RESULT_JSON = "key_result_json"
        const val KEY_POLICY_JSON = "key_policy_json"

        private val DEFAULT_SAMPLE_JSON = """
        {
          "matchers": [
            {
              "source": "1-64512",
              "protocol": "udp",
              "traffic_class": 1,
              "policy": "p1"
            }
          ],
          "policies": {
            "default": {
              "acl": [
                "- 666",
                "+"
              ],
              "ordering": ["random", "hops_asc"]
            },
            "p1": {
              "extends": "default",
              "requirements": {
                "min_mtu": 1420
              },
              "ordering": ["meta_latency_asc"]
            }
          }
        }
        """.trimIndent()

        fun newInstance(currentPolicyJson: String): PathPolicyDialogFragment {
            val extras = Bundle()
            extras.putString(KEY_POLICY_JSON, currentPolicyJson)
            val fragment = PathPolicyDialogFragment()
            fragment.arguments = extras
            return fragment
        }
    }
}
