package com.tvflyshare.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [isPathInsideRoot] / [isHiddenTempFile] 回归测试（纯 JVM，无 Android 依赖）。
 *
 * 为什么给这两条函数单独上测试：它们是「同局域网任何人都能访问共享目录」这件事的
 * 最后一道闸门——HTTP 服务 9 个涉路径接口全靠 [isPathInsideRoot] 把关，
 * 一旦判据退化成朴素前缀判断，越界读取就成立了。这类逻辑改一次必须能被测出来。
 *
 * 覆盖：
 * - 根目录自身合法（删除接口另有"不许删根"的独立判断，这里只管越界）；
 * - 子目录/子文件合法；
 * - 同级目录名共享前缀（TVfile vs TVfileBackup）必须判为越界——这是最容易写错的一处；
 * - `../` 逃出共享根必须判为越界；
 * - 传输中间态临时文件被识别，而 macOS 资源 fork 类 `._xxx` 不能被误伤。
 */
class PathSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun 共享根自身算在内部() {
        val root = tmp.newFolder("TVfile")
        assertTrue(isPathInsideRoot(root, root))
    }

    @Test
    fun 子目录与子文件算在内部() {
        val root = tmp.newFolder("TVfile")
        val sub = File(root, "电影/2026")
        sub.mkdirs()
        assertTrue(isPathInsideRoot(root, sub))
        assertTrue(isPathInsideRoot(root, File(root, "a.mp4")))
    }

    @Test
    fun 同级目录共享前缀必须判为越界() {
        // root=TVfile、child=TVfileBackup：child 的 canonicalPath 以 "TVfile" 开头，
        // 朴素 startsWith 会误判为内部，正确的分隔符边界判断必须拦下它。
        val root = tmp.newFolder("TVfile")
        val sibling = File(tmp.root, "TVfileBackup")
        sibling.mkdirs()
        assertFalse(isPathInsideRoot(root, sibling))
        assertFalse(isPathInsideRoot(root, File(sibling, "x.txt")))
    }

    @Test
    fun 上级目录逃出共享根判为越界() {
        val root = tmp.newFolder("TVfile")
        assertFalse(isPathInsideRoot(root, File(root, "../outside.txt")))
        assertFalse(isPathInsideRoot(root, File(tmp.root, "elsewhere.txt")))
    }

    @Test
    fun 上传与下载中间态临时文件被识别() {
        assertTrue(isHiddenTempFile("._flyup_tmp_abc.mp4"))
        assertTrue(isHiddenTempFile("._flydl_tmp_abc.mp4"))
    }

    @Test
    fun macOS资源fork类文件不被误伤() {
        assertFalse(isHiddenTempFile("._DS_Store"))
        assertFalse(isHiddenTempFile("._我的文档.docx"))
        assertFalse(isHiddenTempFile("正常文件.mp4"))
    }
}
