package com.tvflyshare.app.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.R
import com.tvflyshare.app.httpserver.HttpServerManager
import com.tvflyshare.app.network.NetworkType
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TransferFileItemRow
import com.tvflyshare.app.uiconfig.components.TransferFileUploadingRow
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.ui.viewmodel.TransferFileItem
import com.tvflyshare.app.ui.viewmodel.UploadingTransferItem
import com.tvflyshare.app.ui.viewmodel.TransferUiEvent
import com.tvflyshare.app.ui.viewmodel.TransferUiState
import com.tvflyshare.app.ui.viewmodel.TransferViewModel
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun TransferScreen(
    onBack: () -> Unit,
    viewModel: TransferViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 用于控制列表滚动
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 监听 ViewModel 发出的新文件事件
    LaunchedEffect(viewModel) {
        viewModel.newFileEvent.collect { newCount: Int ->
            val isFirstItemVisible = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val isListEmpty = uiState.fileList.isEmpty()

            if (isListEmpty || isFirstItemVisible) {
                // 如果列表为空，或焦点正好在最顶部，则自动滚动到顶部显示新文件
                listState.animateScrollToItem(0)
            } else {
                // 否则（用户正在浏览下方内容），累加新文件数量并显示悬浮气泡
                viewModel.onEvent(TransferUiEvent.IncrementNewFileCount(newCount))
            }
        }
    }

    // 监听列表滚动状态：当用户手动滚动回最顶部时，清除提示气泡
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
            if (uiState.newFileCount > 0) {
                viewModel.onEvent(TransferUiEvent.ClearNewFileIndicator)
            }
        }
    }

    // 上传条目从列表顶部消失（传完成入库）时，LazyColumn 保留像素级滚动偏移，
    // 会让紧随其后的第一个文件行只露出半截。若用户正看着顶部（首可见项位于上传条目区内），
    // 将列表对齐回顶部；用户浏览列表深处时不动（LazyColumn 按条目 key 锚定，深处不受影响）。
    val prevUploadingCount = remember { mutableStateOf(0) }
    LaunchedEffect(uiState.uploadingFiles.size) {
        val prev = prevUploadingCount.value
        val cur = uiState.uploadingFiles.size
        if (cur < prev && listState.firstVisibleItemIndex < prev) {
            listState.scrollToItem(0)
        }
        prevUploadingCount.value = cur
    }

    // Composable 进入时启动数据加载/轮询，离开时立即取消。
    // 不能依赖 ViewModel.onCleared——它调用时机晚于 Composable 离开，
    // 会导致旧轮询协程继续运行，阻塞新的 HTTP 请求。
    DisposableEffect(viewModel) {
        viewModel.onEvent(TransferUiEvent.Start)
        onDispose { viewModel.onEvent(TransferUiEvent.Stop) }
    }

    TVBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // TopBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvTheme.dimens.topBarHeight)
                        .padding(horizontal = TvTheme.dimens.spacing2Xl),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(id = R.string.back),
                        tint = TvTheme.colors.textPrimary,
                        modifier = Modifier
                            .size(TvTheme.dimens.iconSizeLg)
                            .clickable {
                                if (uiState.currentPath.isNotEmpty()) {
                                    viewModel.onEvent(TransferUiEvent.NavigateUp)
                                } else {
                                    onBack()
                                }
                            }
                    )
                    Text(
                        text = stringResource(id = R.string.transfer_quick_transfer),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontTitle,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = TvTheme.dimens.spacingSm)
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))
                    Text(
                        text = uiState.ipAddress?.let { stringResource(id = R.string.transfer_address, it) }
                            ?: stringResource(id = R.string.transfer_getting_network),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontCol2Title,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = when (uiState.networkType) {
                            NetworkType.WIFI -> Icons.Default.Wifi
                            NetworkType.ETHERNET -> Icons.Default.SettingsEthernet
                            else -> Icons.Default.WifiOff
                        },
                        contentDescription = when (uiState.networkType) {
                            NetworkType.WIFI -> "WiFi"
                            NetworkType.ETHERNET -> stringResource(id = R.string.transfer_network_ethernet)
                            else -> stringResource(id = R.string.transfer_network_none)
                        },
                        tint = TvTheme.colors.textPrimary,
                        modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
                    )
                }

                // 内容区：宽屏（TV/手机横屏/平板横屏）左右分栏；竖屏上下堆叠
                val windowInfo = rememberWindowAdaptiveInfo()
                if (windowInfo.isWideLayout) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = TvTheme.dimens.spacing2Xl),
                            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacing2Xl),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ===== 左栏：二维码 + 提示 =====
                            TransferQrPanel(
                                uiState = uiState,
                                compact = false,
                                weightModifier = Modifier.weight(1f),
                            )
                            // ===== 右栏：文件列表 =====
                            TransferFileListPanel(
                                uiState = uiState,
                                viewModel = viewModel,
                                context = context,
                                coroutineScope = coroutineScope,
                                listState = listState,
                                weightModifier = Modifier.weight(1.2f),
                            )
                        }
                    }
                } else {
                    // 竖屏（手机/车机竖屏）：上下堆叠，高度让给文件列表；
                    // 二维码区转紧凑横排（二维码左、标题/地址右），高度从约 320dp 压到约 150dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = TvTheme.dimens.spacingXl),
                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg)
                    ) {
                        TransferQrPanel(
                            uiState = uiState,
                            compact = true,
                            weightModifier = Modifier.fillMaxWidth(),
                        )
                        TransferFileListPanel(
                            uiState = uiState,
                            viewModel = viewModel,
                            context = context,
                            coroutineScope = coroutineScope,
                            listState = listState,
                            weightModifier = Modifier.weight(1f),
                        )
                    }
                }
            } // Close 外层 Column(line 135)

            // 悬浮的新文件提示气泡
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = uiState.newFileCount > 0,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = TvTheme.dimens.spacingMd)
                ) {
                    val indicatorInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = indicatorInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusBtn),
                                normalBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.9f),
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderFocused
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusBtn))
                            .clickable(
                                interactionSource = indicatorInteraction,
                                indication = null
                            ) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                    viewModel.onEvent(TransferUiEvent.ClearNewFileIndicator)
                                }
                            }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm)
                    ) {
                        Text(
                            text = stringResource(id = R.string.transfer_new_files_toast, uiState.newFileCount),
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 删除确认弹窗
            if (uiState.fileToDelete != null) {
                TvConfirmDialog(
                    title = stringResource(id = R.string.title_delete_confirm),
                    message = stringResource(id = R.string.msg_confirm_delete_single, uiState.fileToDelete?.name ?: ""),
                    isDanger = true,
                    onConfirm = {
                        uiState.fileToDelete?.let { viewModel.onEvent(TransferUiEvent.DeleteFile(it.id)) }
                    },
                    onDismiss = { viewModel.onEvent(TransferUiEvent.ShowDeleteConfirm(null)) }
                )
            }

            // 批量删除确认弹窗
            if (uiState.showBatchDeleteConfirm) {
                TvConfirmDialog(
                    title = stringResource(id = R.string.title_delete_confirm),
                    message = stringResource(id = R.string.msg_confirm_delete, uiState.selectedIds.size),
                    isDanger = true,
                    onConfirm = {
                        viewModel.onEvent(TransferUiEvent.DeleteSelectedFiles)
                    },
                    onDismiss = { viewModel.onEvent(TransferUiEvent.ShowBatchDeleteConfirm(false)) }
                )
            }
        }
    }
}

