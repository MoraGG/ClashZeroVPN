package com.clashzerovpn.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clashzerovpn.R
import com.clashzerovpn.data.ClashConfig
import com.clashzerovpn.data.ClashConfigParser
import com.clashzerovpn.data.ProfileStore
import com.clashzerovpn.data.ProxyGroup
import com.clashzerovpn.data.ProxyNode
import com.clashzerovpn.data.VpnState
import com.clashzerovpn.databinding.ActivityMainBinding
import com.clashzerovpn.engine.ClashEngine
import com.clashzerovpn.engine.ZeroTierEngine
import com.clashzerovpn.vpn.CZVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileStore: ProfileStore
    private var clashConfig: ClashConfig? = null
    private var selectedNode: ProxyNode? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                CZVpnService.start(this)
            } else {
                Toast.makeText(this, "必须授权 VPN 才能使用", Toast.LENGTH_LONG).show()
                binding.switchVpn.isChecked = false
            }
        }

    private val clashImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val fname = copyToAppStorage(uri, "clash_config.yaml")
                val profile = profileStore.load()
                profile.clashConfigPath = fname
                profileStore.save(profile)
                binding.tvClashConfig.text = fname
                Toast.makeText(this, "Clash 配置已导入", Toast.LENGTH_SHORT).show()

                // 解析配置并更新 UI
                loadAndDisplayNodes(fname)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import clash failed", e)
                Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        profileStore = ProfileStore(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        initUI()
        observeState()

        // 如果已有配置，加载节点
        val profile = profileStore.load()
        if (profile.clashConfigPath.isNotEmpty()) {
            loadAndDisplayNodes(profile.clashConfigPath)
        }
    }

    private fun initUI() {
        val profile = profileStore.load()
        binding.tvClashConfig.text = profile.clashConfigPath.ifEmpty {
            getString(R.string.hint_clash_config)
        }
        binding.etZTNetwork.setText(profile.zeroTierNetworkId)
        binding.etZTNetwork.filters = arrayOf<InputFilter>(LengthFilter(16))

        // 主开关
        binding.switchVpn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) tryStartVpn() else CZVpnService.stop(this)
        }

        binding.btnImportClash.setOnClickListener {
            clashImportLauncher.launch(arrayOf(
                "application/yaml",
                "application/x-yaml",
                "text/yaml",
                "application/json",
                "text/plain",
                "*/*"
            ))
        }

        binding.btnSaveZT.setOnClickListener {
            val id = binding.etZTNetwork.text?.toString()?.trim() ?: ""
            if (id.isNotEmpty() && id.length != 16) {
                binding.tilZTNetwork.error = "网络 ID 需为 16 位十六进制字符"
                return@setOnClickListener
            }
            binding.tilZTNetwork.error = null
            val p = profileStore.load()
            p.zeroTierNetworkId = id
            profileStore.save(p)
            Toast.makeText(this, "已保存 ZeroTier 网络 ID: $id", Toast.LENGTH_SHORT).show()
        }

        // 节点选择按钮
        binding.btnSelectNode.setOnClickListener {
            showNodeSelectionDialog()
        }
    }

    /**
     * 加载配置并显示节点
     */
    private fun loadAndDisplayNodes(configPath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val parser = ClashConfigParser()
            val config = parser.parse(configPath)

            withContext(Dispatchers.Main) {
                if (config != null && config.proxies.isNotEmpty()) {
                    clashConfig = config
                    val validNodes = config.getAvailableNodes()

                    if (validNodes.isNotEmpty()) {
                        binding.tvNodeCount.text = "共 ${validNodes.size} 个节点"
                        binding.tvNodeCount.visibility = View.VISIBLE

                        // 自动选择第一个节点
                        if (selectedNode == null) {
                            selectedNode = validNodes.first()
                            updateSelectedNodeUI()
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "成功加载 ${validNodes.size} 个节点",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        binding.tvNodeCount.text = "未找到有效节点"
                        binding.tvNodeCount.visibility = View.VISIBLE
                    }
                } else {
                    binding.tvNodeCount.text = "解析配置失败"
                    binding.tvNodeCount.visibility = View.VISIBLE
                    Toast.makeText(
                        this@MainActivity,
                        "配置解析失败，请检查文件格式",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 显示节点选择对话框
     */
    private fun showNodeSelectionDialog() {
        val config = clashConfig
        if (config == null || config.proxies.isEmpty()) {
            Toast.makeText(this, "请先导入 Clash 配置", Toast.LENGTH_SHORT).show()
            return
        }

        val nodes = config.getAvailableNodes()
        if (nodes.isEmpty()) {
            Toast.makeText(this, "配置中没有有效节点", Toast.LENGTH_SHORT).show()
            return
        }

        // 按类型分组显示
        val groupedNodes = nodes.groupBy { it.type }

        // 创建对话框布局
        val dialogView = layoutInflater.inflate(R.layout.dialog_node_selection, null)
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinner_node_type)
        val spinnerNode = dialogView.findViewById<Spinner>(R.id.spinner_node)

        // 类型选择器
        val types = listOf("全部") + groupedNodes.keys.sorted()
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        spinnerType.adapter = typeAdapter

        // 节点选择器
        var currentNodes = nodes
        var nodeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            currentNodes.map { "${it.name} (${it.getTypeDisplayName()})" }
        )
        spinnerNode.adapter = nodeAdapter

        // 类型切换监听
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentNodes = if (position == 0) {
                    nodes
                } else {
                    groupedNodes[types[position]] ?: nodes
                }
                nodeAdapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    currentNodes.map { "${it.name} (${it.getTypeDisplayName()})" }
                )
                spinnerNode.adapter = nodeAdapter
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 设置当前选中的节点
        selectedNode?.let { selected ->
            val index = currentNodes.indexOfFirst { it.name == selected.name }
            if (index >= 0) {
                spinnerNode.setSelection(index)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("选择节点")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val selectedIndex = spinnerNode.selectedItemPosition
                if (selectedIndex >= 0 && selectedIndex < currentNodes.size) {
                    selectedNode = currentNodes[selectedIndex]
                    updateSelectedNodeUI()
                    Toast.makeText(
                        this,
                        "已选择: ${selectedNode?.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 更新已选节点 UI
     */
    private fun updateSelectedNodeUI() {
        selectedNode?.let { node ->
            binding.tvSelectedNode.text = "当前节点: ${node.name}"
            binding.tvSelectedNode.visibility = View.VISIBLE
        }
    }

    private fun tryStartVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            CZVpnService.start(this)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CZVpnService.state.collect { s ->
                        renderVpnState(s)
                    }
                }
                launch {
                    CZVpnService.traffic.collect { t ->
                        val downSpeed = formatSpeed(t.rxBytesPerSec)
                        val upSpeed = formatSpeed(t.txBytesPerSec)
                        val downTot = formatBytes(t.totalRxBytes)
                        val upTot = formatBytes(t.totalTxBytes)
                        binding.tvTraffic.text =
                            "↓ $downSpeed  ↑ $upSpeed\n总下载: $downTot  总上传: $upTot"
                    }
                }
                launch {
                    renderEngineStatus()
                }
            }
        }
    }

    private fun renderVpnState(s: VpnState) {
        binding.tvStatus.text = when (s) {
            VpnState.CONNECTED -> getString(R.string.status_connected)
            VpnState.CONNECTING -> getString(R.string.status_connecting)
            VpnState.FAILED -> getString(R.string.status_failed)
            else -> getString(R.string.status_disconnected)
        }
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(this, when (s) {
                VpnState.CONNECTED -> R.color.status_on
                VpnState.CONNECTING -> R.color.teal_700
                VpnState.FAILED -> R.color.status_error
                else -> R.color.status_off
            })
        )
        val shouldCheck = (s == VpnState.CONNECTED || s == VpnState.CONNECTING)
        if (binding.switchVpn.isChecked != shouldCheck) {
            binding.switchVpn.setOnCheckedChangeListener(null)
            binding.switchVpn.isChecked = shouldCheck
            binding.switchVpn.setOnCheckedChangeListener { _, c ->
                if (c) tryStartVpn() else CZVpnService.stop(this@MainActivity)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val ztEngine = CZVpnService.lastZeroTierEngine
            val clashEngine = CZVpnService.lastClashEngine
            runOnUiThread {
                if (clashEngine != null) {
                    binding.tvClashStatus.text = if (clashEngine.isReadyFlow.value) "就绪" else "未就绪"
                    binding.tvClashStatus.setTextColor(ContextCompat.getColor(
                        this@MainActivity,
                        if (clashEngine.isReadyFlow.value) R.color.status_on else R.color.status_off
                    ))
                }
                if (ztEngine != null) {
                    val online = ztEngine.isOnlineFlow.value
                    val nid = ztEngine.nodeIdFlow.value
                    binding.tvZTStatus.text = if (online) "在线" else "离线"
                    binding.tvZTStatus.setTextColor(ContextCompat.getColor(
                        this@MainActivity,
                        if (online) R.color.status_on else R.color.status_off
                    ))
                    if (nid != 0L) {
                        binding.tvZTNodeId.visibility = View.VISIBLE
                        binding.tvZTNodeId.text = "Node ID: %010x".format(nid)
                    }
                }
            }
        }
    }

    private fun renderEngineStatus() {
        // 启动时一次性刷新
    }

    private fun formatSpeed(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.2f MB/s".format(bytes / 1048576.0)
        bytes >= 1024 -> "%.2f KB/s".format(bytes / 1024.0)
        else -> "$bytes B/s"
    }
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun copyToAppStorage(uri: android.net.Uri, defaultName: String): String {
        val dir = java.io.File(filesDir, "configs")
        if (!dir.exists()) dir.mkdirs()
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            c.moveToFirst()
            if (idx >= 0) c.getString(idx) else defaultName
        } ?: defaultName
        val out = java.io.File(dir, name)
        contentResolver.openInputStream(uri).use { inp ->
            out.outputStream().use { outS -> inp?.copyTo(outS) }
        }
        return out.absolutePath
    }

    companion object { private const val TAG = "CZ.Main" }
}
