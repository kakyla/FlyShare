package com.tvflyshare.app.filebrowser.model

/**
 * 文件列表排序与隐藏项过滤——四种浏览来源共用一份实现。
 *
 * 2026-10-30 重构：此前本地 / SMB / FTP / 互传各自的 ViewModel 里各有一份
 * `sortFileList` + `refreshDisplayList`（其中三份逐字相同），改排序规则要改四处。
 *
 * 规则与迁移前完全一致：目录恒在前按名称升序，文件按所选方式排序。
 */
object FileListSorter {

    /** 过滤隐藏项（以点开头）并按 [sortMode] 排序 */
    fun apply(items: List<FileItem>, sortMode: SortMode, showHidden: Boolean): List<FileItem> {
        val visible = if (showHidden) items else items.filterNot { it.isHidden }
        return applyWithoutFilter(visible, sortMode)
    }

    /** 调用方已自行完成隐藏项过滤时使用 */
    fun applyWithoutFilter(items: List<FileItem>, sortMode: SortMode): List<FileItem> {
        val dirs = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = items.filterNot { it.isDirectory }
        val sortedFiles = when (sortMode) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
            SortMode.SIZE_ASC -> files.sortedBy { it.size }
            SortMode.TYPE -> files.sortedBy { it.extension }
            SortMode.DATE_DESC -> files.sortedByDescending { it.lastModified }
        }
        return dirs + sortedFiles
    }
}
