package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.smb.SmbBrowser
import com.tvflyshare.app.smb.SmbClientManager
import com.tvflyshare.app.smb.SmbConnectionState
import com.tvflyshare.app.smb.SmbFileEntry
import com.tvflyshare.app.smb.SmbFileOperator
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.filebrowser.model.FileOperationType
import com.tvflyshare.app.settings.SettingsRepository
import com.tvflyshare.app.util.AppException
import kotlinx.coroutines.Dispatchers
import com.tvflyshare.app.filebrowser.model.SortMode
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
import com.tvflyshare.app.ui.screens.network.SmbAction
import com.tvflyshare.app.util.StoragePermissionUtils

// ============================================================
// UI 状态与事件模型
// ============================================================

data class SmbBrowseUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val currentPath: String = "\\",
    val fileList: List<SmbFileEntry> = emptyList(),
    val displayList: List<SmbFileEntry> = emptyList(),
    val downloadingFile: SmbFileEntry? = null,
    val downloadPercent: Int = 0,
    val selectedEntry: SmbFileEntry? = null,
    val showActionMenu: Boolean = false,
    val renameTarget: SmbFileEntry? = null,
    val pendingOperation: FileOperationType? = null,
    val pendingEntries: List<SmbFileEntry> = emptyList(),
    val pendingDownload: List<SmbFileEntry> = emptyList(),
    val pendingDelete: List<SmbFileEntry> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val isGridView: Boolean = false,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val showCreateFolderDialog: Boolean = false,
    val detailsTarget: SmbFileEntry? = null
)

sealed class SmbUiEvent {
    // 多选
    data class ToggleMultiSelect(val enabled: Boolean) : SmbUiEvent()
    data class ToggleSelection(val path: String) : SmbUiEvent()
    object SelectAll : SmbUiEvent()

    // 文件操作 (触发 UI 或 直接处理)
    data class OpenFile(val file: File) : SmbUiEvent()
    data class HandleEntryAction(val entry: SmbFileEntry, val action: SmbAction) : SmbUiEvent()
    
    // 弹窗与状态控制
    data class ShowActionMenu(val show: Boolean, val entry: SmbFileEntry? = null) : SmbUiEvent()
    data class ShowRenameDialog(val entry: SmbFileEntry?) : SmbUiEvent()
    data class ConfirmRename(val newName: String) : SmbUiEvent()
    
    // 视图与菜单
    object ToggleGridView : SmbUiEvent()
    data class SetSortMode(val mode: SortMode) : SmbUiEvent()
    object ToggleHiddenFiles : SmbUiEvent()
    data class ShowCreateFolderDialog(val show: Boolean) : SmbUiEvent()
    data class ConfirmCreateFolder(val name: String) : SmbUiEvent()
    
    // 批处理意图
    data class RequestDelete(val entries: List<SmbFileEntry>) : SmbUiEvent()
    data class ConfirmDelete(val entries: List<SmbFileEntry>) : SmbUiEvent()
    
    data class RequestCopyMove(val isCopy: Boolean, val entries: List<SmbFileEntry>) : SmbUiEvent()
    data class ConfirmCopyMove(val isCopy: Boolean, val entries: List<SmbFileEntry>, val destDirSmbPath: String) : SmbUiEvent()
    
    data class RequestDownload(val entries: List<SmbFileEntry>) : SmbUiEvent()
    data class ConfirmDownload(val entries: List<SmbFileEntry>, val destDirLocalPath: String) : SmbUiEvent()

    object NavigateBack : SmbUiEvent()
}

// ============================================================
// SmbBrowseViewModel
// 统一管理网络连接、状态和耗时 IO 操作
// ============================================================

