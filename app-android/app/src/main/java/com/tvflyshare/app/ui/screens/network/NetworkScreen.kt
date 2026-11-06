@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tvflyshare.app.ui.screens.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.R
import com.tvflyshare.app.network.SmbConnectionStore
import com.tvflyshare.app.network.FtpConnectionStore
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.ui.screens.network.components.ActionTileButton
import com.tvflyshare.app.ui.screens.network.components.DeviceCard
import com.tvflyshare.app.ui.screens.network.components.SmbConnectionCard
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.ui.screens.network.components.*
import com.tvflyshare.app.ui.viewmodel.NetworkAction
import com.tvflyshare.app.ui.viewmodel.NetworkUiState
import com.tvflyshare.app.ui.viewmodel.NetworkViewModel
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog

@Composable
fun NetworkScreen(
    onBack: () -> Unit,
    viewModel: NetworkViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isPopping by remember { androidx.compose.runtime.mutableStateOf(false) }

    // 拦截物理返回键
    androidx.activity.compose.BackHandler {
        if (uiState.showSmbBrowse) {
            // 如果 SMB 浏览界面处于显示状态，交由 SmbBrowseScreen 内部的 BackHandler 处理
            // 如果走到这里，说明某种原因导致其未拦截，我们安全地将其关闭
            viewModel.onAction(NetworkAction.ShowSmbBrowse(show = false))
        } else if (uiState.showFtpBrowse) {
            viewModel.onAction(NetworkAction.ShowFtpBrowse(show = false))
        } else if (uiState.isMultiSelectMode) {
            viewModel.onAction(NetworkAction.ExitMultiSelect)
        } else if (!isPopping) {
            isPopping = true
            onBack()
        }
    }

    if (uiState.showSmbBrowse) {
        SmbBrowseScreen(
            host = uiState.smbHostInput,
            shareName = uiState.smbShareName,
            username = uiState.smbUsername,
            password = uiState.smbPassword,
            onBack = {
                viewModel.onAction(NetworkAction.ShowSmbBrowse(show = false))
            },
        )
        return
    }

    if (uiState.showFtpBrowse) {
        FtpBrowseScreen(
            host = uiState.ftpHost,
            port = uiState.ftpPort,
            username = uiState.ftpUsername,
            password = uiState.ftpPassword,
            anonymous = uiState.ftpAnonymous,
            onBack = {
                viewModel.onAction(NetworkAction.ShowFtpBrowse(show = false))
            },
        )
        return
    }

    TVBackground {
        ScreenScaffold(
            title = stringResource(R.string.network_title),
            showBack = true,
            onBack = {
                if (uiState.isMultiSelectMode) {
                    viewModel.onAction(NetworkAction.ExitMultiSelect)
                } else if (!isPopping) {
                    isPopping = true
                    onBack()
                }
            },
            deviceInfo = "${uiState.deviceName}  ${uiState.localIp}",
            scrollable = false,
            applyBodyPadding = false,
        ) { _ ->

            // 主体：宽屏（TV/横屏）左右分栏（列表区 + 固定宽按钮栏）；
            // 竖屏（手机/车机）单列堆叠——按钮区横排置顶，列表区上下排列
            val windowInfo = rememberWindowAdaptiveInfo()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (windowInfo.isWideLayout) TvTheme.dimens.spacing2Xl else TvTheme.dimens.spacingXl,
                        vertical = TvTheme.dimens.spacingMd
                    ),
            ) {
                if (!windowInfo.isWideLayout) {
                    // 竖屏：操作按钮横排置顶（原右侧固定宽竖排按钮栏，窄屏上占掉近半屏宽）
                    NetworkActionPanel(
                        uiState = uiState,
                        viewModel = viewModel,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (windowInfo.isWideLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg),
                    ) {
                        // 左侧：上下两个独立滚动区域
                        NetworkMainSection(
                            uiState = uiState,
                            viewModel = viewModel,
                            singleColumn = !windowInfo.isWideLayout,
                            modifier = Modifier.weight(1f),
                        )

                        // 右侧：操作按钮
                        NetworkActionPanel(
                            uiState = uiState,
                            viewModel = viewModel,
                            compact = false,
                            modifier = Modifier.width(TvTheme.dimens.actionPanelWidth),
                        )
                    }
                } else {
                    // 竖屏：列表区占满剩余高度（已保存连接 60% / 局域网设备 40%）
                    NetworkMainSection(
                        uiState = uiState,
                        viewModel = viewModel,
                        singleColumn = !windowInfo.isWideLayout,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (uiState.showDeleteConfirm) {
        TvConfirmDialog(
            title = stringResource(id = R.string.title_delete_confirm),
            message = stringResource(id = R.string.msg_confirm_delete, uiState.selectedCount),
            isDanger = true,
            onConfirm = {
                viewModel.onAction(NetworkAction.DeleteSelected)
            },
            onDismiss = { viewModel.onAction(NetworkAction.ShowDeleteConfirm(false)) }
        )
    }

    // SMB 连接对话框
    if (uiState.showSmbDialog) {
        val saved = uiState.savedConnections.find { it.host == uiState.smbHostInput }
        SmbConnectDialog(
            initialHost = uiState.smbHostInput,
            initialShare = saved?.share ?: "",
            initialUser = saved?.username ?: "",
            initialPass = saved?.password ?: "",
            onConfirm = { host, share, user, pass ->
                viewModel.onAction(NetworkAction.SaveConnection(
                    SmbConnectionStore.SmbConnection(
                        host = host, share = share, username = user, password = pass
                    )
                ))
                viewModel.onAction(NetworkAction.ShowSmbDialog(false))
                viewModel.onAction(NetworkAction.ShowSmbBrowse(
                    show = true, host = host, shareName = share, username = user, password = pass
                ))
            },
            onDismiss = { viewModel.onAction(NetworkAction.ShowSmbDialog(false)) },
        )
    }

    // FTP 连接对话框
    if (uiState.showFtpDialog) {
        // 2026-10-04 审计 P2 修复：从已保存连接回填端口/账号（与 SMB 侧对齐）——
        // 原先 user/pass/anonymous 恒为空/false，二次连接已存 FTP 需要全部重填
        val savedFtp = uiState.ftpConnections.find { it.host == uiState.ftpHostInput }
        FtpConnectDialog(
            initialHost = uiState.ftpHostInput,
            initialPort = savedFtp?.port ?: uiState.ftpPortInput,
            initialUser = savedFtp?.username ?: "",
            initialPass = savedFtp?.password ?: "",
            initialAnonymous = savedFtp?.anonymous ?: false,
            onConfirm = { host, port, user, pass, anonymous ->
                viewModel.onAction(NetworkAction.SaveFtpConnection(
                    FtpConnectionStore.FtpConnection(
                        host = host, port = port, username = user, password = pass, anonymous = anonymous
                    )
                ))
                viewModel.onAction(NetworkAction.ShowFtpDialog(false))
                viewModel.onAction(NetworkAction.ShowFtpBrowse(
                    show = true, host = host, port = port, username = user, password = pass, anonymous = anonymous
                ))
            },
            onDismiss = { viewModel.onAction(NetworkAction.ShowFtpDialog(false)) },
        )
    }
}

// ============================================================
// 网络页主体列表区 — 已保存连接（上，60%）+ 局域网设备（下，40%）
// 列表项排布随操作形态切换：竖屏/触屏单列满宽（名字/IP 完整可见、点按面积大），
// 宽屏/遥控双列网格（dpad 导航顺手）；触屏与遥控操作逻辑不同，不可混用。
// ============================================================

@Composable
private fun NetworkMainSection(
    uiState: NetworkUiState,
    viewModel: NetworkViewModel,
    singleColumn: Boolean,
    modifier: Modifier = Modifier,
) {
    // 触屏（竖屏，非宽屏）单列满宽，名字/IP 完整可见、点按面积大；
    // 遥控/宽屏双列网格，dpad 导航更顺手。两条操作逻辑不同，不能混用。
    val cols = if (singleColumn) 1 else 2

    Column(modifier = modifier) {
        // === 上区：已保存的连接（60%） ===
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f),
        ) {
            SectionHeader(stringResource(R.string.network_saved_connections))
            if (uiState.savedConnections.isEmpty() && uiState.ftpConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = TvTheme.dimens.spacingLg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.network_no_saved),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                val pairs = uiState.savedConnections.chunked(cols)
                val ftpPairs = uiState.ftpConnections.chunked(cols)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                ) {
                    pairs.forEach { pair ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                            ) {
                                pair.forEach { conn ->
                                    val itemKey = "${conn.host}/${conn.share}"
                                    val isSelected = uiState.selectedConns.contains(itemKey)
                                    SmbConnectionCard(
                                        conn = conn,
                                        isMultiSelect = uiState.isMultiSelectMode,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (uiState.isMultiSelectMode) {
                                                viewModel.onAction(NetworkAction.ToggleConnSelection(itemKey))
                                            } else {
                                                viewModel.onAction(NetworkAction.MoveConnectionToFront(conn.host, conn.share))
                                                viewModel.onAction(NetworkAction.ShowSmbBrowse(
                                                    show = true,
                                                    host = conn.host,
                                                    shareName = conn.share,
                                                    username = conn.username,
                                                    password = conn.password
                                                ))
                                            }
                                        },
                                        onLongClick = {
                                            if (!uiState.isMultiSelectMode) {
                                                viewModel.onAction(NetworkAction.ToggleMultiSelect(true))
                                            }
                                            viewModel.onAction(NetworkAction.ToggleConnSelection(itemKey))
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(cols - pair.size) {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    // FTP 已保存连接并入同一“已保存连接”列表
                    ftpPairs.forEach { pair ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                            ) {
                                pair.forEach { conn ->
                                    val itemKey = "${conn.host}:${conn.port}"
                                    val isSelected = uiState.selectedFtps.contains(itemKey)
                                    FtpConnectionCard(
                                        conn = conn,
                                        isMultiSelect = uiState.isMultiSelectMode,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (uiState.isMultiSelectMode) {
                                                viewModel.onAction(NetworkAction.ToggleFtpSelection(itemKey))
                                            } else {
                                                viewModel.onAction(NetworkAction.MoveFtpToFront(conn.host, conn.port))
                                                viewModel.onAction(NetworkAction.ShowFtpBrowse(
                                                    show = true,
                                                    host = conn.host,
                                                    port = conn.port,
                                                    username = conn.username,
                                                    password = conn.password,
                                                    anonymous = conn.anonymous
                                                ))
                                            }
                                        },
                                        onLongClick = {
                                            if (!uiState.isMultiSelectMode) {
                                                viewModel.onAction(NetworkAction.ToggleMultiSelect(true))
                                            }
                                            viewModel.onAction(NetworkAction.ToggleFtpSelection(itemKey))
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(cols - pair.size) {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvTheme.dimens.borderNormal)
                .background(TvTheme.colors.surfaceVariant)
                .padding(vertical = TvTheme.dimens.spacingXs),
        )

        // === 下区：局域网设备（40%） ===
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f),
        ) {
            SectionHeader(stringResource(R.string.network_title))
            if (uiState.devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = TvTheme.dimens.spacingLg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState.isScannerRunning) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            CircularProgressIndicator(color = TvTheme.colors.textPrimary)
                            Text(stringResource(R.string.network_scanning), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontSmall)
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lan,
                                contentDescription = null,
                                tint = TvTheme.colors.textSecondary,
                                modifier = Modifier.size(TvTheme.dimens.iconSizeXl),
                            )
                            Text(
                                text = when {
                                    uiState.isScannerRunning -> stringResource(id = R.string.network_scanning_lan)
                                    uiState.scanCompleted && uiState.devices.isEmpty() -> stringResource(id = R.string.network_scan_no_devices)
                                    uiState.scanFailedNoNetwork -> stringResource(id = R.string.network_scan_no_network)
                                    else -> stringResource(id = R.string.network_scan_hint)
                                },
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontSmall,
                            )
                        }
                    }
                }
            } else {
                val devicePairs = uiState.devices.chunked(cols)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                ) {
                    // 2026-10-04 审计 P2 修复：key 加序号兜底——原先纯 ip 拼接，
                    // 同 IP/空 IP 时 LazyColumn 重复 key 直接抛异常崩溃
                    itemsIndexed(devicePairs, key = { index, _ -> "pair_$index" }) { _, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            pair.forEach { device ->
                                val isSelected = uiState.selectedDevices.contains(device.ip)
                                DeviceCard(
                                    device = device,
                                    isMultiSelect = uiState.isMultiSelectMode,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (uiState.isMultiSelectMode) {
                                            viewModel.onAction(NetworkAction.ToggleDeviceSelection(device.ip))
                                        } else {
                                            if (uiState.isScannerRunning) {
                                                viewModel.onAction(NetworkAction.RefreshScanner) // Stop it
                                            }
                                            viewModel.onAction(NetworkAction.ShowSmbDialog(show = true, host = device.ip))
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isMultiSelectMode) {
                                            viewModel.onAction(NetworkAction.ToggleMultiSelect(true))
                                        }
                                        viewModel.onAction(NetworkAction.ToggleDeviceSelection(device.ip))
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(cols - pair.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 网络页操作按钮区 — compact=false 为宽屏右侧竖排按钮栏；
// compact=true 为竖屏顶部横排（三个按钮等分宽度，磁贴样式不变）
// ============================================================

@Composable
private fun NetworkActionPanel(
    uiState: NetworkUiState,
    viewModel: NetworkViewModel,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        // 竖屏：一大两小——主操作独占一行满宽，其余两个半宽并排；
        // 按钮数超过两个即换行，杜绝一行挤三个导致文字被省略号截断；
        // 竖屏按钮内容整体水平居中（用户真机反馈）
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
        ) {
            if (uiState.isMultiSelectMode) {
                // 多选模式：主操作「全选」独占一行，删除/退出多选两个半宽并排
                ActionTileButton(
                    icon = Icons.Default.SelectAll,
                    label = stringResource(id = R.string.file_manager_action_select_all),
                    color = TvTheme.colors.brandPrimary,
                    onClick = {
                        viewModel.onAction(NetworkAction.SelectAll)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                ) {
                    ActionTileButton(
                        icon = Icons.Default.Delete,
                        label = if (uiState.selectedDevices.isNotEmpty() && uiState.selectedConns.isEmpty()) {
                            stringResource(id = R.string.network_remove_selected, uiState.selectedCount)
                        } else {
                            stringResource(id = R.string.network_delete_selected, uiState.selectedCount)
                        },
                        color = TvTheme.colors.brandError,
                        onClick = {
                            if (uiState.selectedCount > 0) {
                                viewModel.onAction(NetworkAction.ShowDeleteConfirm(true))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    ActionTileButton(
                        icon = Icons.Default.Close,
                        label = stringResource(id = R.string.network_exit_multi),
                        color = TvTheme.colors.surfaceVariant,
                        onClick = { viewModel.onAction(NetworkAction.ExitMultiSelect) },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
            } else {
                // 正常模式：主操作「扫描」独占一行，连接 SMB/FTP 两个半宽并排
                ActionTileButton(
                    icon = Icons.Default.Search,
                    label = if (uiState.isScannerRunning) stringResource(id = R.string.network_scanning_count, uiState.devices.size) else stringResource(id = R.string.network_scan_device),
                    color = TvTheme.colors.brandPrimary,
                    onClick = {
                        viewModel.onAction(NetworkAction.RefreshScanner)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                ) {
                    ActionTileButton(
                        icon = Icons.Default.Add,
                        label = stringResource(id = R.string.network_connect_smb),
                        color = TvTheme.colors.tileColor3,
                        onClick = {
                            val last = uiState.savedConnections.firstOrNull()
                            viewModel.onAction(NetworkAction.ShowSmbDialog(show = true, host = last?.host ?: ""))
                        },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    ActionTileButton(
                        icon = Icons.Default.Cloud,
                        label = stringResource(id = R.string.network_connect_ftp),
                        color = TvTheme.colors.tileColor3,
                        onClick = {
                            val last = uiState.ftpConnections.firstOrNull()
                            viewModel.onAction(NetworkAction.ShowFtpDialog(show = true, host = last?.host ?: ""))
                        },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
        ) {
            if (uiState.isMultiSelectMode) {
                // 多选模式下的操作按钮
                ActionTileButton(
                    icon = Icons.Default.SelectAll,
                    label = stringResource(id = R.string.file_manager_action_select_all),
                    color = TvTheme.colors.brandPrimary,
                    onClick = {
                        viewModel.onAction(NetworkAction.SelectAll)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionTileButton(
                    icon = Icons.Default.Delete,
                    label = if (uiState.selectedDevices.isNotEmpty() && uiState.selectedConns.isEmpty()) {
                        stringResource(id = R.string.network_remove_selected, uiState.selectedCount)
                    } else {
                        stringResource(id = R.string.network_delete_selected, uiState.selectedCount)
                    },
                    color = TvTheme.colors.brandError,
                    onClick = {
                        if (uiState.selectedCount > 0) {
                            viewModel.onAction(NetworkAction.ShowDeleteConfirm(true))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionTileButton(
                    icon = Icons.Default.Close,
                    label = stringResource(id = R.string.network_exit_multi),
                    color = TvTheme.colors.surfaceVariant,
                    onClick = { viewModel.onAction(NetworkAction.ExitMultiSelect) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 正常模式
                ActionTileButton(
                    icon = Icons.Default.Search,
                    label = if (uiState.isScannerRunning) stringResource(id = R.string.network_scanning_count, uiState.devices.size) else stringResource(id = R.string.network_scan_device),
                    color = TvTheme.colors.brandPrimary,
                    onClick = {
                        viewModel.onAction(NetworkAction.RefreshScanner)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionTileButton(
                    icon = Icons.Default.Add,
                    label = stringResource(id = R.string.network_connect_smb),
                    color = TvTheme.colors.tileColor3,
                    onClick = {
                        val last = uiState.savedConnections.firstOrNull()
                        viewModel.onAction(NetworkAction.ShowSmbDialog(show = true, host = last?.host ?: ""))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionTileButton(
                    icon = Icons.Default.Cloud,
                    label = stringResource(id = R.string.network_connect_ftp),
                    color = TvTheme.colors.tileColor3,
                    onClick = {
                        val last = uiState.ftpConnections.firstOrNull()
                        viewModel.onAction(NetworkAction.ShowFtpDialog(show = true, host = last?.host ?: ""))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ============================================================
// 分区标题
// ============================================================

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TvTheme.colors.textSecondary,
        fontSize = TvTheme.dimens.fontSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = TvTheme.dimens.spacingSm, bottom = TvTheme.dimens.spacingXs),
    )
}
