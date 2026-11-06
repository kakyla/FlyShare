package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.network.FtpConnectionStore
import com.tvflyshare.app.network.LanDevice
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.network.SmbConnectionStore
import com.tvflyshare.app.network.SmbHostScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tvflyshare.app.R

data class NetworkUiState(
    val savedConnections: List<SmbConnectionStore.SmbConnection> = emptyList(),
    val devices: List<LanDevice> = emptyList(),
    val isScannerRunning: Boolean = false,
    val scanCompleted: Boolean = false,
    val scanFailedNoNetwork: Boolean = false,
    val localIp: String = "",
    val deviceName: String = "",

    // Multi-select state
    val isMultiSelectMode: Boolean = false,
    val selectedConns: Set<String> = emptySet(), // key: "host/share"
    val selectedDevices: Set<String> = emptySet(), // key: ip
    val selectedFtps: Set<String> = emptySet(), // key: "host:port"

    // Dialogs
    val showDeleteConfirm: Boolean = false,
    val showSmbDialog: Boolean = false,
    val smbHostInput: String = "",

    // FTP
    val ftpConnections: List<FtpConnectionStore.FtpConnection> = emptyList(),
    val showFtpDialog: Boolean = false,
    val ftpHostInput: String = "",
    val ftpPortInput: Int = 21,

    // Navigation to Browse
    val showSmbBrowse: Boolean = false,
    val smbShareName: String = "",
    val smbUsername: String = "",
    val smbPassword: String = "",

    val showFtpBrowse: Boolean = false,
    val ftpHost: String = "",
    val ftpPort: Int = 21,
    val ftpUsername: String = "",
    val ftpPassword: String = "",
    val ftpAnonymous: Boolean = false
) {
    val selectedCount: Int
        get() = selectedConns.size + selectedDevices.size + selectedFtps.size
}

sealed class NetworkAction {
    object RefreshConnections : NetworkAction()
    object RefreshScanner : NetworkAction()
    
    // Dialog & Navigation Controls
    data class ShowSmbDialog(val show: Boolean, val host: String = "") : NetworkAction()
    data class ShowSmbBrowse(
        val show: Boolean, 
        val host: String = "", 
        val shareName: String = "", 
        val username: String = "", 
        val password: String = ""
    ) : NetworkAction()

    data class ShowFtpDialog(val show: Boolean, val host: String = "") : NetworkAction()
    data class ShowFtpBrowse(
        val show: Boolean,
        val host: String = "",
        val port: Int = 21,
        val username: String = "",
        val password: String = "",
        val anonymous: Boolean = false
    ) : NetworkAction()
    
    // Multi-select actions
    data class ToggleMultiSelect(val enabled: Boolean) : NetworkAction()
    data class ToggleConnSelection(val key: String) : NetworkAction()
    data class ToggleDeviceSelection(val ip: String) : NetworkAction()
    data class ToggleFtpSelection(val key: String) : NetworkAction()
    object SelectAll : NetworkAction()
    object ExitMultiSelect : NetworkAction()
    
    // Deletion
    data class ShowDeleteConfirm(val show: Boolean) : NetworkAction()
    object DeleteSelected : NetworkAction()

