package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.components.*
import com.tvflyshare.app.ui.viewmodel.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.tvflyshare.app.network.FtpConnectionStore
import com.tvflyshare.app.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FtpConnectionCard(
    conn: FtpConnectionStore.FtpConnection,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // 与 DeviceCard 保持一致：竖屏（窄窗口）时第二行副文字移到卡片底部独占整行，
    // 避免窄列下被省略号截断；横屏/TV 保持原排布
    val compact = !rememberWindowAdaptiveInfo().isWideLayout

    Surface(
        modifier = modifier
            .height(TvTheme.dimens.cardHeightSm)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalBgColor = TvTheme.colors.tileColor3,
                focusedBgColor = TvTheme.colors.tileColor3, // 保持底色不变
                isSelected = isSelected,
                selectedBgColor = TvTheme.colors.tileColor3,
                selectedBorderColor = TvTheme.colors.brandSuccess
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        if (compact) {
            // 竖屏：第一行 图标+主机:端口，第二行用户名独占整行（与 DeviceCard 同款排布）
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
                    // 多选模式显示勾选圈
                    if (isMultiSelect) {
                        TvCheckbox(
                            checked = isSelected,
                            size = TvTheme.dimens.iconSizeMd,
                            iconSize = TvTheme.dimens.iconSizeSm,
                            shapeType = "circle"
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = TvTheme.colors.textOnTile,
                        modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
                    )
                    Text(
                        text = "${conn.host}:${conn.port}",
                        color = TvTheme.colors.textOnTile,
                        fontSize = TvTheme.dimens.fontCaption,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 用户名独占整行：不再受图标挤占，窄列也能完整显示；整行居中（用户真机反馈）
                Text(
                    text = if (conn.anonymous) {
                        stringResource(id = R.string.ftp_anonymous)
                    } else {
                        conn.username.ifBlank { stringResource(id = R.string.ftp_user_placeholder) }
                    },
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
                // 多选模式显示勾选圈
                if (isMultiSelect) {
                    TvCheckbox(
                        checked = isSelected,
                        size = TvTheme.dimens.iconSizeMd,
                        iconSize = TvTheme.dimens.iconSizeSm,
                        shapeType = "circle"
                    )
                }

                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = TvTheme.colors.textOnTile,
                    modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${conn.host}:${conn.port}",
                        color = TvTheme.colors.textOnTile,
                        fontSize = TvTheme.dimens.fontCaption,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (conn.anonymous) {
                            stringResource(id = R.string.ftp_anonymous)
                        } else {
                            conn.username.ifBlank { stringResource(id = R.string.ftp_user_placeholder) }
                        },
                        color = TvTheme.colors.textOnTileDim,
                        fontSize = TvTheme.dimens.fontSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
