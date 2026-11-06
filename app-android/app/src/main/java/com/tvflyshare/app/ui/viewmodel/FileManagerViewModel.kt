package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.filebrowser.model.FileOperationType
import com.tvflyshare.app.filebrowser.model.SortMode
import com.tvflyshare.app.util.FileEntry
import com.tvflyshare.app.util.FileOperations
import com.tvflyshare.app.util.FileScanner
import com.tvflyshare.app.util.MediaChangeNotifier
import com.tvflyshare.app.TVTransferApp
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.tvflyshare.app.util.StoragePermissionUtils
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tvflyshare.app.R
import com.tvflyshare.app.Constants

data class FileManagerUiState(
    val currentPath: String = Constants.INTERNAL_STORAGE_ROOT,
    // 当前浏览的存储根（内置存储/U盘），供界面判定"返回上一级"是否可用（2026-10-04 审计 P2 修复）
    val rootPath: String = Constants.INTERNAL_STORAGE_ROOT,
    val titleText: String = "",
    val fileList: List<FileEntry> = emptyList(),
    val displayList: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    // 下拉刷新进行中：保留现有列表原地更新（不触发全屏转圈），由 PullToRefreshBox 顶部指示器驱动
    val isRefreshing: Boolean = false,
    val loadError: String? = null,
    val isMultiSelectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val isGridView: Boolean = false,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val pendingOperation: FileOperationType? = null,
    
    // UI states moved from Screen
    val selectedEntry: FileEntry? = null,
    val showActionMenu: Boolean = false,
    val renameTarget: FileEntry? = null,
    val showCreateFolderDialog: Boolean = false,
    val showOptionsMenu: Boolean = false,
    val showSortMenu: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false,
    val fileToDelete: FileEntry? = null,
    val showMultiDeleteConfirmDialog: Boolean = false
)

sealed class FileManagerUiEvent {
    object NavigateUp : FileManagerUiEvent()
    data class NavigateTo(val path: String) : FileManagerUiEvent()
    
    object ToggleMultiSelectMode : FileManagerUiEvent()
    data class ToggleSelection(val path: String) : FileManagerUiEvent()
    object SelectAll : FileManagerUiEvent()
    object ClearSelection : FileManagerUiEvent()
    
    object ToggleGridView : FileManagerUiEvent()
    object ToggleHiddenFiles : FileManagerUiEvent()
    data class SetSortMode(val mode: SortMode) : FileManagerUiEvent()
    
    // Dialogs / Menus
    data class ShowOptionsMenu(val show: Boolean) : FileManagerUiEvent()
    data class ShowSortMenu(val show: Boolean) : FileManagerUiEvent()
    data class ShowCreateFolderDialog(val show: Boolean) : FileManagerUiEvent()
    data class ShowActionMenu(val show: Boolean, val entry: FileEntry? = null) : FileManagerUiEvent()
    data class ShowRenameDialog(val entry: FileEntry?) : FileManagerUiEvent()
    data class ShowDeleteConfirmDialog(val show: Boolean, val entry: FileEntry? = null) : FileManagerUiEvent()
    data class ShowMultiDeleteConfirmDialog(val show: Boolean) : FileManagerUiEvent()
    
    // Operations
    data class ConfirmRename(val entry: FileEntry, val newName: String) : FileManagerUiEvent()
    data class ConfirmCreateFolder(val name: String) : FileManagerUiEvent()
    data class ConfirmDelete(val entry: FileEntry) : FileManagerUiEvent()
    object ConfirmMultiDelete : FileManagerUiEvent()
    
