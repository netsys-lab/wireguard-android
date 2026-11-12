/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.config.BadConfigException.Location;
import com.wireguard.config.BadConfigException.Reason;
import com.wireguard.config.BadConfigException.Section;
import com.wireguard.util.NonNullForAll;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import androidx.annotation.Nullable;

/**
 * Represents the contents of a wg-quick configuration file, made up of one or more "Interface"
 * sections (combined together), and zero or more "Peer" sections (treated individually).
 * <p>
 * Instances of this class are immutable.
 */
@NonNullForAll
public final class Config {
    private final Interface interfaze;
    private final List<Peer> peers;

    private Config(final Builder builder) {
        interfaze = Objects.requireNonNull(builder.interfaze, "An [Interface] section is required");
        // Defensively copy to ensure immutability even if the Builder is reused.
        peers = Collections.unmodifiableList(new ArrayList<>(builder.peers));
    }

    /**
     * Parses an series of "Interface" and "Peer" sections into a {@code Config}. Throws
     * {@link BadConfigException} if the input is not well-formed or contains data that cannot
     * be parsed.
     *
     * @param stream a stream of UTF-8 text that is interpreted as a WireGuard configuration
     * @return a {@code Config} instance representing the supplied configuration
     */
    public static Config parse(final InputStream stream)
            throws IOException, BadConfigException {
        return parse(new BufferedReader(new InputStreamReader(stream)));
    }

    /**
     * Parses an series of "Interface" and "Peer" sections into a {@code Config}. Throws
     * {@link BadConfigException} if the input is not well-formed or contains data that cannot
     * be parsed.
     *
     * @param reader a BufferedReader of UTF-8 text that is interpreted as a WireGuard configuration
     * @return a {@code Config} instance representing the supplied configuration
     */
    public static Config parse(final BufferedReader reader)
            throws IOException, BadConfigException {
        final Builder builder = new Builder();
        final Collection<String> interfaceLines = new ArrayList<>();
        final Collection<String> peerLines = new ArrayList<>();
        boolean inInterfaceSection = false;
        boolean inPeerSection = false;
        boolean seenInterfaceSection = false;
        @Nullable String bootstrapUrl = null;
        @Nullable String pathPolicy = null;
        @Nullable String tunnelModeRaw = null;
        @Nullable String line;
        final StringBuilder pathPolicyBuilder = new StringBuilder();
        boolean readingMultiLinePathPolicy = false;
        int openBraces = 0;
        while ((line = reader.readLine()) != null) {
            final String trimmedLine = line.trim();

            if (readingMultiLinePathPolicy) {
                if (trimmedLine.startsWith("[") || (trimmedLine.contains("=") && !trimmedLine.startsWith("#"))) {
                    pathPolicy = pathPolicyBuilder.toString().trim();
                    readingMultiLinePathPolicy = false;
                    pathPolicyBuilder.setLength(0);
                } else {
                    final String jsonChunk = trimmedLine.startsWith("#") ? trimmedLine.substring(1).trim() : trimmedLine;
                    pathPolicyBuilder.append(' ').append(jsonChunk);
                    openBraces += countBraces(jsonChunk);
                    if (openBraces <= 0) {
                        pathPolicy = pathPolicyBuilder.toString().trim();
                        readingMultiLinePathPolicy = false;
                        pathPolicyBuilder.setLength(0);
                    }
                    continue;
                }
            }

            if (trimmedLine.startsWith("#")) {
                final String upper = trimmedLine.toUpperCase(Locale.ENGLISH);
                final int equalsIndex = trimmedLine.indexOf('=');
                if (equalsIndex != -1) {
                    final String value = trimmedLine.substring(equalsIndex + 1).trim();
                    if (upper.contains("TUNNELMODE")) {
                        tunnelModeRaw = value;
                    } else if (upper.contains("BOOTSTRAPURL")) {
                        bootstrapUrl = value;
                    } else if (upper.contains("PATHPOLICY")) {
                        pathPolicy = value;
                        if (value.startsWith("{") && !value.endsWith("}")) {
                            readingMultiLinePathPolicy = true;
                            pathPolicyBuilder.setLength(0);
                            pathPolicyBuilder.append(value);
                            openBraces = countBraces(value);
                        }
                    }
                }
            }
            final int commentIndex = line.indexOf('#');
            if (commentIndex != -1)
                line = line.substring(0, commentIndex);
            line = line.trim();
            if (line.isEmpty())
                continue;
            if (line.startsWith("[")) {
                // Consume all [Peer] lines read so far.
                if (inPeerSection) {
                    builder.parsePeer(peerLines);
                    peerLines.clear();
                }
                if ("[Interface]".equalsIgnoreCase(line)) {
                    inInterfaceSection = true;
                    inPeerSection = false;
                    seenInterfaceSection = true;
                } else if ("[Peer]".equalsIgnoreCase(line)) {
                    inInterfaceSection = false;
                    inPeerSection = true;
                } else {
                    throw new BadConfigException(Section.CONFIG, Location.TOP_LEVEL,
                            Reason.UNKNOWN_SECTION, line);
                }
            } else if (inInterfaceSection) {
                interfaceLines.add(line);
            } else if (inPeerSection) {
                peerLines.add(line);
            } else {
                throw new BadConfigException(Section.CONFIG, Location.TOP_LEVEL,
                        Reason.UNKNOWN_SECTION, line);
            }
        }
        if (readingMultiLinePathPolicy && pathPolicyBuilder.length() > 0) {
            pathPolicy = pathPolicyBuilder.toString().trim();
            readingMultiLinePathPolicy = false;
        }
        if (inPeerSection)
            builder.parsePeer(peerLines);
        if (!seenInterfaceSection)
            throw new BadConfigException(Section.CONFIG, Location.TOP_LEVEL,
                    Reason.MISSING_SECTION, null);
        // Combine all [Interface] sections in the file.
        builder.parseInterface(interfaceLines, tunnelModeRaw, bootstrapUrl, pathPolicy);
        return builder.build();
    }

