package com.tvflyshare.app.filebrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.filebrowser.model.FileItem
import com.tvflyshare.app.uiconfig.components.TvFileListItem
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.ThumbnailLoader
import com.tvflyshare.app.util.TimeUtils

/**
 * 统一文件列表行。
 *
 * 2026-10-30 重构：替代此前四套各自实现的条目行
 * （本地 FileRow、`SmbFileItemRow`、FTP 页私有 `FtpFileItemRow`、互传页私有 `RemoteFileItem`）。
 *
 * 外观基准 = 本地文件管理器（用户 2026-10-30 拍板）：直接复用 `TvFileListItem`，
 * 也就是本地页原本用的那个组件，字号与内边距与迁移前完全一致。
 */
@Composable
fun FileItemRow(
    item: FileItem,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = FileVisualUtils.getFileVisual(item.name, item.isDirectory)
    // 本地图片 / 视频：列表行图标位也出缩略图（复用 ThumbnailBox，解码中不卡 UI、失败回退图标）；
    // 仅本地文件管理器使用本组件，SMB / FTP / 互传 各自有独立行组件，不会走到这里。
    val showThumb = !item.isDirectory &&
        (ThumbnailLoader.isImageEntry(item.name) || ThumbnailLoader.isVideoEntry(item.name))
    TvFileListItem(
        modifier = modifier,
        icon = visual.icon,
        iconTint = visual.tint,
        iconSize = TvTheme.dimens.iconSizeXl,
        title = item.name,
        subtitle = fileItemSubtitle(item),
        isSelected = isSelected,
        isMultiSelectMode = isMultiSelectMode,
        onClick = onClick,
        onLongClick = onLongClick,
        iconSlot = if (showThumb) {
            { ThumbnailBox(item = item, boxSize = TvTheme.dimens.iconSizeXl) }
        } else null,
    )
}

/**
 * 条目副标题（本地口径）：
 * - 目录：来源统计过子项数就显示"N 项"（0 也显示 0 项），未统计则显示"目录"
 * - 文件：`大小  •  完整修改时间（yyyy/MM/dd HH:mm）`
 *
 * 迁移前本地页用 `FileScanner` 预格式化的 sizeText/dateText，格式与本函数一致；
 * 统一后不再需要在条目里存两份预格式化字符串。
 */
@Composable
fun fileItemSubtitle(item: FileItem): String {
    if (item.isDirectory) {
        val count = item.childCount
        return if (count != null) {
            stringResource(R.string.file_manager_items_count, count)
        } else {
            stringResource(R.string.smb_type_dir_label)
        }
    }
    val sizeText = FileVisualUtils.formatFileSize(item.size)
    // 本地基准：迁移前本地列表显示完整日期（yyyy/MM/dd HH:mm），此处保持一致
    val timeText = TimeUtils.formatTime(item.lastModified, withYear = true)
    return if (timeText.isEmpty()) {
        sizeText
    } else {
        stringResource(R.string.file_manager_size_date, sizeText, timeText)
    }
}
