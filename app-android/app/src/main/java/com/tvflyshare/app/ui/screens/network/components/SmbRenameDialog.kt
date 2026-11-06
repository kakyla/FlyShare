package com.tvflyshare.app.ui.screens.network.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.focus.onFocusChanged
import com.tvflyshare.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.tvflyshare.app.ui.screens.network.SmbAction

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmbRenameDialog(
    originalName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputName by remember { mutableStateOf(originalName) }
    val inputInteractionSource = remember { MutableInteractionSource() }
    val inputFocusRequester = remember { FocusRequester() }
    val confirmButtonInteractionSource = remember { MutableInteractionSource() }
    val dismissButtonInteractionSource = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        try {
            inputFocusRequester.requestFocus()
        } catch (e: IllegalStateException) {
            Log.w("SmbRenameDialog", "requestFocus 跳过：焦点节点尚未挂载", e)
        }
    }

    TvAlertDialog(
        title = stringResource(id = R.string.smb_rename_title),
        text = stringResource(id = R.string.smb_rename_hint),
        confirmText = stringResource(id = R.string.smb_rename_confirm),
        dismissText = stringResource(id = R.string.smb_rename_cancel),
        confirmEnabled = inputName.isNotBlank(),
        onConfirm = { if (inputName.isNotBlank()) onConfirm(inputName.trim()) },
        onDismiss = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TvTheme.dimens.spacing2Xl)
                    .tvFocusStyle(
                        interactionSource = inputInteractionSource,
                        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                        focusedBgColor = TvTheme.colors.borderNormal.copy(alpha=0.3f),
                        normalBgColor = TvTheme.colors.borderNormal.copy(alpha=0.3f),
                        focusedBorderColor = TvTheme.colors.brandPrimary,
                        normalBorderColor = TvTheme.colors.borderNormal,
                        borderWidth = TvTheme.dimens.borderFocused,
                        focusedScale = 1f,
                        normalScale = 1f
                    )
                    .padding(horizontal = TvTheme.dimens.spacingMd),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    textStyle = TextStyle(
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontTitle,
                    ),
                    singleLine = true,
                    interactionSource = inputInteractionSource,
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester),
                )
                if (inputName.isEmpty()) {
                    Text(
                        text = originalName,
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontTitle,
                    )
                }
            }
        }
    )
}