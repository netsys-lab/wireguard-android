package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.FlowPathDto
import com.wireguard.android.model.FlowPathsResponseDto
import com.wireguard.android.model.FlowPathsState
import com.wireguard.android.util.FlowRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PathSelectionBottomSheet : BottomSheetDialogFragment() {

    lateinit var tunnel: Tunnel
    lateinit var flowRepository: FlowRepository

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var retryCount = 0
    private val maxRetries = 10
    private var loadingJob: Job? = null

    private var paths: List<FlowPathDto> = emptyList()
    private var currentState: FlowPathsState = FlowPathsState.PENDING

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.path_selection_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val flowId = arguments?.getLong(ARG_FLOW_ID, -1L) ?: -1L
        view.findViewById<TextView>(R.id.path_subtitle)?.text =
            getString(R.string.optimizing_connection, "Flow #$flowId")
        view.findViewById<View>(R.id.btn_confirm_path)?.setOnClickListener {
            dismiss()
        }
        loadPaths(flowId)
    }

    override fun onDestroyView() {
        loadingJob?.cancel()
        scope.cancel()
        super.onDestroyView()
    }

    private fun loadPaths(flowId: Long) {
        if (!::tunnel.isInitialized || !::flowRepository.isInitialized) return
        loadingJob?.cancel()
        loadingJob = scope.launch {
            fetchPaths(flowId)
        }
    }

    private suspend fun fetchPaths(flowId: Long) {
        currentState = FlowPathsState.PENDING
        updateUI()

        val result = flowRepository.getFlowPaths(tunnel, flowId)
        val response: FlowPathsResponseDto = result.getOrNull() ?: run {
            currentState = FlowPathsState.ERROR
            updateUI()
            return
        }

        when (response.state) {
            FlowPathsState.READY -> {
                currentState = FlowPathsState.READY
                paths = response.paths
                updateUI()
            }
            FlowPathsState.PENDING -> {
                if (retryCount < maxRetries) {
                    retryCount++
                    delay(1000L)
                    fetchPaths(flowId)
                } else {
                    currentState = FlowPathsState.ERROR
                    updateUI()
                }
            }
            FlowPathsState.EMPTY -> {
                currentState = FlowPathsState.EMPTY
                updateUI()
            }
            FlowPathsState.ERROR -> {
                currentState = FlowPathsState.ERROR
                updateUI()
            }
            else -> {
                currentState = FlowPathsState.ERROR
                updateUI()
            }
        }
    }

    private fun updateUI() {
        val view = view ?: return
        val loadingView = view.findViewById<View>(R.id.path_loading_view)
        val contentView = view.findViewById<View>(R.id.path_content_view)
        val emptyView = view.findViewById<View>(R.id.path_empty_view)
        val errorView = view.findViewById<View>(R.id.path_error_view)
        val retryButton = view.findViewById<Button>(R.id.btn_retry)
        val subtitle = view.findViewById<TextView>(R.id.path_subtitle)

        loadingView?.visibility = View.GONE
        contentView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        errorView?.visibility = View.GONE

        when (currentState) {
            FlowPathsState.PENDING -> {
                loadingView?.visibility = View.VISIBLE
            }
            FlowPathsState.READY -> {
                contentView?.visibility = View.VISIBLE
                subtitle?.text = getString(R.string.path_count, paths.size)
                val recyclerView = view.findViewById<RecyclerView>(R.id.path_list)
                recyclerView?.layoutManager = LinearLayoutManager(context)
                recyclerView?.adapter = PathAdapter(paths)
            }
            FlowPathsState.EMPTY -> {
                emptyView?.visibility = View.VISIBLE
            }
            FlowPathsState.ERROR -> {
                errorView?.visibility = View.VISIBLE
                retryButton?.setOnClickListener {
                    retryCount = 0
                    val flowId = arguments?.getLong(ARG_FLOW_ID, -1L) ?: -1L
                    loadPaths(flowId)
                }
            }
            else -> {
                errorView?.visibility = View.VISIBLE
                retryButton?.setOnClickListener {
                    retryCount = 0
                    val flowId = arguments?.getLong(ARG_FLOW_ID, -1L) ?: -1L
                    loadPaths(flowId)
                }
            }
        }
    }

    companion object {
        private const val ARG_FLOW_ID = "flow_id"

        fun newInstance(flowId: Long): PathSelectionBottomSheet {
            return PathSelectionBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_FLOW_ID, flowId)
                }
            }
        }
    }
}

class PathAdapter(
    private val paths: List<FlowPathDto>
) : RecyclerView.Adapter<PathAdapter.PathViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PathViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.path_item, parent, false)
        return PathViewHolder(view)
    }

    override fun onBindViewHolder(holder: PathViewHolder, position: Int) {
        val path = paths[position]
        holder.bind(path)
    }

    override fun getItemCount(): Int = paths.size

    class PathViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView = itemView.findViewById<TextView>(R.id.path_name)
        private val currentBadge = itemView.findViewById<TextView>(R.id.path_current_badge)
        private val latencyView = itemView.findViewById<TextView>(R.id.path_latency)
        private val metadataView = itemView.findViewById<TextView>(R.id.path_metadata)
        private val nextHopView = itemView.findViewById<TextView>(R.id.path_next_hop)

        fun bind(path: FlowPathDto) {
            nameView.text = path.display
            currentBadge.visibility = if (path.current) View.VISIBLE else View.GONE
            latencyView.text = path.latencyMs?.firstOrNull()?.let { "${it.toLong()}ms" } ?: ""
            metadataView.text = buildString {
                path.interfaces?.let { append(it.joinToString(" → ")) }
                path.linkType?.let { if (isNotEmpty()) append(" · "); append(it.joinToString(", ")) }
            }
            nextHopView.text = path.nextHop?.let { "Next hop: $it" } ?: ""
        }
    }
}
