package com.tvflyshare.app.filebrowser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.filebrowser.model.FileItem
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.components.TvTextField
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.TimeUtils

/**
 * 重命名弹窗——四套合并成一套。
 *
 * 迁移前：本地 `RenameDialog`（自绘 BasicTextField）、`SmbRenameDialog`、FTP 页内联、互传页内联。
 * 文案与按钮取本地口径；输入控件统一为 [TvTextField]。
 */
@Composable
fun FileRenameDialog(
    item: FileItem,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember(item.path) { mutableStateOf(item.name) }
    TvAlertDialog(
        title = stringResource(id = R.string.file_manager_rename_title),
        text = stringResource(id = R.string.file_manager_rename_hint),
        confirmText = stringResource(id = R.string.file_manager_btn_confirm),
        dismissText = stringResource(id = R.string.file_manager_action_cancel),
        onConfirm = {
            val trimmed = newName.trim()
            if (trimmed.isNotEmpty() && trimmed != item.name) onConfirm(trimmed) else onDismiss()
        },
        onDismiss = onDismiss,
        content = {
            TvTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

/** 新建文件夹弹窗——四套合并成一套（本地口径：默认预填"新建文件夹"） */
@Composable
fun FileCreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var folderName by remember { mutableStateOf("") }
    val defaultName = stringResource(id = R.string.file_manager_new_folder_default)
    TvAlertDialog(
        title = stringResource(id = R.string.file_manager_new_folder_title),
        text = stringResource(id = R.string.file_manager_new_folder_hint),
        confirmText = stringResource(id = R.string.file_manager_btn_confirm),
        dismissText = stringResource(id = R.string.file_manager_action_cancel),
        onConfirm = {
            val name = folderName.ifBlank { defaultName }
            onConfirm(name.trim())
        },
        onDismiss = onDismiss,
        content = {
            TvTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = { Text(text = defaultName, color = TvTheme.colors.textTertiary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

/**
 * 文件详情弹窗——三套合并成一套。
 *
 * @param pathLabel 完整路径展示文案（各来源自己拼：本地绝对路径、smb://主机/共享/路径、ftp://主机:端口/路径）
 */
@Composable
fun FileDetailsDialog(
    item: FileItem,
    pathLabel: String,
    onDismiss: () -> Unit,
) {
    TvAlertDialog(
        title = stringResource(id = R.string.smb_file_details),
        confirmText = stringResource(id = R.string.app_uninstall_btn_close),
        dismissText = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)) {
                Text(
                    text = stringResource(id = R.string.smb_file_name, item.name),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontBody,
                )
                Text(
                    text = stringResource(
                        id = R.string.smb_file_size,
                        if (item.isDirectory) {
                            stringResource(id = R.string.smb_details_type_dir)
                        } else {
                            FileVisualUtils.formatFileSize(item.size)
                        }
                    ),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontBody,
                )
                Text(
                    text = stringResource(
                        id = R.string.smb_file_modified,
                        TimeUtils.formatTime(item.lastModified).ifEmpty { "-" }
                    ),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontBody,
                )
                Text(
                    text = stringResource(id = R.string.smb_file_path, pathLabel),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontBody,
                )
            }
        }
    )
}

/**
 * 删除确认——三到四套合并成一套。
 *
 * 单个/批量的文案自动切换；高危场景（存储根目录、Android/data、应用外置目录）
 * 由调用方通过 [titleOverride] / [messageOverride] 传入更严厉的提示，
 * 因为"什么算高危"是各来源自己的策略，不属于共用组件。
 */
@Composable
fun FileDeleteConfirmDialog(
    items: List<FileItem>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    titleOverride: String? = null,
    messageOverride: String? = null,
    isDanger: Boolean = false,
) {
    if (items.isEmpty()) return
    val isMulti = items.size > 1
    // 批量拼接在非组合上下文里做，不能用 stringResource（@Composable 只能在其自身作用域调用）
    val context = LocalContext.current
    val title = titleOverride ?: stringResource(id = R.string.title_delete_confirm)
    val message = messageOverride ?: if (isMulti) {
        items.joinToString("\n") { context.getString(R.string.msg_confirm_delete_single, it.name) }
    } else {
        context.getString(R.string.msg_confirm_delete_single, items.first().name)
    }
    TvConfirmDialog(
        title = title,
        message = message,
        confirmText = stringResource(id = R.string.file_manager_action_delete),
        cancelText = stringResource(id = R.string.file_manager_action_cancel),
        isDanger = isDanger,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
