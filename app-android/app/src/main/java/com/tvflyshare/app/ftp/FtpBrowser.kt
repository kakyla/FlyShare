package com.tvflyshare.app.ftp

import android.util.Log
import com.tvflyshare.app.R
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPFile

/**
 * FTP 目录浏览器
 *
 * 职责：列出 FTP 服务器指定目录的内容，返回 [FtpFileEntry] 列表。
 * 依赖 [FtpClientManager] 提供已连接的 FTPClient。
 */
class FtpBrowser(private val clientManager: FtpClientManager) {

    companion object {
        private const val TAG = "FtpBrowser"

        /** 把绝对路径转为 listFiles 所需的相对路径（根目录返回空串） */
        fun toListPath(absolutePath: String): String = absolutePath.trim('/')

        /** 拼接父目录绝对路径与子名称，返回新的绝对路径 */
        fun joinPath(dirAbs: String, name: String): String =
            if (dirAbs == "/" || dirAbs.isEmpty()) "/$name" else "$dirAbs/$name"
    }

    /**
     * 列出指定目录下的文件和子目录
     *
     * @param absolutePath 绝对路径，根目录传 "/" 或 ""
     * @return 文件列表，目录在前、文件在后排序；失败抛出异常
     */
    suspend fun listDirectory(absolutePath: String): List<FtpFileEntry> = withContext(Dispatchers.IO) {
        if (!clientManager.ensureConnected()) {
            throw AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_ftp_not_connected))
        }
        val client = clientManager.getFtpClient()
            ?: throw AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_ftp_not_connected))

        val dirAbs = if (absolutePath.isEmpty()) "/" else absolutePath
        val listPath = toListPath(dirAbs)
        Log.i(TAG, "listDirectory: input='$absolutePath', listPath='$listPath'")

        return@withContext try {
            val files: Array<FTPFile> = client.listFiles(listPath) ?: return@withContext emptyList()
            val results = mutableListOf<FtpFileEntry>()
            files.forEach { f ->
                val name = f.name
                // 2026-10-04 审计 P2 修复：过滤 null 名——commons-net 对无法解析的 LIST 行
                // 只填 rawListing，getName() 可为 null，传给 joinPath 会 NPE 且一条坏行拖垮整目录
                if (name == null || name == "." || name == "..") return@forEach
                val isDir = f.isDirectory
                val fullPath = joinPath(dirAbs, name)
                results.add(
                    FtpFileEntry(
                        name = name,
                        path = fullPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else f.size,
                        lastModified = runCatching { f.timestamp.timeInMillis }.getOrDefault(0L)
                    )
                )
            }
            results.sortedWith(
                compareByDescending<FtpFileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "listDirectory error: $listPath", e)
            throw e
        }
    }
}
