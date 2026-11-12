/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import com.wireguard.util.NonNullForAll;

import androidx.annotation.Nullable;

/**
 * The app that owns the socket of a flow, as determined by {@link FlowOwnerTracker}.
 * Immutable.
 */
@NonNullForAll
public final class FlowOwner {
    /** UID value used while no owner is known. */
    public static final int UID_NONE = -1;

    private final int uid;
    private final Status status;
    @Nullable private final String reason;
    private final long resolvedAtMillis;

    private FlowOwner(final int uid, final Status status, @Nullable final String reason, final long resolvedAtMillis) {
        this.uid = uid;
        this.status = status;
        this.reason = reason;
        this.resolvedAtMillis = resolvedAtMillis;
    }

    /** Owner found: {@code uid} is the Android UID of the app. */
    public static FlowOwner resolved(final int uid, final long nowMillis) {
        return new FlowOwner(uid, Status.RESOLVED, null, nowMillis);
    }

    /**
     * Lookup finished without an owner.
     *
     * @param reason short machine-readable reason, e.g. {@code no_socket} or an exception name
     */
    public static FlowOwner unknown(final String reason, final long nowMillis) {
        return new FlowOwner(UID_NONE, Status.UNKNOWN, reason, nowMillis);
    }

    public int getUid() {
        return uid;
    }

    public Status getStatus() {
        return status;
    }

    @Nullable
    public String getReason() {
        return reason;
    }

    public long getResolvedAtMillis() {
        return resolvedAtMillis;
    }

    @Override
    public String toString() {
        return "FlowOwner{uid=" + uid + ", status=" + status + (reason != null ? ", reason=" + reason : "") + '}';
    }

    /** Result of the owner lookup. */
    public enum Status {
        RESOLVED,
        UNKNOWN
    }
}
