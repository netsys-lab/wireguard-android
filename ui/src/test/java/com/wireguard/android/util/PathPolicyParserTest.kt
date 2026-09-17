package com.wireguard.android.util

import com.wireguard.android.model.AppFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