    // Connection Management
    data class MoveConnectionToFront(val host: String, val share: String) : NetworkAction()
    data class SaveConnection(val conn: SmbConnectionStore.SmbConnection) : NetworkAction()
    data class MoveFtpToFront(val host: String, val port: Int) : NetworkAction()
    data class SaveFtpConnection(val conn: FtpConnectionStore.FtpConnection) : NetworkAction()
}

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val store = SmbConnectionStore(context)
    private val ftpStore = FtpConnectionStore(context)
    private val scanner = SmbHostScanner()

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState.asStateFlow()

    init {
        // Initialize static info
        val ip = NetworkUtils.getLocalIpAddress() ?: context.getString(R.string.network_unknown_ip)
        val model = Build.MODEL ?: "Android Device"
        val name = if (model.length > 20) model.take(20) else model
        _uiState.update { it.copy(localIp = ip, deviceName = name) }

        // Start observing scanner state
        viewModelScope.launch {
            combine(scanner.hosts, scanner.isRunning, scanner.scanCompleted) { hosts, isRunning, scanCompleted ->
                Triple(hosts, isRunning, scanCompleted)
            }.collect { (hosts, isRunning, scanCompleted) ->
                _uiState.update { 
                    it.copy(
                        devices = hosts,
                        isScannerRunning = isRunning,
                        scanCompleted = scanCompleted
                    )
                }
            }
        }

        refreshConnections()
        refreshFtpConnections()
        // startScan() // 取消默认自动扫描
    }

    fun onAction(action: NetworkAction) {
        when (action) {
            is NetworkAction.RefreshConnections -> refreshConnections()
            is NetworkAction.RefreshScanner -> {
                if (_uiState.value.isScannerRunning) {
                    scanner.stop()
                } else {
                    startScan()
                }
            }
            is NetworkAction.ShowSmbDialog -> {
                _uiState.update { it.copy(showSmbDialog = action.show, smbHostInput = action.host) }
            }
            is NetworkAction.ShowSmbBrowse -> {
                _uiState.update { 
                    it.copy(
                        showSmbBrowse = action.show,
                        smbHostInput = action.host,
                        smbShareName = action.shareName,
                        smbUsername = action.username,
                        smbPassword = action.password
                    )
                }
            }
            is NetworkAction.ShowFtpDialog -> {
                _uiState.update { it.copy(showFtpDialog = action.show, ftpHostInput = action.host) }
            }
            is NetworkAction.ShowFtpBrowse -> {
                _uiState.update {
                    it.copy(
                        showFtpBrowse = action.show,
                        ftpHost = action.host,
                        ftpPort = action.port,
                        ftpUsername = action.username,
                        ftpPassword = action.password,
                        ftpAnonymous = action.anonymous
                    )
                }
            }
            is NetworkAction.ToggleMultiSelect -> {
                _uiState.update { it.copy(isMultiSelectMode = action.enabled) }
                if (!action.enabled) clearSelection()
            }
            is NetworkAction.ToggleConnSelection -> {
                val newSet = _uiState.value.selectedConns.toMutableSet()
                if (newSet.contains(action.key)) newSet.remove(action.key) else newSet.add(action.key)
                _uiState.update { it.copy(selectedConns = newSet) }
            }
            is NetworkAction.ToggleDeviceSelection -> {
                val newSet = _uiState.value.selectedDevices.toMutableSet()
                if (newSet.contains(action.ip)) newSet.remove(action.ip) else newSet.add(action.ip)
                _uiState.update { it.copy(selectedDevices = newSet) }
            }
            is NetworkAction.ToggleFtpSelection -> {
                val newSet = _uiState.value.selectedFtps.toMutableSet()
                if (newSet.contains(action.key)) newSet.remove(action.key) else newSet.add(action.key)
                _uiState.update { it.copy(selectedFtps = newSet) }
            }
            is NetworkAction.SelectAll -> {
                val allConns = _uiState.value.savedConnections.map { "${it.host}/${it.share}" }.toSet()
                val allDevices = _uiState.value.devices.map { it.ip }.toSet()
                val allFtps = _uiState.value.ftpConnections.map { "${it.host}:${it.port}" }.toSet()
                _uiState.update { 
                    it.copy(selectedConns = allConns, selectedDevices = allDevices, selectedFtps = allFtps) 
                }
            }
            is NetworkAction.ExitMultiSelect -> {
                _uiState.update { it.copy(isMultiSelectMode = false) }
                clearSelection()
            }
            is NetworkAction.ShowDeleteConfirm -> {
                _uiState.update { it.copy(showDeleteConfirm = action.show) }
            }
            is NetworkAction.DeleteSelected -> {
                deleteSelectedItems()
            }
            is NetworkAction.MoveConnectionToFront -> {
                // 2026-10-04 审计 P1 修复：与 SaveConnection 同规兜底（Keystore 异常设备闪退）；
                // 存储层已降级普通 prefs，此处再兜一层防未预期异常
                try {
                    store.moveToFront(action.host, action.share)
                } catch (e: Exception) {
                    Log.e("NetworkViewModel", "SMB 连接置顶失败（已跳过，不影响连接流程）", e)
                }
                refreshConnections()
            }
            is NetworkAction.SaveConnection -> {
                // 保存连接使用 EncryptedSharedPreferences，低版本设备缺 Keystore 支撑时可能抛异常。
                // 捕获兜底，保存失败不应中断已成功的连接流程，也不应闪退。
                try {
                    store.save(action.conn)
                } catch (e: Exception) {
                    Log.e("NetworkViewModel", "保存 SMB 连接失败（已跳过，不影响当前连接）", e)
                }
                refreshConnections()
            }
            is NetworkAction.MoveFtpToFront -> {
                // 2026-10-04 审计 P1 修复：FTP 侧原先无任何异常兜底（与 SMB 不对称）
                try {
                    ftpStore.moveToFront(action.host, action.port)
                } catch (e: Exception) {
                    Log.e("NetworkViewModel", "FTP 连接置顶失败（已跳过，不影响连接流程）", e)
                }
                refreshFtpConnections()
            }
            is NetworkAction.SaveFtpConnection -> {
                // 2026-10-04 审计 P1 修复：与 SMB 的 SaveConnection 同规兜底，保存失败不闪退
                try {
                    ftpStore.save(action.conn)
                } catch (e: Exception) {
                    Log.e("NetworkViewModel", "保存 FTP 连接失败（已跳过，不影响当前连接）", e)
                }
                refreshFtpConnections()
            }
        }
    }

    private fun refreshConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            val connections = store.getAll()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(savedConnections = connections) }
            }
        }
    }

    private fun refreshFtpConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            val connections = ftpStore.getAll()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(ftpConnections = connections) }
            }
        }
    }

    private fun startScan() {
        val hasNetwork = NetworkUtils.getLocalIpAddress() != null
        _uiState.update { it.copy(scanFailedNoNetwork = !hasNetwork) }
        if (hasNetwork) {
            scanner.start()
        }
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedConns = emptySet(), selectedDevices = emptySet(), selectedFtps = emptySet()) }
    }

    private fun deleteSelectedItems() {
        // 2026-10-10 审计修复：removeAll 是加密存储磁盘 IO（Keystore 读写），切 IO 线程——
        // 10-04 修复只加了 try/catch 兜闪退，IO 仍在主线程，多选批量删除时卡顿
        viewModelScope.launch {
            val state = _uiState.value

            // Remove SMB connections
            val connsToRemove = state.savedConnections.filter {
                state.selectedConns.contains("${it.host}/${it.share}")
            }
            if (connsToRemove.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        store.removeAll(connsToRemove)
                    } catch (e: Exception) {
                        Log.e("NetworkViewModel", "删除 SMB 连接失败", e)
                    }
                }
                refreshConnections()
            }

            // Remove FTP connections
            val ftpsToRemove = state.ftpConnections.filter {
                state.selectedFtps.contains("${it.host}:${it.port}")
            }
            if (ftpsToRemove.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        ftpStore.removeAll(ftpsToRemove)
                    } catch (e: Exception) {
                        Log.e("NetworkViewModel", "删除 FTP 连接失败", e)
                    }
                }
                refreshFtpConnections()
            }

            // Remove devices from scanner memory
            state.devices.forEach { d ->
                if (state.selectedDevices.contains(d.ip)) {
                    scanner.removeDevice(d)
                }
            }

            // Exit multi-select
            onAction(NetworkAction.ExitMultiSelect)
            onAction(NetworkAction.ShowDeleteConfirm(false))
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.release()
    }
}
