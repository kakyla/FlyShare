package com.tvflyshare.app.network

import android.content.Context
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom

/**
 * 连接记录加密存储的回归测试。
 *
 * 守住三条契约：
 * - 系统 Keystore 不可用时的软件兜底仍然只写密文，盘上不出现明文密码；
 * - 密文带认证，篡改或换钥匙都解不开，且解不开时返回 null 而不是抛到 UI 层；
 * - 旧版降级期写下的明文记录不会在系统加密恢复后凭空消失，且明文文件会被清空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureConnectionPrefsTest {

    private val key = ByteArray(SoftwareCipher.KEY_BYTES).also { SecureRandom().nextBytes(it) }
    private val listKey = "smb_connection_list"
    private val secret = "p@ssw0rd-NEVER-PLAINTEXT"

    private fun sampleJson(): String =
        """[{"host":"192.168.1.9","share":"video","username":"tv","password":"$secret"}]"""

    @Test
    fun 软件加密往返能还原原文() {
        val cipher = SoftwareCipher.encrypt(sampleJson(), key)
        assertEquals(sampleJson(), SoftwareCipher.decrypt(cipher, key))
    }

    @Test
    fun 密文不含明文且每次加密结果不同() {
        val first = SoftwareCipher.encrypt(sampleJson(), key)
        val second = SoftwareCipher.encrypt(sampleJson(), key)
        assertFalse(first.contains(secret))
        assertFalse(first.contains("192.168.1.9"))
        assertNotEquals(first, second) // IV 随机，不能退化成固定输出
    }

    @Test
    fun 密文被篡改后解不开且返回空而不是抛异常() {
        val cipher = SoftwareCipher.encrypt(sampleJson(), key)
        val tampered = cipher.substring(0, cipher.length - 4) + "AAAA"
        assertNull(SoftwareCipher.decrypt(tampered, key))
    }

    @Test
    fun 换一把钥匙解不开() {
        val otherKey = ByteArray(SoftwareCipher.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = SoftwareCipher.encrypt(sampleJson(), key)
        assertNull(SoftwareCipher.decrypt(cipher, otherKey))
    }

    @Test
    fun 空输入与坏输入按无数据处理() {
        assertNull(SoftwareCipher.decrypt(null, key))
        assertNull(SoftwareCipher.decrypt("", key))
        assertNull(SoftwareCipher.decrypt("not-base64$$", key))
    }

    @Test
    fun 存取往返能还原且所有配置文件里都没有明文密码() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = SecureConnectionPrefs(app, "secure_roundtrip_test", listKey)
        prefs.save(sampleJson())
        assertEquals(sampleJson(), prefs.load())

        val xmlDump = File(app.applicationInfo.dataDir, "shared_prefs")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .joinToString(separator = "\n") { runCatching { it.readText() }.getOrDefault("") }
        assertFalse("配置文件里出现了明文密码", xmlDump.contains(secret))
    }

    @Test
    fun 历史明文文件取走后即被清空() {
        val app = RuntimeEnvironment.getApplication()
        val legacyName = "legacy_plain_test"
        val legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        legacy.edit().putString(listKey, sampleJson()).apply()

        val prefs = SecureConnectionPrefs(app, "secure_legacy_test", listKey)
        val taken = prefs.takePlainFrom(legacyName)
        assertEquals(sampleJson(), taken)
        assertTrue(legacy.all.isEmpty())
        assertNull(prefs.takePlainFrom(legacyName))
    }

    @Test
    fun 降级期写下的记录在加密恢复后仍读得到且不会重复() {
        val app = RuntimeEnvironment.getApplication()
        val plainName = "smb_connections_encrypted_plain"
        app.getSharedPreferences(plainName, Context.MODE_PRIVATE)
            .edit().putString(listKey, sampleJson()).apply()

        val store = SmbConnectionStore(app)
        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals(secret, all[0].password)
        assertTrue(app.getSharedPreferences(plainName, Context.MODE_PRIVATE).all.isEmpty())

        // 重建实例模拟 App 重启：记录仍在，且不会被重复叠加
        val again = SmbConnectionStore(app).getAll()
        assertEquals(1, again.size)
        assertEquals(secret, again[0].password)
    }

    /**
     * Robolectric 下系统 Keystore 不可用，EncryptedSharedPreferences 建不起来，
     * 所有数据都走软件兜底。这里注入一份普通 prefs 冒充主引擎，专门覆盖
     * 「主、备引擎同时有内容」这种真机上偶发、测试里必须能稳定复现的场景。
     */
    private fun withFakeSystemEngine(
        prefs: SecureConnectionPrefs,
        app: Context,
        prefsName: String,
    ): android.content.SharedPreferences {
        val fake = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        SecureConnectionPrefs::class.java.getDeclaredField("systemPrefs")
            .apply { isAccessible = true }.set(prefs, fake)
        SecureConnectionPrefs::class.java.getDeclaredField("engineReady")
            .apply { isAccessible = true }.setBoolean(prefs, true)
        return fake
    }

    /** 模拟「系统加密坏掉期间写进软件引擎、设备恢复后主引擎还留着旧内容」的历史状态。 */
    private fun seedSoftwareCopy(app: Context, prefsName: String, json: String, stamp: Long) {
        val swKey = ByteArray(SoftwareCipher.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        app.getSharedPreferences(prefsName + "_sw", Context.MODE_PRIVATE)
            .edit()
            .putString("local_key", Base64.encodeToString(swKey, SoftwareCipher.BASE64_FLAGS))
            .putString(listKey, SoftwareCipher.encrypt(json, swKey))
            .putLong(listKey + "_stamp", stamp)
            .apply()
    }

    @Test
    fun 主备两份同时存在时新的排前面且旧的不会丢() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = SecureConnectionPrefs(app, "secure_merge_test", listKey)
        val oldJson = """[{"host":"10.0.0.1","share":"old","username":"tv","password":"old-pass"}]"""
        val main = withFakeSystemEngine(prefs, app, "secure_merge_test")
        main.edit().putString(listKey, oldJson).putLong(listKey + "_stamp", 1L).apply()

        seedSoftwareCopy(app, "secure_merge_test", sampleJson(), 99L)
        val snap = prefs.snapshot()
        assertFalse(snap.hasUnreadable)
        assertEquals(listOf(sampleJson(), oldJson), snap.values)
    }

    @Test
    fun 兜底内容解不开时标记禁止覆盖写() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = SecureConnectionPrefs(app, "secure_unreadable_test", listKey)
        val main = withFakeSystemEngine(prefs, app, "secure_unreadable_test")
        main.edit().putString(listKey, sampleJson()).putLong(listKey + "_stamp", 1L).apply()

        app.getSharedPreferences("secure_unreadable_test_sw", Context.MODE_PRIVATE)
            .edit().putString(listKey, "这不是密文").apply()

        val snap = prefs.snapshot()
        assertTrue(snap.hasUnreadable)
        assertEquals(listOf(sampleJson()), snap.values)
    }

    @Test
    fun 内容解不开时拒绝覆盖写原有记录不丢() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = SecureConnectionPrefs(app, "smb_blocked_test", listKey)
        val main = withFakeSystemEngine(prefs, app, "smb_blocked_test")
        val originalJson = """[{"host":"10.0.0.1","share":"video","username":"tv","password":"$secret"}]"""
        main.edit().putString(listKey, originalJson).putLong(listKey + "_stamp", 1L).apply()

        app.getSharedPreferences("smb_blocked_test_sw", Context.MODE_PRIVATE)
            .edit().putString(listKey, "broken-cipher").apply()

        val store = SmbConnectionStore(app, prefs)
        store.save(SmbConnectionStore.SmbConnection("10.0.0.2", "movie", "tv", "another-pass"))

        // 主引擎里的原记录必须原封不动：读不出来时宁可这次保存不生效，也不能把整份盖掉
        assertEquals(originalJson, main.getString(listKey, null))
        assertEquals(1, store.getAll().size)
        assertEquals(secret, store.getAll()[0].password)
    }

    @Test
    fun 降级期新增记录在系统加密恢复后仍读得到且新密码不被旧的盖回去() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = SecureConnectionPrefs(app, "smb_merge_test", listKey)
        val main = withFakeSystemEngine(prefs, app, "smb_merge_test")
        val oldJson = """[{"host":"10.0.0.1","share":"video","username":"tv","password":"old-pass"}]"""
        main.edit().putString(listKey, oldJson).putLong(listKey + "_stamp", 1L).apply()

        val downgradedJson = """
            [{"host":"10.0.0.1","share":"video","username":"tv","password":"new-pass"},
             {"host":"10.0.0.9","share":"movie","username":"tv","password":"$secret"}]
        """.trimIndent()
        seedSoftwareCopy(app, "smb_merge_test", downgradedJson, 99L)

        val store = SmbConnectionStore(app, prefs)
        val all = store.getAll()
        assertEquals(2, all.size)
        assertEquals("new-pass", all.first { it.host == "10.0.0.1" }.password)
        assertTrue(all.any { it.host == "10.0.0.9" })
    }

    /**
     * 跨版本直升（2026-11-08 追问）：用户可能从好几个版本之前直接装最新版，盘上同时留着
     * 四份历史数据——很早的明文 prefs、降级期明文、旧版加密主文件、降级期软件兜底。
     * 实测它们能否一次全部接住、新密码优先、两份明文都被清空，且不因重启重复叠加。
     */
    @Test
    fun 跳过多个版本直升时四份历史数据都能接住() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = SecureConnectionPrefs(app, "smb_cross_version_test", listKey)
        val main = withFakeSystemEngine(prefs, app, "smb_cross_version_test")
        // 旧版写的主文件：故意不写写入序号，模拟 2.6.16 及更早版本的落盘格式
        val oldestJson =
            """[{"host":"10.0.0.1","share":"a","username":"tv","password":"p-old"}]"""
        main.edit().putString(listKey, oldestJson).apply()

        // 降级期软件兜底：同一条改过密码，另加一条新连接
        val downgradedJson = """
            [{"host":"10.0.0.1","share":"a","username":"tv","password":"p-new"},
             {"host":"10.0.0.2","share":"b","username":"tv","password":"$secret"}]
        """.trimIndent()
        seedSoftwareCopy(app, "smb_cross_version_test", downgradedJson, 50L)

        // 更早期的两份明文回迁文件（文件名与历代版本一致）
        app.getSharedPreferences("smb_connections", Context.MODE_PRIVATE)
            .edit().putString(listKey,
                """[{"host":"10.0.0.9","share":"z","username":"tv","password":"p9"}]""").apply()
        app.getSharedPreferences("smb_connections_encrypted_plain", Context.MODE_PRIVATE)
            .edit().putString(listKey,
                """[{"host":"10.0.0.8","share":"y","username":"tv","password":"p8"}]""").apply()

        val store = SmbConnectionStore(app, prefs)
        val all = store.getAll()
        assertEquals(4, all.size)
        // 新的排前面：同一条连接的新密码不能被主文件里的旧密码盖回去
        assertEquals("p-new", all.first { it.host == "10.0.0.1" }.password)
        assertEquals(secret, all.first { it.host == "10.0.0.2" }.password)
        assertTrue(all.any { it.host == "10.0.0.9" })
        assertTrue(all.any { it.host == "10.0.0.8" })
        // 明文文件写回成功后才清空
        assertTrue(app.getSharedPreferences("smb_connections", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(app.getSharedPreferences("smb_connections_encrypted_plain", Context.MODE_PRIVATE).all.isEmpty())

        // 模拟重启：记录仍完整，且不会被重复叠加
        assertEquals(4, SmbConnectionStore(app, prefs).getAll().size)
    }
}