// ============================================================
// 二维码面板 — 宽屏竖排（二维码上、文字下）/ 竖屏紧凑横排（二维码左、文字右）
// compact=true 用于竖屏上下堆叠布局：整体高度从约 320dp 压到约 150dp，把高度让给文件列表
// ============================================================

@Composable
private fun TransferQrPanel(
    uiState: TransferUiState,
    compact: Boolean,
    weightModifier: Modifier,
) {
    if (compact) {
        // 竖屏紧凑横排
        Row(
            modifier = weightModifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                    .background(TvTheme.colors.surfaceLight)
                    .padding(TvTheme.dimens.spacingMd),
                contentAlignment = Alignment.Center
            ) {
                TransferQrCode(uiState = uiState)
            }
            Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))
            TransferQrTexts(uiState = uiState)
        }
    } else {
        // 宽屏竖排（原横屏布局）
        Column(
            modifier = weightModifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                    .background(TvTheme.colors.surfaceLight)
                    .padding(TvTheme.dimens.spacingMd),
                contentAlignment = Alignment.Center
            ) {
                TransferQrCode(uiState = uiState)
            }

            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
            TransferQrTexts(uiState = uiState, centered = true)
        }
    }
}

/** 二维码图片本体：有码显码、无网络提示、生成中提示 */
@Composable
private fun TransferQrCode(uiState: TransferUiState) {
    val bitmap = uiState.qrBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(id = R.string.transfer_scan_qr),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    } else if (uiState.ipAddress == null) {
        Text(
            text = stringResource(id = R.string.transfer_no_network_conn),
            color = TvTheme.colors.textOnSurfaceLight,
            fontSize = TvTheme.dimens.fontCol1Text,
            fontWeight = FontWeight.Bold,
        )
    } else {
        Text(
            text = stringResource(id = R.string.transfer_generating),
            color = TvTheme.colors.textOnSurfaceLight,
            fontSize = TvTheme.dimens.fontCol1Text,
        )
    }
}

