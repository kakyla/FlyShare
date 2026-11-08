package com.tvflyshare.app.network

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMB 连接记录持久化存储
 *
 * 用 [SecureConnectionPrefs] 存 JSON 数组，每条记录包含 host/share/user/pass。
 * 正常时由系统 Keystore 加密；系统加密组件不可用时改用本机软件密钥加密，
 * 任何情况下盘上都不写明文，也不会因加密组件故障闪退或丢记录。
 * 软件兜底的密钥与密文同在 App 私有目录，root 设备仍可还原；
 * allowBackup=false 已挡住 adb 备份导出。保护级别详见 [SecureConnectionPrefs] 注释。
 *
 * 线程安全：所有写操作（save/moveToFront/remove/removeAll）都用 synchronized 串行化，
 * 避免并发 read-modify-write 丢更新。
 *
 * 防丢记录（2026-11-08 审计修复）：读取时把主、备引擎与历史明文文件的内容**全部合并**，
 * 按 host+share 去重、新的优先；任何一份解不开或内容损坏时置 [writeBlocked]，
 * 拒绝覆盖写，避免「读成空列表 → 下次保存把整份记录盖掉」。
 *
 * 回迁：历史明文文件（更早期的 smb_connections 与降级期产生的 *_plain）里的记录
 * 并回当前加密存储、写回成功后才清空原文件。
 */
class SmbConnectionStore(
    context: Context,
    // 允许单测注入存储实现，用来覆盖"主、备引擎同时有数据"这类真机上难复现的场景；
    // 正常调用仍走默认参数，行为不变。
    private val securePrefs: SecureConnectionPrefs = SecureConnectionPrefs(
        context.applicationContext, ENCRYPTED_PREFS_NAME, KEY_LIST
    ),
) {

    companion object {
        private const val TAG = "SmbConnectionStore"
        private const val KEY_LIST = "smb_connection_list"
        private const val LEGACY_PREFS_NAME = "smb_connections"
        private const val ENCRYPTED_PREFS_NAME = "smb_connections_encrypted"
    }

    private val lock = Any()

    /** 存储处于不可用/损坏状态：允许读、禁止写，防止把用户的连接记录覆盖成空。 */
    private var writeBlocked = false

    data class SmbConnection(
        val host: String,
        val share: String,
        val username: String,
        val password: String,
    )

    /**
     * 读取当前记录，顺带回迁历史明文文件。
     *
     * 主、备引擎与历史明文的内容按写入序号从新到旧合并，先到先得：降级期的新密码
     * 不会被主引擎里的旧密码盖回去，降级前的旧记录也不会在新引擎恢复后凭空消失。
     */
    @Synchronized
    private fun currentList(): List<SmbConnection> {
        val snap = securePrefs.snapshot()
        writeBlocked = snap.hasUnreadable
        if (writeBlocked) {
            Log.e(TAG, "存储里有解不开的内容，暂停回迁与写入以保护原数据")
        }

        val pending = if (writeBlocked) {
            emptyList()
        } else {
            listOf(ENCRYPTED_PREFS_NAME + "_plain", LEGACY_PREFS_NAME)
                .mapNotNull { name -> securePrefs.peekPlainFrom(name)?.let { name to it } }
        }

        val merged = mutableListOf<SmbConnection>()
        (snap.values + pending.map { it.second }).forEach { json ->
            if (isCorruptJson(json)) {
                writeBlocked = true
                Log.e(TAG, "保存的连接内容损坏，暂停写入以保护原数据")
            }
            parseList(json).forEach { item ->
                if (merged.none { it.host == item.host && it.share == item.share }) {
                    merged.add(item)
                }
            }
        }

        // 写回成功才清历史明文：先清后写一旦写失败，那份记录就真的没了
        if (pending.isNotEmpty() && !writeBlocked) {
            if (writeList(merged)) {
                pending.forEach { securePrefs.clearPlainFrom(it.first) }
                Log.i(TAG, "回迁 ${pending.size} 份历史明文记录到加密存储")
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
    private fun parseList(json: String?): List<SmbConnection> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
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
        }.getOrElse {
            Log.w(TAG, "Failed to parse saved connections JSON", it)
            emptyList()
        }
    }

    /** 获取所有已保存的连接记录 */
    fun getAll(): List<SmbConnection> = try {
        currentList()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        writeBlocked = true
        Log.w(TAG, "读取保存的连接失败，暂停写入以保护原数据", e)
        emptyList()
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

    /** @return 是否真的落盘；存储状态异常时返回 false 且绝不覆盖原数据。 */
    private fun writeList(list: List<SmbConnection>): Boolean {
        if (writeBlocked) {
            Log.e(TAG, "存储状态异常，拒绝覆盖写入以保护原有记录")
            return false
        }
        val arr = JSONArray()
        list.forEach { conn ->
            arr.put(JSONObject().apply {
                put("host", conn.host)
                put("share", conn.share)
                put("username", conn.username)
                put("password", conn.password)
            })
        }
        return securePrefs.save(arr.toString())
    }
}
