package com.tvflyshare.app.ui.screens.filemanager

import androidx.compose.ui.res.stringResource

import com.tvflyshare.app.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
// PullToRefreshBox 位于 material3 的 pulltorefresh 子包：通配 material3.* 不含子包，必须显式导入
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.uiconfig.components.TvDropdownMenu
import com.tvflyshare.app.uiconfig.components.TvDropdownMenuItem
import com.tvflyshare.app.uiconfig.components.TvFileListItem
import com.tvflyshare.app.uiconfig.components.*
import com.tvflyshare.app.settings.SettingsRepository
import com.tvflyshare.app.uiconfig.global.applyTvDialogBlurEffect
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.util.FileEntry
import com.tvflyshare.app.util.FileOperations
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.PreviewSupport
import com.tvflyshare.app.util.StoragePermissionUtils
import com.tvflyshare.app.util.DeleteRiskClassifier
import com.tvflyshare.app.util.DeleteRiskLevel
import com.tvflyshare.app.ui.viewmodel.FileManagerViewModel
import com.tvflyshare.app.filebrowser.model.FileOperationType
import com.tvflyshare.app.filebrowser.model.FileItem
import com.tvflyshare.app.filebrowser.model.toFileItem
import com.tvflyshare.app.filebrowser.ui.FileCreateFolderDialog
import com.tvflyshare.app.filebrowser.ui.FileDeleteConfirmDialog
import com.tvflyshare.app.filebrowser.ui.FileDetailsDialog
import com.tvflyshare.app.filebrowser.ui.BrowserToolbar
import com.tvflyshare.app.filebrowser.ui.FileItemGrid
import com.tvflyshare.app.filebrowser.ui.FileItemRow
import com.tvflyshare.app.filebrowser.ui.FileRenameDialog
import com.tvflyshare.app.filebrowser.model.BrowserCapabilities
import com.tvflyshare.app.filebrowser.ui.FileActionCallbacks
import com.tvflyshare.app.filebrowser.ui.MultiSelectMenu
import com.tvflyshare.app.filebrowser.ui.buildFileActionMenuItems
import kotlinx.coroutines.CoroutineScope
import com.tvflyshare.app.filebrowser.model.SortMode
import java.io.File
import com.tvflyshare.app.ui.viewmodel.FileManagerUiEvent
import com.tvflyshare.app.Constants

// ============================================================
// Main Screen
// ============================================================

