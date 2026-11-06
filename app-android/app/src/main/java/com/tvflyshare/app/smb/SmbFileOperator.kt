package com.tvflyshare.app.smb

import android.content.Context
import java.io.File

/**
 * SMB 文件操作门面（Facade）。
 *
 * 原 SmbFileOperator（910 行）按职责拆分为 5 个内部操作符：
 * - SmbDownloadOperator：下载到本地 / 安装 APK
 * - SmbDeleteOperator：删除文件 / 目录
 * - SmbRenameOperator：重命名 / 新建文件夹
 * - SmbLocalTransferOperator：复制 / 移动到本地
 * - SmbInternalTransferOperator：SMB 内复制 / 移动
 *
 * 本类仅保留对外公共 API 与委托逻辑，方法签名与原实现完全一致，
 * 调用方（SmbBrowseViewModel）无需任何改动。所有传输逻辑实现见各操作符文件。
 */
class SmbFileOperator(
    private val clientManager: SmbClientManager,
    private val context: Context
) {
    private val deleteOperator = SmbDeleteOperator(clientManager)
    private val downloadOperator = SmbDownloadOperator(clientManager, context)
    private val renameOperator = SmbRenameOperator(clientManager)
    private val localTransferOperator = SmbLocalTransferOperator(clientManager, context, deleteOperator)
    private val internalTransferOperator = SmbInternalTransferOperator(clientManager, context)

    suspend fun downloadFile(fileEntry: SmbFileEntry, progressCallback: ProgressCallback? = null): File? =
        downloadOperator.downloadFile(fileEntry, progressCallback)

    suspend fun installApk(fileEntry: SmbFileEntry, progressCallback: ProgressCallback? = null): Boolean =
        downloadOperator.installApk(fileEntry, progressCallback)

    suspend fun deleteSmbFile(fileEntry: SmbFileEntry): Boolean =
        deleteOperator.deleteSmbFile(fileEntry)

    suspend fun deleteSmbDirectory(fileEntry: SmbFileEntry): Boolean =
        deleteOperator.deleteSmbDirectory(fileEntry)

    suspend fun renameFile(fileEntry: SmbFileEntry, newName: String): Result<Unit> =
        renameOperator.renameFile(fileEntry, newName)

    suspend fun createFolder(parentDirSmbPath: String, folderName: String): Result<Unit> =
        renameOperator.createFolder(parentDirSmbPath, folderName)

    suspend fun copyToLocal(fileEntry: SmbFileEntry, destDir: String, progressCallback: ProgressCallback? = null): Result<Unit> =
        localTransferOperator.copyToLocal(fileEntry, destDir, progressCallback)

    suspend fun copyMultipleToLocal(entries: List<SmbFileEntry>, destDir: String): Pair<Int, List<String>> =
        localTransferOperator.copyMultipleToLocal(entries, destDir)

    suspend fun moveToLocal(fileEntry: SmbFileEntry, destDir: String, progressCallback: ProgressCallback? = null): Result<Unit> =
        localTransferOperator.moveToLocal(fileEntry, destDir, progressCallback)

    suspend fun moveWithinSmb(fileEntry: SmbFileEntry, destDirSmbPath: String): Result<Unit> =
        internalTransferOperator.moveWithinSmb(fileEntry, destDirSmbPath)

    suspend fun moveMultipleWithinSmb(entries: List<SmbFileEntry>, destDirSmbPath: String): Pair<Int, List<String>> =
        internalTransferOperator.moveMultipleWithinSmb(entries, destDirSmbPath)

    suspend fun copyWithinSmb(fileEntry: SmbFileEntry, destDirSmbPath: String, progressCallback: ProgressCallback? = null): Result<Unit> =
        internalTransferOperator.copyWithinSmb(fileEntry, destDirSmbPath, progressCallback)

    suspend fun copyMultipleWithinSmb(entries: List<SmbFileEntry>, destDirSmbPath: String): Pair<Int, List<String>> =
        internalTransferOperator.copyMultipleWithinSmb(entries, destDirSmbPath)
}
