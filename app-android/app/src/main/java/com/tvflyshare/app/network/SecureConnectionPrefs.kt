package com.tvflyshare.app.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 连接记录的加密存储（SMB / FTP 共用）。
 *
 * 两条路径写下去的都是密文，代码里不再有「明文分支」：
 * - 正常：用 EncryptedSharedPreferences，密钥由系统 Keystore 保管、取不出来；
 * - 系统加密组件初始化或读写抛异常：改用本机随机 AES 密钥把内容加密后写进
 *   普通私有 prefs（[SoftwareCipher]）。密钥与密文同在 App 私有目录，root 设备
 *   仍可还原；这条兜底挡的是「明文躺着」和「加密组件一坏就丢记录／闪退」，
 *   不是防住已取得最高权限的攻击者。allowBackup=false 已关闭备份导出。
 *
 * 关于「主、备两份」的历史包袱：早期版本降级时会另写一份，系统加密恢复后只读主文件，
 * 降级期保存的记录会凭空消失。现在 [snapshot] 把**所有读得出来的内容**都交给上层合并，
 * 并用写入序号决定合并优先级（新的先到先得），因此任何一次引擎切换、任何一次恢复，
 * 都不会再让记录消失，也不会让旧密码把新密码盖回去。
 *
 * [Snapshot.hasUnreadable] 是另一条保护：盘上存在解不开的内容时，上层必须**拒绝覆盖写**，
 * 否则一次读取失败就会返回空列表，下次保存把整份记录抹掉（2026-11-08 审计实测路径）。
 */