// PullToRefreshBox 标注 @ExperimentalMaterial3Api，工程开 allWarningsAsErrors，-Werror 下必须显式 OptIn
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    fileType: String,
    rootPath: String? = null,
    onBack: () -> Unit,
    onPreview: (String) -> Unit = {},
    viewModel: FileManagerViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isNavigable = fileType == "all"

    // 网格列数随窗口宽度分档：竖屏手机（COMPACT，<600dp）2 列，横屏/TV/竖屏平板保持 4 列。
    // 原写死 4 列：竖屏手机上每列仅约 70dp，文件图标+名称严重挤压
    val windowInfo = rememberWindowAdaptiveInfo()
    val isCompact = windowInfo.widthClass == WindowWidthClass.COMPACT
    val gridColumns = if (windowInfo.widthClass == WindowWidthClass.COMPACT) 2 else 4
    
    // Local-only UI states (no VM counterpart): 权限弹窗与文件详情目标
    var showManageStoragePermissionDialog by remember { mutableStateOf(false) }
    var detailsTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var isBookmarkDirView by remember { mutableStateOf(false) }
    var bookmarkEntryPointPath by remember { mutableStateOf<String?>(null) }
    var bookmarkMultiSelectMode by remember { mutableStateOf(false) }
    var bookmarkSelectedPaths by remember { mutableStateOf(setOf<String>()) }
    var bookmarkToDelete by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val fileListFocusRequester = remember { FocusRequester() }
    var prevPendingOperation by remember { mutableStateOf<FileOperationType?>(null) }

    LaunchedEffect(fileType, rootPath) {
        viewModel.initData(fileType, rootPath)
    }

    // 自动刷新兜底：回到本页（ON_RESUME，如从系统安装器/外部应用返回）时静默重扫当前目录。
    // 目录内容变化本身由 ViewModel 内的 FileObserver 覆盖，这里只管"页面离开又回来"的场景。
    // 首次进入跳过：initData 已加载过，避免重复扫描。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) {
                    firstResume = false
                } else {
                    viewModel.loadFiles(showLoading = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 移动/复制完成后（目录选择浮层关闭、列表 refresh），主动把遥控焦点钉回文件列表，
    // 否则原 focusRestorer 记住的那一项因列表内容变化而失效，焦点落到"无主"状态，
    // 导致"选完目标目录后要点很多下才能看到焦点"
    LaunchedEffect(uiState.pendingOperation) {
        if (prevPendingOperation != null && uiState.pendingOperation == null) {
            // 低版本（如 Android 5.x）上，文件列表项在重组时序里可能尚未挂载，
            // 此时 requestFocus 会抛 IllegalStateException("FocusRequester is not initialized")。
            // 捕获后跳过即可，焦点会在后续重组自行恢复，不阻断复制/移动/删除等文件操作。
            try {
                fileListFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                Log.w("FileManagerScreen", "requestFocus 跳过：焦点节点尚未挂载", e)
            }
        }
        prevPendingOperation = uiState.pendingOperation
    }

    LaunchedEffect(viewModel.singleEvent) {
        viewModel.singleEvent.collect { event ->
            when (event) {
                is FileManagerUiEvent.RequestManageExternalStoragePermission -> {
                    showManageStoragePermissionDialog = true
                }
                else -> {}
            }
        }
    }

    BackHandler {
        when {
            uiState.showSortMenu -> viewModel.onEvent(FileManagerUiEvent.ShowSortMenu(false))
            uiState.showOptionsMenu -> viewModel.onEvent(FileManagerUiEvent.ShowOptionsMenu(false))
            uiState.renameTarget != null -> viewModel.onEvent(FileManagerUiEvent.ShowRenameDialog(null))
            uiState.showActionMenu -> viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(false))
            detailsTarget != null -> detailsTarget = null
            showBookmarksDialog -> showBookmarksDialog = false
            bookmarkToDelete != null -> bookmarkToDelete = null
            bookmarkMultiSelectMode -> {
                bookmarkMultiSelectMode = false
                bookmarkSelectedPaths = emptySet()
            }
            isBookmarkDirView -> isBookmarkDirView = false
            uiState.isMultiSelectMode -> viewModel.onEvent(FileManagerUiEvent.ToggleMultiSelectMode)
            uiState.pendingOperation != null -> viewModel.onEvent(FileManagerUiEvent.RequestOperation(null))
            else -> {
                if (isNavigable && uiState.currentPath != (rootPath ?: Constants.INTERNAL_STORAGE_ROOT)) {
                    if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                        bookmarkEntryPointPath = null
                        isBookmarkDirView = true
                    } else {
                        viewModel.onEvent(FileManagerUiEvent.NavigateUp)
                    }
                } else {
                    if (bookmarkEntryPointPath != null) {
                        bookmarkEntryPointPath = null
                        isBookmarkDirView = true
                    } else {
                        onBack()
                    }
                }
            }
        }
    }

    TVBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                        if (isBookmarkDirView) {
                            bookmarkMultiSelectMode = !bookmarkMultiSelectMode
                            if (!bookmarkMultiSelectMode) bookmarkSelectedPaths = emptySet()
                        } else {
                            viewModel.onEvent(FileManagerUiEvent.ToggleMultiSelectMode)
                        }
                        true
                    } else {
                        false
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── TopBar（共用组件，外观与迁移前一致） ──
                BrowserToolbar(
                    title = if (isBookmarkDirView) {
                        stringResource(id = R.string.file_manager_bookmark_view)
                    } else {
                        uiState.titleText
                    },
                    selectedCount = if (isBookmarkDirView) bookmarkSelectedPaths.size else uiState.selectedPaths.size,
                    inMultiSelect = if (isBookmarkDirView) bookmarkMultiSelectMode else uiState.isMultiSelectMode,
                    showActions = isNavigable && !isBookmarkDirView,
                    capabilities = LOCAL_CAPABILITIES,
                    sortMode = uiState.sortMode,
                    showHiddenFiles = uiState.showHiddenFiles,
                    onToggleGridView = { viewModel.onEvent(FileManagerUiEvent.ToggleGridView) },
                    onToggleHidden = { viewModel.onEvent(FileManagerUiEvent.ToggleHiddenFiles) },
                    onSetSortMode = { mode -> viewModel.onEvent(FileManagerUiEvent.SetSortMode(mode)) },
                    onCreateFolder = { viewModel.onEvent(FileManagerUiEvent.ShowCreateFolderDialog(true)) },
                    onBack = {
                        if (isBookmarkDirView) {
                            isBookmarkDirView = false
                        } else if (isNavigable && uiState.currentPath != (rootPath ?: Constants.INTERNAL_STORAGE_ROOT)) {
                            if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                                bookmarkEntryPointPath = null
                                isBookmarkDirView = true
                            } else {
                                viewModel.onEvent(FileManagerUiEvent.NavigateUp)
                            }
                        } else {
                            if (bookmarkEntryPointPath != null) {
                                bookmarkEntryPointPath = null
                                isBookmarkDirView = true
                            } else {
                                onBack()
                            }
                        }
                    },
                    onOpenBookmarks = { isBookmarkDirView = true },
                )

                // ── 内容区 ──
                if (isBookmarkDirView) {
                    val repo = remember { SettingsRepository(context) }
                    var bookmarks by remember { mutableStateOf(repo.getLocalBookmarks()) }
                    LaunchedEffect(bookmarkToDelete) {
                        if (bookmarkToDelete == null) {
                            bookmarks = repo.getLocalBookmarks()
                        }
                    }
                    if (bookmarks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(id = R.string.file_manager_empty_bookmarks), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontTitle)
                        }
                    } else {
                        val widePanelVisibleBookmark = !isCompact && bookmarkMultiSelectMode
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = if (isCompact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing2Xl)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (widePanelVisibleBookmark) Modifier.padding(end = TvTheme.dimens.spacing3Xl * 4 + TvTheme.dimens.spacingLg) else Modifier)
                            ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(gridColumns),
                                modifier = Modifier.fillMaxSize().focusRestorer(),
                                contentPadding = PaddingValues(vertical = TvTheme.dimens.spacingMd),
                                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs),
                                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                            ) {
                                items(count = bookmarks.size, key = { index -> "bm_$index" }) { index ->
                                    val bookmark = bookmarks[index]
                                    val item = FileItem(name = bookmark.first, path = bookmark.second, isDirectory = true)
                                    FileItemGrid(
                                        item = item,
                                        isMultiSelectMode = bookmarkMultiSelectMode,
                                        isSelected = bookmarkSelectedPaths.contains(bookmark.second),
                                        onClick = {
                                            if (bookmarkMultiSelectMode) {
                                                val newPaths = bookmarkSelectedPaths.toMutableSet()
                                                if (newPaths.contains(bookmark.second)) newPaths.remove(bookmark.second) else newPaths.add(bookmark.second)
                                                bookmarkSelectedPaths = newPaths
                                            } else {
                                                bookmarkEntryPointPath = bookmark.second
                                                isBookmarkDirView = false
                                                viewModel.onEvent(FileManagerUiEvent.NavigateTo(bookmark.second))
                                            }
                                        },
                                        onLongClick = {
                                            if (!bookmarkMultiSelectMode) {
                                                bookmarkMultiSelectMode = true
                                                bookmarkSelectedPaths = setOf(bookmark.second)
                                            }
                                        },
                                        modifier = Modifier.onFocusChanged { if (it.isFocused) { viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(false)) } }
                                    )
                                }
                            }
                            } // Column end (bookmark)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = bookmarkMultiSelectMode,
                                modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg)
                            ) {
                                TvSideMenu(
                                    title = stringResource(id = R.string.smb_multi_select_actions),
                                    subtitle = stringResource(id = R.string.file_manager_items_selected, bookmarkSelectedPaths.size),
                                    items = buildList {
                                        add(TvMenuItem(stringResource(id = R.string.file_manager_action_select_all), Icons.Default.CheckCircle, TvMenuItemStyle.DEFAULT, enabled = bookmarkSelectedPaths.size < bookmarks.size) {
                                            bookmarkSelectedPaths = bookmarks.map { it.second }.toSet()
                                        })
                                        add(TvMenuItem(stringResource(id = R.string.smb_unbookmark), Icons.Default.Star, TvMenuItemStyle.DEFAULT, enabled = bookmarkSelectedPaths.isNotEmpty()) {
                                            bookmarkToDelete = "BATCH"
                                        })
                                        add(TvMenuItem(stringResource(id = R.string.file_manager_action_cancel), Icons.Default.Close, TvMenuItemStyle.DEFAULT) {
                                            bookmarkMultiSelectMode = false
                                            bookmarkSelectedPaths = emptySet()
                                        })
                                    },
                                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4)
                                )
                            }
                        }
                    }
                } else when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = TvTheme.colors.brandPrimary,
                                strokeWidth = TvTheme.dimens.borderFocused,
                                modifier = Modifier.size(TvTheme.dimens.iconSizeXl)
                            )
                        }
                    }
                    uiState.loadError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = uiState.loadError ?: "",
                                    color = TvTheme.colors.textSecondary,
                                    fontSize = TvTheme.dimens.fontCol2Title,
                                )
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                Text(
                                    text = stringResource(id = R.string.permission_retry),
                                    color = TvTheme.colors.brandPrimary,
                                    fontSize = TvTheme.dimens.fontCol1Text,
                                    modifier = Modifier.clickable {
                                        viewModel.loadFiles()
                                    }
                                )
                            }
                        }
                    }
                    uiState.displayList.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(id = R.string.dir_picker_empty_dir),
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontTitle,
                            )
                        }
                    }
                    else -> {
                        PullToRefreshBox(
                            isRefreshing = uiState.isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                        val widePanelVisible = !isCompact && (
                            (!uiState.isMultiSelectMode && uiState.selectedEntry != null && uiState.showActionMenu) ||
                            (uiState.isMultiSelectMode && !uiState.showMultiDeleteConfirmDialog && uiState.pendingOperation == null)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = if (isCompact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing2Xl)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (widePanelVisible) Modifier.padding(end = TvTheme.dimens.sideMenuWidth + TvTheme.dimens.spacingLg) else Modifier)
                            ) {
                            // ── 左侧：文件列表 ──
                            val onFileClick: (FileEntry) -> Unit = { entry ->
                                if (uiState.isMultiSelectMode) {
                                    viewModel.onEvent(FileManagerUiEvent.ToggleSelection(entry.path))
                                } else if (isNavigable && entry.isDirectory) {
                                    viewModel.onEvent(FileManagerUiEvent.NavigateTo(entry.path))
                                } else if (SettingsRepository(context).getBuiltInPreview() && PreviewSupport.canPreview(entry.name)) {
                                    // 内建预览开启且为可预览类型（文本/图片）：点一下直接预览，不再弹操作菜单
                                    onPreview(entry.path)
                                } else {
                                    viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(true, entry))
                                }
                            }

                            val onFileLongClick: (FileEntry) -> Unit = { entry ->
                                if (!uiState.isMultiSelectMode) {
                                    viewModel.onEvent(FileManagerUiEvent.ToggleMultiSelectMode)
                                    viewModel.onEvent(FileManagerUiEvent.ToggleSelection(entry.path))
                                }
                            }

                            if (uiState.isGridView) {
                                LazyVerticalGrid(
                                        columns = GridCells.Fixed(gridColumns),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusRequester(fileListFocusRequester)
                                            .focusRestorer(),
                                        contentPadding = PaddingValues(vertical = TvTheme.dimens.spacingMd),
                                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs),
                                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                                ) {
                                    if (isNavigable && uiState.currentPath != uiState.rootPath) {
                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                            ParentDirItem { 
                                                if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                                                    bookmarkEntryPointPath = null
                                                    isBookmarkDirView = true
                                                } else {
                                                    viewModel.onEvent(FileManagerUiEvent.NavigateUp)
                                                }
                                            }
                                        }
                                    }
                                    gridItems(items = uiState.displayList, key = { it.path }) { entry ->
                                        FileItemGrid(
                                            item = entry.toFileItem(),
                                            isMultiSelectMode = uiState.isMultiSelectMode,
                                            isSelected = uiState.selectedPaths.contains(entry.path),
                                            onClick = { onFileClick(entry) },
                                            onLongClick = { onFileLongClick(entry) },
                                            modifier = Modifier.onFocusChanged { if (it.isFocused) { viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(false)) } }
                                        )
                                    }
                                }
                            } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusRequester(fileListFocusRequester)
                                            .focusRestorer(),
                                        contentPadding = PaddingValues(vertical = TvTheme.dimens.spacingMd),
                                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                                    ) {
                                    if (isNavigable && uiState.currentPath != uiState.rootPath) {
                                        item {
                                            ParentDirItem {
                                                if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                                                    bookmarkEntryPointPath = null
                                                    isBookmarkDirView = true
                                                } else {
                                                    viewModel.onEvent(FileManagerUiEvent.NavigateUp)
                                                }
                                            }
                                        }
                                    }
                                    items(items = uiState.displayList, key = { it.path }) { entry ->
                                        FileItemRow(
                                            item = entry.toFileItem(),
                                            isMultiSelectMode = uiState.isMultiSelectMode,
                                            isSelected = uiState.selectedPaths.contains(entry.path),
                                            onClick = { onFileClick(entry) },
                                            onLongClick = { onFileLongClick(entry) },
                                            modifier = Modifier.onFocusChanged { if (it.isFocused) { viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(false)) } }
                                        )
                                    }
                                }
                            }
                            } // Column end (normal)
                        }

                            // ── 右侧：操作面板 ──
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !uiState.isMultiSelectMode && uiState.selectedEntry != null && uiState.showActionMenu,
                                modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg)
                            ) {
                                uiState.selectedEntry?.let { entry ->
                                    TvSideMenu(
                                        title = entry.name,
                                        subtitle = "",
                                        items = buildFileActionMenuItems(
                                            item = entry.toFileItem(),
                                            capabilities = LOCAL_CAPABILITIES,
                                            dismissActionMenu = { viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(false)) },
                                            callbacks = localActionCallbacks(
                                                context = context,
                                                coroutineScope = coroutineScope,
                                                entry = entry,
                                                onPreview = onPreview,
                                                onRename = { viewModel.onEvent(FileManagerUiEvent.ShowRenameDialog(entry)) },
                                                onCopy = { viewModel.onEvent(FileManagerUiEvent.RequestSingleOperation(entry, FileOperationType.COPY)) },
                                                onMove = { viewModel.onEvent(FileManagerUiEvent.RequestSingleOperation(entry, FileOperationType.MOVE)) },
                                                onDetails = { detailsTarget = entry },
                                                onDelete = { viewModel.onEvent(FileManagerUiEvent.ShowDeleteConfirmDialog(true, entry)) },
                                            ),
                                        ),
                                        modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.sideMenuWidth)
                                    )
                                }
                            }

                            // 多选模式操作栏
                            androidx.compose.animation.AnimatedVisibility(
                                visible = uiState.isMultiSelectMode && !uiState.showMultiDeleteConfirmDialog && uiState.pendingOperation == null,
                                modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg)
                            ) {
                                MultiSelectMenu(
                                    selectedCount = uiState.selectedPaths.size,
                                    totalCount = uiState.displayList.size,
                                    hasFolderSelected = uiState.displayList.any { uiState.selectedPaths.contains(it.path) && it.isDirectory },
                                    capabilities = LOCAL_CAPABILITIES,
                                    onSelectAll = { viewModel.onEvent(FileManagerUiEvent.SelectAll) },
                                    onCancel = { viewModel.onEvent(FileManagerUiEvent.ToggleMultiSelectMode) },
                                    onCopy = { viewModel.onEvent(FileManagerUiEvent.RequestOperation(FileOperationType.COPY)) },
                                    onMove = { viewModel.onEvent(FileManagerUiEvent.RequestOperation(FileOperationType.MOVE)) },
                                    onDownload = {},
                                    onDelete = { viewModel.onEvent(FileManagerUiEvent.ShowMultiDeleteConfirmDialog(true)) },
                                    onBookmark = {
                                        coroutineScope.launch {
                                            var count = 0
                                            uiState.displayList.filter { uiState.selectedPaths.contains(it.path) && it.isDirectory }.forEach { folder ->
                                                SettingsRepository(context).addLocalBookmark(folder.name, folder.path)
                                                count++
                                            }
                                            GlobalToastManager.showToast(context.getString(R.string.file_manager_bookmarked_multi, count))
                                            viewModel.onEvent(FileManagerUiEvent.ToggleMultiSelectMode)
                                        }
                                    },
                                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.sideMenuWidth)
                                )
                            }
                        }
                    }
                }

                // ── 重命名对话框 ──
                uiState.renameTarget?.let { target ->
                    FileRenameDialog(
                        item = target.toFileItem(),
                        onConfirm = { newName ->
                            viewModel.onEvent(FileManagerUiEvent.ConfirmRename(target, newName))
                            viewModel.onEvent(FileManagerUiEvent.ShowRenameDialog(null))
                        },
                        onDismiss = { 
                            viewModel.onEvent(FileManagerUiEvent.ShowRenameDialog(null))
                        }
                    )
                }

                // ── 新建文件夹对话框 ──
                if (uiState.showCreateFolderDialog) {
                    FileCreateFolderDialog(
                        onConfirm = { name ->
                            viewModel.onEvent(FileManagerUiEvent.ConfirmCreateFolder(name))
                            viewModel.onEvent(FileManagerUiEvent.ShowCreateFolderDialog(false))
                        },
                        onDismiss = { 
                            viewModel.onEvent(FileManagerUiEvent.ShowCreateFolderDialog(false))
                        }
                    )
                }

                // ── 文件详情对话框 ──
                // 2026-11-02 修复：此前详情动作已接（onDetails -> detailsTarget），
                // 但渲染被遗漏，导致点击「详情」无任何反应。复用共用 FileDetailsDialog。
                detailsTarget?.let { target ->
                    FileDetailsDialog(
                        item = target.toFileItem(),
                        pathLabel = target.path,
                        onDismiss = { detailsTarget = null },
                    )
                }
            } // Column End

            // ── 复制/移动目录选择（全屏 overlay） ──
            uiState.pendingOperation?.let { opType ->
                DirPickerScreen(
                    selectedPaths = uiState.selectedPaths.toList(),
                    operationType = opType,
                    onComplete = { targetPath, message ->
                        viewModel.onEvent(FileManagerUiEvent.OperationComplete(message, targetPath))
                    },
                    onCancel = {
                        viewModel.onEvent(FileManagerUiEvent.RequestOperation(null))
                    }
                )
            }

            // ── Toast 浮层由 GlobalToastHost 接管 ──

            // ── 删除确认对话框 ──
            val delEntry = uiState.fileToDelete
            if (uiState.showDeleteConfirmDialog && delEntry != null) {
                // 单删不涉及根目录全选；仅判定 Android 树 / 单应用外部数据
                val delRisk = DeleteRiskClassifier.classify(listOf(delEntry.path), isStorageRoot = false, isSelectAll = false)
                val onDelConfirm: () -> Unit = {
                    viewModel.onEvent(FileManagerUiEvent.ConfirmDelete(delEntry))
                    viewModel.onEvent(FileManagerUiEvent.ShowDeleteConfirmDialog(false))
                    viewModel.onEvent(FileManagerUiEvent.ShowActionMenu(false, null))
                }
                val onDelDismiss: () -> Unit = {
                    viewModel.onEvent(FileManagerUiEvent.ShowDeleteConfirmDialog(false))
                }
                // 高危分级是本地来源自己的策略：把文案算好后交给共用删除确认弹窗
                val dangerTitle: String? = if (!delRisk.isHighRisk) null else if (delRisk.level == DeleteRiskLevel.ANDROID_TREE) {
                    stringResource(id = R.string.danger_delete_android_title)
                } else {
                    stringResource(id = R.string.danger_delete_storage_root_title)
                }
                val dangerMsg: String? = if (!delRisk.isHighRisk) null else if (delRisk.level == DeleteRiskLevel.ANDROID_TREE) {
                    stringResource(id = R.string.danger_delete_android_msg)
                } else {
                    stringResource(id = R.string.danger_delete_storage_root_msg)
                }
                val extHint = if (delRisk.level == DeleteRiskLevel.APP_EXTERNAL) {
                    "\n\n" + stringResource(id = R.string.app_external_delete_hint, delRisk.appPackage ?: "")
                } else {
                    ""
                }
                FileDeleteConfirmDialog(
                    items = listOf(delEntry.toFileItem()),
                    onConfirm = onDelConfirm,
                    onDismiss = onDelDismiss,
                    titleOverride = dangerTitle,
                    messageOverride = dangerMsg
                        ?: (stringResource(id = R.string.msg_confirm_delete_single, delEntry.name) + extHint),
                    isDanger = delRisk.isHighRisk,
                )
            }
            if (bookmarkToDelete != null) {
                TvConfirmDialog(
                    title = stringResource(id = R.string.smb_unbookmark),
                    message = if (bookmarkToDelete == "BATCH") stringResource(id = R.string.smb_msg_unbookmark_multi_confirm, bookmarkSelectedPaths.size) else stringResource(id = R.string.smb_msg_unbookmark_confirm),
                    confirmText = stringResource(id = R.string.smb_unbookmark),
                    cancelText = stringResource(id = R.string.file_manager_action_cancel),
                onConfirm = {
                    bookmarkToDelete?.let { currentBookmarkToDelete ->
                        bookmarkToDelete = null
                        coroutineScope.launch {
                            val repo = SettingsRepository(context)
                            if (currentBookmarkToDelete == "BATCH") {
                                bookmarkSelectedPaths.forEach { repo.removeLocalBookmark(it) }
                                bookmarkSelectedPaths = emptySet()
                                bookmarkMultiSelectMode = false
                                GlobalToastManager.showToast(context.getString(R.string.file_manager_unbookmarked_multi))
                            } else {
                                repo.removeLocalBookmark(currentBookmarkToDelete)
                                GlobalToastManager.showToast(context.getString(R.string.file_manager_unbookmarked))
                            }
                        }
                    }
                },
                    onDismiss = { bookmarkToDelete = null }
                )
            }

            // ── 批量删除确认对话框 ──
            if (uiState.showMultiDeleteConfirmDialog) {
                // 判定是否处于存储卷根目录且已全选当前目录所有可见项（根目录全选删除）
                val rootSegs = uiState.currentPath.trimEnd('/').split('/').filter { it.isNotEmpty() }
                val isStorageRoot = rootSegs.size <= 3 && rootSegs.firstOrNull() == "storage"
                val isSelectAll = uiState.selectedPaths.isNotEmpty() && uiState.selectedPaths.size == uiState.displayList.size
                val multiRisk = DeleteRiskClassifier.classify(uiState.selectedPaths.toList(), isStorageRoot, isSelectAll)
                val onMultiConfirm: () -> Unit = {
                    viewModel.onEvent(FileManagerUiEvent.ConfirmMultiDelete)
                    viewModel.onEvent(FileManagerUiEvent.ShowMultiDeleteConfirmDialog(false))
                }
                val onMultiDismiss: () -> Unit = {
                    viewModel.onEvent(FileManagerUiEvent.ShowMultiDeleteConfirmDialog(false))
                }
                val dangerTitle: String? = if (!multiRisk.isHighRisk) null else if (multiRisk.level == DeleteRiskLevel.ANDROID_TREE) {
                    stringResource(id = R.string.danger_delete_android_title)
                } else {
                    stringResource(id = R.string.danger_delete_storage_root_title)
                }
                val dangerMsg: String? = if (!multiRisk.isHighRisk) null else if (multiRisk.level == DeleteRiskLevel.ANDROID_TREE) {
                    stringResource(id = R.string.danger_delete_android_msg)
                } else {
                    stringResource(id = R.string.danger_delete_storage_root_msg)
                }
                val extHint = if (multiRisk.level == DeleteRiskLevel.APP_EXTERNAL) {
                    "\n\n" + stringResource(id = R.string.app_external_delete_hint, multiRisk.appPackage ?: "")
                } else {
                    ""
                }
                FileDeleteConfirmDialog(
                    items = uiState.displayList.filter { uiState.selectedPaths.contains(it.path) }.map { it.toFileItem() },
                    onConfirm = onMultiConfirm,
                    onDismiss = onMultiDismiss,
                    titleOverride = dangerTitle ?: stringResource(id = R.string.file_manager_multi_delete_title),
                    messageOverride = dangerMsg
                        ?: (stringResource(id = R.string.file_manager_multi_delete_msg, uiState.selectedPaths.size) + extHint),
                    isDanger = multiRisk.isHighRisk,
                )
            }
            // ── 请求 MANAGE_EXTERNAL_STORAGE 权限对话框 ──
            if (showManageStoragePermissionDialog) {
                TvAlertDialog(
                    title = stringResource(id = R.string.error_permission_denied),
                    text = stringResource(id = R.string.file_manager_auth_msg),
                    confirmText = stringResource(id = R.string.file_manager_btn_auth),
                    dismissText = stringResource(id = R.string.file_manager_action_cancel),
                    onConfirm = {
                        try {
                            val intent = StoragePermissionUtils.getManageStorageIntent(context)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            coroutineScope.launch {
                                GlobalToastManager.showToast(context.getString(R.string.file_manager_auth_failed))
                            }
                        }
                        showManageStoragePermissionDialog = false
                    },
                    onDismiss = { showManageStoragePermissionDialog = false }
                )
            }
        }
    }
}


