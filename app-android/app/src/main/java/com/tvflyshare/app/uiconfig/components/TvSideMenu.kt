package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.TvTheme

/** 统一菜单项颜色风格 */
enum class TvMenuItemStyle {
    DEFAULT, DANGER, SUCCESS
}

/** 统一菜单项数据类 */
data class TvMenuItem(
    val label: String,
    val icon: ImageVector,
    val style: TvMenuItemStyle = TvMenuItemStyle.DEFAULT,
    val enabled: Boolean = true,
    val action: () -> Unit
)

/** 全局统一的 TV 侧边菜单组件 */
@Composable
fun TvSideMenu(
    title: String,
    subtitle: String,
    items: List<TvMenuItem>,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    if (onClose != null) {
        BackHandler { onClose() }
    }

    // 面板最高占屏幕 95%，内容不足时按内容收缩（外观与以前一致）。
    // 2026-10-30 修复：原实现用普通 Column 直接摆条目，一旦「条目数 × 52dp + 标题 + 间距」
    // 超过 95% 屏高（手机横屏、低高度窗口）就被裁掉底部——多选面板的「取消」只剩半截。
    // 改为 LazyColumn 承载：内容放得下时行为完全一致，放不下时在面板内滚动，不再裁切。
    val maxMenuHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.95f).dp }

    LazyColumn(
        modifier = modifier
            .heightIn(max = maxMenuHeight)
            .clip(RoundedCornerShape(topStart = TvTheme.dimens.radiusMd, bottomStart = TvTheme.dimens.radiusMd))
            .background(TvTheme.colors.dialogBg)
            .padding(TvTheme.dimens.spacingMd),
        // 内容少时整体垂直居中（与修复前一致的观感）；内容多时按需滚动
        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm, Alignment.CenterVertically),
    ) {
        item { Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm)) }
        item {
            Text(
                text = title,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCaption,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = TvTheme.dimens.spacingSm),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (subtitle.isNotEmpty()) {
            item {
                Text(
                    text = subtitle,
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd)) }
        items(items) { item ->
            TvMenuItemButton(item = item)
        }
    }
}

@Composable
private fun TvMenuItemButton(item: TvMenuItem) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    // 统一的焦点状态颜色值
    val focusedBg = TvTheme.colors.surfaceVariant.copy(alpha = 0.5f)
    val normalBg = TvTheme.colors.surface.copy(alpha = 0.1f)
    val focusedBorderColor = TvTheme.colors.focusBorder

    val iconColor = when (item.style) {
        TvMenuItemStyle.DANGER -> TvTheme.colors.brandError
        TvMenuItemStyle.SUCCESS -> TvTheme.colors.brandSuccess
        TvMenuItemStyle.DEFAULT -> TvTheme.colors.textPrimary
    }

    val textColor = when (item.style) {
        TvMenuItemStyle.DANGER -> TvTheme.colors.brandError
        else -> TvTheme.colors.textPrimary
    }

    val alpha = if (!item.enabled) 0.3f else if (isFocused) 1f else 0.7f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.menuItemHeight)
            .clickable(
                enabled = item.enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = item.action
            )
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalScale = 1f,
                focusedBgColor = focusedBg,
                normalBgColor = normalBg,
                focusedBorderColor = focusedBorderColor,
                normalBorderColor = TvTheme.colors.borderNormal,
                borderWidth = TvTheme.dimens.borderFocused,
            )
            .padding(horizontal = TvTheme.dimens.spacingMd),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = iconColor.copy(alpha = alpha),
                modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
            )
            Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))
            Text(
                text = item.label,
                color = textColor.copy(alpha = alpha),
                fontSize = TvTheme.dimens.fontBody,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
