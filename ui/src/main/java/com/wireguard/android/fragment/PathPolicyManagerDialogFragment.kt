/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.wireguard.android.R
import com.wireguard.android.model.ScitraMatcher
import com.wireguard.android.model.ScitraPolicyConfig
import com.wireguard.android.model.ScitraPolicyEntry
import com.wireguard.android.model.ScitraRequirements
import com.wireguard.android.util.PathPolicyParser
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class PathPolicyManagerDialogFragment : DialogFragment() {

    private lateinit var tvDefaultPolicySummary: TextView
    private lateinit var btnEditDefaultPolicy: View

    private lateinit var tvMatchersSectionTitle: TextView
    private lateinit var btnAddMatcher: View
    private lateinit var tvMatchersEmpty: View
    private lateinit var layoutMatchersContainer: LinearLayout

    private lateinit var tvPoliciesSectionTitle: TextView
    private lateinit var btnAddPolicy: View
    private lateinit var tvPoliciesEmpty: View
    private lateinit var layoutPoliciesContainer: LinearLayout

    private var currentConfig = ScitraPolicyConfig()
    private val matchersList = mutableListOf<ScitraMatcher>()
    private val policiesMap = LinkedHashMap<String, ScitraPolicyEntry>()

    // File Import SAF Launcher
    private val fileImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            importPolicyFromUri(uri)
        }
    }

    // File Export SAF Launcher
    private val fileExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            exportPolicyToUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.PathPolicyDialogTheme)

        val initialJson = arguments?.getString(KEY_POLICY_JSON)
        currentConfig = PathPolicyParser.parseConfig(initialJson)

        // Initialize mutable state
        matchersList.clear()
        matchersList.addAll(currentConfig.matchers)
        policiesMap.clear()
        policiesMap.putAll(currentConfig.policies)

        // Ensure default policy entry exists if not yet present
        if (!policiesMap.containsKey("default")) {
            policiesMap["default"] = ScitraPolicyEntry(
                acl = listOf("+"),
                ordering = listOf("meta_latency_asc", "hops_asc")
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_path_policy_manager, container, false)

        tvDefaultPolicySummary = root.findViewById(R.id.tv_default_policy_summary)
        btnEditDefaultPolicy = root.findViewById(R.id.btn_edit_default_policy)

        tvMatchersSectionTitle = root.findViewById(R.id.tv_matchers_section_title)
        btnAddMatcher = root.findViewById(R.id.btn_add_matcher)
        tvMatchersEmpty = root.findViewById(R.id.tv_matchers_empty)
        layoutMatchersContainer = root.findViewById(R.id.layout_matchers_container)

        tvPoliciesSectionTitle = root.findViewById(R.id.tv_policies_section_title)
        btnAddPolicy = root.findViewById(R.id.btn_add_policy)
        tvPoliciesEmpty = root.findViewById(R.id.tv_policies_empty)
        layoutPoliciesContainer = root.findViewById(R.id.layout_policies_container)

        // Close / Cancel / Apply
        root.findViewById<View>(R.id.btn_close_manager).setOnClickListener { dismiss() }
        root.findViewById<View>(R.id.btn_cancel_manager).setOnClickListener { dismiss() }
        root.findViewById<View>(R.id.btn_apply_manager).setOnClickListener { applyAndSave() }

        // Header Actions
        root.findViewById<View>(R.id.btn_view_raw_json).setOnClickListener { showRawJsonDialog() }
        root.findViewById<View>(R.id.btn_import_policy).setOnClickListener { fileImportLauncher.launch("application/json") }
        root.findViewById<View>(R.id.btn_export_policy).setOnClickListener { fileExportLauncher.launch("scitra-policy.json") }

        // Edit Default Policy
        btnEditDefaultPolicy.setOnClickListener {
            val defaultEntry = policiesMap["default"] ?: ScitraPolicyEntry()
            val dialog = PolicyEditorDialogFragment.newInstance(
                originalPolicyName = "default",
                policyEntry = defaultEntry,
                existingPolicies = policiesMap.keys.toList(),
                isDefault = true
            )
            dialog.show(childFragmentManager, PolicyEditorDialogFragment.TAG)
        }

        // Add Matcher Button
        btnAddMatcher.setOnClickListener {
            val customPolicyNames = policiesMap.keys.filter { it != "default" }
            if (customPolicyNames.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Please create at least one custom policy in your library before adding a matcher.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val dialog = MatcherEditorDialogFragment.newInstance(
                matcherIndex = -1,
                matcher = null,
                availablePolicies = customPolicyNames
            )
            dialog.show(childFragmentManager, MatcherEditorDialogFragment.TAG)
        }

        // Add Policy Button
        btnAddPolicy.setOnClickListener {
            val dialog = PolicyEditorDialogFragment.newInstance(
                originalPolicyName = null,
                policyEntry = null,
                existingPolicies = policiesMap.keys.toList(),
                isDefault = false
            )
            dialog.show(childFragmentManager, PolicyEditorDialogFragment.TAG)
        }

        setupFragmentResultListeners()

        renderAll()

        return root
    }

    private fun setupFragmentResultListeners() {
        // Listener for Policy Editor results (edit or add policy)
        childFragmentManager.setFragmentResultListener(
            PolicyEditorDialogFragment.REQUEST_KEY_POLICY_ENTRY,
            viewLifecycleOwner
        ) { _, bundle ->
            val origName = bundle.getString(PolicyEditorDialogFragment.KEY_ORIGINAL_POLICY_NAME)
            val newName = bundle.getString(PolicyEditorDialogFragment.KEY_POLICY_NAME) ?: return@setFragmentResultListener
            val jsonStr = bundle.getString(PolicyEditorDialogFragment.KEY_POLICY_ENTRY_JSON) ?: return@setFragmentResultListener

            val entry = parsePolicyEntryFromJson(jsonStr)

            if (origName != null && origName != newName) {
                // Renamed policy: update map preserving key order, and update matchers pointing to old name!
                val newMap = LinkedHashMap<String, ScitraPolicyEntry>()
                for ((k, v) in policiesMap) {
                    if (k == origName) {
                        newMap[newName] = entry
                    } else {
                        // Also update extends/failover references if they point to the old name
                        val updatedV = v.copy(
                            extends = if (v.extends == origName) newName else v.extends,
                            failover = if (v.failover == origName) newName else v.failover,
                        )
                        newMap[k] = updatedV
                    }
                }
                policiesMap.clear()
                policiesMap.putAll(newMap)

                // Update any matchers pointing to the old name
                for (i in 0 until matchersList.size) {
                    if (matchersList[i].policy == origName) {
                        matchersList[i] = matchersList[i].copy(policy = newName)
                    }
                }
            } else {
                policiesMap[newName] = entry
            }

            renderAll()
        }

        // Listener for Matcher Editor results (edit or add matcher)
        childFragmentManager.setFragmentResultListener(
            MatcherEditorDialogFragment.REQUEST_KEY_MATCHER_ENTRY,
            viewLifecycleOwner
        ) { _, bundle ->
            val index = bundle.getInt(MatcherEditorDialogFragment.KEY_MATCHER_INDEX, -1)
            val jsonStr = bundle.getString(MatcherEditorDialogFragment.KEY_MATCHER_JSON) ?: return@setFragmentResultListener

            val matcher = parseMatcherFromJson(jsonStr)

            if (index in 0 until matchersList.size) {
                matchersList[index] = matcher
            } else {
                matchersList.add(matcher)
            }

            renderMatchersList()
        }
    }

    private fun renderAll() {
        renderDefaultPolicyCard()
        renderMatchersList()
        renderPoliciesLibrary()
    }

    private fun renderDefaultPolicyCard() {
        val defaultEntry = policiesMap["default"]
        if (defaultEntry != null) {
            val parts = mutableListOf<String>()
            if (defaultEntry.ordering.isNotEmpty()) {
                val ordNames = defaultEntry.ordering.map { keyToReadable(it) }
                parts.add("Ordering: ${ordNames.joinToString(", ")}")
            } else {
                parts.add("Ordering: Daemon default")
            }

            if (defaultEntry.requirements.hasConstraints) {
                val reqs = mutableListOf<String>()
                defaultEntry.requirements.minMtu?.let { reqs.add("MTU ≥ $it") }
                defaultEntry.requirements.maxMetaLat?.let { reqs.add("Lat ≤ ${it}ms") }
                defaultEntry.requirements.minMetaBw?.let { reqs.add("BW ≥ $it kbps") }
                parts.add(reqs.joinToString(", "))
            }

            if (defaultEntry.acl.isNotEmpty()) {
                parts.add("${defaultEntry.acl.size} ACL rule(s)")
            }

            tvDefaultPolicySummary.text = parts.joinToString(" • ")
        } else {
            tvDefaultPolicySummary.text = "Implicit empty policy (evaluates all reachable candidate paths)"
        }
    }

    private fun renderMatchersList() {
        layoutMatchersContainer.removeAllViews()
        val count = matchersList.size
        tvMatchersSectionTitle.text = "Traffic Matchers ($count)"

        if (count == 0) {
            tvMatchersEmpty.visibility = View.VISIBLE
            return
        }
        tvMatchersEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(context)

        for (i in 0 until count) {
            val m = matchersList[i]
            val item = inflater.inflate(R.layout.item_manager_matcher_entry, layoutMatchersContainer, false)

            val tvRank = item.findViewById<TextView>(R.id.tv_matcher_rank)
            val tvPolicyChip = item.findViewById<TextView>(R.id.tv_matcher_target_policy)
            val tvApp = item.findViewById<TextView>(R.id.tv_matcher_app)
            val tvCriteria = item.findViewById<TextView>(R.id.tv_matcher_criteria)
            val btnUp = item.findViewById<ImageButton>(R.id.btn_matcher_up)
            val btnDown = item.findViewById<ImageButton>(R.id.btn_matcher_down)
            val btnEdit = item.findViewById<ImageButton>(R.id.btn_matcher_edit)
            val btnDelete = item.findViewById<ImageButton>(R.id.btn_matcher_delete)

            tvRank.text = "#${i + 1}"
            tvPolicyChip.text = "→ Policy: ${m.policy}"

            // App Filter Line
            if (!m.appName.isNullOrBlank()) {
                tvApp.text = "📱 App: ${m.appName}"
                tvApp.visibility = View.VISIBLE
            } else {
                tvApp.visibility = View.GONE
            }

            // Criteria Line
            val criteria = mutableListOf<String>()
            if (!m.protocol.isNullOrBlank()) criteria.add(m.protocol.uppercase())
            if (!m.destination.isNullOrBlank()) criteria.add("To: ${m.destination}")
            if (!m.source.isNullOrBlank()) criteria.add("From: ${m.source}")
            if (m.trafficClass != null) criteria.add("DSCP: ${m.trafficClass}")

            if (criteria.isEmpty()) {
                criteria.add(if (m.appName != null) "All network traffic for selected app" else "All traffic matching policy scope")
            }
            tvCriteria.text = criteria.joinToString(" • ")

            // Reorder Up
            if (i == 0) {
                btnUp.alpha = 0.25f
                btnUp.isEnabled = false
            } else {
                btnUp.alpha = 1.0f
                btnUp.isEnabled = true
                val prev = i - 1
                val curr = i
                btnUp.setOnClickListener {
                    val temp = matchersList[curr]
                    matchersList[curr] = matchersList[prev]
                    matchersList[prev] = temp
                    renderMatchersList()
                }
            }

            // Reorder Down
            if (i == count - 1) {
                btnDown.alpha = 0.25f
                btnDown.isEnabled = false
            } else {
                btnDown.alpha = 1.0f
                btnDown.isEnabled = true
                val next = i + 1
                val curr = i
                btnDown.setOnClickListener {
                    val temp = matchersList[curr]
                    matchersList[curr] = matchersList[next]
                    matchersList[next] = temp
                    renderMatchersList()
                }
            }

            // Edit
            val currentIndex = i
            btnEdit.setOnClickListener {
                val customPolicyNames = policiesMap.keys.filter { it != "default" }
                val dialog = MatcherEditorDialogFragment.newInstance(
                    matcherIndex = currentIndex,
                    matcher = m,
                    availablePolicies = customPolicyNames
                )
                dialog.show(childFragmentManager, MatcherEditorDialogFragment.TAG)
            }

            // Delete
            btnDelete.setOnClickListener {
                matchersList.removeAt(currentIndex)
                renderMatchersList()
            }

            layoutMatchersContainer.addView(item)
        }
    }

    private fun renderPoliciesLibrary() {
        layoutPoliciesContainer.removeAllViews()
        val customPolicies = policiesMap.filter { it.key != "default" }
        val count = customPolicies.size
        tvPoliciesSectionTitle.text = "Policy Library ($count)"

        if (count == 0) {
            tvPoliciesEmpty.visibility = View.VISIBLE
            return
        }
        tvPoliciesEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(context)

        for ((name, entry) in customPolicies) {
            val item = inflater.inflate(R.layout.item_manager_policy_entry, layoutPoliciesContainer, false)

            val tvName = item.findViewById<TextView>(R.id.tv_policy_entry_name)
            val badgeExtends = item.findViewById<TextView>(R.id.badge_policy_extends)
            val badgeFailover = item.findViewById<TextView>(R.id.badge_policy_failover)
            val badgeSelector = item.findViewById<TextView>(R.id.badge_policy_selector)
            val tvSummary = item.findViewById<TextView>(R.id.tv_policy_entry_summary)
            val btnDuplicate = item.findViewById<ImageButton>(R.id.btn_policy_duplicate)
            val btnEdit = item.findViewById<ImageButton>(R.id.btn_policy_edit)
            val btnDelete = item.findViewById<ImageButton>(R.id.btn_policy_delete)

            tvName.text = name

            // Extends Badge
            if (!entry.extends.isNullOrBlank()) {
                badgeExtends.text = "Extends: ${entry.extends}"
                badgeExtends.visibility = View.VISIBLE
            } else {
                badgeExtends.visibility = View.GONE
            }

            // Failover Badge
            if (!entry.failover.isNullOrBlank()) {
                badgeFailover.text = "Failover: ${entry.failover}"
                badgeFailover.visibility = View.VISIBLE
            } else {
                badgeFailover.visibility = View.GONE
            }

            // Selector Badge
            if (!entry.selector.isNullOrBlank()) {
                badgeSelector.text = "Selector: ${entry.selector}"
                badgeSelector.visibility = View.VISIBLE
            } else {
                badgeSelector.visibility = View.GONE
            }

            // Summary
            val summaryParts = mutableListOf<String>()
            if (entry.ordering.isNotEmpty()) {
                val ordList = entry.ordering.map { keyToReadable(it) }
                summaryParts.add("Ordering: ${ordList.joinToString(", ")}")
            }
            if (entry.requirements.hasConstraints) {
                val reqs = mutableListOf<String>()
                entry.requirements.minMtu?.let { reqs.add("MTU ≥ $it") }
                entry.requirements.maxMetaLat?.let { reqs.add("Lat ≤ ${it}ms") }
                entry.requirements.minMetaBw?.let { reqs.add("BW ≥ $it kbps") }
                summaryParts.add(reqs.joinToString(", "))
            }
            if (entry.acl.isNotEmpty()) {
                summaryParts.add("${entry.acl.size} ACL rule(s)")
            }
            if (!entry.sequence.isNullOrBlank()) {
                summaryParts.add("Seq: ${entry.sequence}")
            }

            tvSummary.text = if (summaryParts.isNotEmpty()) summaryParts.joinToString(" • ") else "Default routing constraints"

            // Edit
            btnEdit.setOnClickListener {
                val dialog = PolicyEditorDialogFragment.newInstance(
                    originalPolicyName = name,
                    policyEntry = entry,
                    existingPolicies = policiesMap.keys.toList(),
                    isDefault = false
                )
                dialog.show(childFragmentManager, PolicyEditorDialogFragment.TAG)
            }

            // Duplicate
            btnDuplicate.setOnClickListener {
                duplicatePolicy(name, entry)
            }

            // Delete
            btnDelete.setOnClickListener {
                confirmDeletePolicy(name)
            }

            layoutPoliciesContainer.addView(item)
        }
    }

    private fun duplicatePolicy(name: String, entry: ScitraPolicyEntry) {
        var newName = "${name}_copy"
        var counter = 2
        while (policiesMap.containsKey(newName)) {
            newName = "${name}_copy$counter"
            counter++
        }
        policiesMap[newName] = entry.copy()
        renderPoliciesLibrary()
        Toast.makeText(requireContext(), "Created duplicate policy '$newName'", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeletePolicy(name: String) {
        val affectedMatchers = matchersList.count { it.policy == name }
        val message = if (affectedMatchers > 0) {
            "Policy '$name' is currently targeted by $affectedMatchers matcher(s). Deleting it may leave those matchers targeting an undefined policy.\n\nAre you sure you want to delete it?"
        } else {
            "Are you sure you want to delete policy '$name'?"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Delete Policy")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                policiesMap.remove(name)
                renderAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // Import / Export / Raw JSON Viewer
    // =========================================================================

    private fun importPolicyFromUri(uri: Uri) {
        try {
            val contentResolver = requireContext().contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
                val jsonContent = reader.readText()
                val parsed = PathPolicyParser.parseConfig(jsonContent)

                if (parsed.policies.isEmpty() && parsed.matchers.isEmpty()) {
                    Toast.makeText(requireContext(), "Imported file does not contain valid scitra-policy definitions.", Toast.LENGTH_LONG).show()
                    return
                }

                // Update state
                matchersList.clear()
                matchersList.addAll(parsed.matchers)
                policiesMap.clear()
                policiesMap.putAll(parsed.policies)
                if (!policiesMap.containsKey("default")) {
                    policiesMap["default"] = ScitraPolicyEntry(
                        acl = listOf("+"),
                        ordering = listOf("meta_latency_asc", "hops_asc")
                    )
                }

                renderAll()

                val issues = PathPolicyParser.validateConfig(parsed)
                if (issues.isNotEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "Imported with ${issues.size} validation warning(s). Check matchers and policies.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Successfully imported ${parsed.matchers.size} matcher(s) and ${parsed.policies.size} policy/policies.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to import JSON: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPolicyToUri(uri: Uri) {
        try {
            val config = ScitraPolicyConfig(
                matchers = matchersList.toList(),
                policies = LinkedHashMap(policiesMap)
            )
            val jsonString = PathPolicyParser.serializeConfig(config, 2)

            val contentResolver = requireContext().contentResolver
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
            }
            Toast.makeText(requireContext(), "scitra-policy.json exported successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to export JSON: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRawJsonDialog() {
        val config = ScitraPolicyConfig(
            matchers = matchersList.toList(),
            policies = LinkedHashMap(policiesMap)
        )
        val jsonString = PathPolicyParser.serializeConfig(config, 2)

        val textView = TextView(requireContext()).apply {
            text = jsonString
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextColor(requireContext().getColor(R.color.scitra_on_surface))
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(textView)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("scitra-policy.json Preview")
            .setView(scrollView)
            .setPositiveButton("Copy to Clipboard") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("scitra-policy.json", jsonString)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applyAndSave() {
        val config = ScitraPolicyConfig(
            matchers = matchersList.toList(),
            policies = LinkedHashMap(policiesMap)
        )

        // Semantic validation check
        val issues = PathPolicyParser.validateConfig(config)
        if (issues.isNotEmpty()) {
            val firstIssue = issues.first()
            Toast.makeText(requireContext(), "Warning: $firstIssue", Toast.LENGTH_LONG).show()
        }

        val jsonString = PathPolicyParser.serializeConfig(config, 2)

        setFragmentResult(
            REQUEST_KEY_POLICY,
            bundleOf(KEY_RESULT_JSON to jsonString)
        )

        dismiss()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun parsePolicyEntryFromJson(jsonStr: String): ScitraPolicyEntry {
        return PathPolicyParser.parsePolicyEntry(jsonStr)
    }

    private fun parseMatcherFromJson(jsonStr: String): ScitraMatcher {
        return PathPolicyParser.parseMatcher(jsonStr)
    }

    private fun keyToReadable(key: String): String {
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

    companion object {
        const val TAG = "PathPolicyManagerDialogFragment"
        const val REQUEST_KEY_POLICY = "request_key_policy"
        const val KEY_RESULT_JSON = "key_result_json"
        const val KEY_POLICY_JSON = "key_policy_json"

        fun newInstance(currentPolicyJson: String): PathPolicyManagerDialogFragment {
            val args = Bundle().apply {
                putString(KEY_POLICY_JSON, currentPolicyJson)
            }
            return PathPolicyManagerDialogFragment().apply { arguments = args }
        }
    }
}
