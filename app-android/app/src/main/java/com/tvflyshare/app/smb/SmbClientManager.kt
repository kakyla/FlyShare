package com.tvflyshare.app.smb

import android.util.Log
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.tvflyshare.app.network.NetBiosResolver
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.UiText
import com.tvflyshare.app.R
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tvflyshare.app.network.NetworkUtils

/**
 * SMB 连接状态
 */
sealed class SmbConnectionState {
    /** 未连接 */
    object Disconnected : SmbConnectionState()

    /** 连接中 */
    object Connecting : SmbConnectionState()

    /** 已连接，shareName 为当前打开的共享名 */
    data class Connected(val host: String, val shareName: String) : SmbConnectionState()

    /** 连接失败，message 为错误信息 */
    data class Error(val message: UiText) : SmbConnectionState() {
        fun getMessage(context: android.content.Context): String {
            return message.asString(context)
        }
    }
}

/**
 * SMB 客户端连接管理器
 *
 * 职责：
 * 1. 管理 SMB 连接的生命周期（连接 → 使用 → 断开）
 * 2. 提供 DiskShare 供 SmbBrowser / SmbFileOperator 使用
 * 3. 维护连接状态（StateFlow 驱动 UI）
 *
 * 使用方式：
 *   val manager = SmbClientManager()
 *   manager.connect("192.168.1.100", "shared", "user", "pass")
 *   val share = manager.getDiskShare()
 *   // 使用 share 浏览/操作文件...
 *   manager.disconnect()
 *
 * 线程安全：
 *   建连流程由协程 Mutex 串行化（挂起而非阻塞线程），同一时刻只会有一次建连；
 *   连接资源字段为 @Volatile，getDiskShare/isConnected 为无锁读，不会被建连耗时拖住；
 *   disconnect 仍加对象锁，但只做 close 与置空，属瞬时操作。
 *   文件操作（open/read/close）本身需由调用方在同一线程或自行同步。
 */
class SmbClientManager {

    companion object {
        private const val TAG = "SmbClientManager"

    }

    private val client = SMBClient(
        com.hierynomus.smbj.SmbConfig.builder()
            .withTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // 改为请求超时30秒，废弃导致闲置断开的 SoTimeout
            .build()
    )

    // 三个连接资源字段标记 @Volatile：读取方（getDiskShare/isConnected）无需加锁即可看到最新值，
    // 从而不会被建连期间的长时网络 IO 阻塞。
    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var diskShare: DiskShare? = null

    /**
     * 建连串行锁。
     *
     * 选用协程 Mutex 而非 synchronized：建连过程含最长 15 秒的阻塞网络 IO，
     * 若用对象监视器锁会连带阻塞 getDiskShare()/disconnect() 这类瞬时调用；
     * Mutex 只挂起协程、不占用线程，同时可防止并发建连产生无人回收的孤儿连接。
     */
    private val connectMutex = Mutex()

    /** 连接状态（UI 订阅） */
    private val _state = MutableStateFlow<SmbConnectionState>(SmbConnectionState.Disconnected)
    val state: StateFlow<SmbConnectionState> = _state.asStateFlow()

    private var username = ""
    private var password = ""
    private var domain = ""

    /** 当前连接信息 */
    var host: String? = null
        private set
    var shareName: String? = null
        private set

    /**
     * 连接到 SMB 共享
     *
     * @param host     目标主机 IP 或主机名（主机名会先通过 NetBIOS 解析为 IP）
     * @param shareName 共享文件夹名称（如 "Public"、"Users" 等）
     * @param username 用户名（匿名访问传空串）
     * @param password 密码（匿名访问传空串）
     * @return true 连接成功，false 连接失败
     */
    suspend fun connect(host: String, shareName: String, username: String, password: String, domain: String = ""): Boolean =
        connectMutex.withLock {
            connectInternal(host, shareName, username, password, domain)
        }

