package com.tvflyshare.app.smb

import android.content.Context
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.UiText
import com.tvflyshare.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.EnumSet

internal class SmbLocalTransferOperator(
    private val clientManager: SmbClientManager,
    private val context: Context,
    private val deleteOperator: SmbDeleteOperator
) {
    /**
     * 复制 SMB 目录及其所有内容到本地目录
     */
    private suspend fun copyDirectoryToLocal(
        dirEntry: SmbFileEntry,
        destDir: String,
        progressCallback: ProgressCallback? = null
    ): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))
        
        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        val targetDirPath = destDir + File.separator + File(dirEntry.name).name
        val targetDirFile = File(targetDirPath)

        if (!targetDirFile.exists()) {
            val created = targetDirFile.mkdirs()
            if (!created) {
                return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_cannot_create_dir, targetDirPath)))
            }
        }

        return try {
            val normalizedPath = dirEntry.path.replace("/", "\\").trim('\\')
            share.list(normalizedPath).forEach { info ->
                val name = info.fileName
                if (name == "." || name == "..") return@forEach

                val isDirectory = (info.fileAttributes and 0x10L) != 0L
                val fullPath = if (normalizedPath.isEmpty()) name else "$normalizedPath\\$name"
                
                val childEntry = SmbFileEntry(
                    name = name,
                    path = fullPath,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis()
                )

                if (isDirectory) {
                    val childResult = copyDirectoryToLocal(childEntry, targetDirPath, progressCallback)
                    if (childResult.isFailure) return childResult
                } else {
                    val childResult = copyToLocal(childEntry, targetDirPath, progressCallback)
                    if (childResult.isFailure) return childResult
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Copy directory to local error: ${dirEntry.path}", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "未知错误")))
        }
    }

    /**
     * 复制 SMB 文件到本地目录
     *
     * @param fileEntry SMB 文件信息
     * @param destDir 本地目标目录
     * @param progressCallback 下载进度回调（可选）
     * @return Result 成功或带具体失败原因
     */
    suspend fun copyToLocal(
        fileEntry: SmbFileEntry,
        destDir: String,
        progressCallback: ProgressCallback? = null
    ): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        // 如果是目录，调用目录复制逻辑
        if (fileEntry.isDirectory) {
            return copyDirectoryToLocal(fileEntry, destDir, progressCallback)
        }

        val destDirFile = File(destDir)
        if (!destDirFile.exists()) {
            val created = destDirFile.mkdirs()
            if (!created) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_cannot_create_dir, destDir)))
        }
        if (!destDirFile.canWrite()) {
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_dir_not_writable, destDir)))
        }

        // 路径遍历防护：取最后一段文件名，避免 fileEntry.name 含分隔符导致越界
        val safeName = File(fileEntry.name).name
        val destFile = File(destDirFile, safeName)
        if (!destFile.canonicalPath.startsWith(destDirFile.canonicalPath)) {
            Log.w(SMB_FILE_OP_TAG, "Path traversal detected: ${fileEntry.name}")
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_invalid_filename, fileEntry.name)))
        }
        if (destFile.exists()) {
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_exists, fileEntry.name)))
        }

        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        return try {
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
            val buffer = ByteArray(64 * 1024)

            smbFile.use { smb ->
                FileOutputStream(destFile).use { fos ->
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

            Log.i(SMB_FILE_OP_TAG, "Copied to local: ${fileEntry.name} -> ${destFile.absolutePath}")
            Result.success(Unit)
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Copy to local SMB API error: ${fileEntry.name}", e)
            if (destFile.exists() && !destFile.delete()) Log.w(SMB_FILE_OP_TAG, "Failed to delete incomplete file: ${destFile.path}")
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "SMB API 错误")))
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Copy to local IO error: ${fileEntry.name}", e)
            if (destFile.exists() && !destFile.delete()) Log.w(SMB_FILE_OP_TAG, "Failed to delete incomplete file: ${destFile.path}")
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "IO 错误")))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Copy to local unexpected error: ${fileEntry.name}", e)
            if (destFile.exists() && !destFile.delete()) {
                Log.w(SMB_FILE_OP_TAG, "Failed to delete incomplete file: ${destFile.path}")
            }
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "未知错误")))
        }
    }

    /**
     * 批量复制 SMB 文件到本地目录
     *
     * @param entries SMB 文件列表
     * @param destDir 本地目标目录
     * @return Pair(成功数, 失败详情列表)
     */
    suspend fun copyMultipleToLocal(
        entries: List<SmbFileEntry>,
        destDir: String
    ): Pair<Int, List<String>> {
        var count = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            copyToLocal(entry, destDir)
                .onSuccess { count++ }
                .onFailure { 
                    val msg = (it as? AppException)?.uiMessage?.asString(context) ?: it.message
                    errors.add("${entry.name}: $msg") 
                }
        }
        return count to errors
    }

    /**
     * 移动 SMB 文件到本地目录（下载后删除 SMB 源文件）
     *
     * @param fileEntry SMB 文件信息
     * @param destDir 本地目标目录
     * @param progressCallback 下载进度回调（可选）
     * @return Result 成功或带具体失败原因
     */
    suspend fun moveToLocal(
        fileEntry: SmbFileEntry,
        destDir: String,
        progressCallback: ProgressCallback? = null
    ): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        // 先复制到本地
        val copyResult = copyToLocal(fileEntry, destDir, progressCallback)
        if (copyResult.isFailure) return copyResult

        // 复制成功后删除 SMB 源文件
        val deleted = if (fileEntry.isDirectory) {
            deleteOperator.deleteSmbDirectory(fileEntry)
        } else {
            deleteOperator.deleteSmbFile(fileEntry)
        }

        if (!deleted) {
            // 删除源失败，回滚：删除已下载的本地副本
            // 2026-10-04 审计 P2 修复：原先用非递归 File.delete()——对目录（copyToLocal 已复制整棵树）
            // 必失败，结果"源没删成、本地留完整副本、UI 报失败"三处矛盾；改递归删除 + 统一 safeName
            val safeName = File(fileEntry.name).name
            val localFile = File(destDir, safeName)
            if (localFile.exists() && !localFile.deleteRecursively()) {
                Log.w(SMB_FILE_OP_TAG, "Failed to delete incomplete local copy: ${localFile.path}")
            }
            return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_move_failed_delete_source)))
        }

        Log.i(SMB_FILE_OP_TAG, "Moved to local: ${fileEntry.name} -> $destDir")
        return Result.success(Unit)
    }
}
