package com.wireguard.android.model

/**
 * Root data structure representing a complete scitra-policy.json configuration file.
 */
data class ScitraPolicyConfig(
    val matchers: List<ScitraMatcher> = emptyList(),
    val policies: Map<String, ScitraPolicyEntry> = emptyMap(),
    val legacyApps: PolicyAppFilterUiModel? = null,
) {
    fun hasPolicies(): Boolean = policies.isNotEmpty()
    fun hasMatchers(): Boolean = matchers.isNotEmpty()
}

/**
 * Conditions that a packet or flow must satisfy in order for the policy attached
 * to become active. Matches document order until first match is found.
 */
data class ScitraMatcher(
    val policy: String,
    val source: String? = null,
    val destination: String? = null,
    val protocol: String? = null, // "tcp" or "udp"
    val trafficClass: Int? = null, // 6-bit DSCP (0..63)
    val appName: String? = null, // Application package name or identifier (e.g. "com.android.chrome")
    val appUid: Int? = null, // Optional application UID
)

/**
 * Filtering rules and ordering applied to paths.
 */
data class ScitraPolicyEntry(
    val extends: String? = null,
    val failover: String? = null,
    val acl: List<String> = emptyList(), // Hop predicates preceded by + or -
    val sequence: String? = null, // Regular expression of hop predicates
    val requirements: ScitraRequirements = ScitraRequirements(),
    val ordering: List<String> = emptyList(), // Sort order rules
    val selector: String? = null, // Dynamic path selector (e.g. "lowest_rtt")
)

data class ScitraRequirements(
    val minMtu: Int? = null,
    val maxMetaLat: Int? = null,
    val minMetaBw: Long? = null,
) {
    val hasConstraints: Boolean
        get() = minMtu != null || maxMetaLat != null || minMetaBw != null
}

// =========================================================================
// UI Representation Models (Used by Fragments, Adapters and ViewModels)
// =========================================================================

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
    val selector: String? = null,
    val rawJson: String = "",
)

data class PolicyMatcherUiModel(
    val source: String? = null,
    val destination: String? = null,
    val protocol: String? = null,
    val trafficClass: Int? = null,
    val policy: String? = null,
    val appName: String? = null,
    val appUid: Int? = null,
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

