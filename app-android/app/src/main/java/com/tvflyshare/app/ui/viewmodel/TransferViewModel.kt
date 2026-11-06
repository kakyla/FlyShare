package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.httpserver.HttpServerManager
import com.tvflyshare.app.httpserver.UploadStage
import com.tvflyshare.app.httpserver.UploadingFileInfo
import com.tvflyshare.app.network.LocalHttpClient
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.qrcode.QrCodeGenerator
import com.tvflyshare.app.R
import com.tvflyshare.app.service.TransferForegroundService
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.StoragePermissionUtils
import com.tvflyshare.app.network.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder

// 空闲轮询间隔：1 秒。太长会让 TV 端进度条目比网页端晚出现太多（网页条目是本机即时插入的）；
// 配合服务端 120ms 采样与上传期 400ms 轮询，进度显示平滑且负载可控。
private const val POLL_INTERVAL_MS = 1000L

/** 文件列表数据模型（与 HttpServerManager 的 UploadedFile 对应） */
@Serializable
data class TransferFileItem(
    val id: String,           // 相对共享根目录的路径
    val name: String,         // 文件名（不含路径）
    val size: Long,
    val lastModified: Long = 0L,
    val isDirectory: Boolean = false,
)

/** 正在上传中的文件（与 HttpServerManager 的 UploadingFileInfo 对应，仅用于列表展示） */
data class UploadingTransferItem(
    val name: String,
    val size: Long,
    val received: Long = 0,   // 已接收字节数（实时进度）
    val startedAt: Long,
    val isSaving: Boolean = false, // true=已收完正在写盘，false=正在接收
    val bytesPerSecond: Long = 0, // 平滑后的实时速度（字节/秒），0 表示尚未测出
    // 服务端每请求唯一 id（"up_N"）——2026-10-04 审计 P0 修复：
    // LazyColumn key 原用 startedAt+name，同毫秒并发上传同名文件撞 key 会崩溃
    val id: String = "",
)

/** 传输页 UI 状态 */
data class TransferUiState(
    val ipAddress: String? = null,
    val qrBitmap: Bitmap? = null,
    val fileList: List<TransferFileItem> = emptyList(),
    val uploadingFiles: List<UploadingTransferItem> = emptyList(),
    val isLoading: Boolean = true,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val newFileCount: Int = 0, // 新增：记录新到达文件的数量
    val networkType: NetworkType = NetworkType.NONE, // 新增：统一管理网络状态
    val fileToDelete: TransferFileItem? = null,
    val showBatchDeleteConfirm: Boolean = false,
    val currentPath: String = "", // 当前所在目录（相对共享根目录的路径，空字符串为根目录）
    // TV 端「清空列表」隐藏的条目 id（仅本页视图，不删文件、不影响电脑端；离开本页自动恢复）
    val hiddenIds: Set<String> = emptySet(),
)

sealed class TransferUiEvent {
    object Start : TransferUiEvent()
    object Stop : TransferUiEvent()
    object ToggleMultiSelectMode : TransferUiEvent()
    object SelectAll : TransferUiEvent()
    object DeleteSelectedFiles : TransferUiEvent()
    data class ToggleSelection(val fileId: String) : TransferUiEvent()
    data class DeleteFile(val fileId: String) : TransferUiEvent()
    object ClearNewFileIndicator : TransferUiEvent()
    data class IncrementNewFileCount(val count: Int) : TransferUiEvent()
    data class ShowBatchDeleteConfirm(val show: Boolean) : TransferUiEvent()
    data class ShowDeleteConfirm(val file: TransferFileItem?) : TransferUiEvent()
    object ClearList : TransferUiEvent()
    data class NavigateTo(val path: String) : TransferUiEvent()
    object NavigateUp : TransferUiEvent()
}

