package com.tvflyshare.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
// isActive 是 kotlinx.coroutines 对 CoroutineScope 的扩展属性，必须显式导入才可解析
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 本地文件缩略图加载器（图片 + 视频）。
 *
 * 设计边界（对齐"弱设备 / 车机不能卡"的硬约束）：
 * - 解码全部在 Dispatchers.IO；主线程只负责画图，绝不阻塞。
 * - 同时解码数上限 4（Semaphore），弱芯片不会被打满、不发热掉帧。
 * - 两级缓存：内存 LruCache（按字节上限，老设备也安全）+ 磁盘缓存（再进同一文件夹秒出，
 *   按 mtime 做 LRU 淘汰，条目数 / 总字节任一越线才裁，不会只进不出）。
 * - 滑出屏幕的条目 LaunchedEffect 取消时，正在排队的任务取许可前先判活，避免无谓解码。
 *
 * 图片：复用 PreviewSupport.decodeBitmap（已做 inSampleSize 降采样 + OOM 护栏），按真实路径解码。
 * 视频：走 MediaStore 系统缓存缩略图（按路径反查 _ID 后取 MINI_KIND），绝不自己抽首帧（那才贵）。
 *       SMB / FTP / 互传 不进这里（FileItemGrid 仅本地文件管理器使用；即便误传非本地路径，
 *       解码或反查失败即回退图标，不会崩）。
 */
object ThumbnailLoader {

    // 内存缓存：按字节计，上限 16MB；超出 LruCache 自动淘汰最久未用，老设备内存安全。
    private const val MEMORY_CACHE_BYTES = 16 * 1024 * 1024
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    // 同时解码上限：弱设备友好，避免快速滚动时打满 CPU / 发热掉帧。
    private val decodeSemaphore = Semaphore(4)

    // 磁盘缓存护栏（2026-10-31 修）：此前是「超过 600 个文件就彻底停止写入」且无任何淘汰，
    // 而缓存键含 路径+mtime+size——文件一旦被删或被改，旧条目就再也读不到，等于永久垃圾堆在
    // 应用 cache 里；堆满 600 之后新缩略图反而全部不再落盘，冷启动每次都要重新解码。
    // 现改为按最后修改时间做 LRU 淘汰 + 总字节上限，写入后触发一次裁剪。
    private const val DISK_CACHE_MAX_FILES = 400   // 触发裁剪的条目数
    private const val DISK_CACHE_TRIM_TO = 250     // 裁剪后保留的条目数
    private const val DISK_CACHE_MAX_BYTES = 24L * 1024 * 1024 // 触发裁剪的总字节

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "wmv", "flv", "rmvb", "ts", "m4v")

    fun isImageEntry(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    fun isVideoEntry(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTS
    }

    /**
     * 加载缩略图。返回 null 表示无法生成（调用方回退到通用图标）。
     *
     * @param reqSize 目标边长（px），用于降采样与缓存键。
     */
    suspend fun loadThumbnail(
        context: Context,
        path: String,
        lastModified: Long,
        size: Long,
        isVideo: Boolean,
        reqSize: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey(path, lastModified, size, isVideo, reqSize)

        // ① 内存缓存命中
        memoryCache.get(key)?.let { return@withContext it }

        // ② 磁盘缓存命中（应用私有 cache，卸载即清）
        val dir = File(context.cacheDir, "thumbnails")
        runCatching {
            val disk = File(dir, key)
            if (disk.exists()) {
                BitmapFactory.decodeFile(disk.absolutePath)?.let {
                    // 命中即视为最近使用：更新 mtime，让 LRU 裁剪时不会把它当旧条目删掉
                    runCatching { disk.setLastModified(System.currentTimeMillis()) }
                    memoryCache.put(key, it)
                    return@withContext it
                }
            }
        }

        // ③ 解码（受并发上限约束）
        // 滑出屏幕被取消时，取许可前先判活，避免无谓解码。
        // 两个前提缺一不可：① 判断必须在有 CoroutineScope 接收者的作用域内 —— withPermit 的
        //    lambda 是普通 suspend () -> T（无接收者），写在里面 isActive 无法解析，故放在 withContext 体内；
        // ② isActive 是 kotlinx.coroutines 的扩展属性，需在文件顶部 import 才能使用。
        if (!isActive) return@withContext null
        val bmp = decodeSemaphore.withPermit {
            if (isVideo) loadVideoThumbnail(context, path, reqSize)
            else PreviewSupport.decodeBitmap(File(path), reqSize, reqSize).getOrNull()?.bitmap
        } ?: return@withContext null

        memoryCache.put(key, bmp)
        // 落磁盘缓存（不阻塞调用方）
        runCatching { storeToDisk(dir, File(dir, key), bmp) }
        bmp
    }

    /**
     * 视频缩略图：按路径精确反查 MediaStore 视频 _ID，再取系统缓存的 MINI_KIND 缩略图。
     * 这是系统已缓存的帧，几乎零成本；绝不自己用 MediaMetadataRetriever 抽首帧。
     */
    private fun loadVideoThumbnail(context: Context, path: String, reqSize: Int): Bitmap? {
        val id = runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull() ?: return null

        val thumb = runCatching {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
            )
        }.getOrNull() ?: return null

        // MINI_KIND 偏大（约 512×384），缩到 reqSize 以收紧内存占用
        return if (thumb.width > reqSize || thumb.height > reqSize) {
            val scale = reqSize.toFloat() / maxOf(thumb.width, thumb.height)
            val w = (thumb.width * scale).toInt().coerceAtLeast(1)
            val h = (thumb.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(thumb, w, h, true).also { if (it != thumb) thumb.recycle() }
        } else {
            thumb
        }
    }

    private fun storeToDisk(dir: File, file: File, bmp: Bitmap) {
        if (!dir.exists() && !dir.mkdirs()) return
        runCatching {
            // WebP 有损（quality 80）：缩略图不需要无损，同等观感下体积远小于 PNG。
            // API 30 起 WEBP 常量被拆成 WEBP_LOSSY / WEBP_LOSSLESS，按需分流（minSdk 21 仍走 WEBP）。
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            file.outputStream().use { out -> bmp.compress(format, 80, out) }
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
        // 写完后按 LRU 裁一次：先删最久没被读/写的条目，而不是像以前那样直接停止写入
        trimDiskCache(dir)
    }

    /**
     * 磁盘缓存 LRU 裁剪：只有当条目数或总字节越过阈值才动手，裁到 3/4 水位为止，
     * 避免每次写入都遍历目录。全程在 IO 线程，且整体包在 runCatching 里，失败不影响出图。
     */
    private fun trimDiskCache(dir: File) {
        runCatching {
            val files = dir.listFiles()?.toMutableList() ?: return
            var totalBytes = files.sumOf { it.length() }
            if (files.size <= DISK_CACHE_MAX_FILES && totalBytes <= DISK_CACHE_MAX_BYTES) return

            files.sortBy { it.lastModified() } // 最久未用的排前面
            val targetBytes = DISK_CACHE_MAX_BYTES * 3 / 4
            while (files.size > DISK_CACHE_TRIM_TO || totalBytes > targetBytes) {
                val oldest = files.removeAt(0)
                val len = oldest.length()
                if (oldest.delete()) totalBytes -= len
                if (files.isEmpty()) break
            }
        }
    }

    private fun cacheKey(
        path: String,
        lastModified: Long,
        size: Long,
        isVideo: Boolean,
        reqSize: Int,
    ): String {
        val raw = "$path|$lastModified|$size|${if (isVideo) 'v' else 'i'}|$reqSize"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
