/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.util.Log;

import com.wireguard.util.NonNullForAll;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.Nullable;

/**
 * Attributes flows to the app that owns their socket.
 *
 * <p>Android can only tell the owner of a socket while the socket still exists. The Go
 * backend therefore reports every new TCP/UDP flow immediately (wgWaitNewFlows). This
 * tracker runs one worker thread per tunnel start that blocks in the backend, looks up the
 * owner for each new flow and keeps the result in a table <i>flow id → {@link FlowOwner}</i>.
 * The table lives only in memory and is discarded when tracking stops.
 *
 * <p>The tracker is bound to the tunnel, not to any screen, so flows are attributed even
 * while the app UI is closed.
 */
@NonNullForAll
public final class FlowOwnerTracker {
    private static final String TAG = "WireGuard/FlowOwner";

    static final int WAIT_TIMEOUT_MS = 500;
    static final int MAX_EVENTS_PER_CALL = 256;
    /** Upper bound for the owner table until flows expire in the backend. */
    static final int MAX_ENTRIES = 50_000;
    private static final long STATS_INTERVAL_MS = 60_000;
    /** If a wait returns faster than this without events, back off to avoid spinning. */
    private static final long MIN_EMPTY_WAIT_MS = 50;
    private static final long EMPTY_BACKOFF_MS = 100;
    private static final long ERROR_BACKOFF_MS = 1_000;

    private final NativeBridge bridge;
    private final OwnerLookup lookup;
    @Nullable private volatile Worker worker;

    public FlowOwnerTracker(final NativeBridge bridge, final OwnerLookup lookup) {
        this.bridge = bridge;
        this.lookup = lookup;
    }

    /**
     * Starts tracking for the given tunnel handle. Any previous worker is stopped first.
     *
     * @return {@code false} if the backend refused to report new flows (e.g. mock tunnel)
     */
    public synchronized boolean start(final int tunnelHandle) {
        final Worker previous = worker;
        stop();
        // Tunnel handles are reused. Wait for the old worker to leave its blocking call,
        // otherwise it could consume events of the new tunnel (at most one wait timeout).
        if (previous != null)
            joinQuietly(previous.thread, WAIT_TIMEOUT_MS * 2L);
        if (bridge.setNewFlowEventsEnabled(tunnelHandle, true) != 0) {
            logW("Backend refused new-flow events for handle=" + tunnelHandle + ", owner tracking off");
            return false;
        }
        final Worker w = new Worker(tunnelHandle);
        worker = w;
        w.thread.start();
        logI("Owner tracking started for handle=" + tunnelHandle);
        return true;
    }

    /**
     * Stops tracking and discards the owner table. Must be called before the tunnel's
     * backend device is closed. Safe to call when not running.
     */
    public synchronized void stop() {
        final Worker w = worker;
        if (w == null)
            return;
        worker = null;
        w.running = false;
        try {
            bridge.setNewFlowEventsEnabled(w.handle, false);
        } catch (final Throwable t) {
            logW("Disabling new-flow events failed: " + t);
        }
        w.logStats("stopped");
        logI("Owner tracking stopped for handle=" + w.handle);
    }

    /** Whether a worker is currently running. */
    public boolean isActive() {
        final Worker w = worker;
        return w != null && w.running;
    }

    /** Copy of the current owner table (flow id → owner). Empty when not tracking. */
    public Map<Long, FlowOwner> getOwners() {
        final Worker w = worker;
        if (w == null)
            return Collections.emptyMap();
        return Collections.unmodifiableMap(new HashMap<>(w.owners));
    }

    /**
     * Removes entries of flows that no longer exist. {@code flowIds} is a snapshot of the
     * backend's flow list; because flow ids only grow, entries with an id above the largest
     * id in the snapshot belong to flows created after the snapshot and are kept.
     */
    public void retainOnly(final Set<Long> flowIds) {
        final Worker w = worker;
        if (w == null)
            return;
        long maxId = -1;
        for (final Long id : flowIds)
            maxId = Math.max(maxId, id);
        final long limit = maxId;
        w.owners.keySet().removeIf(id -> id <= limit && !flowIds.contains(id));
    }

    /** Counters of the running worker, or {@code null} when not tracking. */
    @Nullable
    public Stats getStats() {
        final Worker w = worker;
        return w == null ? null : w.snapshotStats();
    }

