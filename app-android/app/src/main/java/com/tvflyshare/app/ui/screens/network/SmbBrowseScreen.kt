package com.tvflyshare.app.ui.screens.network

import com.tvflyshare.app.uiconfig.components.TvEmptyState
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOff

import android.util.Log
import com.tvflyshare.app.uiconfig.global.TvTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import com.tvflyshare.app.smb.SmbConnectionState
import com.tvflyshare.app.smb.SmbFileEntry
import com.tvflyshare.app.smb.SmbFileType
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.TvIconButton
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.components.TvMenuItem
import com.tvflyshare.app.uiconfig.components.TvMenuItemStyle
import com.tvflyshare.app.uiconfig.components.TvSideMenu
import com.tvflyshare.app.uiconfig.global.applyTvDialogBlurEffect
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.settings.SettingsRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import com.tvflyshare.app.filebrowser.model.FileOperationType
import com.tvflyshare.app.ui.viewmodel.SmbBrowseViewModel
import com.tvflyshare.app.ui.screens.network.components.*
import com.tvflyshare.app.ui.viewmodel.SmbUiEvent
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.components.TvDropdownMenu
import com.tvflyshare.app.uiconfig.components.TvDropdownMenuItem
import com.tvflyshare.app.filebrowser.model.SortMode
import com.tvflyshare.app.ui.screens.network.components.SmbFileItemGrid
import com.tvflyshare.app.util.FileActionUtils

