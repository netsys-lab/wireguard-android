package com.wireguard.android.util

import com.wireguard.android.model.AppFilterMode
import com.wireguard.android.model.ParsedPathPolicy
import com.wireguard.android.model.PolicyAclRuleUiModel
import com.wireguard.android.model.PolicyAppFilterUiModel
import com.wireguard.android.model.PolicyMatcherUiModel
import com.wireguard.android.model.PolicyOrderingUiModel
import com.wireguard.android.model.PolicyRequirementsUiModel
import com.wireguard.android.model.ScitraMatcher
import com.wireguard.android.model.ScitraPolicyConfig
import com.wireguard.android.model.ScitraPolicyEntry
import com.wireguard.android.model.ScitraRequirements
import org.json.JSONArray
import org.json.JSONObject

object PathPolicyParser {

    /**
     * Parses a raw JSON string into a structured ScitraPolicyConfig containing
     * all matchers and policies in document order.
     */
    fun parseConfig(rawJson: String?): ScitraPolicyConfig {
        if (rawJson.isNullOrBlank()) {
            return ScitraPolicyConfig()
        }

        return try {
            val root = JSONObject(rawJson)

            // 1. Matchers
            val matchersList = mutableListOf<ScitraMatcher>()
            val matchersArr = root.optJSONArray("matchers")
            if (matchersArr != null) {
                for (i in 0 until matchersArr.length()) {
                    val m = matchersArr.optJSONObject(i) ?: continue
                    val policyName = m.optString("policy", "").trim()
                    if (policyName.isEmpty()) continue

                    val source = m.optString("source", "").trim().takeIf { it.isNotEmpty() }
                    val dest = m.optString("destination", "").trim().takeIf { it.isNotEmpty() }
                    val proto = m.optString("protocol", "").trim().lowercase().takeIf { it.isNotEmpty() }
                    val tc = if (m.has("traffic_class")) m.optInt("traffic_class") else null
                    val appName = when {
                        m.has("app_name") -> m.optString("app_name", "").trim().takeIf { it.isNotEmpty() }
                        m.has("package") -> m.optString("package", "").trim().takeIf { it.isNotEmpty() }
                        else -> null
                    }
                    val appUid = if (m.has("app_uid")) m.optInt("app_uid") else null

                    matchersList.add(
                        ScitraMatcher(
                            policy = policyName,
                            source = source,
                            destination = dest,
                            protocol = proto,
                            trafficClass = tc,
                            appName = appName,
                            appUid = appUid,
                        )
                    )
                }
            }

            // 2. Policies (LinkedHashMap to preserve document order)
            val policiesMap = LinkedHashMap<String, ScitraPolicyEntry>()
            val policiesObj = root.optJSONObject("policies")
            if (policiesObj != null) {
                val keys = policiesObj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val p = policiesObj.optJSONObject(name) ?: continue

                    val extends = p.optString("extends", "").trim().takeIf { it.isNotEmpty() }
                    val failover = p.optString("failover", "").trim().takeIf { it.isNotEmpty() }

                    // ACL
                    val aclList = mutableListOf<String>()
                    val aclArr = p.optJSONArray("acl")
                    if (aclArr != null) {
                        for (j in 0 until aclArr.length()) {
                            val rule = aclArr.optString(j, "").trim()
                            if (rule.isNotEmpty()) aclList.add(rule)
                        }
                    }

                    // Sequence
                    val seq = p.optString("sequence", "").trim().takeIf { it.isNotEmpty() }

                    // Requirements
                    val reqObj = p.optJSONObject("requirements")
                    val requirements = ScitraRequirements(
                        minMtu = if (reqObj?.has("min_mtu") == true) reqObj.optInt("min_mtu") else null,
                        maxMetaLat = when {
                            reqObj?.has("max_meta_lat") == true -> reqObj.optInt("max_meta_lat")
                            reqObj?.has("max_latency") == true -> reqObj.optInt("max_latency")
                            else -> null
                        },
                        minMetaBw = when {
                            reqObj?.has("min_meta_bw") == true -> reqObj.optLong("min_meta_bw")
                            reqObj?.has("min_bandwidth") == true -> reqObj.optLong("min_bandwidth")
                            else -> null
                        },
                    )

                    // Ordering
                    val orderingList = mutableListOf<String>()
                    val ordArr = p.optJSONArray("ordering")
                    if (ordArr != null) {
                        for (j in 0 until ordArr.length()) {
                            val ord = ordArr.optString(j, "").trim()
                            if (ord.isNotEmpty()) orderingList.add(ord)
                        }
                    }

                    // Dynamic path selector
                    val selector = p.optString("selector", "").trim().takeIf { it.isNotEmpty() }

                    policiesMap[name] = ScitraPolicyEntry(
                        extends = extends,
                        failover = failover,
                        acl = aclList,
                        sequence = seq,
                        requirements = requirements,
                        ordering = orderingList,
                        selector = selector,
                    )
                }
            }

            // 3. Legacy top-level apps object (backward compatibility)
            val appsObj = root.optJSONObject("apps")
            val legacyApps = if (appsObj != null) {
                val modeStr = appsObj.optString("mode", "include").lowercase()
                val mode = if (modeStr == "exclude") AppFilterMode.EXCLUDE else AppFilterMode.INCLUDE
                val pkgs = mutableListOf<String>()
                val pkgArr = appsObj.optJSONArray("packages")
                if (pkgArr != null) {
                    for (i in 0 until pkgArr.length()) {
                        val pkg = pkgArr.optString(i, "").trim()
                        if (pkg.isNotEmpty()) pkgs.add(pkg)
                    }
                }
                PolicyAppFilterUiModel(mode = mode, packages = pkgs)
            } else null

            ScitraPolicyConfig(
                matchers = matchersList,
                policies = policiesMap,
                legacyApps = legacyApps,
            )
        } catch (e: Exception) {
            ScitraPolicyConfig()
        }
    }

    /**
     * Serializes a ScitraPolicyConfig into canonical scitra-policy.json format.
     */
    fun serializeConfig(config: ScitraPolicyConfig, indentSpaces: Int = 2): String {
        val root = JSONObject()

        // Matchers
        val matchersArr = JSONArray()
        for (m in config.matchers) {
            val mObj = JSONObject()
            m.source?.takeIf { it.isNotBlank() }?.let { mObj.put("source", it) }
            m.destination?.takeIf { it.isNotBlank() }?.let { mObj.put("destination", it) }
            m.protocol?.takeIf { it.isNotBlank() }?.let { mObj.put("protocol", it.lowercase()) }
            m.trafficClass?.let { mObj.put("traffic_class", it) }
            m.appName?.takeIf { it.isNotBlank() }?.let { mObj.put("app_name", it) }
            m.appUid?.let { mObj.put("app_uid", it) }
            mObj.put("policy", m.policy)
            matchersArr.put(mObj)
        }
        root.put("matchers", matchersArr)

        // Policies
        val policiesObj = JSONObject()
        for ((name, p) in config.policies) {
            val pObj = JSONObject()
            p.extends?.takeIf { it.isNotBlank() }?.let { pObj.put("extends", it) }
            p.failover?.takeIf { it.isNotBlank() }?.let { pObj.put("failover", it) }

            if (p.acl.isNotEmpty()) {
                val aclArr = JSONArray()
                p.acl.forEach { aclArr.put(it) }
                pObj.put("acl", aclArr)
            }

            p.sequence?.takeIf { it.isNotBlank() }?.let { pObj.put("sequence", it) }

            if (p.requirements.hasConstraints) {
                val reqObj = JSONObject()
                p.requirements.minMtu?.let { reqObj.put("min_mtu", it) }
                p.requirements.maxMetaLat?.let { reqObj.put("max_meta_lat", it) }
                p.requirements.minMetaBw?.let { reqObj.put("min_meta_bw", it) }
                pObj.put("requirements", reqObj)
            }

            if (p.ordering.isNotEmpty()) {
                val ordArr = JSONArray()
                p.ordering.forEach { ordArr.put(it) }
                pObj.put("ordering", ordArr)
            }

            p.selector?.takeIf { it.isNotBlank() }?.let { pObj.put("selector", it) }

            policiesObj.put(name, pObj)
        }
        root.put("policies", policiesObj)

        // Legacy apps filter (only if present)
        config.legacyApps?.takeIf { it.packages.isNotEmpty() }?.let { apps ->
            val appsObj = JSONObject()
            appsObj.put("mode", if (apps.mode == AppFilterMode.EXCLUDE) "exclude" else "include")
            val pkgArr = JSONArray()
            apps.packages.sorted().forEach { pkgArr.put(it) }
            appsObj.put("packages", pkgArr)
            root.put("apps", appsObj)
        }

        return if (indentSpaces > 0) root.toString(indentSpaces) else root.toString()
    }

    /**
     * Validates a policy configuration according to SCION path policy specifications.
     * Returns a list of validation issues/warnings, or an empty list if valid.
     */
    fun validateConfig(config: ScitraPolicyConfig): List<String> {
        val issues = mutableListOf<String>()
        val definedPolicyNames = config.policies.keys.toList()

        // 1. Validate Matchers
        config.matchers.forEachIndexed { idx, matcher ->
            val indexNum = idx + 1
            if (matcher.policy.isBlank()) {
                issues.add("Matcher #$indexNum: Policy target must not be empty.")
            } else if (matcher.policy == "default") {
                issues.add("Matcher #$indexNum: Directly targeting the reserved 'default' policy is not allowed. 'default' is applied automatically when no matcher matches.")
            } else if (!config.policies.containsKey(matcher.policy)) {
                issues.add("Matcher #$indexNum: Targets undefined policy '${matcher.policy}'.")
            }

            matcher.protocol?.let { proto ->
                if (proto != "tcp" && proto != "udp") {
                    issues.add("Matcher #$indexNum: Protocol '$proto' is invalid. Must be 'tcp' or 'udp'.")
                }
            }

            matcher.trafficClass?.let { tc ->
                if (tc !in 0..63) {
                    issues.add("Matcher #$indexNum: Traffic class (DSCP) must be between 0 and 63.")
                }
            }
        }

        // 2. Validate Policies
        definedPolicyNames.forEachIndexed { index, name ->
            val entry = config.policies[name] ?: return@forEachIndexed

            // Extends check: must precede in document order
            entry.extends?.let { base ->
                if (base == name) {
                    issues.add("Policy '$name': Cannot extend itself.")
                } else {
                    val baseIndex = definedPolicyNames.indexOf(base)
                    if (baseIndex == -1) {
                        issues.add("Policy '$name': Extends unknown policy '$base'.")
                    } else if (baseIndex >= index) {
                        issues.add("Policy '$name': Extends policy '$base', which must precede '$name' in document order.")
                    }
                }
            }

            // Failover check: must exist
            entry.failover?.let { fallback ->
                if (fallback == name) {
                    issues.add("Policy '$name': Cannot have itself as failover policy.")
                } else if (!config.policies.containsKey(fallback)) {
                    issues.add("Policy '$name': Failover targets unknown policy '$fallback'.")
                }
            }

            // Requirements validation
            entry.requirements.minMtu?.let {
                if (it < 0) issues.add("Policy '$name': min_mtu cannot be negative.")
            }
            entry.requirements.maxMetaLat?.let {
                if (it < 0) issues.add("Policy '$name': max_meta_lat cannot be negative.")
            }
            entry.requirements.minMetaBw?.let {
                if (it < 0) issues.add("Policy '$name': min_meta_bw cannot be negative.")
            }
        }

        return issues
    }

    /**
     * Backward-compatible parse function returning ParsedPathPolicy for a specific active policy name.
     */
    fun parse(rawJson: String?, activePolicyName: String? = null): ParsedPathPolicy {
        if (rawJson.isNullOrBlank()) {
            return defaultPolicy(activePolicyName)
        }

        val config = parseConfig(rawJson)
        val definedNames = config.policies.keys.toList()

        val chosenName = when {
            !activePolicyName.isNullOrBlank() && config.policies.containsKey(activePolicyName) -> activePolicyName
            definedNames.contains("p1") -> "p1"
            definedNames.isNotEmpty() -> definedNames.first()
            else -> activePolicyName?.takeIf { it.isNotBlank() } ?: "default"
        }

        val policyEntry = config.policies[chosenName] ?: ScitraPolicyEntry()

        // Matchers UI models
        val matchersList = config.matchers.map { m ->
            PolicyMatcherUiModel(
                source = m.source,
                destination = m.destination,
                protocol = m.protocol,
                trafficClass = m.trafficClass,
                policy = m.policy,
                appName = m.appName,
                appUid = m.appUid,
            )
        }

        // App filter from legacy top-level or from matchers
        val appFilter = config.legacyApps ?: run {
            val appNames = config.matchers.mapNotNull { it.appName }.distinct()
            if (appNames.isNotEmpty()) {
                PolicyAppFilterUiModel(mode = AppFilterMode.INCLUDE, packages = appNames)
            } else {
                PolicyAppFilterUiModel(mode = AppFilterMode.ALL, packages = emptyList())
            }
        }

        // Requirements
        val requirements = PolicyRequirementsUiModel(
            minMtu = policyEntry.requirements.minMtu,
            maxLatencyMs = policyEntry.requirements.maxMetaLat,
            minBwKbps = policyEntry.requirements.minMetaBw,
        )

        // ACL Rules
        val aclList = policyEntry.acl.map { ruleStr ->
            val isAllow = ruleStr.startsWith("+")
            val cleaned = ruleStr.removePrefix("+").removePrefix("-").trim()
            val description = when {
                cleaned.isEmpty() && isAllow -> "Allow all transit ASes"
                cleaned.isEmpty() && !isAllow -> "Block all transit ASes"
                isAllow -> "Allow ISD-AS $cleaned"
                else -> "Block ISD-AS $cleaned"
            }
            PolicyAclRuleUiModel(
                isAllow = isAllow,
                pattern = ruleStr,
                description = description,
            )
        }

        // Ordering
        val orderingList = policyEntry.ordering.map { rawItem ->
            parseOrderingCriterion(rawItem)
        }

        val isDefault = chosenName == "default" || (config.policies.isEmpty() && config.matchers.isEmpty())
        val description = generateSummaryDescription(
            chosenName,
            matchersList,
            appFilter,
            requirements,
            aclList,
            orderingList,
            policyEntry.selector,
        )

        val formattedJson = serializeConfig(config, 2)

        return ParsedPathPolicy(
            name = chosenName,
            extends = policyEntry.extends,
            failover = policyEntry.failover,
            isDefaultPolicy = isDefault,
            summaryDescription = description,
            matchers = matchersList,
            appFilter = appFilter,
            requirements = requirements,
            aclRules = aclList,
            ordering = orderingList,
            selector = policyEntry.selector,
            rawJson = formattedJson,
        )
    }

    fun parseOrderingCriterion(item: String): PolicyOrderingUiModel {
        return when {
            item == "random" -> PolicyOrderingUiModel(
                key = "random",
                direction = null,
                label = "Random Load Balancing",
                detail = "Distribute traffic pseudo-randomly among candidate paths"
            )
            item.contains("latency") -> {
                val dir = if (item.endsWith("desc")) "desc" else "asc"
                PolicyOrderingUiModel(
                    key = "latency",
                    direction = dir,
                    label = if (dir == "asc") "Lowest Latency" else "Highest Latency",
                    detail = if (dir == "asc") "Prioritizes paths with the lowest round-trip latency" else "Prioritizes paths with highest latency"
                )
            }
            item.contains("bw") || item.contains("bandwidth") -> {
                val dir = if (item.endsWith("asc")) "asc" else "desc"
                PolicyOrderingUiModel(
                    key = "bandwidth",
                    direction = dir,
                    label = if (dir == "desc") "Highest Bandwidth" else "Lowest Bandwidth",
                    detail = if (dir == "desc") "Prioritizes paths with the largest bottleneck capacity" else "Prioritizes lower bandwidth paths"
                )
            }
            item.contains("hops") -> {
                val dir = if (item.endsWith("desc")) "desc" else "asc"
                PolicyOrderingUiModel(
                    key = "hops",
                    direction = dir,
                    label = if (dir == "asc") "Fewest Hops" else "Most Hops",
                    detail = if (dir == "asc") "Favors shorter paths with fewer inter-AS link transitions" else "Favors longer paths"
                )
            }
            else -> PolicyOrderingUiModel(
                key = item,
                direction = null,
                label = item,
                detail = "Custom SCION ordering rule"
            )
        }
    }

    fun generateSummaryDescription(
        name: String,
        matchers: List<PolicyMatcherUiModel>,
        apps: PolicyAppFilterUiModel,
        reqs: PolicyRequirementsUiModel,
        acl: List<PolicyAclRuleUiModel>,
        ordering: List<PolicyOrderingUiModel>,
        selector: String? = null,
    ): String {
        val parts = mutableListOf<String>()

        if (ordering.isNotEmpty()) {
            val criteriaNames = ordering.map { it.label }
            parts.add("Ranks candidate paths by ${criteriaNames.joinToString(" then ")}.")
        } else {
            parts.add("Selects optimal SCION paths.")
        }

        selector?.takeIf { it.isNotBlank() }?.let {
            parts.add("Dynamic path selector: $it.")
        }

        if (reqs.hasConstraints) {
            val constraints = mutableListOf<String>()
            reqs.minMtu?.let { constraints.add("MTU ≥ $it bytes") }
            reqs.maxLatencyMs?.let { constraints.add("latency ≤ ${it}ms") }
            reqs.minBwKbps?.let { constraints.add("bandwidth ≥ ${it} kbit/s") }
            parts.add("Requires ${constraints.joinToString(", ")}.")
        }

        if (acl.isNotEmpty()) {
            val blocked = acl.filter { !it.isAllow }
            if (blocked.isNotEmpty()) {
                val blockedAses = blocked.map { it.pattern.removePrefix("-").trim() }.filter { it.isNotEmpty() }
                if (blockedAses.isNotEmpty()) {
                    parts.add("Excludes AS: ${blockedAses.joinToString(", ")}.")
                }
            }
        }

        when (apps.mode) {
            AppFilterMode.INCLUDE -> parts.add("Applies exclusively to ${apps.packages.size} selected app(s).")
            AppFilterMode.EXCLUDE -> parts.add("Applies to all apps except ${apps.packages.size} excluded app(s).")
            AppFilterMode.ALL -> {}
        }

        return parts.joinToString(" ")
    }

    private fun defaultPolicy(activePolicyName: String?, rawJson: String = ""): ParsedPathPolicy {
        val name = activePolicyName?.takeIf { it.isNotBlank() } ?: "Lowest Latency"
        val fallbackJson = rawJson.ifBlank {
            """
            {
              "matchers": [],
              "policies": {
                "default": {
                  "requirements": {
                    "min_mtu": 1280
                  },
                  "acl": ["+"],
                  "ordering": ["meta_latency_asc", "hops_asc"]
                }
              }
            }
            """.trimIndent()
        }

        return ParsedPathPolicy(
            name = name,
            isDefaultPolicy = true,
            summaryDescription = "Automatically evaluates all reachable SCION paths and chooses the optimal route by minimizing latency and hop count.",
            matchers = emptyList(),
            appFilter = PolicyAppFilterUiModel(mode = AppFilterMode.ALL, packages = emptyList()),
            requirements = PolicyRequirementsUiModel(minMtu = 1280),
            aclRules = listOf(
                PolicyAclRuleUiModel(
                    isAllow = true,
                    pattern = "+",
                    description = "Allow all reachable SCION transit ASes"
                )
            ),
            ordering = listOf(
                PolicyOrderingUiModel(
                    key = "latency",
                    direction = "asc",
                    label = "Lowest Latency",
                    detail = "Selects the path with minimal round-trip latency"
                ),
                PolicyOrderingUiModel(
                    key = "hops",
                    direction = "asc",
                    label = "Fewest Hops",
                    detail = "Secondary tie-breaker: prefers shorter inter-AS routes"
                )
            ),
            rawJson = fallbackJson,
        )
    }
}
