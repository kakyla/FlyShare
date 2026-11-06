package com.tvflyshare.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 内置预览的类型判定与内容读取（第一期：文本 + 图片）。
 *
 * 设计边界：
 * - 不引入任何第三方库：图片走 BitmapFactory 采样解码，文本自行探测编码。
 * - 耗时操作全部在 Dispatchers.IO 上完成，调用方拿到的就是可直接渲染的结果。
 * - 大文件一律「只读开头一段 + 明确告知已截断」，绝不把整个文件塞进内存。
 */
enum class PreviewMediaType {
    /** 位图图片 */
    IMAGE,

    /** 纯文本 */
    TEXT,
}

/**
 * 文本预览的读取结果。
 *
 * @param lines 已按显示宽度硬切好的行（每行不超过 [PreviewSupport.MAX_LINE_CHARS] 字符）
 * @param truncated 是否因为文件太大只显示了开头一部分
 * @param charsetName 实际采用的编码名，用于在界面上如实告知用户
 * @param totalBytes 文件真实字节数（未截断时的原始大小）
 */
data class TextPreview(
    val lines: List<String>,
    val truncated: Boolean,
    val charsetName: String,
    val totalBytes: Long,
)

/**
 * 图片预览的解码结果。
 *
 * @param bitmap 已按屏幕尺寸降采样后的位图
 * @param sourceWidth 原图宽度（像素）
 * @param sourceHeight 原图高度（像素）
 */
