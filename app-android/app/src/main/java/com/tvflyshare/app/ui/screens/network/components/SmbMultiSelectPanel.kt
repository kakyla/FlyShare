package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import com.tvflyshare.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.tvflyshare.app.ui.screens.network.SmbAction

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmbMultiSelectPanel(
    selectedCount: Int,
    totalCount: Int,
    hasFolderSelected: Boolean,
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
        add(TvMenuItem(stringResource(R.string.action_select_all), Icons.Default.CheckCircle, TvMenuItemStyle.DEFAULT, enabled = selectedCount < totalCount, action = onSelectAll))
        add(TvMenuItem(stringResource(R.string.action_copy), Icons.Default.ContentCopy, TvMenuItemStyle.DEFAULT, enabled = selectedCount > 0, action = onCopy))
        add(TvMenuItem(stringResource(R.string.action_move), Icons.AutoMirrored.Filled.DriveFileMove, TvMenuItemStyle.DEFAULT, enabled = selectedCount > 0, action = onMove))
        add(TvMenuItem(stringResource(R.string.action_download), Icons.Default.Download, TvMenuItemStyle.DEFAULT, enabled = selectedCount > 0, action = onDownload))
        if (hasFolderSelected) {
            add(TvMenuItem(stringResource(R.string.action_bookmark), Icons.Default.Star, TvMenuItemStyle.DEFAULT, enabled = selectedCount > 0, action = onBookmark))
        }
        // 2026-10-04 审计 P2 修复：删除语义标 DANGER 红色样式
        add(TvMenuItem(stringResource(R.string.action_delete), Icons.Default.Delete, enabled = selectedCount > 0, action = onDelete))
        add(TvMenuItem(stringResource(R.string.btn_cancel), Icons.Default.Close, TvMenuItemStyle.DEFAULT, enabled = true, action = onCancel))
    }

    TvSideMenu(
        title = stringResource(R.string.smb_selected_count, selectedCount, totalCount),
        subtitle = "",
        items = items,
        // 2026-10-04 审计 P2 修复：透传 onClose 让 TvSideMenu 注册 BackHandler（返回键关闭侧栏）
        onClose = onClose ?: onCancel,
        modifier = modifier
    )
}