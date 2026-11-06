package com.tvflyshare.app.smb

/**
 * SMB 文件数据模型
 *
 * 表示 SMB 共享中的一个文件或目录。
 */
data class SmbFileEntry(
    val name: String,              // 文件/目录名称
    val path: String,             // 完整路径（如 \shared\folder\video.mp4）
    val isDirectory: Boolean,      // 是否为目录
    val size: Long = 0L,          // 文件大小（字节），目录为 0
    val lastModified: Long = 0L,  // 最后修改时间（毫秒时间戳）
) {
    /** 文件扩展名（小写，不含点） */
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast(".", "").lowercase()

    /** 文件类型分类 */
    val fileType: SmbFileType
        get() = SmbFileType.fromExtension(extension)
}

/**
 * SMB 文件类型枚举
 *
 * 根据扩展名分类，决定点击文件时的操作（播放/安装/查看/下载）。
 */
enum class SmbFileType {
    VIDEO,    // 视频：mp4 mkv avi mov flv webm ts m4v
    IMAGE,    // 图片：jpg jpeg png webp gif bmp
    APK,      // 安装包：apk
    AUDIO,    // 音频：mp3 flac wav aac ogg m4a
    OTHER;    // 其他

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "flv", "webm", "ts", "m4v", "wmv", "mpg")
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff")
        private val APK_EXTS = setOf("apk")
        private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma")

        fun fromExtension(ext: String): SmbFileType {
            return when (ext.lowercase()) {
                in VIDEO_EXTS -> VIDEO
                in IMAGE_EXTS -> IMAGE
                in APK_EXTS -> APK
                in AUDIO_EXTS -> AUDIO
                else -> OTHER
            }
        }
    }
}