    /**
     * 建连实现体，调用方必须已持有 [connectMutex]。
     *
     * 单独抽出的原因：[ensureConnected] 的重连路径需要复用同一把锁做双重检查，
     * 而 Mutex 不可重入，直接调用公开的 [connect] 会自死锁。
     */
    private suspend fun connectInternal(
        host: String,
        shareName: String,
        username: String,
        password: String,
        domain: String
    ): Boolean {
        // 先断开旧连接
        disconnect()

        _state.value = SmbConnectionState.Connecting
        this.host = host
        this.shareName = shareName
        this.username = username
        this.password = password
        this.domain = domain

        return try {
            withContext(Dispatchers.IO) {
                withTimeout(15000L) { // 15秒整体连接超时
                    Log.i(TAG, "Connecting to SMB: $host/$shareName as '$username' (domain: '$domain')")

                    // 主机名预解析：Android 默认不解析 NetBIOS 短名，需通过 UDP 137 广播解析成 IP
                    // IP 地址直接用，无需解析
                    val connectHost = if (NetworkUtils.isIpv4(host)) {
                        host
                    } else {
                        Log.i(TAG, "Host '$host' is not IP, resolving via NetBIOS...")
                        val resolved = NetBiosResolver.resolve(host)
                        if (resolved == null) {
                            _state.value = SmbConnectionState.Error(
                                AppException.NetworkException(specificMessage = UiText.StringResource(R.string.error_smb_resolve_hostname, host)).uiMessage
                            )
                            return@withTimeout false
                        }
                        Log.i(TAG, "Resolved '$host' -> $resolved")
                        resolved
                    }

                    // 以下三步是耗时网络 IO，刻意不加对象监视器锁：
                    // 并发建连已由 connectMutex 串行化，字段又是 @Volatile，
                    // 读取方（getDiskShare 等）不必与这段 IO 争抢同一把锁。

                    // 2026-10-04 审计 P1 修复：15s 应用层超时先于 smbj 内部 30s 到期，而阻塞 IO 不响应取消——
                    // 超时 catch 里的 disconnect() 跑完时（字段还是 null，close 落空），阻塞调用才返回并继续
                    // 赋值，产生永远无人 close 的孤儿 Connection/Session/DiskShare。
                    // 每个阻塞调用返回后检查取消态：已取消则就地回收本次已创建的资源。
                    connection = client.connect(connectHost)
                    if (!isActive) {
                        runCatching { connection?.close() }
                        connection = null
                        return@withTimeout false
                    }

                    // 创建认证上下文
                    val authContext = if (username.isEmpty()) {
                        // 匿名访问
                        AuthenticationContext.anonymous()
                    } else {
                        val useDomain = if (domain.isBlank()) null else domain
                        AuthenticationContext(username, password.toCharArray(), useDomain)
                    }

                    // 登录建立会话
                    session = connection?.authenticate(authContext)
                    if (!isActive) {
                        runCatching { session?.close() }
                        runCatching { connection?.close() }
                        session = null
                        connection = null
                        return@withTimeout false
                    }

                    // 打开共享
                    diskShare = session?.connectShare(shareName) as? DiskShare
                    if (!isActive) {
                        runCatching { diskShare?.close() }
                        runCatching { session?.close() }
                        runCatching { connection?.close() }
                        diskShare = null
                        session = null
                        connection = null
                        return@withTimeout false
                    }

                    if (diskShare == null) {
                        _state.value = SmbConnectionState.Error(AppException.SmbException(specificMessage = UiText.StringResource(R.string.error_smb_cannot_open_share, shareName)).uiMessage)
                        disconnect()
                        return@withTimeout false
                    }

                    _state.value = SmbConnectionState.Connected(host, shareName)
                    Log.i(TAG, "SMB connected: \\$host\\$shareName")
                    true
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "SMB connection timeout", AppException.NetworkException(e))
            _state.value = SmbConnectionState.Error(AppException.NetworkException(e, specificMessage = UiText.StringResource(R.string.error_network_timeout)).uiMessage)
            disconnect()
            false
        } catch (e: SMBApiException) {
            Log.e(TAG, "SMB API Error", e)
            val msg = when (e.status) {
                NtStatus.STATUS_LOGON_FAILURE -> UiText.StringResource(R.string.error_smb_logon_failure)
                NtStatus.STATUS_ACCESS_DENIED -> UiText.StringResource(R.string.error_smb_access_denied)
                NtStatus.STATUS_BAD_NETWORK_NAME -> UiText.StringResource(R.string.error_smb_bad_network_name)
                NtStatus.STATUS_ACCOUNT_DISABLED -> UiText.StringResource(R.string.error_smb_account_disabled)
                else -> UiText.StringResource(R.string.error_smb_generic_status, e.status.name)
            }
            _state.value = SmbConnectionState.Error(AppException.SmbException(e, specificMessage = msg).uiMessage)
            disconnect()
            false
        } catch (e: TransportException) {
            Log.e(TAG, "SMB connection transport error", AppException.NetworkException(e))
            _state.value = SmbConnectionState.Error(AppException.NetworkException(e, specificMessage = UiText.StringResource(R.string.error_smb_cannot_connect)).uiMessage)
            disconnect()
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "SMB connection error", AppException.SmbException(e))
            val msg = if (e.message?.contains("Connection refused") == true) {
                UiText.StringResource(R.string.error_smb_connection_refused)
            } else if (e.message?.contains("No route to host") == true) {
                UiText.StringResource(R.string.error_smb_no_route)
            } else {
                AppException.SmbException(e).uiMessage
            }
            _state.value = SmbConnectionState.Error(msg)
            disconnect()
            false
        }
    }

