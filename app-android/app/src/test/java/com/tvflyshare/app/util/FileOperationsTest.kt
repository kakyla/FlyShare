package com.tvflyshare.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [FileOperations.sanitizeFileName] 净化逻辑回归测试（纯 JVM，无 Android 依赖）。
 *
 * 该函数是「远端新建文件夹」路径穿越防护与名称清洗的核心，覆盖：
 * - 非法字符（\ / : * ? " < > | 及换行）替换为下划线；
 * - 路径穿越经 File(name) 剥离目录分量（../../etc/passwd → passwd）；
 * - 超长名称截断到约 200 字节并保留扩展名；
 * - 基名首尾空格去除且保留扩展名；
 * - 普通文件名保持不变。
 *
 * 注意：测试输入刻意使用多字母前缀（如 ab: 而非 a:），以规避 Windows 主机 JVM 将
 * 「单字母:」解析为盘符导致 File.name 丢掉首字母的宿主机 artifact（真机 Linux 无此问题）。
 */
class FileOperationsTest {

    @Test
    fun 非法字符被替换为下划线() {
        val r = FileOperations.sanitizeFileName("ab:cd*ef?gh")
        assertEquals("ab_cd_ef_gh", r)
        assertFalse(r.contains(Regex("[\\\\/:*?\"<>|\r\n]")))
    }

    @Test
    fun 路径穿越被File_name剥离() {
        assertEquals("passwd", FileOperations.sanitizeFileName("../../etc/passwd"))
        assertEquals("secret", FileOperations.sanitizeFileName("../secret"))
    }

    @Test
    fun 超长文件名被截断到约200字节() {
        val long = "x".repeat(300) + ".txt"
        val r = FileOperations.sanitizeFileName(long)
        val bytes = r.toByteArray().size
        assert(bytes <= 200) { "字节数应 <=200，实际 " + bytes }
        assert(r.length < long.length)
    }

    @Test
    fun 普通文件名保持不变() {
        val name = "我的文档 2026.pdf"
        assertEquals(name, FileOperations.sanitizeFileName(name))
    }

    @Test
    fun 首尾空格的基名被去除且保留扩展名() {
        assertEquals("lead.txt", FileOperations.sanitizeFileName("  lead.txt"))
    }
}
