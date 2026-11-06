package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.ftp.FtpBrowser
import com.tvflyshare.app.ftp.FtpClientManager
import com.tvflyshare.app.ftp.FtpConnectionState
import com.tvflyshare.app.ftp.FtpFileEntry
import com.tvflyshare.app.ftp.FtpFileOperator
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.filebrowser.model.SortMode
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.StoragePermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.tvflyshare.app.R

// ============================================================
// UI 状态与事件模型
// ============================================================

data class FtpBrowseUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val currentPath: String = "/",
    val fileList: List<FtpFileEntry> = emptyList(),
    val displayList: List<FtpFileEntry> = emptyList(),
    val downloadingFile: FtpFileEntry? = null,
    val downloadPercent: Int = 0,
    val selectedEntry: FtpFileEntry? = null,
    val showActionMenu: Boolean = false,
    val renameTarget: FtpFileEntry? = null,
    val pendingDelete: List<FtpFileEntry> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val showCreateFolderDialog: Boolean = false,
    val detailsTarget: FtpFileEntry? = null,
    val isGridView: Boolean = false
)

sealed class FtpUiEvent {
    data class ToggleMultiSelect(val enabled: Boolean) : FtpUiEvent()
    data class ToggleSelection(val path: String) : FtpUiEvent()
    object SelectAll : FtpUiEvent()

    data class OpenFile(val file: File) : FtpUiEvent()
    data class HandleEntryAction(val entry: FtpFileEntry, val action: FtpAction) : FtpUiEvent()

    data class ShowActionMenu(val show: Boolean, val entry: FtpFileEntry? = null) : FtpUiEvent()
    data class ShowRenameDialog(val entry: FtpFileEntry?) : FtpUiEvent()
    data class ConfirmRename(val newName: String) : FtpUiEvent()

    object ToggleGridView : FtpUiEvent()
    data class SetSortMode(val mode: SortMode) : FtpUiEvent()
    object ToggleHiddenFiles : FtpUiEvent()
    data class ShowCreateFolderDialog(val show: Boolean) : FtpUiEvent()
    data class ConfirmCreateFolder(val name: String) : FtpUiEvent()

    data class RequestDelete(val entries: List<FtpFileEntry>) : FtpUiEvent()
    data class ConfirmDelete(val entries: List<FtpFileEntry>) : FtpUiEvent()

    data class RequestDownload(val entries: List<FtpFileEntry>) : FtpUiEvent()

    object NavigateBack : FtpUiEvent()
}

enum class FtpAction(val titleResId: Int) {
    OPEN(R.string.smb_action_open),
    INSTALL(R.string.smb_action_install),
    DELETE(R.string.smb_action_delete),
    RENAME(R.string.smb_action_rename),
    DOWNLOAD(R.string.smb_action_download),
    DETAILS(R.string.smb_action_details)
}

// ============================================================
// FtpBrowseViewModel
// ============================================================

