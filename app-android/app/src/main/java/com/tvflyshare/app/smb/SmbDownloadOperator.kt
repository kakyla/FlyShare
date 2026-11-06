package com.tvflyshare.app.smb

import android.content.Context
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.FileActionUtils
import com.tvflyshare.app.util.StoragePermissionUtils
import com.tvflyshare.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.EnumSet

internal class SmbDownloadOperator(
    private val clientManager: SmbClientManager,
    private val context: Context
) {
    /**
     * 下载 SMB 文件到本地
     *
     * @param fileEntry SMB 文件信息
     * @param progressCallback 进度回调（可选）
     * @return 下载成功后的本地 File，失败返回 null
     */
    suspend fun downloadFile(
        fileEntry: SmbFileEntry,
        progressCallback: ProgressCallback? = null
    ): File? {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) {
            Log.w(SMB_FILE_OP_TAG, "Not connected or reconnect failed")
            return null
        }
        val share = clientManager.getDiskShare()
        if (share == null) {
            Log.w(SMB_FILE_OP_TAG, "DiskShare is null, not connected")
            return null
        }

        // 下载目标目录：使用项目统一的 TVfile 目录（优先 /sdcard/TVfile，无权限时降级 App 私有 TVfile）
        val localDir = StoragePermissionUtils.getStorageDir(context)

        // 路径遍历防护：取最后一段文件名，避免 fileEntry.name 含分隔符导致越界
        val safeName = File(fileEntry.name).name
        val localFile = File(localDir, safeName)
        if (!localFile.canonicalPath.startsWith(localDir.canonicalPath)) {
            Log.w(SMB_FILE_OP_TAG, "Path traversal detected: ${fileEntry.name}")
            return null
        }

        // 2026-10-10 审计修复：隐藏临时名直写 + 成功后原子改名——原先
        // FileOutputStream(localFile) 直接截断本地同名文件，传输中途失败还会把
        // 用户原有文件一并 delete，数据不可恢复。临时名下失败只删临时文件；
        // renameTo 对已存在目标是原子覆盖，成功路径语义与原先一致（对齐 FTP 下载）。
        val tmpFile = File(localDir, ".${safeName}.flyshare_dl_tmp")

        return try {
            // 打开 SMB 文件
            val smbFile = share.openFile(
                fileEntry.path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            val totalSize = fileEntry.size
            var downloaded = 0L
            val buffer = ByteArray(64 * 1024) // 64KB 缓冲区
            var finished = false

            try {
                smbFile.use { smb ->
                    FileOutputStream(tmpFile).use { fos ->
                        smb.inputStream.use { input ->
                            while (true) {
                                val read = input.read(buffer)
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
                }
                finished = tmpFile.renameTo(localFile)
                if (!finished) {
                    Log.w(SMB_FILE_OP_TAG, "rename to final name failed: ${fileEntry.name}")
                }
            } finally {
                if (!finished) tmpFile.takeIf { it.exists() }?.delete()
            }

            if (!finished) return null

            Log.i(SMB_FILE_OP_TAG, "Downloaded: ${fileEntry.name} -> ${localFile.absolutePath}")
            localFile
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Download SMB API error: ${fileEntry.name}", e)
            tmpFile.takeIf { it.exists() }?.delete()
            null
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Download IO error: ${fileEntry.name}", e)
            tmpFile.takeIf { it.exists() }?.delete()
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Download unexpected error: ${fileEntry.name}", e)
            // 清理不完整文件
            tmpFile.takeIf { it.exists() }?.delete()
            null
        }
    }

    /**
     * 安装 SMB 上的 APK
     *
     * 流程：下载到本地 → 调用系统安装 Intent
     *
     * @param fileEntry SMB APK 文件信息
     * @param progressCallback 下载进度回调（可选）
     * @return true 下载成功并启动安装 Intent
     */
    suspend fun installApk(
        fileEntry: SmbFileEntry,
        progressCallback: ProgressCallback? = null
    ): Boolean {
        // 先下载到本地
        val localFile = downloadFile(fileEntry, progressCallback) ?: return false

        return try {
            // 用 FileActionUtils 构建 Intent（内部用 FileProvider，兼容 Android 7.0+）
            // 之前用 Uri.fromFile 在 Android 7.0+（API 24+）会抛 FileUriExposedException 导致崩溃
            val intent = FileActionUtils.buildInstallApkIntent(context, localFile)
            if (intent != null) {
                try {
                    context.startActivity(intent)
                    Log.i(SMB_FILE_OP_TAG, "APK install intent launched: ${fileEntry.name}")
                    true
                } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                    // 启动 Intent 失败，清理已下载的残留 APK
                    if (!localFile.delete()) {
                        Log.w(SMB_FILE_OP_TAG, "Failed to delete leftover APK: ${localFile.path}")
                    }
                    Log.e(SMB_FILE_OP_TAG, "APK install error: ${fileEntry.name}", e)
                    false
                }
            } else {
                // Intent 构建失败，清理已下载的残留 APK
                if (!localFile.delete()) {
                    Log.w(SMB_FILE_OP_TAG, "Failed to delete leftover APK: ${localFile.path}")
                }
                Log.e(SMB_FILE_OP_TAG, "APK install intent build failed: ${fileEntry.name}")
                false
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            // 兜底：清理已下载的残留 APK
            if (!localFile.delete()) {
                Log.w(SMB_FILE_OP_TAG, "Failed to delete leftover APK: ${localFile.path}")
            }
            Log.e(SMB_FILE_OP_TAG, "APK install error: ${fileEntry.name}", e)
            false
        }
    }
}
