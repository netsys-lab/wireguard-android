/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigTest {

    @Test(expected = BadConfigException.class)
    public void invalid_config_throws() throws IOException, BadConfigException {
        try (final InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("broken.conf")) {
            Config.parse(is);
        }
    }

    @Test
    public void valid_config_parses_correctly() throws IOException, ParseException {
        Config config = null;
        final Collection<InetNetwork> expectedAllowedIps = new HashSet<>(Arrays.asList(InetNetwork.parse("0.0.0.0/0"), InetNetwork.parse("::0/0")));
        try (final InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("working.conf")) {
            config = Config.parse(is);
        } catch (final BadConfigException e) {
            fail("'working.conf' should never fail to parse");
        }
        assertNotNull("config cannot be null after parsing", config);
        assertTrue(
                "No applications should be excluded by default",
                config.getInterface().getExcludedApplications().isEmpty()
        );
        assertEquals("Test config has exactly one peer", 1, config.getPeers().size());
        assertEquals("Test config's allowed IPs are 0.0.0.0/0 and ::0/0", config.getPeers().get(0).getAllowedIps(), expectedAllowedIps);
        assertEquals("Test config has one DNS server", 1, config.getInterface().getDnsServers().size());
    }

    @Test
    public void multiline_path_policy_parses_and_preserves_tunnel_config() throws IOException, ParseException {
        final String rawConf = "[Interface]\n" +
                "# TunnelMode = SCION\n" +
                "# PathPolicy = {\n" +
                "  \"policies\": {\n" +
                "    \"p1\": {\n" +
                "      \"name\": \"Jonas\"\n" +
                "    }\n" +
                "  }\n" +
                "}\n" +
                "Address = 10.0.0.1/32\n" +
                "PrivateKey = aAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEE=\n" +
                "DNS = 1.1.1.1\n";

        try (final java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(rawConf.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            final Config config = Config.parse(bais);
            assertNotNull(config);
            assertEquals(1, config.getInterface().getAddresses().size());
            assertEquals(1, config.getInterface().getDnsServers().size());
            assertTrue("Path policy should contain Jonas", config.getInterface().getPathPolicy().contains("Jonas"));

            // Verify round-trip serializes on a single line
            final String wgQuick = config.toWgQuickString();
            assertTrue(wgQuick.contains("# PathPolicy = "));
            // Ensure no raw JSON line breaks inside Interface
            final String[] lines = wgQuick.split("\n");
            for (final String l : lines) {
                final String trimmed = l.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) continue;
                assertTrue("Non-comment, non-empty line must contain '=': " + trimmed, trimmed.contains("="));
            }

            // Verify parsing the serialized string back
            try (final java.io.ByteArrayInputStream roundTripStream = new java.io.ByteArrayInputStream(wgQuick.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                final Config roundTripConfig = Config.parse(roundTripStream);
                assertNotNull(roundTripConfig);
                assertEquals(config.getInterface().getAddresses(), roundTripConfig.getInterface().getAddresses());
                assertEquals(config.getInterface().getKeyPair().getPrivateKey(), roundTripConfig.getInterface().getKeyPair().getPrivateKey());
                assertTrue(roundTripConfig.getInterface().getPathPolicy().contains("Jonas"));
            }
        }
    }
}

