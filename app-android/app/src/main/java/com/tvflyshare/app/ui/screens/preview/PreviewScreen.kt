package com.tvflyshare.app.ui.screens.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.PreviewMediaType
import com.tvflyshare.app.util.PreviewSupport
import java.io.File

/**
 * 内置预览页入口。
 *
 * 2026-10-30 第一期只接管文本与图片：这两类是电视上「系统里根本没人接」的格式，
 * 以前点开要么闪退要么毫无反应。音频/视频仍走系统播放器（见第二期）。
 *
 * 页面完全用遥控器操作：方向键 / OK / 返回，没有任何需要触摸的控件。
 */
@Composable
fun MediaPreviewScreen(path: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val mediaType = remember(path) { PreviewSupport.typeOf(File(path).name) }
    when (mediaType) {
        PreviewMediaType.IMAGE -> ImagePreviewContent(path = path, onBack = onBack)
        PreviewMediaType.TEXT -> TextPreviewContent(path = path, onBack = onBack)
        null -> UnsupportedPreview(onBack = onBack)
    }
}

/**
 * 预览页公共骨架：纯黑底 + 顶部信息条 + 底部操作提示 + 统一按键分发。
 *
 * 为什么要自己拿焦点：本页没有任何可聚焦控件，若不主动 requestFocus，
 * 方向键会被系统当成「移动焦点」而无人接收，遥控器就完全失灵。
 *
 * @param onKeyPress 除返回键外的按键处理，返回 true 表示已消费
 */
@Composable
internal fun PreviewScaffold(
    onBack: () -> Unit,
    topBar: @Composable RowScope.() -> Unit,
    bottomHint: String?,
    overlayVisible: Boolean = true,
    onKeyPress: (Key) -> Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 从系统弹窗、通知栏或切回本应用后，焦点可能已经不在本页了（本页没有任何可聚焦控件，
    // 一旦丢失就再也抢不回来，遥控器整页失灵）。因此每次回到前台都重新拿一次焦点。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                focusRequester.requestFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvTheme.colors.mediaBackdrop)
            .focusRequester(focusRequester)
            // onPreviewKeyEvent 放在 focusable 外层：走「预览」阶段先拿到按键，
            // 不会被焦点系统当成焦点移动先吃掉
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    // 返回键刻意不在这里处理：它由外层 BackHandler 走系统的返回分发，
                    // 若这里也调一次 onBack 就会 pop 两次，一次退回两层页面。
                    Key.Back -> false
                    Key.Escape -> {
                        if (event.type == KeyEventType.KeyDown) onBack()
                        true
                    }
                    else -> event.type == KeyEventType.KeyDown && onKeyPress(event.key)
                }
            }
            .focusable()
    ) {
        content()

        if (overlayVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(TvTheme.colors.dialogBg)
                    .padding(
                        horizontal = TvTheme.dimens.spacingLg,
                        vertical = TvTheme.dimens.spacingSm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                content = topBar,
            )

            if (bottomHint != null) {
                Text(
                    text = bottomHint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(TvTheme.colors.dialogBg)
                        .padding(
                            horizontal = TvTheme.dimens.spacingLg,
                            vertical = TvTheme.dimens.spacingSm,
                        ),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 不支持的格式（正常入口已过滤，这里兜的是路径被改过、文件已删等异常情况） */
@Composable
private fun UnsupportedPreview(onBack: () -> Unit) {
    PreviewScaffold(
        onBack = onBack,
        topBar = {},
        bottomHint = null,
        overlayVisible = false,
        onKeyPress = { false },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = TvTheme.dimens.spacing2Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
        ) {
            Text(
                text = stringResource(id = R.string.preview_unsupported_title),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
            )
            Text(
                text = stringResource(id = R.string.preview_unsupported_desc),
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontBody,
                textAlign = TextAlign.Center,
            )
        }
    }
}