class SmbBrowseViewModel(
    application: Application,
    private val host: String,
    private val shareName: String,
    private val user: String,
    private val pass: String
) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    val clientManager = SmbClientManager()
    val browser = SmbBrowser(clientManager)
    private val operator = SmbFileOperator(clientManager, application)

    private val _uiState = MutableStateFlow(SmbBrowseUiState())
    val uiState: StateFlow<SmbBrowseUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<SmbUiEvent>()
    val uiEvent: SharedFlow<SmbUiEvent> = _uiEvent.asSharedFlow()

    private var loadJob: Job? = null
    private var isInitialized = false

    val connState: StateFlow<SmbConnectionState> = clientManager.state

    fun initConnection() {
        if (isInitialized && clientManager.state.value !is SmbConnectionState.Error) return
        isInitialized = true

        if (shareName.isBlank()) {
            _uiState.update { it.copy(isLoading = false, loadError = getApplication<Application>().getString(R.string.smb_error_share_name_empty)) }
            return
        }

        viewModelScope.launch {
            val domain = settingsRepository.getSmbWorkgroup()
            val success = withContext(Dispatchers.IO) {
                clientManager.connect(host, shareName, user, pass, domain)
            }
            if (success) {
                loadDirectory("\\")
            } else {
                val err = (clientManager.state.value as? SmbConnectionState.Error)?.message?.asString(getApplication()) ?: getApplication<Application>().getString(R.string.smb_connect_failed)
                _uiState.update { it.copy(isLoading = false, loadError = err) }
            }
        }
    }

    /** 2026-10-10 审计修复：连接失败重试——先串行断开旧连接再重建，消除 disconnect/connect 竞态
     *  （原先 UI 用裸 CoroutineScope 并发 disconnect + initConnection，新连接可能被旧断开拆掉） */
    fun retryConnection() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { clientManager.disconnect() }
            isInitialized = false
            initConnection()
        }
    }

    fun refresh() {
        loadDirectory(_uiState.value.currentPath)
    }

    fun loadDirectory(path: String) {
        // 取消上一次正在进行的加载，防止连续点击造成的竞态条件
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null, currentPath = path) }
            try {
                val files = withContext(Dispatchers.IO) {
                    browser.listDirectory(path)
                }
                _uiState.update { state ->
                    val newFiles = files
                    val filtered = if (state.showHiddenFiles) newFiles else newFiles.filter { !it.name.startsWith(".") }
                    val sorted = sortFileList(filtered, state.sortMode)
                    state.copy(isLoading = false, fileList = newFiles, displayList = sorted, loadError = null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, fileList = emptyList(), loadError = getApplication<Application>().getString(R.string.smb_error_read_dir, e.message ?: "")) }
            }
        }
    }

    private fun sortFileList(list: List<SmbFileEntry>, sortMode: SortMode): List<SmbFileEntry> {
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

    fun onEvent(event: SmbUiEvent) {
        when (event) {
            is SmbUiEvent.NavigateBack -> handleBack()
            is SmbUiEvent.ToggleGridView -> {
                _uiState.update { it.copy(isGridView = !it.isGridView) }
            }
            is SmbUiEvent.SetSortMode -> {
                _uiState.update { it.copy(sortMode = event.mode) }
                refreshDisplayList()
            }
            is SmbUiEvent.ToggleHiddenFiles -> {
                _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
                refreshDisplayList()
            }
            is SmbUiEvent.ShowCreateFolderDialog -> {
                _uiState.update { it.copy(showCreateFolderDialog = event.show) }
            }
            is SmbUiEvent.ConfirmCreateFolder -> {
                _uiState.update { it.copy(showCreateFolderDialog = false) }
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        operator.createFolder(uiState.value.currentPath, event.name)
                    }
                    result.onSuccess {
                        viewModelScope.launch(Dispatchers.Main) { GlobalToastManager.showToast(getApplication<Application>().getString(R.string.smb_msg_folder_created, event.name)) }
                        refresh()
                    }.onFailure { e ->
                        viewModelScope.launch(Dispatchers.Main) { GlobalToastManager.showToast(AppException.fromThrowable(e).uiMessage.asString(getApplication())) }
                    }
                }
            }

            is SmbUiEvent.ToggleMultiSelect -> {
                _uiState.update { it.copy(isMultiSelectMode = event.enabled) }
                if (!event.enabled) {
                    _uiState.update { it.copy(selectedPaths = emptySet()) }
                }
            }
            is SmbUiEvent.ToggleSelection -> {
                val newSet = _uiState.value.selectedPaths.toMutableSet()
                if (newSet.contains(event.path)) newSet.remove(event.path) else newSet.add(event.path)
                _uiState.update { it.copy(selectedPaths = newSet) }
            }
            is SmbUiEvent.SelectAll -> {
                // 2026-10-10 审计修复：全选作用于过滤后的 displayList（对齐 FTP 侧 10-04 修复）——
                // 原先用原始 fileList，隐藏文件被静默纳入批量删除/下载，与"显示隐藏文件"开关语义冲突
                _uiState.update { it.copy(selectedPaths = it.displayList.map { f -> f.path }.toSet()) }
            }
            is SmbUiEvent.ShowActionMenu -> {
                _uiState.update { it.copy(showActionMenu = event.show, selectedEntry = event.entry) }
            }
            is SmbUiEvent.ShowRenameDialog -> {
                _uiState.update { it.copy(renameTarget = event.entry, showActionMenu = false) }
            }
            is SmbUiEvent.ConfirmRename -> {
                _uiState.value.renameTarget?.let { renameFile(it, event.newName) }
            }
            is SmbUiEvent.RequestDelete -> {
                _uiState.update { it.copy(pendingDelete = event.entries, showActionMenu = false) }
            }
            is SmbUiEvent.ConfirmDelete -> {
                deleteSelected(event.entries)
            }
            is SmbUiEvent.RequestCopyMove -> {
                _uiState.update { 
                    it.copy(
                        pendingOperation = if (event.isCopy) FileOperationType.COPY else FileOperationType.MOVE,
                        pendingEntries = event.entries,
                        showActionMenu = false
                    ) 
                }
            }
            is SmbUiEvent.ConfirmCopyMove -> {
                copyOrMoveWithinSmb(event.entries, event.destDirSmbPath, event.isCopy)
            }
            is SmbUiEvent.RequestDownload -> {
                val downloadDir = StoragePermissionUtils.getStorageDir(getApplication()).absolutePath
                _uiState.update { it.copy(showActionMenu = false, isMultiSelectMode = false, selectedPaths = emptySet()) }
                downloadToLocal(event.entries, downloadDir) { _, _ -> }
            }
            is SmbUiEvent.ConfirmDownload -> {
                downloadToLocal(event.entries, event.destDirLocalPath) { _, _ -> }
            }
            is SmbUiEvent.HandleEntryAction -> {
                val entry = event.entry
                when (event.action) {
                    SmbAction.OPEN -> openFile(entry)
                    SmbAction.INSTALL -> installApk(entry)
                    SmbAction.RENAME -> onEvent(SmbUiEvent.ShowRenameDialog(entry))
                    SmbAction.DELETE -> onEvent(SmbUiEvent.RequestDelete(listOf(entry)))
                    SmbAction.COPY -> onEvent(SmbUiEvent.RequestCopyMove(true, listOf(entry)))
                    SmbAction.MOVE -> onEvent(SmbUiEvent.RequestCopyMove(false, listOf(entry)))
                    SmbAction.DOWNLOAD -> onEvent(SmbUiEvent.RequestDownload(listOf(entry)))
                    SmbAction.DETAILS -> _uiState.update { it.copy(detailsTarget = entry) }
                    SmbAction.BOOKMARK -> {}
                }
            }
            is SmbUiEvent.OpenFile -> {
                viewModelScope.launch { _uiEvent.emit(event) }
            }
        }
    }

    fun handleBack() {
        val state = _uiState.value
        when {
            state.isMultiSelectMode -> {
                _uiState.update { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
            }
            state.showActionMenu -> {
                _uiState.update { it.copy(showActionMenu = false, selectedEntry = null) }
            }
            state.renameTarget != null -> {
                _uiState.update { it.copy(renameTarget = null) }
            }
            state.currentPath != "\\" && state.currentPath.isNotEmpty() && clientManager.state.value !is SmbConnectionState.Connecting -> {
                val parentPath = state.currentPath.substringBeforeLast("\\", "\\")
                val newPath = if (parentPath.isEmpty()) "\\" else parentPath
                loadDirectory(newPath)
            }
            else -> {
                viewModelScope.launch { _uiEvent.emit(SmbUiEvent.NavigateBack) }
            }
        }
    }

    fun showToast(msg: String) {
        viewModelScope.launch {
            GlobalToastManager.showToast(msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 2026-10-04 审计 P2 修复：disconnect 含 SMB2 TREE_DISCONNECT/LOGOFF/TCP close 真实网络往返，
        // onCleared 在主线程回调——网络半死时 close 内部写 socket 可阻塞至超时级造成掉帧/ANR，切 IO 收尾
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            clientManager.disconnect()
        }
    }

    // ============================================================
    // 文件操作处理 (全在 Dispatchers.IO，防止卡主线程)
    // ============================================================

    fun updateState(transform: (SmbBrowseUiState) -> SmbBrowseUiState) {
        _uiState.update(transform)
    }

    fun deleteSelected(entries: List<SmbFileEntry>) {
        viewModelScope.launch {
            var count = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (entry in entries) {
                    val ok = if (entry.isDirectory) {
                        operator.deleteSmbDirectory(entry)
                    } else {
                        operator.deleteSmbFile(entry)
                    }
                    if (ok) count++ else errors.add(entry.name)
                }
            }
            val msg = if (errors.isEmpty()) getApplication<Application>().getString(R.string.smb_msg_deleted_success, count) else getApplication<Application>().getString(R.string.smb_msg_deleted_partial, count, errors.size)
            _uiState.update {
                it.copy(
                    pendingDelete = emptyList(),
                    isMultiSelectMode = false,
                    selectedPaths = emptySet()
                )
            }
            showToast(msg)
            refresh()
        }
    }

    fun copyOrMoveWithinSmb(entries: List<SmbFileEntry>, destDirSmbPath: String, isCopy: Boolean) {
        viewModelScope.launch {
            val (successCount, errors) = withContext(Dispatchers.IO) {
                if (isCopy) {
                    operator.copyMultipleWithinSmb(entries, destDirSmbPath)
                } else {
                    operator.moveMultipleWithinSmb(entries, destDirSmbPath)
                }
            }
            val msg = if (errors.isEmpty()) {
                if (isCopy) getApplication<Application>().getString(R.string.smb_msg_copy_success, successCount) else getApplication<Application>().getString(R.string.smb_msg_move_success, successCount)
            } else {
                getApplication<Application>().getString(R.string.smb_msg_transfer_partial, successCount, errors.joinToString("; "))
            }
            _uiState.update {
                it.copy(
                    pendingOperation = null,
                    pendingEntries = emptyList(),
                    isMultiSelectMode = false,
                    selectedPaths = emptySet()
                )
            }
            showToast(msg)
            refresh()
        }
    }

    fun renameFile(target: SmbFileEntry, newName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                operator.renameFile(target, newName)
            }
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

    fun downloadToLocal(entries: List<SmbFileEntry>, destDir: String, onComplete: (Int, List<String>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                operator.copyMultipleToLocal(entries, destDir)
            }
            onComplete(result.first, result.second)
            val (count, errors) = result
            val msg = if (errors.isEmpty()) getApplication<Application>().getString(R.string.smb_msg_download_success, count) else getApplication<Application>().getString(R.string.smb_msg_download_partial, count, errors.size)
            showToast(msg)
            _uiState.update {
                it.copy(
                    pendingDownload = emptyList(),
                    isMultiSelectMode = false,
                    selectedPaths = emptySet()
                )
            }
            refresh()
        }
    }

    fun openFile(entry: SmbFileEntry) {
        // 2026-10-04 审计 P2 修复：在途守卫——遥控器双击会并发两次 downloadFile 写同一路径，
        // 两个 FileOutputStream 交错写产出损坏文件
        if (_uiState.value.downloadingFile != null) return
        _uiState.update { it.copy(downloadingFile = entry, downloadPercent = 0) }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                operator.downloadFile(entry) { _, _, percent ->
                    _uiState.update { it.copy(downloadPercent = percent) }
                }
            }
            _uiState.update { it.copy(downloadingFile = null) }
            if (file != null) {
                _uiEvent.emit(SmbUiEvent.OpenFile(file))
            } else {
                showToast(getApplication<Application>().getString(R.string.smb_error_download_failed))
            }
        }
    }

    fun installApk(entry: SmbFileEntry) {
        // 2026-10-04 审计 P2 修复：在途守卫（同 openFile）
        if (_uiState.value.downloadingFile != null) return
        _uiState.update { it.copy(downloadingFile = entry, downloadPercent = 0) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                operator.installApk(entry) { _, _, percent ->
                    _uiState.update { it.copy(downloadPercent = percent) }
                }
            }
            _uiState.update { it.copy(downloadingFile = null) }
            showToast(if (success) getApplication<Application>().getString(R.string.smb_msg_apk_downloaded) else getApplication<Application>().getString(R.string.smb_error_download_failed))
        }
    }

    // ============================================================
    // ViewModel Factory
    // ============================================================

    class Factory(
        private val application: Application,
        private val host: String,
        private val shareName: String,
        private val user: String,
        private val pass: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SmbBrowseViewModel(application, host, shareName, user, pass) as T
        }
    }
}
