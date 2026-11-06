package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.applyTvDialogBlurEffect
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass

/**
 * 网格操作菜单项定义
 */
data class TvGridMenuItem(
    val text: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 电视端通用的网格操作对话框
 * 适配大屏焦点，提供应用图标/图片头部，以及横向分布的极简操作按钮。
 */
@Composable
fun TvGridMenuDialog(
    title: String,
    iconBitmap: ImageBitmap? = null,
    fallbackIconText: String? = null,
    onDismissRequest: () -> Unit,
    items: List<TvGridMenuItem>
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val isCompact = rememberWindowAdaptiveInfo().widthClass == WindowWidthClass.COMPACT
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            window?.applyTvDialogBlurEffect()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = TvTheme.dimens.dialogWidthLg)
                .then(if (isCompact) Modifier.padding(horizontal = TvTheme.dimens.spacingLg) else Modifier)
                .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                .background(TvTheme.colors.dialogBg)
                .border(TvTheme.dimens.borderNormal, TvTheme.colors.borderNormal, RoundedCornerShape(TvTheme.dimens.radiusLg))
                .padding(TvTheme.dimens.spacingXl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部图标区
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = title,
                        modifier = Modifier
                            .size(TvTheme.dimens.dialogIconSize)
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                    )
                } else if (fallbackIconText != null) {
                    Box(
                        modifier = Modifier
                            .size(TvTheme.dimens.dialogIconSize)
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                            .background(TvTheme.colors.brandPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = fallbackIconText,
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontTitle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (iconBitmap != null || fallbackIconText != null) {
                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                }

                // 标题
                Text(
                    text = title,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(TvTheme.dimens.spacing2Xl))

                // 横向网格按钮区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEach { item ->
                        TvGridMenuBtn(
                            text = item.text,
                            icon = item.icon,
                            isDestructive = item.isDestructive,
                            onClick = item.onClick
                        )
                    }
                }
            }
    }
}

@Composable
private fun TvGridMenuBtn(
    text: String,
    icon: ImageVector,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val baseColor = if (isDestructive) TvTheme.colors.brandError else TvTheme.colors.brandPrimary
    val contentColor = if (isFocused) TvTheme.colors.textPrimary else TvTheme.colors.textSecondary

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.1f,
                normalBgColor = TvTheme.colors.borderNormal,
                focusedBgColor = baseColor,
                borderWidth = TvTheme.dimens.borderFocused
            )
            .padding(TvTheme.dimens.spacingMd),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (isFocused) TvTheme.colors.textPrimary else baseColor,
            modifier = Modifier.size(TvTheme.dimens.iconSizeLg)
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
        Text(
            text = text,
            color = contentColor,
            fontSize = TvTheme.dimens.fontCaption,
            fontWeight = FontWeight.Medium
        )
    }
}
