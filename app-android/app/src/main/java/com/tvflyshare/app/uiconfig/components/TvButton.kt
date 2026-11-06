package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.tvflyshare.app.uiconfig.global.TvTheme

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = TvTheme.dimens.spacingLg,
    verticalPadding: Dp = TvTheme.dimens.spacingSm,
    normalBgColor: Color = TvTheme.colors.surface,
    focusedBgColor: Color = TvTheme.colors.surfaceVariant,
    normalBorderColor: Color = TvTheme.colors.borderNormal,
    focusedBorderColor: Color = TvTheme.colors.focusBorder,
    textColor: Color = TvTheme.colors.textPrimary
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .background(normalBgColor)
            .clickable(
                // enabled=false 时不可点击也不可聚焦，D-pad 自动跳过（2026-10-04 审计新增）
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedBgColor = focusedBgColor,
                normalBgColor = normalBgColor,
                focusedBorderColor = focusedBorderColor,
                normalBorderColor = normalBorderColor
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Text(
            text = text,
            // 禁用态用次要文字色，与全局 Toast/说明文字的既定层级一致
            color = if (enabled) textColor else TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontBody
        )
    }
}
