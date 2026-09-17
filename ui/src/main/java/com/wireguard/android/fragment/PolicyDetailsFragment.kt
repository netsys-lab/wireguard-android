package com.wireguard.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.model.AppFilterMode
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.ParsedPathPolicy
import com.wireguard.android.model.PathSectionState
import com.wireguard.android.model.PolicyAclRuleUiModel
import com.wireguard.android.model.PolicyOrderingUiModel
import com.wireguard.android.util.PathPolicyParser
import com.wireguard.android.viewmodel.FlowPathViewModel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PolicyDetailsFragment : BaseFragment() {

    private var currentPolicyJson: String = ""
    private var parsedPolicy: ParsedPathPolicy? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.policy_details_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        view.findViewById<View>(R.id.btn_copy_json).setOnClickListener {
            val json = parsedPolicy?.rawJson ?: currentPolicyJson
            if (json.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SCION Policy JSON", json)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.json_copied, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.btn_configure_policy_action).setOnClickListener {
            openConfigurePolicyDialog()
        }

        view.findViewById<View>(R.id.btn_reset_policy_action).setOnClickListener {
            resetPolicyToDefault()
        }

        childFragmentManager.setFragmentResultListener(
            PathPolicyDialogFragment.REQUEST_KEY_POLICY,
            viewLifecycleOwner
        ) { _, bundle ->
            val resultJson = bundle.getString(PathPolicyDialogFragment.KEY_RESULT_JSON)
            if (resultJson != null) {
                saveNewPolicy(resultJson)
            }
        }

        loadAndRenderPolicy()
    }

    private fun loadAndRenderPolicy() {
        val tunnel = selectedTunnel
        lifecycleScope.launch {
            try {
                val config = tunnel?.getConfigAsync()
                val policyJson = config?.`interface`?.pathPolicy ?: ""
                currentPolicyJson = policyJson

                // Try to get live policy name from ViewModel if available
                var livePolicyName: String? = null
                try {
                    val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
                    val section = vm.pathSection.value
                    if (section is PathSectionState.Ready) {
                        livePolicyName = section.policyName
                    }
                } catch (ignored: Exception) {}

                val parsed = PathPolicyParser.parse(policyJson, livePolicyName)
                parsedPolicy = parsed
                renderPolicy(parsed)
            } catch (e: Exception) {
                val fallback = PathPolicyParser.parse(null, null)
                parsedPolicy = fallback
                renderPolicy(fallback)
            }
        }
    }

    private fun renderPolicy(policy: ParsedPathPolicy) {
        val v = view ?: return

        // 1. Overview
        v.findViewById<TextView>(R.id.policy_detail_name).text = policy.name
        val badge = v.findViewById<TextView>(R.id.policy_detail_badge)
        if (policy.isDefaultPolicy) {
            badge.text = getString(R.string.policy_default_badge)
            badge.setBackgroundResource(R.drawable.scitra_badge_chip_bg)
            badge.setTextColor(requireContext().getColor(R.color.scitra_primary))
        } else {
            badge.text = getString(R.string.flow_state_active)
            badge.setBackgroundResource(R.drawable.scitra_status_badge_bg)
            badge.setTextColor(requireContext().getColor(R.color.scitra_on_primary))
        }

        val extendsLabel = v.findViewById<TextView>(R.id.policy_extends_label)
        if (!policy.extends.isNullOrEmpty()) {
            extendsLabel.text = "Extends baseline: ${policy.extends}"
            extendsLabel.visibility = View.VISIBLE
        } else {
            extendsLabel.visibility = View.GONE
        }

        v.findViewById<TextView>(R.id.policy_summary_desc).text = policy.summaryDescription

        // 2. Ordering Pipeline
        val orderingContainer = v.findViewById<LinearLayout>(R.id.policy_ordering_container)
        orderingContainer.removeAllViews()
        policy.ordering.forEachIndexed { index, criterion ->
            orderingContainer.addView(createOrderingStepView(index + 1, criterion))
        }

        // 3. Traffic Matchers
        val matcher = policy.matchers.firstOrNull()
        v.findViewById<TextView>(R.id.matcher_protocol).text = matcher?.protocol ?: "All protocols"
        v.findViewById<TextView>(R.id.matcher_source).text = matcher?.source ?: "Any"
        v.findViewById<TextView>(R.id.matcher_destination).text = matcher?.destination ?: "Any"
        v.findViewById<TextView>(R.id.matcher_traffic_class).text =
            matcher?.trafficClass?.toString() ?: "Any"

        // 4. Application Scope
        val appModeBadge = v.findViewById<TextView>(R.id.app_filter_mode_badge)
        val appDesc = v.findViewById<TextView>(R.id.app_filter_desc)
        val appsContainer = v.findViewById<LinearLayout>(R.id.policy_apps_container)
        appsContainer.removeAllViews()

        when (policy.appFilter.mode) {
            AppFilterMode.ALL -> {
                appModeBadge.text = "ALL APPLICATIONS"
                appDesc.text = getString(R.string.all_apps_scoped)
            }
            AppFilterMode.INCLUDE -> {
                appModeBadge.text = "INCLUDED APPLICATIONS (${policy.appFilter.packages.size})"
                appDesc.text = "Policy applies exclusively to the following applications:"
                populateAppEntries(appsContainer, policy.appFilter.packages)
            }
            AppFilterMode.EXCLUDE -> {
                appModeBadge.text = "EXCLUDED APPLICATIONS (${policy.appFilter.packages.size})"
                appDesc.text = "Policy applies to all network traffic EXCEPT the following applications:"
                populateAppEntries(appsContainer, policy.appFilter.packages)
            }
        }

        // 5. Requirements & ACL
        v.findViewById<TextView>(R.id.req_min_mtu).text =
            policy.requirements.minMtu?.let { "$it B" } ?: "None"
        v.findViewById<TextView>(R.id.req_max_latency).text =
            policy.requirements.maxLatencyMs?.let { "$it ms" } ?: "None"
        v.findViewById<TextView>(R.id.req_min_bw).text =
            policy.requirements.minBwKbps?.let { formatBw(it) } ?: "None"

        val aclContainer = v.findViewById<LinearLayout>(R.id.policy_acl_container)
        aclContainer.removeAllViews()
        if (policy.aclRules.isEmpty()) {
            val emptyTv = TextView(requireContext()).apply {
                text = "No custom ACL filters (all reachable transit ASes are permitted)."
                setTextColor(requireContext().getColor(R.color.scitra_on_surface_variant))
                textSize = 12f
            }
            aclContainer.addView(emptyTv)
        } else {
            policy.aclRules.forEach { rule ->
                aclContainer.addView(createAclRuleView(rule))
            }
        }

        // 6. JSON Spec
        v.findViewById<TextView>(R.id.tv_policy_json).text = colorizeJson(policy.rawJson)

        // 7. Reset Button visibility
        val btnReset = v.findViewById<View>(R.id.btn_reset_policy_action)
        btnReset.visibility = if (!policy.isDefaultPolicy && currentPolicyJson.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun createOrderingStepView(stepNumber: Int, criterion: PolicyOrderingUiModel): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.scitra_section_card_bg)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * resources.displayMetrics.density).toInt()
            }
            layoutParams = lp
        }

        val stepBadge = TextView(ctx).apply {
            text = "$stepNumber"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.scitra_primary))
            setBackgroundResource(R.drawable.scitra_badge_chip_bg)
            val size = (26 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (10 * resources.displayMetrics.density).toInt()
            }
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(ctx).apply {
            text = criterion.label
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.getColor(R.color.scitra_on_surface))
        }

        val detailTv = TextView(ctx).apply {
            text = criterion.detail
            textSize = 11f
            setTextColor(ctx.getColor(R.color.scitra_on_surface_variant))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * resources.displayMetrics.density).toInt()
            }
            layoutParams = lp
        }

        textCol.addView(titleTv)
        textCol.addView(detailTv)

        card.addView(stepBadge)
        card.addView(textCol)
        return card
    }

    private fun createAclRuleView(rule: PolicyAclRuleUiModel): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padV = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(ctx).apply {
            text = if (rule.isAllow) "+ ALLOW" else "- BLOCK"
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val padH = (6 * resources.displayMetrics.density).toInt()
            val padTop = (2 * resources.displayMetrics.density).toInt()
            setPadding(padH, padTop, padH, padTop)
            if (rule.isAllow) {
                setTextColor(ctx.getColor(R.color.scitra_on_primary))
                setBackgroundResource(R.drawable.scitra_status_badge_bg)
            } else {
                setTextColor(ctx.getColor(R.color.scitra_on_primary))
                setBackgroundResource(R.drawable.scitra_error_btn_bg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (10 * resources.displayMetrics.density).toInt()
            }
        }

        val descTv = TextView(ctx).apply {
            text = rule.description
            textSize = 12f
            setTextColor(ctx.getColor(R.color.scitra_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(badge)
        row.addView(descTv)
        return row
    }

    private fun populateAppEntries(container: LinearLayout, packages: List<String>) {
        val ctx = context ?: return
        val pm = ctx.packageManager

        lifecycleScope.launch {
            val appDataList = withContext(Dispatchers.IO) {
                packages.map { pkg ->
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        Triple(name, pkg, icon)
                    } catch (e: Exception) {
                        Triple(pkg, pkg, null as Drawable?)
                    }
                }
            }

            for ((name, pkg, icon) in appDataList) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.scitra_section_card_bg)
                    val pad = (8 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                }

                if (icon != null) {
                    val iv = ImageView(ctx).apply {
                        setImageDrawable(icon)
                        val size = (24 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = (8 * resources.displayMetrics.density).toInt()
                        }
                    }
                    row.addView(iv)
                }

                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val title = TextView(ctx).apply {
                    text = name
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ctx.getColor(R.color.scitra_on_surface))
                }

                val subtitle = TextView(ctx).apply {
                    text = pkg
                    textSize = 10f
                    setTextColor(ctx.getColor(R.color.scitra_on_surface_variant))
                }

                textCol.addView(title)
                textCol.addView(subtitle)
                row.addView(textCol)
                container.addView(row)
            }
        }
    }

    private fun openConfigurePolicyDialog() {
        val jsonToEdit = if (currentPolicyJson.isNotBlank()) {
            currentPolicyJson
        } else {
            parsedPolicy?.rawJson ?: ""
        }
        val dialog = PathPolicyDialogFragment.newInstance(jsonToEdit, isEditMode = true)
        dialog.show(childFragmentManager, "path_policy")
    }

    private fun saveNewPolicy(newJson: String) {
        val tunnel = selectedTunnel ?: return
        lifecycleScope.launch {
            try {
                val currentConfig = tunnel.getConfigAsync()
                val newInterfaceBuilder = Interface.Builder()
                    .addAddresses(currentConfig.`interface`.addresses)
                    .addDnsServers(currentConfig.`interface`.dnsServers)
                    .addDnsSearchDomains(currentConfig.`interface`.dnsSearchDomains)
                    .excludeApplications(currentConfig.`interface`.excludedApplications)
                    .includeApplications(currentConfig.`interface`.includedApplications)
                    .setKeyPair(currentConfig.`interface`.keyPair)
                    .setBootstrapUrl(currentConfig.`interface`.bootstrapUrl)
                    .setPathPolicy(newJson)
                    .setTunnelMode(currentConfig.`interface`.tunnelMode)

                currentConfig.`interface`.listenPort.ifPresent { newInterfaceBuilder.setListenPort(it) }
                currentConfig.`interface`.mtu.ifPresent { newInterfaceBuilder.setMtu(it) }

                val newConfig = Config.Builder()
                    .setInterface(newInterfaceBuilder.build())
                    .addPeers(currentConfig.peers)
                    .build()

                tunnel.setConfigAsync(newConfig)
                Toast.makeText(context, R.string.policy_updated_success, Toast.LENGTH_SHORT).show()
                currentPolicyJson = newJson
                loadAndRenderPolicy()

                try {
                    val vm = ViewModelProvider(requireActivity())[FlowPathViewModel::class.java]
                    vm.loadPaths()
                } catch (ignored: Exception) {}
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to save policy: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetPolicyToDefault() {
        saveNewPolicy("")
    }

    private fun formatBw(kbps: Long): String = when {
        kbps >= 1_000_000 -> "${kbps / 1_000_000} Gbps"
        kbps >= 1_000 -> "${kbps / 1_000} Mbps"
        else -> "$kbps Kbps"
    }

    private fun colorizeJson(json: String): SpannableString {
        val spannable = SpannableString(json)
        val ctx = context ?: return spannable

        val keyColor = ctx.getColor(R.color.scitra_primary)
        val stringColor = ctx.getColor(R.color.scitra_success)
        val numberColor = ctx.getColor(R.color.scitra_warning)

        // Keys
        val keyRegex = Regex("\"([^\"]+)\"\\s*:")
        for (match in keyRegex.findAll(json)) {
            spannable.setSpan(
                ForegroundColorSpan(keyColor),
                match.range.first,
                match.range.last + 1,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Strings
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

        // Numbers
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

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        loadAndRenderPolicy()
    }

    companion object {
        fun newInstance(): PolicyDetailsFragment {
            return PolicyDetailsFragment()
        }
    }
}
