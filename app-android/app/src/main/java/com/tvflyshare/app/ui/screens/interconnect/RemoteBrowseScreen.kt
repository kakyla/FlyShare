package com.tvflyshare.app.ui.screens.interconnect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import com.tvflyshare.app.uiconfig.components.TvDropdownMenu
import com.tvflyshare.app.uiconfig.components.TvDropdownMenuItem
import com.tvflyshare.app.uiconfig.components.TvCheckbox
import com.tvflyshare.app.uiconfig.components.TvTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.interconnect.RemoteFileEntry
import com.tvflyshare.app.ui.viewmodel.RemoteBrowseViewModel
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.components.TvSideMenu
import com.tvflyshare.app.uiconfig.components.TvMenuItem
import com.tvflyshare.app.uiconfig.components.TvMenuItemStyle
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.service.TransferForegroundService
import com.tvflyshare.app.filebrowser.model.SortMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.StoragePermissionUtils

// 互传没有服务器推送，停在本页且在前台时以轻量轮询（1 秒）拉取对方目录，实现近实时刷新。
// 1 秒是「看起来像实时」与「局域网请求开销可忽略」的平衡点；且只在 RESUMED 时运行（见下方生命周期门控）。
private const val REMOTE_AUTO_REFRESH_MS = 1000L