@Composable
fun SmbBrowseScreen(
    host: String,
    shareName: String,
    username: String,
    password: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    // 使用 Factory 注入连接参数
    val viewModel: SmbBrowseViewModel = viewModel(
        factory = SmbBrowseViewModel.Factory(application, host, shareName, username, password)
    )

    val uiState by viewModel.uiState.collectAsState()
    val connState by viewModel.connState.collectAsState()
    val fileListFocusRequester = remember { FocusRequester() }
    var isBookmarkDirView by remember { mutableStateOf(false) }
    var bookmarkEntryPointPath by remember { mutableStateOf<String?>(null) }
    var bookmarkMultiSelectMode by remember { mutableStateOf(false) }
    var bookmarkSelectedPaths by remember { mutableStateOf(setOf<String>()) }
    var bookmarkToDelete by remember { mutableStateOf<String?>(null) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val windowInfo = rememberWindowAdaptiveInfo()
    val isCompact = windowInfo.widthClass == WindowWidthClass.COMPACT

    // 初始连接与事件监听
    LaunchedEffect(Unit) {
        viewModel.initConnection()

        viewModel.uiEvent.collect { event ->
            when (event) {
                is SmbUiEvent.NavigateBack -> onBack()
                is SmbUiEvent.OpenFile -> {
                    val canOpen = FileActionUtils.canOpenFile(context, event.file)
                    if (!canOpen) {
                        viewModel.showToast(context.getString(R.string.smb_msg_download_no_app))
                    } else {
                        val intent = FileActionUtils.buildOpenFileIntent(context, event.file)
                        if (intent != null) {
                            val tried = kotlin.runCatching {
                                context.startActivity(intent)
                                true
                            }.getOrDefault(false)
                            viewModel.showToast(if (tried) context.getString(R.string.smb_msg_opened) else context.getString(R.string.smb_msg_download_cannot_open))
                        } else {
                            viewModel.showToast(context.getString(R.string.smb_msg_download_cannot_open))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // 移动/复制完成后（目录选择浮层关闭、列表 refresh），主动把遥控焦点钉回文件列表，
    // 修复"选完目标目录后要点很多下才能看到焦点"的问题
    var prevPendingOp by remember { mutableStateOf<FileOperationType?>(null) }
    LaunchedEffect(uiState.pendingOperation) {
        if (prevPendingOp != null && uiState.pendingOperation == null) {
            // 低版本（如 Android 5.x）上，文件列表项在重组时序里可能尚未挂载，
            // 此时 requestFocus 会抛 IllegalStateException("FocusRequester is not initialized")。
            // 捕获后跳过即可，焦点会在后续重组自行恢复，不阻断复制/移动/删除等操作。
            try {
                fileListFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                Log.w("SmbBrowseScreen", "requestFocus 跳过：焦点节点尚未挂载", e)
            }
        }
        prevPendingOp = uiState.pendingOperation
    }

    // 拦截系统返回键
    androidx.activity.compose.BackHandler {
        when {
            uiState.showCreateFolderDialog -> {
                viewModel.onEvent(SmbUiEvent.ShowCreateFolderDialog(false))
            }
            uiState.isMultiSelectMode -> {
                viewModel.updateState { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
            }
            uiState.showActionMenu -> {
                viewModel.updateState { it.copy(showActionMenu = false, selectedEntry = null) }
            }
            uiState.renameTarget != null -> {
                viewModel.updateState { it.copy(renameTarget = null) }
            }
            uiState.pendingOperation != null -> {
                viewModel.updateState { it.copy(pendingOperation = null) }
            }
            uiState.detailsTarget != null -> {
                viewModel.updateState { it.copy(detailsTarget = null) }
            }
            bookmarkToDelete != null -> {
                bookmarkToDelete = null
            }
            bookmarkMultiSelectMode -> {
                bookmarkMultiSelectMode = false
                bookmarkSelectedPaths = emptySet()
            }
            isBookmarkDirView -> {
                isBookmarkDirView = false
            }
            else -> {
                if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                    bookmarkEntryPointPath = null
                    isBookmarkDirView = true
                } else if (uiState.currentPath == "\\" || uiState.currentPath.isEmpty()) {
                    if (bookmarkEntryPointPath != null) {
                        bookmarkEntryPointPath = null
                        isBookmarkDirView = true
                    } else {
                        onBack()
                    }
                } else {
                    viewModel.handleBack()
                }
            }
        }
    }

    TVBackground {
        ScreenScaffold(
            title = if (isBookmarkDirView) stringResource(id = R.string.smb_title_bookmarks) else stringResource(id = R.string.smb_title_browse),
            showBack = true,
            onBack = {
                if (isBookmarkDirView) {
                    isBookmarkDirView = false
                } else if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                    bookmarkEntryPointPath = null
                    isBookmarkDirView = true
                } else {
                    viewModel.handleBack()
                }
            },
            deviceInfo = if (isBookmarkDirView) null else "$host/$shareName",
            scrollable = false,
            applyBodyPadding = false,
        ) { _ ->

            // 内容区
            when {
                connState is SmbConnectionState.Connecting || uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            CircularProgressIndicator(color = TvTheme.colors.textPrimary)
                            Text(
                                text = if (connState is SmbConnectionState.Connecting) stringResource(id = R.string.smb_status_connecting) else stringResource(id = R.string.smb_status_loading),
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontCaption,
                            )
                        }
                    }
                }

                connState is SmbConnectionState.Error -> {
                    val errorMsg = (connState as SmbConnectionState.Error).message.asString(LocalContext.current)
                    TvEmptyState(
                        title = stringResource(id = R.string.smb_status_connect_failed),
                        subtitle = errorMsg,
                        icon = Icons.Default.CloudOff,
                        actionText = stringResource(id = R.string.smb_btn_retry),
                        onActionClick = {
                            // 2026-10-10 审计修复：改走 ViewModel 串行重连——原先裸 CoroutineScope
                            // 并发 disconnect + initConnection，新连接可能被旧断开拆掉（对齐 FTP 侧）
                            viewModel.retryConnection()
                        }
                    )
                }

                uiState.downloadingFile != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                            modifier = Modifier.padding(horizontal = TvTheme.dimens.spacing2Xl),
                        ) {
                            Text(
                                text = stringResource(id = R.string.smb_status_downloading, uiState.downloadingFile?.name ?: ""),
                                color = TvTheme.colors.textPrimary,
                                fontSize = TvTheme.dimens.fontCaption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LinearProgressIndicator(
                                progress = { uiState.downloadPercent / 100f },
                                color = TvTheme.colors.brandPrimary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${uiState.downloadPercent}%",
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontSmall,
                            )
                        }
                    }
                }

                else -> {
                    // 路径栏 + 文件列表 + 右侧操作面板
                    val widePanelVisible = !isCompact && (
                        (!uiState.isMultiSelectMode && uiState.selectedEntry != null && uiState.showActionMenu) ||
                        (uiState.isMultiSelectMode && uiState.pendingDelete.isEmpty() && uiState.pendingOperation == null && uiState.pendingDownload.isEmpty())
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isCompact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing2Xl)
                            .padding(top = TvTheme.dimens.spacingSm, bottom = TvTheme.dimens.spacingMd),
                    ) {
                        // 左侧：路径 + 文件列表（宽屏且右侧面板可见时右侧留白，竖屏由底部浮层覆盖，无需留白）
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (widePanelVisible) Modifier.padding(end = TvTheme.dimens.spacing3Xl * 4 + TvTheme.dimens.spacingLg) else Modifier)
                        ) {
                            // 路径栏与顶部菜单
                            var showOptionsMenu by remember { mutableStateOf(false) }
                            var showSortMenu by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(TvTheme.dimens.topBarHeight),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = TvTheme.colors.textSecondary,
                                    modifier = Modifier.size(TvTheme.dimens.spacingMd),
                                )
                                Text(
                                    text = if (isBookmarkDirView) stringResource(id = R.string.smb_bookmark_desc) else " ${uiState.currentPath}",
                                    color = TvTheme.colors.textSecondary,
                                    fontSize = TvTheme.dimens.fontSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = TvTheme.dimens.spacingXs).weight(1f),
                                )
                                if (isBookmarkDirView) {
                                    if (bookmarkMultiSelectMode) {
                                        Text(
                                            text = stringResource(id = R.string.smb_items_selected, bookmarkSelectedPaths.size),
                                            color = TvTheme.colors.brandPrimary,
                                            fontSize = TvTheme.dimens.fontSmall,
                                        )
                                    }
                                } else if (uiState.isMultiSelectMode) {
                                    Text(
                                        text = stringResource(id = R.string.smb_items_selected, uiState.selectedPaths.size),
                                        color = TvTheme.colors.brandPrimary,
                                        fontSize = TvTheme.dimens.fontSmall,
                                    )
                                } else {
                                    // 收藏
                                    TvIconButton(
                                        icon = Icons.Default.Star,
                                        contentDescription = stringResource(id = R.string.smb_bookmark_desc),
                                        onClick = { isBookmarkDirView = true }
                                    )
                                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                                    // 视图切换
                                    TvIconButton(
                                        icon = Icons.AutoMirrored.Filled.List,
                                        contentDescription = stringResource(id = R.string.smb_view_toggle_desc),
                                        onClick = { viewModel.onEvent(SmbUiEvent.ToggleGridView) }
                                    )
                                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                                    // 更多操作菜单
                                    val menuInteraction = remember { MutableInteractionSource() }
                                    Box(
                                        modifier = Modifier
                                            .size(TvTheme.dimens.iconSizeLg)
                                            .clickable(
                                                interactionSource = menuInteraction,
                                                indication = null,
                                                onClick = { showOptionsMenu = true }
                                            )
                                            .tvFocusStyle(
                                                interactionSource = menuInteraction,
                                                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                                                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                                                normalBgColor = TvTheme.colors.surfaceVariant.copy(alpha = 0.3f),
                                                focusedBorderColor = TvTheme.colors.brandPrimary,
                                                normalBorderColor = TvTheme.colors.borderNormal,
                                                borderWidth = TvTheme.dimens.borderFocused,
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource(id = R.string.smb_more_actions_desc),
                                            tint = TvTheme.colors.textPrimary,
                                            modifier = Modifier.size(TvTheme.dimens.iconSizeSm)
                                        )
                                        androidx.compose.material3.MaterialTheme(
                                            colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(surface = TvTheme.colors.bgGradientTop)
                                        ) {
                                            TvDropdownMenu(
                                                expanded = showOptionsMenu,
                                                onDismissRequest = { showOptionsMenu = false },
                                                modifier = Modifier.background(TvTheme.colors.bgGradientTop)
                                            ) {
                                                TvDropdownMenuItem(
                                                    text = stringResource(id = R.string.smb_sort),
                                                    onClick = { showOptionsMenu = false; showSortMenu = true }
                                                )
                                                TvDropdownMenuItem(
                                                    text = stringResource(id = R.string.smb_new_folder_title),
                                                    onClick = { showOptionsMenu = false; viewModel.onEvent(SmbUiEvent.ShowCreateFolderDialog(true)) }
                                                )
                                                TvDropdownMenuItem(
                                                    text = if (uiState.showHiddenFiles) "✓ " + stringResource(id = R.string.smb_show_hidden) else stringResource(id = R.string.smb_show_hidden),
                                                    onClick = { showOptionsMenu = false; viewModel.onEvent(SmbUiEvent.ToggleHiddenFiles) }
                                                )
                                            }
                                            TvDropdownMenu(
                                                expanded = showSortMenu,
                                                onDismissRequest = { showSortMenu = false },
                                                modifier = Modifier.background(TvTheme.colors.bgGradientTop)
                                            ) {
                                                SortMode.entries.forEach { mode ->
                                                    TvDropdownMenuItem(
                                                        text = if (uiState.sortMode == mode) "✓ ${stringResource(id = mode.titleResId)}" else "  ${stringResource(id = mode.titleResId)}",
                                                        onClick = { viewModel.onEvent(SmbUiEvent.SetSortMode(mode)); showSortMenu = false }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (isBookmarkDirView) {
                                val repo = remember { SettingsRepository(context) }
                                var bookmarks by remember { mutableStateOf(repo.getSmbBookmarks(host, shareName)) }
                                LaunchedEffect(bookmarkToDelete) {
                                    if (bookmarkToDelete == null) {
                                        bookmarks = repo.getSmbBookmarks(host, shareName)
                                    }
                                }
                                if (bookmarks.isEmpty()) {
                                    TvEmptyState(
                                        title = stringResource(id = R.string.smb_no_bookmarks_title),
                                        subtitle = stringResource(id = R.string.smb_no_bookmarks_subtitle),
                                        icon = Icons.Default.FolderOff
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (isCompact) 2 else 3),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                // 宽屏多选时右侧面板为浮层，为网格让出面板宽度，避免遮挡最右一列
                                                .then(if (!isCompact && bookmarkMultiSelectMode) Modifier.padding(end = TvTheme.dimens.spacing3Xl * 4 + TvTheme.dimens.spacingLg) else Modifier)
                                                .focusRequester(fileListFocusRequester),
                                            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                        ) {
                                            items(count = bookmarks.size, key = { index -> "bm_$index" }) { index ->
                                                val bookmark = bookmarks[index]
                                                val entry = SmbFileEntry(name = bookmark.first, path = bookmark.second, size = 0, lastModified = 0, isDirectory = true)
                                                SmbFileItemGrid(
                                                    entry = entry,
                                                    isSelected = bookmarkSelectedPaths.contains(bookmark.second),
                                                    isMultiSelectMode = bookmarkMultiSelectMode,
                                                    onClick = {
                                                        if (bookmarkMultiSelectMode) {
                                                            val newPaths = bookmarkSelectedPaths.toMutableSet()
                                                            if (newPaths.contains(bookmark.second)) newPaths.remove(bookmark.second) else newPaths.add(bookmark.second)
                                                            bookmarkSelectedPaths = newPaths
                                                        } else {
                                                            bookmarkEntryPointPath = bookmark.second
                                                            isBookmarkDirView = false
                                                            viewModel.loadDirectory(bookmark.second)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!bookmarkMultiSelectMode) {
                                                            bookmarkMultiSelectMode = true
                                                            bookmarkSelectedPaths = setOf(bookmark.second)
                                                        }
                                                    },
                                                    modifier = Modifier.onFocusChanged { if (it.isFocused) bookmarkMultiSelectMode = false }
                                                )
                                            }
                                        }
                                        
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = bookmarkMultiSelectMode,
                                        modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg)
                                    ) {
                                            TvSideMenu(
                                                title = stringResource(id = R.string.smb_multi_select_title),
                                                subtitle = stringResource(id = R.string.smb_items_selected, bookmarkSelectedPaths.size),
                                                items = buildList {
                                                    add(TvMenuItem(stringResource(id = R.string.smb_action_select_all), Icons.Default.CheckCircle, TvMenuItemStyle.DEFAULT, enabled = bookmarkSelectedPaths.size < bookmarks.size) {
                                                        bookmarkSelectedPaths = bookmarks.map { it.second }.toSet()
                                                    })
                                                    add(TvMenuItem(stringResource(id = R.string.smb_action_unbookmark), Icons.Default.Star, TvMenuItemStyle.DEFAULT, enabled = bookmarkSelectedPaths.isNotEmpty()) {
                                                        bookmarkToDelete = "BATCH"
                                                    })
                                                    add(TvMenuItem(stringResource(id = R.string.smb_action_cancel), Icons.Default.Close, TvMenuItemStyle.DEFAULT) {
                                                        bookmarkMultiSelectMode = false
                                                        bookmarkSelectedPaths = emptySet()
                                                    })
                                                },
                                                modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4)
                                            )
                                        }
                                    }
                                }
                            } else if (uiState.loadError != null) {
                                TvEmptyState(
                                    title = stringResource(id = R.string.smb_status_load_failed),
                                    subtitle = uiState.loadError,
                                    icon = Icons.Default.CloudOff,
                                    actionText = stringResource(id = R.string.smb_btn_retry),
                                    onActionClick = { viewModel.refresh() }
                                )
                            } else if (uiState.displayList.isEmpty() && !uiState.isLoading) {
                                TvEmptyState(
                                    title = stringResource(id = R.string.smb_empty_dir_title),
                                    subtitle = stringResource(id = R.string.smb_empty_dir_subtitle),
                                    icon = Icons.Default.FolderOff
                                )
                            } else {
                                if (uiState.isGridView) {
                                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (isCompact) 2 else 3),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusRequester(fileListFocusRequester),
                                        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                    ) {
                                        if (uiState.currentPath != "\\" && uiState.currentPath.isNotEmpty() && !uiState.isMultiSelectMode) {
                                            item(key = "__parent__", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(if (isCompact) 2 else 3) }) {
                                                ParentDirItem(onClick = { 
                                                    if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                                                        bookmarkEntryPointPath = null
                                                        isBookmarkDirView = true
                                                    } else {
                                                        viewModel.handleBack()
                                                    }
                                                })
                                            }
                                        }
                                        items(
                                            count = uiState.displayList.size,
                                            key = { index -> uiState.displayList[index].path }
                                        ) { index ->
                                            val entry = uiState.displayList[index]
                                            SmbFileItemGrid(
                                                entry = entry,
                                                isSelected = uiState.selectedPaths.contains(entry.path),
                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                onClick = {
                                                    if (uiState.isMultiSelectMode) {
                                                        val newSelected = uiState.selectedPaths.toMutableSet()
                                                        if (newSelected.contains(entry.path)) newSelected.remove(entry.path)
                                                        else newSelected.add(entry.path)
                                                        viewModel.updateState { it.copy(selectedPaths = newSelected) }
                                                    } else if (entry.isDirectory) {
                                                        viewModel.loadDirectory(entry.path)
                                                    } else {
                                                        viewModel.updateState { it.copy(selectedEntry = entry, showActionMenu = true) }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!uiState.isMultiSelectMode) {
                                                        viewModel.updateState {
                                                            it.copy(isMultiSelectMode = true, selectedPaths = setOf(entry.path))
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        viewModel.updateState { it.copy(showActionMenu = false) }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .focusRequester(fileListFocusRequester),
                                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                    ) {
                                        if (uiState.currentPath != "\\" && uiState.currentPath.isNotEmpty() && !uiState.isMultiSelectMode) {
                                            item(key = "__parent__") {
                                                ParentDirItem(onClick = { 
                                                    if (bookmarkEntryPointPath != null && uiState.currentPath == bookmarkEntryPointPath) {
                                                        bookmarkEntryPointPath = null
                                                        isBookmarkDirView = true
                                                    } else {
                                                        viewModel.handleBack()
                                                    }
                                                })
                                            }
                                        }
                                        items(uiState.displayList, key = { it.path }) { entry ->
                                            SmbFileItemRow(
                                                entry = entry,
                                                isSelected = uiState.selectedPaths.contains(entry.path),
                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                onClick = {
                                                    if (uiState.isMultiSelectMode) {
                                                        val newSelected = uiState.selectedPaths.toMutableSet()
                                                        if (newSelected.contains(entry.path)) newSelected.remove(entry.path)
                                                        else newSelected.add(entry.path)
                                                        viewModel.updateState { it.copy(selectedPaths = newSelected) }
                                                    } else if (entry.isDirectory) {
                                                        viewModel.loadDirectory(entry.path)
                                                    } else {
                                                        viewModel.updateState { it.copy(selectedEntry = entry, showActionMenu = true) }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!uiState.isMultiSelectMode) {
                                                        viewModel.updateState {
                                                            it.copy(isMultiSelectMode = true, selectedPaths = setOf(entry.path))
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        viewModel.updateState { it.copy(showActionMenu = false) }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 右侧：操作面板
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !uiState.isMultiSelectMode && uiState.selectedEntry != null && uiState.showActionMenu,
                            modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg),
                        ) {
                            uiState.selectedEntry?.let { entry ->
                                SmbActionPanel(
                                    entry = entry,
                                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4),
                                    onClose = { viewModel.updateState { it.copy(showActionMenu = false, selectedEntry = null) } },
                                    onAction = { action ->
                                        viewModel.updateState { it.copy(showActionMenu = false, selectedEntry = null) }
                                        when (action) {
                                            SmbAction.OPEN -> viewModel.openFile(entry)
                                            SmbAction.INSTALL -> viewModel.installApk(entry)
                                            SmbAction.DELETE -> viewModel.updateState { it.copy(pendingDelete = listOf(entry)) }
                                            SmbAction.RENAME -> viewModel.updateState { it.copy(renameTarget = entry) }
                                            SmbAction.COPY -> viewModel.updateState { it.copy(pendingEntries = listOf(entry), pendingOperation = FileOperationType.COPY) }
                                            SmbAction.MOVE -> viewModel.updateState { it.copy(pendingEntries = listOf(entry), pendingOperation = FileOperationType.MOVE) }
                                            SmbAction.DOWNLOAD -> viewModel.onEvent(SmbUiEvent.RequestDownload(listOf(entry)))
                                            SmbAction.DETAILS -> viewModel.updateState { it.copy(detailsTarget = entry) }
                                            SmbAction.BOOKMARK -> {
                                                SettingsRepository(context).addSmbBookmark(entry.name, entry.path, host, shareName)
                                                viewModel.showToast(context.getString(R.string.smb_msg_bookmarked_single, entry.name))
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        if (!isBookmarkDirView) {
                            // 多选操作栏 (普通目录)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = uiState.isMultiSelectMode && uiState.pendingDelete.isEmpty() && uiState.pendingOperation == null && uiState.pendingDownload.isEmpty(),
                                modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg),
                            ) {
                                SmbMultiSelectPanel(
                                    selectedCount = uiState.selectedPaths.size,
                                    // 2026-10-10 审计修复：总数/全选口径改为 displayList（与 VM SelectAll 对齐，
                                    // 隐藏文件不计入全选）
                                    totalCount = uiState.displayList.size,
                                    hasFolderSelected = uiState.displayList.any { uiState.selectedPaths.contains(it.path) && it.isDirectory },
                                    onSelectAll = {
                                        viewModel.updateState { it.copy(selectedPaths = uiState.displayList.map { f -> f.path }.toSet()) }
                                    },
                                    onCancel = {
                                        viewModel.updateState { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
                                    },
                                    onCopy = {
                                        val entries = uiState.fileList.filter { uiState.selectedPaths.contains(it.path) }
                                        viewModel.updateState { it.copy(pendingEntries = entries, pendingOperation = FileOperationType.COPY) }
                                    },
                                    onMove = {
                                        val entries = uiState.fileList.filter { uiState.selectedPaths.contains(it.path) }
                                        viewModel.updateState { it.copy(pendingEntries = entries, pendingOperation = FileOperationType.MOVE) }
                                    },
                                    onDownload = {
                                        val entries = uiState.fileList.filter { uiState.selectedPaths.contains(it.path) }
                                        viewModel.onEvent(SmbUiEvent.RequestDownload(entries))
                                    },
                                    onDelete = {
                                        val entries = uiState.fileList.filter { uiState.selectedPaths.contains(it.path) }
                                        viewModel.updateState { it.copy(pendingDelete = entries) }
                                    },
                                    onBookmark = {
                                        coroutineScope.launch {
                                            var count = 0
                                            uiState.fileList.filter { uiState.selectedPaths.contains(it.path) && it.isDirectory }.forEach { folder ->
                                                SettingsRepository(context).addSmbBookmark(folder.name, folder.path, host, shareName)
                                                count++
                                            }
                                            viewModel.showToast(context.getString(R.string.smb_msg_bookmarked_multi, count))
                                            viewModel.updateState { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
                                        }
                                    },
                                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4),
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.showCreateFolderDialog) {
                SmbCreateFolderDialog(viewModel = viewModel)
            }
            // 重命名对话框
            uiState.renameTarget?.let { target ->
                SmbRenameDialog(
                    originalName = target.name,
                    onConfirm = { newName -> viewModel.renameFile(target, newName) },
                    onDismiss = { viewModel.updateState { it.copy(renameTarget = null) } },
                )
            }

            uiState.detailsTarget?.let { target ->
                SmbDetailsDialog(target = target, host = host, shareName = shareName, onDismiss = { viewModel.updateState { it.copy(detailsTarget = null) } })
            }
            if (bookmarkToDelete != null) {
                TvConfirmDialog(
                    title = stringResource(id = R.string.smb_unbookmark_title),
                    message = if (bookmarkToDelete == "BATCH") stringResource(id = R.string.smb_unbookmark_multi_msg, bookmarkSelectedPaths.size) else stringResource(id = R.string.smb_unbookmark_single_msg),
                    confirmText = stringResource(id = R.string.smb_action_unbookmark),
                    cancelText = stringResource(id = R.string.smb_btn_cancel),
                    onConfirm = {
                        bookmarkToDelete?.let { currentBookmarkToDelete ->
                            // 2026-10-04 审计 P2 修复：先完成删除再置 null 触发重载——
                            // 原先置 null 的 LaunchedEffect 重载与删除协程无先后保证，
                            // 可能读到删除前的旧数据短暂显示已取消的收藏
                            coroutineScope.launch {
                                val repo = SettingsRepository(context)
                                if (currentBookmarkToDelete == "BATCH") {
                                    bookmarkSelectedPaths.forEach { repo.removeSmbBookmark(it, host, shareName) }
                                    bookmarkSelectedPaths = emptySet()
                                    bookmarkMultiSelectMode = false
                                    viewModel.showToast(context.getString(R.string.smb_msg_unbookmarked_multi))
                                } else {
                                    repo.removeSmbBookmark(currentBookmarkToDelete, host, shareName)
                                    viewModel.showToast(context.getString(R.string.smb_msg_unbookmarked_single))
                                }
                                bookmarkToDelete = null
                            }
                        }
                    },
                    onDismiss = { bookmarkToDelete = null }
                )
            }

            if (uiState.pendingDelete.isNotEmpty()) {
                SmbDeleteConfirmDialog(pendingDelete = uiState.pendingDelete, onConfirm = { viewModel.deleteSelected(uiState.pendingDelete) }, onDismiss = { viewModel.updateState { it.copy(pendingDelete = emptyList()) } })
            }
        }

        // 复制/移动目录选择（SMB内）
        uiState.pendingOperation?.let { opType ->
            SmbDirPickerDialog(
                browser = viewModel.browser,
                host = host,
                shareName = shareName,
                initialPath = uiState.currentPath,
                onConfirm = { destDirSmbPath ->
                    viewModel.copyOrMoveWithinSmb(uiState.pendingEntries, destDirSmbPath, opType == FileOperationType.COPY)
                },
                onCancel = { viewModel.updateState { it.copy(pendingOperation = null) } },
            )
        }
    }
}
