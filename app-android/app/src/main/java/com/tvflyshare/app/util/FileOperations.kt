package com.tvflyshare.app.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.util.Log
import java.io.File
import com.tvflyshare.app.Constants
import com.tvflyshare.app.R

/**
 * 文件操作工具类
 *
 * 提供：打开、安装、删除、重命名
 * MediaStore 文件删除兼容 Android 10+
 */
/**
 * 同名冲突处理策略
 */
enum class ConflictStrategy {
    FAIL,       // 默认：同名即失败（保持原行为）
    OVERWRITE,  // 覆盖同名文件/目录
    SKIP,       // 跳过同名项（不计入成功/失败）
    RENAME      // 自动重命名为 name(1).ext
}

private class SkipSignal : Throwable()

object FileOperations {

    /**
     * 复制/移动前置校验（2026-10-04 审计 P0/P1 修复）：
     * 1. 目标目录 == 源文件所在目录：复制/移动无意义，且 OVERWRITE 策略会先删掉"目标"
     *    ——而它就是源文件本身，造成源文件永久丢失（先删后拷必失败，无回滚）。
     * 2. 目标目录位于源文件夹内部：copyRecursively 会无限嵌套复制直到路径爆长/写满磁盘，
     *    MOVE 分支最终还会把整棵源树删掉。
     * 返回 null 表示通过；返回 AppException 表示拒绝该操作。
     */
    private fun validateCopyMoveTarget(src: File, destDirFile: File): AppException? {
        return try {
            val parent = src.parentFile
            if (parent != null && parent.canonicalPath == destDirFile.canonicalPath) {
                AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_source_dest_same))
            } else if (src.isDirectory && isPathInsideRoot(src, destDirFile)) {
                AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_dest_inside_source))
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // canonical 解析失败时放行，走原有错误路径兜底
            null
        }
    }

    /**
     * 触发媒体扫描以更新 MediaStore
     */
    fun triggerMediaScan(context: Context, vararg paths: String) {
        try {
            // 第 4 个参数是「扫描完成」回调：等文件真正进入媒体库后再发事件，
            // 保证文件管理器收到通知去刷新时能查到刚落盘的新文件（否则会刷到旧列表）。
            // 这是事件驱动的即时刷新入口——替代定时轮询，零额外开销。
            MediaScannerConnection.scanFile(context.applicationContext, paths, null) { _, _ ->
                MediaChangeNotifier.notifyChanged()
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("FileOperations", "Failed to trigger media scan", AppException.fromThrowable(e))
        }
    }

    /**
     * 净化文件名：过滤非法字符并截断超长名称，以兼容 Android/Windows (FAT32) 等系统
     */
    fun sanitizeFileName(originalName: String): String {
        // 1. 过滤非法字符：Windows 和 Android(FAT32) 不支持 \ / : * ? " < > | 以及换行符
        var safeName = File(originalName).name.replace(Regex("[\\\\/:*?\"<>|\r\n]"), "_")

        // 2026-10-10 审计修复：中和保留名与末尾点号——"."、".." 作为净化输出即路径穿越候选名
        // （原先 sanitizeFileName("..") 原样返回 ".."，仅靠下游 exists() 改名循环隐式兜底）；
        // 末尾点号在 FAT32/Windows 为非法名。净化层自身必须保证输出安全。
        safeName = safeName.trimEnd('.')
        if (safeName.isEmpty()) safeName = "_"

        // 2. 防止文件名过长导致文件系统报错（限制为最多约 200 字节，预留后缀和数字后缀空间）
        val nameBytes = safeName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 200) {
            val dotIndex = safeName.lastIndexOf('.')
            val ext = if (dotIndex > 0) safeName.substring(dotIndex) else ""
            val extBytesSize = ext.toByteArray(Charsets.UTF_8).size
            
            var baseName = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
            while (baseName.toByteArray(Charsets.UTF_8).size + extBytesSize > 200 && baseName.isNotEmpty()) {
                // 每次截掉最后一个字符
                baseName = baseName.substring(0, baseName.length - 1)
            }
            // 去除首尾空格，因为 FAT32/Windows 不允许文件名以空格结尾
            baseName = baseName.trim()
            safeName = baseName + ext
        } else {
            val dotIndex = safeName.lastIndexOf('.')
            val ext = if (dotIndex > 0) safeName.substring(dotIndex) else ""
            var baseName = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
            baseName = baseName.trim()
            safeName = baseName + ext
        }
        return safeName
    }

    /**
     * 用系统 Intent 打开文件
     */
    fun openFile(context: Context, entry: FileEntry): Boolean {
        return try {
            val file = File(entry.path)
            if (!file.exists()) return false

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.${Constants.FILE_PROVIDER_AUTHORITY_SUFFIX}", file)
            } else {
                Uri.fromFile(file)
            }

            val mime = getMimeType(entry.path)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /**
     * 安装 APK
     */
    fun installApk(context: Context, entry: FileEntry): Boolean {
        return try {
            val file = File(entry.path)
            if (!file.exists() || !file.name.endsWith(".apk", ignoreCase = true)) return false

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.${Constants.FILE_PROVIDER_AUTHORITY_SUFFIX}", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("FileOperations", "Failed to install APK", e)
            false
        }
    }

    /**
     * 递归删除文件或目录
     */
    private fun deleteRecursively(file: File, context: Context): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child, context)) {
                        return false
                    }
                }
            }
        }
        val deleted = file.delete()
        if (deleted) {
            deleteViaMediaStore(context, file.absolutePath)
        }
        return deleted
    }

    /**
     * 删除文件（带详细错误反馈）
     * @return Result 成功或带具体失败原因
     */
    fun deleteFileWithFeedback(context: Context, entry: FileEntry): Result<Unit> {
        val file = File(entry.path)
        if (!file.exists()) {
            // 文件不存在，尝试通过 MediaStore 删除记录
            val viaMedia = deleteViaMediaStore(context, entry.path)
            return if (viaMedia) Result.success(Unit)
                   else Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_file_not_found)))
        }
        // 优先用递归删除
        val deleted = deleteRecursively(file, context)
        if (deleted) {
            triggerMediaScan(context, entry.path)
            return Result.success(Unit)
        }
        // 失败：可能是权限问题，尝试 MediaStore
        val viaMedia = deleteViaMediaStore(context, entry.path)
        if (viaMedia) {
            triggerMediaScan(context, entry.path)
            return Result.success(Unit)
        }
        // 都失败：给出具体原因
        val canWrite = file.canWrite()
        val parentCanWrite = file.parentFile?.canWrite() ?: false
        return when {
            !canWrite -> Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_no_write_permission)))
            !parentCanWrite -> Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_no_delete_permission)))
            else -> Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_delete_failed_occupied)))
        }
    }

    /**
     * 旧版删除（兼容）
     */
    fun deleteFile(context: Context, entry: FileEntry): Boolean {
        return deleteFileWithFeedback(context, entry).isSuccess
    }

    /**
     * 批量删除
     * @return 成功删除的数量
     */
    fun deleteFiles(context: Context, entries: List<FileEntry>): Int {
        var count = 0
        for (entry in entries) {
            if (deleteFile(context, entry)) count++
        }
        return count
    }

    /**
     * 重命名文件
     * @return Result 成功或带具体失败原因
     */
    fun renameFile(context: Context, entry: FileEntry, newName: String): Result<Unit> {
        return try {
            if (newName.isBlank()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_name_empty)))
            // 非法字符全量校验（对齐 sanitizeFileName 的 FAT/Windows 字符集；2026-10-04 审计 P2 修复，
            // 原先只拦 / \ . ..，把 a:b 这类名字重命名到 FAT32 U 盘会得到文件系统级报错）
            if (newName.contains(Regex("""[\\/:*?"<>|]""")) || newName == ".." || newName == ".") {
                return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_invalid_chars)))
            }
            val file = File(entry.path)
            if (!file.exists()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_file_not_found)))
            val parent = file.parentFile ?: return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_parent_dir_unknown)))
            val destFile = File(parent, newName)
            if (destFile.exists()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_name_exists)))
            if (!parent.canWrite()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_no_write_permission)))
            val ok = file.renameTo(destFile)
            if (ok) {
                // 如果是文件夹重命名，需要触发媒体库扫描其下的文件，并且更新相关媒体库记录
                // 如果是文件重命名，也需要更新
                deleteViaMediaStore(context, file.absolutePath)
                triggerMediaScan(context, file.absolutePath, destFile.absolutePath)
                Result.success(Unit)
            }
            else Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_rename_occupied)))
        } catch (e: SecurityException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_rename_failed, e.message ?: "")))
        } catch (e: java.io.IOException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_rename_failed, e.message ?: "")))
        }
    }

    /**
     * 递归复制文件或目录
     */
    private fun copyRecursively(src: File, dest: File): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dest.exists() && !dest.mkdirs()) {
                    return false
                }
                val children = src.listFiles()
                if (children != null) {
                    for (child in children) {
                        val destChild = File(dest, child.name)
                        if (!copyRecursively(child, destChild)) {
                            return false
                        }
                    }
                }
                true
            } else {
                src.copyTo(dest, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e("FileOperations", "copyRecursively failed", e)
            false
        }
    }

    /**
     * 复制文件到目标目录
     * @param destDir 目标目录（绝对路径）
     * @return Result 成功或带具体失败原因
     */
    fun copyFileWithFeedback(context: Context, entry: FileEntry, destDir: String, conflict: ConflictStrategy = ConflictStrategy.FAIL): Result<Unit> {
        return try {
            val src = File(entry.path)
            if (!src.exists()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_source_not_found, entry.path)))
            val destDirFile = File(destDir)
            if (!destDirFile.exists()) {
                val created = destDirFile.mkdirs()
                if (!created) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_cannot_create_dir, destDir)))
            }
            if (!destDirFile.canWrite()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_dir_not_writable, destDir)))
            validateCopyMoveTarget(src, destDirFile)?.let { return Result.failure(it) }
            val rawDest = File(destDirFile, entry.name)
            val dest = when {
                !rawDest.exists() -> rawDest
                conflict == ConflictStrategy.OVERWRITE -> { deleteForOverwrite(rawDest, context); rawDest }
                conflict == ConflictStrategy.RENAME -> File(destDirFile, resolveNonConflictName(destDirFile, entry.name))
                conflict == ConflictStrategy.SKIP -> return Result.failure(SkipSignal())
                else -> return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_exists, rawDest.name)))
            }
            try {
                if (!copyRecursively(src, dest)) {
                    return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_copy_failed, "递归复制失败")))
                }
            } catch (e: java.io.FileNotFoundException) {
                if (e.message?.contains("EPERM") == true || e.message?.contains("EACCES") == true) {
                    return Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_permission_denied_storage)))
                }
                throw e
            }
            triggerMediaScan(context, dest.absolutePath)
            Result.success(Unit)
        } catch (e: SecurityException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "")))
        } catch (e: java.io.IOException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_copy_failed, e.message ?: "")))
        }
    }

    /**
     * 批量复制文件到目标目录
     * @param destDir 目标目录（绝对路径）
     * @return Pair(成功数, 失败详情列表)
     */
    fun copyFilesWithFeedback(context: Context, entries: List<FileEntry>, destDir: String, conflict: ConflictStrategy = ConflictStrategy.FAIL): Triple<Int, Int, List<String>> {
        var count = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            copyFileWithFeedback(context, entry, destDir, conflict)
                .onSuccess { count++ }
                .onFailure {
                    if (it is SkipSignal) {
                        skipped++
                    } else {
                        val msg = (it as? AppException)?.uiMessage?.asString(context) ?: it.message
                        errors.add("${entry.name}: $msg")
                    }
                }
        }
        return Triple(count, skipped, errors)
    }

    /**
     * 移动文件到目标目录
     * 优先 renameTo，若跨盘则 copy+delete
     * @param destDir 目标目录（绝对路径）
     * @return Result 成功或带具体失败原因
     */
    fun moveFileWithFeedback(context: Context, entry: FileEntry, destDir: String, conflict: ConflictStrategy = ConflictStrategy.FAIL): Result<Unit> {
        return try {
            val src = File(entry.path)
            if (!src.exists()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_source_not_found, entry.path)))
            val destDirFile = File(destDir)
            if (!destDirFile.exists()) {
                val created = destDirFile.mkdirs()
                if (!created) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_cannot_create_dir, destDir)))
            }
            if (!destDirFile.canWrite()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_dir_not_writable, destDir)))
            validateCopyMoveTarget(src, destDirFile)?.let { return Result.failure(it) }
            val rawDest = File(destDirFile, entry.name)
            val dest = when {
                !rawDest.exists() -> rawDest
                conflict == ConflictStrategy.OVERWRITE -> { deleteForOverwrite(rawDest, context); rawDest }
                conflict == ConflictStrategy.RENAME -> File(destDirFile, resolveNonConflictName(destDirFile, entry.name))
                conflict == ConflictStrategy.SKIP -> return Result.failure(SkipSignal())
                else -> return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_target_exists, rawDest.name)))
            }
            // 先尝试 rename（高效，同盘必成）
            try {
                if (src.renameTo(dest)) {
                    deleteViaMediaStore(context, entry.path)
                    triggerMediaScan(context, entry.path, dest.absolutePath)
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                // ignore and fallback to copy
            }
            // 跨盘或权限限制：复制再删
            try {
                if (copyRecursively(src, dest)) {
                    val deleted = deleteRecursively(src, context)
                    if (deleted) {
                        triggerMediaScan(context, entry.path, dest.absolutePath)
                        return Result.success(Unit)
                    }
                    // 删除源文件失败：保留已复制的目标副本（2026-10-04 审计 P2 修复——
                    // 原先静默 deleteRecursively(dest) 回滚，若源是目录且删除中途失败，
                    // 用户会同时丢失"部分源 + 全部副本"；保留副本让用户可手动清理）
                    Log.w("FileOperations", "Move source delete failed; kept copied target at ${dest.absolutePath}")
                    return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_move_failed_delete_source)))
                }
            } catch (e: java.io.FileNotFoundException) {
                if (e.message?.contains("EPERM") == true || e.message?.contains("EACCES") == true) {
                    return Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_permission_denied_storage)))
                }
                throw e
            }
            Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_move_failed_copy)))
        } catch (e: SecurityException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_move_failed, e.message ?: "")))
        } catch (e: java.io.IOException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_move_failed, e.message ?: "")))
        }
    }

    /**
     * 批量移动文件到目标目录
     * @param destDir 目标目录（绝对路径）
     * @return Pair(成功数, 失败详情列表)
     */
    fun moveFilesWithFeedback(context: Context, entries: List<FileEntry>, destDir: String, conflict: ConflictStrategy = ConflictStrategy.FAIL): Triple<Int, Int, List<String>> {
        var count = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            moveFileWithFeedback(context, entry, destDir, conflict)
                .onSuccess { count++ }
                .onFailure {
                    if (it is SkipSignal) {
                        skipped++
                    } else {
                        val msg = (it as? AppException)?.uiMessage?.asString(context) ?: it.message
                        errors.add("${entry.name}: $msg")
                    }
                }
        }
        return Triple(count, skipped, errors)
    }

    /**
     * 新建文件夹
     * @param parentDir 父目录路径
     * @param folderName 文件夹名称
     * @return Result 成功或带具体失败原因
     */
    fun createFolder(context: Context, parentDir: String, folderName: String): Result<Unit> {
        return try {
            if (folderName.isBlank()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_folder_name_empty)))
            if (folderName.contains("/") || folderName.contains("\\") || folderName == ".." || folderName == ".") {
                return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_invalid_chars)))
            }
            val parent = File(parentDir)
            if (!parent.exists() || !parent.isDirectory) {
                return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_parent_dir_not_found)))
            }
            if (!parent.canWrite()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_no_write_permission_short)))
            val newDir = File(parent, folderName)
            if (newDir.exists()) return Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_folder_exists)))
            val created = newDir.mkdir()
            if (created) Result.success(Unit)
            else Result.failure(AppException.StorageException(specificMessage = UiText.StringResource(R.string.error_create_failed)))
        } catch (e: SecurityException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_create_folder_failed, e.message ?: "")))
        } catch (e: java.io.IOException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.StorageException(cause = e, specificMessage = UiText.StringResource(R.string.error_create_folder_failed, e.message ?: "")))
        }
    }

    /**
     * 获取文件 MIME 类型
     */
    private fun getMimeType(path: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(path)
            ?: path.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        } else {
            "*/*"
        }
    }

    /**
     * 通过 MediaStore 删除文件（Android 10+ 分区存储兼容）
     */
    fun deleteViaMediaStore(context: Context, path: String): Boolean {
        return try {
            val uri = findMediaStoreUri(context, path) ?: return false
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("FileOperations", "Failed to delete via MediaStore", AppException.fromThrowable(e))
            false
        }
    }

    /**
     * 根据文件路径查找 MediaStore 中的 URI
     */
    private fun findMediaStoreUri(context: Context, path: String): Uri? {
        val projections = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
        )
        val uris = listOf(
            MediaStore.Files.getContentUri("external"),
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        for (uri in uris) {
            context.contentResolver.query(
                uri, projections,
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(uri, id)
                }
            }
        }
        return null
    }

    /**
     * 覆盖前删除目标（文件直接删，目录递归删）
     */
    private fun deleteForOverwrite(dest: File, context: Context): Boolean {
        return if (dest.isDirectory) {
            deleteRecursively(dest, context)
        } else {
            val deleted = dest.delete()
            if (deleted) deleteViaMediaStore(context, dest.absolutePath)
            deleted
        }
    }

    /**
     * 为同名文件生成不冲突的新名：name(1).ext, name(2).ext ...
     */
    private fun resolveNonConflictName(dir: File, name: String): String {
        val base = File(dir, name)
        if (!base.exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        var candidate: String
        do {
            candidate = "$stem($n)$ext"
            n++
        } while (File(dir, candidate).exists() && n < 9999)
        return candidate
    }
}
