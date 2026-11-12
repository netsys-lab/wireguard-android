/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

/**
 * Whether flow owners (apps) are currently being tracked by the backend.
 */
public enum FlowOwnerTrackingState {
    /** Tracking runs: new TCP/UDP flows are attributed to apps. */
    ACTIVE,
    /** Tunnel is not running or tracking could not be started. */
    INACTIVE,
    /** Not possible on this device/backend (e.g. Android below 10, wg-quick backend). */
    UNSUPPORTED
}