class FtpBrowseViewModel(
    application: Application,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val anonymous: Boolean
) : AndroidViewModel(application) {

    val clientManager = FtpClientManager()
    val browser = FtpBrowser(clientManager)
    private val operator = FtpFileOperator(clientManager, application)

    private val _uiState = MutableStateFlow(FtpBrowseUiState())
    val uiState: StateFlow<FtpBrowseUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<FtpUiEvent>()
    val uiEvent: SharedFlow<FtpUiEvent> = _uiEvent.asSharedFlow()

    private var loadJob: Job? = null
    private var isInitialized = false

    val connState: StateFlow<FtpConnectionState> = clientManager.state

    fun initConnection() {
        if (isInitialized && clientManager.state.value !is FtpConnectionState.Error) return
        isInitialized = true

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                clientManager.connect(host, port, user, pass, anonymous)
            }
            if (success) {
                loadDirectory("/")
            } else {
                val err = (clientManager.state.value as? FtpConnectionState.Error)
                    ?.getMessage(getApplication()) ?: getApplication<Application>().getString(R.string.ftp_connect_failed_fallback)
                _uiState.update { it.copy(isLoading = false, loadError = err) }
            }
        }
    }

    fun refresh() {
        loadDirectory(_uiState.value.currentPath)
    }

    /**
     * 错误态重试：强制断开并重新连接后重载当前目录。
     *
     * 不能用 [initConnection]：其守卫 `if (isInitialized && state !is Error) return`
     * 会在"连接已静默断开但 state 仍为 Connected"时直接返回，导致浏览中掉线后重试不重连。
     * 这里先置 [isInitialized]=false 再断开重连，确保每次重试都建立新连接。
     */
    fun retry() {
        isInitialized = false
        val path = _uiState.value.currentPath
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            val success = withContext(Dispatchers.IO) {
                // 2026-10-04 审计 P2 修复：去掉手动 disconnect——connect() 内部持锁后
                // 首行即 disconnect 旧连，原先锁外的手动断开可与在建连并发拆 socket
                clientManager.connect(host, port, user, pass, anonymous)
            }
            if (success) {
                loadDirectory(path)
            } else {
                val err = (clientManager.state.value as? FtpConnectionState.Error)
                    ?.getMessage(getApplication())
                    ?: getApplication<Application>().getString(R.string.ftp_connect_failed_fallback)
                _uiState.update { it.copy(isLoading = false, loadError = err) }
            }
        }
    }

    fun loadDirectory(path: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null, currentPath = path) }
            try {
                val files = withContext(Dispatchers.IO) {
                    browser.listDirectory(path)
                }
                _uiState.update { state ->
                    val filtered = if (state.showHiddenFiles) files else files.filter { !it.name.startsWith(".") }
                    val sorted = sortFileList(filtered, state.sortMode)
                    state.copy(isLoading = false, fileList = files, displayList = sorted, loadError = null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        fileList = emptyList(),
                        loadError = getApplication<Application>().getString(R.string.ftp_load_failed) + ": " + (e.message ?: "")
                    )
                }
            }
        }
    }

    private fun sortFileList(list: List<FtpFileEntry>, sortMode: SortMode): List<FtpFileEntry> {
        val dirs = list.filter { it.isDirectory }
        val files = list.filter { !it.isDirectory }
        val sortedDirs = dirs.sortedBy { it.name.lowercase() }
        val sortedFiles = when (sortMode) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
            SortMode.SIZE_ASC -> files.sortedBy { it.size }
            SortMode.TYPE -> files.sortedBy { it.name.substringAfterLast(".").lowercase() }
            SortMode.DATE_DESC -> files.sortedByDescending { it.lastModified }
        }
        return sortedDirs + sortedFiles
    }

    private fun refreshDisplayList() {
        _uiState.update { state ->
            val filtered = if (state.showHiddenFiles) state.fileList else state.fileList.filter { !it.name.startsWith(".") }
            val sorted = sortFileList(filtered, state.sortMode)
            state.copy(displayList = sorted)
        }
    }

    fun onEvent(event: FtpUiEvent) {
        when (event) {
            is FtpUiEvent.NavigateBack -> handleBack()
            is FtpUiEvent.ToggleGridView -> {
                _uiState.update { it.copy(isGridView = !it.isGridView) }
            }
            is FtpUiEvent.SetSortMode -> {
                _uiState.update { it.copy(sortMode = event.mode) }
                refreshDisplayList()
            }
            is FtpUiEvent.ToggleHiddenFiles -> {
                _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
                refreshDisplayList()
            }
            is FtpUiEvent.ShowCreateFolderDialog -> {
                _uiState.update { it.copy(showCreateFolderDialog = event.show) }
            }
            is FtpUiEvent.ConfirmCreateFolder -> {
                _uiState.update { it.copy(showCreateFolderDialog = false) }
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        operator.createFolder(uiState.value.currentPath, event.name)
                    }
                    result.onSuccess {
                        viewModelScope.launch(Dispatchers.Main) {
                            GlobalToastManager.showToast(getApplication<Application>().getString(R.string.smb_msg_folder_created, event.name))
                        }
                        refresh()
                    }.onFailure { e ->
                        viewModelScope.launch(Dispatchers.Main) {
                            GlobalToastManager.showToast(AppException.fromThrowable(e).uiMessage.asString(getApplication()))
                        }
                    }
                }
            }
            is FtpUiEvent.ToggleMultiSelect -> {
                _uiState.update { it.copy(isMultiSelectMode = event.enabled) }
                if (!event.enabled) _uiState.update { it.copy(selectedPaths = emptySet()) }
            }
            is FtpUiEvent.ToggleSelection -> {
                val newSet = _uiState.value.selectedPaths.toMutableSet()
                if (newSet.contains(event.path)) newSet.remove(event.path) else newSet.add(event.path)
                _uiState.update { it.copy(selectedPaths = newSet) }
            }
            is FtpUiEvent.SelectAll -> {
                // 2026-10-04 审计 P2 修复：全选作用于过滤后的 displayList——原先用原始 fileList，
                // 隐藏文件（如 .ftpquota）被静默纳入批量删除/下载，与"显示隐藏文件"开关语义冲突
                _uiState.update { it.copy(selectedPaths = it.displayList.map { f -> f.path }.toSet()) }
            }
            is FtpUiEvent.ShowActionMenu -> {
                _uiState.update { it.copy(showActionMenu = event.show, selectedEntry = event.entry) }
            }
            is FtpUiEvent.ShowRenameDialog -> {
                _uiState.update { it.copy(renameTarget = event.entry, showActionMenu = false) }
            }
            is FtpUiEvent.ConfirmRename -> {
                _uiState.value.renameTarget?.let { renameFile(it, event.newName) }
            }
            is FtpUiEvent.RequestDelete -> {
                _uiState.update { it.copy(pendingDelete = event.entries, showActionMenu = false) }
            }
            is FtpUiEvent.ConfirmDelete -> {
                deleteSelected(event.entries)
            }
            is FtpUiEvent.RequestDownload -> {
                val downloadDir = StoragePermissionUtils.getStorageDir(getApplication()).absolutePath
                // 2026-10-04 审计 P2 修复：排除目录——对目录 retrieveFileStream 必失败，
                // 原先批量勾选含目录时报"部分下载失败"误导用户
                val targets = event.entries.filter { !it.isDirectory }
                // 2026-10-04 审计 P1 修复：FTPClient 非线程安全——批量下载后台进行时原先
                // 界面完全可交互，并发浏览/删除与数据传输共享控制连接会协议错乱。
                // 复用单文件下载的全屏遮罩（downloadingFile）阻断交互，单点互斥。
                _uiState.update {
                    it.copy(
                        showActionMenu = false,
                        isMultiSelectMode = false,
                        selectedPaths = emptySet(),
                        downloadingFile = targets.firstOrNull(),
                        downloadPercent = 0
                    )
                }
                if (targets.isNotEmpty()) {
                    downloadToLocal(targets, downloadDir)
                } else {
                    // 2026-10-10 审计修复：原先误用 smb_error_download_failed（"下载失败"）——
                    // 实际是只勾了目录、没有可下载文件
                    showToast(getApplication<Application>().getString(R.string.error_no_downloadable_files))
                    _uiState.update { it.copy(downloadingFile = null) }
                }
            }
            is FtpUiEvent.HandleEntryAction -> {
                val entry = event.entry
                when (event.action) {
                    FtpAction.OPEN -> openFile(entry)
                    FtpAction.INSTALL -> installApk(entry)
                    FtpAction.RENAME -> onEvent(FtpUiEvent.ShowRenameDialog(entry))
                    FtpAction.DELETE -> onEvent(FtpUiEvent.RequestDelete(listOf(entry)))
                    FtpAction.DOWNLOAD -> onEvent(FtpUiEvent.RequestDownload(listOf(entry)))
                    FtpAction.DETAILS -> _uiState.update { it.copy(detailsTarget = entry) }
                }
            }
            is FtpUiEvent.OpenFile -> {
                viewModelScope.launch { _uiEvent.emit(event) }
            }
        }
    }

    fun handleBack() {
        val state = _uiState.value
        when {
            state.isMultiSelectMode -> _uiState.update { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
            state.showActionMenu -> _uiState.update { it.copy(showActionMenu = false, selectedEntry = null) }
            state.renameTarget != null -> _uiState.update { it.copy(renameTarget = null) }
            state.currentPath != "/" && state.currentPath.isNotEmpty() && clientManager.state.value !is FtpConnectionState.Connecting -> {
                // 2026-10-04 审计 P1 修复："/a".substringBeforeLast("/", "/") 返回 ""（分隔符存在时
                // 默认值不生效）——原先返回上级后 currentPath 变空串，根目录错误渲染 ".." 且点它直接退出浏览页
                val parentPath = state.currentPath.substringBeforeLast("/", "/").ifEmpty { "/" }
                loadDirectory(parentPath)
            }
            else -> viewModelScope.launch { _uiEvent.emit(FtpUiEvent.NavigateBack) }
        }
    }

    fun showToast(msg: String) {
        viewModelScope.launch { GlobalToastManager.showToast(msg) }
    }

    override fun onCleared() {
        super.onCleared()
        // 2026-10-04 审计 P2 修复：disconnect 内含 logout()（向控制 socket 写 QUIT 的网络 IO），
        // onCleared 在主线程回调会抛 NetworkOnMainThreadException 被吞——服务器收不到 QUIT，
        // 且真实失败被日志噪音掩盖。切 IO 收尾。
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            clientManager.disconnect()
        }
    }

    fun updateState(transform: (FtpBrowseUiState) -> FtpBrowseUiState) {
        _uiState.update(transform)
    }

    fun deleteSelected(entries: List<FtpFileEntry>) {
        viewModelScope.launch {
            var count = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (entry in entries) {
                    val ok = if (entry.isDirectory) operator.deleteDirectory(entry) else operator.deleteFile(entry)
                    if (ok) count++ else errors.add(entry.name)
                }
            }
            val msg = if (errors.isEmpty()) {
                getApplication<Application>().getString(R.string.smb_msg_deleted_success, count)
            } else {
                getApplication<Application>().getString(R.string.smb_msg_deleted_partial, count, errors.size)
            }
            _uiState.update { it.copy(pendingDelete = emptyList(), isMultiSelectMode = false, selectedPaths = emptySet()) }
            showToast(msg)
            refresh()
        }
    }

    fun renameFile(target: FtpFileEntry, newName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { operator.renameFile(target, newName) }
            result.onSuccess {
                // 2026-10-10 审计修复：原先误用 dir_picker_created_folder（"已创建文件夹"）
                showToast(getApplication<Application>().getString(R.string.file_manager_msg_renamed, newName))
                refresh()
            }.onFailure { e ->
                val msg = (e as? AppException)?.uiMessage?.asString(getApplication()) ?: e.message
                showToast(msg ?: getApplication<Application>().getString(R.string.smb_error_rename_failed_default))
            }
            _uiState.update { it.copy(renameTarget = null) }
        }
    }

    fun downloadToLocal(entries: List<FtpFileEntry>, destDir: String) {
        viewModelScope.launch {
            var count = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (entry in entries) {
                    val file = operator.downloadFile(entry) { _, _, percent ->
                        _uiState.update { it.copy(downloadPercent = percent) }
                    }
                    if (file != null) count++ else errors.add(entry.name)
                }
            }
            val msg = if (errors.isEmpty()) {
                getApplication<Application>().getString(R.string.smb_msg_download_success, count)
            } else {
                getApplication<Application>().getString(R.string.smb_msg_download_partial, count, errors.size)
            }
            showToast(msg)
            _uiState.update { it.copy(isMultiSelectMode = false, selectedPaths = emptySet(), downloadingFile = null) }
        }
    }

    fun openFile(entry: FtpFileEntry) {
        // 2026-10-04 审计 P2 修复：在途互斥——FTPClient 非线程安全，双击会并发两次下载
        if (_uiState.value.downloadingFile != null) return
        _uiState.update { it.copy(downloadingFile = entry, downloadPercent = 0) }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                operator.downloadFile(entry) { _, _, percent -> _uiState.update { it.copy(downloadPercent = percent) } }
            }
            _uiState.update { it.copy(downloadingFile = null) }
            if (file != null) {
                _uiEvent.emit(FtpUiEvent.OpenFile(file))
            } else {
                showToast(getApplication<Application>().getString(R.string.smb_error_download_failed))
            }
        }
    }

    fun installApk(entry: FtpFileEntry) {
        // 2026-10-04 审计 P2 修复：在途互斥（同 openFile）
        if (_uiState.value.downloadingFile != null) return
        _uiState.update { it.copy(downloadingFile = entry, downloadPercent = 0) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                operator.installApk(entry) { _, _, percent -> _uiState.update { it.copy(downloadPercent = percent) } }
            }
            _uiState.update { it.copy(downloadingFile = null) }
            showToast(
                if (success) getApplication<Application>().getString(R.string.smb_msg_apk_downloaded)
                else getApplication<Application>().getString(R.string.smb_error_download_failed)
            )
        }
    }

    class Factory(
        private val application: Application,
        private val host: String,
        private val port: Int,
        private val user: String,
        private val pass: String,
        private val anonymous: Boolean
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FtpBrowseViewModel(application, host, port, user, pass, anonymous) as T
        }
    }
}
