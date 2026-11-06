package com.tvflyshare.app.ui.screens.network

import android.util.Log
import android.app.Application
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Android
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.components.TvDropdownMenu
import com.tvflyshare.app.uiconfig.components.TvDropdownMenuItem
import com.tvflyshare.app.ftp.FtpConnectionState
import com.tvflyshare.app.ftp.FtpFileEntry
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.components.TvEmptyState
import com.tvflyshare.app.uiconfig.components.TvIconButton
import com.tvflyshare.app.uiconfig.components.TvMenuItem
import com.tvflyshare.app.uiconfig.components.TvMenuItemStyle
import com.tvflyshare.app.uiconfig.components.TvSideMenu
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.components.TvTextField
import com.tvflyshare.app.filebrowser.model.SortMode
import com.tvflyshare.app.ui.screens.network.components.FtpFileItemGrid
import com.tvflyshare.app.ui.viewmodel.FtpAction
import com.tvflyshare.app.ui.viewmodel.FtpBrowseViewModel
import com.tvflyshare.app.ui.viewmodel.FtpUiEvent
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FtpBrowseScreen(
    host: String,
    port: Int,
    username: String,
    password: String,
    anonymous: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    val viewModel: FtpBrowseViewModel = viewModel(
        factory = FtpBrowseViewModel.Factory(application, host, port, username, password, anonymous)
    )

    val uiState by viewModel.uiState.collectAsState()
    val connState by viewModel.connState.collectAsState()

    val windowInfo = rememberWindowAdaptiveInfo()
    val isCompact = windowInfo.widthClass == WindowWidthClass.COMPACT

    val retryFocusRequester = remember { FocusRequester() }

    // 连接失败 / 加载失败态出现时，自动把焦点请求到「重试」按钮，
    // 避免电视端遥控在界面上乱跑多次才落到重试（见测试反馈 Bug 4）。
    LaunchedEffect(uiState.loadError, connState) {
        if (uiState.loadError != null || connState is FtpConnectionState.Error) {
            try {
                retryFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                Log.w("FtpBrowseScreen", "requestFocus 跳过：焦点节点尚未挂载", e)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initConnection()
        viewModel.uiEvent.collect { event ->
            when (event) {
                is FtpUiEvent.NavigateBack -> onBack()
                is FtpUiEvent.OpenFile -> {
                    val canOpen = FileActionUtils.canOpenFile(context, event.file)
                    if (!canOpen) {
                        viewModel.showToast(context.getString(R.string.smb_msg_download_no_app))
                    } else {
                        val intent = FileActionUtils.buildOpenFileIntent(context, event.file)
                        if (intent != null) {
                            val tried = runCatching { context.startActivity(intent); true }.getOrDefault(false)
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

    androidx.activity.compose.BackHandler {
        when {
            uiState.showCreateFolderDialog -> viewModel.onEvent(FtpUiEvent.ShowCreateFolderDialog(false))
            uiState.isMultiSelectMode -> viewModel.updateState { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
            uiState.showActionMenu -> viewModel.updateState { it.copy(showActionMenu = false, selectedEntry = null) }
            uiState.renameTarget != null -> viewModel.updateState { it.copy(renameTarget = null) }
            else -> viewModel.handleBack()
        }
    }

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.ftp_title_browse),
            showBack = true,
            onBack = { viewModel.handleBack() },
            deviceInfo = "$host:$port",
            scrollable = false,
            applyBodyPadding = false,
        ) { _ ->

            when {
                connState is FtpConnectionState.Connecting || uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            CircularProgressIndicator(color = TvTheme.colors.textPrimary)
                            Text(
                                text = if (connState is FtpConnectionState.Connecting) stringResource(id = R.string.ftp_status_connecting) else stringResource(id = R.string.ftp_status_loading),
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontCaption,
                            )
                        }
                    }
                }

                connState is FtpConnectionState.Error -> {
                    val errorMsg = (connState as FtpConnectionState.Error).getMessage(LocalContext.current)
                    TvEmptyState(
                        title = stringResource(id = R.string.ftp_status_connect_failed),
                        subtitle = errorMsg,
                        icon = Icons.Default.CloudOff,
                        actionText = stringResource(id = R.string.ftp_btn_retry),
                        focusRequester = retryFocusRequester,
                        onActionClick = { viewModel.retry() }
                    )
                }

                uiState.downloadingFile != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                            modifier = Modifier.padding(horizontal = TvTheme.dimens.spacing2Xl),
                        ) {
                            Text(
                                text = stringResource(id = R.string.ftp_status_downloading, uiState.downloadingFile?.name ?: ""),
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
                            Text(text = "${uiState.downloadPercent}%", color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontSmall)
                        }
                    }
                }

                else -> {
                    val widePanelVisible = !isCompact && (
                        (!uiState.isMultiSelectMode && uiState.selectedEntry != null && uiState.showActionMenu) ||
                        (uiState.isMultiSelectMode && uiState.pendingDelete.isEmpty())
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isCompact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing2Xl)
                            .padding(top = TvTheme.dimens.spacingSm, bottom = TvTheme.dimens.spacingMd),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (widePanelVisible) Modifier.padding(end = TvTheme.dimens.spacing3Xl * 4 + TvTheme.dimens.spacingLg) else Modifier)
                        ) {
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
                                    text = " ${uiState.currentPath}",
                                    color = TvTheme.colors.textSecondary,
                                    fontSize = TvTheme.dimens.fontSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = TvTheme.dimens.spacingXs).weight(1f),
                                )
                                if (uiState.isMultiSelectMode) {
                                    Text(
                                        text = stringResource(id = R.string.smb_items_selected, uiState.selectedPaths.size),
                                        color = TvTheme.colors.brandPrimary,
                                        fontSize = TvTheme.dimens.fontSmall,
                                    )
                                } else {
                                    // 视图切换：列表 / 网格（与 SMB 浏览对齐）
                                    TvIconButton(
                                        icon = Icons.AutoMirrored.Filled.List,
                                        contentDescription = stringResource(id = R.string.smb_view_toggle_desc),
                                        onClick = { viewModel.onEvent(FtpUiEvent.ToggleGridView) }
                                    )
                                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                                    TvIconButton(
                                        icon = Icons.Default.MoreVert,
                                        contentDescription = stringResource(id = R.string.smb_more_actions_desc),
                                        onClick = { showOptionsMenu = true }
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
                                                onClick = { showOptionsMenu = false; viewModel.onEvent(FtpUiEvent.ShowCreateFolderDialog(true)) }
                                            )
                                            TvDropdownMenuItem(
                                                text = if (uiState.showHiddenFiles) "✓ " + stringResource(id = R.string.smb_show_hidden) else stringResource(id = R.string.smb_show_hidden),
                                                onClick = { showOptionsMenu = false; viewModel.onEvent(FtpUiEvent.ToggleHiddenFiles) }
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
                                                    onClick = { viewModel.onEvent(FtpUiEvent.SetSortMode(mode)); showSortMenu = false }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (uiState.loadError != null) {
                                TvEmptyState(
                                    title = stringResource(id = R.string.ftp_load_failed),
                                    subtitle = uiState.loadError,
                                    icon = Icons.Default.CloudOff,
                                    actionText = stringResource(id = R.string.ftp_btn_retry),
                                    focusRequester = retryFocusRequester,
                                    onActionClick = { viewModel.retry() }
                                )
                            } else if (uiState.displayList.isEmpty() && !uiState.isLoading) {
                                TvEmptyState(
                                    title = stringResource(id = R.string.ftp_empty_dir_title),
                                    subtitle = stringResource(id = R.string.ftp_empty_dir_subtitle),
                                    icon = Icons.Default.FolderOff
                                )
                            } else {
                                if (uiState.isGridView) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(if (isCompact) 2 else 3),
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                    ) {
                                        if (uiState.currentPath != "/" && !uiState.isMultiSelectMode) {
                                            item(key = "__parent__", span = { GridItemSpan(if (isCompact) 2 else 3) }) {
                                                ParentDirItem(onClick = { viewModel.handleBack() })
                                            }
                                        }
                                        items(
                                            count = uiState.displayList.size,
                                            key = { index -> uiState.displayList[index].path }
                                        ) { index ->
                                            val entry = uiState.displayList[index]
                                            FtpFileItemGrid(
                                                entry = entry,
                                                isSelected = uiState.selectedPaths.contains(entry.path),
                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                onClick = {
                                                    if (uiState.isMultiSelectMode) {
                                                        val newSelected = uiState.selectedPaths.toMutableSet()
                                                        if (newSelected.contains(entry.path)) newSelected.remove(entry.path) else newSelected.add(entry.path)
                                                        viewModel.updateState { it.copy(selectedPaths = newSelected) }
                                                    } else if (entry.isDirectory) {
                                                        viewModel.loadDirectory(entry.path)
                                                    } else {
                                                        viewModel.updateState { it.copy(selectedEntry = entry, showActionMenu = true) }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!uiState.isMultiSelectMode) {
                                                        viewModel.updateState { it.copy(isMultiSelectMode = true, selectedPaths = setOf(entry.path)) }
                                                    }
                                                },
                                                modifier = Modifier.onFocusChanged { if (it.isFocused) viewModel.updateState { it.copy(showActionMenu = false) } }
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                                    ) {
                                        if (uiState.currentPath != "/" && !uiState.isMultiSelectMode) {
                                            item(key = "__parent__") {
                                                ParentDirItem(onClick = { viewModel.handleBack() })
                                            }
                                        }
                                        items(uiState.displayList, key = { it.path }) { entry ->
                                            FtpFileItemRow(
                                                entry = entry,
                                                isSelected = uiState.selectedPaths.contains(entry.path),
                                                isMultiSelectMode = uiState.isMultiSelectMode,
                                                onClick = {
                                                    if (uiState.isMultiSelectMode) {
                                                        val newSelected = uiState.selectedPaths.toMutableSet()
                                                        if (newSelected.contains(entry.path)) newSelected.remove(entry.path) else newSelected.add(entry.path)
                                                        viewModel.updateState { it.copy(selectedPaths = newSelected) }
                                                    } else if (entry.isDirectory) {
                                                        viewModel.loadDirectory(entry.path)
                                                    } else {
                                                        viewModel.updateState { it.copy(selectedEntry = entry, showActionMenu = true) }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!uiState.isMultiSelectMode) {
                                                        viewModel.updateState { it.copy(isMultiSelectMode = true, selectedPaths = setOf(entry.path)) }
                                                    }
                                                },
                                                modifier = Modifier.onFocusChanged { if (it.isFocused) viewModel.updateState { it.copy(showActionMenu = false) } }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 右侧：单文件操作面板
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !uiState.isMultiSelectMode && uiState.selectedEntry != null && uiState.showActionMenu,
                            modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg),
                        ) {
                            uiState.selectedEntry?.let { entry ->
                                val items = buildList {
                                    add(TvMenuItem(stringResource(id = R.string.smb_action_open), Icons.Default.PlayCircle, TvMenuItemStyle.DEFAULT) {
                                        viewModel.onEvent(FtpUiEvent.HandleEntryAction(entry, FtpAction.OPEN))
                                    })
                                    if (entry.fileType.name == "APK") {
                                        add(TvMenuItem(stringResource(id = R.string.smb_action_install), Icons.Default.Android, TvMenuItemStyle.DEFAULT) {
                                            viewModel.onEvent(FtpUiEvent.HandleEntryAction(entry, FtpAction.INSTALL))
                                        })
                                    }
                                    add(TvMenuItem(stringResource(id = R.string.smb_action_download), Icons.Default.Download, TvMenuItemStyle.DEFAULT) {
                                        viewModel.onEvent(FtpUiEvent.HandleEntryAction(entry, FtpAction.DOWNLOAD))
                                    })
                                    add(TvMenuItem(stringResource(id = R.string.smb_action_rename), Icons.Default.Edit, TvMenuItemStyle.DEFAULT) {
                                        viewModel.onEvent(FtpUiEvent.HandleEntryAction(entry, FtpAction.RENAME))
                                    })
                                    add(TvMenuItem(stringResource(id = R.string.smb_action_details), Icons.Default.Info, TvMenuItemStyle.DEFAULT) {
                                        viewModel.onEvent(FtpUiEvent.HandleEntryAction(entry, FtpAction.DETAILS))
                                    })
                                    add(TvMenuItem(stringResource(id = R.string.smb_action_delete), Icons.Default.Delete, TvMenuItemStyle.DEFAULT) {
                                        viewModel.onEvent(FtpUiEvent.HandleEntryAction(entry, FtpAction.DELETE))
                                    })
                                }
                                TvSideMenu(
                                    title = entry.name,
                                    subtitle = FileVisualUtils.formatFileSize(entry.size),
                                    items = items,
                                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4),
                                    onClose = { viewModel.updateState { it.copy(showActionMenu = false, selectedEntry = null) } }
                                )
                            }
                        }

                        // 右侧：多选操作面板
                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.isMultiSelectMode && uiState.pendingDelete.isEmpty(),
                            modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                else Modifier.align(Alignment.CenterEnd).padding(start = TvTheme.dimens.spacingLg),
                        ) {
                            val items = buildList {
                                add(TvMenuItem(stringResource(id = R.string.file_manager_action_select_all), Icons.Default.Description, TvMenuItemStyle.DEFAULT, enabled = uiState.selectedPaths.size < uiState.fileList.size) {
                                    viewModel.onEvent(FtpUiEvent.SelectAll)
                                })
                                add(TvMenuItem(stringResource(id = R.string.smb_action_download), Icons.Default.Download, TvMenuItemStyle.DEFAULT, enabled = uiState.selectedPaths.isNotEmpty()) {
                                    val entries = uiState.fileList.filter { uiState.selectedPaths.contains(it.path) }
                                    viewModel.onEvent(FtpUiEvent.RequestDownload(entries))
                                })
                                add(TvMenuItem(stringResource(id = R.string.smb_action_delete), Icons.Default.Delete, TvMenuItemStyle.DEFAULT, enabled = uiState.selectedPaths.isNotEmpty()) {
                                    val entries = uiState.fileList.filter { uiState.selectedPaths.contains(it.path) }
                                    viewModel.updateState { it.copy(pendingDelete = entries) }
                                })
                                add(TvMenuItem(stringResource(id = R.string.file_manager_action_cancel), Icons.Default.ArrowUpward, TvMenuItemStyle.DEFAULT) {
                                    viewModel.updateState { it.copy(isMultiSelectMode = false, selectedPaths = emptySet()) }
                                })
                            }
                            TvSideMenu(
                                title = stringResource(id = R.string.smb_multi_select_title),
                                subtitle = stringResource(id = R.string.smb_items_selected, uiState.selectedPaths.size),
                                items = items,
                                modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4),
                            )
                        }
                    }
                }
            }

            // 新建文件夹对话框
            if (uiState.showCreateFolderDialog) {
                var folderName by remember { mutableStateOf("") }
                TvAlertDialog(
                    title = stringResource(id = R.string.smb_new_folder_title),
                    onDismiss = { viewModel.onEvent(FtpUiEvent.ShowCreateFolderDialog(false)) },
                    confirmText = stringResource(id = R.string.smb_btn_confirm),
                    dismissText = stringResource(id = R.string.smb_btn_cancel),
                    onConfirm = { viewModel.onEvent(FtpUiEvent.ConfirmCreateFolder(folderName)) },
                    content = {
                        TvTextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            // 重命名对话框
            uiState.renameTarget?.let { target ->
                var newName by remember(target.path) { mutableStateOf(target.name) }
                TvAlertDialog(
                    title = stringResource(id = R.string.smb_action_rename),
                    onDismiss = { viewModel.updateState { it.copy(renameTarget = null) } },
                    confirmText = stringResource(id = R.string.smb_btn_confirm),
                    dismissText = stringResource(id = R.string.smb_btn_cancel),
                    onConfirm = { viewModel.onEvent(FtpUiEvent.ConfirmRename(newName)) },
                    content = {
                        TvTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            // 文件详情对话框
            uiState.detailsTarget?.let { target ->
                TvAlertDialog(
                    title = stringResource(id = R.string.smb_details_title),
                    content = {
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                        Column(verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)) {
                            Text(text = stringResource(id = R.string.smb_details_name, target.name), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                            Text(text = stringResource(id = R.string.smb_details_size, if (target.isDirectory) stringResource(id = R.string.smb_details_type_dir) else FileVisualUtils.formatFileSize(target.size)), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                            Text(text = stringResource(id = R.string.smb_details_modified, dateFormat.format(Date(target.lastModified))), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                            Text(text = stringResource(id = R.string.smb_details_path, host, port.toString(), target.path), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                        }
                    },
                    confirmText = stringResource(id = R.string.smb_btn_close),
                    dismissText = null,
                    onConfirm = { viewModel.updateState { it.copy(detailsTarget = null) } },
                    onDismiss = { viewModel.updateState { it.copy(detailsTarget = null) } }
                )
            }

            // 删除确认弹窗
            if (uiState.pendingDelete.isNotEmpty()) {
                val toDelete = uiState.pendingDelete
                val isMulti = toDelete.size > 1
                TvConfirmDialog(
                    title = if (isMulti) stringResource(id = R.string.smb_delete_multi_title, toDelete.size) else stringResource(id = R.string.smb_delete_single_title),
                    message = if (isMulti) toDelete.joinToString("\n") { "• ${it.name}" } else stringResource(id = R.string.smb_delete_msg, toDelete.firstOrNull()?.name ?: ""),
                    isDanger = true,
                    onConfirm = { viewModel.onEvent(FtpUiEvent.ConfirmDelete(toDelete)) },
                    onDismiss = { viewModel.updateState { it.copy(pendingDelete = emptyList()) } }
                )
            }
        }
    }
}

/**
 * 文件列表行（FTP）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FtpFileItemRow(
    entry: FtpFileEntry,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val visual = FileVisualUtils.getFileVisual(entry.name, entry.isDirectory)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                focusedBorderColor = TvTheme.colors.brandPrimary,
                normalBorderColor = if (isSelected) TvTheme.colors.brandPrimary else TvTheme.colors.borderNormal,
                borderWidth = if (isSelected) TvTheme.dimens.borderFocused else TvTheme.dimens.borderNormal,
            )
            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.tint,
            modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = if (isFocused) TvTheme.colors.textPrimary else TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol1Text,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory) {
                Text(
                    text = FileVisualUtils.formatFileSize(entry.size),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = TvTheme.colors.brandPrimary,
                modifier = Modifier.size(TvTheme.dimens.iconSizeSm),
            )
        }
    }
}

/**
 * 返回上级目录行
 */
@Composable
private fun ParentDirItem(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                focusedBorderColor = TvTheme.colors.brandPrimary,
                normalBorderColor = TvTheme.colors.borderNormal,
                borderWidth = TvTheme.dimens.borderNormal,
            )
            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
    ) {
        Icon(
            imageVector = Icons.Default.ArrowUpward,
            contentDescription = null,
            tint = TvTheme.colors.textSecondary,
            modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
        )
        Text(
            text = "..",
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCol1Text,
            fontWeight = FontWeight.Medium,
        )
    }
}
