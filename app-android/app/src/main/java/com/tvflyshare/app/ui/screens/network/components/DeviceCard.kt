package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.components.*
import com.tvflyshare.app.smb.SmbFileEntry
import com.tvflyshare.app.ui.viewmodel.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.tvflyshare.app.network.LanDevice
import com.tvflyshare.app.network.SmbConnectionStore

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    device: LanDevice,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    // 2026-10-04 审计 P2 修复：删除 onRemove 死参数——函数体从未引用（"卡片删除入口"整体不可达），
    // 长按进多选删除是唯一删除路径；保留死参数会误导后续维护者以为功能存在
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // 竖屏（窄窗口）专用排布：局域网设备是两列网格，每列仅约 200dp，
    // 旧排布（图标 + 名字/IP 两行文字）里 IP 列宽不足 100dp，被省略号截断；
    // 竖屏时把 IP 移到卡片底部独占整行，可用宽度约 165dp，IP 完整显示（用户真机反馈）。
    // 横屏/TV 保持原排布不变。
    val compact = !rememberWindowAdaptiveInfo().isWideLayout

    val icon = when (device.deviceType.lowercase(java.util.Locale.ROOT)) {
        "tv" -> Icons.Default.Tv
        "phone" -> Icons.Default.PhoneAndroid
        "box" -> Icons.Default.Computer
        "smb" -> Icons.Default.Computer
        else -> Icons.Default.Router
    }

    Surface(
        modifier = modifier
            .height(TvTheme.dimens.cardHeightSm)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalBgColor = TvTheme.colors.brandPrimary,
                focusedBgColor = TvTheme.colors.brandPrimary,
                isSelected = isSelected,
                selectedBgColor = TvTheme.colors.brandPrimary,
                selectedBorderColor = TvTheme.colors.brandSuccess
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        if (compact) {
            // 竖屏：第一行 图标+名字+在线点，第二行 IP 独占整行
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingSm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                ) {
                    // 多选勾选圈
                    if (isMultiSelect) {
                        TvCheckbox(
                            checked = isSelected,
                            size = TvTheme.dimens.iconSizeMd,
                            iconSize = TvTheme.dimens.iconSizeSm,
                            shapeType = "circle"
                        )
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = device.deviceType,
                        tint = TvTheme.colors.textOnTile,
                        modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
                    )
                    Text(
                        // 主机名获取失败时 device.name=device.ip，此时第一行显示"局域网设备"避免与第二行 IP 重复
                        text = if (device.name == device.ip) stringResource(id = R.string.network_device_lan) else device.name,
                        color = TvTheme.colors.textOnTile,
                        fontSize = TvTheme.dimens.fontCaption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (device.isOnline && !isMultiSelect) {
                        Box(
                            modifier = Modifier
                                .size(TvTheme.dimens.spacingSm)
                                .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                .background(TvTheme.colors.brandSuccess),
                        )
                    }
                }
                // IP 独占整行：不再受图标/在线点挤占，窄列也能完整显示；整行居中（用户真机反馈）
                Text(
                    text = device.ip,
                    color = TvTheme.colors.textOnTileDim,
                    fontSize = TvTheme.dimens.fontSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(horizontal = TvTheme.dimens.spacingLg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
            ) {
                // 多选勾选圈
                if (isMultiSelect) {
                    TvCheckbox(
                        checked = isSelected,
                        size = TvTheme.dimens.iconSizeMd,
                        iconSize = TvTheme.dimens.iconSizeSm,
                        shapeType = "circle"
                    )
                }

                Icon(
                    imageVector = icon,
                    contentDescription = device.deviceType,
                    tint = TvTheme.colors.textOnTile,
                    modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // 主机名获取失败时 device.name=device.ip，此时第一行显示"局域网设备"避免与第二行 IP 重复
                        text = if (device.name == device.ip) stringResource(id = R.string.network_device_lan) else device.name,
                        color = TvTheme.colors.textOnTile,
                        fontSize = TvTheme.dimens.fontCaption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = device.ip,
                        color = TvTheme.colors.textOnTileDim,
                        fontSize = TvTheme.dimens.fontSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (device.isOnline && !isMultiSelect) {
                    Box(
                        modifier = Modifier
                            .size(TvTheme.dimens.spacingSm)
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .background(TvTheme.colors.brandSuccess),
                    )
                }
            }
        }
    }
}