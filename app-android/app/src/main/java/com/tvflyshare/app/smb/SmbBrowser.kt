package com.tvflyshare.app.smb

import android.util.Log
import com.hierynomus.smbj.share.DiskShare
import com.tvflyshare.app.util.AppException
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tvflyshare.app.R
import com.tvflyshare.app.util.UiText

/**
 * SMB 目录浏览器
 *
 * 职责：列出 SMB 共享中的目录内容，返回 SmbFileEntry 列表。
 * 依赖 SmbClientManager 提供已连接的 DiskShare。
 *
 * 使用方式：
 *   val browser = SmbBrowser(clientManager)
 *   val files = browser.listDirectory("\\shared\\folder")
 */
class SmbBrowser(private val clientManager: SmbClientManager) {

    companion object {
        private const val TAG = "SmbBrowser"

        /** Windows FILE_ATTRIBUTE_DIRECTORY 标志位 */
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
    }

    /**
     * 列出指定目录下的文件和子目录
     *
     * @param path SMB 路径（如 "\\Videos" 或 "\\" 表示根目录）
     * @return 文件列表，按目录在前、文件在后排序；失败抛 AppException（2026-10-04 审计修正注释，原先写"失败返回空列表"与实现矛盾）
     */
    suspend fun listDirectory(path: String): List<SmbFileEntry> = withContext(Dispatchers.IO) {
        if (!clientManager.ensureConnected()) {
            Log.w(TAG, "Not connected or reconnect failed")
            throw AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected))
        }
        val share = clientManager.getDiskShare()
        if (share == null) {
            // 2026-10-04 审计 P2 修复：原先返回空列表，UI 会显示"空目录"掩盖故障——
            // 与上方 ensureConnected 分支统一抛异常，让 ViewModel 走错误提示
            Log.w(TAG, "DiskShare is null, not connected")
            throw AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected))
        }

        // 规范化路径：smbj 使用 \\ 开头的路径
        val normalizedPath = normalizePath(path)
        Log.i(TAG, "listDirectory: input='$path', normalized='$normalizedPath'")

        return@withContext try {
            val results = mutableListOf<SmbFileEntry>()

            share.list(normalizedPath).forEach { info ->
                val name = info.fileName
                // 跳过 . 和 ..
                if (name == "." || name == "..") return@forEach

                val isDirectory = (info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L
                val fullPath = if (normalizedPath.isEmpty()) name else "$normalizedPath\\$name"
                Log.i(TAG, "  entry: name='$name', fullPath='$fullPath', isDir=$isDirectory")

                results.add(
                    SmbFileEntry(
                        name = name,
                        path = fullPath,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else info.endOfFile,
                        lastModified = info.lastWriteTime.toEpochMillis()
                    )
                )
            }

            // 排序：目录在前，同类型按名称排序
            results.sortedWith(
                compareByDescending<SmbFileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "listDirectory error: $normalizedPath", e)
            throw e
        }
    }

    /**
    * 规范化 SMB 路径
    *
    * smbj 的 list(path) 中 path 是相对于共享根目录的相对路径，
    * 不带前导反斜杠。根目录传空字符串。
    * 例: "\\" → ""，"\\4897165" → "4897165"，"4897165" → "4897165"
    */
    private fun normalizePath(path: String): String {
        val p = path.replace("/", "\\")
        return p.trim('\\')
    }
}
