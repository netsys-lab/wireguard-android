/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.util

import android.util.Log

object FlowLog {
    const val TAG = "WireGuard/FlowDebug"

    fun d(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
            println("$TAG: $msg")
        }
    }

    fun i(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
            println("$TAG: $msg")
        }
    }

    fun w(msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
        } catch (_: Throwable) {
            System.err.println("$TAG [WARN]: $msg")
            tr?.printStackTrace()
        }
    }

    fun e(msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
        } catch (_: Throwable) {
            System.err.println("$TAG [ERROR]: $msg")
            tr?.printStackTrace()
        }
    }
}
