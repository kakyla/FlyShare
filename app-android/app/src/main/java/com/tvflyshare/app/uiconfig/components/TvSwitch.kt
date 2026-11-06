package com.tvflyshare.app.uiconfig.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 全局统一的滑动开关组件（纯展示，交互由父级处理）。
 *
 * 开启：轨道为 brandSuccess，白色滑块滑到右侧；
 * 关闭：轨道为 selectCircleBg（与 TvCheckbox 未选中底色一致），滑块滑到左侧。
 * 滑块位置与轨道颜色均带过渡动画。
 */
@Composable
fun TvSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) TvTheme.colors.brandSuccess else TvTheme.colors.selectCircleBg,
        label = "switchTrackColor"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) {
            TvTheme.dimens.switchWidth - TvTheme.dimens.switchThumbSize - TvTheme.dimens.switchThumbPadding
        } else {
            TvTheme.dimens.switchThumbPadding
        },
        label = "switchThumbOffset"
    )

    Box(
        modifier = modifier
            .size(
                width = TvTheme.dimens.switchWidth,
                height = TvTheme.dimens.switchHeight
            )
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(TvTheme.dimens.switchThumbSize)
                .clip(CircleShape)
                .background(TvTheme.colors.textPrimary)
        )
    }
}
