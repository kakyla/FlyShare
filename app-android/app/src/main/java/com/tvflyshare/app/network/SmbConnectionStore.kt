package com.tvflyshare.app.network

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMB 连接记录持久化存储
 *
 * 用 EncryptedSharedPreferences 存 JSON 数组，每条记录包含 host/share/user/pass。
 * 密码字段经 Android Keystore 加密，root 设备或 adb backup 也读不出明文。
 *
 * 线程安全：所有写操作（save/moveToFront/remove/removeAll）都用 synchronized 串行化，
 * 避免并发 read-modify-write 丢更新。
 *
 * 迁移：首次升级时若检测到旧明文 prefs（smb_connections）有数据，会读取后写入加密 prefs 再清除旧数据。
 */
class SmbConnectionStore(context: Context) {

    companion object {
        private const val TAG = "SmbConnectionStore"
        private const val KEY_LIST = "smb_connection_list"
        private const val LEGACY_PREFS_NAME = "smb_connections"
        private const val ENCRYPTED_PREFS_NAME = "smb_connections_encrypted"
        private const val MIGRATION_DONE_KEY = "migration_done"
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // 2026-10-04 审计 P1 修复：Keystore 损坏/低版本设备缺支撑时 lazy 初始化本身会抛，
            // 之前 save/moveToFront/removeAll/getAll 全部入口都会闪退——降级为普通 SharedPreferences
            //（连接记录不加密但功能可用），日志留痕便于排查
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
            appContext.getSharedPreferences(ENCRYPTED_PREFS_NAME + "_plain", Context.MODE_PRIVATE)
        }
    }

    /** 一次性迁移：把旧明文 SharedPreferences 数据搬到加密 prefs */
    @Synchronized
    private fun migrateIfNeeded() {
        if (prefs.getBoolean(MIGRATION_DONE_KEY, false)) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyJson = legacy.getString(KEY_LIST, null)
        if (!legacyJson.isNullOrBlank()) {
            // 旧数据搬到加密 prefs
            prefs.edit().putString(KEY_LIST, legacyJson).apply()
            Log.i(TAG, "Migrated ${legacyJson.length} chars from legacy prefs to encrypted prefs")
        }
        // 清除旧明文数据（含 last_* 字段）
        legacy.edit().clear().apply()
        prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).apply()
    }

    data class SmbConnection(
        val host: String,
        val share: String,
        val username: String,
        val password: String,
    )

    /** 获取所有已保存的连接记录 */
    fun getAll(): List<SmbConnection> {
        return try {
            migrateIfNeeded()
            // 2026-10-04 审计 P2 修复：prefs.getString（解密）原先在 try 外，Keystore 失效场景直接崩溃
            val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                // 2026-10-04 审计 P2 修复：单条损坏记录跳过而非整体清空（与 FtpConnectionStore 同规）
                runCatching {
                    val obj = arr.getJSONObject(i)
                    SmbConnection(
                        host = obj.getString("host"),
                        share = obj.getString("share"),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            // JSON 损坏时记录日志，便于排查（之前静默吞异常会导致连接莫名消失而无迹可寻）
            Log.w(TAG, "Failed to parse saved connections JSON", e)
            emptyList()
        }
    }

    /** 保存或更新一条记录（以 host+share 作为唯一键） */
    fun save(conn: SmbConnection) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            val idx = list.indexOfFirst { it.host == conn.host && it.share == conn.share }
            if (idx >= 0) list[idx] = conn else list.add(conn)
            writeList(list)
        }
    }

    /** 将指定连接移到列表最前（最近使用优先） */
    fun moveToFront(host: String, share: String) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            val idx = list.indexOfFirst { it.host == host && it.share == share }
            if (idx > 0) {
                val item = list.removeAt(idx)
                list.add(0, item)
                writeList(list)
            }
        }
    }

    /** 删除一条记录 */
    fun remove(host: String, share: String) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            list.removeAll { it.host == host && it.share == share }
            writeList(list)
        }
    }

    /**
     * 批量删除记录
     *
     * 一次 read-modify-write 完成多条删除，避免循环调 remove 导致 O(N²) 读写
     *
     * @param conns 要删除的连接列表（按 host+share 匹配）
     */
    fun removeAll(conns: List<SmbConnection>) {
        if (conns.isEmpty()) return
        synchronized(lock) {
            val list = getAll().toMutableList()
            list.removeAll { existing ->
                conns.any { it.host == existing.host && it.share == existing.share }
            }
            writeList(list)
        }
    }

    private fun writeList(list: List<SmbConnection>) {
        val arr = JSONArray()
        list.forEach { conn ->
            arr.put(JSONObject().apply {
                put("host", conn.host)
                put("share", conn.share)
                put("username", conn.username)
                put("password", conn.password)
            })
        }
        // 合并为一次 edit().apply()，避免两次写入
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
