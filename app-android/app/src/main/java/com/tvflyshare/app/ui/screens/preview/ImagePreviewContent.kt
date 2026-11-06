package com.tvflyshare.app.ui.screens.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.BitmapPreview
import com.tvflyshare.app.util.PreviewSupport
import kotlinx.coroutines.delay
import java.io.File

/** 图片加载的三种状态 */
private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Loaded(val preview: BitmapPreview) : ImageLoadState
    data class Failed(val message: String) : ImageLoadState
}

/**
 * 图片预览：全屏显示 + 左右键切同目录的上一张/下一张。
 *
 * 性能上的两个关键约定：
 * 1. 解码前先 [delay] 一下——快速连按时上一次协程会在延时点被取消，
 *    不会挨个把整条目录的图都解码进内存；
 * 2. 只按屏幕尺寸降采样（不做缩略图、不做缓存），退出页面即可被回收。
 */
@Composable
internal fun ImagePreviewContent(path: String, onBack: () -> Unit) {
    // 先把自己放进去：首帧就能显示，扫描完同目录再整体替换
    var siblings by remember(path) { mutableStateOf(listOf(File(path))) }
    var index by remember(path) { mutableStateOf(0) }
    var loadState by remember(path) { mutableStateOf<ImageLoadState>(ImageLoadState.Loading) }
    var overlayVisible by remember(path) { mutableStateOf(true) }
    val genericError = stringResource(id = R.string.preview_open_failed)

    LaunchedEffect(path) {
        val file = File(path)
        val list = PreviewSupport.siblingImages(file)
        siblings = list
        index = list.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
    }

    PreviewScaffold(
        onBack = onBack,
        topBar = {
            Text(
                text = siblings.getOrNull(index)?.name.orEmpty(),
                modifier = Modifier.weight(1f, fill = false),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (siblings.size > 1) {
                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                Text(
                    text = stringResource(
                        id = R.string.preview_image_counter,
                        index + 1,
                        siblings.size,
                    ),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontCaption,
                )
            }
        },
        bottomHint = stringResource(id = R.string.preview_hint_image),
        overlayVisible = overlayVisible,
        onKeyPress = { key ->
            when (key) {
                Key.DirectionLeft -> {
                    if (index > 0) index -= 1
                    true
                }
                Key.DirectionRight -> {
                    if (index < siblings.lastIndex) index += 1
                    true
                }
                Key.DirectionCenter, Key.Enter -> {
                    overlayVisible = !overlayVisible
                    true
                }
                else -> false
            }
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 触屏左右滑动切换上一张/下一张（与遥控器左右键等效）
                    var accumulated = 0f
                    val threshold = maxOf(size.width * 0.18f, 1f)
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumulated += dragAmount
                            if (accumulated > threshold && index > 0) {
                                index -= 1
                                accumulated = 0f
                            } else if (accumulated < -threshold && index < siblings.lastIndex) {
                                index += 1
                                accumulated = 0f
                            }
                        },
                        onDragEnd = { accumulated = 0f }
                    )
                }
        ) {
            val density = LocalDensity.current
            val reqWidth = with(density) { maxWidth.roundToPx() }
            val reqHeight = with(density) { maxHeight.roundToPx() }
            val current = siblings.getOrNull(index)

            LaunchedEffect(current, reqWidth, reqHeight) {
                if (current == null || reqWidth <= 0 || reqHeight <= 0) return@LaunchedEffect
                loadState = ImageLoadState.Loading
                // 连按左右键时上一条协程在这里就被取消，只解最终停下的那一张
                delay(80)
                loadState = PreviewSupport.decodeBitmap(current, reqWidth, reqHeight).fold(
                    onSuccess = { ImageLoadState.Loaded(it) },
                    onFailure = { error ->
                        ImageLoadState.Failed(error.message ?: genericError)
                    },
                )
            }

            when (val state = loadState) {
                ImageLoadState.Loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(TvTheme.dimens.progressIndicatorSize),
                    color = TvTheme.colors.textPrimary,
                )

                is ImageLoadState.Failed -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = TvTheme.dimens.spacing2Xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                ) {
                    Text(
                        text = stringResource(id = R.string.preview_error, state.message),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontBody,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = current?.name.orEmpty(),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontCaption,
                    )
                }

                is ImageLoadState.Loaded -> Image(
                    bitmap = state.preview.bitmap.asImageBitmap(),
                    contentDescription = current?.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
