package com.tvflyshare.app.ftp

import android.content.Context
import android.util.Log
import com.tvflyshare.app.R
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.StoragePermissionUtils
import com.tvflyshare.app.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import java.io.File
import java.io.FileOutputStream

/**
 * FTP 文件操作器
 *
 * 职责：
 * 1. 从 FTP 下载文件到本地（支持进度回调）
 * 2. 安装 FTP 上的 APK（先下载再 Intent）
 * 3. 删除 FTP 端文件/目录
 * 4. 重命名
 * 5. 新建文件夹
 *
 * 依赖 [FtpClientManager] 提供已连接的 FTPClient。
 * 下载目标目录：项目统一的 TVfile/（与 SMB、崩溃日志一致，优先 /sdcard/TVfile，无权限降级 App 私有）。
 */
class FtpFileOperator(
    private val clientManager: FtpClientManager,
    private val context: Context
) {

    companion object {
        private const val TAG = "FtpFileOperator"
    }

    fun interface ProgressCallback {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
    }

    /**
     * 下载 FTP 文件到本地
     *
     * @param entry 文件信息
     * @param progressCallback 进度回调（可选）
     * @return 下载成功后的本地 File，失败返回 null
     */
    suspend fun downloadFile(
        entry: FtpFileEntry,
        progressCallback: ProgressCallback? = null
    ): File? {
        if (!clientManager.ensureConnected()) {
            Log.w(TAG, "Not connected or reconnect failed")
            return null
        }
        val client = clientManager.getFtpClient() ?: return null

        // 下载目标目录：使用项目统一的 TVfile 目录（优先 /sdcard/TVfile，无权限时降级 App 私有 TVfile）
        val localDir = StoragePermissionUtils.getStorageDir(context)

        // 路径遍历防护（2026-10-04 审计 P2 加固：前缀比较补分隔符，防 /sdcard/TVfileX 同形前缀绕过）
        val safeName = File(entry.name).name
        val localFile = File(localDir, safeName)
        val localBase = localDir.canonicalPath
        if (!localFile.canonicalPath.startsWith(localBase + File.separator) &&
            localFile.canonicalPath != localBase
        ) {
            Log.w(TAG, "Path traversal detected: ${entry.name}")
            return null
        }

        return try {
            withContext(Dispatchers.IO) {
                client.enterLocalPassiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                val input = client.retrieveFileStream(entry.path) ?: run {
                    Log.w(TAG, "retrieveFileStream returned null for ${entry.path}")
                    return@withContext null
                }
                // 2026-10-10 审计修复：隐藏临时名直写 + 成功后原子改名——原先
                // FileOutputStream(localFile) 直接截断本地同名文件，传输中途失败还会把
                // 用户原有文件一并 delete，数据不可恢复。临时名下失败只删临时文件；
                // renameTo 对已存在目标是原子覆盖，成功路径语义与原先一致。
                val tmpFile = File(localDir, ".${safeName}.flyshare_dl_tmp")
                val totalSize = entry.size
                var downloaded = 0L
                val buffer = ByteArray(64 * 1024)
                var finished = false
                try {
                    input.use { inp ->
                        FileOutputStream(tmpFile).use { fos ->
                            while (true) {
                                val read = inp.read(buffer)
                                if (read <= 0) break
                                fos.write(buffer, 0, read)
                                downloaded += read
                                if (totalSize > 0) {
                                    val percent = ((downloaded * 100) / totalSize).toInt()
                                    progressCallback?.onProgress(downloaded, totalSize, percent)
                                }
                            }
                        }
                    }
                    // 必须调用以结束传输并取回最终回复码
                    val ok = client.completePendingCommand()
                    if (!ok) {
                        Log.w(TAG, "completePendingCommand failed for ${entry.path}")
                        return@withContext null
                    }
                    if (!tmpFile.renameTo(localFile)) {
                        Log.w(TAG, "rename to final name failed for ${entry.name}")
                        return@withContext null
                    }
                    finished = true
                    Log.i(TAG, "Downloaded: ${entry.name} -> ${localFile.absolutePath}")
                    localFile
                } finally {
                    if (!finished) tmpFile.takeIf { it.exists() }?.delete()
                    // 2026-10-10 审计修复：传输中断时服务器的 226/426 回复仍滞留控制连接，
                    // 不消费会使同一 client 上的下一条命令读到遗留回复而协议失步
                    // （soTimeout=30s 兜底防挂死）。成功路径 completePendingCommand 已消费，跳过。
                    if (!finished) runCatching { if (client.isConnected) client.completePendingCommand() }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Download error: ${entry.name}", e)
            null
        }
    }

    /** 安装 FTP 上的 APK（先下载再启动安装 Intent） */
    suspend fun installApk(
        entry: FtpFileEntry,
        progressCallback: ProgressCallback? = null
    ): Boolean {
        val localFile = downloadFile(entry, progressCallback) ?: return false
        return try {
            val intent = FileActionUtils.buildInstallApkIntent(context, localFile)
            if (intent != null) {
                try {
                    context.startActivity(intent)
                    Log.i(TAG, "APK install intent launched: ${entry.name}")
                    true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (!localFile.delete()) Log.w(TAG, "delete leftover APK")
                    Log.e(TAG, "APK install error", e)
                    false
                }
            } else {
                if (!localFile.delete()) Log.w(TAG, "delete leftover APK")
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!localFile.delete()) Log.w(TAG, "delete leftover APK")
            false
        }
    }

    /** 删除 FTP 端文件 */
    suspend fun deleteFile(entry: FtpFileEntry): Boolean {
        if (!clientManager.ensureConnected()) return false
        val client = clientManager.getFtpClient() ?: return false
        return try {
            withContext(Dispatchers.IO) {
                client.deleteFile(entry.path)
            }.also { Log.i(TAG, "Deleted FTP file: ${entry.path}") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Delete file error: ${entry.path}", e)
            false
        }
    }

    /** 递归删除 FTP 端目录 */
    suspend fun deleteDirectory(entry: FtpFileEntry): Boolean {
        if (!clientManager.ensureConnected()) return false
        val client = clientManager.getFtpClient() ?: return false
        return try {
            withContext(Dispatchers.IO) {
                val listPath = FtpBrowser.toListPath(entry.path)
                // 2026-10-04 审计 P2 修复：listFiles null 防御 + 过滤 null 名（不可解析 LIST 行）
                val children = client.listFiles(listPath) ?: return@withContext false
                for (f in children) {
                    if (f.name == null || f.name == "." || f.name == "..") continue
                    val childAbs = FtpBrowser.joinPath(entry.path, f.name)
                    val ok = if (f.isDirectory) {
                        deleteDirectory(FtpFileEntry(f.name, childAbs, true))
                    } else {
                        client.deleteFile(childAbs)
                    }
                    if (!ok) return@withContext false
                }
                client.removeDirectory(entry.path)
            }.also { Log.i(TAG, "Deleted FTP directory: ${entry.path}") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Delete directory error: ${entry.path}", e)
            false
        }
    }

    /** 重命名文件/目录 */
    suspend fun renameFile(entry: FtpFileEntry, newName: String): Result<Unit> {
        if (!clientManager.ensureConnected()) {
            return Result.failure(AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_ftp_not_connected)))
        }
        if (newName.isBlank()) {
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_name_empty)))
        }
        // 2026-10-10 审计修复：与 SMB 侧重命名同规——校验非法字符。原先 newName 含 "/" 时
        // joinPath 会拼出跨目录路径，"重命名"悄然变成"移动"。
        if (newName.contains(Regex("[\\\\/:*?\"<>|]"))) {
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_invalid_chars)))
        }
        val client = clientManager.getFtpClient()
            ?: return Result.failure(AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_ftp_not_connected)))

        val parentAbs = entry.path.substringBeforeLast("/", "/")
        val newPath = FtpBrowser.joinPath(parentAbs, newName)
        return try {
            val ok = withContext(Dispatchers.IO) { client.rename(entry.path, newPath) }
            if (ok) {
                Log.i(TAG, "Renamed: ${entry.path} -> $newPath")
                Result.success(Unit)
            } else {
                Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_rename_failed, "FTP")))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Rename error: ${entry.path}", e)
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_rename_failed, e.message ?: "")))
        }
    }

    /** 新建文件夹 */
    suspend fun createFolder(parentDirAbs: String, folderName: String): Result<Unit> {
        if (!clientManager.ensureConnected()) {
            return Result.failure(AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_ftp_not_connected)))
        }
        if (folderName.isBlank() || folderName.contains("/") || folderName == ".." || folderName == ".") {
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_invalid_chars)))
        }
        val client = clientManager.getFtpClient()
            ?: return Result.failure(AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_ftp_not_connected)))

        val newPath = FtpBrowser.joinPath(parentDirAbs, folderName)
        return try {
            val ok = withContext(Dispatchers.IO) { client.makeDirectory(newPath) }
            if (ok) {
                Log.i(TAG, "Created folder: $newPath")
                Result.success(Unit)
            } else {
                Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_create_folder_failed, "FTP")))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Create folder error: $newPath", e)
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_create_folder_failed, e.message ?: "")))
        }
    }
}
