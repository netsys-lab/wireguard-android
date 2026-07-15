/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.R
import com.wireguard.android.databinding.DebugPacketActivityBinding
import com.wireguard.android.util.DebugPacketLogStreamer
import com.wireguard.android.util.DownloadsFileSaver
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.ScionDebugSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DebugPacketActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "WireGuard/DebugPacket"
        private const val MAX_CHAR_COUNT = 512
        private const val INSPECTOR_URL = "https://scionpacketinspector.netsec.ethz.ch/"
        private const val COLLAPSED_HEIGHT_DP = 80
        private const val EXPANDED_HEIGHT_DP = 300
    }

    private lateinit var binding: DebugPacketActivityBinding
    private var isUdp = true
    private var sendJob: Job? = null

    private val logStreamer = DebugPacketLogStreamer()
    private lateinit var logAdapter: LogAdapter
    private var isLogExpanded = false
    private val downloadsFileSaver by lazy { DownloadsFileSaver(this) }

    private lateinit var logRecyclerView: RecyclerView
    private lateinit var logEmptyText: TextView
    private lateinit var btnLogExpand: TextView
    private lateinit var btnLogSave: TextView
    private lateinit var btnLogClear: TextView

    private val debugColor by lazy { ResourcesCompat.getColor(resources, R.color.debug_tag_color, theme) }
    private val errorColor by lazy { ResourcesCompat.getColor(resources, R.color.error_tag_color, theme) }
    private val infoColor by lazy { ResourcesCompat.getColor(resources, R.color.info_tag_color, theme) }
    private val warningColor by lazy { ResourcesCompat.getColor(resources, R.color.warning_tag_color, theme) }
    private val defaultColor by lazy {
        com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DebugPacketActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logRecyclerView = findViewById(R.id.log_recycler_view)
        logEmptyText = findViewById(R.id.log_empty_text)
        btnLogExpand = findViewById(R.id.btn_log_expand)
        btnLogSave = findViewById(R.id.btn_log_save)
        btnLogClear = findViewById(R.id.btn_log_clear)

        setupTopBar()
        setupMessageInput()
        setupProtocolToggle()
        setupSendButton()
        setupLogPanel()
        updateCharCount()
        updateButtonState()

        lifecycleScope.launch(Dispatchers.IO) { logStreamer.start() }
        lifecycleScope.launch {
            logStreamer.entries.collectLatest { entries ->
                logAdapter.updateEntries(entries)
                logEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                logRecyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.linkInspector.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INSPECTOR_URL)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Packet Inspector URL", e)
            }
        }
    }

    private fun setupMessageInput() {
        binding.editTextMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCharCount()
                updateButtonState()
                resetStatus()
            }
        })
    }

    private fun setupProtocolToggle() {
        binding.btnUdp.setOnClickListener {
            if (!isUdp) {
                isUdp = true
                updateProtocolButtons()
                binding.textTcpNotice.visibility = View.GONE
                resetStatus()
            }
        }
        binding.btnTcp.setOnClickListener {
            if (isUdp) {
                isUdp = false
                updateProtocolButtons()
                binding.textTcpNotice.visibility = View.VISIBLE
                resetStatus()
            }
        }
    }

    private fun updateProtocolButtons() {
        if (isUdp) {
            binding.btnUdp.setBackgroundResource(R.drawable.scitra_chip_selected)
            binding.btnUdp.setTextColor(ContextCompat.getColor(this, R.color.scitra_on_primary))
            binding.btnTcp.setBackgroundResource(R.drawable.scitra_chip_unselected)
            binding.btnTcp.setTextColor(ContextCompat.getColor(this, R.color.scitra_on_surface_variant))
        } else {
            binding.btnTcp.setBackgroundResource(R.drawable.scitra_chip_selected)
            binding.btnTcp.setTextColor(ContextCompat.getColor(this, R.color.scitra_on_primary))
            binding.btnUdp.setBackgroundResource(R.drawable.scitra_chip_unselected)
            binding.btnUdp.setTextColor(ContextCompat.getColor(this, R.color.scitra_on_surface_variant))
        }
    }

    private fun setupSendButton() {
        binding.btnSend.setOnClickListener {
            val message = binding.editTextMessage.text?.toString() ?: ""
            if (message.isBlank()) {
                showError(getString(R.string.debug_packet_error_empty))
                return@setOnClickListener
            }
            if (message.length > MAX_CHAR_COUNT) {
                showError(getString(R.string.debug_packet_error_too_long, MAX_CHAR_COUNT))
                return@setOnClickListener
            }
            sendMessage(message)
        }
    }

    private fun setupLogPanel() {
        logAdapter = LogAdapter()
        logRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@DebugPacketActivity)
            adapter = logAdapter
        }

        btnLogExpand.setOnClickListener {
            isLogExpanded = !isLogExpanded
            val heightDp = if (isLogExpanded) EXPANDED_HEIGHT_DP else COLLAPSED_HEIGHT_DP
            val heightPx = (heightDp * resources.displayMetrics.density).toInt()
            val lp = logRecyclerView.layoutParams
            lp.height = heightPx
            logRecyclerView.layoutParams = lp
            btnLogExpand.text = if (isLogExpanded) {
                getString(R.string.debug_packet_log_collapse)
            } else {
                getString(R.string.debug_packet_log_expand)
            }
        }

        btnLogSave.setOnClickListener {
            lifecycleScope.launch { saveLog() }
        }

        btnLogClear.setOnClickListener {
            logStreamer.clear()
        }
    }

    private suspend fun saveLog() {
        val lines = logStreamer.getRawLines()
        if (lines.isEmpty()) {
            Snackbar.make(binding.root, "No log entries to save", Snackbar.LENGTH_SHORT).show()
            return
        }
        val logContent = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        var exception: Throwable? = null
        var outputFile: DownloadsFileSaver.DownloadsFile? = null
        with(Dispatchers.IO) {
            try {
                outputFile = downloadsFileSaver.save("wireguard-debug-packet-log.txt", "text/plain", true)
                outputFile?.outputStream?.write(logContent)
            } catch (e: Throwable) {
                outputFile?.delete()
                exception = e
            }
        }
        if (outputFile == null) return
        Snackbar.make(
            binding.root,
            if (exception == null) getString(R.string.log_export_success, outputFile.fileName)
            else getString(R.string.log_export_error, ErrorMessages[exception]),
            if (exception == null) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        ).show()
    }

    private fun updateCharCount() {
        val count = binding.editTextMessage.text?.length ?: 0
        binding.textCharCount.text = getString(R.string.debug_packet_char_count, count, MAX_CHAR_COUNT)
    }

    private fun updateButtonState() {
        val message = binding.editTextMessage.text?.toString() ?: ""
        val hasText = message.isNotBlank() && message.length <= MAX_CHAR_COUNT
        val sending = sendJob?.isActive == true

        binding.btnSend.isEnabled = hasText && !sending
        binding.textStatus.visibility = if (sending) View.VISIBLE else View.GONE

        if (sending) {
            binding.btnSend.text = getString(R.string.debug_packet_sending_button)
            binding.btnSend.isEnabled = false
        } else {
            binding.btnSend.text = getString(R.string.debug_packet_send_button)
        }
    }

    private fun resetStatus() {
        sendJob?.cancel()
        binding.textStatus.visibility = View.GONE
        binding.textStatus.text = ""
        updateButtonState()
    }

    private fun sendMessage(payload: String) {
        sendJob = lifecycleScope.launch {
            updateButtonState()

            val result = if (isUdp) {
                ScionDebugSender.sendUdp(payload)
            } else {
                ScionDebugSender.sendTcp(payload)
            }

            when (result) {
                is ScionDebugSender.SendResult.Success -> {
                    binding.textStatus.text = getString(R.string.debug_packet_status_success)
                    binding.textStatus.setTextColor(
                        ContextCompat.getColor(this@DebugPacketActivity, R.color.scitra_success)
                    )
                    binding.textStatus.visibility = View.VISIBLE
                }
                is ScionDebugSender.SendResult.Failure -> {
                    showError(result.message)
                }
            }
            kotlinx.coroutines.delay(1500)
            binding.btnSend.isEnabled = true
            binding.btnSend.text = getString(R.string.debug_packet_send_button)
        }
    }

    private fun showError(message: String) {
        binding.textStatus.text = message
        binding.textStatus.setTextColor(
            ContextCompat.getColor(this, R.color.scitra_error)
        )
        binding.textStatus.visibility = View.VISIBLE
        updateButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        sendJob?.cancel()
        logStreamer.close()
    }

    private fun levelToColor(level: String): Int {
        return when (level) {
            "V", "D" -> debugColor
            "E" -> errorColor
            "I" -> infoColor
            "W" -> warningColor
            else -> defaultColor
        }
    }

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private var entries = listOf<DebugPacketLogStreamer.LogEntry>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val timestamp: TextView = view.findViewById(R.id.log_timestamp)
            val tag: TextView = view.findViewById(R.id.log_tag)
            val message: TextView = view.findViewById(R.id.log_message)
        }

        fun updateEntries(newEntries: List<DebugPacketLogStreamer.LogEntry>) {
            entries = newEntries
            notifyDataSetChanged()
            if (entries.isNotEmpty()) {
                logRecyclerView.scrollToPosition(entries.size - 1)
            }
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.debug_packet_log_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.timestamp.text = entry.timestamp
            holder.message.text = entry.message

            val tagText = entry.tag
            val spannable = SpannableString(tagText)
            spannable.setSpan(
                ForegroundColorSpan(levelToColor(entry.level.toString())),
                0, tagText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.tag.text = spannable
        }
    }
}
