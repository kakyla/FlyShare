package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.tvflyshare.app.uiconfig.global.TvTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFileListItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    iconSize: Dp = TvTheme.dimens.iconSizeXl,
    title: String,
    subtitle: String? = null,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    checkboxShape: String = "rounded",
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // 可选：自定义图标位（如本地图片 / 视频缩略图）。提供时优先渲染它，否则回退到 icon 图标。
    // 默认 null —— 其它调用方无需改动，行为不变。
    iconSlot: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.listItemHeight)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                isSelected = isSelected,
                borderWidth = if (isSelected) TvTheme.dimens.borderFocused else TvTheme.dimens.borderNormal,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.1f),
                normalBgColor = TvTheme.colors.surface.copy(alpha = 0.1f),
                focusedBorderColor = TvTheme.colors.focusBorder,
                normalBorderColor = TvTheme.colors.borderNormal,
                selectedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                selectedBorderColor = TvTheme.colors.brandPrimary,
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = TvTheme.dimens.spacingLg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            TvCheckbox(
                checked = isSelected,
                size = TvTheme.dimens.iconSizeLg,
                iconSize = TvTheme.dimens.iconSizeMd,
                shapeType = checkboxShape
            )
            Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
        }

        iconSlot?.invoke()
            ?: Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
        Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title, 
                color = TvTheme.colors.textPrimary, 
                fontSize = TvTheme.dimens.fontCol2Title, 
                fontWeight = FontWeight.Medium, 
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TvTheme.colors.textSecondary, 
                    fontSize = TvTheme.dimens.fontCol2Desc, 
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
