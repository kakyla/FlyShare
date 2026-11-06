package com.tvflyshare.app.ui.screens.preview

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.PreviewSupport
import com.tvflyshare.app.util.TextPreview
import kotlinx.coroutines.launch
import java.io.File

/**
 * 文本预览：全屏滚动阅读。
 *
 * 用 LazyColumn 逐行渲染而不是把整段文本塞进一个 Text：
 * 一个上万字的长文本作为单个 Text 会触发整段文字测量与排版，电视芯片上会明显卡顿，
 * 逐行懒加载只测量可见的那几十行。
 *
 * 超长单行在 [PreviewSupport] 里已按 500 字符硬切，因此这里不会出现「一行一万字」。
 */
@Composable
internal fun TextPreviewContent(path: String, onBack: () -> Unit) {
    var result by remember(path) { mutableStateOf<Result<TextPreview>?>(null) }

    LaunchedEffect(path) {
        result = PreviewSupport.readText(File(path))
    }

    val preview = result?.getOrNull()
    val errorMessage = result?.exceptionOrNull()?.message
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 一次滚 3 行：行高取正文行高，行距取列表实际使用的间距，滚动手感才和视觉一致
    val lineStep = with(density) {
        (TvTheme.dimens.lineHeightBody.toPx() + TvTheme.dimens.spacingXs.toPx()) * 3f
    }

    val hintText = stringResource(id = R.string.preview_hint_text)
    val truncatedText = stringResource(id = R.string.preview_truncated)
    val bottomHint = if (preview?.truncated == true) "$truncatedText　　$hintText" else hintText

    val metaText = if (preview != null) {
        stringResource(
            id = R.string.preview_text_meta,
            preview.lines.size,
            preview.charsetName,
            FileVisualUtils.formatFileSize(preview.totalBytes),
        )
    } else {
        ""
    }

    PreviewScaffold(
        onBack = onBack,
        topBar = {
            Text(
                text = File(path).name,
                modifier = Modifier.weight(1f, fill = false),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaText.isNotEmpty()) {
                Text(
                    text = metaText,
                    modifier = Modifier.padding(start = TvTheme.dimens.spacingMd),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontCaption,
                )
            }
        },
        bottomHint = bottomHint,
        onKeyPress = { key ->
            when (key) {
                Key.DirectionUp -> {
                    coroutineScope.launch { listState.scrollBy(-lineStep) }
                    true
                }
                Key.DirectionDown -> {
                    coroutineScope.launch { listState.scrollBy(lineStep) }
                    true
                }
                // 遥控器没有翻页键，左右键做「跳一段」用
                Key.DirectionLeft -> {
                    coroutineScope.launch { listState.scrollBy(-lineStep * 5f) }
                    true
                }
                Key.DirectionRight -> {
                    coroutineScope.launch { listState.scrollBy(lineStep * 5f) }
                    true
                }
                else -> false
            }
        },
    ) {
        when {
            result == null -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(TvTheme.dimens.progressIndicatorSize),
                color = TvTheme.colors.textPrimary,
            )

            errorMessage != null -> Text(
                text = stringResource(id = R.string.preview_error, errorMessage),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = TvTheme.dimens.spacing2Xl),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontBody,
                textAlign = TextAlign.Center,
            )

            preview != null -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = TvTheme.dimens.spacing2Xl,
                    end = TvTheme.dimens.spacing2Xl,
                    // 上下留出浮层的高度，避免首行/末行被信息条压住
                    top = TvTheme.dimens.spacing3Xl,
                    bottom = TvTheme.dimens.spacing3Xl,
                ),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs),
            ) {
                items(preview.lines) { line ->
                    Text(
                        text = line.ifEmpty { " " },
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontCaption,
                        lineHeight = TvTheme.dimens.lineHeightBody,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