class SecureConnectionPrefs(
    context: Context,
    private val name: String,
    private val key: String,
) {
    companion object {
        private const val TAG = "SecureConnectionPrefs"
        private const val SOFTWARE_SUFFIX = "_sw"
        private const val KEY_SOFTWARE_KEY = "local_key"
        private const val STAMP_SUFFIX = "_stamp"
    }

    /**
     * 一次读取的全部结果。
     *
     * @param values 各引擎里能读出的 JSON，按写入序号从新到旧排列；上层按业务键去重合并。
     * @param hasUnreadable 是否存在「非空但解不开」的内容。为 true 时上层禁止任何覆盖写。
     */
    data class Snapshot(val values: List<String>, val hasUnreadable: Boolean)

    private data class Candidate(val value: String, val stamp: Long)

    private val appContext = context.applicationContext
    private val lock = Any()
    private val stampKey = key + STAMP_SUFFIX

    /** 主引擎；创建失败或读写失败后置 null，本进程后续一律走软件引擎。 */
    private var systemPrefs: SharedPreferences? = null
    private var engineReady = false

    private val softwarePrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(name + SOFTWARE_SUFFIX, Context.MODE_PRIVATE)
    }

    private val softwareKey: ByteArray by lazy {
        val stored = runCatching { softwarePrefs.getString(KEY_SOFTWARE_KEY, null) }.getOrNull()
        val decoded = stored?.let { runCatching { Base64.decode(it, SoftwareCipher.BASE64_FLAGS) }.getOrNull() }
        if (decoded != null && decoded.size == SoftwareCipher.KEY_BYTES) {
            decoded
        } else {
            ByteArray(SoftwareCipher.KEY_BYTES).also { bytes ->
                SecureRandom().nextBytes(bytes)
                softwarePrefs.edit()
                    .putString(KEY_SOFTWARE_KEY, Base64.encodeToString(bytes, SoftwareCipher.BASE64_FLAGS))
                    .apply()
            }
        }
    }

    /** 惰性建主引擎；失败时静默转软件引擎，不把异常抛给调用方。 */
    private fun systemEngine(): SharedPreferences? = synchronized(lock) {
        if (!engineReady) {
            systemPrefs = runCatching {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onFailure {
                Log.w(TAG, "系统加密存储不可用，改用本机软件密钥加密", it)
            }.getOrNull()
            engineReady = true
        }
        systemPrefs
    }

    /**
     * 主引擎失效时切到软件引擎，并尽力把主引擎里还读得出来的旧内容搬过去。
     * 不清主引擎：设备故障时本来就写不进去，硬清只会制造新异常；残留由 [snapshot] 合并兜住。
     */
    private fun switchToSoftware(reason: Throwable?) {
        val engine = systemPrefs ?: return
        systemPrefs = null
        Log.w(TAG, "系统加密存储读写失败，改用本机软件密钥加密", reason)
        runCatching { engine.getString(key, null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { plain ->
                runCatching {
                    softwarePrefs.edit()
                        .putString(key, SoftwareCipher.encrypt(plain, softwareKey))
                        .putLong(stampKey, readStamp(engine) + 1)
                        .apply()
                }
            }
    }

    private fun readStamp(prefs: SharedPreferences): Long =
        runCatching { prefs.getLong(stampKey, 0L) }.getOrDefault(0L)

    private fun nextStamp(): Long = maxOf(
        systemPrefs?.let { readStamp(it) } ?: 0L,
        readStamp(softwarePrefs),
    ) + 1

    /**
     * 读取快照：主、备引擎都看，能读出的内容全部返回（按写入序号从新到旧）。
     *
     * 顺序上先读主引擎，因为它失败时会触发 [switchToSoftware] 往软件引擎写入搬迁内容；
     * 软件引擎必须在这之后读，否则刚搬过来的那份会被漏掉。
     */
    fun snapshot(): Snapshot = synchronized(lock) {
        val candidates = mutableListOf<Candidate>()
        var unreadable = false

        systemEngine()?.let { engine ->
            runCatching { engine.getString(key, null) }
                .onFailure { switchToSoftware(it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates.add(Candidate(it, readStamp(engine))) }
        }

        val swRaw = runCatching { softwarePrefs.getString(key, null) }.getOrNull()
        if (!swRaw.isNullOrBlank()) {
            val decoded = SoftwareCipher.decrypt(swRaw, softwareKey)
            if (decoded == null) {
                unreadable = true
                Log.e(TAG, "软件兜底内容解不开，本次读取禁止覆盖写以保护原数据")
            } else if (decoded.isNotBlank()) {
                candidates.add(Candidate(decoded, readStamp(softwarePrefs)))
            }
        }

        val values = candidates
            .sortedByDescending { it.stamp }
            .map { it.value }
            .distinct()
        Snapshot(values, unreadable)
    }

    /** 便捷读取：返回最新一份可读内容；正式用例请用 [snapshot] 以拿到完整合并信息。 */
    fun load(): String? = snapshot().values.firstOrNull()

    /**
     * 写入连接记录 JSON；无论走哪条引擎，落盘的都是密文。
     *
     * 写入成功的那份记上递增序号；另一份会被清掉，保证正常设备上不会长期留一份
     * 用软件密钥就能还原的密码副本。清理失败不影响本次保存，[snapshot] 会按序号合并。
     *
     * @return 是否真的落盘。false 表示主、备都写不进去，调用方不得据此认为保存成功。
     */
    fun save(value: String): Boolean = synchronized(lock) {
        val stamp = nextStamp()
        val engine = systemEngine()
        if (engine != null) {
            val ok = runCatching {
                engine.edit().putString(key, value).putLong(stampKey, stamp).apply()
                true
            }.onFailure { switchToSoftware(it) }.getOrDefault(false)
            if (ok) {
                clearSoftware()
                return@synchronized true
            }
        }
        val ok = runCatching {
            softwarePrefs.edit()
                .putString(key, SoftwareCipher.encrypt(value, softwareKey))
                .putLong(stampKey, stamp)
                .apply()
            true
        }.onFailure { Log.e(TAG, "软件兜底存储写入失败", it) }.getOrDefault(false)
        if (ok) clearSystem()
        if (!ok) Log.e(TAG, "主、备存储都写不进去，本次保存未落盘")
        ok
    }

    private fun clearSoftware() {
        runCatching { softwarePrefs.edit().remove(key).remove(stampKey).apply() }
            .onFailure { Log.w(TAG, "清理软件镜像失败（下次读取会按序号合并，不会丢记录）", it) }
    }

    private fun clearSystem() {
        systemPrefs?.let { engine ->
            runCatching { engine.edit().remove(key).remove(stampKey).apply() }
                .onFailure { Log.w(TAG, "清理主存储失败（下次读取会按序号合并，不会丢记录）", it) }
        }
    }

    /** 只读历史明文 prefs 的内容，不清文件；回迁写回成功后再调 [clearPlainFrom]。 */
    fun peekPlainFrom(prefsName: String): String? {
        val legacy = runCatching {
            appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        if (runCatching { legacy.all.isEmpty() }.getOrDefault(true)) return null
        return runCatching { legacy.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 清空历史明文 prefs 文件。只在内容已安全写回当前加密存储之后调用。 */
    fun clearPlainFrom(prefsName: String) {
        runCatching {
            appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    /** 取走并清空（组合方法，供兼容与单测使用）。业务回迁请用 peek + clear 两步。 */
    fun takePlainFrom(prefsName: String): String? =
        peekPlainFrom(prefsName)?.also { clearPlainFrom(prefsName) }
}

/**
 * 本机软件密钥的 AES-GCM 加解密，仅供 [SecureConnectionPrefs] 在系统 Keystore
 * 不可用时兜底。IV 随机生成并拼在密文前，用 GCM 自带的认证 tag 防篡改。
 *
 * 这里刻意做成独立对象：不依赖 Android Keystore，纯 JVM 单测即可验证往返与
 * 「密文里不含明文」这两条最关键的契约。
 */
object SoftwareCipher {
    const val KEY_BYTES = 32
    const val BASE64_FLAGS = Base64.DEFAULT

    private const val TAG = "SoftwareCipher"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plain: String, key: ByteArray): String {
        require(key.size == KEY_BYTES) { "software key must be $KEY_BYTES bytes" }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
        return Base64.encodeToString(packed, BASE64_FLAGS)
    }

    /**
     * 解不开时返回 null，不把异常抛到 UI 层。
     * 注意「返回 null」不等于「没有数据」：调用方 [SecureConnectionPrefs.snapshot] 会据此
     * 标记 hasUnreadable 并拒绝覆盖写，避免把用户保存的连接整份抹掉。
     */
    fun decrypt(packed: String?, key: ByteArray): String? {
        if (packed.isNullOrBlank() || key.size != KEY_BYTES) return null
        return runCatching {
            val bytes = Base64.decode(packed, BASE64_FLAGS)
            require(bytes.size > IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "软件密钥解不开旧内容", it)
            null
        }
    }
}