    data class RequestOperation(val op: FileOperationType?) : FileManagerUiEvent()
    data class RequestSingleOperation(val entry: FileEntry, val op: FileOperationType) : FileManagerUiEvent()
    data class OperationComplete(val message: String, val targetPath: String) : FileManagerUiEvent()
    object RequestManageExternalStoragePermission : FileManagerUiEvent()
}

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val _singleEvent = MutableSharedFlow<FileManagerUiEvent>()
    val singleEvent: SharedFlow<FileManagerUiEvent> = _singleEvent.asSharedFlow()

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    private var currentFileType: String = "all"
    private var rootPath: String = Constants.INTERNAL_STORAGE_ROOT
    // loadFiles 竞态守卫（2026-10-04 审计 P2 修复）：快速导航时只让最新一次扫描结果落地，
    // 旧目录的后到响应直接丢弃，避免"路径已是 B、列表还是 A"的错位
    private var loadSeq = 0

    // 自动刷新（2026-10-23 用户拍板：只做自动刷新，不做下拉手势与手动刷新图标）：
    // ① 目录监听：正浏览目录的内容变化（传输完成落盘、外部增删）→ 防抖后静默重扫；
    // ② 回到页面的 ON_RESUME 静默重扫兜底（Screen 内实现）。
    private var dirObserver: FileObserver? = null
    private var dirObserverPath: String? = null
    private var dirWatchDebounceJob: Job? = null

    // ③ 文件落地即时刷新（事件驱动，替代轮询）：媒体分类/安装包等视图没有目录监听，
    //    所有写入最终都会触发 triggerMediaScan，其扫描完成回调发出 MediaChangeNotifier 事件，
    //    这里收到即防抖静默刷新——文件落盘进媒体库后立刻出现在列表里，零轮询开销。
    //    "全部文件"视图已有目录监听（FileObserver），不重复响应，避免无谓重扫。
    private var mediaChangeRefreshJob: Job? = null

    // ④ 系统媒体库监听（ContentObserver）：捕捉“非本应用写入”的文件变化
    //    （电脑 USB/MTP 拷贝、其它 App 保存等）——这类文件的落盘事件我们收不到，
    //    但系统更新媒体库时会通知。仍是事件驱动、不轮询；页面存活期间挂载，onCleared 摘除。
    private var mediaStoreObserver: ContentObserver? = null

    init {
        viewModelScope.launch {
            MediaChangeNotifier.events.collect {
                if (currentFileType != "all") scheduleMediaChangeRefresh()
            }
        }
        startMediaStoreObserver()
    }

    private fun startMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                scheduleMediaChangeRefresh()
            }
        }
        try {
            // 监听整个外部存储的媒体/文件库；notifyForDescendants=true 让具体文件变化也回调
            getApplication<Application>().contentResolver
                .registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
            mediaStoreObserver = observer
        } catch (e: Exception) {
            Log.w("FileManagerViewModel", "registerContentObserver failed", e)
        }
    }

    private fun stopMediaStoreObserver() {
        mediaStoreObserver?.let { observer ->
            runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(observer) }
        }
        mediaStoreObserver = null
    }

    private fun scheduleMediaChangeRefresh() {
        mediaChangeRefreshJob?.cancel()
        mediaChangeRefreshJob = viewModelScope.launch {
            delay(DIR_WATCH_DEBOUNCE_MS)
            loadFiles(showLoading = false)
        }
    }

    fun initData(fileType: String, initialRootPath: String?) {
        currentFileType = fileType
        rootPath = initialRootPath ?: Constants.INTERNAL_STORAGE_ROOT
        _uiState.update { it.copy(currentPath = rootPath, rootPath = rootPath) }
        loadFiles()
    }

    fun onEvent(event: FileManagerUiEvent) {
        val state = _uiState.value
        when (event) {
            is FileManagerUiEvent.NavigateUp -> {
                if (currentFileType == "all" && state.currentPath != rootPath) {
                    val parentFile = File(state.currentPath).parentFile
                    if (parentFile != null) {
                        onEvent(FileManagerUiEvent.NavigateTo(parentFile.absolutePath))
                    }
                }
            }
            is FileManagerUiEvent.NavigateTo -> {
                _uiState.update { it.copy(currentPath = event.path) }
                loadFiles()
            }
            is FileManagerUiEvent.ToggleMultiSelectMode -> {
                _uiState.update { 
                    val newMode = !it.isMultiSelectMode
                    it.copy(
                        isMultiSelectMode = newMode,
                        selectedPaths = if (!newMode) emptySet() else it.selectedPaths
                    )
                }
            }
            is FileManagerUiEvent.ToggleSelection -> {
                _uiState.update { 
                    val newPaths = if (it.selectedPaths.contains(event.path)) {
                        it.selectedPaths - event.path
                    } else {
                        it.selectedPaths + event.path
                    }
                    it.copy(selectedPaths = newPaths)
                }
            }
            is FileManagerUiEvent.SelectAll -> {
                _uiState.update { it.copy(selectedPaths = it.displayList.map { entry -> entry.path }.toSet()) }
            }
            is FileManagerUiEvent.ClearSelection -> {
                _uiState.update { it.copy(selectedPaths = emptySet()) }
            }
            is FileManagerUiEvent.ToggleGridView -> {
                _uiState.update { it.copy(isGridView = !it.isGridView) }
            }
            is FileManagerUiEvent.ToggleHiddenFiles -> {
                _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
                refreshDisplayList()
            }
            is FileManagerUiEvent.SetSortMode -> {
                _uiState.update { it.copy(sortMode = event.mode) }
                refreshDisplayList()
            }
            is FileManagerUiEvent.ShowOptionsMenu -> _uiState.update { it.copy(showOptionsMenu = event.show) }
            is FileManagerUiEvent.ShowSortMenu -> _uiState.update { it.copy(showSortMenu = event.show) }
            is FileManagerUiEvent.ShowCreateFolderDialog -> _uiState.update { it.copy(showCreateFolderDialog = event.show) }
            is FileManagerUiEvent.ShowActionMenu -> _uiState.update { it.copy(showActionMenu = event.show, selectedEntry = event.entry) }
            is FileManagerUiEvent.ShowRenameDialog -> _uiState.update { it.copy(renameTarget = event.entry, showActionMenu = false) }
            is FileManagerUiEvent.ShowDeleteConfirmDialog -> _uiState.update { it.copy(showDeleteConfirmDialog = event.show, fileToDelete = event.entry, showActionMenu = false) }
            is FileManagerUiEvent.ShowMultiDeleteConfirmDialog -> _uiState.update { it.copy(showMultiDeleteConfirmDialog = event.show) }
            
            is FileManagerUiEvent.ConfirmRename -> {
                // 2026-10-04 审计 P1 修复：renameFile 含磁盘 IO + MediaStore query，
                // 原先在主线程同步执行，批量/慢盘时卡顿。整体切 IO，结果回 Main。
                viewModelScope.launch(Dispatchers.IO) {
                    val result = FileOperations.renameFile(getApplication(), event.entry, event.newName)
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            GlobalToastManager.showToast(getApplication<Application>().getString(R.string.file_manager_msg_renamed, event.newName))
                            loadFiles()
                        }.onFailure { e: Throwable ->
                            GlobalToastManager.showToast(AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()))
                        }
                    }
                }
            }
            is FileManagerUiEvent.ConfirmCreateFolder -> {
                _uiState.update { it.copy(showCreateFolderDialog = false) }
                viewModelScope.launch(Dispatchers.IO) {
                    val result = FileOperations.createFolder(getApplication(), _uiState.value.currentPath, event.name)
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            GlobalToastManager.showToast(getApplication<Application>().getString(R.string.file_manager_msg_folder_created, event.name))
                            loadFiles()
                        }.onFailure { e: Throwable ->
                            GlobalToastManager.showToast(AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()))
                        }
                    }
                }
            }
            is FileManagerUiEvent.ConfirmDelete -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val result = FileOperations.deleteFileWithFeedback(getApplication(), event.entry)
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            GlobalToastManager.showToast(getApplication<Application>().getString(R.string.file_manager_msg_deleted, event.entry.name))
                            loadFiles()
                        }.onFailure { e: Throwable ->
                            GlobalToastManager.showToast(AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()))
                        }
                    }
                }
            }
            is FileManagerUiEvent.ConfirmMultiDelete -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val listToDelete = _uiState.value.displayList.filter { _uiState.value.selectedPaths.contains(it.path) }
                    val deleted = FileOperations.deleteFiles(getApplication(), listToDelete)
                    withContext(Dispatchers.Main) {
                        GlobalToastManager.showToast(getApplication<Application>().getString(R.string.file_manager_msg_deleted_multi, deleted))
                        _uiState.update { it.copy(showMultiDeleteConfirmDialog = false, isMultiSelectMode = false, selectedPaths = emptySet()) }
                        loadFiles()
                    }
                }
            }
            is FileManagerUiEvent.RequestOperation -> {
                if (event.op == FileOperationType.COPY || event.op == FileOperationType.MOVE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !StoragePermissionUtils.hasAdvancedStoragePermission()) {
                        // On Android 11+ and above, for copy/move to arbitrary external storage,
                        // MANAGE_EXTERNAL_STORAGE is required. If not granted, request it.
                        _uiState.update { it.copy(pendingOperation = null) } // Clear pending op
                        viewModelScope.launch { _singleEvent.emit(FileManagerUiEvent.RequestManageExternalStoragePermission) }
                    } else {
                        // Permission is granted or not Android 11+, proceed with operation
                        _uiState.update { it.copy(pendingOperation = event.op) }
                    }
                } else {
                    // For other operations (like DOWNLOAD or null), proceed directly
                    _uiState.update { it.copy(pendingOperation = event.op) }
                }
            }
            is FileManagerUiEvent.RequestSingleOperation -> {
                _uiState.update { 
                    it.copy(
                        pendingOperation = event.op,
                        selectedPaths = setOf(event.entry.path),
                        isMultiSelectMode = false,
                        showActionMenu = false
                    ) 
                }
            }
            is FileManagerUiEvent.OperationComplete -> {
                viewModelScope.launch(Dispatchers.Main) {
                    GlobalToastManager.showToast(event.message)
                }
                // 2026-10-29 需求：复制/移动完成后自动跳转到目标目录（而非停留在来源目录），
                // 让用户立刻看到操作结果。先更新 currentPath 再 loadFiles 才会扫描目标目录。
                _uiState.update {
                    it.copy(
                        currentPath = event.targetPath,
                        pendingOperation = null,
                        isMultiSelectMode = false,
                        selectedPaths = emptySet()
                    )
                }
                loadFiles()
            }
            is FileManagerUiEvent.RequestManageExternalStoragePermission -> {
                // This event is handled by _singleEvent emission in the screen, no direct state update here.
            }
        }
    }

    // Keep loadFiles and other internal methods, remove the public action methods.
    // showLoading=false 为"静默刷新"（自动触发用）：不置 isLoading，列表不被全屏转圈替换、原地更新。
    fun loadFiles(showLoading: Boolean = true) {
        val state = _uiState.value
        val path = state.currentPath
        val fileType = currentFileType

        _uiState.update {
            if (showLoading) it.copy(isLoading = true, loadError = null) else it.copy(loadError = null)
        }
        val seq = ++loadSeq
        // 目录监听跟随最新一次加载重建：换了目录就停旧的；同目录静默刷新不重建（避免无谓的监听抖动）
        if (dirObserverPath != null && dirObserverPath != path) stopDirWatcher()

        viewModelScope.launch {
            val context = getApplication<Application>()
            when (fileType) {
                "all" -> {
                    _uiState.update { it.copy(titleText = path) }
                    FileScanner.scanDirectory(path)
                        .onSuccess { if (seq == loadSeq) { updateFileList(it); startDirWatcher(path) } }
                        .onFailure { e: Throwable -> if (seq == loadSeq) _uiState.update { it.copy(loadError = AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()), isLoading = false, isRefreshing = false) } }
                }
                "apk" -> {
                    _uiState.update { it.copy(titleText = getApplication<Application>().getString(R.string.file_manager_title_apk)) }
                    // 自动修正安装包后缀：在列 .apk 的同一次遍历中顺手还原 .apk1/.apk.1，
                    // 一遍遍历同时完成改名与收集，避免二次遍历外置存储
                    val autoFixSuffix = (getApplication() as TVTransferApp).settingsRepository.getAutoFixApkSuffix()
                    FileScanner.getApkFiles(6, autoFixSuffix = autoFixSuffix)
                        .onSuccess { if (seq == loadSeq) updateFileList(it) }
                        .onFailure { e: Throwable -> if (seq == loadSeq) _uiState.update { it.copy(loadError = AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()), isLoading = false, isRefreshing = false) } }
                }
                "video" -> {
                    _uiState.update { it.copy(titleText = getApplication<Application>().getString(R.string.file_manager_title_video)) }
                    FileScanner.getMediaFiles(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        .onSuccess { if (seq == loadSeq) updateFileList(it) }
                        .onFailure { e: Throwable -> if (seq == loadSeq) _uiState.update { it.copy(loadError = AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()), isLoading = false, isRefreshing = false) } }
                }
                "image" -> {
                    _uiState.update { it.copy(titleText = getApplication<Application>().getString(R.string.file_manager_title_image)) }
                    FileScanner.getMediaFiles(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        .onSuccess { if (seq == loadSeq) updateFileList(it) }
                        .onFailure { e: Throwable -> if (seq == loadSeq) _uiState.update { it.copy(loadError = AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()), isLoading = false, isRefreshing = false) } }
                }
                "music", "audio" -> {
                    _uiState.update { it.copy(titleText = getApplication<Application>().getString(R.string.file_manager_title_music)) }
                    FileScanner.getMediaFiles(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                        .onSuccess { if (seq == loadSeq) updateFileList(it) }
                        .onFailure { e: Throwable -> if (seq == loadSeq) _uiState.update { it.copy(loadError = AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()), isLoading = false, isRefreshing = false) } }
                }
            }
        }
    }

    /** 手动下拉刷新：保留现有列表原地更新（不触发全屏转圈），由 PullToRefreshBox 的 isRefreshing 驱动顶部指示器。 */
    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, loadError = null) }
        loadFiles(showLoading = false)
    }

    private fun updateFileList(newList: List<FileEntry>) {
        val old = _uiState.value.fileList
        // 自动刷新（目录监听 / 媒体分类轮询）会频繁触发，若内容未变则不重建列表，
        // 避免焦点与滚动位置每次都被重置（媒体库轮询无变化时尤其明显）
        if (old.isNotEmpty() && old.size == newList.size) {
            var identical = true
            for (i in old.indices) {
                val a = old[i]
                val b = newList[i]
                if (a.path != b.path || a.size != b.size ||
                    a.lastModified != b.lastModified || a.isDirectory != b.isDirectory
                ) {
                    identical = false
                    break
                }
            }
            if (identical) {
                // 仅清掉 loading 标记，不触碰列表与排序结果
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                return
            }
        }
        _uiState.update { state ->
            val filtered = if (state.showHiddenFiles) newList else newList.filter { !it.name.startsWith(".") }
            val sorted = sortFileList(filtered, state.sortMode)
            state.copy(
                fileList = newList,
                displayList = sorted,
                isLoading = false,
                isRefreshing = false
            )
        }
    }

    private fun refreshDisplayList() {
        _uiState.update { state ->
            val filtered = if (state.showHiddenFiles) state.fileList else state.fileList.filter { !it.name.startsWith(".") }
            val sorted = sortFileList(filtered, state.sortMode)
            state.copy(displayList = sorted)
        }
    }

    // --- Private Scanners ---

    private fun sortFileList(list: List<FileEntry>, sortMode: SortMode): List<FileEntry> {
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

    // --- 自动刷新：目录监听（仅"全部文件"视图按目录扫描；媒体分类走 MediaStore，不挂监听） ---

    @Suppress("DEPRECATION") // FileObserver(String) 在 API 29 标废弃，但 File 构造器要求 API 29+；minSdk 21 全版本统一走 String 构造器
    private fun startDirWatcher(watchedPath: String) {
        if (dirObserver != null && dirObserverPath == watchedPath) return
        stopDirWatcher()
        val observer = object : FileObserver(watchedPath, DIR_WATCH_EVENTS) {
            override fun onEvent(event: Int, eventPath: String?) {
                // 部分设备会夹带掩码外事件，按位过滤后忽略
                if (event and DIR_WATCH_EVENTS == 0) return
                scheduleSilentRefresh(watchedPath)
            }
        }
        observer.startWatching()
        dirObserver = observer
        dirObserverPath = watchedPath
    }

    // 目录事件防抖：突发批量变更（批量拷贝/解压等）只在尘埃落定后静默刷一次
    private fun scheduleSilentRefresh(watchedPath: String) {
        dirWatchDebounceJob?.cancel()
        dirWatchDebounceJob = viewModelScope.launch {
            delay(DIR_WATCH_DEBOUNCE_MS)
            dirWatchDebounceJob = null
            // 期间用户已翻走目录就不再触发；即便漏判，loadSeq 竞态守卫也会丢弃旧结果
            if (_uiState.value.currentPath == watchedPath) {
                loadFiles(showLoading = false)
            }
        }
    }

    private fun stopDirWatcher() {
        dirWatchDebounceJob?.cancel()
        dirWatchDebounceJob = null
        dirObserver?.stopWatching()
        dirObserver = null
        dirObserverPath = null
    }

    override fun onCleared() {
        stopDirWatcher()
        stopMediaStoreObserver()
        super.onCleared()
    }

    companion object {
        private const val DIR_WATCH_DEBOUNCE_MS = 500L
        // 内容变化相关事件：新建/删除/改名（移入移出）/写完（传输完成落盘）
        private val DIR_WATCH_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
                FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
    }
}