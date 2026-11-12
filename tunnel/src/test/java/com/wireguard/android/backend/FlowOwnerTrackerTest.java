/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FlowOwnerTrackerTest {

    /** Fake backend: responses are handed out one per wait call; otherwise behaves like an empty wait. */
    private static final class FakeBridge implements FlowOwnerTracker.NativeBridge {
        final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        volatile int enableResult = 0;
        volatile Boolean lastEnabled;
        volatile int waitCalls;

        @Override
        public int setNewFlowEventsEnabled(final int tunnelHandle, final boolean enabled) {
            lastEnabled = enabled;
            return enableResult;
        }

        @Override
        public String waitNewFlows(final int tunnelHandle, final int timeoutMs, final int maxEvents) {
            waitCalls++;
            try {
                final String r = responses.poll(timeoutMs, TimeUnit.MILLISECONDS);
                return r != null ? r : "{\"events\":[],\"dropped\":0}";
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static String event(final long flowId, final int protocol, final int localPort) {
        return "{\"flowId\":" + flowId + ",\"protocol\":" + protocol
                + ",\"localIP\":\"10.0.0.2\",\"localPort\":" + localPort
                + ",\"remoteIP\":\"1.1.1.1\",\"remotePort\":443}";
    }

    private static String response(final String... events) {
        return "{\"events\":[" + String.join(",", events) + "],\"dropped\":0}";
    }

    /** Lookup: port 1000 → uid 10123, port 2000 → no socket, port 3000 → SecurityException. */
    private static final FlowOwnerTracker.OwnerLookup LOOKUP = (protocol, localIp, localPort, remoteIp, remotePort) -> {
        if (localPort == 1000)
            return 10123;
        if (localPort == 3000)
            throw new SecurityException("not the active VPN");
        return -1;
    };

    private static Map<Long, FlowOwner> awaitOwners(final FlowOwnerTracker tracker, final int count) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 3000;
        Map<Long, FlowOwner> owners = tracker.getOwners();
        while (owners.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            owners = tracker.getOwners();
        }
        return owners;
    }

    @Test
    public void resolvesOwnersFromEvents() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        assertTrue(tracker.start(7));
        assertEquals(Boolean.TRUE, bridge.lastEnabled);

        bridge.responses.add(response(event(1, 6, 1000), event(2, 17, 2000), event(3, 6, 3000)));
        final Map<Long, FlowOwner> owners = awaitOwners(tracker, 3);

        assertEquals(3, owners.size());
        assertEquals(FlowOwner.Status.RESOLVED, owners.get(1L).getStatus());
        assertEquals(10123, owners.get(1L).getUid());
        assertEquals(FlowOwner.Status.UNKNOWN, owners.get(2L).getStatus());
        assertEquals("no_socket", owners.get(2L).getReason());
        assertEquals(FlowOwner.Status.UNKNOWN, owners.get(3L).getStatus());
        assertEquals("SecurityException", owners.get(3L).getReason());

        final FlowOwnerTracker.Stats stats = tracker.getStats();
        assertNotNull(stats);
        assertEquals(3, stats.events);
        assertEquals(1, stats.resolved);
        assertEquals(2, stats.unknown);
        tracker.stop();
    }

    @Test
    public void countsDroppedEvents() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        tracker.start(1);
        bridge.responses.add("{\"events\":[" + event(5, 6, 1000) + "],\"dropped\":4}");
        awaitOwners(tracker, 1);
        final FlowOwnerTracker.Stats stats = tracker.getStats();
        assertNotNull(stats);
        assertEquals(4, stats.dropped);
        tracker.stop();
    }

    @Test
    public void deviceNotFoundStopsWorker() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        tracker.start(1);
        bridge.responses.add("{\"events\":[],\"dropped\":0,\"error\":\"device_not_found\"}");

        final long deadline = System.currentTimeMillis() + 3000;
        while (tracker.isActive() && System.currentTimeMillis() < deadline)
            Thread.sleep(10);
        assertFalse(tracker.isActive());
        tracker.stop();
    }

    @Test
    public void stopDisablesEventsAndDiscardsTable() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        tracker.start(1);
        bridge.responses.add(response(event(1, 6, 1000)));
        awaitOwners(tracker, 1);

        tracker.stop();
        assertEquals(Boolean.FALSE, bridge.lastEnabled);
        assertFalse(tracker.isActive());
        assertTrue(tracker.getOwners().isEmpty());
        assertNull(tracker.getStats());
    }

    @Test
    public void restartStartsWithEmptyTable() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        tracker.start(1);
        bridge.responses.add(response(event(1, 6, 1000)));
        awaitOwners(tracker, 1);

        // Same handle again (handles are reused): flow ids restart, old entries must be gone.
        tracker.start(1);
        assertTrue(tracker.getOwners().isEmpty());
        tracker.stop();
    }

    @Test
    public void refusedStartDoesNotRunWorker() {
        final FakeBridge bridge = new FakeBridge();
        bridge.enableResult = -1;
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        assertFalse(tracker.start(1));
        assertFalse(tracker.isActive());
        assertTrue(tracker.getOwners().isEmpty());
    }

    @Test
    public void retainOnlyRemovesOtherFlows() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        tracker.start(1);
        bridge.responses.add(response(event(1, 6, 1000), event(2, 6, 1000), event(3, 6, 1000)));
        awaitOwners(tracker, 3);

        // Snapshot knows flows up to id 2; flow 1 is gone, flow 3 is newer than the snapshot.
        tracker.retainOnly(new HashSet<>(Collections.singletonList(2L)));
        final Map<Long, FlowOwner> owners = tracker.getOwners();
        assertEquals(2, owners.size());
        assertTrue(owners.containsKey(2L));
        assertTrue("entries newer than the snapshot must be kept", owners.containsKey(3L));
        tracker.stop();
    }

    @Test
    public void invalidJsonIsIgnored() throws Exception {
        final FakeBridge bridge = new FakeBridge();
        final FlowOwnerTracker tracker = new FlowOwnerTracker(bridge, LOOKUP);
        tracker.start(1);
        bridge.responses.add("not json");
        bridge.responses.add(response(event(9, 6, 1000)));
        final Map<Long, FlowOwner> owners = awaitOwners(tracker, 1);
        assertTrue(tracker.isActive());
        assertEquals(10123, owners.get(9L).getUid());
        tracker.stop();
    }
}
