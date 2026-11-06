package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.uiconfig.global.TvTheme
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
import com.tvflyshare.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.tvflyshare.app.network.LanDevice
import com.tvflyshare.app.network.SmbConnectionStore

@Composable
fun ActionTileButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 竖屏：图标+文字整体水平居中（窄屏一行放不了几个按钮，居中排布更匀称）；
    // 横屏/TV 保持左对齐原样
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .height(TvTheme.dimens.spacing3Xl) // 近似 TvTheme.dimens.spacing3Xl
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.05f,
                normalBgColor = color,
                focusedBgColor = color
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = TvTheme.dimens.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compact) {
                Arrangement.spacedBy(TvTheme.dimens.spacingMd, Alignment.CenterHorizontally)
            } else {
                Arrangement.spacedBy(TvTheme.dimens.spacingMd)
            },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TvTheme.colors.textOnTile,
                modifier = Modifier.size(TvTheme.dimens.iconSizeMd),
            )
            Text(
                text = label,
                color = TvTheme.colors.textOnTile,
                fontSize = TvTheme.dimens.fontBody,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}