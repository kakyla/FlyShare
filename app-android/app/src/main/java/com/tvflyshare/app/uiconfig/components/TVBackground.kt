package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 全页统一背景 — 从上到下的极淡渐变
 *
 * 为什么渐变幅度必须很小：整屏高度的色差一旦拉大（每通道 > 约 15/255），
 * 8bit 量化会把它切成一个个约 14px 的色阶带，电视面板上会看到横穿屏幕的接缝，
 * 容易被误认为屏幕硬件有问题。颜色取值规则见 TvTheme.bgGradientTop 处注释。
 *
 * 不在此处统一加 statusBarsPadding/navigationBarsPadding：
 * 各页面已有自己的固定 padding 设计（如 HomeScreen top=48dp），硬加系统栏 padding 会导致内容偏下。
 * edge-to-edge 只让背景色延伸到系统栏区域消除黑边，内容 padding 交给各页面自己控制。
 */
@Composable
fun TVBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(TvTheme.colors.bgGradientTop, TvTheme.colors.bgGradientBottom)
                )
            )
    ) {
        content()
    }
}
