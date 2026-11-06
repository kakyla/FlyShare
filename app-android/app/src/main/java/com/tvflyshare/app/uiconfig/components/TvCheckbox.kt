package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 全局统一的复选框组件
 */
@Composable
fun TvCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = TvTheme.dimens.iconSizeMd,
    iconSize: Dp = TvTheme.dimens.iconSizeSm,
    shapeType: String = "circle" // "circle" or "rounded"
) {
    val shape = if (shapeType == "rounded") RoundedCornerShape(size / 2) else CircleShape

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
        .background(TvTheme.colors.borderNormal)
        .border(
            width = TvTheme.dimens.borderNormal,
            color = if (checked) TvTheme.colors.textPrimary else TvTheme.colors.textPrimary.copy(alpha = 0.33f),
            shape = shape
        ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = if (shapeType == "rounded") Icons.Default.CheckCircle else Icons.Default.Check,
                contentDescription = "已选中",
                tint = TvTheme.colors.textPrimary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
