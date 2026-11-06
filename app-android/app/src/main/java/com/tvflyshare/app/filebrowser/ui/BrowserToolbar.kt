package com.tvflyshare.app.filebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.R
import com.tvflyshare.app.filebrowser.model.BrowserCapabilities
import com.tvflyshare.app.filebrowser.model.SortMode
import com.tvflyshare.app.uiconfig.components.TvDropdownMenu
import com.tvflyshare.app.uiconfig.components.TvDropdownMenuItem
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 统一的浏览页顶栏：返回 · 标题 · 收藏 · 视图切换 · 更多（排序/新建文件夹/隐藏项）。
 *
 * 2026-10-30 重构：此前本地 / SMB / FTP 各写了一遍（本地那遍在自带 TopBar 里），
 * 互传那遍缺网格视图与隐藏项开关——功能不一致正是"改一处要改四处"的直接后果。
 *
 * 外观基准 = 本地文件管理器（用户拍板）：返回箭头、标题字号、右侧三个按钮的尺寸与
 * 焦点样式、多选时的"已选 N 项"字号，均照本地页原实现，迁移本地页时应零视觉差异。
 *
 * @param title 顶栏标题（本地页是分类名或当前目录名）
 * @param pathText 可选路径行；本地页不显示路径（传 null），SMB/FTP/互传 传完整路径
 * @param showActions 是否显示右侧按钮组（本地页只在"全部文件"视图显示）
 */
@Composable
fun BrowserToolbar(
    title: String,
    selectedCount: Int,
    inMultiSelect: Boolean,
    capabilities: BrowserCapabilities,
    sortMode: SortMode,
    showHiddenFiles: Boolean,
    onToggleGridView: () -> Unit,
    onToggleHidden: () -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
    pathText: String? = null,
    showActions: Boolean = true,
    onBack: (() -> Unit)? = null,
    onOpenBookmarks: (() -> Unit)? = null,
) {
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvTheme.dimens.topBarHeight)
                .padding(horizontal = TvTheme.dimens.spacingXl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.file_manager_action_cancel),
                    tint = TvTheme.colors.textPrimary,
                    modifier = Modifier
                        .size(TvTheme.dimens.iconSizeMd)
                        .clickable { onBack() }
                )
                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
            }
            Text(
                text = title,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (inMultiSelect) {
                Text(
                    text = stringResource(id = R.string.file_manager_items_selected, selectedCount),
                    color = TvTheme.colors.brandPrimary,
                    fontSize = TvTheme.dimens.fontCol2Title,
                )
            } else if (showActions) {
                if (capabilities.bookmark && onOpenBookmarks != null) {
                    ToolbarIconButton(
                        icon = Icons.Default.Star,
                        contentDescription = stringResource(id = R.string.file_manager_bookmark_view),
                        onClick = onOpenBookmarks,
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                }
                if (capabilities.gridView) {
                    ToolbarIconButton(
                        icon = Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(id = R.string.smb_toggle_view),
                        onClick = onToggleGridView,
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                }

                Box {
                    ToolbarIconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = stringResource(id = R.string.smb_more_actions),
                        onClick = { showOptionsMenu = true },
                        focusedBorderColor = TvTheme.colors.brandPrimary,
                    )
                    TvDropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                    ) {
                        TvDropdownMenuItem(
                            text = stringResource(id = R.string.file_manager_sort),
                            onClick = { showOptionsMenu = false; showSortMenu = true }
                        )
                        if (capabilities.createFolder) {
                            TvDropdownMenuItem(
                                text = stringResource(id = R.string.file_manager_new_folder),
                                onClick = { showOptionsMenu = false; onCreateFolder() }
                            )
                        }
                        if (capabilities.hiddenToggle) {
                            TvDropdownMenuItem(
                                text = if (showHiddenFiles) {
                                    "✓ " + stringResource(id = R.string.file_manager_show_hidden)
                                } else {
                                    stringResource(id = R.string.file_manager_show_hidden)
                                },
                                onClick = { showOptionsMenu = false; onToggleHidden() }
                            )
                        }
                    }
                    TvDropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        SortMode.entries.forEach { mode ->
                            TvDropdownMenuItem(
                                text = if (sortMode == mode) {
                                    "✓ ${stringResource(id = mode.titleResId)}"
                                } else {
                                    "  ${stringResource(id = mode.titleResId)}"
                                },
                                onClick = { onSetSortMode(mode); showSortMenu = false }
                            )
                        }
                    }
                }
            }
        }

        if (pathText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TvTheme.dimens.spacingXl)
                    .padding(bottom = TvTheme.dimens.spacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = TvTheme.colors.textSecondary,
                    modifier = Modifier.size(TvTheme.dimens.spacingMd),
                )
                Text(
                    text = " $pathText",
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = TvTheme.dimens.spacingXs).weight(1f),
                )
            }
        }
    }
}

/** 顶栏图标按钮——尺寸与焦点样式照本地文件管理器原有实现 */
@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    focusedBorderColor: Color = TvTheme.colors.focusBorder,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(TvTheme.dimens.iconSizeLg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                normalBgColor = TvTheme.colors.surfaceVariant.copy(alpha = 0.3f),
                focusedBorderColor = focusedBorderColor,
                normalBorderColor = TvTheme.colors.borderNormal,
                borderWidth = TvTheme.dimens.borderFocused,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TvTheme.colors.textPrimary,
            modifier = Modifier.size(TvTheme.dimens.iconSizeSm),
        )
    }
}
