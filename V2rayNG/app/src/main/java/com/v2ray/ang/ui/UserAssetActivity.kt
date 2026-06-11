package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityUserAssetBinding
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.UserAssetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UserAssetActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityUserAssetBinding.inflate(layoutInflater) }
    private val ownerActivity: UserAssetActivity
        get() = this
    private val viewModel: UserAssetViewModel by viewModels()
    private lateinit var adapter: UserAssetAdapter

    val extDir by lazy { File(Utils.userAssetPath(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_asset_setting))

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        adapter = UserAssetAdapter(viewModel, extDir, ActivityAdapterListener())
        binding.recyclerView.adapter = adapter

        binding.tvGeoFilesSourcesSummary.text = getGeoFilesSources()
        binding.layoutGeoFilesSources.setOnClickListener {
            setGeoFilesSources()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_asset, menu)
        return super.onCreateOptionsMenu(menu)
    }

    // Use when to streamline the option selection
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_file -> showFileChooser().let { true }
        R.id.add_url -> startActivity(Intent(this, UserAssetUrlActivity::class.java)).let { true }
        R.id.add_qrcode -> importAssetFromQRcode().let { true }
        R.id.download_file -> downloadGeoFiles().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun getGeoFilesSources(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES) ?: AppConfig.GEO_FILES_SOURCES.first()
    }

    private fun setGeoFilesSources() {
        val builtInSize = AppConfig.GEO_FILES_SOURCES.size
        val itemsList = AppConfig.GEO_FILES_SOURCES.toMutableList()
        var savedCustomSources = MmkvManager.getCustomGeoSources()
        itemsList.addAll(savedCustomSources)
        
        val customActionText = getString(R.string.asset_geo_files_sources_custom)
        itemsList.add(customActionText)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@UserAssetActivity)
        }
        
        var mainDialog: AlertDialog? = null
        
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val textView = holder.itemView.findViewById<TextView>(android.R.id.text1)
                textView.text = itemsList[position]
                
                holder.itemView.setOnClickListener {
                    val i = holder.adapterPosition
                    if (i == RecyclerView.NO_POSITION) return@setOnClickListener
                    try {
                        when {
                            i == itemsList.lastIndex -> showCustomSourceInputDialog()
                            i < builtInSize -> saveAndRefreshGeoSource(AppConfig.GEO_FILES_SOURCES[i])
                            else -> saveAndRefreshGeoSource(savedCustomSources[i - builtInSize])
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to select geo source", e)
                    }

                    mainDialog?.dismiss()
                }
            }

            override fun getItemCount(): Int = itemsList.size
        }
        recyclerView.adapter = adapter

        mainDialog = AlertDialog.Builder(this)
            .setView(recyclerView)
            .create()

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val position = viewHolder.adapterPosition
                if (position in builtInSize until itemsList.lastIndex) {
                    return super.getSwipeDirs(recyclerView, viewHolder)
                }
                return 0
            }

            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val customIndex = position - builtInSize
                val targetUrl = savedCustomSources[customIndex]

                adapter.notifyItemChanged(position)

                if (direction == ItemTouchHelper.RIGHT) {
                    AlertDialog.Builder(this@UserAssetActivity)
                        .setTitle(getString(R.string.asset_geo_files_sources_delete_title))
                        .setMessage(getString(R.string.asset_geo_files_sources_delete_message, targetUrl))
                        .setPositiveButton(android.R.string.ok) { dialog, _ ->
                            MmkvManager.removeCustomGeoSource(targetUrl)
                            if (getGeoFilesSources() == targetUrl) {
                                saveAndRefreshGeoSource(AppConfig.GEO_FILES_SOURCES.first())
                            }
                            dialog.dismiss()
                            
                            savedCustomSources = MmkvManager.getCustomGeoSources()
                            itemsList.clear()
                            itemsList.addAll(AppConfig.GEO_FILES_SOURCES)
                            itemsList.addAll(savedCustomSources)
                            itemsList.add(customActionText)
                            adapter.notifyDataSetChanged()
                        }
                        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                        .show()

                } else if (direction == ItemTouchHelper.LEFT) {
                    showEditCustomSourceDialog(targetUrl) { newUrl ->
                        if (newUrl.isNotEmpty() && newUrl != targetUrl) {
                            MmkvManager.removeCustomGeoSource(targetUrl)
                            MmkvManager.addCustomGeoSource(newUrl)
                            
                            if (getGeoFilesSources() == targetUrl) {
                                saveAndRefreshGeoSource(newUrl)
                            }
                            
                            savedCustomSources = MmkvManager.getCustomGeoSources()
                            itemsList.clear()
                            itemsList.addAll(AppConfig.GEO_FILES_SOURCES)
                            itemsList.addAll(savedCustomSources)
                            itemsList.add(customActionText)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)
        mainDialog.show()
    }

    private fun showEditCustomSourceDialog(oldUrl: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(oldUrl)
            hint = getString(R.string.asset_geo_files_sources_input_hint)
            setSingleLine(true)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.asset_geo_files_sources_edit_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(input.text.toString().trim())
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showCustomSourceInputDialog() {
        val input = EditText(this).apply {
            setText(getGeoFilesSources())
            hint = getString(R.string.asset_geo_files_sources_input_hint)
            setSingleLine(true)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.asset_geo_files_sources_input_title)) 
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> 
                val customUrl = input.text.toString().trim()
                if (customUrl.isNotEmpty()) {
                    MmkvManager.addCustomGeoSource(customUrl)
                    saveAndRefreshGeoSource(customUrl)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> 
                dialog.dismiss()
            }
            .show()
    }

    private fun saveAndRefreshGeoSource(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_GEO_FILES_SOURCES, value)
        binding.tvGeoFilesSourcesSummary.text = value
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            val assetId = Utils.getUuid()
            runCatching {
                val assetItem = AssetUrlItem(
                    getCursorName(uri) ?: uri.toString(),
                    "file"
                )

                val assetList = MmkvManager.decodeAssetUrls()
                if (assetList.any { it.assetUrl.remarks == assetItem.remarks && it.guid != assetId }) {
                    toast(R.string.msg_remark_is_duplicate)
                } else {
                    MmkvManager.encodeAsset(assetId, assetItem)
                    copyFile(uri)
                }
            }.onFailure {
                toastError(R.string.toast_asset_copy_failed)
                MmkvManager.removeAssetUrl(assetId)
            }
        }
    }

    private fun copyFile(uri: Uri): String {
        val targetFile = File(extDir, getCursorName(uri) ?: uri.toString())
        contentResolver.openInputStream(uri).use { inputStream ->
            targetFile.outputStream().use { fileOut ->
                inputStream?.copyTo(fileOut)
                toastSuccess(R.string.toast_success)
                refreshData()
            }
        }
        return targetFile.path
    }

    private fun getCursorName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.let { cursor ->
            cursor.run {
                if (moveToFirst()) getString(getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }.also { cursor.close() }
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to get cursor name", e)
        null
    }

    private fun importAssetFromQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importAsset(scanResult)
            }
        }
        return true
    }


    private fun importAsset(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            // Send URL to UserAssetUrlActivity for Processing
            startActivity(
                Intent(this, UserAssetUrlActivity::class.java)
                    .putExtra(UserAssetUrlActivity.ASSET_URL_QRCODE, url)
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import asset from URL", e)
            return false
        }
        return true
    }

    private fun downloadGeoFiles() {
        refreshData()
        showLoading()
        toast(R.string.msg_downloading_content)

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = viewModel.downloadGeoFiles(extDir, httpPort, proxyUsername, proxyPassword)
            withContext(Dispatchers.Main) {
                if (result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.successCount))
                } else {
                    toast(getString(R.string.toast_failure))
                }
                refreshData()
                hideLoading()
            }
        }
    }

    fun initAssets() {
        lifecycleScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(this@UserAssetActivity, assets)
            withContext(Dispatchers.Main) {
                refreshData()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload(getGeoFilesSources())
        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, UserAssetUrlActivity::class.java)
                    .putExtra("assetId", guid)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            val asset = viewModel.getAsset(position)?.takeIf { it.guid == guid }
                ?: viewModel.getAssets().find { it.guid == guid }
                ?: return
            val file = extDir.listFiles()?.find { it.name == asset.assetUrl.remarks }

            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    file?.delete()
                    MmkvManager.removeAssetUrl(guid)
                    initAssets()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    // do nothing
                }
                .show()
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}