// ============================================================
// TransferViewModel
//
// 职责：管理传输页全部数据逻辑，与 HomeViewModel 风格统一
//   - 启动 HTTP 前台服务
//   - 获取本机 IP + 生成二维码
//   - 加载已有文件列表（重试 3 次）
//   - 轮询上传状态（eventId 去重 + 增量追加）
//   - 删除文件（远程 + 本地同步）
//   - 打开/安装文件
// ============================================================

class TransferViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * 进程级缓存：IP 地址 + 二维码 Bitmap
         *
         * 原因：NetworkInterface.getNetworkInterfaces() 在 MuMu 模拟器上短时间内重复调用
         * 会越来越慢（首次 ~120ms，第二次 ~2000ms，第三次更慢），导致连续进出页面时
         * 严重卡顿甚至黑屏。TV 设备 IP 在应用运行期间基本不变，缓存后避免重复系统调用。
         * 二维码内容取决于 IP，IP 不变时二维码也不需要重新生成。
         */
        @Volatile
        private var cachedIp: String? = null
        @Volatile
        private var cachedQr: Bitmap? = null
    }

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    /** 轮询协程引用，便于在 onCleared 取消 */
    private var pollJob: kotlinx.coroutines.Job? = null

    /** 上一轮从服务端拉到的原始 id 集合：用于「新文件」判定，不受 TV 端「清空列表」隐藏影响 */
    private var lastRawIds: Set<String> = emptySet()

    /**
     * 2026-10-04 审计 P2 修复(a)：刚删除的文件 id 集合。
     * 删除成功后到删除生效于服务端之间存在窗口，此间发起的在途轮询响应会把文件"插回来"
     * 并误报"新文件"——一轮内跳过这些 id；确认服务端已无此文件后移出。
     * （原 lastSeenEventId 死变量已删，其注释描述的 eventId 去重机制实际不存在）
     */
    private val recentlyDeletedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // TODO(Kaky, M7): 网络变化监听由 VM 直接持有 ConnectivityManager/NetworkCallback（android.net 平台依赖），
    // 应抽取为 connectivity Repository，VM 仅订阅状态流，避免平台 API 泄漏到业务层。
    /** 网络变化监听器：网络切换时清空 IP/二维码缓存，触发重新获取 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 新增：用于通知 UI 层有新文件到达的事件流（无缓冲，即发即弃，避免旧事件重复消费）
    private val _newFileEvent = kotlinx.coroutines.flow.MutableSharedFlow<Int>(replay = 0)
    val newFileEvent = _newFileEvent.asSharedFlow()

    fun onEvent(event: TransferUiEvent) {
        when (event) {
            is TransferUiEvent.Start -> start()
            is TransferUiEvent.Stop -> stop()
            is TransferUiEvent.ToggleMultiSelectMode -> toggleMultiSelectMode()
            is TransferUiEvent.SelectAll -> selectAll()
            is TransferUiEvent.DeleteSelectedFiles -> deleteSelectedFiles()
            is TransferUiEvent.ToggleSelection -> toggleSelection(event.fileId)
            is TransferUiEvent.DeleteFile -> deleteFile(event.fileId)
            is TransferUiEvent.ClearNewFileIndicator -> clearNewFileIndicator()
            is TransferUiEvent.IncrementNewFileCount -> incrementNewFileCount(event.count)
            is TransferUiEvent.ShowBatchDeleteConfirm -> _uiState.update { it.copy(showBatchDeleteConfirm = event.show) }
            is TransferUiEvent.ShowDeleteConfirm -> _uiState.update { it.copy(fileToDelete = event.file) }
            is TransferUiEvent.ClearList -> clearList()
            is TransferUiEvent.NavigateTo -> navigateTo(event.path)
            is TransferUiEvent.NavigateUp -> navigateUp()
        }
    }

    init {
        // 仅启动 HTTP 服务，不启动数据加载（数据加载由 start() 控制，配合 Composable 生命周期）
        startHttpService()
        // 注册网络变化监听，切换 WiFi/网线时自动刷新二维码
        registerNetworkCallback()
    }

    /**
     * 启动 HTTP 服务（仅一次，在 init 调用）。
     * 登记为 transfer 持有者；页面离开时（onCleared）release。
     */
    private fun startHttpService() {
        TransferForegroundService.acquire(getApplication<Application>(), TransferForegroundService.OWNER_TRANSFER)
    }

    /**
     * 注册网络变化监听
     *
     * 当用户切换 WiFi/网线、或网络重新连接时，清空 IP/二维码缓存，重新获取。
     * 当网络断开时（onLost），清空 IP 并更新 UI 状态为无网络。
     */
    private fun registerNetworkCallback() {
        val context = getApplication<Application>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        
        // 初始化一次当前网络状态
        val initialNetworkType = NetworkUtils.getNetworkType(context)
        _uiState.update { it.copy(networkType = initialNetworkType) }
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络可用（可能是切换到新网络），清空缓存
                cachedIp = null
                cachedQr = null
                // 如果页面正在显示（pollJob 活动中），立即刷新 IP 和二维码
                if (pollJob?.isActive == true) {
                    refreshIpAndQr()
                }
            }

            override fun onLost(network: Network) {
                // 网络断开
                cachedIp = null
                cachedQr = null
                _uiState.update { 
                    it.copy(
                        ipAddress = null, 
                        qrBitmap = null,
                        networkType = NetworkType.NONE
                    ) 
                }
            }
        }
        networkCallback = callback
        // 2026-10-04 审计 P2 修复：空 NetworkRequest 匹配所有网络（含蜂窝/VPN），
        // onAvailable 对每个网络都清缓存重算，极端多网并存下二维码可能短暂指向错误地址——
        // 只订阅本机业务相关的 Wi-Fi / 以太网
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build(),
            callback
        )
    }

    /**
     * 重新获取 IP 和二维码并更新 UI（网络切换后调用）
     */
    private fun refreshIpAndQr() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val currentNetworkType = NetworkUtils.getNetworkType(context)
            
            val ip = withContext(Dispatchers.IO) { NetworkUtils.getLocalIpAddress() }
            var qr: Bitmap? = null
            if (ip != null) {
                qr = withContext(Dispatchers.IO) {
                    QrCodeGenerator.generate("http://$ip:${HttpServerManager.getPort()}")
                }
            }
            cachedIp = ip
            cachedQr = qr
            _uiState.update { 
                it.copy(
                    ipAddress = ip, 
                    qrBitmap = qr,
                    networkType = currentNetworkType
                ) 
            }
        }
    }

    /**
     * 启动数据加载 + 轮询（由 TransferScreen 的 DisposableEffect 调用）
     *
     * 必须在 Composable 进入时调用，离开时调用 stop() 取消。
     * 不能在 init 里启动——ViewModel 的 onCleared 调用时机晚于 Composable 离开，
     * 会导致旧轮询协程继续运行，阻塞新的 HTTP 请求（keep-alive 连接池竞争），
     * 表现为连续进出页面时第二次进入卡 2 秒。
     */
    fun start() {
        if (pollJob?.isActive == true) return
        loadDataAndPoll()
    }

    /**
     * 停止数据加载 + 轮询（由 TransferScreen 的 DisposableEffect 调用）
     */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        // 方案A：离开快速传输页即恢复「清空列表」隐藏的条目显示（下次进来看到全量）
        if (_uiState.value.hiddenIds.isNotEmpty()) {
            _uiState.update { it.copy(hiddenIds = emptySet()) }
        }
    }

    /**
     * 获取 IP/二维码 + 初始加载文件 + 设置 baseline + 启动轮询
     *
     * 注意：IP 获取和二维码生成是耗时操作，必须在 IO 线程执行，不能阻塞主线程。
     */
    private fun loadDataAndPoll() {
        // 耗时操作（IP 获取 + 二维码生成 + 文件加载 + 轮询）全部在协程 IO 线程
        pollJob = viewModelScope.launch {
            // 上传进度改为事件驱动：订阅服务端进度流（服务端 ~120ms 采样、内容去重后推送），
            // 条目出现/推进/移除即时反映到界面，不再等轮询跳——与网页端进度近乎同步。
            launch {
                HttpServerManager.uploadingFlow.collect { infos ->
                    val files = _uiState.value.fileList
                    _uiState.update { it.copy(uploadingFiles = mergeUploading(files, toUiItems(infos))) }
                }
            }

            // 获取本机 IP + 生成二维码（使用进程级缓存，避免重复系统调用导致越来越慢）
            var ip = cachedIp
            var qr = cachedQr
            if (ip == null) {
                ip = withContext(Dispatchers.IO) { NetworkUtils.getLocalIpAddress() }
                if (ip != null) {
                    qr = withContext(Dispatchers.IO) {
                        QrCodeGenerator.generate("http://$ip:${HttpServerManager.getPort()}")
                    }
                }
                cachedIp = ip
                cachedQr = qr
            }
            _uiState.update { it.copy(ipAddress = ip, qrBitmap = qr) }

            // 初始加载（服务可能还没启动，连接失败时重试；成功响应即使空列表也立即结束）
            var initial: List<TransferFileItem> = emptyList()
            for (i in 1..3) {
                val result = fetchFiles()
                if (result.isSuccess) {
                    initial = result.getOrDefault(emptyList())
                    break
                }
                delay(300)
            }
            lastRawIds = initial.map { it.id }.toSet()
            val hiddenAtInit = _uiState.value.hiddenIds
            val sorted = initial
                .filter { it.id !in hiddenAtInit }
                .sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
            _uiState.update { it.copy(fileList = sorted, isLoading = false) }

            // 轮询全量文件列表，以支持多端同步（如网页端删除后 TV 端自动消失）。
            // 上传进度已由上方进度流驱动，此处只负责文件列表与新文件事件。
            while (true) {
                // 上传期间保持较短的列表刷新，让刚完成的文件尽快出现在列表；空闲时维持原间隔
                val interval = if (_uiState.value.uploadingFiles.isNotEmpty()) 400L else POLL_INTERVAL_MS
                delay(interval)
                // 2026-10-04 审计 P2 修复(b)：发起时快照目录，返回时 currentPath 已变则丢弃本次响应，
                // 避免导航后旧目录内容覆盖新目录（在途响应错配）
                val fetchedPath = _uiState.value.currentPath
                val result = fetchFiles(fetchedPath)
                if (result.isSuccess) {
                    if (_uiState.value.currentPath != fetchedPath) continue
                    val currentFiles = result.getOrDefault(emptyList())
                    val isLoading = _uiState.value.isLoading

                    // 判断是否有新文件到达：用「上一轮原始 id 集合」比较，不受 TV 端「清空列表」隐藏影响
                    // 导航切换目录（isLoading 为 true）期间不触发"新文件"提示，避免误报
                    if (!isLoading) {
                        val newFiles = currentFiles.filter { newFile ->
                            newFile.id !in lastRawIds &&
                                // 删除后一轮轮询内的旧响应回插不算新文件
                                newFile.id !in recentlyDeletedIds
                        }
                        if (newFiles.isNotEmpty()) {
                            // 发现有新文件到达，发送事件通知 UI 层
                            _newFileEvent.emit(newFiles.size)
                        }
                    }
                    // 无论是否在目录切换，都同步原始 id 集合，避免下次误判为"新文件"
                    lastRawIds = currentFiles.map { it.id }.toSet()
                    // 服务端已确认消失的"刚删除"id 放行移出；仍在服务端的保留继续守卫
                    recentlyDeletedIds.retainAll { id -> currentFiles.none { it.id == id } }

                    val hiddenNow = _uiState.value.hiddenIds
                    val sortedFiles = currentFiles
                        .filter { it.id !in hiddenNow }
                        .sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
                    _uiState.update { it.copy(fileList = sortedFiles, isLoading = false) }
                }
            }
        }
    }

    /**
     * 进入指定目录（path 为相对共享根目录的路径）。
     * 重置列表与多选状态，由轮询协程按新的 currentPath 自动刷新。
     */
    fun navigateTo(path: String) {
        _uiState.update {
            it.copy(
                currentPath = path,
                fileList = emptyList(),
                isLoading = true,
                isMultiSelectMode = false,
                selectedIds = emptySet(),
                showBatchDeleteConfirm = false,
            )
        }
    }

    /** 返回上一级目录（已在根目录则无操作） */
    fun navigateUp() {
        val cur = _uiState.value.currentPath
        if (cur.isEmpty()) return
        val parent = cur.substringBeforeLast('/', "")
        navigateTo(parent)
    }

    /**
     * 删除文件（远程删除成功后本地同步移除）
     *
     * 注意：当后端返回 404（文件已不存在，例如被外部删除）时，也应移除本地项，
     * 否则本地列表会与磁盘不同步，导致该条目永远"删不掉"。
     */
    fun deleteFile(fileId: String) {
        viewModelScope.launch {
            val code = deleteFileRemote(fileId)
            // 200 = 删除成功；404 = 文件已不存在；两者都应移除本地项
            if (code == 200 || code == 404) {
                // 2026-10-04 审计 P2 修复(a)：登记刚删除的 id，一轮轮询内防旧响应回插/误报
                recentlyDeletedIds.add(fileId)
                _uiState.update { state ->
                    state.copy(
                        fileList = state.fileList.filterNot { it.id == fileId },
                        fileToDelete = null
                    )
                }
            } else {
                // 2026-10-10 审计修复：删除失败原先完全静默（对话框默默关闭），用户无从得知没删掉
                GlobalToastManager.showToast(
                    getApplication<Application>().getString(R.string.transfer_msg_delete_failed)
                )
                _uiState.update { it.copy(fileToDelete = null) }
            }
        }
    }

    // 新增：清除 UI 状态中的新文件数量
    fun clearNewFileIndicator() {
        _uiState.update { it.copy(newFileCount = 0) }
    }
    
    // 新增：累加 UI 状态中的新文件数量
    fun incrementNewFileCount(count: Int) {
        _uiState.update { it.copy(newFileCount = it.newFileCount + count) }
    }

    // ============================================================
    // 多选模式支持
    // ============================================================

    fun toggleMultiSelectMode() {
        _uiState.update { it.copy(isMultiSelectMode = !it.isMultiSelectMode, selectedIds = emptySet()) }
    }

    fun toggleSelection(fileId: String) {
        _uiState.update { state ->
            val newSet = state.selectedIds.toMutableSet()
            if (newSet.contains(fileId)) newSet.remove(fileId) else newSet.add(fileId)
            state.copy(selectedIds = newSet)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            if (state.selectedIds.size == state.fileList.size) {
                state.copy(selectedIds = emptySet()) // Cancel all
            } else {
                state.copy(selectedIds = state.fileList.map { it.id }.toSet())
            }
        }
    }

    /**
     * 清空列表（仅 TV 端视图）：把当前目录已显示的条目加入隐藏集合，从本页隐藏。
     * - 不删除任何文件、不影响电脑端（电脑端直接读服务端目录，不知道这里的隐藏）；
     * - 新传进来的文件不在隐藏集合内，仍会正常显示；
     * - 离开本页自动恢复显示（见 [stop]）。
     */
    fun clearList() {
        _uiState.update { state ->
            if (state.fileList.isEmpty()) {
                state
            } else {
                state.copy(
                    hiddenIds = state.hiddenIds + state.fileList.map { it.id },
                    fileList = emptyList(),
                    isMultiSelectMode = false,
                    selectedIds = emptySet(),
                    showBatchDeleteConfirm = false,
                )
            }
        }
    }

    fun deleteSelectedFiles() {
        viewModelScope.launch {
            val idsToDelete = _uiState.value.selectedIds.toList()
            _uiState.update { it.copy(isMultiSelectMode = false, selectedIds = emptySet(), showBatchDeleteConfirm = false) }
            var okCount = 0
            var failCount = 0
            idsToDelete.forEach { id ->
                val code = deleteFileRemote(id)
                if (code == 200 || code == 404) {
                    // 2026-10-04 审计 P2 修复(a)：批量删除同样登记，防旧响应回插/误报
                    recentlyDeletedIds.add(id)
                    okCount++
                    _uiState.update { state ->
                        state.copy(fileList = state.fileList.filterNot { it.id == id })
                    }
                } else {
                    failCount++
                }
            }
            // 2026-10-10 审计修复：删除失败原先完全静默——网络瞬断/对端服务重启时
            // 界面显示"已退出多选"，文件实际还在，用户无从得知
            if (failCount > 0) {
                GlobalToastManager.showToast(
                    getApplication<Application>().getString(R.string.smb_msg_deleted_partial, okCount, failCount)
                )
            }
        }
    }

    /**
     * 打开/安装文件
     *
     * 使用传入的 Context (如 Activity context) 以避免直接使用 Application Context 抛出异常
     * 文件已被删除时返回 false，调用方据此从列表移除
     */
    fun openFileWithContext(context: Context, file: TransferFileItem): Boolean {
        // 文件夹不应被"打开"，进入目录由 UI 层处理
        if (file.isDirectory) return false
        val storageDir = StoragePermissionUtils.getStorageDir(context)
        val fileObj = File(storageDir, file.id)
        if (!fileObj.exists()) {
            // 文件已被删除，本地同步移除
            _uiState.update { state ->
                state.copy(fileList = state.fileList.filterNot { it.id == file.id })
            }
            return false
        }
        val intent = if (FileActionUtils.isApkFile(file.name)) {
            FileActionUtils.buildInstallApkIntent(context, fileObj)
        } else {
            FileActionUtils.buildOpenFileIntent(context, fileObj)
        }
        if (intent != null) {
            try {
                // TV 上部分系统可能需要 NEW_TASK flag，尽管 utils 里加了，保险起见再加一次
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("TransferViewModel", "Failed to start activity", e)
                return false
            }
        } else {
            return false
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        // 注销网络监听，避免 ViewModel 销毁后回调仍触发（导致内存泄漏 + 空指针）
        networkCallback?.let { callback ->
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        }
        networkCallback = null

        // 离开快速传输页：注销 transfer 持有者。
        // 服务不再由本页持有；若 TV 互联页也未持有，则延迟 30s 自动关闭（见 TransferForegroundService）。
        TransferForegroundService.release(getApplication<Application>(), TransferForegroundService.OWNER_TRANSFER)
    }

    // ============================================================
    // 网络请求（私有，与原 TransferScreen 逻辑一致）
    // ============================================================

    /**
     * 速度采样：条目 key -> (上次已接收字节, 上次采样时刻)，用于计算瞬时速度。
     * 只在内存中存活，条目结束后随 toUiItems 的清理逻辑一并移除。
     */
    private val speedSamples = mutableMapOf<String, Pair<Long, Long>>()

    /** 平滑后的实时速度：条目 key -> 字节/秒（EMA：0.7 旧值 + 0.3 新值，避免单次采样抖动） */
    private val smoothedSpeed = mutableMapOf<String, Long>()

    /** 条目唯一 key（同名文件并发上传时靠 startedAt 区分） */
    private fun sampleKey(startedAt: Long, name: String) = "${startedAt}_$name"

    /**
     * 合并上传中条目：SAVING 阶段且同名文件已出现在文件列表里的条目不再显示。
     *
     * 原因：服务端 rename 成正式名后文件立刻进列表，但「上传中」条目要等轮询协程
     * 下一跳（≤120ms）才移除；TV 每 400ms 轮询一次，可能撞上短暂的
     * 「文件 + 上传条目」并存（用户看到的收尾「两个文件」残留窗口）。此处在端上兜掉。
     * 只过滤 isSaving 的条目：RECEIVING 阶段同名旧文件在列表属正常并存，不能误删进度行。
     */
    private fun mergeUploading(
        files: List<TransferFileItem>,
        uploading: List<UploadingTransferItem>,
    ): List<UploadingTransferItem> = uploading.filter { up ->
        !(up.isSaving && files.any { it.name == up.name })
    }

    /**
     * 服务端进度信息 -> 界面条目，并基于前后两次采样的字节差计算实时速度（EMA 平滑）。
     * 由上传进度流驱动调用（~120ms 级），速度与百分比随进度同步刷新。
     */
    private fun toUiItems(infos: List<UploadingFileInfo>): List<UploadingTransferItem> {
        return try {
            val now = System.currentTimeMillis()
            val items = infos.map { info ->
                // 2026-10-04 审计修复：优先用每请求唯一 id 做采样键，防同名并发上传共享速度样本
                val key = info.id.ifEmpty { sampleKey(info.startedAt, info.name) }
                val prev = speedSamples[key]
                var speed = smoothedSpeed[key] ?: 0L
                if (prev != null) {
                    val elapsed = now - prev.second
                    if (elapsed > 0) {
                        val instant = ((info.received - prev.first) * 1000L) / elapsed
                        // 负速度（条目重建/字节回落）不参与平滑，保持上次结果
                        if (instant >= 0) {
                            speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                        }
                    }
                }
                speedSamples[key] = info.received to now
                smoothedSpeed[key] = speed
                UploadingTransferItem(
                    name = info.name,
                    size = info.size,
                    received = info.received,
                    startedAt = info.startedAt,
                    isSaving = info.stage == UploadStage.SAVING,
                    bytesPerSecond = speed,
                    id = info.id,
                )
            }
            // 清理已结束条目的采样记录，避免 map 随上传次数无限增长
            val aliveKeys = items.map { it.id.ifEmpty { sampleKey(it.startedAt, it.name) } }.toSet()
            speedSamples.keys.retainAll(aliveKeys)
            smoothedSpeed.keys.retainAll(aliveKeys)
            items
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    /**
     * 拉取文件列表
     *
     * 返回 Result：
     * - Success(list) — HTTP 200 成功响应，list 可能为空（空目录是合法状态，不需要重试）
     * - Failure — 连接失败/超时/非 200（HTTP 服务还没起来，需要重试）
     *
     * 注意：不能用"列表是否为空"判断是否需要重试——空目录返回空列表是正常的，
     * 否则会白白重试 3 次 delay(300)，让页面卡在"加载中"近 1 秒。
     */
    private suspend fun fetchFiles(forPath: String = _uiState.value.currentPath): Result<List<TransferFileItem>> {
        // 根据调用时刻的目录快照拼装 path 参数（根目录为空）——
        // 2026-10-04 审计 P2 修复(b)：原先在函数内实时读 currentPath，快照比对无法成立
        val path = forPath
        val url = if (path.isEmpty()) {
            "/api/files"
        } else {
            "/api/files?path=" + URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        }
        val response = LocalHttpClient.get(url)
        if (response == null || response.code != 200 || response.body == null) {
            return Result.failure(Exception("HTTP ${response?.code ?: -1}"))
        }
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val list = json.decodeFromString<List<TransferFileItem>>(response.body)
            // 文件夹优先，文件按最后修改时间倒序（最新上传的排最前）
            Result.success(list.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified })))
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 发送 DELETE 请求，返回 HTTP 状态码（失败返回 -1）
     *
     * 使用 path 查询参数（相对共享根目录的路径）。编码注意：URLEncoder.encode 会把
     * 空格编码成 "+"，但后端按标准 URL 参数解析时 "+" 即空格，这里与后端约定保持一致；
     * 同时把 "+" 替换为标准 "%20" 以兼容含空格的文件名（如 "PVC 压延体系.jpg"）。
     */
    private suspend fun deleteFileRemote(fileId: String): Int {
        val encodedPath = URLEncoder.encode(fileId, "UTF-8").replace("+", "%20")
        return LocalHttpClient.delete("/api/files?path=$encodedPath")
    }
}
