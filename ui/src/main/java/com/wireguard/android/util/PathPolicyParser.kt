package com.wireguard.android.util

import com.wireguard.android.model.AppFilterMode
import com.wireguard.android.model.ParsedPathPolicy
import com.wireguard.android.model.PolicyAclRuleUiModel
import com.wireguard.android.model.PolicyAppFilterUiModel
import com.wireguard.android.model.PolicyMatcherUiModel
import com.wireguard.android.model.PolicyOrderingUiModel
import com.wireguard.android.model.PolicyRequirementsUiModel
import org.json.JSONArray
import org.json.JSONObject

object PathPolicyParser {

    fun parse(rawJson: String?, activePolicyName: String? = null): ParsedPathPolicy {
        if (rawJson.isNullOrBlank()) {
            return defaultPolicy(activePolicyName)
        }

        return try {
            val root = JSONObject(rawJson)
            val policiesObj = root.optJSONObject("policies")

            // Determine target policy name
            val policyNames = mutableListOf<String>()
            policiesObj?.keys()?.forEach { policyNames.add(it) }

            val chosenName = when {
                !activePolicyName.isNullOrBlank() && policiesObj?.has(activePolicyName) == true -> activePolicyName
                policyNames.contains("p1") -> "p1"
                policyNames.isNotEmpty() -> policyNames.first()
                else -> activePolicyName?.takeIf { it.isNotBlank() } ?: "default"
            }

            val policyObj = policiesObj?.optJSONObject(chosenName) ?: JSONObject()

            // Matchers
            val matchersList = mutableListOf<PolicyMatcherUiModel>()
            val matchersArr = root.optJSONArray("matchers")
            if (matchersArr != null) {
                for (i in 0 until matchersArr.length()) {
                    val m = matchersArr.optJSONObject(i) ?: continue
                    matchersList.add(
                        PolicyMatcherUiModel(
                            source = m.optString("source", null)?.takeIf { it.isNotEmpty() },
                            destination = m.optString("destination", null)?.takeIf { it.isNotEmpty() },
                            protocol = m.optString("protocol", null)?.takeIf { it.isNotEmpty() },
                            trafficClass = if (m.has("traffic_class")) m.optInt("traffic_class") else null,
                            policy = m.optString("policy", null)?.takeIf { it.isNotEmpty() },
                        )
                    )
                }
            }

            // Apps
            val appsObj = root.optJSONObject("apps")
            val appFilter = if (appsObj != null) {
                val modeStr = appsObj.optString("mode", "include").lowercase()
                val mode = if (modeStr == "exclude") AppFilterMode.EXCLUDE else AppFilterMode.INCLUDE
                val pkgs = mutableListOf<String>()
                val pkgArr = appsObj.optJSONArray("packages")
                if (pkgArr != null) {
                    for (i in 0 until pkgArr.length()) {
                        val p = pkgArr.optString(i, "")
                        if (p.isNotEmpty()) pkgs.add(p)
                    }
                }
                PolicyAppFilterUiModel(mode = mode, packages = pkgs)
            } else {
                PolicyAppFilterUiModel(mode = AppFilterMode.ALL, packages = emptyList())
            }

            // Requirements
            val reqObj = policyObj.optJSONObject("requirements")
            val requirements = PolicyRequirementsUiModel(
                minMtu = if (reqObj?.has("min_mtu") == true) reqObj.optInt("min_mtu") else null,
                maxLatencyMs = when {
                    reqObj?.has("max_meta_lat") == true -> reqObj.optInt("max_meta_lat")
                    reqObj?.has("max_latency") == true -> reqObj.optInt("max_latency")
                    else -> null
                },
                minBwKbps = when {
                    reqObj?.has("min_meta_bw") == true -> reqObj.optLong("min_meta_bw")
                    reqObj?.has("min_bandwidth") == true -> reqObj.optLong("min_bandwidth")
                    else -> null
                },
            )

            // ACL Rules
            val aclList = mutableListOf<PolicyAclRuleUiModel>()
            val aclArr = policyObj.optJSONArray("acl")
            if (aclArr != null) {
                for (i in 0 until aclArr.length()) {
                    val ruleStr = aclArr.optString(i, "").trim()
                    if (ruleStr.isEmpty()) continue
                    val isAllow = ruleStr.startsWith("+")
                    val cleaned = ruleStr.removePrefix("+").removePrefix("-").trim()
                    val description = when {
                        cleaned.isEmpty() && isAllow -> "Allow all transit ASes"
                        cleaned.isEmpty() && !isAllow -> "Block all transit ASes"
                        isAllow -> "Allow ISD-AS $cleaned"
                        else -> "Block ISD-AS $cleaned"
                    }
                    aclList.add(
                        PolicyAclRuleUiModel(
                            isAllow = isAllow,
                            pattern = ruleStr,
                            description = description,
                        )
                    )
                }
            }

            // Ordering
            val orderingList = mutableListOf<PolicyOrderingUiModel>()
            val ordArr = policyObj.optJSONArray("ordering")
            if (ordArr != null) {
                for (i in 0 until ordArr.length()) {
                    val rawItem = ordArr.optString(i, "").trim()
                    if (rawItem.isEmpty()) continue
                    val criterion = parseOrderingCriterion(rawItem)
                    orderingList.add(criterion)
                }
            }

            val extends = policyObj.optString("extends", null)?.takeIf { it.isNotEmpty() }
            val failover = policyObj.optString("failover", null)?.takeIf { it.isNotEmpty() }

            val description = generateSummaryDescription(
                chosenName,
                matchersList,
                appFilter,
                requirements,
                aclList,
                orderingList
            )

            ParsedPathPolicy(
                name = chosenName,
                extends = extends,
                failover = failover,
                isDefaultPolicy = false,
                summaryDescription = description,
                matchers = matchersList,
                appFilter = appFilter,
                requirements = requirements,
                aclRules = aclList,
                ordering = orderingList,
                rawJson = root.toString(2),
            )
        } catch (e: Exception) {
            defaultPolicy(activePolicyName, rawJson)
        }
    }

    private fun parseOrderingCriterion(item: String): PolicyOrderingUiModel {
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

    private fun generateSummaryDescription(
        name: String,
        matchers: List<PolicyMatcherUiModel>,
        apps: PolicyAppFilterUiModel,
        reqs: PolicyRequirementsUiModel,
        acl: List<PolicyAclRuleUiModel>,
        ordering: List<PolicyOrderingUiModel>,
    ): String {
        val parts = mutableListOf<String>()

        if (ordering.isNotEmpty()) {
            val criteriaNames = ordering.map { it.label }
            parts.add("Ranks candidate paths by ${criteriaNames.joinToString(" then ")}.")
        } else {
            parts.add("Selects optimal SCION paths.")
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
        return ParsedPathPolicy(
            name = name,
            isDefaultPolicy = true,
            summaryDescription = "Automatically evaluates all reachable SCION paths and chooses the optimal route by minimizing latency and hop count.",
            matchers = listOf(
                PolicyMatcherUiModel(
                    source = "Any",
                    destination = "Any",
                    protocol = "All protocols",
                    trafficClass = null,
                    policy = name
                )
            ),
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
            rawJson = rawJson.ifBlank {
                """
                {
                  "matchers": [
                    {
                      "source": "",
                      "protocol": "all",
                      "policy": "$name"
                    }
                  ],
                  "policies": {
                    "$name": {
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
        )
    }
}
