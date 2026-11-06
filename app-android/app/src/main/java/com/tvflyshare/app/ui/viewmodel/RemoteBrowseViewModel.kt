package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.interconnect.RemoteFileEntry
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.FileOperations
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.DOWNLOAD_TMP_PREFIX
import com.tvflyshare.app.util.StoragePermissionUtils
import com.tvflyshare.app.filebrowser.model.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import com.tvflyshare.app.R

data class RemoteBrowseUiState(
    val currentPath: String = "",
    val files: List<RemoteFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDownloading: Boolean = false,
    val downloadingFileName: String = "",
    val downloadProgress: Float = 0f,
    val downloadSpeedBytesPerSec: Long = 0, // 平滑后的实时速度（字节/秒），0 表示尚未测出
    val isMultiSelectMode: Boolean = false,
    val selectedFiles: Set<RemoteFileEntry> = emptySet(),
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showCreateFolderDialog: Boolean = false
)

class RemoteBrowseViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        // HTTP 连接/读取超时（毫秒）
        const val HTTP_TIMEOUT_MS = 5000
    }

    private val _uiState = MutableStateFlow(RemoteBrowseUiState())
    val uiState: StateFlow<RemoteBrowseUiState> = _uiState.asStateFlow()

    private var targetIp: String = ""
    private var targetPort: Int = 0
    private var targetName: String = ""
    
    private var currentDownloadJob: Job? = null

    // 2026-10-04 审计 P2 修复：目录浏览在途请求——新请求发出前取消旧请求，防旧响应后到覆盖新目录
    private var listJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    // ===== 下载实时速度采样（单下载会话，前后两次进度差 / 时间差，EMA 0.7/0.3 平滑） =====
    private var lastSpeedSampleBytes = 0L
    private var lastSpeedSampleTime = 0L
    private var smoothedDownloadSpeed = 0L

    /** 记录一次进度采样并返回平滑后的速度（字节/秒） */
    private fun sampleDownloadSpeed(received: Long): Long {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSpeedSampleTime
        if (lastSpeedSampleTime == 0L || elapsed <= 0) {
            lastSpeedSampleBytes = received
            lastSpeedSampleTime = now
            return smoothedDownloadSpeed
        }
        val instant = ((received - lastSpeedSampleBytes) * 1000L) / elapsed
        // 负速度（换文件后字节回落）不参与平滑，保持上次结果
        if (instant >= 0) {
            smoothedDownloadSpeed = if (smoothedDownloadSpeed == 0L) instant else (smoothedDownloadSpeed * 7 + instant * 3) / 10
        }
        lastSpeedSampleBytes = received
        lastSpeedSampleTime = now
        return smoothedDownloadSpeed
    }

    /** 新下载会话开始前复位速度采样状态 */
    private fun resetSpeedSampling() {
        lastSpeedSampleBytes = 0L
        lastSpeedSampleTime = 0L
        smoothedDownloadSpeed = 0L
    }

    fun initDevice(ip: String, port: Int, name: String) {
        if (targetIp == ip && targetPort == port) return
        targetIp = ip
        targetPort = port
        targetName = name
        loadPath("")
    }

    fun loadPath(path: String) {
        _uiState.update { it.copy(isLoading = true, error = null, isMultiSelectMode = false, selectedFiles = emptySet()) }

        listJob?.cancel()
        listJob = viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val encodedPath = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
                val url = URL("http://$targetIp:$targetPort/api/v1/tvfile/list?path=$encodedPath")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val rawFiles = json.decodeFromString<List<RemoteFileEntry>>(responseStr)
                    val sortedFiles = sortFileList(rawFiles, _uiState.value.sortMode)
                    _uiState.update {
                        it.copy(
                            currentPath = path,
                            files = sortedFiles,
                            isLoading = false
                        )
                    }
                } else {
                    // 2026-10-10 审计修复：消费并关闭 errorStream——错误响应体不读取会导致
                    // 连接无法复用；对齐同文件 deleteRemoteEntry 的 finally-disconnect 修复
                    runCatching { connection.errorStream?.close() }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = getApplication<Application>().getString(R.string.remote_browse_error_http, connection.responseCode)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteBrowseVM", "Failed to load remote path", e)
                val raw = e.message ?: ""
                val friendly = when {
                    // 对方服务未启动 / 端口不对（连接被拒绝）
                    raw.contains("Failed to connect", ignoreCase = true) ||
                    raw.contains("Connection refused", ignoreCase = true) ||
                    raw.contains("ECONNREFUSED", ignoreCase = true) ->
                        getApplication<Application>().getString(R.string.remote_browse_error_conn_refused)
                    // 网络不可达 / 超时 / 跨网段隔离（A 连不到 B 常见于此）
                    raw.contains("timed out", ignoreCase = true) ||
                    raw.contains("timeout", ignoreCase = true) ||
                    raw.contains("unreachable", ignoreCase = true) ||
                    raw.contains("No route", ignoreCase = true) ||
                    raw.contains("network is unreachable", ignoreCase = true) ->
                        getApplication<Application>().getString(R.string.remote_browse_error_conn_unreachable)
                    else ->
                        getApplication<Application>().getString(R.string.remote_browse_error_conn, raw)
                }
                _uiState.update {
                    it.copy(isLoading = false, error = friendly)
                }
            } finally {
                // 2026-10-10 审计修复：loadPath 原先从不 disconnect（对齐 deleteRemoteEntry 修复）——
                // 对端无响应/快速连续导航时 socket 悬挂堆积；cancel（快速进目录）也在此统一回收
                connection?.disconnect()
            }
        }
    }

    /**
     * 静默刷新当前远程目录（用于「本机 → 对端」上传完成后，让对端列表立刻显示新文件）。
     * 不弹加载圈、不清空多选/选中态；列表内容无变化时不更新，避免打断用户操作；
     * 失败静默处理（自动刷新不应弹错误打扰用户）。
     */
    fun silentRefresh() {
        if (targetIp.isEmpty()) return
        // 用户正在切换目录/加载中时跳过本轮，避免打断正常导航加载
        if (_uiState.value.isLoading) return
        // 上一轮请求还没结束就跳过（不取消重发）：轮询间隔短于网络超时，取消重发会导致一直刷不出结果
        if (listJob?.isActive == true) return
        val path = _uiState.value.currentPath
        listJob = viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val encodedPath = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
                val url = URL("http://$targetIp:$targetPort/api/v1/tvfile/list?path=$encodedPath")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val sortedFiles = sortFileList(
                        json.decodeFromString<List<RemoteFileEntry>>(responseStr),
                        _uiState.value.sortMode
                    )
                    _uiState.update { state ->
                        // 用户已翻到别的目录 → 丢弃本次旧结果；内容没变 → 不动（保选中与滚动位置）
                        if (state.currentPath != path || state.files == sortedFiles) state
                        else state.copy(files = sortedFiles)
                    }
                } else {
                    runCatching { connection.errorStream?.close() }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 静默失败：保留现有列表，不弹错
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun navigateUp() {
        val currentPath = _uiState.value.currentPath
        if (currentPath.isNotEmpty()) {
            val lastSlash = currentPath.lastIndexOf('/')
            if (lastSlash > 0) {
                loadPath(currentPath.substring(0, lastSlash))
            } else {
                loadPath("")
            }
            // 切换目录时清除多选状态
            if (_uiState.value.isMultiSelectMode) {
                _uiState.update { it.copy(isMultiSelectMode = false, selectedFiles = emptySet()) }
            }
        }
    }

    fun toggleMultiSelectMode() {
        _uiState.update { it.copy(isMultiSelectMode = !it.isMultiSelectMode, selectedFiles = emptySet()) }
    }

    fun toggleSelection(file: RemoteFileEntry) {
        _uiState.update { state ->
            val newSet = state.selectedFiles.toMutableSet()
            if (newSet.contains(file)) newSet.remove(file) else newSet.add(file)
            state.copy(selectedFiles = newSet)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val downloadableFiles = state.files.filter { !it.isDirectory }
            if (state.selectedFiles.size == downloadableFiles.size && downloadableFiles.isNotEmpty()) {
                state.copy(selectedFiles = emptySet())
            } else {
                state.copy(selectedFiles = downloadableFiles.toSet())
            }
        }
    }

    /** 切换排序方式（对标 SMB 管理器）：文件夹置顶 + 选定模式排序客户端生效 */
    fun setSortMode(mode: SortMode) {
        _uiState.update { state ->
            state.copy(sortMode = mode, files = sortFileList(state.files, mode))
        }
    }

    fun setShowCreateFolderDialog(show: Boolean) {
        _uiState.update { it.copy(showCreateFolderDialog = show) }
    }

    /**
     * 在远端（对端共享目录）新建文件夹。对端为本应用同款 HTTP 服务，
     * 走 /api/v1/tvfile/mkdir 端点。成功后刷新当前目录列表。
     */
    fun createFolder(name: String, onComplete: (Boolean, String?) -> Unit) {
        val folderName = name.trim()
        if (folderName.isEmpty()) {
            onComplete(false, getApplication<Application>().getString(R.string.smb_msg_folder_name_empty))
            return
        }
        _uiState.update { it.copy(showCreateFolderDialog = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val encodedPath = URLEncoder.encode(_uiState.value.currentPath, "UTF-8").replace("+", "%20")
                val encodedName = URLEncoder.encode(folderName, "UTF-8").replace("+", "%20")
                val url = URL("http://$targetIp:$targetPort/api/v1/tvfile/mkdir?path=$encodedPath&name=$encodedName")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS

                val success = connection.responseCode == HttpURLConnection.HTTP_OK
                val respStr = if (success) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        loadPath(_uiState.value.currentPath)
                        onComplete(true, folderName)
                    } else {
                        onComplete(false, extractRemoteError(respStr))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("RemoteBrowseVM", "Failed to create folder $folderName", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.message)
                }
            }
        }
    }

    private fun extractRemoteError(resp: String): String {
        return try {
            val idx = resp.indexOf("\"error\"")
            if (idx >= 0) {
                resp.substringAfter("\":\"").substringBefore("\"").takeIf { it.isNotEmpty() } ?: resp
            } else resp
        } catch (_: Exception) {
            resp
        }
    }

    fun downloadSelectedFiles(onComplete: (List<String>, Int) -> Unit) {
        val filesToDownload = _uiState.value.selectedFiles.toList()
        if (filesToDownload.isEmpty()) {
            onComplete(emptyList(), 0)
            return
        }

        _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, downloadSpeedBytesPerSec = 0L, downloadingFileName = getApplication<Application>().getString(R.string.remote_browse_downloading_files, filesToDownload.size), isMultiSelectMode = false, selectedFiles = emptySet()) }
        resetSpeedSampling()

        currentDownloadJob?.cancel()
        currentDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            val successNames = mutableListOf<String>()
            var failedCount = 0
            val totalFiles = filesToDownload.size

            for ((index, fileEntry) in filesToDownload.withIndex()) {
                try {
                    val finalFile = downloadRemoteFile(fileEntry, StoragePermissionUtils.getStorageDir(getApplication())) { received, total ->
                        if (total > 0) {
                            val overallProgress = (index + received.toFloat() / total) / totalFiles
                            _uiState.update { it.copy(downloadProgress = overallProgress, downloadSpeedBytesPerSec = sampleDownloadSpeed(received)) }
                        }
                    }
                    FileOperations.triggerMediaScan(getApplication(), finalFile.absolutePath)
                    successNames.add(fileEntry.name)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("RemoteBrowseVM", "Failed to download file ${fileEntry.name}", e)
                    failedCount++
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
                onComplete(successNames.toList(), failedCount)
            }
        }
    }

    fun resetDownloadState() {
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        _uiState.update { it.copy(isDownloading = false, downloadProgress = 0f) }
    }

    fun downloadFile(fileEntry: RemoteFileEntry, onComplete: (Boolean, String?) -> Unit) {
        _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, downloadSpeedBytesPerSec = 0L, downloadingFileName = fileEntry.name) }
        resetSpeedSampling()

        currentDownloadJob?.cancel()
        currentDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val finalFile = downloadRemoteFile(fileEntry, StoragePermissionUtils.getStorageDir(getApplication())) { received, total ->
                    if (total > 0) {
                        _uiState.update { it.copy(downloadProgress = received.toFloat() / total, downloadSpeedBytesPerSec = sampleDownloadSpeed(received)) }
                    }
                }

                // Trigger MediaStore scan
                FileOperations.triggerMediaScan(getApplication(), finalFile.absolutePath)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
                    onComplete(true, finalFile.absolutePath)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("RemoteBrowseVM", "Failed to download file", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDownloading = false) }
                    onComplete(false, e.message)
                }
            }
        }
    }

    /**
     * 安装远程 APK：先下载到本地，再用 FileProvider 构建 Intent 调起系统安装器。
     * 复用 SMB 侧的 FileActionUtils.buildInstallApkIntent 范式，兼容 Android 7.0+（Uri 暴露防护）。
     */
    fun installApk(fileEntry: RemoteFileEntry, onComplete: (Boolean, String?) -> Unit) {
        _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, downloadingFileName = fileEntry.name) }
        currentDownloadJob?.cancel()
        currentDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val finalFile = downloadRemoteFile(fileEntry, StoragePermissionUtils.getStorageDir(getApplication())) { _, _ -> }

                FileOperations.triggerMediaScan(getApplication(), finalFile.absolutePath)

                val intent = FileActionUtils.buildInstallApkIntent(getApplication(), finalFile)
                if (intent != null) {
                    getApplication<Application>().startActivity(intent)
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
                        onComplete(true, finalFile.absolutePath)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isDownloading = false) }
                        onComplete(false, "无法构建安装 Intent")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("RemoteBrowseVM", "Failed to install APK ${fileEntry.name}", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isDownloading = false) }
                    onComplete(false, e.message)
                }
            }
        }
    }

    // ============================================================
    // 远端删除 / 重命名（走对端 /api/v1/tvfile/delete、/api/v1/tvfile/rename）
    // ============================================================

    /** 删除单个远端文件/文件夹（文件夹由对端递归删除），成功后刷新当前目录 */
    fun deleteFile(entry: RemoteFileEntry, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = deleteRemoteEntry(entry)
            withContext(Dispatchers.Main) {
                if (ok) loadPath(_uiState.value.currentPath)
                onComplete(
                    ok,
                    if (ok) null else getApplication<Application>().getString(R.string.http_api_error_delete_failed)
                )
            }
        }
    }

    /** 批量删除远端选中项，返回 (成功数, 失败数) */
    fun deleteSelectedFiles(onComplete: (Int, Int) -> Unit) {
        val targets = _uiState.value.selectedFiles.toList()
        if (targets.isEmpty()) {
            onComplete(0, 0)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var okCount = 0
            var failCount = 0
            for (entry in targets) {
                if (deleteRemoteEntry(entry)) okCount++ else failCount++
            }
            withContext(Dispatchers.Main) {
                loadPath(_uiState.value.currentPath)
                onComplete(okCount, failCount)
            }
        }
    }

    private suspend fun deleteRemoteEntry(entry: RemoteFileEntry): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val encodedPath = URLEncoder.encode(entry.path, "UTF-8").replace("+", "%20")
            val url = URL("http://$targetIp:$targetPort/api/v1/tvfile/delete?path=$encodedPath")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("RemoteBrowseVM", "Failed to delete remote ${entry.name}", e)
            false
        } finally {
            // 2026-10-04 审计 P2 修复：原先失败路径既不读 errorStream 也不 disconnect，
            // 未回收的 HttpURLConnection 只能靠 GC——批量删除失败会悬挂大量 socket
            connection?.disconnect()
        }
    }

    /** 重命名远端文件/文件夹，成功后刷新当前目录 */
    fun renameFile(entry: RemoteFileEntry, newName: String, onComplete: (Boolean, String?) -> Unit) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            onComplete(false, getApplication<Application>().getString(R.string.http_api_error_invalid_name))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val encodedPath = URLEncoder.encode(entry.path, "UTF-8").replace("+", "%20")
                val encodedName = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
                val url = URL("http://$targetIp:$targetPort/api/v1/tvfile/rename?path=$encodedPath&name=$encodedName")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS

                val success = connection.responseCode == HttpURLConnection.HTTP_OK
                val respStr = if (success) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                withContext(Dispatchers.Main) {
                    if (success) loadPath(_uiState.value.currentPath)
                    onComplete(success, if (success) trimmed else extractRemoteError(respStr))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("RemoteBrowseVM", "Failed to rename remote ${entry.name}", e)
                withContext(Dispatchers.Main) { onComplete(false, e.message) }
            }
        }
    }

    // ============================================================
    // 推送：本机文件 -> 对端共享目录
    // 复用对端 /api/upload 的 octet-stream 流式上传端点（网页上传同款），
    // 对端落盘/去重/进度条目展示（其快速传输页）全部由既有服务端逻辑承接。
    // ============================================================

    /** 发送进度回调参数：当前第 index/total 个、文件名、单文件进度、平滑速度（字节/秒） */
    data class SendProgress(
        val index: Int,
        val total: Int,
        val fileName: String,
        val fileProgress: Float,
        val speedBytesPerSec: Long,
    )

    private var currentSendJob: Job? = null

    /** 是否正在向对端发送文件（供界面屏蔽重复操作） */
    val isSending: Boolean get() = currentSendJob?.isActive == true

    fun cancelSend() {
        currentSendJob?.cancel()
        currentSendJob = null
    }

    /**
     * 把本机文件逐个推送给对端，落到对端当前浏览目录 [remoteDir]（相对其共享根，空串为根目录）。
     *
     * @param onProgress 每个文件的进度回调（~100ms 节流，发送协程线程调用）
     * @param onComplete (成功数, 失败数)，全部结束后在主线程回调
     */
    fun sendFilesToDevice(
        files: List<File>,
        remoteDir: String,
        onProgress: (SendProgress) -> Unit,
        onComplete: (Int, Int) -> Unit
    ) {
        if (files.isEmpty()) {
            onComplete(0, 0)
            return
        }
        currentSendJob?.cancel()
        currentSendJob = viewModelScope.launch(Dispatchers.IO) {
            var okCount = 0
            var failCount = 0
            val total = files.size
            var lastSpeedSampleBytes = 0L
            var lastSpeedSampleTime = 0L
            var smoothedSpeed = 0L

            for ((index, file) in files.withIndex()) {
                try {
                    lastSpeedSampleBytes = 0L
                    lastSpeedSampleTime = 0L
                    uploadLocalFile(file, remoteDir) { received, fileSize ->
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSpeedSampleTime
                        if (lastSpeedSampleTime == 0L || elapsed > 0) {
                            val instant = ((received - lastSpeedSampleBytes) * 1000L) / elapsed
                            if (instant >= 0) {
                                smoothedSpeed = if (smoothedSpeed == 0L) instant else (smoothedSpeed * 7 + instant * 3) / 10
                            }
                            lastSpeedSampleBytes = received
                            lastSpeedSampleTime = now
                        }
                        onProgress(
                            SendProgress(
                                index = index + 1,
                                total = total,
                                fileName = file.name,
                                fileProgress = if (fileSize > 0) received.toFloat() / fileSize else 0f,
                                speedBytesPerSec = smoothedSpeed,
                            )
                        )
                    }
                    okCount++
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("RemoteBrowseVM", "Failed to send file ${file.name}", e)
                    failCount++
                }
            }

            // 上传完成后静默刷新对端当前目录：刚推过去的文件立刻出现在列表里（不再需要手动返回重进）
            silentRefresh()

            withContext(Dispatchers.Main) {
                onComplete(okCount, failCount)
            }
        }
    }

    /**
     * 单个文件 octet-stream 流式上传到对端 /api/upload。
     * 固定长度流式模式（setFixedLengthStreamingMode）避免 HttpURLConnection 整包缓冲内存。
     */
    private suspend fun uploadLocalFile(
        file: File,
        remoteDir: String,
        onProgress: (received: Long, fileSize: Long) -> Unit
    ) {
        val encodedName = android.util.Base64.encodeToString(
            file.name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val pathParam = if (remoteDir.isEmpty()) {
            ""
        } else {
            "&path=" + URLEncoder.encode(remoteDir, "UTF-8").replace("+", "%20")
        }
        val url = URL("http://$targetIp:$targetPort/api/upload?b64name=$encodedName$pathParam")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setFixedLengthStreamingMode(file.length())
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS

        try {
            connection.outputStream.use { out ->
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var sent = 0L
                    var lastNotify = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, bytesRead)
                        sent += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= 100L) {
                            lastNotify = now
                            onProgress(sent, file.length())
                        }
                    }
                    onProgress(sent, file.length())
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw Exception("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    // ============================================================
    // 下载公共实现
    // ============================================================

    /**
     * 在目录内为文件名去重：同名依次生成 name_1.ext、name_2.ext…
     * （原三处下载各自重复的同一份逻辑，收敛于此）
     */
    private fun uniqueTargetFile(dir: File, name: String): File {
        var dest = File(dir, name)
        var counter = 1
        while (dest.exists()) {
            val dotIndex = name.lastIndexOf('.')
            val baseName = if (dotIndex > 0) name.substring(0, dotIndex) else name
            val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
            dest = File(dir, "${baseName}_$counter$ext")
            counter++
        }
        return dest
    }

    /**
     * 下载远端文件到指定目录：隐藏临时名直写目标目录 + 原子改名（对齐上传链路方案）。
     *
     * 下载全程列表里不出现半成品（本机各列表按点前缀隐藏文件过滤 + PathSafety.HIDDEN_TEMP_PREFIXES
     * 精确过滤），失败/取消只清理临时文件，不残留残骸文件；同目录改名 = 同分区原子操作。
     *
     * @param onProgress 进度回调 (已接收字节, 总字节)；总字节未知时为 0
     * @return 改名后的本地最终文件
     * @throws Exception 网络/落盘/改名失败（临时文件已在 finally 清理）
     */
    private suspend fun downloadRemoteFile(
        fileEntry: RemoteFileEntry,
        targetDir: File,
        onProgress: (received: Long, total: Long) -> Unit
    ): File {
        currentCoroutineContext().ensureActive()
        // 2026-10-04 审计 P1 修复：name 完全来自对端 HTTP 响应，含 ../ 或 / 即可路径穿越——
        // 与服务端接收上传时的 sanitizeFileName 防线对称
        val safeName = FileOperations.sanitizeFileName(File(fileEntry.name).name)
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw java.io.IOException("invalid remote file name: ${fileEntry.name}")
        }
        val encodedPath = URLEncoder.encode(fileEntry.path, "UTF-8").replace("+", "%20")
        val url = URL("http://$targetIp:$targetPort/api/v1/tvfile/download?path=$encodedPath")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = HTTP_TIMEOUT_MS

        if (connection.responseCode != HttpURLConnection.HTTP_OK &&
            connection.responseCode != HttpURLConnection.HTTP_PARTIAL
        ) {
            throw Exception("HTTP ${connection.responseCode}")
        }

        val totalSize = connection.contentLength.toLong()
        val finalFile = uniqueTargetFile(targetDir, safeName)
        val tempFile = File.createTempFile("." + DOWNLOAD_TMP_PREFIX, ".tmp", targetDir)
        var renamed = false
        try {
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var received = 0L
                    var lastNotify = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                        received += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= 100L) {
                            lastNotify = now
                            onProgress(received, totalSize)
                        }
                    }
                    // 2026-10-04 审计 P1 修复：完整性校验——对端中途"干净"断开时 read 正常返回 -1，
                    // 半成品会被改名成正式名冒充完整文件（上传链路有长度校验，此处补齐）
                    if (totalSize > 0 && received != totalSize) {
                        throw java.io.IOException("incomplete download: $received/$totalSize bytes")
                    }
                    onProgress(received, totalSize)
                }
            }
            renamed = try {
                tempFile.renameTo(finalFile)
            } catch (e: Exception) {
                Log.w("RemoteBrowseVM", "Rename to target failed, fallback to copy", e)
                false
            }
            if (!renamed) {
                try {
                    tempFile.copyTo(finalFile, overwrite = true)
                } catch (e: Exception) {
                    Log.w("RemoteBrowseVM", "Rename fallback copy failed", e)
                }
            }
            if (!finalFile.exists()) throw Exception("save failed")
            return finalFile
        } finally {
            if (!renamed) {
                tempFile.takeIf { it.exists() }?.delete()
            }
        }
    }

    /** 文件列表排序：文件夹始终置顶，文件按选中模式排序（复用 SMB/FileManager 的 SortMode 语义） */
    private fun sortFileList(list: List<RemoteFileEntry>, sortMode: SortMode): List<RemoteFileEntry> {
        val dirs = list.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = list.filter { !it.isDirectory }
        val sortedFiles = when (sortMode) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
            SortMode.SIZE_ASC -> files.sortedBy { it.size }
            SortMode.TYPE -> files.sortedBy { it.name.substringAfterLast('.').lowercase() }
            SortMode.DATE_DESC -> files.sortedByDescending { it.lastModified }
        }
        return dirs + sortedFiles
    }
}
