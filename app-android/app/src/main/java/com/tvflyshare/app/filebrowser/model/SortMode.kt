package com.tvflyshare.app.filebrowser.model

import com.tvflyshare.app.R

/**
 * 文件列表排序方式。
 *
 * 2026-10-30 重构：原定义在 `ui/screens/filemanager/FileManagerEnums.kt`，
 * 却被 SMB / FTP / 互传三个 ViewModel 跨包引用（页面 A 依赖页面 B）。
 * 归位到中立包，四种来源共用同一份排序定义。
 */
enum class SortMode(val titleResId: Int) {
    NAME_ASC(R.string.file_manager_sort_name_asc),
    SIZE_DESC(R.string.file_manager_sort_size_desc),
    SIZE_ASC(R.string.file_manager_sort_size_asc),
    TYPE(R.string.file_manager_sort_type),
    DATE_DESC(R.string.file_manager_sort_date_desc),
}
