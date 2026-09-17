package com.wireguard.android.model

data class ParsedPathPolicy(
    val name: String,
    val extends: String? = null,
    val failover: String? = null,
    val isDefaultPolicy: Boolean = false,
    val summaryDescription: String = "",
    val matchers: List<PolicyMatcherUiModel> = emptyList(),
    val appFilter: PolicyAppFilterUiModel = PolicyAppFilterUiModel(),
    val requirements: PolicyRequirementsUiModel = PolicyRequirementsUiModel(),
    val aclRules: List<PolicyAclRuleUiModel> = emptyList(),
    val ordering: List<PolicyOrderingUiModel> = emptyList(),
    val rawJson: String = "",
)

data class PolicyMatcherUiModel(
    val source: String? = null,
    val destination: String? = null,
    val protocol: String? = null,
    val trafficClass: Int? = null,
    val policy: String? = null,
)

data class PolicyAppFilterUiModel(
    val mode: AppFilterMode = AppFilterMode.ALL,
    val packages: List<String> = emptyList(),
)

enum class AppFilterMode {
    ALL,
    INCLUDE,
    EXCLUDE,
}

data class PolicyRequirementsUiModel(
    val minMtu: Int? = null,
    val maxLatencyMs: Int? = null,
    val minBwKbps: Long? = null,
) {
    val hasConstraints: Boolean
        get() = minMtu != null || maxLatencyMs != null || minBwKbps != null
}

data class PolicyAclRuleUiModel(
    val isAllow: Boolean,
    val pattern: String,
    val description: String,
)

data class PolicyOrderingUiModel(
    val key: String,
    val direction: String?,
    val label: String,
    val detail: String,
)
