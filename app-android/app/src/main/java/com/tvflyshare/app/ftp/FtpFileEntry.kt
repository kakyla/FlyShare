package com.tvflyshare.app.ftp

import com.tvflyshare.app.smb.SmbFileType

/**
 * FTP 文件数据模型
 *
 * 表示 FTP 服务器上的一个文件或目录。路径使用 `/` 分隔（与 SMB 的 `\` 不同）。
 */
data class FtpFileEntry(
    val name: String,              // 文件/目录名称
    val path: String,             // 完整路径（如 /Movies/video.mp4，根目录条目为 "/"）
    val isDirectory: Boolean,      // 是否为目录
    val size: Long = 0L,          // 文件大小（字节），目录为 0
    val lastModified: Long = 0L,  // 最后修改时间（毫秒时间戳）
) {
    /** 文件扩展名（小写，不含点） */
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast(".", "").lowercase()

    /** 文件类型分类（复用 SMB 的分类枚举） */
    val fileType: SmbFileType
        get() = SmbFileType.fromExtension(extension)
}
