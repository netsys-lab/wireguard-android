/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerDialogFragment : DialogFragment() {

    private lateinit var etSearch: EditText
    private lateinit var rvApps: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView

    private val allApps = mutableListOf<AppPickerEntry>()
    private val filteredApps = mutableListOf<AppPickerEntry>()
    private var adapter: AppPickerAdapter? = null

    data class AppPickerEntry(
        val icon: Drawable?,
        val name: String,
        val packageName: String,
        val uid: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.PathPolicyDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.dialog_app_picker, container, false)

        etSearch = root.findViewById(R.id.et_picker_search)
        rvApps = root.findViewById(R.id.rv_picker_apps)
        pbLoading = root.findViewById(R.id.pb_picker_loading)
        tvEmpty = root.findViewById(R.id.tv_picker_empty)

        root.findViewById<View>(R.id.btn_close_picker).setOnClickListener { dismiss() }

        rvApps.layoutManager = LinearLayoutManager(context)
        adapter = AppPickerAdapter()
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                filterApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()

        return root
    }

    private fun loadApps() {
        pbLoading.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = requireContext().packageManager
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getInstalledPackages(0)
            }

            val entries = mutableListOf<AppPickerEntry>()
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val hasLaunch = pm.getLaunchIntentForPackage(pkg.packageName) != null
                // Show non-system apps and system apps with launcher icons
                if (!isSystem || hasLaunch) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    entries.add(AppPickerEntry(icon, label, pkg.packageName, appInfo.uid))
                }
            }

            entries.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(entries)
                filterApps(etSearch.text.toString())
                pbLoading.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
            }
        }
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            filteredApps.addAll(allApps)
        } else {
            filteredApps.addAll(allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        tvEmpty.visibility = if (filteredApps.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
        adapter?.notifyDataSetChanged()
    }

    private inner class AppPickerAdapter : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_app_picker_icon)
            val tvName: TextView = view.findViewById(R.id.tv_app_picker_name)
            val tvPackage: TextView = view.findViewById(R.id.tv_app_picker_package)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker_entry, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = filteredApps.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = filteredApps[position]
            holder.tvName.text = entry.name
            holder.tvPackage.text = entry.packageName
            if (entry.icon != null) {
                holder.ivIcon.setImageDrawable(entry.icon)
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.itemView.setOnClickListener {
                setFragmentResult(
                    REQUEST_KEY_APP_SELECTED,
                    bundleOf(
                        KEY_PACKAGE_NAME to entry.packageName,
                        KEY_APP_LABEL to entry.name,
                        KEY_APP_UID to entry.uid
                    )
                )
                dismiss()
            }
        }
    }

    companion object {
        const val TAG = "AppPickerDialogFragment"
        const val REQUEST_KEY_APP_SELECTED = "request_key_app_selected"
        const val KEY_PACKAGE_NAME = "key_package_name"
        const val KEY_APP_LABEL = "key_app_label"
        const val KEY_APP_UID = "key_app_uid"

        fun newInstance(): AppPickerDialogFragment {
            return AppPickerDialogFragment()
        }
    }
}