    private static int countBraces(final String str) {
        int count = 0;
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < str.length(); i++) {
            final char c = str.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                if (c == '{') count++;
                else if (c == '}') count--;
            }
        }
        return count;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Config))
            return false;
        final Config other = (Config) obj;
        return interfaze.equals(other.interfaze) && peers.equals(other.peers);
    }

    /**
     * Returns the interface section of the configuration.
     *
     * @return the interface configuration
     */
    public Interface getInterface() {
        return interfaze;
    }

    /**
     * Returns a list of the configuration's peer sections.
     *
     * @return a list of {@link Peer}s
     */
    public List<Peer> getPeers() {
        return peers;
    }

    @Override
    public int hashCode() {
        return 31 * interfaze.hashCode() + peers.hashCode();
    }

    /**
     * Converts the {@code Config} into a string suitable for debugging purposes. The {@code Config}
     * is identified by its interface's public key and the number of peers it has.
     *
     * @return a concise single-line identifier for the {@code Config}
     */
    @Override
    public String toString() {
        return "(Config " + interfaze + " (" + peers.size() + " peers))";
    }

    /**
     * Converts the {@code Config} into a string suitable for use as a {@code wg-quick}
     * configuration file.
     *
     * @return the {@code Config} represented as one [Interface] and zero or more [Peer] sections
     */
    public String toWgQuickString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("[Interface]\n").append(interfaze.toWgQuickString());
        for (final Peer peer : peers)
            sb.append("\n[Peer]\n").append(peer.toWgQuickString());
        return sb.toString();
    }

    /**
     * Serializes the {@code Config} for use with the WireGuard cross-platform userspace API.
     *
     * @return the {@code Config} represented as a series of "key=value" lines
     */
    public String toWgUserspaceString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(interfaze.toWgUserspaceString());
        sb.append("replace_peers=true\n");
        for (final Peer peer : peers)
            sb.append(peer.toWgUserspaceString());
        return sb.toString();
    }

    @SuppressWarnings("UnusedReturnValue")
    public static final class Builder {
        // Defaults to an empty set.
        private final ArrayList<Peer> peers = new ArrayList<>();
        // No default; must be provided before building.
        @Nullable private Interface interfaze;

        public Builder addPeer(final Peer peer) {
            peers.add(peer);
            return this;
        }

        public Builder addPeers(final Collection<Peer> peers) {
            this.peers.addAll(peers);
            return this;
        }

        public Config build() {
            if (interfaze == null)
                throw new IllegalArgumentException("An [Interface] section is required");
            return new Config(this);
        }

        public Builder parseInterface(final Iterable<? extends CharSequence> lines)
                throws BadConfigException {
            return parseInterface(lines, null, null, null);
        }

        public Builder parseInterface(final Iterable<? extends CharSequence> lines, @Nullable final String bootstrapUrl)
                throws BadConfigException {
            return parseInterface(lines, null, bootstrapUrl, null);
        }

        public Builder parseInterface(final Iterable<? extends CharSequence> lines, @Nullable final String tunnelModeRaw, @Nullable final String bootstrapUrl, @Nullable final String pathPolicy)
                throws BadConfigException {
            return setInterface(Interface.parse(lines, tunnelModeRaw, bootstrapUrl, pathPolicy));
        }

        public Builder parsePeer(final Iterable<? extends CharSequence> lines)
                throws BadConfigException {
            return addPeer(Peer.parse(lines));
        }

        public Builder setInterface(final Interface interfaze) {
            this.interfaze = interfaze;
            return this;
        }
    }
}
