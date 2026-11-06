package com.tvflyshare.app.uiconfig.components

import androidx.compose.runtime.Composable
import com.tvflyshare.app.uiconfig.global.TvTheme

import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R

@Composable
fun TvConfirmDialog(
    title: String,
    message: String,
    confirmText: String = stringResource(id = R.string.btn_confirm),
    cancelText: String = stringResource(id = R.string.btn_cancel),
    isDanger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TvAlertDialog(
        title = title,
        text = message,
        confirmText = confirmText,
        dismissText = cancelText,
        isDanger = isDanger,
        defaultFocusCancel = isDanger,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

