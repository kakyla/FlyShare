package com.tvflyshare.app.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 存储设备信息
 */
data class StorageDeviceInfo(
    val name: String,
    val path: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val totalGB: String,
    val usedGB: String,
    val usedRatio: Float,
    val isRemovable: Boolean,
)

/**
 * 存储设备扫描器
 *
 * 扫描设备上所有挂载的存储卷：
 * - 内部存储（必有两个）
 * - USB / SD 卡（API 24+）
 *
 * API 21-23 降级：仅扫描内部存储。
 */
object StorageDeviceScanner {

    /**
     * 扫描所有存储设备
     */
    suspend fun scan(context: Context): Result<List<StorageDeviceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val devices = mutableListOf<StorageDeviceInfo>()

            // 内部存储（所有 API 版本都有）
            val internalPath = Environment.getExternalStorageDirectory()?.absolutePath
            if (internalPath != null) {
                val internal = scanPath(internalPath, "内部存储", isRemovable = false)
                if (internal != null) devices.add(internal)
            }

            // USB / SD 卡（API 24+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                val volumes = storageManager?.storageVolumes
                if (volumes != null) {
                    for (volume in volumes) {
                        // 跳过内部存储（已添加）
                        if (!volume.isRemovable) continue

                        // 获取挂载路径
                        val path = getVolumePath(volume)
                        if (path != null && path != internalPath) {
                            val name = volume.getDescription(context) ?: "USB存储"
                            val device = scanPath(path, name, isRemovable = true)
                            if (device != null) devices.add(device)
                        }
                    }
                }
            }

            devices
        }
    }

    /**
     * 扫描单个路径，返回存储设备信息
     */
    private fun scanPath(path: String, name: String, isRemovable: Boolean): StorageDeviceInfo? {
        // 2026-10-04 审计 P2 修复：StatFs 对已拔出/未挂载卷抛 IllegalArgumentException，
        // 原先异常冒泡到 scan() 的 runCatching 使整份设备列表清空——逐卷兜底，坏卷跳过
        return try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) return null

            val stat = StatFs(path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes

            if (totalBytes <= 0) return null

            StorageDeviceInfo(
                name = name,
                path = path,
                totalBytes = totalBytes,
                usedBytes = usedBytes,
                totalGB = formatGB(totalBytes),
                usedGB = formatGB(usedBytes),
                usedRatio = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f,
                isRemovable = isRemovable,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * 获取 StorageVolume 的挂载路径
     *
     * StorageVolume.getPath() 是隐藏 API（API 24+），
     * 通过反射获取。如果失败返回 null。
     */
    private fun getVolumePath(volume: StorageVolume): String? {
        return try {
            val getPath = volume.javaClass.getMethod("getPath")
            getPath.invoke(volume) as? String
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            // 降级：尝试 getPathFile
            try {
                val getPathFile = volume.javaClass.getMethod("getPathFile")
                val file = getPathFile.invoke(volume) as? File
                file?.absolutePath
            } catch (e2: Exception) {
                if (e2 is kotlinx.coroutines.CancellationException) throw e2
                null
            }
        }
    }

    /** 格式化为 GB 字符串 */
    private fun formatGB(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f", bytes / 1024.0)
            else -> bytes.toString()
        }
    }
}
