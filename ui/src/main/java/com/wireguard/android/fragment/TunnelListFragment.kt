/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.updater.SnackbarUpdateShower
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

import com.wireguard.android.BR

/**
 * Fragment containing a list of known WireGuard tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null

    private val scionTunnels = com.wireguard.android.databinding.ObservableSortedKeyedArrayList<String, ObservableTunnel>(com.wireguard.android.model.TunnelComparator)
    private val standardTunnels = com.wireguard.android.databinding.ObservableSortedKeyedArrayList<String, ObservableTunnel>(com.wireguard.android.model.TunnelComparator)

    private val listChangedCallback = object : androidx.databinding.ObservableList.OnListChangedCallback<androidx.databinding.ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: androidx.databinding.ObservableList<ObservableTunnel>?) {
            syncTunnels()
        }

        override fun onItemRangeChanged(sender: androidx.databinding.ObservableList<ObservableTunnel>?, positionStart: Int, itemCount: Int) {
            syncTunnels()
        }

        override fun onItemRangeInserted(sender: androidx.databinding.ObservableList<ObservableTunnel>?, positionStart: Int, itemCount: Int) {
            sender?.subList(positionStart, positionStart + itemCount)?.forEach { tunnel ->
                tunnel.addOnPropertyChangedCallback(tunnelPropertyChangedCallback)
            }
            syncTunnels()
        }

        override fun onItemRangeMoved(sender: androidx.databinding.ObservableList<ObservableTunnel>?, fromPosition: Int, toPosition: Int, itemCount: Int) {
            syncTunnels()
        }

        override fun onItemRangeRemoved(sender: androidx.databinding.ObservableList<ObservableTunnel>?, positionStart: Int, itemCount: Int) {
            syncTunnels()
        }
    }

    private val tunnelPropertyChangedCallback = object : androidx.databinding.Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: androidx.databinding.Observable?, propertyId: Int) {
            if (propertyId == BR.scion || propertyId == BR.config) {
                syncTunnels()
            }
        }
    }

    private fun syncTunnels() {
        val allTunnels = binding?.tunnels ?: return

        val scionList = allTunnels.filter { it.isScion }
        val standardList = allTunnels.filter { !it.isScion }

        // Update scionTunnels
        val scionNames = scionList.map { it.name }.toSet()
        scionTunnels.removeAll { it.name !in scionNames }
        for (item in scionList) {
            if (!scionTunnels.containsKey(item.name)) {
                scionTunnels.add(item)
            }
        }

        // Update standardTunnels
        val standardNames = standardList.map { it.name }.toSet()
        standardTunnels.removeAll { it.name !in standardNames }
        for (item in standardList) {
            if (!standardTunnels.containsKey(item.name)) {
                standardTunnels.add(item)
            }
        }
    }

    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
    }

    private val snackbarUpdateShower = SnackbarUpdateShower(this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                lifecycleScope.launch {
                    val tunnels = Application.getTunnelManager().getTunnels()
                    for (i in checkedItems) {
                        if (i >= 0 && i < tunnels.size) {
                            actionModeListener.setTunnelChecked(tunnels[i], true)
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        val bottomSheet = AddTunnelsSheet()
        binding?.apply {
            binding!!.scionTunnels = scionTunnels
            binding!!.standardTunnels = standardTunnels
            btnAddTunnel.setOnClickListener {
                if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null)
                    return@setOnClickListener
                childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
                    when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                        AddTunnelsSheet.REQUEST_CREATE -> {
                            startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                        }

                        AddTunnelsSheet.REQUEST_IMPORT -> {
                            tunnelFileImportResultLauncher.launch("*/*")
                        }

                        AddTunnelsSheet.REQUEST_SCAN -> {
                            qrImportResultLauncher.launch(
                                ScanOptions()
                                    .setOrientationLocked(false)
                                    .setBeepEnabled(false)
                                    .setPrompt(getString(R.string.qr_code_hint))
                            )
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            btnSettings.setOnClickListener {
                startActivity(Intent(requireActivity(), com.wireguard.android.activity.SettingsActivity::class.java))
            }

            executePendingBindings()
            snackbarUpdateShower.attach(mainContainer, null)
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onDestroyView() {
        binding?.tunnels?.let { tunnels ->
            tunnels.removeOnListChangedCallback(listChangedCallback)
            tunnels.forEach { tunnel ->
                tunnel.removeOnPropertyChangedCallback(tunnelPropertyChangedCallback)
            }
        }
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            if (newTunnel != null) viewForTunnel(newTunnel)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel)?.setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        binding!!.scionTunnels = scionTunnels
        binding!!.standardTunnels = standardTunnels
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            binding!!.tunnels = tunnels
            tunnels.addOnListChangedCallback(listChangedCallback)
            tunnels.forEach { tunnel ->
                tunnel.addOnPropertyChangedCallback(tunnelPropertyChangedCallback)
            }
            syncTunnels()
        }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleTunnelChecked(item)
                    }
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleTunnelChecked(item)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedTunnels.contains(item))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private fun viewForTunnel(tunnel: ObservableTunnel): MultiselectableRelativeLayout? {
        val binding = binding ?: return null
        return if (tunnel.isScion) {
            val index = scionTunnels.indexOf(tunnel)
            if (index >= 0) {
                binding.scionTunnelList.findViewHolderForAdapterPosition(index)?.itemView as? MultiselectableRelativeLayout
            } else {
                null
            }
        } else {
            val index = standardTunnels.indexOf(tunnel)
            if (index >= 0) {
                binding.tunnelList.findViewHolderForAdapterPosition(index)?.itemView as? MultiselectableRelativeLayout
            } else {
                null
            }
        }
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedTunnels: MutableCollection<ObservableTunnel> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            val list = ArrayList<Int>()
            val mainTunnels = binding?.tunnels ?: return list
            checkedTunnels.forEach { tunnel ->
                val index = mainTunnels.indexOf(tunnel)
                if (index >= 0) list.add(index)
            }
            return list
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedTunnels = HashSet(checkedTunnels)
                    activity.lifecycleScope.launch {
                        try {
                            val futures = copyCheckedTunnels.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedTunnels.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        for (tunnel in tunnels) {
                            setTunnelChecked(tunnel, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.scionTunnelList?.adapter?.notifyDataSetChanged()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            checkedTunnels.clear()
            binding?.scionTunnelList?.adapter?.notifyDataSetChanged()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setTunnelChecked(tunnel: ObservableTunnel, checked: Boolean) {
            if (checked) {
                checkedTunnels.add(tunnel)
            } else {
                checkedTunnels.remove(tunnel)
            }
            if (actionMode == null && !checkedTunnels.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedTunnels.isEmpty()) {
                actionMode!!.finish()
            }
            binding?.scionTunnelList?.adapter?.notifyDataSetChanged()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            updateTitle(actionMode)
        }

        fun toggleTunnelChecked(tunnel: ObservableTunnel) {
            setTunnelChecked(tunnel, !checkedTunnels.contains(tunnel))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedTunnels.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
