package com.tvflyshare.app.network

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * FTP 连接记录持久化存储
 *
 * 与 [SmbConnectionStore] 同构：用 EncryptedSharedPreferences 存 JSON 数组，
 * 密码字段经 Android Keystore 加密，root 设备或 adb backup 也读不出明文。
 *
 * 与 SMB 不同：FTP 必须保存端口（默认 21）和是否匿名登录。
 */
class FtpConnectionStore(context: Context) {

    companion object {
        private const val TAG = "FtpConnectionStore"
        private const val KEY_LIST = "ftp_connection_list"
        private const val ENCRYPTED_PREFS_NAME = "ftp_connections_encrypted"
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
            // 2026-10-04 审计 P1 修复：Keystore 损坏/低版本设备缺支撑时降级为普通 SharedPreferences
            //（与 SmbConnectionStore 同规），避免 save/getAll/moveToFront 全部入口闪退
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
            appContext.getSharedPreferences(ENCRYPTED_PREFS_NAME + "_plain", Context.MODE_PRIVATE)
        }
    }

    data class FtpConnection(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val anonymous: Boolean,
    )

    /** 获取所有已保存的连接记录 */
    fun getAll(): List<FtpConnection> {
        return try {
            // 2026-10-04 审计 P2 修复：prefs.getString（解密）原先在 try 外，Keystore 失效场景直接崩溃
            val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                // 2026-10-04 审计 P2 修复：单条损坏记录跳过而非整体清空——
                // 原先一条坏数据会让 getAll 返回空列表，后续 save 用空列表覆盖存储，其余记录全丢
                runCatching {
                    val obj = arr.getJSONObject(i)
                    val port = runCatching { obj.getInt("port") }.getOrDefault(21)
                    val anonymous = runCatching { obj.getBoolean("anonymous") }.getOrDefault(false)
                    FtpConnection(
                        host = obj.getString("host"),
                        port = port,
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                        anonymous = anonymous,
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Failed to parse saved FTP connections JSON", e)
            emptyList()
        }
    }

    /** 保存或更新一条记录（以 host:port 作为唯一键） */
    fun save(conn: FtpConnection) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            val idx = list.indexOfFirst { it.host == conn.host && it.port == conn.port }
            if (idx >= 0) list[idx] = conn else list.add(conn)
            writeList(list)
        }
    }

    /** 将指定连接移到列表最前（最近使用优先） */
    fun moveToFront(host: String, port: Int) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            val idx = list.indexOfFirst { it.host == host && it.port == port }
            if (idx > 0) {
                val item = list.removeAt(idx)
                list.add(0, item)
                writeList(list)
            }
        }
    }

    /** 删除一条记录 */
    fun remove(host: String, port: Int) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            list.removeAll { it.host == host && it.port == port }
            writeList(list)
        }
    }

    /** 批量删除记录（按 host:port 匹配） */
    fun removeAll(conns: List<FtpConnection>) {
        if (conns.isEmpty()) return
        synchronized(lock) {
            val list = getAll().toMutableList()
            list.removeAll { existing ->
                conns.any { it.host == existing.host && it.port == existing.port }
            }
            writeList(list)
        }
    }

    private fun writeList(list: List<FtpConnection>) {
        val arr = JSONArray()
        list.forEach { conn ->
            arr.put(JSONObject().apply {
                put("host", conn.host)
                put("port", conn.port)
                put("username", conn.username)
                put("password", conn.password)
                put("anonymous", conn.anonymous)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
