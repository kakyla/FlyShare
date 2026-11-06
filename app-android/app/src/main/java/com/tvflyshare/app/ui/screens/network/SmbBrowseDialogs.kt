package com.tvflyshare.app.ui.screens.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.smb.SmbFileEntry
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.components.TvTextField
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.ui.viewmodel.SmbBrowseViewModel
import com.tvflyshare.app.ui.viewmodel.SmbUiEvent
import com.tvflyshare.app.util.FileVisualUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SmbCreateFolderDialog(viewModel: SmbBrowseViewModel) {
    var folderName by remember { mutableStateOf("") }
    TvAlertDialog(
        title = stringResource(id = R.string.smb_new_folder_title),
        onDismiss = { viewModel.onEvent(SmbUiEvent.ShowCreateFolderDialog(false)) },
        confirmText = stringResource(id = R.string.smb_btn_confirm),
        dismissText = stringResource(id = R.string.smb_btn_cancel),
        onConfirm = {
            viewModel.onEvent(SmbUiEvent.ConfirmCreateFolder(folderName))
        },
        content = {
            TvTextField(
                value = folderName,
                onValueChange = { folderName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
internal fun SmbDetailsDialog(
    target: SmbFileEntry,
    host: String,
    shareName: String,
    onDismiss: () -> Unit,
) {
    TvAlertDialog(
        title = stringResource(id = R.string.smb_details_title),
        content = {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            Column(verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)) {
                Text(text = stringResource(id = R.string.smb_details_name, target.name), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                Text(text = stringResource(id = R.string.smb_details_size, if (target.isDirectory) stringResource(id = R.string.smb_details_type_dir) else FileVisualUtils.formatFileSize(target.size)), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                Text(text = stringResource(id = R.string.smb_details_modified, dateFormat.format(Date(target.lastModified))), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                Text(text = stringResource(id = R.string.smb_details_path, host, shareName, target.path), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
            }
        },
        confirmText = stringResource(id = R.string.smb_btn_close),
        dismissText = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss
    )
}

@Composable
internal fun SmbDeleteConfirmDialog(
    pendingDelete: List<SmbFileEntry>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isMulti = pendingDelete.size > 1
    TvConfirmDialog(
        title = if (isMulti) stringResource(id = R.string.smb_delete_multi_title, pendingDelete.size) else stringResource(id = R.string.smb_delete_single_title),
        message = if (isMulti) {
            pendingDelete.joinToString("\n") { "• ${it.name}" }
        } else {
            stringResource(id = R.string.smb_delete_msg, pendingDelete.firstOrNull()?.name ?: "")
        },
        isDanger = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
