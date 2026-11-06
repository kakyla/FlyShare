package com.tvflyshare.app.smb

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

internal class SmbRenameOperator(
    private val clientManager: SmbClientManager
) {
    /**
     * 重命名 SMB 文件/目录
     *
     * @param fileEntry SMB 文件信息
     * @param newName 新名称（不含路径）
     * @return Result 成功或带具体失败原因
     */
    suspend fun renameFile(fileEntry: SmbFileEntry, newName: String): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) {
            return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))
        }
        if (newName.isBlank()) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_name_empty)))
        // 校验所有 Windows 非法字符：\ / : * ? " < > |
        if (newName.contains(Regex("""[\\/:*?"<>|]"""))) {
            return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_invalid_chars_smb)))
        }

        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        return try {
            val parentPath = fileEntry.path.substringBeforeLast("\\", "")
            val newPath = if (parentPath.isEmpty()) newName else "$parentPath\\$newName"

            // smbj 的 rename 在 DiskEntry 上，需要先 open
            // 2026-10-04 审计 P2 修复：AccessMask 用 DELETE——SMB2 的 SET_INFO(FileRenameInformation)
            // 要求句柄带 DELETE access，GENERIC_WRITE 在 Windows 严格校验下会 STATUS_ACCESS_DENIED
            val entry = if (fileEntry.isDirectory) {
                share.openDirectory(
                    fileEntry.path,
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
            Log.i(SMB_FILE_OP_TAG, "Renamed: ${fileEntry.path} -> $newPath")
            Result.success(Unit)
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Rename SMB API error: ${fileEntry.path}", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_rename_failed, e.message ?: "SMB API 错误")))
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Rename IO error: ${fileEntry.path}", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_rename_failed, e.message ?: "IO 错误")))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Rename unexpected error: ${fileEntry.path}", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_rename_failed, e.message ?: "未知错误")))
        }
    }

    /**
     * 新建文件夹
     */
    suspend fun createFolder(parentDirSmbPath: String, folderName: String): Result<Unit> {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        val share = clientManager.getDiskShare()
            ?: return Result.failure(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_not_connected)))

        if (folderName.isBlank()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_folder_name_empty)))
        // 2026-10-04 审计 P2 修复：非法字符全量校验（对齐 renameFile）——
        // 原先放行 : * ? " < > |，新建 a:b 之类名字在 NTFS 上会产生备份数据流对象
        if (folderName.contains(Regex("""[\\/:*?"<>|]""")) || folderName == ".." || folderName == ".") {
            return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_invalid_chars_smb)))
        }

        val normalizedDest = parentDirSmbPath.replace("/", "\\").trim('\\')
        val newDirPath = if (normalizedDest.isEmpty()) folderName else "$normalizedDest\\$folderName"

        return try {
            val destExists = try {
                share.openDirectory(
                    newDirPath,
                    java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { true }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            
            if (destExists) {
                Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_folder_exists)))
            } else {
                share.mkdir(newDirPath)
                Result.success(Unit)
            }
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Create folder SMB API error: $newDirPath", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_create_folder_failed, e.message ?: "SMB API 错误")))
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Create folder IO error: $newDirPath", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_create_folder_failed, e.message ?: "IO 错误")))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Create folder unexpected error: $newDirPath", e)
            Result.failure(AppException.SmbException(cause = e, specificMessage = UiText.StringResource(R.string.error_create_folder_failed, e.message ?: "")))
        }
    }
}
