package com.tvflyshare.app.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 文件视觉解析结果
 */
data class FileVisual(
    val icon: ImageVector,
    val tint: Color
)

/**
 * 全局文件视觉解析器，统一不同页面对文件类型的图标和颜色展示
 */
object FileVisualUtils {
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        // coerceIn 兜底：异常大值（≥1024^5，MediaStore 脏数据/魔改 ROM 可能出现）不再数组越界（2026-10-04 审计 P2 修复）
        val digitGroups = ((Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()).coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    @Composable
    fun getFileVisual(fileName: String, isDirectory: Boolean): FileVisual {
        if (isDirectory) {
            return FileVisual(icon = Icons.Default.Folder, tint = TvTheme.colors.iconFolder)
        }

        val extension = fileName.substringAfterLast('.', "").lowercase()

        return when (extension) {
            "apk" -> FileVisual(icon = Icons.Default.Android, tint = TvTheme.colors.brandSuccess)
            "mp4", "mkv", "avi", "mov", "flv", "wmv" -> FileVisual(icon = Icons.Default.Movie, tint = TvTheme.colors.brandError)
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> FileVisual(icon = Icons.Default.Image, tint = TvTheme.colors.brandPrimary)
            "mp3", "wav", "flac", "aac", "ogg", "m4a" -> FileVisual(icon = Icons.Default.AudioFile, tint = TvTheme.colors.brandWarning)
            "zip", "rar", "7z", "tar", "gz" -> FileVisual(icon = Icons.Default.FolderZip, tint = TvTheme.colors.tileColor3)
            else -> FileVisual(icon = Icons.AutoMirrored.Filled.InsertDriveFile, tint = TvTheme.colors.textSecondary)
        }
    }
}
