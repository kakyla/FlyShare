package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
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
import com.tvflyshare.app.ui.screens.network.SmbAction
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmbActionPanel(
    entry: SmbFileEntry,
    onAction: (SmbAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDir = entry.isDirectory
    val fileExtension = entry.name.substringAfterLast('.', "").lowercase()
    
    val items = buildList {
        if (!isDir && fileExtension != "apk") {
            add(TvMenuItem(stringResource(id = R.string.smb_action_open), Icons.Default.Description, TvMenuItemStyle.DEFAULT, action = { onAction(SmbAction.OPEN) }))
        } else if (fileExtension == "apk") {
            add(TvMenuItem(stringResource(id = R.string.smb_action_install), Icons.Default.CheckCircle, TvMenuItemStyle.SUCCESS, action = { onAction(SmbAction.INSTALL) }))
        }
        add(TvMenuItem(stringResource(id = R.string.smb_action_rename), Icons.Default.Edit, TvMenuItemStyle.DEFAULT, action = { onAction(SmbAction.RENAME) }))
        add(TvMenuItem(stringResource(id = R.string.smb_action_copy), Icons.Default.ContentCopy, TvMenuItemStyle.DEFAULT, action = { onAction(SmbAction.COPY) }))
        add(TvMenuItem(stringResource(id = R.string.smb_action_download), Icons.Default.Download, TvMenuItemStyle.DEFAULT, action = { onAction(SmbAction.DOWNLOAD) }))
        if (isDir) {
            add(TvMenuItem(stringResource(id = R.string.smb_action_bookmark), Icons.Default.Star, TvMenuItemStyle.DEFAULT, action = { onAction(SmbAction.BOOKMARK) }))
        }
        add(TvMenuItem(stringResource(id = R.string.smb_action_details), Icons.Default.Info, TvMenuItemStyle.DEFAULT, action = { onAction(SmbAction.DETAILS) }))
        // 2026-10-04 审计 P2 修复：删除语义标 DANGER 红色样式（TvSideMenu 支持该样式）
        add(TvMenuItem(stringResource(id = R.string.smb_action_delete), Icons.Default.Delete, action = { onAction(SmbAction.DELETE) }))
    }

    TvSideMenu(
        title = entry.name,
        subtitle = "",
        items = items,
        // 2026-10-04 审计 P2 修复：onClose 原先被丢弃，TvSideMenu 收不到就不注册 BackHandler，
        // 返回键关闭侧栏依赖屏幕级兜底——透传恢复既定范式（对齐 RemoteBrowse/FtpBrowse）
        onClose = onClose,
        modifier = modifier
    )
}