package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.WindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo

/**
 * 统一页面骨架：所有子页面（固定行页 + 列表页）共用，解决两件事：
 *
 * 1. 横竖屏（compact / isWideLayout）判定集中在唯一一处 [rememberWindowAdaptiveInfo]，
 *    通过 [content] 的 [WindowAdaptiveInfo] 参数下发给页面，避免各页重复计算、口径漂移。
 * 2. 顶栏与内容边距统一，并集中处理「固定行页面横屏内容被切掉」的问题：
 *    [scrollable]=true（默认）时 body 包一层 [verticalScroll]，内容超高可滚到全部；
 *    [scrollable]=false 时 body 不滚，由页面内部 LazyColumn 自行滚动（列表页用此档）。
 *
 * 用法：
 * - 固定行页（设置/关于/隐私/协议/检查更新）：[scrollable]=true，body 用普通 Column。
 * - 列表页：[scrollable]=false，body 内自行放 LazyColumn（[fillMaxSize]）。
 * - 顶栏：标准页用 title/showBack/onBack/rightAction 参数；自定义顶栏（如文件浏览地址栏）
 *   传 [topBar] = { ... } 覆盖。
 * - [applyBodyPadding]：是否由本组件统一加 body 外边距。固定行页用默认 true；
 *   列表页内部已自带 contentPadding，传 false 避免边距翻倍（布局零位移）。
 *
 * 注意：body 内容的横向/纵向边距在 [applyBodyPadding]=true 时由本组件统一加上，
 * 页面内部不要再重复加外层边距。
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    deviceInfo: String? = null,
    timeText: String? = null,
    rightAction: (@Composable () -> Unit)? = null,
    topBar: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    applyBodyPadding: Boolean = true,
    content: @Composable (windowInfo: WindowAdaptiveInfo) -> Unit,
) {
    val windowInfo = rememberWindowAdaptiveInfo()
    val compact = windowInfo.widthClass == WindowWidthClass.COMPACT
    Column(modifier = modifier.fillMaxSize()) {
        if (topBar != null) {
            topBar()
        } else {
            TopBar(
                title = title,
                showBack = showBack,
                onBack = onBack,
                deviceInfo = deviceInfo,
                timeText = timeText,
                rightAction = rightAction,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (applyBodyPadding) {
                        Modifier.padding(
                            horizontal = if (compact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing3Xl,
                            vertical = TvTheme.dimens.spacingLg,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        ) {
            content(windowInfo)
        }
    }
}
