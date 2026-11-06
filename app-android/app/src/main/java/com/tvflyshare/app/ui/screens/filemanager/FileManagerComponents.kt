package com.tvflyshare.app.ui.screens.filemanager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.components.TvFileListItem
import com.tvflyshare.app.uiconfig.global.TvTheme

// 2026-10-30 重构：FileRow / FileGridItem 已由 filebrowser/ui 的 FileItemRow / FileItemGrid 取代
//（本地页已切到共用组件），本文件只保留"返回上级"这一行。
// 待与 network/components/ParentDirItem.kt 合并为唯一实现，届时本文件可删。
@Composable
internal fun ParentDirItem(onBack: () -> Unit) {
    TvFileListItem(
        icon = Icons.Default.FolderOpen,
        iconTint = TvTheme.colors.brandPrimary,
        title = stringResource(id = R.string.file_manager_return_up),
        onClick = onBack
    )
}
