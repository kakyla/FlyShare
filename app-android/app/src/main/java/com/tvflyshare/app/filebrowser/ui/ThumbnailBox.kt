package com.tvflyshare.app.filebrowser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.tvflyshare.app.filebrowser.model.FileItem
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.ThumbnailLoader

/**
 * 文件磁贴的图标位：本地图片 / 视频尝试显示缩略图；解码中或未生成时回退到通用图标。
 *
 * 仅用于本地文件管理器（FileItemGrid）。SMB / FTP / 互传 各自有独立网格组件，不会调用这里。
 * 解码全部在后台线程，主线程不阻塞；滑出屏幕时 LaunchedEffect 取消，已加载的会命中内存缓存。
 */
@Composable
fun ThumbnailBox(
    item: FileItem,
    modifier: Modifier = Modifier,
    boxSize: Dp = TvTheme.dimens.gridTileIconSize,
) {
    val context = LocalContext.current
    val isVideo = ThumbnailLoader.isVideoEntry(item.name)

    // 以 路径+修改时间+大小 为键：文件变了自动重新生成；同一文件复用同一份 state。
    val bitmap = remember(item.path, item.lastModified, item.size) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.path, item.lastModified, item.size) {
        bitmap.value = ThumbnailLoader.loadThumbnail(
            context = context,
            path = item.path,
            lastModified = item.lastModified,
            size = item.size,
            isVideo = isVideo,
            reqSize = THUMB_REQ_PX,
        )
    }

    Box(modifier = modifier.size(boxSize), contentAlignment = Alignment.Center) {
        // 占位图标：始终作为底，解码完成后被真图覆盖（Crop 填满整个图标框）
        val visual = FileVisualUtils.getFileVisual(item.name, false)
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.tint,
            modifier = Modifier.size(boxSize * 0.55f),
        )
        bitmap.value?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(boxSize),
            )
        }
    }
}

/**
 * 缩略图目标边长（px）。磁贴图标位约 56dp，256px 已足够清晰且解码便宜；
 * 即便是弱设备，降采样到 256px 的 JPEG 解码也仅需十几毫秒。
 */
private const val THUMB_REQ_PX = 256
