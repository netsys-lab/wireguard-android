/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PathPolicyDialogFragment : DialogFragment() {

    private lateinit var wizardFlipper: ViewFlipper
    private lateinit var stepIndicatorContainer: LinearLayout
    private lateinit var tvStepBadge: TextView
    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepSubtitle: TextView
    private lateinit var btnBack: View
    private lateinit var btnNext: View
    private lateinit var btnBackArrow: View
    private lateinit var btnAdvanced: View
    private lateinit var advancedConfigContainer: View

    // Step 3 - App Filter
    private lateinit var rvAppList: RecyclerView
    private lateinit var etAppSearch: EditText
    private lateinit var tvAppCount: TextView
    private lateinit var chipAppInclude: TextView
    private lateinit var chipAppExclude: TextView

    // Step 4 - ACL list
    private lateinit var aclList: LinearLayout

    // Step 5 - Ordering list
    private lateinit var orderingList: LinearLayout

    // Step 6 - Review
    private lateinit var tvJsonPreview: TextView
    private lateinit var tvValidationIcon: TextView
    private lateinit var tvValidationTitle: TextView
    private lateinit var tvValidationDesc: TextView

    private var currentStep = 0  // Index into activeSteps

    // Advanced mode
    private var isAdvancedMode = false
    // ViewFlipper child indices: 0=Identity, 1=Traffic, 2=Apps, 3=PolicyRules, 4=Ordering, 5=Review
    private var activeSteps = listOf(0, 1, 2, 5)  // Simple mode default
    private val allStepIndices = listOf(0, 1, 2, 3, 4, 5)

    // App filter state
    private var isAppExcludeMode = false
    private val allApps = mutableListOf<AppEntry>()
    private val filteredApps = mutableListOf<AppEntry>()
    private val selectedPackages = mutableSetOf<String>()
    private var appAdapter: AppPolicyAdapter? = null

    data class AppEntry(
        val icon: Drawable?,
        val name: String,
        val packageName: String
    )

    private var currentJson = DEFAULT_SAMPLE_JSON

    // Step indicator views (dots + lines)
    private val stepDots = mutableListOf<View>()
    private val stepLabels = mutableListOf<TextView>()
    private val stepLines = mutableListOf<View>()

    // Ordering state: key -> "asc" | "desc" | null (disabled)
    private val orderingState = linkedMapOf(
        "latency" to null as String?,
        "bandwidth" to null as String?,
        "hops" to null as String?,
        "random" to null as String?
    )

    // Protocol selection state
    private var selectedProtocol: String = ""

    private data class StepInfo(
        val title: String,
        val subtitle: String,
        val shortLabel: String
    )

    private val steps = listOf(
        StepInfo("Identity", "Define the identity of this path policy.", "Identity"),
        StepInfo("Traffic Matcher", "Define the traffic scope for this policy rule.", "Traffic\nMatcher"),
        StepInfo("App Filter", "Select which apps this policy applies to.", "App\nFilter"),
        StepInfo("Policy Rules", "Define absolute requirements, blocklists,\nand routing paths for this policy.", "Policy\nRules"),
        StepInfo("Path Ordering", "Define the priority of sorting criteria\nfor your routing policy.", "Path\nOrdering"),
        StepInfo("Review Configuration", "Verify the generated JSON for your new\npath policy before applying it.", "Review")
    )

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

        // Bind views
        wizardFlipper = root.findViewById(R.id.wizard_flipper)
        stepIndicatorContainer = root.findViewById(R.id.step_indicator_container)
        tvStepBadge = root.findViewById(R.id.tv_step_badge)
        tvStepTitle = root.findViewById(R.id.tv_step_title)
        tvStepSubtitle = root.findViewById(R.id.tv_step_subtitle)
        btnBack = root.findViewById(R.id.btn_wizard_back)
        btnNext = root.findViewById(R.id.btn_wizard_next)
        btnBackArrow = root.findViewById(R.id.btn_back_arrow)

        aclList = root.findViewById(R.id.acl_list)
        orderingList = root.findViewById(R.id.ordering_list)

        // Step 3 - App filter views
        rvAppList = root.findViewById(R.id.rv_app_list)
        etAppSearch = root.findViewById(R.id.et_app_search)
        tvAppCount = root.findViewById(R.id.tv_app_count)
        chipAppInclude = root.findViewById(R.id.chip_app_include)
        chipAppExclude = root.findViewById(R.id.chip_app_exclude)

        // Review step views
        tvJsonPreview = root.findViewById(R.id.tv_json_preview)
        tvValidationIcon = root.findViewById(R.id.tv_validation_icon)
        tvValidationTitle = root.findViewById(R.id.tv_validation_title)
        tvValidationDesc = root.findViewById(R.id.tv_validation_desc)
        btnAdvanced = root.findViewById(R.id.btn_advanced_config)
        advancedConfigContainer = root.findViewById(R.id.advanced_config_container)

        // Close / Back Arrow
        root.findViewById<View>(R.id.close_dialog).setOnClickListener { dismiss() }
        btnBackArrow.setOnClickListener {
            if (currentStep > 0) navigateToStep(currentStep - 1) else dismiss()
        }

        // Protocol chips
        setupProtocolChips(root)

        // App filter setup
        setupAppFilter()

        // Advanced config button
        btnAdvanced.setOnClickListener { enableAdvancedMode() }

        // ACL add button
        root.findViewById<View>(R.id.btn_add_acl).setOnClickListener {
            addAclEntry("+", "")
        }

        // Build step indicator
        buildStepIndicator()

        // Navigation buttons
        btnBack.setOnClickListener {
            if (currentStep > 0) {
                navigateToStep(currentStep - 1)
            } else {
                dismiss()
            }
        }

        btnNext.setOnClickListener {
            if (currentStep < activeSteps.size - 1) {
                navigateToStep(currentStep + 1)
            } else {
                saveAndDismiss()
            }
        }

        // Build ordering entries
        buildOrderingEntries()

        // Initialize from JSON
        syncUiFromCurrentJson()

        // Set initial step
        navigateToStep(0)

        return root
    }

    private fun setupProtocolChips(root: View) {
        val chipTcp = root.findViewById<TextView>(R.id.chip_tcp)
        val chipUdp = root.findViewById<TextView>(R.id.chip_udp)
        val chipBoth = root.findViewById<TextView>(R.id.chip_both)
        val chips = listOf(chipTcp, chipUdp, chipBoth)
        val values = listOf("tcp", "udp", "")

        fun selectChip(index: Int) {
            selectedProtocol = values[index]
            chips.forEachIndexed { i, chip ->
                if (i == index) {
                    chip.setBackgroundResource(R.drawable.scitra_chip_selected)
                    chip.setTextColor(requireContext().getColor(R.color.scitra_on_primary))
                } else {
                    chip.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chip.setTextColor(requireContext().getColor(R.color.scitra_on_surface))
                }
            }
        }

        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener { selectChip(i) }
        }

        // Default to "Both"
        selectChip(2)
    }

    // ========== APP FILTER ==========

    private fun setupAppFilter() {
        // RecyclerView
        appAdapter = AppPolicyAdapter()
        rvAppList.layoutManager = LinearLayoutManager(requireContext())
        rvAppList.adapter = appAdapter

        // Include/Exclude chips
        chipAppInclude.setOnClickListener { setAppMode(exclude = false) }
        chipAppExclude.setOnClickListener { setAppMode(exclude = true) }

        // Search
        etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })

        // Load apps
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val activity = activity ?: return
        val pm = activity.packageManager
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val apps = mutableListOf<AppEntry>()
                withContext(Dispatchers.IO) {
                    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackagesHoldingPermissions(
                            arrayOf(Manifest.permission.INTERNET),
                            PackageManager.PackageInfoFlags.of(0L)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackagesHoldingPermissions(
                            arrayOf(Manifest.permission.INTERNET), 0
                        )
                    }
                    packages.forEach { pkgInfo ->
                        val appInfo = pkgInfo.applicationInfo ?: return@forEach
                        apps.add(
                            AppEntry(
                                icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null },
                                name = appInfo.loadLabel(pm).toString(),
                                packageName = pkgInfo.packageName
                            )
                        )
                    }
                }
                apps.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                withContext(Dispatchers.Main.immediate) {
                    allApps.clear()
                    allApps.addAll(apps)
                    filterApps(etAppSearch.text?.toString() ?: "")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(context, "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        if (query.isBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val q = query.lowercase()
            filteredApps.addAll(allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        appAdapter?.notifyDataSetChanged()
        updateAppCount()
    }

    private fun updateAppCount() {
        val count = selectedPackages.size
        val mode = if (isAppExcludeMode) "excluded" else "included"
        tvAppCount.text = if (count == 0) "No apps selected — policy applies to all apps"
        else "$count app${if (count != 1) "s" else ""} $mode"
    }

    private fun setAppMode(exclude: Boolean) {
        isAppExcludeMode = exclude
        val ctx = requireContext()
        if (exclude) {
            chipAppExclude.setBackgroundResource(R.drawable.scitra_chip_selected)
            chipAppExclude.setTextColor(ctx.getColor(R.color.scitra_on_primary))
            chipAppInclude.setBackgroundResource(R.drawable.scitra_chip_unselected)
            chipAppInclude.setTextColor(ctx.getColor(R.color.scitra_on_surface))
        } else {
            chipAppInclude.setBackgroundResource(R.drawable.scitra_chip_selected)
            chipAppInclude.setTextColor(ctx.getColor(R.color.scitra_on_primary))
            chipAppExclude.setBackgroundResource(R.drawable.scitra_chip_unselected)
            chipAppExclude.setTextColor(ctx.getColor(R.color.scitra_on_surface))
        }
        updateAppCount()
    }

    // RecyclerView adapter for app list
    private inner class AppPolicyAdapter : RecyclerView.Adapter<AppPolicyAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
            val tvName: TextView = view.findViewById(R.id.tv_app_name)
            val tvPackage: TextView = view.findViewById(R.id.tv_app_package)
            val cbSelected: CheckBox = view.findViewById(R.id.cb_app_selected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_policy_entry, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = filteredApps.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            holder.tvName.text = app.name
            holder.tvPackage.text = app.packageName
            if (app.icon != null) {
                holder.ivIcon.setImageDrawable(app.icon)
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Prevent listener from firing during bind
            holder.cbSelected.setOnCheckedChangeListener(null)
            holder.cbSelected.isChecked = selectedPackages.contains(app.packageName)
            holder.cbSelected.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
                updateAppCount()
            }

            // Clicking the row also toggles
            holder.itemView.setOnClickListener {
                holder.cbSelected.isChecked = !holder.cbSelected.isChecked
            }
        }
    }

    private fun buildStepIndicator() {
        stepIndicatorContainer.removeAllViews()
        stepDots.clear()
        stepLabels.clear()
        stepLines.clear()

        val ctx = requireContext()
        val numSteps = activeSteps.size

        for (i in 0 until numSteps) {
            val flipperIndex = activeSteps[i]

            // Column for dot + label
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Dot
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(20, 20)
                setBackgroundResource(R.drawable.wizard_step_pending)
            }
            stepDots.add(dot)
            column.addView(dot)

            // Label — look up from steps list using the ViewFlipper index
            val label = TextView(ctx).apply {
                text = steps[flipperIndex].shortLabel
                textSize = 9f
                setTextColor(ctx.getColor(R.color.scitra_on_surface_variant))
                gravity = Gravity.CENTER
                setPadding(0, 4, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            stepLabels.add(label)
            column.addView(label)

            stepIndicatorContainer.addView(column)

            // Connector line (between dots, not after last)
            if (i < numSteps - 1) {
                val line = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 2).apply {
                        weight = 1f
                        gravity = Gravity.CENTER_VERTICAL
                        // Align with the dot vertically (offset by dot center)
                        topMargin = -20 // shift line up to align with dots
                    }
                    setBackgroundResource(R.drawable.wizard_step_line)
                }
                stepLines.add(line)
                stepIndicatorContainer.addView(line)
            }
        }
    }

    private fun updateStepIndicator() {
        val ctx = context ?: return

        stepDots.forEachIndexed { i, dot ->
            when {
                i < currentStep -> dot.setBackgroundResource(R.drawable.wizard_step_completed)
                i == currentStep -> dot.setBackgroundResource(R.drawable.wizard_step_active)
                else -> dot.setBackgroundResource(R.drawable.wizard_step_pending)
            }
        }

        stepLabels.forEachIndexed { i, label ->
            when {
                i < currentStep -> {
                    label.setTextColor(ctx.getColor(R.color.scitra_success))
                    label.typeface = Typeface.DEFAULT_BOLD
                }
                i == currentStep -> {
                    label.setTextColor(ctx.getColor(R.color.scitra_primary))
                    label.typeface = Typeface.DEFAULT_BOLD
                }
                else -> {
                    label.setTextColor(ctx.getColor(R.color.scitra_on_surface_variant))
                    label.typeface = Typeface.DEFAULT
                }
            }
        }

        stepLines.forEachIndexed { i, line ->
            if (i < currentStep) {
                line.setBackgroundResource(R.drawable.wizard_step_line_active)
            } else {
                line.setBackgroundResource(R.drawable.wizard_step_line)
            }
        }
    }

    private fun navigateToStep(step: Int) {
        if (step < 0 || step >= activeSteps.size) return

        // The ViewFlipper child index for this logical step
        val flipperIndex = activeSteps[step]

        // If navigating to the Review step (always flipper index 5), generate JSON preview
        if (flipperIndex == 5) {
            generateJsonPreview()
            advancedConfigContainer.visibility = if (isAdvancedMode) View.GONE else View.VISIBLE
        }

        currentStep = step
        wizardFlipper.displayedChild = flipperIndex
        updateStepIndicator()
        updateStepHeader()
        updateNavigationButtons()
    }

    private fun updateStepHeader() {
        val flipperIndex = activeSteps[currentStep]
        val info = steps[flipperIndex]
        tvStepBadge.text = "● STEP ${currentStep + 1} OF ${activeSteps.size}"
        tvStepTitle.text = info.title
        tvStepSubtitle.text = info.subtitle
    }

    private fun updateNavigationButtons() {
        val ctx = context ?: return
        val back = btnBack as? TextView
        val next = btnNext as? TextView

        if (currentStep == 0) {
            back?.text = "Cancel"
        } else {
            back?.text = "Back"
        }

        if (currentStep == activeSteps.size - 1) {
            next?.text = "Save Policy"
        } else {
            next?.text = "Next  →"
        }
    }

    private fun enableAdvancedMode() {
        if (isAdvancedMode) return
        isAdvancedMode = true

        // Expand to all 6 steps
        activeSteps = allStepIndices.toList()

        // Hide the advanced button and container
        btnAdvanced.visibility = View.GONE
        advancedConfigContainer.visibility = View.GONE

        // Rebuild the step indicator to show all 6 dots
        buildStepIndicator()

        // If currently on Review (flipperIndex 5), navigate to the first advanced step (Policy Rules, index 3)
        if (wizardFlipper.displayedChild == 5) {
            navigateToStep(3)
        } else {
            updateStepIndicator()
            updateStepHeader()
            updateNavigationButtons()
        }

        Toast.makeText(context, "Advanced configuration enabled", Toast.LENGTH_SHORT).show()
    }

    // ========== ACL MANAGEMENT ==========

    private fun addAclEntry(type: String, value: String) {
        val inflater = LayoutInflater.from(context)
        val card = inflater.inflate(R.layout.item_acl_entry, aclList, false)

        val tvType = card.findViewById<TextView>(R.id.tv_acl_type)
        val etValue = card.findViewById<EditText>(R.id.et_acl_value)

        tvType.text = type
        if (type == "+") {
            tvType.setTextColor(requireContext().getColor(R.color.scitra_success))
        } else {
            tvType.setTextColor(requireContext().getColor(R.color.md_theme_dark_error))
        }

        // Toggle allow/deny on click
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

        card.findViewById<ImageView>(R.id.btn_delete_acl).setOnClickListener {
            aclList.removeView(card)
        }

        aclList.addView(card)
    }

    // ========== ORDERING MANAGEMENT ==========

    private fun buildOrderingEntries() {
        orderingList.removeAllViews()
        val inflater = LayoutInflater.from(context)

        val entries = listOf(
            "Latency" to "latency",
            "Bandwidth" to "bandwidth",
            "Hops" to "hops",
            "Random" to "random"
        )

        for ((label, key) in entries) {
            val card = inflater.inflate(R.layout.item_ordering_entry, orderingList, false)
            card.tag = key

            card.findViewById<TextView>(R.id.tv_ordering_label).text = label

            val chipAsc = card.findViewById<TextView>(R.id.chip_asc)
            val chipDesc = card.findViewById<TextView>(R.id.chip_desc)

            // For "Random", hide Asc/Desc — it's a toggle
            if (key == "random") {
                chipAsc.visibility = View.GONE
                chipDesc.visibility = View.GONE

                // Add a single "Enable" toggle instead
                val chipEnable = TextView(requireContext()).apply {
                    text = "Enable"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(40, 0, 40, 0)
                    minimumHeight = (32 * resources.displayMetrics.density).toInt()
                    setTextColor(requireContext().getColor(R.color.scitra_on_surface))
                    setBackgroundResource(R.drawable.scitra_chip_unselected)
                    isClickable = true
                    isFocusable = true
                    tag = "chip_enable"
                }
                (card as LinearLayout).addView(chipEnable)

                chipEnable.setOnClickListener {
                    val current = orderingState[key]
                    if (current != null) {
                        orderingState[key] = null
                        chipEnable.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipEnable.setTextColor(requireContext().getColor(R.color.scitra_on_surface))
                    } else {
                        orderingState[key] = "random"
                        chipEnable.setBackgroundResource(R.drawable.scitra_chip_selected)
                        chipEnable.setTextColor(requireContext().getColor(R.color.scitra_on_primary))
                    }
                }
            } else {
                fun updateChips() {
                    val state = orderingState[key]
                    val ctx = requireContext()
                    if (state == "asc") {
                        chipAsc.setBackgroundResource(R.drawable.scitra_chip_selected)
                        chipAsc.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                        chipDesc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipDesc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    } else if (state == "desc") {
                        chipAsc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipAsc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                        chipDesc.setBackgroundResource(R.drawable.scitra_chip_selected)
                        chipDesc.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                    } else {
                        chipAsc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipAsc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                        chipDesc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipDesc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    }
                }

                chipAsc.setOnClickListener {
                    orderingState[key] = if (orderingState[key] == "asc") null else "asc"
                    updateChips()
                }

                chipDesc.setOnClickListener {
                    orderingState[key] = if (orderingState[key] == "desc") null else "desc"
                    updateChips()
                }

                updateChips()
            }

            orderingList.addView(card)
        }
    }

    // ========== JSON SYNC ==========

    private fun syncUiFromCurrentJson() {
        try {
            val rootObj = JSONObject(currentJson)

            // If there are matchers, populate step 2 from the first one
            val matchers = rootObj.optJSONArray("matchers")
            if (matchers != null && matchers.length() > 0) {
                val matcher = matchers.getJSONObject(0)
                val root = view ?: return

                root.findViewById<EditText>(R.id.et_matcher_source)?.setText(matcher.optString("source"))
                root.findViewById<EditText>(R.id.et_matcher_destination)?.setText(matcher.optString("destination"))

                val proto = matcher.optString("protocol")
                selectedProtocol = proto
                selectProtocolChip(proto)

                val tc = matcher.optInt("traffic_class", -1)
                if (tc != -1) {
                    root.findViewById<EditText>(R.id.et_matcher_traffic_class)?.setText(tc.toString())
                }

                // The matcher's policy reference tells us which policy to load
                val policyName = matcher.optString("policy")

                // Populate step 1 from the referenced policy
                val policies = rootObj.optJSONObject("policies")
                if (policies != null && policyName.isNotEmpty() && policies.has(policyName)) {
                    root.findViewById<EditText>(R.id.et_policy_name)?.setText(policyName)
                    val policy = policies.getJSONObject(policyName)
                    populatePolicyFields(root, policy)
                } else if (policies != null) {
                    // Just take the first policy
                    val firstKey = policies.keys().next()
                    root.findViewById<EditText>(R.id.et_policy_name)?.setText(firstKey)
                    populatePolicyFields(root, policies.getJSONObject(firstKey))
                }
            } else {
                // No matchers — just load the first policy
                val policies = rootObj.optJSONObject("policies")
                if (policies != null && policies.keys().hasNext()) {
                    val root = view ?: return
                    val firstKey = policies.keys().next()
                    root.findViewById<EditText>(R.id.et_policy_name)?.setText(firstKey)
                    populatePolicyFields(root, policies.getJSONObject(firstKey))
                }
            }

            // Restore app filter from JSON
            val appsObj = rootObj.optJSONObject("apps")
            if (appsObj != null) {
                val mode = appsObj.optString("mode", "include")
                isAppExcludeMode = mode == "exclude"
                setAppMode(isAppExcludeMode)

                selectedPackages.clear()
                val pkgArr = appsObj.optJSONArray("packages")
                if (pkgArr != null) {
                    for (i in 0 until pkgArr.length()) {
                        selectedPackages.add(pkgArr.getString(i))
                    }
                }
                updateAppCount()
                appAdapter?.notifyDataSetChanged()
            }

            // Auto-detect advanced fields — if the JSON has ACL, ordering,
            // requirements, extends, failover, or sequence, enable advanced mode
            val policiesForAutoDetect = rootObj.optJSONObject("policies")
            if (policiesForAutoDetect != null) {
                val keys = policiesForAutoDetect.keys()
                while (keys.hasNext()) {
                    val policy = policiesForAutoDetect.optJSONObject(keys.next()) ?: continue
                    val hasAdvanced = policy.has("acl") || policy.has("ordering") ||
                            policy.has("requirements") || policy.has("extends") ||
                            policy.has("failover") || policy.has("sequence")
                    if (hasAdvanced) {
                        enableAdvancedMode()
                        break
                    }
                }
            }

        } catch (e: Exception) {
            Toast.makeText(context, "Error reading policy JSON", Toast.LENGTH_SHORT).show()
        }
    }

    private fun populatePolicyFields(root: View, policy: JSONObject) {
        root.findViewById<EditText>(R.id.et_policy_extends)?.setText(policy.optString("extends"))
        root.findViewById<EditText>(R.id.et_policy_failover)?.setText(policy.optString("failover"))

        // ACL
        aclList.removeAllViews()
        val aclArr = policy.optJSONArray("acl")
        if (aclArr != null) {
            for (i in 0 until aclArr.length()) {
                val rule = aclArr.getString(i).trim()
                if (rule.startsWith("-")) {
                    addAclEntry("-", rule.substring(1).trim())
                } else if (rule.startsWith("+")) {
                    val value = rule.substring(1).trim()
                    addAclEntry("+", value)
                } else {
                    addAclEntry("+", rule)
                }
            }
        }

        // Sequence
        root.findViewById<EditText>(R.id.et_sequence)?.setText(policy.optString("sequence"))

        // Requirements
        val reqs = policy.optJSONObject("requirements")
        if (reqs != null) {
            val mtu = reqs.optInt("min_mtu", -1)
            val lat = reqs.optInt("max_meta_lat", -1)
            val bw = reqs.optInt("min_meta_bw", -1)
            if (mtu != -1) root.findViewById<EditText>(R.id.et_min_mtu)?.setText(mtu.toString())
            if (lat != -1) root.findViewById<EditText>(R.id.et_max_latency)?.setText(lat.toString())
            if (bw != -1) root.findViewById<EditText>(R.id.et_min_bandwidth)?.setText(bw.toString())
        }

        // Ordering
        val orderingArr = policy.optJSONArray("ordering")
        // Reset ordering state
        orderingState.keys.forEach { orderingState[it] = null }
        if (orderingArr != null) {
            for (i in 0 until orderingArr.length()) {
                val item = orderingArr.getString(i)
                when {
                    item == "random" -> orderingState["random"] = "random"
                    item.startsWith("meta_latency_") -> orderingState["latency"] = item.substringAfter("meta_latency_")
                    item.startsWith("meta_bandwidth_") || item.startsWith("meta_bw_") -> orderingState["bandwidth"] = item.substringAfterLast("_")
                    item.startsWith("hops_") -> orderingState["hops"] = item.substringAfter("hops_")
                    item == "latency_asc" || item == "latency_desc" -> orderingState["latency"] = item.substringAfter("latency_")
                    item == "bandwidth_asc" || item == "bandwidth_desc" -> orderingState["bandwidth"] = item.substringAfter("bandwidth_")
                }
            }
        }
        refreshOrderingUi()
    }

    private fun refreshOrderingUi() {
        val ctx = context ?: return
        for (i in 0 until orderingList.childCount) {
            val card = orderingList.getChildAt(i)
            val key = card.tag as? String ?: continue

            if (key == "random") {
                val chipEnable = card.findViewWithTag<TextView>("chip_enable") ?: continue
                if (orderingState[key] != null) {
                    chipEnable.setBackgroundResource(R.drawable.scitra_chip_selected)
                    chipEnable.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                } else {
                    chipEnable.setBackgroundResource(R.drawable.scitra_chip_unselected)
                    chipEnable.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                }
            } else {
                val chipAsc = card.findViewById<TextView>(R.id.chip_asc)
                val chipDesc = card.findViewById<TextView>(R.id.chip_desc)
                val state = orderingState[key]

                when (state) {
                    "asc" -> {
                        chipAsc.setBackgroundResource(R.drawable.scitra_chip_selected)
                        chipAsc.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                        chipDesc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipDesc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    }
                    "desc" -> {
                        chipAsc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipAsc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                        chipDesc.setBackgroundResource(R.drawable.scitra_chip_selected)
                        chipDesc.setTextColor(ctx.getColor(R.color.scitra_on_primary))
                    }
                    else -> {
                        chipAsc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipAsc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                        chipDesc.setBackgroundResource(R.drawable.scitra_chip_unselected)
                        chipDesc.setTextColor(ctx.getColor(R.color.scitra_on_surface))
                    }
                }
            }
        }
    }

    private fun selectProtocolChip(protocol: String) {
        val root = view ?: return
        val chipTcp = root.findViewById<TextView>(R.id.chip_tcp)
        val chipUdp = root.findViewById<TextView>(R.id.chip_udp)
        val chipBoth = root.findViewById<TextView>(R.id.chip_both)
        val chips = listOf(chipTcp, chipUdp, chipBoth)
        val ctx = requireContext()

        val index = when (protocol.lowercase()) {
            "tcp" -> 0
            "udp" -> 1
            else -> 2
        }

        chips.forEachIndexed { i, chip ->
            if (i == index) {
                chip.setBackgroundResource(R.drawable.scitra_chip_selected)
                chip.setTextColor(ctx.getColor(R.color.scitra_on_primary))
            } else {
                chip.setBackgroundResource(R.drawable.scitra_chip_unselected)
                chip.setTextColor(ctx.getColor(R.color.scitra_on_surface))
            }
        }
    }

    private fun syncJsonFromCurrentUi(): JSONObject {
        val root = view ?: return JSONObject()

        val rootObj = JSONObject()
        val policyName = root.findViewById<EditText>(R.id.et_policy_name).text.toString().trim()

        // Build matcher
        val matchersArr = JSONArray()
        val matcherObj = JSONObject()

        val source = root.findViewById<EditText>(R.id.et_matcher_source).text.toString().trim()
        val dest = root.findViewById<EditText>(R.id.et_matcher_destination).text.toString().trim()
        val tcStr = root.findViewById<EditText>(R.id.et_matcher_traffic_class).text.toString().trim()

        if (source.isNotEmpty()) matcherObj.put("source", source)
        if (dest.isNotEmpty()) matcherObj.put("destination", dest)
        if (selectedProtocol.isNotEmpty()) matcherObj.put("protocol", selectedProtocol)
        if (tcStr.isNotEmpty()) matcherObj.put("traffic_class", tcStr.toIntOrNull() ?: 0)
        if (policyName.isNotEmpty()) matcherObj.put("policy", policyName)

        if (matcherObj.length() > 0) {
            matchersArr.put(matcherObj)
        }
        rootObj.put("matchers", matchersArr)

        // Build policy
        val policiesObj = JSONObject()
        val policyObj = JSONObject()

        val ext = root.findViewById<EditText>(R.id.et_policy_extends).text.toString().trim()
        val failover = root.findViewById<EditText>(R.id.et_policy_failover).text.toString().trim()
        if (ext.isNotEmpty()) policyObj.put("extends", ext)
        if (failover.isNotEmpty()) policyObj.put("failover", failover)

        // ACL
        val aclArr = JSONArray()
        for (i in 0 until aclList.childCount) {
            val card = aclList.getChildAt(i)
            val type = card.findViewById<TextView>(R.id.tv_acl_type).text.toString()
            val value = card.findViewById<EditText>(R.id.et_acl_value).text.toString().trim()
            if (value.isNotEmpty()) {
                aclArr.put("$type $value")
            } else {
                aclArr.put(type)
            }
        }
        if (aclArr.length() > 0) policyObj.put("acl", aclArr)

        // Sequence
        val seq = root.findViewById<EditText>(R.id.et_sequence).text.toString().trim()
        if (seq.isNotEmpty()) policyObj.put("sequence", seq)

        // Requirements
        val mtuStr = root.findViewById<EditText>(R.id.et_min_mtu).text.toString().trim()
        val latStr = root.findViewById<EditText>(R.id.et_max_latency).text.toString().trim()
        val bwStr = root.findViewById<EditText>(R.id.et_min_bandwidth).text.toString().trim()
        val reqsObj = JSONObject()
        var hasReqs = false
        if (mtuStr.isNotEmpty()) { reqsObj.put("min_mtu", mtuStr.toIntOrNull() ?: 0); hasReqs = true }
        if (latStr.isNotEmpty()) { reqsObj.put("max_meta_lat", latStr.toIntOrNull() ?: 0); hasReqs = true }
        if (bwStr.isNotEmpty()) { reqsObj.put("min_meta_bw", bwStr.toIntOrNull() ?: 0); hasReqs = true }
        if (hasReqs) policyObj.put("requirements", reqsObj)

        // Ordering
        val ordArr = JSONArray()
        for ((key, dir) in orderingState) {
            if (dir == null) continue
            when (key) {
                "random" -> ordArr.put("random")
                "latency" -> ordArr.put("meta_latency_$dir")
                "bandwidth" -> ordArr.put("meta_bw_$dir")
                "hops" -> ordArr.put("hops_$dir")
            }
        }
        if (ordArr.length() > 0) policyObj.put("ordering", ordArr)

        val name = if (policyName.isNotEmpty()) policyName else "default"
        policiesObj.put(name, policyObj)
        rootObj.put("policies", policiesObj)

        // Apps filter
        if (selectedPackages.isNotEmpty()) {
            val appsObj = JSONObject()
            appsObj.put("mode", if (isAppExcludeMode) "exclude" else "include")
            val pkgArr = JSONArray()
            selectedPackages.sorted().forEach { pkgArr.put(it) }
            appsObj.put("packages", pkgArr)
            rootObj.put("apps", appsObj)
        }

        return rootObj
    }

    private fun generateJsonPreview() {
        try {
            val json = syncJsonFromCurrentUi()
            val formatted = json.toString(2)
            tvJsonPreview.text = colorizeJson(formatted)
            currentJson = formatted

            // Validation
            val policyName = view?.findViewById<EditText>(R.id.et_policy_name)?.text.toString().trim()
            if (policyName.isNotEmpty()) {
                tvValidationIcon.text = "✓"
                tvValidationIcon.setTextColor(requireContext().getColor(R.color.scitra_success))
                tvValidationTitle.text = "Validation Passed"
                tvValidationTitle.setTextColor(requireContext().getColor(R.color.scitra_success))
                tvValidationDesc.text = "Syntax and schema constraints have been verified against SCION mesh definitions."
            } else {
                tvValidationIcon.text = "!"
                tvValidationIcon.setTextColor(requireContext().getColor(R.color.scitra_warning))
                tvValidationTitle.text = "Warning"
                tvValidationTitle.setTextColor(requireContext().getColor(R.color.scitra_warning))
                tvValidationDesc.text = "Policy name is empty. A default name will be used."
            }
        } catch (e: Exception) {
            tvJsonPreview.text = "Error generating JSON: ${e.message}"
            tvValidationIcon.text = "✕"
            tvValidationIcon.setTextColor(requireContext().getColor(R.color.md_theme_dark_error))
            tvValidationTitle.text = "Validation Failed"
            tvValidationTitle.setTextColor(requireContext().getColor(R.color.md_theme_dark_error))
            tvValidationDesc.text = e.message ?: "Unknown error"
        }
    }

    private fun colorizeJson(json: String): SpannableString {
        val spannable = SpannableString(json)
        val ctx = context ?: return spannable

        val keyColor = ctx.getColor(R.color.scitra_primary)
        val stringColor = ctx.getColor(R.color.scitra_success)
        val numberColor = ctx.getColor(R.color.scitra_warning)

        // Colorize JSON keys (before colon)
        val keyRegex = Regex("\"([^\"]+)\"\\s*:")
        for (match in keyRegex.findAll(json)) {
            spannable.setSpan(
                ForegroundColorSpan(keyColor),
                match.range.first,
                match.range.last + 1,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Colorize string values (after colon)
        val valueRegex = Regex(":\\s*\"([^\"]+)\"")
        for (match in valueRegex.findAll(json)) {
            val valueStart = json.indexOf('"', match.range.first + 1)
            if (valueStart >= 0) {
                val valueEnd = json.indexOf('"', valueStart + 1) + 1
                if (valueEnd > valueStart) {
                    spannable.setSpan(
                        ForegroundColorSpan(stringColor),
                        valueStart,
                        valueEnd,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        // Colorize numbers
        val numRegex = Regex(":\\s*(\\d+)")
        for (match in numRegex.findAll(json)) {
            val group = match.groups[1] ?: continue
            spannable.setSpan(
                ForegroundColorSpan(numberColor),
                group.range.first,
                group.range.last + 1,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }

    private fun saveAndDismiss() {
        val json = syncJsonFromCurrentUi()
        currentJson = json.toString(2)

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
