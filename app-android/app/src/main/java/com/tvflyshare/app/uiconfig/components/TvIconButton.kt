package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tvflyshare.app.uiconfig.global.TvTheme

@Composable
fun TvIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    normalBgColor: Color = TvTheme.colors.surfaceVariant.copy(alpha = 0.3f),
    focusedBgColor: Color = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
    normalBorderColor: Color = TvTheme.colors.borderNormal,
    focusedBorderColor: Color = TvTheme.colors.focusBorder,
    iconTint: Color = TvTheme.colors.textPrimary
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    Box(
        modifier = modifier
            .size(TvTheme.dimens.iconSizeLg)
            .clickable(
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
                normalBorderColor = normalBorderColor,
                borderWidth = TvTheme.dimens.borderFocused
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(TvTheme.dimens.iconSizeSm)
        )
    }
}
