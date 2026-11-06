package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.components.*
import com.tvflyshare.app.smb.SmbFileEntry
import com.tvflyshare.app.ui.viewmodel.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.tvflyshare.app.ui.screens.network.SmbAction
import com.tvflyshare.app.util.FileVisualUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmbFileItemRow(
    entry: SmbFileEntry,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.listItemHeight)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onLongClick()
                    true
                } else {
                    false
                }
            }
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                isSelected = isSelected,
                normalBgColor = TvTheme.colors.surface.copy(alpha=0.1f),
                borderWidth = if (isSelected) TvTheme.dimens.borderFocused else TvTheme.dimens.borderNormal
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = TvTheme.dimens.spacingXl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .size(TvTheme.dimens.iconSizeLg)
                    .clip(RoundedCornerShape(TvTheme.dimens.iconSizeLg / 2))
                    .background(if (isSelected) TvTheme.colors.brandPrimary else TvTheme.colors.borderNormal)
                    .border(TvTheme.dimens.borderNormal, if (isSelected) TvTheme.colors.brandPrimary else TvTheme.colors.borderNormal, RoundedCornerShape(TvTheme.dimens.iconSizeLg / 2)),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = TvTheme.colors.textOnTile,
                        modifier = Modifier.size(TvTheme.dimens.checkIconSize),
                    )
                }
            }
            Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))
        }

        val fileVisual = FileVisualUtils.getFileVisual(entry.name, entry.isDirectory)

        Icon(
            imageVector = fileVisual.icon,
            contentDescription = null,
            tint = fileVisual.tint,
            modifier = Modifier.size(TvTheme.dimens.iconSizeXl),
        )

        Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) {
                    stringResource(id = R.string.smb_type_dir_label)
                } else {
                    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    val timeStr = if (entry.lastModified > 0) sdf.format(java.util.Date(entry.lastModified)) else ""
                    "${FileVisualUtils.formatFileSize(entry.size)}  •  $timeStr"
                },
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontCaption,
                maxLines = 1,
            )
        }
    }
}