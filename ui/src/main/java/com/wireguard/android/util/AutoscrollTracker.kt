/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

/**
 * Tracks whether the log viewer should follow the latest line.
 *
 * Behavior:
 *  - When the user is at (or near) the bottom, autoscroll is enabled and new lines are followed.
 *  - When the user scrolls upward, following is paused automatically.
 *  - The user can resume manually (or by scrolling back to the bottom).
 *
 * [newLinesWhilePaused] accumulates the number of new entries that arrived while paused so the
 * UI can show a "N new lines" indicator.
 */
class AutoscrollTracker(private val thresholdFromBottom: Int = 2) {

    var enabled: Boolean = true
        private set

    var newLinesWhilePaused: Int = 0
        private set

    /** Returns the number of new lines that arrived since the last reset. */
    fun onNewData() {
        if (enabled) {
            newLinesWhilePaused = 0
        } else {
            newLinesWhilePaused++
        }
    }

    /** Called when the scroll position changes. */
    fun onScroll(firstVisible: Int, visibleCount: Int, totalCount: Int) {
        val distanceFromBottom = totalCount - (firstVisible + visibleCount)
        val atBottom = distanceFromBottom <= thresholdFromBottom
        if (atBottom) {
            enabled = true
            newLinesWhilePaused = 0
        } else if (enabled) {
            // User moved away from the bottom: pause following.
            enabled = false
        }
    }

    /** Manual resume requested by the user. */
    fun resume() {
        enabled = true
        newLinesWhilePaused = 0
    }

    /** Manual pause requested by the user. */
    fun pause() {
        enabled = false
    }

    fun reset() {
        enabled = true
        newLinesWhilePaused = 0
    }
}
