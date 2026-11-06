package com.tvflyshare.app.smb

/** SMB 操作类共享日志标签（与各操作符原 TAG 保持一致，便于日志检索）。 */
const val SMB_FILE_OP_TAG = "SmbFileOperator"

/**
 * 下载/复制进度回调（原 SmbFileOperator.ProgressCallback 提升为包级，供各操作符共享）。
 *
 * @param downloaded 已传输字节数
 * @param total 文件总大小（字节）
 * @param percent 百分比（0-100）
 */
fun interface ProgressCallback {
    fun onProgress(downloaded: Long, total: Long, percent: Int)
}
