package com.tvflyshare.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件扫描器
 *
 * 职责：集中处理本地文件的扫描逻辑，将机制从 ViewModel 中完全解耦。
 * - 普通目录扫描：返回先目录后文件，按名称排序。
 * - 媒体扫描：利用 MediaStore 提升速度。
 * - APK扫描：采用有限深度（默认6层）遍历，兼顾性能与准确性。
 */
object FileScanner {

    /**
     * 2026-10-10 审计修复：SimpleDateFormat 非线程安全——本 object 的扫描均跑在多线程
     * Dispatchers.IO 上，原先共享单例实例并发 format() 会产出乱码日期甚至内部越界异常
     * （被 runCatching 吞掉表现为整列表偶发为空）。改为每次调用新建实例（对齐 TimeUtils 做法）。
     */
    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    /**
     * 扫描指定目录
     *
     * @param path 目录路径
     * @return Result 包含文件列表，失败返回 Result.failure
     */
    suspend fun scanDirectory(path: String): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return@runCatching emptyList()
            }

            val files = dir.listFiles()
                ?: return@runCatching emptyList()

            // 分类：目录 vs 文件
            val dirs = files.filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
            val regularFiles = files.filter { it.isFile }
                .sortedBy { it.name.lowercase() }

            // 合并：先目录后文件
            (dirs + regularFiles).map { file ->
                val childCount = if (file.isDirectory) {
                    // 2026-10-04 审计 P2 修复：排除隐藏文件，与列表默认过滤口径一致
                    file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                } else {
                    0
                }
                FileEntry(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified(),
                    sizeText = if (file.isFile) FileVisualUtils.formatFileSize(file.length()) else "",
                    dateText = formatDate(file.lastModified()),
                    childCount = childCount,
                )
            }
        }
    }

    private val videoExtensions = listOf(".mp4", ".mkv", ".mov", ".avi", ".wmv", ".flv", ".rmvb")
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
    private val audioExtensions = listOf(".mp3", ".flac", ".wav", ".aac", ".m4a", ".ogg")

    private fun buildMediaSelection(uri: Uri): Pair<String?, Array<String>?> {
        val extensions = when (uri) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> videoExtensions
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> imageExtensions
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI -> audioExtensions
            else -> return Pair(null, null)
        }
        val selection = StringBuilder()
        val selectionArgs = mutableListOf<String>()
        selection.append("(")
        for (i in extensions.indices) {
            if (i > 0) selection.append(" OR ")
            selection.append("${MediaStore.MediaColumns.DATA} LIKE ?")
            selectionArgs.add("%${extensions[i]}")
        }
        selection.append(")")
        return Pair(selection.toString(), selectionArgs.toTypedArray())
    }

    /**
     * 获取 MediaStore 指定类型的数量
     */
    suspend fun getMediaCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val (selection, selectionArgs) = buildMediaSelection(uri)
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                cursor.count
            } ?: 0
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            0
        }
    }

    /**
     * 获取 MediaStore 指定类型的文件列表
     */
    suspend fun getMediaFiles(context: Context, uri: Uri): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        try {
            val results = mutableListOf<FileEntry>()
            val (selection, selectionArgs) = buildMediaSelection(uri)
            context.contentResolver.query(
                uri,
                null, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && results.size < 500) {
                    val path = if (pathCol >= 0) cursor.getString(pathCol) else null
                    if (path == null) continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else File(path).name
                    if (name == null || name.endsWith(".dat", ignoreCase = true)) continue
                    
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else File(path).length()
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) * 1000 else File(path).lastModified()
                    
                    results.add(
                        FileEntry(
                            name = name,
                            path = path,
                            isDirectory = false,
                            size = size,
                            lastModified = date,
                            sizeText = FileVisualUtils.formatFileSize(size),
                            dateText = formatDate(date),
                        )
                    )
                }
            }
            Result.success(results)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 采用限制深度的目录遍历获取 APK 数量
     * @param maxDepth 最大深度，默认 6 层
     */
    suspend fun getApkCount(maxDepth: Int = 6, autoFixSuffix: Boolean = false): Int = withContext(Dispatchers.IO) {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            var count = 0
            fun countApkFiles(dir: File, depth: Int) {
                if (depth <= 0 || !dir.exists() || !dir.isDirectory) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isFile) {
                        // 开启自动修正时，遍历中顺手把 .apk1/.apk.1 还原为 .apk 并计入，避免二次遍历外置存储
                        val f = if (autoFixSuffix) restoreApkSuffix(file) else file
                        if (f.name.endsWith(".apk", ignoreCase = true)) {
                            count++
                        }
                    } else if (file.isDirectory && !file.name.startsWith(".") && file.name != "Android") {
                        countApkFiles(file, depth - 1)
                    }
                }
            }
            countApkFiles(rootDir, maxDepth)
            count
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            0
        }
    }

    /**
     * 采用限制深度的目录遍历获取 APK 文件列表
     * @param maxDepth 最大深度，默认 6 层
     */
    suspend fun getApkFiles(maxDepth: Int = 6, autoFixSuffix: Boolean = false): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            val results = mutableListOf<FileEntry>()
            fun collectApkFiles(dir: File, depth: Int) {
                if (depth <= 0 || !dir.exists() || !dir.isDirectory) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isFile) {
                        // 开启自动修正时，遍历中顺手把 .apk1/.apk.1 还原为 .apk 并计入，避免二次遍历外置存储
                        val f = if (autoFixSuffix) restoreApkSuffix(file) else file
                        if (f.name.endsWith(".apk", ignoreCase = true)) {
                            results.add(
                                FileEntry(
                                    name = f.name,
                                    path = f.absolutePath,
                                    isDirectory = false,
                                    size = f.length(),
                                    lastModified = f.lastModified(),
                                    sizeText = FileVisualUtils.formatFileSize(f.length()),
                                    dateText = formatDate(f.lastModified()),
                                )
                            )
                        }
                    } else if (file.isDirectory && !file.name.startsWith(".") && file.name != "Android") {
                        collectApkFiles(file, depth - 1)
                    }
                }
            }
            collectApkFiles(rootDir, maxDepth)
            Result.success(results.sortedByDescending { it.lastModified })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 将社交应用改名后的安装包还原为 `.apk`（通用匹配，覆盖微信/QQ 及所有同类应用）。
     *
     * 动机：微信/QQ 在接收 APK 时会把扩展名改成 `.apk.1`/`.apk1`（在 `.apk` 后追加数字/点号）
     * 以阻止自动安装，导致本应用「安装包」扫描（只认 `.apk`）找不到，用户只能在下载目录手动改名。
     * 其它社交/办公应用（钉钉、飞书等）大多改用自家扩展名（如 `.dingtalk`），属不同机制，
     * 不在本函数范围内；本函数只抓"`.apk` + 数字/点/下划线尾缀"这个统一的改名签名，
     * 因此无需为某个 App 单独枚举后缀。详见 `restoreApkSuffix` 的安全约束与匹配规则。
     *
     * 扫描范围与 `getApkFiles` 完全一致（外置存储根、6 层、跳过隐藏目录与 `Android`），
     * 因此不论社交应用把文件落在哪个下载目录都能抓到，不依赖具体路径。
     *
     * @param maxDepth 最大深度，默认 6 层
     * @return 实际成功改名的文件数
     */
    suspend fun fixApkSuffix(maxDepth: Int = 6): Int = withContext(Dispatchers.IO) {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            var fixed = 0
            fun walk(dir: File, depth: Int) {
                if (depth <= 0 || !dir.exists() || !dir.isDirectory) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isFile) {
                        // 复用 restoreApkSuffix：改名成功（返回文件与入参不同）才计数
                        if (restoreApkSuffix(file) != file) fixed++
                    } else if (file.isDirectory && !file.name.startsWith(".") && file.name != "Android") {
                        walk(file, depth - 1)
                    }
                }
            }
            walk(rootDir, maxDepth)
            fixed
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            0
        }
    }

    /**
     * 冲突顺延：目标 .apk 已存在时，返回 `原名_2.apk`、`原名_3.apk` 等不冲突的名字。
     */
    private fun resolveCollision(dir: File, baseName: String): File {
        val dot = baseName.lastIndexOf('.')
        val namePart = if (dot > 0) baseName.substring(0, dot) else baseName
        val extPart = if (dot > 0) baseName.substring(dot) else ""
        var i = 2
        var candidate: File
        do {
            candidate = File(dir, "${namePart}_$i$extPart")
            i++
        } while (candidate.exists() && i < 10000)
        return candidate
    }

    /**
     * 把被社交应用改名的安装包还原为 `.apk`，返回改名后的 File；若无需 / 无法改名则返回原 file。
     *
     * 通用匹配（一次覆盖所有社交应用，无需逐个枚举 App）：
     * 社交应用转发 APK 时的统一"改名签名"是——在 `.apk` 之后追加一串数字/点/下划线
     * （微信/QQ 的 `.apk.1`、`.apk1`，以及将来任何"在 .apk 后追加数字点号"的应用）。
     * 因此只认"文件名含 `.apk`、且其后的尾缀仅由 数字/点/下划线 组成直到文件名末尾"的结构，
     * 不再硬编码具体后缀。钉钉等改用自家扩展名（如 `.dingtalk`）的非数字后缀不在范围内，
     * 属不同机制，本函数不触碰——既避免误改无关文件，也不为某个 App 单独补一次。
     *
     * 安全约束（避免误改）：
     * 1. 仅匹配上述结构；名字里夹带 "apk" 但不是该结构的（如 `电影APK.1预告.mp4`）不动。
     * 2. 改名前校验内容为 ZIP/APK 本地文件头签名 `PK\x03\x04`；非安装包内容跳过。
     * 3. 仅 `renameTo` 改扩展名，字节零改动、可逆。
     * 4. 目标 `.apk` 已存在则顺延 `原名_2.apk`，绝不覆盖。
     *
     * 供 `getApkCount` / `getApkFiles` 在扫描时顺手完成改名（避免二次遍历），
     * 也供 `fixApkSuffix` 复用。
     */
    private fun restoreApkSuffix(file: File): File {
        if (!file.isFile) return file
        val lower = file.name.lowercase()
        if (lower.endsWith(".apk")) return file // 已是标准 apk，无需处理
        val idx = lower.lastIndexOf(".apk")
        if (idx < 0) return file // 文件名根本不含 .apk
        val tail = lower.substring(idx + 4) // ".apk" 之后的尾缀
        // 仅允许 数字/点/下划线（社交应用追加的版本/序号后缀）；
        // 其余扩展名（.dingtalk/.bak/.tmp…）一律不碰，避免误改
        if (!tail.all { it.isDigit() || it == '.' || it == '_' }) return file
        val targetName = file.name.substring(0, idx + 4) // 截到 ".apk" 处
        val parent = file.parentFile ?: return file
        val target = File(parent, targetName)
        val finalTarget = if (target.exists()) resolveCollision(parent, targetName) else target
        return if (isZipOrApk(file) && file.canWrite() && file.renameTo(finalTarget)) finalTarget else file
    }

    /**
     * 判断文件是否为 ZIP/APK（本地文件头签名 `PK\x03\x04`）。
     * 仅读取文件头四字节，不做完整解析，开销极低。
     */
    private fun isZipOrApk(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        } catch (e: Exception) {
            return false
        }
    }

}
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val sizeText: String,
    val dateText: String,
    val childCount: Int = 0, // 文件夹的直接子项数量（文件和文件夹总数）
)
