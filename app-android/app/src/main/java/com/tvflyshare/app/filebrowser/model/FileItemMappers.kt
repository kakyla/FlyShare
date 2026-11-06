package com.tvflyshare.app.filebrowser.model

import com.tvflyshare.app.util.FileEntry

/**
 * 本地存储条目 → 统一条目。
 *
 * 本地来源目前仍由 `FileScanner` 产出 [FileEntry]（带 sizeText/dateText 两个预格式化字段）。
 * 统一后条目只保留原始数值，展示文案由 [com.tvflyshare.app.filebrowser.ui.fileItemSubtitle] 生成，
 * 格式与迁移前一致（`大小  •  MM-dd HH:mm`）。
 */
fun FileEntry.toFileItem(): FileItem = FileItem(
    name = name,
    path = path,
    isDirectory = isDirectory,
    size = size,
    lastModified = lastModified,
    childCount = childCount,
)
