package com.wireguard.android.util

import com.wireguard.android.model.AppFilterMode
import com.wireguard.android.model.ScitraMatcher
import com.wireguard.android.model.ScitraPolicyConfig
import com.wireguard.android.model.ScitraPolicyEntry
import com.wireguard.android.model.ScitraRequirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathPolicyParserTest {

    @Test
    fun `parse null or empty string returns default policy`() {
        val policy = PathPolicyParser.parse(null, "LowestLatency")
        assertEquals("LowestLatency", policy.name)
        assertTrue(policy.isDefaultPolicy)
        assertEquals(AppFilterMode.ALL, policy.appFilter.mode)
        assertEquals(2, policy.ordering.size)
        assertEquals("Lowest Latency", policy.ordering[0].label)
        assertEquals("Fewest Hops", policy.ordering[1].label)
        assertEquals(1, policy.aclRules.size)
        assertTrue(policy.aclRules[0].isAllow)
    }

    @Test
    fun `parse full policy json structures all components`() {
        val json = """
        {
          "matchers": [
            {
              "source": "1-64512",
              "destination": "2-ff00:0:110",
              "protocol": "udp",
              "traffic_class": 1,
              "policy": "p1"
            }
          ],
          "policies": {
            "p1": {
              "extends": "default",
              "requirements": {
                "min_mtu": 1420,
                "max_latency": 45,
                "min_meta_bw": 100000
              },
              "acl": [
                "- 666",
                "+ 1-ff00:0:110",
                "+"
              ],
              "ordering": ["meta_latency_asc", "meta_bw_desc", "hops_asc", "random"]
            }
          },
          "apps": {
            "mode": "include",
            "packages": ["com.android.chrome", "org.mozilla.firefox"]
          }
        }
        """.trimIndent()

        val policy = PathPolicyParser.parse(json, "p1")
        assertEquals("p1", policy.name)
        assertEquals("default", policy.extends)
        assertFalse(policy.isDefaultPolicy)

        // Matchers
        assertEquals(1, policy.matchers.size)
        val matcher = policy.matchers[0]
        assertEquals("1-64512", matcher.source)
        assertEquals("2-ff00:0:110", matcher.destination)
        assertEquals("udp", matcher.protocol)
        assertEquals(1, matcher.trafficClass)

        // Apps
        assertEquals(AppFilterMode.INCLUDE, policy.appFilter.mode)
        assertEquals(2, policy.appFilter.packages.size)
        assertTrue(policy.appFilter.packages.contains("com.android.chrome"))

        // Requirements
        assertEquals(1420, policy.requirements.minMtu)
        assertEquals(45, policy.requirements.maxLatencyMs)
        assertEquals(100000L, policy.requirements.minBwKbps)
        assertTrue(policy.requirements.hasConstraints)

        // ACL Rules
        assertEquals(3, policy.aclRules.size)
        assertFalse(policy.aclRules[0].isAllow)
        assertEquals("- 666", policy.aclRules[0].pattern)
        assertTrue(policy.aclRules[1].isAllow)
        assertEquals("+ 1-ff00:0:110", policy.aclRules[1].pattern)
        assertTrue(policy.aclRules[2].isAllow)
        assertEquals("+", policy.aclRules[2].pattern)

        // Ordering
        assertEquals(4, policy.ordering.size)
        assertEquals("Lowest Latency", policy.ordering[0].label)
        assertEquals("Highest Bandwidth", policy.ordering[1].label)
        assertEquals("Fewest Hops", policy.ordering[2].label)
        assertEquals("Random Load Balancing", policy.ordering[3].label)

        // Summary description contains key elements
        assertTrue(policy.summaryDescription.contains("Lowest Latency"))
        assertTrue(policy.summaryDescription.contains("1420"))
        assertTrue(policy.summaryDescription.contains("666"))
        assertTrue(policy.summaryDescription.contains("2 selected app"))
    }

    @Test
    fun `parse corrupted json falls back to default policy safely`() {
        val corrupted = "{ not-valid json ;;"
        val policy = PathPolicyParser.parse(corrupted, "FallbackName")
        assertEquals("FallbackName", policy.name)
        assertTrue(policy.isDefaultPolicy)
        assertNotNull(policy.ordering)
    }

    @Test
    fun `parseConfig correctly parses manpage example with multiple matchers and policies`() {
        val json = """
        {
          "matchers": [
            {
              "source": "1-64512,127.0.0.1",
              "protocol": "udp",
              "traffic_class": 1,
              "policy": "p1"
            },
            {
              "destination": "[1-ff00:0:1,10.0.0.1]:22",
              "source": "1-64512",
              "protocol": "tcp",
              "policy": "p2"
            },
            {
              "destination": "[1-ff00:0:1,10.0.0.1]:80",
              "source": "1-64512",
              "protocol": "tcp",
              "policy": "p3"
            }
          ],
          "policies": {
            "default": {
              "acl": [
                "- 666",
                "+"
              ],
              "ordering": ["random", "hops_asc", "meta_bandwidth_desc"]
            },
            "p1": {
              "extends": "default",
              "requirements": {
                "min_mtu": 1420
              },
              "ordering": ["meta_latency_asc"]
            },
            "p2": {
              "extends": "default",
              "sequence": "1-64512#20 0*"
            },
            "p3": {
              "extends": "p2",
              "failover": "p2",
              "requirements": {
                "min_mtu": 1500
              }
            }
          }
        }
        """.trimIndent()

        val config = PathPolicyParser.parseConfig(json)

        // Verify matchers
        assertEquals(3, config.matchers.size)
        assertEquals("p1", config.matchers[0].policy)
        assertEquals("1-64512,127.0.0.1", config.matchers[0].source)
        assertEquals("udp", config.matchers[0].protocol)
        assertEquals(1, config.matchers[0].trafficClass)

        assertEquals("p2", config.matchers[1].policy)
        assertEquals("[1-ff00:0:1,10.0.0.1]:22", config.matchers[1].destination)
        assertEquals("1-64512", config.matchers[1].source)
        assertEquals("tcp", config.matchers[1].protocol)

        assertEquals("p3", config.matchers[2].policy)

        // Verify policies and document order
        assertEquals(4, config.policies.size)
        val policyNames = config.policies.keys.toList()
        assertEquals(listOf("default", "p1", "p2", "p3"), policyNames)

        val defaultPolicy = config.policies["default"]!!
        assertEquals(listOf("- 666", "+"), defaultPolicy.acl)
        assertEquals(listOf("random", "hops_asc", "meta_bandwidth_desc"), defaultPolicy.ordering)

        val p1 = config.policies["p1"]!!
        assertEquals("default", p1.extends)
        assertEquals(1420, p1.requirements.minMtu)
        assertEquals(listOf("meta_latency_asc"), p1.ordering)

        val p2 = config.policies["p2"]!!
        assertEquals("default", p2.extends)
        assertEquals("1-64512#20 0*", p2.sequence)

        val p3 = config.policies["p3"]!!
        assertEquals("p2", p3.extends)
        assertEquals("p2", p3.failover)
        assertEquals(1500, p3.requirements.minMtu)

        // Verify validation passes
        val issues = PathPolicyParser.validateConfig(config)
        assertTrue("Expected no validation issues, but got: $issues", issues.isEmpty())
    }

    @Test
    fun `parseConfig supports app_name and app_uid and selector`() {
        val json = """
        {
          "matchers": [
            {
              "app_name": "com.android.chrome",
              "app_uid": 10042,
              "protocol": "tcp",
              "policy": "web_policy"
            }
          ],
          "policies": {
            "web_policy": {
              "selector": "lowest_rtt",
              "requirements": {
                "max_meta_lat": 60,
                "min_meta_bw": 50000
              }
            }
          }
        }
        """.trimIndent()

        val config = PathPolicyParser.parseConfig(json)
        assertEquals(1, config.matchers.size)
        val m = config.matchers[0]
        assertEquals("com.android.chrome", m.appName)
        assertEquals(10042, m.appUid)
        assertEquals("tcp", m.protocol)
        assertEquals("web_policy", m.policy)

        val p = config.policies["web_policy"]!!
        assertEquals("lowest_rtt", p.selector)
        assertEquals(60, p.requirements.maxMetaLat)
        assertEquals(50000L, p.requirements.minMetaBw)
    }

    @Test
    fun `serializeConfig round-trip preserves all fields`() {
        val originalConfig = ScitraPolicyConfig(
            matchers = listOf(
                ScitraMatcher(
                    policy = "video",
                    appName = "com.google.android.youtube",
                    appUid = 10123,
                    destination = "[1-ff00:0:1,10.0.0.1]:443",
                    protocol = "udp",
                    trafficClass = 46
                )
            ),
            policies = linkedMapOf(
                "default" to ScitraPolicyEntry(
                    acl = listOf("+"),
                    ordering = listOf("hops_asc")
                ),
                "video" to ScitraPolicyEntry(
                    extends = "default",
                    failover = "default",
                    acl = listOf("+ 1-ff00:0:1", "- 666"),
                    sequence = "1-ff00:0:1#0 0*",
                    requirements = ScitraRequirements(
                        minMtu = 1420,
                        maxMetaLat = 30,
                        minMetaBw = 200000L
                    ),
                    ordering = listOf("meta_bandwidth_desc", "meta_latency_asc"),
                    selector = "lowest_rtt"
                )
            )
        )

        val serializedJson = PathPolicyParser.serializeConfig(originalConfig)
        val roundTripConfig = PathPolicyParser.parseConfig(serializedJson)

        assertEquals(originalConfig.matchers.size, roundTripConfig.matchers.size)
        val m = roundTripConfig.matchers[0]
        assertEquals("video", m.policy)
        assertEquals("com.google.android.youtube", m.appName)
        assertEquals(10123, m.appUid)
        assertEquals("[1-ff00:0:1,10.0.0.1]:443", m.destination)
        assertEquals("udp", m.protocol)
        assertEquals(46, m.trafficClass)

        assertEquals(listOf("default", "video"), roundTripConfig.policies.keys.toList())
        val videoPolicy = roundTripConfig.policies["video"]!!
        assertEquals("default", videoPolicy.extends)
        assertEquals("default", videoPolicy.failover)
        assertEquals(listOf("+ 1-ff00:0:1", "- 666"), videoPolicy.acl)
        assertEquals("1-ff00:0:1#0 0*", videoPolicy.sequence)
        assertEquals(1420, videoPolicy.requirements.minMtu)
        assertEquals(30, videoPolicy.requirements.maxMetaLat)
        assertEquals(200000L, videoPolicy.requirements.minMetaBw)
        assertEquals(listOf("meta_bandwidth_desc", "meta_latency_asc"), videoPolicy.ordering)
        assertEquals("lowest_rtt", videoPolicy.selector)
    }

    @Test
    fun `validateConfig detects semantic errors`() {
        val badConfig = ScitraPolicyConfig(
            matchers = listOf(
                ScitraMatcher(policy = "default"), // Not allowed: matcher pointing directly to default
                ScitraMatcher(policy = "non_existent"), // Target doesn't exist
                ScitraMatcher(policy = "p1", protocol = "icmp", trafficClass = 99) // Invalid proto & DSCP
            ),
            policies = linkedMapOf(
                "p1" to ScitraPolicyEntry(
                    extends = "p2", // Invalid: p2 does not precede p1 in document order
                    failover = "unknown_failover"
                ),
                "p2" to ScitraPolicyEntry(
                    extends = "p2" // Invalid: self-extension
                )
            )
        )

        val issues = PathPolicyParser.validateConfig(badConfig)
        assertTrue(issues.any { it.contains("directly targeting the reserved 'default'") || it.contains("'default'") })
        assertTrue(issues.any { it.contains("undefined policy 'non_existent'") })
        assertTrue(issues.any { it.contains("Protocol 'icmp' is invalid") })
        assertTrue(issues.any { it.contains("Traffic class (DSCP) must be between 0 and 63") })
        assertTrue(issues.any { it.contains("must precede 'p1' in document order") })
        assertTrue(issues.any { it.contains("Cannot extend itself") })
        assertTrue(issues.any { it.contains("Failover targets unknown policy 'unknown_failover'") })
    }
}
