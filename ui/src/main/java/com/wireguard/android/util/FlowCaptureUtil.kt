package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.BuildConfig
import com.wireguard.android.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FlowCaptureUtil {
    private const val TAG = "FLOW_PATH_RAW"

    fun capture(rawJson: String, flowId: Long) {
        if (!BuildConfig.FLOW_CAPTURE_ENABLED) return
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(Application.get().filesDir, "flow_capture")
        dir.mkdirs()
        val file = File(dir, "flow_${flowId}_$ts.json")
        try {
            file.writeText(rawJson)
            Log.d(TAG, "Wrote flow=$flowId (${rawJson.length}c) to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write capture for flow=$flowId", e)
        }
    }

}