@Composable
fun RemoteBrowseScreen(
    targetIp: String,
    targetPort: Int,
    targetName: String,
    onBack: () -> Unit,
    viewModel: RemoteBrowseViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val windowInfo = rememberWindowAdaptiveInfo()
    val isCompact = windowInfo.widthClass == WindowWidthClass.COMPACT

    // 操作菜单（菜单键唤出，对标 SMB 管理器）
    var showActionMenu by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<RemoteFileEntry?>(null) }

    // 远端删除/重命名对话框状态
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 2026-10-04 审计 P2 修复：区分「长按菜单单删」与「多选批量删」——
    // 原先多选已有勾选时长按另一个文件选删除，实际执行的是删全部勾选项，误删风险
    var deleteViaMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFileEntry?>(null) }

    // 推送：发送本机文件对话框
    var showSendDialog by remember { mutableStateOf(false) }

    // 下载完成结果面板：让用户看到这次下载了哪些文件、存在哪里（不再只有一个 toast）
    var downloadResultNames by remember { mutableStateOf<List<String>?>(null) }
    var downloadResultFailed by remember { mutableStateOf(0) }
    val downloadDirPath = remember {
        runCatching { StoragePermissionUtils.getStorageDir(context).absolutePath }.getOrDefault("")
    }

    LaunchedEffect(targetIp, targetPort) {
        viewModel.initDevice(targetIp, targetPort, targetName)
    }

    // 跨设备近实时：互传没有推送，停在本页且在前台（RESUMED）时，每 1 秒轻量拉一次对方目录
    // （有变化才更新、不打断选中/滚动）。切后台/离开页面即停，避免无谓请求与耗电。
    val autoRefreshLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(autoRefreshLifecycleOwner, targetIp, targetPort) {
        var pollJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (pollJob == null) {
                        pollJob = coroutineScope.launch {
                            while (isActive) {
                                delay(REMOTE_AUTO_REFRESH_MS)
                                if (!viewModel.uiState.value.isDownloading) viewModel.silentRefresh()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    pollJob?.cancel()
                    pollJob = null
                }
                else -> {}
            }
        }
        autoRefreshLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            pollJob?.cancel()
            autoRefreshLifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val handleBack = {
        if (state.isMultiSelectMode) {
            viewModel.toggleMultiSelectMode()
        } else if (state.currentPath.isNotEmpty()) {
            viewModel.navigateUp()
        } else {
            onBack()
        }
    }

    // 处理离开页面时可能由于协程取消导致的下载状态没能重置的情况
    DisposableEffect(Unit) {
        // 进入对端浏览即登记互联服务持有者；同时续持 OWNER_DISCOVERY，避免从设备列表页
        // (DeviceListScreen) 进入本页时 DISCOVERY 被释放导致互联服务/已发现设备立即断开。
        // 与设备列表页共同构成"任一互联界面可见则服务存活、全部离开后 30s 自动关闭"的引用计数。
        TransferForegroundService.acquire(context, TransferForegroundService.OWNER_DISCOVERY)
        TransferForegroundService.acquire(context, TransferForegroundService.OWNER_REMOTE_BROWSE)
        onDispose {
            viewModel.resetDownloadState()
            TransferForegroundService.release(context, TransferForegroundService.OWNER_DISCOVERY)
            TransferForegroundService.release(context, TransferForegroundService.OWNER_REMOTE_BROWSE)
        }
    }

    BackHandler {
        if (state.isDownloading) {
            // 下载中屏蔽返回键，避免误关进度窗丢失下载
            coroutineScope.launch {
                GlobalToastManager.showToast(context.getString(R.string.remote_browse_downloading_hint))
            }
        } else {
            handleBack()
        }
    }

    TVBackground {
        ScreenScaffold(
            title = targetName + if (state.currentPath.isEmpty()) stringResource(R.string.remote_browse_title_suffix) else "${stringResource(R.string.remote_browse_title_suffix)}/${state.currentPath}",
            showBack = true,
            onBack = {
                // 2026-10-04 审计 P2 修复：下载中顶栏返回与系统返回键行为对齐（原先直通 handleBack
                // 直接退出本页丢弃下载，与 BackHandler 的拦截 Toast 不一致）
                if (state.isDownloading) {
                    coroutineScope.launch {
                        GlobalToastManager.showToast(context.getString(R.string.remote_browse_downloading_hint))
                    }
                } else {
                    handleBack()
                }
            },
            scrollable = false,
            applyBodyPadding = false,
        ) { _ ->

            // 批量操作栏：有可下载文件时显示「选择」入口；多选时显示 全选/批量下载/取消；
            // 右侧「更多」菜单（排序 / 新建文件夹）始终可用，对标 SMB 管理器（空目录亦可新建文件夹）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TvTheme.dimens.spacing3Xl, vertical = TvTheme.dimens.spacingSm),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 发送本机文件到对方（双向互传入口，始终可用）
                val sendInteraction = remember { MutableInteractionSource() }
                Text(
                    text = stringResource(id = R.string.remote_browse_btn_send),
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontSmall,
                    modifier = Modifier
                        .tvFocusStyle(
                            interactionSource = sendInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                            normalBgColor = TvTheme.colors.borderNormal,
                            focusedBgColor = TvTheme.colors.brandPrimary,
                            borderWidth = TvTheme.dimens.borderNormal
                        )
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = null
                        ) { showSendDialog = true }
                        .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                )
                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))

                val hasDownloadable = state.files.any { !it.isDirectory }
                if (state.isMultiSelectMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)) {
                        // 全选
                        val selectAllInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = if (state.selectedFiles.size == state.files.count { !it.isDirectory }) stringResource(id = R.string.transfer_unselect_all) else stringResource(id = R.string.action_select_all),
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontSmall,
                            modifier = Modifier
                                .tvFocusStyle(
                                    interactionSource = selectAllInteraction,
                                    shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                    normalBgColor = TvTheme.colors.borderNormal,
                                    focusedBgColor = TvTheme.colors.brandPrimary,
                                    borderWidth = TvTheme.dimens.borderNormal
                                )
                                .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                .clickable(
                                    interactionSource = selectAllInteraction,
                                    indication = null
                                ) { viewModel.selectAll() }
                                .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                        )
                        // 批量下载
                        val hasSelection = state.selectedFiles.isNotEmpty()
                        val batchDownloadInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = if (hasSelection) stringResource(id = R.string.interconnect_btn_download_count, state.selectedFiles.size) else stringResource(id = R.string.interconnect_btn_download),
                            color = if (!hasSelection) TvTheme.colors.textSecondary else TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontSmall,
                            modifier = Modifier
                                .tvFocusStyle(
                                    interactionSource = batchDownloadInteraction,
                                    shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                    normalBgColor = TvTheme.colors.borderNormal,
                                    focusedBgColor = TvTheme.colors.brandPrimary,
                                    borderWidth = TvTheme.dimens.borderNormal
                                )
                                .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                .clickable(
                                    enabled = hasSelection,
                                    interactionSource = batchDownloadInteraction,
                                    indication = null
                                ) { 
                                    viewModel.downloadSelectedFiles { names, failedCount ->
                                        downloadResultNames = names
                                        downloadResultFailed = failedCount
                                    }
                                }
                                .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                        )
                        // 批量删除（红字警示色，带确认对话框）
                        val batchDeleteInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = if (hasSelection) stringResource(id = R.string.remote_browse_action_delete_count, state.selectedFiles.size) else stringResource(id = R.string.remote_browse_action_delete),
                            color = if (!hasSelection) TvTheme.colors.textSecondary else TvTheme.colors.brandError,
                            fontSize = TvTheme.dimens.fontSmall,
                            modifier = Modifier
                                .tvFocusStyle(
                                    interactionSource = batchDeleteInteraction,
                                    shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                    normalBgColor = TvTheme.colors.borderNormal,
                                    focusedBgColor = TvTheme.colors.brandError,
                                    borderWidth = TvTheme.dimens.borderNormal
                                )
                                .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                .clickable(
                                    enabled = hasSelection,
                                    interactionSource = batchDeleteInteraction,
                                    indication = null
                                ) { showDeleteConfirm = true }
                                .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                        )
                        // 取消
                        val cancelInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = stringResource(id = R.string.btn_cancel),
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontSmall,
                            modifier = Modifier
                                .tvFocusStyle(
                                    interactionSource = cancelInteraction,
                                    shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                    normalBgColor = TvTheme.colors.borderNormal,
                                    focusedBgColor = TvTheme.colors.brandPrimary,
                                    borderWidth = TvTheme.dimens.borderNormal
                                )
                                .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                .clickable(
                                    interactionSource = cancelInteraction,
                                    indication = null
                                ) { viewModel.toggleMultiSelectMode() }
                                .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                        )
                    }
                } else if (hasDownloadable) {
                    // 选择按钮
                    val selectInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = stringResource(id = R.string.action_select_all),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = selectInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                normalBgColor = TvTheme.colors.borderNormal,
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderNormal
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .clickable(
                                interactionSource = selectInteraction,
                                indication = null
                            ) { viewModel.toggleMultiSelectMode() }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                    )
                }

                // 更多操作菜单（排序 / 新建文件夹），对标 SMB 管理器
                var showOptionsMenu by remember { mutableStateOf(false) }
                var showSortMenu by remember { mutableStateOf(false) }
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
                            borderWidth = TvTheme.dimens.borderFocused
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(id = R.string.smb_more_actions_desc),
                        tint = TvTheme.colors.textPrimary,
                        modifier = Modifier.size(TvTheme.dimens.iconSizeSm)
                    )
                    TvDropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false }
                    ) {
                        TvDropdownMenuItem(
                            text = stringResource(id = R.string.smb_sort),
                            onClick = { showOptionsMenu = false; showSortMenu = true }
                        )
                        TvDropdownMenuItem(
                            text = stringResource(id = R.string.smb_new_folder_title),
                            onClick = { showOptionsMenu = false; viewModel.setShowCreateFolderDialog(true) }
                        )
                        TvDropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortMode.entries.forEach { mode ->
                                TvDropdownMenuItem(
                                    text = if (state.sortMode == mode) "✓ ${stringResource(id = mode.titleResId)}" else "  ${stringResource(id = mode.titleResId)}",
                                    onClick = { viewModel.setSortMode(mode); showSortMenu = false }
                                )
                            }
                        }
                    }
                }
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TvTheme.colors.brandPrimary)
                }
            } else if (state.error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.error ?: stringResource(R.string.remote_browse_unknown_error),
                            color = TvTheme.colors.brandError,
                            fontSize = TvTheme.dimens.fontCol1Text
                        )
                        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                        val retryInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = stringResource(id = R.string.remote_browse_retry),
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontSmall,
                            modifier = Modifier
                                .tvFocusStyle(
                                    interactionSource = retryInteraction,
                                    shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                    normalBgColor = TvTheme.colors.borderNormal,
                                    focusedBgColor = TvTheme.colors.brandPrimary,
                                    borderWidth = TvTheme.dimens.borderNormal
                                )
                                .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                .clickable(
                                    interactionSource = retryInteraction,
                                    indication = null
                                ) { viewModel.loadPath(state.currentPath) }
                                .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                        )
                    }
                }
            } else if (state.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.remote_browse_empty_dir),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontCol1Text
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = TvTheme.dimens.spacing3Xl, vertical = TvTheme.dimens.spacingMd),
                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
                    ) {
                        items(state.files, key = { it.path }) { file ->
                            RemoteFileItem(
                                file = file,
                                isMultiSelectMode = state.isMultiSelectMode,
                                isSelected = state.selectedFiles.contains(file),
                                onToggleSelect = { viewModel.toggleSelection(file) },
                                onLongClick = {
                                    actionTarget = file
                                    showActionMenu = true
                                },
                                onClick = {
                                    if (state.isMultiSelectMode) {
                                        if (!file.isDirectory) {
                                            viewModel.toggleSelection(file)
                                        }
                                    } else {
                                        if (file.isDirectory) {
                                            viewModel.loadPath(file.path)
                                        } else {
                                            viewModel.downloadFile(file) { success, msg ->
                                                if (success) {
                                                    downloadResultNames = listOf(file.name)
                                                    downloadResultFailed = 0
                                                } else {
                                                    coroutineScope.launch {
                                                        GlobalToastManager.showToast(context.getString(R.string.remote_browse_download_failed, msg))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // 操作菜单：菜单键(Key.Menu)或对焦项长按唤出，操作逻辑对齐 SMB 管理器
                    // 竖屏：底部全宽浮层；宽屏：右侧固定宽浮层
                    Column(
                        modifier = if (isCompact) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            else Modifier.align(Alignment.CenterEnd)
                    ) {
                        AnimatedVisibility(
                            visible = showActionMenu && actionTarget != null
                        ) {
                            actionTarget?.let { target ->
                            // 菜单文案需在组合作用域预取：buildList 内部非组合上下文，不能直接调 stringResource
                            val installLabel = stringResource(id = R.string.smb_action_install)
                            val downloadLabel = stringResource(id = R.string.smb_action_download)
                            val selectLabel = stringResource(id = R.string.remote_browse_action_select)
                            val renameLabel = stringResource(id = R.string.remote_browse_action_rename)
                            val deleteLabel = stringResource(id = R.string.remote_browse_action_delete)
                            TvSideMenu(
                                title = target.name,
                                subtitle = "",
                                modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(TvTheme.dimens.spacing3Xl * 4),
                                onClose = { showActionMenu = false; actionTarget = null },
                                items = buildList {
                                    val isApk = target.name.substringAfterLast('.', "").equals("apk", ignoreCase = true)
                                    if (isApk) {
                                        add(TvMenuItem(
                                            label = installLabel,
                                            icon = Icons.Default.CheckCircle,
                                            style = TvMenuItemStyle.SUCCESS,
                                        action = {
                                            showActionMenu = false; actionTarget = null
                                            viewModel.installApk(target) { success, msg ->
                                                    coroutineScope.launch {
                                                        GlobalToastManager.showToast(
                                                            context.getString(
                                                                if (success) R.string.remote_browse_download_success else R.string.remote_browse_download_failed,
                                                                if (success) target.name else (msg ?: "")
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        ))
                                    }
                                    add(TvMenuItem(
                                        label = downloadLabel,
                                        icon = Icons.Default.Download,
                                        style = TvMenuItemStyle.DEFAULT,
                                        action = {
                                            showActionMenu = false; actionTarget = null
                                            viewModel.downloadFile(target) { success, msg ->
                                                if (success) {
                                                    downloadResultNames = listOf(target.name)
                                                    downloadResultFailed = 0
                                                } else {
                                                    coroutineScope.launch {
                                                        GlobalToastManager.showToast(context.getString(R.string.remote_browse_download_failed, msg ?: ""))
                                                    }
                                                }
                                            }
                                        }
                                    ))
                                    add(TvMenuItem(
                                        label = selectLabel,
                                        icon = Icons.Default.Add,
                                        style = TvMenuItemStyle.DEFAULT,
                                        action = {
                                            showActionMenu = false; actionTarget = null
                                            if (!state.isMultiSelectMode) viewModel.toggleMultiSelectMode()
                                            viewModel.toggleSelection(target)
                                        }
                                    ))
                                    add(TvMenuItem(
                                        label = renameLabel,
                                        icon = Icons.Default.Edit,
                                        style = TvMenuItemStyle.DEFAULT,
                                        action = {
                                            showActionMenu = false
                                            // 2026-10-10 审计修复：清残留 actionTarget（对齐 install/download 分支），
                                            // 避免状态残留被后续删除确认误用
                                            actionTarget = null
                                            renameTarget = target
                                            showRenameDialog = true
                                        }
                                    ))
                                    add(TvMenuItem(
                                        label = deleteLabel,
                                        icon = Icons.Default.Delete,
                                        action = {
                                            showActionMenu = false
                                            renameTarget = null
                                            actionTarget = target
                                            deleteViaMenu = true
                                            showDeleteConfirm = true
                                        }
                                    ))
                                }
                            )
                        }
                    }
                }
            }
        }
        }

        // 推送：本机文件 -> 对端当前浏览目录
        if (showSendDialog) {
            SendFilesDialog(
                remoteDir = state.currentPath,
                onDismiss = { showSendDialog = false },
                viewModel = viewModel
            )
        }

        // 远端删除确认（单项来自操作菜单，批量来自多选栏）
        if (showDeleteConfirm) {
            val singleTarget = if (deleteViaMenu || state.selectedFiles.isEmpty()) actionTarget else null
            TvConfirmDialog(
                title = stringResource(id = R.string.remote_browse_delete_confirm_title),
                message = if (singleTarget != null) {
                    stringResource(id = R.string.remote_browse_delete_confirm_text, singleTarget.name)
                } else {
                    stringResource(id = R.string.remote_browse_delete_confirm_text_batch, state.selectedFiles.size)
                },
                isDanger = true,
                onConfirm = {
                    showDeleteConfirm = false
                    deleteViaMenu = false
                    val menuTarget = singleTarget
                    if (menuTarget != null) {
                        val deletedName = menuTarget.name
                        viewModel.deleteFile(menuTarget) { success, msg ->
                            coroutineScope.launch {
                                GlobalToastManager.showToast(
                                    context.getString(
                                        // 2026-10-04 审计 P2 修复：删除失败原先误用"下载失败"文案（复制粘贴错误）
                                        if (success) R.string.remote_browse_delete_success else R.string.remote_browse_delete_failed,
                                        if (success) deletedName else (msg ?: "")
                                    )
                                )
                            }
                        }
                    } else {
                        viewModel.deleteSelectedFiles { ok, fail ->
                            coroutineScope.launch {
                                GlobalToastManager.showToast(context.getString(R.string.remote_browse_delete_result, ok, fail))
                            }
                        }
                    }
                    actionTarget = null
                },
                onDismiss = {
                    showDeleteConfirm = false
                    deleteViaMenu = false
                    actionTarget = null
                }
            )
        }

        // 远端重命名对话框
        if (showRenameDialog) {
            renameTarget?.let { target ->
                var newName by remember(target.path) { mutableStateOf(target.name) }
                TvAlertDialog(
                    title = stringResource(id = R.string.remote_browse_rename_title),
                    confirmText = stringResource(id = R.string.smb_btn_confirm),
                    dismissText = stringResource(id = R.string.smb_btn_cancel),
                    onConfirm = {
                        showRenameDialog = false
                        viewModel.renameFile(target, newName) { success, result ->
                            coroutineScope.launch {
                                GlobalToastManager.showToast(
                                    context.getString(
                                        if (success) R.string.remote_browse_rename_success else R.string.remote_browse_error_conn,
                                        result ?: ""
                                    )
                                )
                            }
                        }
                        renameTarget = null
                    },
                    onDismiss = {
                        showRenameDialog = false
                        renameTarget = null
                    },
                    content = {
                        TvTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(stringResource(id = R.string.remote_browse_action_rename)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }
        }

        if (state.isDownloading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvTheme.colors.dialogScrim),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = TvTheme.dimens.dialogWidthMd)
                        .padding(horizontal = TvTheme.dimens.spacingLg)
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                        .background(TvTheme.colors.dialogBg)
                        .padding(TvTheme.dimens.spacingXl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.remote_browse_downloading_to_local),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontCol1Text,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
                    Text(
                        text = state.downloadingFileName,
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = TvTheme.dimens.spacingMd)
                    )
                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                    LinearProgressIndicator(
                        progress = { state.downloadProgress },
                        color = TvTheme.colors.brandPrimary,
                        trackColor = TvTheme.colors.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                    Text(
                        text = if (state.downloadSpeedBytesPerSec > 0) {
                            "${(state.downloadProgress * 100).toInt()}%  •  ${FileVisualUtils.formatFileSize(state.downloadSpeedBytesPerSec)}/s"
                        } else {
                            "${(state.downloadProgress * 100).toInt()}%"
                        },
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontSmall
                    )
                }
            }
        }

        // 新建文件夹对话框（远端共享目录）
        if (state.showCreateFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            TvAlertDialog(
                title = stringResource(id = R.string.smb_new_folder_title),
                onDismiss = { viewModel.setShowCreateFolderDialog(false) },
                confirmText = stringResource(id = R.string.smb_btn_confirm),
                dismissText = stringResource(id = R.string.smb_btn_cancel),
                onConfirm = {
                    viewModel.createFolder(folderName) { success, msg ->
                        coroutineScope.launch {
                            GlobalToastManager.showToast(
                                if (success) context.getString(R.string.smb_msg_folder_created, folderName)
                                else context.getString(R.string.remote_browse_error_conn, msg ?: "")
                            )
                        }
                    }
                },
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

        // 下载完成结果面板：列出这次下载的文件名与保存目录，不再只有一个 toast
        val downloadedNames = downloadResultNames
        if (downloadedNames != null) {
            TvAlertDialog(
                title = stringResource(id = R.string.remote_browse_download_done_title),
                dismissText = null,
                onConfirm = { downloadResultNames = null },
                onDismiss = { downloadResultNames = null },
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.remote_browse_download_result, downloadedNames.size, downloadResultFailed),
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontCol1Text
                        )
                        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                        downloadedNames.take(8).forEach { name ->
                            Text(
                                text = "• $name",
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (downloadedNames.size > 8) {
                            Text(
                                text = stringResource(R.string.remote_browse_download_done_more, downloadedNames.size - 8),
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontSmall
                            )
                        }
                        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                        if (downloadDirPath.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.remote_browse_download_saved_to, downloadDirPath),
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontCaption,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun RemoteFileItem(
    file: RemoteFileEntry,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    // 视觉样式对齐 SMB 管理器：按文件类型取图标/着色，统一格式化大小
    val fileVisual = FileVisualUtils.getFileVisual(file.name, file.isDirectory)
    val dateFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val dateStr = remember(file.lastModified) { dateFormatter.format(Date(file.lastModified)) }
    val sizeStr = remember(file.size) { FileVisualUtils.formatFileSize(file.size) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.listItemHeight)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused,
                focusedBorderColor = TvTheme.colors.brandPrimary
            )
            .onKeyEvent { event: KeyEvent ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onLongClick()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = TvTheme.dimens.spacingLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode && !file.isDirectory) {
            TvCheckbox(
                checked = isSelected,
                size = TvTheme.dimens.iconSizeMd,
                iconSize = TvTheme.dimens.iconSizeSm,
                shapeType = "circle"
            )
            Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
        }

        Icon(
            imageVector = fileVisual.icon,
            contentDescription = null,
            tint = fileVisual.tint,
            modifier = Modifier.size(TvTheme.dimens.iconSizeXl)
        )
        
        Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (file.isDirectory) {
                    stringResource(id = R.string.smb_type_dir_label)
                } else {
                    "$sizeStr  •  $dateStr"
                },
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontCaption,
                maxLines = 1
            )
        }
    }
}