package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 收藏夹选择弹窗（本机 / SMB 共用）。
 *
 * 用在「复制/移动」的目标目录选择界面：点顶栏「★ 收藏」后弹出本列表，
 * 选中某一项即把选择器的当前目录跳到该收藏路径，再由用户点「复制/移动到这里」确认。
 *
 * 数据由调用方传入（本机传 getLocalBookmarks、SMB 传 getSmbBookmarks），
 * 本组件不负责持久化，保持与现有收藏存储单一来源。
 *
 * 2026-10-30 修复：此前自带一套 BasicAlertDialog + 全宽「取消」按钮的骨架，
 * 宽度贴满屏幕、按钮像一条横杠，跟应用里其他弹窗（详情/删除/连接）不是一套尺寸。
 * 改为直接复用 [TvAlertDialog]：宽高上限、圆角、内边距、按钮排布全部与全局一致，
 * 列表区单独限制高度并可滚动，收藏很多时也不会撑破弹窗。
 */
@Composable
fun BookmarkPickerDialog(
    bookmarks: List<Pair<String, String>>,
    onPick: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 列表区最多占 45% 屏高，超出由 LazyColumn 滚动，避免长收藏撑破弹窗
    val listMaxHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.45f).dp }

    TvAlertDialog(
        title = stringResource(id = R.string.file_manager_bookmark_view),
        confirmText = stringResource(id = R.string.btn_cancel),
        dismissText = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        content = {
            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvTheme.dimens.listItemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(id = R.string.file_manager_empty_bookmarks),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontBody,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs),
                ) {
                    items(bookmarks, key = { it.second }) { bookmark ->
                        TvFileListItem(
                            icon = Icons.Default.Star,
                            iconTint = TvTheme.colors.brandPrimary,
                            title = bookmark.first,
                            subtitle = bookmark.second,
                            onClick = { onPick(bookmark.second) },
                        )
                    }
                }
            }
        }
    )
}