    /**
     * 获取当前 DiskShare
     *
     * 调用前需确认 state 为 Connected。
     * 返回的 DiskShare 可用于浏览目录、读写文件。
     *
     * 字段为 @Volatile，此处刻意不加锁：否则建连期间（最长 15 秒）所有调用方都会被挂住。
     *
     * @return DiskShare 或 null（未连接时）
     */
    fun getDiskShare(): DiskShare? = diskShare

    /**
     * 检查并确保连接存活。
     * 
     * 执行轻量级探测（如检查根目录是否存在）。如果发现连接断开，尝试自动重连。
     * @return true 表示连接存活或重连成功，false 表示彻底断开
     */
    suspend fun ensureConnected(): Boolean {
        val currentHost = host ?: return false
        val currentShare = shareName ?: return false

        if (probeAlive()) return true

        Log.w(TAG, "Connection seems dead, attempting to reconnect...")
        return connectMutex.withLock {
            // 双重检查：等待锁期间可能已有其他协程重连成功，避免重复建连
            if (probeAlive()) {
                true
            } else {
                connectInternal(currentHost, currentShare, username, password, domain)
            }
        }
    }

    /**
     * 轻量探活：检查共享根目录是否仍可访问。
     *
     * 只吞掉表示"连接已死"的业务异常；CancellationException 必须继续上抛，
     * 否则调用方取消操作时探活会把取消信号吃掉，导致协程取消链路断裂。
     */
    private suspend fun probeAlive(): Boolean = try {
        withContext(Dispatchers.IO) {
            diskShare?.folderExists("") == true
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }



    /**
     * 是否已连接
     *
     * 此方法为快速检查，不保证后续操作一定成功（服务端可能已关闭连接但对象仍在）。
     */
    fun isConnected(): Boolean = diskShare != null && connection?.isConnected == true

    /**
     * 断开连接，释放资源
     */
    @Synchronized
    fun disconnect() {
        try {
            diskShare?.close()
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Error closing DiskShare", e)
        }
        try {
            session?.close()
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Error closing Session", e)
        }
        try {
            connection?.close()
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Error closing Connection", e)
        }

        diskShare = null
        session = null
        connection = null

        if (_state.value !is SmbConnectionState.Error) {
            _state.value = SmbConnectionState.Disconnected
        }
        Log.d(TAG, "SMB disconnected")
    }
}