data class BitmapPreview(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

object PreviewSupport {

    // ── 可预览的后缀名白名单 ──
    // 只列"确定能用位图/纯文本方式呈现"的格式：
    // svg 需要矢量渲染器、gif 只显示首帧、heic 低版本系统解不出，这些暂时都不算内置可预览。
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")

    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "csv", "ini", "cfg", "conf",
        "properties", "yaml", "yml", "html", "htm", "css", "js", "ts", "kt", "java",
        "py", "sh", "bat", "srt", "ass", "vtt",
    )

    /** 文本最多读取的字节数，超出部分不再读入（只显示开头） */
    private const val MAX_TEXT_BYTES = 1024L * 1024

    /** 解码后最多保留的字符数（约 800KB 内存，避免超大文本撑爆堆） */
    private const val MAX_TEXT_CHARS = 400_000

    /** 单行超过这个长度就硬切，避免 LazyColumn 里出现超长单行拖慢测量 */
    const val MAX_LINE_CHARS = 500

    /** 探测编码时最多看头部这么多字节 */
    private const val CHARSET_SAMPLE_BYTES = 8 * 1024

    /** 原图任一边超过这个值就不解码（防 OOM，也防构造出来的畸形文件） */
    private const val MAX_IMAGE_EDGE = 20_000

    /**
     * 按文件名判定可预览类型。
     *
     * 刻意只做字符串判断、不碰磁盘：这个方法会在点击回调（主线程）里调用，
     * 文件是否存在由预览页自己加载失败后如实报错。
     */
    fun typeOf(fileName: String): PreviewMediaType? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return when (ext) {
            in IMAGE_EXTENSIONS -> PreviewMediaType.IMAGE
            in TEXT_EXTENSIONS -> PreviewMediaType.TEXT
            else -> null
        }
    }

    /** 是否为支持内置预览的文件名 */
    fun canPreview(fileName: String): Boolean = typeOf(fileName) != null

    /**
     * 读取文本内容。
     *
     * 编码判定顺序：BOM > 严格 UTF-8 校验 > GB18030（GBK 的超集）。
     * 国内 Windows/电视上大量 txt 是 GBK 系编码，用 UTF-8 硬解会整屏乱码，
     * 所以不做「默认 UTF-8」而是先验证再回退。
     */
    suspend fun readText(file: File): Result<TextPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val totalBytes = file.length()
            val readLimit = minOf(totalBytes, MAX_TEXT_BYTES).toInt()
            if (readLimit <= 0) {
                return@runCatching TextPreview(emptyList(), false, "UTF-8", totalBytes)
            }

            val buffer = ByteArray(readLimit)
            var offset = 0
            file.inputStream().use { input ->
                while (offset < readLimit) {
                    val read = input.read(buffer, offset, readLimit - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
            val raw = if (offset == buffer.size) buffer else buffer.copyOf(offset)

            val guess = guessCharset(raw)
            var text = decode(raw, guess)

            var truncated = totalBytes > MAX_TEXT_BYTES
            if (text.length > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS)
                truncated = true
            }

            TextPreview(
                lines = splitLines(text),
                truncated = truncated,
                charsetName = guess.displayName,
                totalBytes = totalBytes,
            )
        }
    }

    /**
     * 解码图片，按需求尺寸降采样。
     *
     * @param reqWidth 展示区宽度（像素）
     * @param reqHeight 展示区高度（像素）
     */
    suspend fun decodeBitmap(file: File, reqWidth: Int, reqHeight: Int): Result<BitmapPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sourceWidth = bounds.outWidth
                val sourceHeight = bounds.outHeight
                require(sourceWidth > 0 && sourceHeight > 0) { "这个图片文件解不出来" }
                require(sourceWidth <= MAX_IMAGE_EDGE && sourceHeight <= MAX_IMAGE_EDGE) {
                    "图片尺寸太大（${sourceWidth}×$sourceHeight）"
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight, reqWidth, reqHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                    ?: error("这个图片文件解不出来")
                BitmapPreview(bitmap, sourceWidth, sourceHeight)
            }.recoverCatching { error ->
                if (error is OutOfMemoryError) error("内存不够，这张图太大")
                throw error
            }
        }

    /**
     * 列出同目录下的图片，供左右键切换。
     *
     * 当前文件本身一定会出现在结果里（即使它的后缀名不在白名单内，
     * 例如从收藏夹直接进来的路径）。
     */
    suspend fun siblingImages(file: File): List<File> = withContext(Dispatchers.IO) {
        val parent = file.parentFile ?: return@withContext listOf(file)
        val siblings = parent.listFiles { child ->
            child.isFile && typeOf(child.name) == PreviewMediaType.IMAGE
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        if (siblings.any { it.absolutePath == file.absolutePath }) siblings else listOf(file)
    }

    // ── 内部实现 ──

    private data class CharsetGuess(
        val charset: Charset,
        val displayName: String,
        val bomLength: Int,
    )

    private fun guessCharset(bytes: ByteArray): CharsetGuess {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return CharsetGuess(Charsets.UTF_8, "UTF-8", 3)
        }
        if (bytes.size >= 2) {
            val first = bytes[0]
            val second = bytes[1]
            if (first == 0xFF.toByte() && second == 0xFE.toByte()) {
                return CharsetGuess(Charsets.UTF_16LE, "UTF-16LE", 2)
            }
            if (first == 0xFE.toByte() && second == 0xFF.toByte()) {
                return CharsetGuess(Charsets.UTF_16BE, "UTF-16BE", 2)
            }
        }

        val sample = bytes.copyOf(minOf(bytes.size, CHARSET_SAMPLE_BYTES))
        return if (isStrictUtf8(sample)) {
            CharsetGuess(Charsets.UTF_8, "UTF-8", 0)
        } else {
            CharsetGuess(Charset.forName("GB18030"), "GB18030", 0)
        }
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun decode(bytes: ByteArray, guess: CharsetGuess): String {
        val body = if (guess.bomLength > 0 && bytes.size > guess.bomLength) {
            bytes.copyOfRange(guess.bomLength, bytes.size)
        } else {
            bytes
        }
        return try {
            guess.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 兜底：替换解码都失败时按 UTF-8 强行转（会出现少量替换符，但不至于整屏空白）
            String(body, Charsets.UTF_8)
        }
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            appendWrappedLine(result, text, start, end)
            start = end + 1
        }
        return result
    }

    /** 单行过长时按 [MAX_LINE_CHARS] 硬切成多行 */
    private fun appendWrappedLine(out: MutableList<String>, text: String, from: Int, to: Int) {
        var start = from
        var end = to
        // 去掉 Windows 换行留下的 \r
        if (end > start && text[end - 1] == '\r') end -= 1

        if (end <= start) {
            out.add("")
            return
        }
        while (start < end) {
            val next = minOf(start + MAX_LINE_CHARS, end)
            out.add(text.substring(start, next))
            start = next
        }
    }

    /** 标准降采样算法：保证解码结果不小于需求尺寸的同时尽量省内存 */
    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        var sampleSize = 1
        var width = sourceWidth
        var height = sourceHeight
        while (width / 2 >= reqWidth && height / 2 >= reqHeight) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize
    }
}
