package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.ui.viewmodel.TransferFileItem
import com.tvflyshare.app.ui.viewmodel.UploadingTransferItem
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.FileVisualUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tvflyshare.app.util.TimeUtils

@Composable
fun TransferFileItemRow(
    file: TransferFileItem,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: (TransferFileItem) -> Unit,
    onDelete: () -> Unit,
) {
    val isApk = FileActionUtils.isApkFile(file.name)
    val rowInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isMultiSelectMode) {
                    Modifier.tvFocusStyle(
                        interactionSource = rowInteraction,
                        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                        normalBgColor = TvTheme.colors.surface,
                        focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                        borderWidth = TvTheme.dimens.borderFocused,
                        focusedBorderColor = TvTheme.colors.brandPrimary
                    )
                } else {
                    Modifier.background(TvTheme.colors.surface, RoundedCornerShape(TvTheme.dimens.radiusMd))
                }
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .then(
                if (isMultiSelectMode) {
                    Modifier.clickable(
                        interactionSource = rowInteraction,
                        indication = null
                    ) { onToggleSelect() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧：多选框 + 图标 + 文件名 + 大小/时间
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelectMode) {
                TvCheckbox(
                    checked = isSelected,
                    size = TvTheme.dimens.iconSizeMd,
                    iconSize = TvTheme.dimens.iconSizeSm,
                    shapeType = "circle",
                    modifier = Modifier.padding(end = TvTheme.dimens.spacingSm)
                )
            }
            
            val (fileIcon, fileTint) = if (file.isDirectory) {
                Icons.Default.Folder to TvTheme.colors.brandPrimary
            } else {
                val v = FileVisualUtils.getFileVisual(file.name, false)
                v.icon to v.tint
            }
            Icon(
                imageVector = fileIcon,
                contentDescription = null,
                tint = fileTint,
                modifier = Modifier
                    .padding(end = TvTheme.dimens.spacingSm)
                    .size(TvTheme.dimens.iconSizeLg)
            )
            
            Column {
                Text(
                    text = file.name,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontBody,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
                Text(
                    text = "${FileVisualUtils.formatFileSize(file.size)}  ${TimeUtils.formatTime(file.lastModified, withYear = false)}",
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall,
                )
            }
        }

        // 右侧：操作按钮（各自 focusable，遥控器直接选中）
        if (!isMultiSelectMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 打开/安装
                val openInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .tvFocusStyle(
                            interactionSource = openInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                            normalBgColor = TvTheme.colors.brandSuccess.copy(alpha = 0.85f),
                            focusedBgColor = TvTheme.colors.brandSuccess,
                            borderWidth = TvTheme.dimens.borderFocused
                        )
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                        .clickable(
                            interactionSource = openInteraction,
                            indication = null
                        ) { onOpen(file) }
                        .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (file.isDirectory) stringResource(id = R.string.action_enter)
                               else if (isApk) stringResource(id = R.string.action_install) else stringResource(id = R.string.action_open),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 删除
                val deleteInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .tvFocusStyle(
                            interactionSource = deleteInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                            normalBgColor = TvTheme.colors.brandError.copy(alpha = 0.85f),
                            focusedBgColor = TvTheme.colors.brandError,
                            borderWidth = TvTheme.dimens.borderFocused
                        )
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                        .clickable(
                            interactionSource = deleteInteraction,
                            indication = null
                        ) { onDelete() }
                        .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(id = R.string.action_delete),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * 正在上传中的文件行：实时展示在接收列表顶部，带不确定进度条。
 * 仅展示，不可打开/删除（上传尚未完成）。
 */
@Composable
fun TransferFileUploadingRow(item: UploadingTransferItem) {
    val (fileIcon, fileTint) = FileVisualUtils.getFileVisual(item.name, false)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvTheme.colors.surface, RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = fileIcon,
            contentDescription = null,
            tint = fileTint,
            modifier = Modifier
                .padding(end = TvTheme.dimens.spacingSm)
                .size(TvTheme.dimens.iconSizeLg)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontBody,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
            // 实时进度：received / size（分子分母同为请求体长度口径，夹取到 [0,1] 防止异常值越界）
            val progress = if (item.size > 0) (item.received.toFloat() / item.size).coerceIn(0f, 1f) else 0f
            val percent = (progress * 100).toInt()
            val statusText = when {
                item.isSaving -> stringResource(id = R.string.transfer_status_saving)
                item.size <= 0 -> stringResource(id = R.string.transfer_status_uploading)
                item.bytesPerSecond > 0 -> "${
                    FileVisualUtils.formatFileSize(item.received)
                } / ${FileVisualUtils.formatFileSize(item.size)}  •  $percent%  •  ${
                    stringResource(id = R.string.transfer_speed_suffix, FileVisualUtils.formatFileSize(item.bytesPerSecond))
                }"
                else -> "${FileVisualUtils.formatFileSize(item.received)} / ${FileVisualUtils.formatFileSize(item.size)}  •  $percent%"
            }
            Text(
                text = statusText,
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontSmall,
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
            LinearProgressIndicator(
                progress = { progress },
                color = TvTheme.colors.brandPrimary,
                trackColor = TvTheme.colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}


