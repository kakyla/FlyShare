package com.tvflyshare.app.filebrowser.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.filebrowser.model.BrowserCapabilities
import com.tvflyshare.app.filebrowser.model.FileItem
import com.tvflyshare.app.uiconfig.components.TvMenuItem
import com.tvflyshare.app.uiconfig.components.TvMenuItemStyle

/**
 * 单文件操作的回调集合。页面只需填自己真正支持的项，未填的项不会出现在菜单里。
 */
data class FileActionCallbacks(
    val onOpen: (FileItem) -> Unit = {},
    val onInstall: (FileItem) -> Unit = {},
    val onRename: (FileItem) -> Unit = {},
    val onCopy: (FileItem) -> Unit = {},
    val onMove: (FileItem) -> Unit = {},
    val onDownload: (FileItem) -> Unit = {},
    val onBookmark: (FileItem) -> Unit = {},
    val onDetails: (FileItem) -> Unit = {},
    val onDelete: (FileItem) -> Unit = {},
)

/**
 * 统一的单文件操作菜单（右侧侧栏）。
 *
 * 2026-10-30 重构：替代四套实现——本地 `FileManagerActions.getSingleFileActionMenuItems`、
 * `SmbActionPanel`、FTP 页内联菜单、互传页内联菜单。
 * 动作集合由 [BrowserCapabilities] 决定，页面不再各自写 if 判断。
 */
@Composable
fun buildFileActionMenuItems(
    item: FileItem,
    capabilities: BrowserCapabilities,
    callbacks: FileActionCallbacks,
    dismissActionMenu: () -> Unit = {},
): List<TvMenuItem> = buildList {
    if (item.isApk && capabilities.install) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_install),
                icon = Icons.Default.CheckCircle,
                style = TvMenuItemStyle.SUCCESS,
                action = { dismissActionMenu(); callbacks.onInstall(item) },
            )
        )
    } else if (!item.isDirectory && capabilities.open) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_open),
                icon = Icons.Default.Description,
                action = { dismissActionMenu(); callbacks.onOpen(item) },
            )
        )
    }
    if (capabilities.rename) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_rename),
                icon = Icons.Default.Edit,
                action = { dismissActionMenu(); callbacks.onRename(item) },
            )
        )
    }
    if (capabilities.copy) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_copy),
                icon = Icons.Default.ContentCopy,
                action = { dismissActionMenu(); callbacks.onCopy(item) },
            )
        )
    }
    if (capabilities.move) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_move),
                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                action = { dismissActionMenu(); callbacks.onMove(item) },
            )
        )
    }
    if (capabilities.download) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.smb_action_download),
                icon = Icons.Default.Download,
                action = { dismissActionMenu(); callbacks.onDownload(item) },
            )
        )
    }
    if (capabilities.bookmark && item.isDirectory) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_bookmark),
                icon = Icons.Default.Star,
                action = { dismissActionMenu(); callbacks.onBookmark(item) },
            )
        )
    }
    add(
        TvMenuItem(
            label = stringResource(id = R.string.file_manager_action_details),
                icon = Icons.Default.Info,
                action = { dismissActionMenu(); callbacks.onDetails(item) },
        )
    )
    if (capabilities.delete) {
        add(
            TvMenuItem(
                label = stringResource(id = R.string.file_manager_action_delete),
                icon = Icons.Default.Delete,
                action = { dismissActionMenu(); callbacks.onDelete(item) },
            )
        )
    }
}
