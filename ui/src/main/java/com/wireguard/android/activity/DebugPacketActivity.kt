/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.R
import com.wireguard.android.databinding.DebugPacketActivityBinding
import com.wireguard.android.util.AutoscrollTracker
import com.wireguard.android.util.DebugPacketLogFilter
import com.wireguard.android.util.DebugPacketLogModels
import com.wireguard.android.util.DebugPacketLogStreamer

import com.wireguard.android.util.DownloadsFileSaver
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.ScionDebugSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


private typealias LogEntry = DebugPacketLogModels.LogEntry

class DebugPacketActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "WireGuard/DebugPacket"
        private const val MAX_CHAR_COUNT = 512
        private const val INSPECTOR_URL = "https://scionpacketinspector.netsec.ethz.ch/"
        private const val TRIMMED_MAX = 5000
    }

    private lateinit var binding: DebugPacketActivityBinding
    private var isUdp = true
    private var sendJob: Job? = null

    private val logStreamer = DebugPacketLogStreamer()
    private lateinit var logAdapter: LogAdapter
    private val autoscroll = AutoscrollTracker()
    private var detailedMode = false
    private var isCollapsed = false
    private val filterCriteria = DebugPacketLogFilter.Criteria()

    private var allEntries = listOf<LogEntry>()
    private var visibleEntries = listOf<LogEntry>()

    private lateinit var logRecyclerView: RecyclerView
    private lateinit var logEmptyText: TextView
    private lateinit var btnLogClear: TextView
    private lateinit var btnLogSearch: View
    private lateinit var btnLogPause: ImageButton
    private lateinit var btnLogMore: View
    private lateinit var btnLogNewLines: TextView
    private lateinit var logTrimmed: TextView

    private val downloadsFileSaver by lazy { DownloadsFileSaver(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DebugPacketActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logRecyclerView = findViewById(R.id.log_recycler_view)
        logEmptyText = findViewById(R.id.log_empty_text)
        btnLogClear = findViewById(R.id.btn_log_clear)
        btnLogSearch = findViewById(R.id.btn_log_search)
        btnLogPause = findViewById(R.id.btn_log_pause)
        btnLogMore = findViewById(R.id.btn_log_more)
        btnLogNewLines = findViewById(R.id.log_new_lines)
        logTrimmed = findViewById(R.id.log_trimmed)

        setupTopBar()
        setupMessageInput()
        setupProtocolToggle()
        setupSendButton()
        setupLogPanel()
        updateButtonState()

        logStreamer.onFailure = { throwable ->
            runOnUiThread {
                Toast.makeText(
                    this@DebugPacketActivity,
                    getString(R.string.debug_packet_log_error, throwable.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        logStreamer.start(lifecycleScope)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                logStreamer.entries.collectLatest { entries ->
                    allEntries = entries
                    applyFiltersAndUpdate(fromStream = true)
                    updateTrimmedIndicator()
                }
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
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lm = recyclerView.layoutManager as LinearLayoutManager
                    autoscroll.onScroll(lm.findFirstVisibleItemPosition(), lm.childCount, lm.itemCount)
                    updatePauseButton()
                    if (autoscroll.enabled) updateNewLinesIndicator()
                }
            })
        }

        btnLogClear.setOnClickListener {
            logStreamer.clear()
            allEntries = emptyList()
            autoscroll.reset()
        }

        btnLogSearch.setOnClickListener {
            val box = findViewById<View>(R.id.log_search_box)
            val visible = box.visibility != View.VISIBLE
            box.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) findViewById<androidx.appcompat.widget.AppCompatEditText>(R.id.log_search_input)
                .requestFocus()
        }

        findViewById<androidx.appcompat.widget.AppCompatEditText>(R.id.log_search_input)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterCriteria.query = s?.toString() ?: ""
                    applyFiltersAndUpdate()
                }
            })

        btnLogPause.setOnClickListener {
            if (autoscroll.enabled) {
                autoscroll.pause()
            } else {
                autoscroll.resume()
            }
            updatePauseButton()
            updateNewLinesIndicator()
        }

        btnLogNewLines.setOnClickListener {
            autoscroll.resume()
            updatePauseButton()
            updateNewLinesIndicator()
            scrollToBottom()
        }

        btnLogMore.setOnClickListener { view -> showMoreMenu(view) }

        updatePauseButton()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.debug_packet_log_menu, popup.menu)
        popup.menu.findItem(R.id.action_mode).setTitle(
            if (detailedMode) getString(R.string.debug_packet_log_compact)
            else getString(R.string.debug_packet_log_detailed)
        )
        popup.menu.findItem(R.id.action_collapse).setTitle(
            if (isCollapsed) getString(R.string.debug_packet_log_expand)
            else getString(R.string.debug_packet_log_collapse)
        )
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> lifecycleScope.launch { saveLog() }
                R.id.action_mode -> {
                    detailedMode = !detailedMode
                    logAdapter.notifyDataSetChanged()
                }
                R.id.action_level -> showLevelFilter()
                R.id.action_component -> showComponentFilter()
                R.id.action_collapse -> toggleCollapse()
            }
            true
        }
        popup.show()
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        val lp = logRecyclerView.layoutParams
        lp.height = if (isCollapsed) {
            (80 * resources.displayMetrics.density).toInt()
        } else {
            (260 * resources.displayMetrics.density).toInt()
        }
        logRecyclerView.layoutParams = lp
        btnLogMore.contentDescription = if (isCollapsed) {
            getString(R.string.debug_packet_log_expand)
        } else {
            getString(R.string.debug_packet_log_collapse)
        }
    }

    private val levelOptions = listOf(
        'V' to "TRACE", 'D' to "DEBUG", 'I' to "INFO",
        'W' to "WARN", 'E' to "ERROR", 'F' to "FATAL"
    )

    private fun showLevelFilter() {
        val labels = levelOptions.map { it.second }.toTypedArray()
        val checked = levelOptions.map { filterCriteria.levels.contains(it.first) }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_packet_log_filter_level)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val level = levelOptions[which].first
                filterCriteria.levels = if (isChecked) {
                    filterCriteria.levels + level
                } else {
                    filterCriteria.levels - level
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> applyFiltersAndUpdate() }
            .setNeutralButton(R.string.debug_packet_log_all) { _, _ ->
                filterCriteria.levels = emptySet()
                applyFiltersAndUpdate()
            }
            .show()
    }

    private fun showComponentFilter() {
        val components = DebugPacketLogFilter.distinctComponents(allEntries)
        if (components.isEmpty()) {
            Snackbar.make(binding.root, R.string.debug_packet_log_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        val checked = components.map { filterCriteria.components.contains(it) }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_packet_log_filter_component)
            .setMultiChoiceItems(components.toTypedArray(), checked) { _, which, isChecked ->
                val comp = components[which]
                filterCriteria.components = if (isChecked) {
                    filterCriteria.components + comp
                } else {
                    filterCriteria.components - comp
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> applyFiltersAndUpdate() }
            .setNeutralButton(R.string.debug_packet_log_all) { _, _ ->
                filterCriteria.components = emptySet()
                applyFiltersAndUpdate()
            }
            .show()
    }

    private fun applyFiltersAndUpdate(fromStream: Boolean = false) {
        val newVisible = DebugPacketLogFilter.apply(allEntries, filterCriteria)
        val wasAtBottom = autoscroll.enabled
        visibleEntries = newVisible
        logAdapter.updateEntries(newVisible, fromStream)
        logEmptyText.visibility = if (allEntries.isEmpty()) View.VISIBLE else View.GONE
        logRecyclerView.visibility = if (allEntries.isEmpty() && newVisible.isEmpty()) View.GONE else View.VISIBLE
        if (wasAtBottom) scrollToBottom()
        updateNewLinesIndicator()
    }

    private fun updateTrimmedIndicator() {
        val trimmed = logStreamer.getTrimmedCount()
        if (trimmed > 0) {
            logTrimmed.visibility = View.VISIBLE
            logTrimmed.text = getString(R.string.debug_packet_log_trimmed, TRIMMED_MAX)
        } else {
            logTrimmed.visibility = View.GONE
        }
    }

    private fun updatePauseButton() {
        val paused = !autoscroll.enabled
        btnLogPause.contentDescription = if (paused) {
            getString(R.string.debug_packet_log_resume)
        } else {
            getString(R.string.debug_packet_log_pause)
        }
        if (paused) {
            btnLogPause.setImageResource(R.drawable.ic_play)
            btnLogPause.setColorFilter(
                ContextCompat.getColor(this, R.color.scitra_warning),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            btnLogPause.background = ResourcesCompat.getDrawable(
                resources, R.drawable.log_pause_active_bg, theme
            )
        } else {
            btnLogPause.setImageResource(R.drawable.ic_pause)
            btnLogPause.clearColorFilter()
            btnLogPause.background = null
        }
    }

    private fun updateNewLinesIndicator() {
        if (!autoscroll.enabled && autoscroll.newLinesWhilePaused > 0) {
            btnLogNewLines.visibility = View.VISIBLE
            btnLogNewLines.text = getString(
                R.string.debug_packet_log_new_lines, autoscroll.newLinesWhilePaused
            )
        } else {
            btnLogNewLines.visibility = View.GONE
        }
    }

    private fun scrollToBottom() {
        if (visibleEntries.isNotEmpty()) {
            logRecyclerView.scrollToPosition(visibleEntries.size - 1)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
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
        logStreamer.onFailure = null
        logStreamer.close()
    }

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        private var entries = listOf<LogEntry>()
        private val expanded = mutableSetOf<Int>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.log_time)
            val component: TextView = view.findViewById(R.id.log_component)
            val message: TextView = view.findViewById(R.id.log_message)
            val detail: View = view.findViewById(R.id.log_detail)
            val levelBadge: TextView = view.findViewById(R.id.log_level_badge)
            val tag: TextView = view.findViewById(R.id.log_tag)
            val raw: TextView = view.findViewById(R.id.log_raw)
        }

        fun updateEntries(newEntries: List<LogEntry>, fromStream: Boolean = false) {
            if (fromStream && !autoscroll.enabled) autoscroll.onNewData()
            val oldList = entries
            val oldSize = oldList.size
            val newSize = newEntries.size
            entries = newEntries
            when {
                oldSize == 0 -> notifyDataSetChanged()
                newSize >= oldSize && oldList == newEntries.subList(0, oldSize) -> {
                    // Pure append (most common case): only bind the new tail.
                    notifyItemRangeInserted(oldSize, newSize - oldSize)
                }
                newSize < oldSize -> {
                    // Trimmed from the front: rebind from the first changed index.
                    notifyItemRangeChanged(0, newSize)
                }
                else -> notifyDataSetChanged()
            }
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.debug_packet_log_entry, parent, false)
            val holder = ViewHolder(view)
            view.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in entries.indices) {
                    if (expanded.contains(pos)) expanded.remove(pos) else expanded.add(pos)
                    notifyItemChanged(pos)
                }
            }
            view.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos !in entries.indices) return@setOnLongClickListener false
                val entry = entries[pos]
                copyToClipboard("log-line", entry.rawLine)
                val packetId = entry.packetId
                if (packetId != null) {
                    val trace = DebugPacketLogFilter.collectPacketTrace(entries, packetId)
                    copyToClipboard("packet-trace", trace.joinToString("\n"))
                    Snackbar.make(binding.root,
                        getString(R.string.debug_packet_log_trace_copied, trace.size),
                        Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root,
                        R.string.debug_packet_log_copied, Snackbar.LENGTH_SHORT).show()
                }
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.time.text = entry.displayTime
            holder.component.text = entry.displayComponent.ifEmpty { "APP" }
            holder.message.text = entry.displayMessage.ifEmpty { entry.rawLine }

            val (bg, fg) = levelColors(entry.level)
            holder.component.background = ResourcesCompat.getDrawable(
                resources, R.drawable.log_component_badge_bg, theme
            )?.mutate()?.also {
                (it as? android.graphics.drawable.GradientDrawable)
                    ?.setColor(ContextCompat.getColor(this@DebugPacketActivity, R.color.scitra_surface_container_high))
            }
            holder.levelBadge.setBackgroundResource(R.drawable.log_level_badge_bg)
            holder.levelBadge.background?.mutate()?.let {
                (it as? android.graphics.drawable.GradientDrawable)?.setColor(bg)
            }
            holder.levelBadge.setTextColor(fg)
            holder.levelBadge.text = entry.displayLevel

            val isExpanded = expanded.contains(position) || detailedMode
            holder.detail.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.tag.text = entry.tag.ifEmpty { getString(R.string.debug_packet_log_more) }
            holder.raw.text = entry.rawLine
        }

        private fun levelColors(level: Char): Pair<Int, Int> {
            val bgRes = when (level) {
                'V' -> R.color.log_level_trace
                'D' -> R.color.log_level_debug
                'I' -> R.color.log_level_info
                'W' -> R.color.log_level_warn
                'E' -> R.color.log_level_error
                'F' -> R.color.log_level_fatal
                else -> R.color.log_level_debug
            }
            val fgRes = when (level) {
                'V' -> R.color.log_level_trace_text
                'D' -> R.color.log_level_debug_text
                'I' -> R.color.log_level_info_text
                'W' -> R.color.log_level_warn_text
                'E' -> R.color.log_level_error_text
                'F' -> R.color.log_level_fatal_text
                else -> R.color.log_level_debug_text
            }
            return Pair(
                ContextCompat.getColor(this@DebugPacketActivity, bgRes),
                ContextCompat.getColor(this@DebugPacketActivity, fgRes)
            )
        }
    }
}
