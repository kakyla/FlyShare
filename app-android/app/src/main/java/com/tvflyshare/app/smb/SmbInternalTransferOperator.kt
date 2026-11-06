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
import java.io.IOException
import java.util.EnumSet

internal class SmbInternalTransferOperator(
    private val clientManager: SmbClientManager,
    private val context: Context
) {
    /**
     * 在 SMB 内移动文件/目录到另一个目录
     *
     * 利用 smbj 的 rename（服务器端操作，瞬间完成，不走客户端流量）。
     * 等价于把文件路径改为 destDir\name。
     *
     * @param fileEntry 源文件/目录
     * @param destDirSmbPath 目标目录的 SMB 路径（如 "\Videos"）
     * @return Result 成功或带具体失败原因
     */
    suspend fun moveWithinSmb(
        fileEntry: SmbFileEntry,
        destDirSmbPath: String
    ): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        // 规范化目标路径：去掉前导/末尾反斜杠，再拼上新文件名
        val normalizedDest = destDirSmbPath.replace("/", "\\").trim('\\')
        val newPath = if (normalizedDest.isEmpty()) fileEntry.name else "$normalizedDest\\${fileEntry.name}"

        // 目标已存在则失败（避免覆盖）
        val destExists = try {
            if (fileEntry.isDirectory) {
                share.openDirectory(
                    newPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { true }
            } else {
                share.openFile(
                    newPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { true }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (destExists) {
            return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_target_exists, fileEntry.name)))
        }

        return try {
            // 用 rename 改路径（smbj 服务器端操作）
            val entry = if (fileEntry.isDirectory) {
                share.openDirectory(
                    fileEntry.path,
                    // 2026-10-04 审计 P2 修复：rename 的 SET_INFO(FileRenameInformation) 要求
                    // 句柄带 DELETE access，GENERIC_WRITE 在 Windows 严格校验下会 ACCESS_DENIED
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
            } else {
                share.openFile(
                    fileEntry.path,
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
            }
            entry.use { diskEntry ->
                diskEntry.rename(newPath)
            }
            Log.i(SMB_FILE_OP_TAG, "Moved within SMB: ${fileEntry.path} -> $newPath")
            Result.success(Unit)
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Move within SMB API error: ${fileEntry.path} -> $newPath", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_move_failed, e.message ?: "SMB API 错误")))
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Move within SMB IO error: ${fileEntry.path} -> $newPath", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_move_failed, e.message ?: "IO 错误")))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Move within SMB unexpected error: ${fileEntry.path} -> $newPath", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_move_failed, e.message ?: "未知错误")))
        }
    }

    /**
     * 批量在 SMB 内移动文件/目录
     *
     * @param entries SMB 文件列表
     * @param destDirSmbPath 目标目录的 SMB 路径
     * @return Pair(成功数, 失败详情列表)
     */
    suspend fun moveMultipleWithinSmb(
        entries: List<SmbFileEntry>,
        destDirSmbPath: String
    ): Pair<Int, List<String>> {
        var count = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            moveWithinSmb(entry, destDirSmbPath)
                .onSuccess { count++ }
                .onFailure { errors.add("${entry.name}: ${it.message}") }
        }
        return count to errors
    }

    /**
     * 复制 SMB 目录及其所有内容到 SMB 其他目录
     */
    private suspend fun copyDirectoryWithinSmb(
        dirEntry: SmbFileEntry,
        destDirSmbPath: String,
        progressCallback: ProgressCallback? = null
    ): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        val normalizedDest = destDirSmbPath.replace("/", "\\").trim('\\')
        val newDirPath = if (normalizedDest.isEmpty()) dirEntry.name else "$normalizedDest\\${dirEntry.name}"

        // 尝试创建目标目录
        try {
            val destExists = try {
                share.openDirectory(
                    newDirPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { true }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }

            if (!destExists) {
                share.mkdir(newDirPath)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_cannot_create_dir, newDirPath)))
        }

        return try {
            val normalizedSrc = dirEntry.path.replace("/", "\\").trim('\\')
            share.list(normalizedSrc).forEach { info ->
                val name = info.fileName
                if (name == "." || name == "..") return@forEach

                val isDirectory = (info.fileAttributes and 0x10L) != 0L
                val fullPath = if (normalizedSrc.isEmpty()) name else "$normalizedSrc\\$name"
                
                val childEntry = SmbFileEntry(
                    name = name,
                    path = fullPath,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis()
                )

                if (isDirectory) {
                    val childResult = copyDirectoryWithinSmb(childEntry, newDirPath, progressCallback)
                    if (childResult.isFailure) return childResult
                } else {
                    val childResult = copyWithinSmb(childEntry, newDirPath, progressCallback)
                    if (childResult.isFailure) return childResult
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Copy directory within SMB error: ${dirEntry.path}", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "未知错误")))
        }
    }

    /**
     * 在 SMB 内复制文件到另一个目录
     *
     * smbj 无跨目录 copyFile API，采用流式复制（读 SMB 源 → 写 SMB 目标）。
     * 文件经过客户端内存缓冲（64KB），不落本地磁盘。
     * 大文件速度取决于 SMB 带宽，会走客户端流量。
     *
     * @param fileEntry 源文件
     * @param destDirSmbPath 目标目录的 SMB 路径
     * @param progressCallback 复制进度回调（可选）
     * @return Result 成功或带具体失败原因
     */
    suspend fun copyWithinSmb(
        fileEntry: SmbFileEntry,
        destDirSmbPath: String,
        progressCallback: ProgressCallback? = null
    ): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        if (fileEntry.isDirectory) {
            // 2026-10-10 审计修复：目标等于源目录或位于源子树内时拒绝——原先会递归复制
            // 生成 A\b\A\b… 无限嵌套垃圾目录直至 StackOverflowError（对齐本地
            // FileOperations.validateCopyMoveTarget 的同类校验）
            val srcNorm = fileEntry.path.replace("/", "\\").trim('\\')
            val destNorm = destDirSmbPath.replace("/", "\\").trim('\\')
            if (destNorm == srcNorm || destNorm.startsWith("$srcNorm\\")) {
                return Result.failure(
                    AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_dest_inside_source))
                )
            }
            return copyDirectoryWithinSmb(fileEntry, destDirSmbPath, progressCallback)
        }

        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        // 规范化目标路径
        val normalizedDest = destDirSmbPath.replace("/", "\\").trim('\\')
        val newPath = if (normalizedDest.isEmpty()) fileEntry.name else "$normalizedDest\\${fileEntry.name}"

        // 目标已存在则失败
        val destExists = try {
            share.openFile(
                newPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { true }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (destExists) {
            return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_target_exists, fileEntry.name)))
        }

        return try {
            // 打开源文件（读）
            val srcFile = share.openFile(
                fileEntry.path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            // 打开目标文件（创建）
            val destFile = share.openFile(
                newPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )

            val totalSize = fileEntry.size
            var copied = 0L
            val buffer = ByteArray(64 * 1024)

            srcFile.use { src ->
                destFile.use { dest ->
                    src.inputStream.use { input ->
                        dest.outputStream.use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (totalSize > 0) {
                                    val percent = ((copied * 100) / totalSize).toInt()
                                    progressCallback?.onProgress(copied, totalSize, percent)
                                }
                            }
                        }
                    }
                }
            }

            Log.i(SMB_FILE_OP_TAG, "Copied within SMB: ${fileEntry.path} -> $newPath")
            Result.success(Unit)
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Copy within SMB API error: ${fileEntry.path} -> $newPath", e)
            try { share.rm(newPath) } catch (e2: Exception) { if (e2 is kotlinx.coroutines.CancellationException) throw e2; Log.w(SMB_FILE_OP_TAG, "Failed to clean up partial copy: $newPath", e2) }
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "SMB API 错误")))
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Copy within SMB IO error: ${fileEntry.path} -> $newPath", e)
            try { share.rm(newPath) } catch (e2: Exception) { if (e2 is kotlinx.coroutines.CancellationException) throw e2; Log.w(SMB_FILE_OP_TAG, "Failed to clean up partial copy: $newPath", e2) }
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "IO 错误")))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Copy within SMB unexpected error: ${fileEntry.path} -> $newPath", e)
            // 复制失败时尝试清理目标文件
            try {
                share.rm(newPath)
            } catch (e2: Exception) {
                if (e2 is kotlinx.coroutines.CancellationException) throw e2
                Log.w(SMB_FILE_OP_TAG, "Failed to clean up partial copy: $newPath", e2)
            }
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "未知错误")))
        }
    }

    /**
     * 批量在 SMB 内复制文件
     *
     * @param entries SMB 文件列表
     * @param destDirSmbPath 目标目录的 SMB 路径
     * @return Pair(成功数, 失败详情列表)
     */
    suspend fun copyMultipleWithinSmb(
        entries: List<SmbFileEntry>,
        destDirSmbPath: String
    ): Pair<Int, List<String>> {
        var count = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            copyWithinSmb(entry, destDirSmbPath)
                .onSuccess { count++ }
                .onFailure { 
                    val msg = (it as? AppException)?.uiMessage?.asString(context) ?: it.message
                    errors.add("${entry.name}: $msg") 
                }
        }
        return count to errors
    }
}