// ============================================================
// 本地来源：能力声明与操作回调（2026-10-30 重构）
// 原先这些逻辑写在 FileManagerActions.kt 的两个菜单构建函数里，
// 现在菜单由共用组件按能力渲染，本页只提供"每个动作具体做什么"。
// ============================================================

private val LOCAL_CAPABILITIES: BrowserCapabilities = BrowserCapabilities.LOCAL

private fun localActionCallbacks(
    context: Context,
    coroutineScope: CoroutineScope,
    entry: FileEntry,
    onPreview: (String) -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
): FileActionCallbacks = FileActionCallbacks(
    onOpen = {
        // 2026-10-30 内建预览：文本与图片改由本应用自己呈现，不再依赖电视上是否装了能接的应用；
        // 其余格式（音视频、压缩包、文档）仍按原样交给系统 Intent。
        // 开关每次点击实时读，设置里关掉后立刻恢复旧行为，不必重启。
        if (SettingsRepository(context).getBuiltInPreview() && PreviewSupport.canPreview(entry.name)) {
            onPreview(entry.path)
        } else {
            coroutineScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    FileOperations.openFile(context, entry)
                }
                GlobalToastManager.showToast(
                    context.getString(if (ok) R.string.file_manager_opening else R.string.file_manager_open_failed)
                )
            }
        }
    },
    onInstall = {
        coroutineScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FileOperations.installApk(context, entry)
            }
            GlobalToastManager.showToast(
                context.getString(if (ok) R.string.file_manager_installing else R.string.file_manager_install_failed)
            )
        }
    },
    onRename = { onRename() },
    onCopy = { onCopy() },
    onMove = { onMove() },
    onBookmark = {
        coroutineScope.launch {
            SettingsRepository(context).addLocalBookmark(entry.name, entry.path)
            GlobalToastManager.showToast(context.getString(R.string.file_manager_bookmarked_single, entry.name))
        }
    },
    onDetails = { onDetails() },
    onDelete = { onDelete() },
)
