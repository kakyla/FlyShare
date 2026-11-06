package com.tvflyshare.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import com.tvflyshare.app.Constants

/**
 * 文件操作工具
 *
 * 提供打开文件和安装 APK 的 Intent 构建。
 * 使用 FileProvider 提供 URI，兼容 Android 7.0+。
 */
object FileActionUtils {

    /** 判断是否为 APK 文件 */
    fun isApkFile(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".apk")
    }

    /**
     * 检查系统中是否有应用能打开该文件
     *
     * 用于在 startActivity 之前预检。没有应用能处理时调 startActivity 会启动一个
     * 立即退出的 resolver/空 Activity，把当前应用推到后台，看起来像闪退。
     * （实测 MuMu 上没有音频和视频的接收应用）
     *
     * @return true 有应用能打开
     */
    fun canOpenFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.${Constants.FILE_PROVIDER_AUTHORITY_SUFFIX}", file),
                getMimeType(file.name)
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent.resolveActivity(context.packageManager) != null
    }

    /**
     * 构建打开文件的 Intent（图片、视频、文档等）
     *
     * @return Intent 或 null（文件不存在）
     */
    fun buildOpenFileIntent(context: Context, file: File): Intent? {
        if (!file.exists()) return null

        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.${Constants.FILE_PROVIDER_AUTHORITY_SUFFIX}",
                file
            )
        } catch (e: IllegalArgumentException) {
            Log.w("FileActionUtils", "buildOpenFileIntent: getUriForFile 失败", e)
            return null
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构建安装 APK 的 Intent
     *
     * @return Intent 或 null（文件不存在或非 APK）
     */
    fun buildInstallApkIntent(context: Context, file: File): Intent? {
        if (!file.exists() || !isApkFile(file.name)) return null

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.${Constants.FILE_PROVIDER_AUTHORITY_SUFFIX}", file)
            } catch (e: IllegalArgumentException) {
                Log.w("FileActionUtils", "buildInstallApkIntent: getUriForFile 失败", e)
                return null
            }
        } else {
            Uri.fromFile(file)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 根据文件扩展名获取 MIME 类型 */
    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            // 图片
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> "image/*"
            // 视频
            "mp4", "mov", "mkv", "avi", "flv", "wmv", "ts" -> "video/*"
            // 音频
            "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio/*"
            // 文档
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt", "log" -> "text/plain"
            // 压缩包
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            // 其他
            else -> "*/*"
        }
    }
}
