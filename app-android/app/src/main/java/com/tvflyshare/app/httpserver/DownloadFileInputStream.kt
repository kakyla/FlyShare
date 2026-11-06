package com.tvflyshare.app.httpserver

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 下载 / 断点续传的响应体输入流（自关闭）。
 *
 * 为什么不能把裸 FileInputStream 直接交给响应：
 * NanoHTTPD 发送响应体时，若客户端中途断开（关闭网页、拖动视频进度条、取消下载），
 * 底层 socket 写失败会抛异常，而它不会代为关闭我们交给它的流，文件句柄就此泄漏。
 * 反复几次会把进程可用句柄耗尽，表现为服务端卡死或崩溃。
 *
 * 对策——三条路径都不依赖调用方主动 close 也能释放句柄：
 * 1. 懒打开：首次真正读取时才开句柄，请求在前期就被拒绝的场景不会白开流；
 * 2. 读尽自关：读完约定长度、准备返回 EOF 时主动关句柄（覆盖正常下载完成）；
 * 3. 异常自关：读取过程中出错先关句柄再抛出（覆盖客户端断连）。
 *
 * skip 循环补齐保留在内部：InputStream.skip 契约允许少跳，若不补齐会让
 * 续传起点错位、客户端拼出损坏文件。
 */
internal class DownloadFileInputStream(
    private val file: File,
    private val startOffset: Long,
    private val length: Long
) : InputStream() {

    private var stream: FileInputStream? = null
    private var remaining: Long = length.coerceAtLeast(0)

    private fun ensureOpen(): FileInputStream {
        stream?.let { return it }
        val opened = FileInputStream(file)
        return try {
            var skipped = 0L
            while (skipped < startOffset) {
                val n = opened.skip(startOffset - skipped)
                if (n <= 0) break
                skipped += n
            }
            stream = opened
            opened
        } catch (e: Throwable) {
            runCatching { opened.close() }
            throw e
        }
    }

    private fun releaseQuietly() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // 关闭失败无补救手段，且不影响本次请求已读数据，忽略
        }
        stream = null
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) {
            releaseQuietly()
            return -1
        }
        val cap = minOf(len.toLong(), remaining).toInt()
        return try {
            val n = ensureOpen().read(b, off, cap)
            if (n <= 0) {
                remaining = 0
                releaseQuietly()
                -1
            } else {
                remaining -= n
                n
            }
        } catch (e: IOException) {
            releaseQuietly()
            throw e
        }
    }

    override fun available(): Int =
        if (remaining <= 0) 0 else minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    override fun skip(n: Long): Long {
        if (n <= 0 || remaining <= 0) return 0
        val skipCount = minOf(n, remaining)
        remaining -= skipCount
        return try {
            ensureOpen().skip(skipCount)
        } catch (e: IOException) {
            releaseQuietly()
            throw e
        }
    }

    override fun close() {
        releaseQuietly()
    }
}
