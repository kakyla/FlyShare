package com.tvflyshare.app.network

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * FTP 连接记录持久化存储
 *
 * 与 [SmbConnectionStore] 同构：用 [SecureConnectionPrefs] 存 JSON 数组，
 * 正常时由系统 Keystore 加密，加密组件不可用时改用本机软件密钥加密，
 * 任何情况下盘上都不写明文。保护级别详见 [SecureConnectionPrefs] 注释。
 *
 * 与 SMB 不同：FTP 必须保存端口（默认 21）和是否匿名登录；合并与历史明文回迁
 * 以 host+port 去重、新的优先（见 [currentList]）。
 *
 * 防丢记录（2026-11-08 审计修复）：读取时把主、备引擎与历史明文文件的内容全部合并；
 * 任何一份解不开或内容损坏时禁止覆盖写，避免一次读取失败把整份记录抹掉。
 */
class FtpConnectionStore(
    context: Context,
    // 允许单测注入存储实现，用来覆盖"主、备引擎同时有数据"这类真机上难复现的场景；
    // 正常调用仍走默认参数，行为不变。
    private val securePrefs: SecureConnectionPrefs = SecureConnectionPrefs(
        context.applicationContext, ENCRYPTED_PREFS_NAME, KEY_LIST
    ),
) {

    companion object {
        private const val TAG = "FtpConnectionStore"
        private const val KEY_LIST = "ftp_connection_list"
        private const val ENCRYPTED_PREFS_NAME = "ftp_connections_encrypted"
    }

    private val lock = Any()

    /** 存储处于不可用/损坏状态：允许读、禁止写，防止把用户的连接记录覆盖成空。 */
    private var writeBlocked = false

    data class FtpConnection(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val anonymous: Boolean,
    )

    /**
     * 读取当前记录，顺带回迁降级期产生的明文文件（*_plain）。
     *
     * 主、备引擎与历史明文的内容按写入序号从新到旧合并，先到先得；
     * 写回成功后才清空历史明文文件，避免清了却没写回去导致记录丢失。
     */
    @Synchronized
    private fun currentList(): List<FtpConnection> {
        val snap = securePrefs.snapshot()
        writeBlocked = snap.hasUnreadable
        if (writeBlocked) {
            Log.e(TAG, "存储里有解不开的内容，暂停回迁与写入以保护原数据")
        }

        val plainName = ENCRYPTED_PREFS_NAME + "_plain"
        val pending = if (writeBlocked) {
            null
        } else {
            securePrefs.peekPlainFrom(plainName)?.let { plainName to it }
        }

        val merged = mutableListOf<FtpConnection>()
        (snap.values + listOfNotNull(pending?.second)).forEach { json ->
            if (isCorruptJson(json)) {
                writeBlocked = true
                Log.e(TAG, "保存的连接内容损坏，暂停写入以保护原数据")
            }
            parseList(json).forEach { item ->
                if (merged.none { it.host == item.host && it.port == item.port }) {
                    merged.add(item)
                }
            }
        }

        if (pending != null && !writeBlocked) {
            if (writeList(merged)) {
                securePrefs.clearPlainFrom(pending.first)
                Log.i(TAG, "回迁历史明文记录到加密存储")
            } else {
                Log.w(TAG, "回迁写回失败，历史明文文件保留，下次读取重试")
            }
        }
        return merged
    }

    /** 合法 JSON 空数组不算损坏；解析不了、或有条目却一条都取不出来，都按损坏处理。 */
    private fun isCorruptJson(json: String): Boolean {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return true
        return arr.length() > 0 && parseList(json).isEmpty()
    }

    /** 单条坏数据跳过而非整体清空，避免一次 save 把其余记录全覆盖掉。 */
    private fun parseList(json: String?): List<FtpConnection> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val obj = arr.getJSONObject(i)
                    FtpConnection(
                        host = obj.getString("host"),
                        port = runCatching { obj.getInt("port") }.getOrDefault(21),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                        anonymous = runCatching { obj.getBoolean("anonymous") }.getOrDefault(false),
                    )
                }.getOrNull()
            }
        }.getOrElse {
            Log.w(TAG, "Failed to parse saved FTP connections JSON", it)
            emptyList()
        }
    }

    /** 获取所有已保存的连接记录 */
    fun getAll(): List<FtpConnection> = try {
        currentList()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        writeBlocked = true
        Log.w(TAG, "读取保存的连接失败，暂停写入以保护原数据", e)
        emptyList()
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

    /** @return 是否真的落盘；存储状态异常时返回 false 且绝不覆盖原数据。 */
    private fun writeList(list: List<FtpConnection>): Boolean {
        if (writeBlocked) {
            Log.e(TAG, "存储状态异常，拒绝覆盖写入以保护原有记录")
            return false
        }
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
        return securePrefs.save(arr.toString())
    }
}
