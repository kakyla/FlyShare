package com.tvflyshare.app.filebrowser.model

/**
 * 目标目录选择器要执行的操作类型。
 *
 * 2026-10-30 重构：原定义在 `ui/screens/filemanager/DirPickerScreen.kt`（一个页面文件里），
 * 被本地文件管理器与 SMB 浏览页共用。归位到中立包。
 */
enum class FileOperationType {
    COPY, MOVE, DOWNLOAD,
    /** 仅选择目录（如设置里的「文件保存路径」），不执行复制/移动，确认即返回当前目录 */
    SELECT_DIR
}
