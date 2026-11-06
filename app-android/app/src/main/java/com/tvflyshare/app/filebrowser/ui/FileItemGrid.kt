package com.tvflyshare.app.filebrowser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import com.tvflyshare.app.filebrowser.model.FileItem
import com.tvflyshare.app.uiconfig.components.TvCheckbox
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.ThumbnailLoader

/**
 * 统一文件网格项。
 *
 * 2026-10-30 重构：替代此前的 `SmbFileItemGrid`、`FtpFileItemGrid` 与本地 `FileGridItem` 三份实现。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemGrid(
    item: FileItem,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val visual = FileVisualUtils.getFileVisual(item.name, item.isDirectory)
    // 本地图片 / 视频：磁贴图标位改显缩略图（解码中不卡 UI，失败回退图标）；其余类型仍用通用图标。
    // 注意：本组件仅本地文件管理器 / 分类页使用，SMB / FTP / 互传 各有独立网格组件，不会走到这里。
    val showThumb = !item.isDirectory &&
        (ThumbnailLoader.isImageEntry(item.name) || ThumbnailLoader.isVideoEntry(item.name))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.gridTileHeight)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                isSelected = isSelected,
                borderWidth = if (isSelected) TvTheme.dimens.borderFocused else TvTheme.dimens.borderNormal,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.1f),
                normalBgColor = TvTheme.colors.surface.copy(alpha = 0.1f),
                focusedBorderColor = TvTheme.colors.focusBorder,
                normalBorderColor = TvTheme.colors.borderNormal,
                selectedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                selectedBorderColor = TvTheme.colors.brandPrimary,
            )
    ) {
        if (isMultiSelectMode) {
            TvCheckbox(
                checked = isSelected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(TvTheme.dimens.spacingSm),
                size = TvTheme.dimens.iconSizeMd,
                iconSize = TvTheme.dimens.iconSizeSm,
                shapeType = "rounded"
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(TvTheme.dimens.spacingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showThumb) {
                ThumbnailBox(
                    item = item,
                    modifier = Modifier.size(TvTheme.dimens.gridTileIconSize),
                )
            } else {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.tint,
                    modifier = Modifier.size(TvTheme.dimens.gridTileIconSize)
                )
            }
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
            Text(
                text = item.name,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCaption,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
            Text(
                text = fileItemGridSubtitle(item),
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun fileItemGridSubtitle(item: FileItem): String {
    if (!item.isDirectory) return FileVisualUtils.formatFileSize(item.size)
    val count = item.childCount
    return if (count != null) {
        stringResource(R.string.file_manager_items_count, count)
    } else {
        stringResource(R.string.smb_type_dir_label)
    }
}
