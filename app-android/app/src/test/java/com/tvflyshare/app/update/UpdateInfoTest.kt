package com.tvflyshare.app.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新包指纹必填校验。
 *
 * 2026-11-08 审计修复：对外 README 写的是「按配置里的文件指纹校验后安装」，
 * 旧实现却在指纹缺失时静默跳过。这几个用例守住「没有合格指纹就不放行」的契约。
 */
class UpdateInfoTest {

    // 与 UpdateRepository 里的解析配置保持一致；复用实例，避免每次调用都新建解析器
    private val format = Json { ignoreUnknownKeys = true }

    @Test
    fun 合法的指纹通过() {
        assertTrue(isValidApkSha256("bcba314d497f72f584b0a24e0508519adc290d0c2d9499a33901f36923bc1d58"))
        assertTrue(isValidApkSha256("BCBA314D497F72F584B0A24E0508519ADC290D0C2D9499A33901F36923BC1D58"))
        assertTrue(isValidApkSha256("  " + "a".repeat(64) + "  "))
    }

    @Test
    fun 缺失或格式不对一律拒绝() {
        assertFalse(isValidApkSha256(null))
        assertFalse(isValidApkSha256(""))
        assertFalse(isValidApkSha256("   "))
        assertFalse(isValidApkSha256("a".repeat(63)))
        assertFalse(isValidApkSha256("a".repeat(65)))
        assertFalse(isValidApkSha256("z".repeat(64)))
        assertFalse(isValidApkSha256("<APK 的 SHA-256 指纹>"))
    }

    /**
     * 跨版本直升（2026-11-08 追问）：老版本 App 读到的是同一份 update.json，
     * 新版 App 也要能读老版本发过的配置。实测字段结构兼容、未知的后续字段被忽略、
     * 指纹仍被新版认作必填合格项，versionCode 也能正常比较出「有新版本」。
     */
    @Test
    fun 老版本格式的配置文件新版照样能解析且指纹有效() {
        // 与 v2.6.14 线上配置同构：五个字段齐全；多塞一个未来字段模拟后续扩展
        val legacyJson = """
            {"versionCode":20614,"versionName":"2.6.14",
             "apkUrl":"https://example.invalid/FlyShare-2.6.14.apk",
             "apkSha256":"bcba314d497f72f584b0a24e0508519adc290d0c2d9499a33901f36923bc1d58",
             "updateLog":"历史版本配置","futureField":true}
        """.trimIndent()
        val info = format.decodeFromString<UpdateInfo>(legacyJson)
        assertEquals(20614, info.versionCode)
        assertEquals("2.6.14", info.versionName)
        assertTrue(isValidApkSha256(info.apkSha256))
        assertTrue(20617 > info.versionCode)
    }
}
