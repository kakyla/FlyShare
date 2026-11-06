package com.tvflyshare.app.filebrowser.model

/**
 * 统一文件条目模型。
 *
 * 2026-10-30 重构（C 档第一步）：此前本地/SMB/FTP/互传/发送框各自定义了一份条目类型
 * （FileEntry、SmbFileEntry、FtpFileEntry、RemoteFileEntry、LocalFileEntry），
 * 其中 SmbFileEntry 与 FtpFileEntry 的字段与计算属性逐字相同。
 * 从此所有浏览来源统一产出 [FileItem]，页面与组件只认这一种类型。
 *
 * @param name 文件/目录名称（不含路径）
 * @param path 来源内部的完整路径（本地为绝对路径，SMB 为 \share\dir，FTP 为 /dir，对端设备为相对共享根）
 * @param isDirectory 是否为目录
 * @param size 字节数；目录为 0
 * @param lastModified 毫秒时间戳；0 表示未知
 * @param childCount 目录的直接子项数量；null 表示该来源未统计（如 SMB/FTP），0 表示空目录
 */
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val childCount: Int? = null,
) {
    /** 小写扩展名（不含点）；目录为空串 */
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    /** 是否为安装包 */
    val isApk: Boolean
        get() = !isDirectory && extension == "apk"

    /** 是否为隐藏项（以点开头） */
    val isHidden: Boolean
        get() = name.startsWith(".")
}
