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

        val initialJson = savedInstanceState?.getString(KEY_SAVED_DRAFT_JSON)
            ?: arguments?.getString(KEY_POLICY_JSON)
        currentConfig = PathPolicyParser.parseConfig(initialJson)

        // Initialize mutable state
        matchersList.clear()
        matchersList.addAll(currentConfig.matchers)
        policiesMap.clear()
        policiesMap.putAll(currentConfig.policies)

        // Sanitize any previously corrupted dropdown values
        matchersList.forEachIndexed { i, m ->
            val cleanPolicy = sanitizeName(m.policy) ?: "default"
            if (cleanPolicy != m.policy) {
                matchersList[i] = m.copy(policy = cleanPolicy)
            }
        }
        for ((k, v) in policiesMap.toMap()) {
            val cleanExtends = sanitizeName(v.extends)
            val cleanFailover = sanitizeName(v.failover)
            val cleanSelector = sanitizeName(v.selector)
            if (cleanExtends != v.extends || cleanFailover != v.failover || cleanSelector != v.selector) {
                policiesMap[k] = v.copy(extends = cleanExtends, failover = cleanFailover, selector = cleanSelector)
            }
        }

        // Ensure default policy entry exists first in document order if not yet present
        if (!policiesMap.containsKey("default")) {
            val ordered = LinkedHashMap<String, ScitraPolicyEntry>()
            ordered["default"] = ScitraPolicyEntry(
                acl = listOf("+"),
                ordering = listOf("meta_latency_asc", "hops_asc")
            )
            ordered.putAll(policiesMap)
            policiesMap.clear()
            policiesMap.putAll(ordered)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val config = ScitraPolicyConfig(
            matchers = matchersList.toList(),
            policies = LinkedHashMap(policiesMap)
        )
        outState.putString(KEY_SAVED_DRAFT_JSON, PathPolicyParser.serializeConfig(config, 0))
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
            if (!isAdded || isStateSaved) return@setOnClickListener
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
            if (!isAdded || isStateSaved) return@setOnClickListener
            val customPolicyNames = policiesMap.keys.filter { it != "default" }
            if (customPolicyNames.isEmpty()) {
                val ctx = context ?: return@setOnClickListener
                Toast.makeText(
                    ctx,
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
            if (!isAdded || isStateSaved) return@setOnClickListener
            val dialog = PolicyEditorDialogFragment.newInstance(
                originalPolicyName = null,
                policyEntry = null,
                existingPolicies = policiesMap.keys.toList(),
                isDefault = false
            )
            dialog.show(childFragmentManager, PolicyEditorDialogFragment.TAG)
        }

        renderAll()

        val scrollToLibrary = arguments?.getBoolean(KEY_SCROLL_TO_LIBRARY, false) ?: false
        if (scrollToLibrary) {
            val scrollView = root.findViewById<android.widget.ScrollView>(R.id.scroll_policy_hub)
            val target = root.findViewById<View>(R.id.section_policy_library)
            if (scrollView != null && target != null) {
                scrollView.post {
                    scrollView.smoothScrollTo(0, target.top)
                }
            }
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFragmentResultListeners()
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
            if (matcher.policy.isBlank()) return@setFragmentResultListener

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

        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

        for (i in 0 until count) {
            val m = matchersList[i]
            val item = inflater.inflate(R.layout.item_manager_matcher_entry, layoutMatchersContainer, false)

            val tvRank = item.findViewById<TextView>(R.id.tv_matcher_rank)
            val tvPolicyChip = item.findViewById<TextView>(R.id.tv_matcher_target_policy)
            val tvApp = item.findViewById<TextView>(R.id.tv_matcher_app)
            val btnUp = item.findViewById<ImageButton>(R.id.btn_matcher_up)
            val btnDown = item.findViewById<ImageButton>(R.id.btn_matcher_down)
            val btnEdit = item.findViewById<ImageButton>(R.id.btn_matcher_edit)
            val btnDelete = item.findViewById<ImageButton>(R.id.btn_matcher_delete)

            val displayPolicy = sanitizeName(m.policy) ?: m.policy
            tvRank.text = "#${i + 1}"
            tvPolicyChip.text = "→ Policy: $displayPolicy"

            // Target scope line
            if (!m.appName.isNullOrBlank()) {
                tvApp.text = "📱 App: ${m.appName}"
                tvApp.visibility = View.VISIBLE
            } else {
                val criteria = mutableListOf<String>()
                if (!m.protocol.isNullOrBlank()) criteria.add(m.protocol.uppercase())
                if (!m.destination.isNullOrBlank()) criteria.add("To: ${m.destination}")
                if (!m.source.isNullOrBlank()) criteria.add("From: ${m.source}")
                if (m.trafficClass != null) criteria.add("DSCP: ${m.trafficClass}")

                if (criteria.isNotEmpty()) {
                    tvApp.text = "🌐 ${criteria.joinToString(" • ")}"
                    tvApp.visibility = View.VISIBLE
                } else {
                    tvApp.visibility = View.GONE
                }
            }

            // Clicking card opens editor
            item.setOnClickListener {
                btnEdit.performClick()
            }

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
                    if (curr in 1 until matchersList.size && prev == curr - 1) {
                        val temp = matchersList[curr]
                        matchersList[curr] = matchersList[prev]
                        matchersList[prev] = temp
                        renderMatchersList()
                    }
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
                    if (curr in 0 until matchersList.size - 1 && next == curr + 1) {
                        val temp = matchersList[curr]
                        matchersList[curr] = matchersList[next]
                        matchersList[next] = temp
                        renderMatchersList()
                    }
                }
            }

            // Edit
            val currentIndex = i
            btnEdit.setOnClickListener {
                if (!isAdded || isStateSaved) return@setOnClickListener
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
                if (currentIndex in matchersList.indices) {
                    matchersList.removeAt(currentIndex)
                    renderMatchersList()
                }
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

        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

        for ((name, entry) in customPolicies) {
            val item = inflater.inflate(R.layout.item_manager_policy_entry, layoutPoliciesContainer, false)

            val tvName = item.findViewById<TextView>(R.id.tv_policy_entry_name)
            val btnDuplicate = item.findViewById<ImageButton>(R.id.btn_policy_duplicate)
            val btnEdit = item.findViewById<ImageButton>(R.id.btn_policy_edit)
            val btnDelete = item.findViewById<ImageButton>(R.id.btn_policy_delete)

            tvName.text = name

            // Clicking card opens editor
            item.setOnClickListener {
                btnEdit.performClick()
            }

            // Edit
            btnEdit.setOnClickListener {
                if (!isAdded || isStateSaved) return@setOnClickListener
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
        val ctx = context ?: return
        var newName = "${name}_copy"
        var counter = 2
        while (policiesMap.containsKey(newName)) {
            newName = "${name}_copy$counter"
            counter++
        }
        policiesMap[newName] = entry.copy()
        renderPoliciesLibrary()
        Toast.makeText(ctx, "Created duplicate policy '$newName'", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeletePolicy(name: String) {
        val ctx = context ?: return
        val affectedMatchers = matchersList.count { it.policy == name }
        val message = if (affectedMatchers > 0) {
            "Policy '$name' is currently targeted by $affectedMatchers matcher(s). Deleting it will also remove those matchers and clear references in other policies.\n\nAre you sure you want to delete it?"
        } else {
            "Are you sure you want to delete policy '$name'?"
        }

        AlertDialog.Builder(ctx)
            .setTitle("Delete Policy")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                policiesMap.remove(name)
                // Remove orphaned matchers targeting this policy
                matchersList.removeAll { it.policy == name }
                // Clear extends/failover references pointing to this policy
                for ((k, v) in policiesMap.toMap()) {
                    if (v.extends == name || v.failover == name) {
                        policiesMap[k] = v.copy(
                            extends = if (v.extends == name) null else v.extends,
                            failover = if (v.failover == name) null else v.failover
                        )
                    }
                }
                renderAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // Import / Export / Raw JSON Viewer
    // =========================================================================

    private fun importPolicyFromUri(uri: Uri) {
        val ctx = context ?: return
        try {
            val contentResolver = ctx.contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
                val jsonContent = reader.readText()
                val parsed = PathPolicyParser.parseConfig(jsonContent)

                if (parsed.policies.isEmpty() && parsed.matchers.isEmpty()) {
                    Toast.makeText(ctx, "Imported file does not contain valid scitra-policy definitions.", Toast.LENGTH_LONG).show()
                    return
                }

                // Update state
                matchersList.clear()
                matchersList.addAll(parsed.matchers)
                policiesMap.clear()
                policiesMap.putAll(parsed.policies)
                if (!policiesMap.containsKey("default")) {
                    val ordered = LinkedHashMap<String, ScitraPolicyEntry>()
                    ordered["default"] = ScitraPolicyEntry(
                        acl = listOf("+"),
                        ordering = listOf("meta_latency_asc", "hops_asc")
                    )
                    ordered.putAll(policiesMap)
                    policiesMap.clear()
                    policiesMap.putAll(ordered)
                }

                renderAll()

                val issues = PathPolicyParser.validateConfig(parsed)
                if (issues.isNotEmpty()) {
                    Toast.makeText(
                        ctx,
                        "Imported with ${issues.size} validation warning(s). Check matchers and policies.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        ctx,
                        "Successfully imported ${parsed.matchers.size} matcher(s) and ${parsed.policies.size} policy/policies.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Failed to import JSON: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPolicyToUri(uri: Uri) {
        val ctx = context ?: return
        try {
            val config = ScitraPolicyConfig(
                matchers = matchersList.toList(),
                policies = LinkedHashMap(policiesMap)
            )
            val jsonString = PathPolicyParser.serializeConfig(config, 2)

            val contentResolver = ctx.contentResolver
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
            }
            Toast.makeText(ctx, "scitra-policy.json exported successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Failed to export JSON: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRawJsonDialog() {
        val ctx = context ?: return
        val config = ScitraPolicyConfig(
            matchers = matchersList.toList(),
            policies = LinkedHashMap(policiesMap)
        )
        val jsonString = PathPolicyParser.serializeConfig(config, 2)

        val textView = TextView(ctx).apply {
            text = jsonString
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextColor(ctx.getColor(R.color.scitra_on_surface))
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            addView(textView)
        }

        AlertDialog.Builder(ctx)
            .setTitle("scitra-policy.json Preview")
            .setView(scrollView)
            .setPositiveButton("Copy to Clipboard") { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("scitra-policy.json", jsonString)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(ctx, "JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
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
            val ctx = context ?: return
            AlertDialog.Builder(ctx)
                .setTitle("Invalid Policy Configuration")
                .setMessage("Please resolve the following issue(s) before applying:\n\n• " + issues.joinToString("\n• "))
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val jsonString = PathPolicyParser.serializeConfig(config, 2)
        val tunnelName = arguments?.getString(KEY_TUNNEL_NAME)

        setFragmentResult(
            REQUEST_KEY_POLICY,
            bundleOf(
                KEY_RESULT_JSON to jsonString,
                KEY_RESULT_TUNNEL_NAME to tunnelName
            )
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

    private fun sanitizeName(name: String?): String? {
        if (name == null) return null
        val trimmed = name.trim()
        if (trimmed.startsWith("ScitraDropdownItem(id=")) {
            val extracted = trimmed.substringAfter("id=").substringBefore(",").substringBefore(")")
            return if (extracted == "(None)" || extracted == "none" || extracted.isEmpty()) null else extracted
        }
        return if (trimmed == "(None)" || trimmed == "none" || trimmed.isEmpty()) null else trimmed
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
        const val KEY_RESULT_TUNNEL_NAME = "key_result_tunnel_name"
        const val KEY_POLICY_JSON = "key_policy_json"
        const val KEY_SAVED_DRAFT_JSON = "key_saved_draft_json"
        const val KEY_TUNNEL_NAME = "key_tunnel_name"
        const val KEY_SCROLL_TO_LIBRARY = "key_scroll_to_library"

        fun newInstance(
            currentPolicyJson: String,
            scrollToLibrary: Boolean = false,
            tunnelName: String? = null
        ): PathPolicyManagerDialogFragment {
            val args = Bundle().apply {
                putString(KEY_POLICY_JSON, currentPolicyJson)
                putBoolean(KEY_SCROLL_TO_LIBRARY, scrollToLibrary)
                putString(KEY_TUNNEL_NAME, tunnelName)
            }
            return PathPolicyManagerDialogFragment().apply { arguments = args }
        }
    }
}