    private static void joinQuietly(final Thread thread, final long millis) {
        if (thread == Thread.currentThread())
            return;
        try {
            thread.join(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- logging that also works in local unit tests (android.util.Log is not mocked there) ---

    private static void logI(final String msg) {
        try {
            Log.i(TAG, msg);
        } catch (final RuntimeException ignored) {
        }
    }

    private static void logD(final String msg) {
        try {
            Log.d(TAG, msg);
        } catch (final RuntimeException ignored) {
        }
    }

    private static void logW(final String msg) {
        try {
            Log.w(TAG, msg);
        } catch (final RuntimeException ignored) {
        }
    }

    /** Access to the Go backend; implemented by {@link GoBackend} with JNI calls. */
    public interface NativeBridge {
        /** @return 0 on success, non-zero if the backend cannot report new flows */
        int setNewFlowEventsEnabled(int tunnelHandle, boolean enabled);

        /** Blocks up to {@code timeoutMs}; returns the JSON from wgWaitNewFlows or {@code null}. */
        @Nullable
        String waitNewFlows(int tunnelHandle, int timeoutMs, int maxEvents);
    }

    /** Socket owner lookup; implemented with ConnectivityManager on Android 10+. */
    public interface OwnerLookup {
        /**
         * @param protocol 6 (TCP) or 17 (UDP)
         * @return the owner UID, or a negative value if no socket was found
         * @throws Exception if the lookup is not possible (e.g. SecurityException)
         */
        int lookup(int protocol, String localIp, int localPort, String remoteIp, int remotePort) throws Exception;
    }

    /** Counters for logs and diagnostics. */
    public static final class Stats {
        public final long events;
        public final long resolved;
        public final long unknown;
        public final long dropped;
        public final int tableSize;

        Stats(final long events, final long resolved, final long unknown, final long dropped, final int tableSize) {
            this.events = events;
            this.resolved = resolved;
            this.unknown = unknown;
            this.dropped = dropped;
            this.tableSize = tableSize;
        }

        @Override
        public String toString() {
            return "events=" + events + " resolved=" + resolved + " unknown=" + unknown
                    + " dropped=" + dropped + " table=" + tableSize;
        }
    }

    /**
     * One worker per tunnel start. Each worker has its own owner table, so entries of a
     * previous tunnel (whose flow ids restart at 1) can never leak into a new one.
     */
    final class Worker implements Runnable {
        final int handle;
        final Thread thread;
        final ConcurrentHashMap<Long, FlowOwner> owners = new ConcurrentHashMap<>();
        volatile boolean running = true;
        private final AtomicLong events = new AtomicLong();
        private final AtomicLong resolved = new AtomicLong();
        private final AtomicLong unknown = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private long lastStatsLogMillis = System.currentTimeMillis();

        Worker(final int handle) {
            this.handle = handle;
            thread = new Thread(this, "FlowOwnerTracker-" + handle);
            thread.setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                final long start = System.currentTimeMillis();
                final String json;
                try {
                    json = bridge.waitNewFlows(handle, WAIT_TIMEOUT_MS, MAX_EVENTS_PER_CALL);
                } catch (final Throwable t) {
                    logW("wgWaitNewFlows failed: " + t);
                    sleepQuietly(ERROR_BACKOFF_MS);
                    continue;
                }
                if (json == null) {
                    sleepQuietly(EMPTY_BACKOFF_MS);
                    continue;
                }
                final int handled = handleResponse(json);
                if (handled < 0)
                    break;
                if (handled == 0 && System.currentTimeMillis() - start < MIN_EMPTY_WAIT_MS)
                    sleepQuietly(EMPTY_BACKOFF_MS);
                maybeLogStats();
            }
            running = false;
        }

        /**
         * Processes one wgWaitNewFlows response.
         *
         * @return number of events handled, or -1 if the worker must stop
         */
        int handleResponse(final String json) {
            final JSONObject obj;
            try {
                obj = new JSONObject(json);
            } catch (final JSONException e) {
                logW("Invalid wgWaitNewFlows response: " + e.getMessage());
                return 0;
            }
            final String error = obj.optString("error", "");
            if ("device_not_found".equals(error)) {
                logI("Backend device gone for handle=" + handle + ", stopping owner tracking");
                return -1;
            }
            final long droppedNow = obj.optLong("dropped", 0);
            if (droppedNow > 0) {
                dropped.addAndGet(droppedNow);
                logD("Backend dropped " + droppedNow + " new-flow events (queue full)");
            }
            final JSONArray arr = obj.optJSONArray("events");
            if (arr == null)
                return 0;
            int count = 0;
            for (int i = 0; i < arr.length() && running; i++) {
                final JSONObject ev = arr.optJSONObject(i);
                if (ev == null)
                    continue;
                handleEvent(ev);
                count++;
            }
            return count;
        }

        private void handleEvent(final JSONObject ev) {
            events.incrementAndGet();
            final long flowId = ev.optLong("flowId", -1);
            if (flowId < 0)
                return;
            final long now = System.currentTimeMillis();
            FlowOwner owner;
            try {
                final int uid = lookup.lookup(
                        ev.optInt("protocol"),
                        ev.optString("localIP"), ev.optInt("localPort"),
                        ev.optString("remoteIP"), ev.optInt("remotePort"));
                owner = uid >= 0 ? FlowOwner.resolved(uid, now) : FlowOwner.unknown("no_socket", now);
            } catch (final Exception e) {
                owner = FlowOwner.unknown(e.getClass().getSimpleName(), now);
            }
            if (owner.getStatus() == FlowOwner.Status.RESOLVED)
                resolved.incrementAndGet();
            else
                unknown.incrementAndGet();
            if (!running || owners.size() >= MAX_ENTRIES)
                return;
            owners.put(flowId, owner);
        }

        Stats snapshotStats() {
            return new Stats(events.get(), resolved.get(), unknown.get(), dropped.get(), owners.size());
        }

        void logStats(final String prefix) {
            logD("Owner tracking " + prefix + ": " + snapshotStats());
        }

        private void maybeLogStats() {
            final long now = System.currentTimeMillis();
            if (now - lastStatsLogMillis >= STATS_INTERVAL_MS) {
                lastStatsLogMillis = now;
                logStats("stats");
            }
        }

        private void sleepQuietly(final long millis) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }
}
