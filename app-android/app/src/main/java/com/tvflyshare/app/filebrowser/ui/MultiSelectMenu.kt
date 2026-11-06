package com.tvflyshare.app.filebrowser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.filebrowser.model.BrowserCapabilities
import com.tvflyshare.app.uiconfig.components.TvMenuItem
import com.tvflyshare.app.uiconfig.components.TvSideMenu

/**
 * 统一的多选操作面板。
 *
 * 2026-10-30 重构：替代本地 `getMultiSelectActionMenuItems`、`SmbMultiSelectPanel`、
 * FTP 页内联多选菜单、互传页内联多选菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiSelectMenu(
    selectedCount: Int,
    totalCount: Int,
    hasFolderSelected: Boolean,
    capabilities: BrowserCapabilities,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val items = buildList {
        add(
            TvMenuItem(
                label = stringResource(R.string.file_manager_action_select_all),
                icon = Icons.Default.CheckCircle,
                enabled = selectedCount < totalCount,
                action = onSelectAll,
            )
        )
        if (capabilities.copy) {
            add(
                TvMenuItem(
                    label = stringResource(R.string.file_manager_action_copy),
                    icon = Icons.Default.ContentCopy,
                    enabled = selectedCount > 0,
                    action = onCopy,
                )
            )
        }
        if (capabilities.move) {
            add(
                TvMenuItem(
                    label = stringResource(R.string.file_manager_action_move),
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    enabled = selectedCount > 0,
                    action = onMove,
                )
            )
        }
        if (capabilities.download) {
            add(
                TvMenuItem(
                    label = stringResource(R.string.smb_action_download),
                    icon = Icons.Default.Download,
                    enabled = selectedCount > 0,
                    action = onDownload,
                )
            )
        }
        if (capabilities.bookmark && hasFolderSelected) {
            add(
                TvMenuItem(
                    label = stringResource(R.string.file_manager_action_bookmark),
                    icon = Icons.Default.Star,
                    enabled = selectedCount > 0,
                    action = onBookmark,
                )
            )
        }
        if (capabilities.delete) {
            add(
                TvMenuItem(
                    label = stringResource(R.string.file_manager_action_delete),
                    icon = Icons.Default.Delete,
                    enabled = selectedCount > 0,
                    action = onDelete,
                )
            )
        }
        add(
            TvMenuItem(
                label = stringResource(R.string.file_manager_action_cancel),
                icon = Icons.Default.Close,
                action = onCancel,
            )
        )
    }

    TvSideMenu(
        title = stringResource(R.string.smb_multi_select_title),
        subtitle = stringResource(R.string.file_manager_items_selected_total, selectedCount, totalCount),
        items = items,
        onClose = onClose ?: onCancel,
        modifier = modifier,
    )
}
