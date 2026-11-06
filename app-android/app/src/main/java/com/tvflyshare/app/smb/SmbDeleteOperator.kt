package com.tvflyshare.app.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.util.EnumSet

internal class SmbDeleteOperator(
    private val clientManager: SmbClientManager
) {
    /**
     * 删除 SMB 端的文件
     *
     * @param fileEntry SMB 文件信息
     * @return true 删除成功
     */
    suspend fun deleteSmbFile(fileEntry: SmbFileEntry): Boolean {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return false
        val share = clientManager.getDiskShare()
        if (share == null) {
            Log.w(SMB_FILE_OP_TAG, "DiskShare is null, not connected")
            return false
        }

        return try {
            share.rm(fileEntry.path)
            Log.i(SMB_FILE_OP_TAG, "Deleted SMB file: ${fileEntry.path}")
            true
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Delete error: ${fileEntry.path}", e)
            false
        }
    }

    /**
     * 删除 SMB 端的目录（包含其子文件和子目录）
     *
     * @param fileEntry SMB 目录信息
     * @return true 删除成功
     */
    suspend fun deleteSmbDirectory(fileEntry: SmbFileEntry): Boolean {
        val isAlive = clientManager.ensureConnected()
        if (!isAlive) return false
        val share = clientManager.getDiskShare()
        if (share == null) return false

        return try {
            share.rmdir(fileEntry.path, true)
            Log.i(SMB_FILE_OP_TAG, "Deleted SMB directory recursively: ${fileEntry.path}")
            true
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            Log.e(SMB_FILE_OP_TAG, "Delete directory SMB API error: ${fileEntry.path}", e)
            false
        } catch (e: java.io.IOException) {
            Log.e(SMB_FILE_OP_TAG, "Delete directory IO error: ${fileEntry.path}", e)
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(SMB_FILE_OP_TAG, "Delete directory unexpected error: ${fileEntry.path}", e)
            false
        }
    }
}