/** 二维码旁的说明文字：标题 + 访问地址 + 同一局域网提示。centered=true 时整体居中（宽屏竖排用） */
@Composable
private fun TransferQrTexts(uiState: TransferUiState, centered: Boolean = false) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            text = stringResource(id = R.string.transfer_scan_qr),
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCol2Title,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
        val url = if (uiState.ipAddress != null) {
            "http://${uiState.ipAddress}:${HttpServerManager.getPort()}"
        } else {
            stringResource(id = R.string.transfer_waiting_network)
        }
        Text(
            text = url,
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCaption,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
        Text(
            text = stringResource(id = R.string.transfer_same_lan_hint),
            color = TvTheme.colors.textPrimary.copy(alpha = 0.5f),
            fontSize = TvTheme.dimens.fontSmall,
        )
    }
}

// ============================================================
// 文件列表面板 — 已接收文件列表（标题栏 + 多选操作 + 面包屑 + 列表/空态/加载态）
// 宽屏走左右分栏右栏、竖屏走上下堆叠下半区，逻辑完全一致
// ============================================================

@Composable
private fun TransferFileListPanel(
    uiState: TransferUiState,
    viewModel: TransferViewModel,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    listState: LazyListState,
    weightModifier: Modifier,
) {
    Box(
        modifier = weightModifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = TvTheme.dimens.spacingMd),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.transfer_received_list),
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontBody,
                    fontWeight = FontWeight.Medium,
                )

                if (uiState.fileList.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    // 清空列表（仅 TV 端视图，不删文件、不影响电脑端；离开本页恢复）
                    val clearListInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = stringResource(id = R.string.transfer_clear_list),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = clearListInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                normalBgColor = TvTheme.colors.borderNormal,
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderNormal
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .clickable(
                                interactionSource = clearListInteraction,
                                indication = null
                            ) { viewModel.onEvent(TransferUiEvent.ClearList) }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                    )
                    if (uiState.isMultiSelectMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)) {
                            // 全选
                            val selectAllInteraction = remember { MutableInteractionSource() }
                            Text(
                                text = if (uiState.selectedIds.size == uiState.fileList.size) stringResource(id = R.string.transfer_unselect_all) else stringResource(id = R.string.action_select_all),
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
                                    ) { viewModel.onEvent(TransferUiEvent.SelectAll) }
                                    .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                            )
                            // 批量删除
                            val hasSelection = uiState.selectedIds.isNotEmpty()
                            val batchDeleteInteraction = remember { MutableInteractionSource() }
                            Text(
                                text = if (hasSelection) "${stringResource(id = R.string.action_delete)}(${uiState.selectedIds.size})" else stringResource(id = R.string.action_delete),
                                color = TvTheme.colors.textPrimary,
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
                                    ) {
                                        // 选中的都是普通文件 → 直接删；只要含文件夹 → 保留确认提示（文件夹会整目录删）
                                        val hasFolder = uiState.fileList.any { it.isDirectory && it.id in uiState.selectedIds }
                                        if (hasFolder) viewModel.onEvent(TransferUiEvent.ShowBatchDeleteConfirm(true))
                                        else viewModel.onEvent(TransferUiEvent.DeleteSelectedFiles)
                                    }
                                    .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                            )
                            // 取消
                            val cancelInteraction = remember { MutableInteractionSource() }
                            Text(
                                text = stringResource(id = R.string.btn_cancel),
                                color = TvTheme.colors.textPrimary,
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
                                    ) { viewModel.onEvent(TransferUiEvent.ToggleMultiSelectMode) }
                                    .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                            )
                        }
                    } else {
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
                    } // 结束「清空列表 + 操作按钮」的 Row
                }
            }

            // 当前目录面包屑（返回上级）
            if (uiState.currentPath.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = TvTheme.dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val upInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = stringResource(id = R.string.transfer_back_up),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = upInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                normalBgColor = TvTheme.colors.borderNormal,
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderNormal
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .clickable(
                                interactionSource = upInteraction,
                                indication = null
                            ) { viewModel.onEvent(TransferUiEvent.NavigateUp) }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                    Text(
                        text = uiState.currentPath,
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontSmall,
                    )
                }
            }

            if (uiState.isLoading) {
                // 加载中
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = TvTheme.colors.textPrimary,
                        modifier = Modifier.size(TvTheme.dimens.progressIndicatorSize)
                    )
                }
            } else if (uiState.fileList.isEmpty() && uiState.uploadingFiles.isEmpty()) {
                // 空列表提示
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = TvTheme.colors.textSecondary,
                            modifier = Modifier.size(TvTheme.dimens.iconSizeXl),
                        )
                        Text(
                            text = stringResource(id = R.string.transfer_waiting_receive),
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontCaption,
                        )
                    }
                }
            } else {
            // 文件列表
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = TvTheme.dimens.spacingLg,
                    bottom = TvTheme.dimens.spacingLg,
                ),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs),
            ) {
                    // 2026-10-04 审计 P0 修复：key 用服务端每请求唯一 id，
                    // 原先 startedAt+name 在同毫秒并发上传同名文件时撞 key 直接崩溃
                    items(uiState.uploadingFiles, key = { it.id.ifEmpty { "up_${it.startedAt}_${it.name}" } }) { uploading ->
                        TransferFileUploadingRow(item = uploading)
                    }
                    items(uiState.fileList, key = { it.id }) { file ->
                        TransferFileItemRow(
                            file = file,
                            isMultiSelectMode = uiState.isMultiSelectMode,
                            isSelected = uiState.selectedIds.contains(file.id),
                            onToggleSelect = { viewModel.onEvent(TransferUiEvent.ToggleSelection(file.id)) },
                            onOpen = { file ->
                                if (file.isDirectory) {
                                    viewModel.onEvent(TransferUiEvent.NavigateTo(file.id))
                                } else {
                                    coroutineScope.launch {
                                        val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            viewModel.openFileWithContext(context, file)
                                        }
                                        if (!success) {
                                            GlobalToastManager.showToast(context.getString(R.string.file_manager_open_failed))
                                        }
                                    }
                                }
                            },
                            onDelete = {
                                // 普通文件直接删；文件夹保留确认提示（会整目录删）
                                if (file.isDirectory) viewModel.onEvent(TransferUiEvent.ShowDeleteConfirm(file))
                                else viewModel.onEvent(TransferUiEvent.DeleteFile(file.id))
                            },
                        )
                    }
                }
            }
        }
    }
}


