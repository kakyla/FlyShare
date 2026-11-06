package com.tvflyshare.app.ui.screens.interconnect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.tvflyshare.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.interconnect.DeviceModel
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.service.TransferForegroundService
import com.tvflyshare.app.ui.viewmodel.DeviceListViewModel
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvTextField
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.ui.screens.network.components.ActionTileButton
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo

@Composable
fun DeviceListScreen(
    onBack: () -> Unit,
    onDeviceSelected: (DeviceModel) -> Unit,
    viewModel: DeviceListViewModel = viewModel()
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    // 互联服务持有者：界面处于组合（可见）时登记 OWNER_DISCOVERY，离开组合时注销。
    // 进入对端浏览(RemoteBrowseScreen)后本页虽离开组合释放 DISCOVERY，但 RemoteBrowseScreen
    // 会续持 OWNER_DISCOVERY，故互联服务/已发现设备不会立即断开；退出整个互联流程后 30s
    // 自动关闭（与互传一致），不再受 NavHost 返回栈保活影响。
    DisposableEffect(Unit) {
        TransferForegroundService.acquire(context, TransferForegroundService.OWNER_DISCOVERY)
        onDispose {
            TransferForegroundService.release(context, TransferForegroundService.OWNER_DISCOVERY)
        }
    }

    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    // 竖屏手机等窄窗口：设备网格 4 列每列仅约 80dp，名称/IP 全被折行；手动添加入口改通栏
    val compact = rememberWindowAdaptiveInfo().widthClass == WindowWidthClass.COMPACT
    val gridColumns = if (compact) 2 else 4

    var showAddDialog by remember { mutableStateOf(false) }
    var inputIp by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf(false) }

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.more_tv_interconnect),
            showBack = true,
            onBack = onBack,
            scrollable = false,
            applyBodyPadding = false,
        ) { _ ->
            // 手动添加入口：跨网段时自动搜索不到，由用户填入对方 IP 互联（无需端口）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing3Xl,
                        vertical = TvTheme.dimens.spacingMd
                    ),
                horizontalArrangement = Arrangement.Start
            ) {
                ActionTileButton(
                    icon = Icons.Default.Add,
                    label = stringResource(id = R.string.interconnect_manual_add),
                    color = TvTheme.colors.tileColor3,
                    onClick = { showAddDialog = true },
                    compact = compact,
                    modifier = if (compact) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.width(TvTheme.dimens.actionPanelWidth)
                    }
                )
            }

            if (devices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(
                            horizontal = if (compact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing3Xl
                        )
                    ) {
                        Text(
                            text = stringResource(id = R.string.interconnect_searching),
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontCol1Text
                        )
                        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                        Text(
                            text = stringResource(id = R.string.interconnect_searching_hint),
                            color = TvTheme.colors.textSecondary.copy(alpha = 0.7f),
                            fontSize = TvTheme.dimens.fontSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // 同型号电视的设备名相同（都是 Build.MODEL），重名时在标题追加 IP 尾段区分；
                // 手动添加的设备标题本来就是 IP，无需追加。
                val duplicateNames = devices
                    .filter { !it.deviceId.startsWith("manual-") }
                    .groupingBy { it.deviceName }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = if (compact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing3Xl,
                            vertical = TvTheme.dimens.spacingXl
                        ),
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg),
                    verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg)
                ) {
                    items(devices, key = { it.deviceId }) { device ->
                        DeviceGridItem(
                            device = device,
                            showIpSuffix = device.deviceName in duplicateNames,
                            onClick = { onDeviceSelected(device) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        TvAlertDialog(
            title = stringResource(id = R.string.interconnect_manual_add_title),
            confirmText = stringResource(id = R.string.interconnect_manual_add),
            dismissText = stringResource(id = R.string.btn_cancel),
            onConfirm = {
                val ip = inputIp.trim()
                if (NetworkUtils.isIpv4(ip)) {
                    viewModel.addManualDevice(ip)
                    GlobalToastManager.showToast(context.getString(R.string.interconnect_manual_added, ip))
                    showAddDialog = false
                    inputIp = ""
                } else {
                    ipError = true
                }
            },
            onDismiss = { showAddDialog = false },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.interconnect_manual_add_hint),
                        color = TvTheme.colors.textPrimary.copy(alpha = 0.9f),
                        fontSize = TvTheme.dimens.fontCaption
                    )
                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                    TvTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it; ipError = false },
                        label = { Text(stringResource(id = R.string.interconnect_manual_add_placeholder)) },
                        placeholder = { Text(stringResource(id = R.string.interconnect_manual_add_placeholder)) },
                        singleLine = true,
                        isError = ipError,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ipError) {
                        Text(
                            text = stringResource(id = R.string.interconnect_manual_add_invalid),
                            color = TvTheme.colors.brandError,
                            fontSize = TvTheme.dimens.fontSmall,
                            modifier = Modifier.padding(top = TvTheme.dimens.spacingXs),
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun DeviceGridItem(
    device: DeviceModel,
    showIpSuffix: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isFocused) 1f else 0f)
            .defaultMinSize(minHeight = TvTheme.dimens.gridTileHeight)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusLg),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused,
                focusedBorderColor = TvTheme.colors.brandPrimary
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(TvTheme.dimens.spacingLg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Tv,
            contentDescription = null,
            tint = if (isFocused) TvTheme.colors.brandPrimary else TvTheme.colors.brandPrimary.copy(alpha = 0.8f),
            modifier = Modifier.size(TvTheme.dimens.iconSizeLg)
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
        Text(
            text = if (showIpSuffix) {
                "${device.deviceName} (.${device.ipAddress.substringAfterLast('.')})"
            } else {
                device.deviceName
            },
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCol1Text,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
        Text(
            text = device.ipAddress,
            color = TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontSmall,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
