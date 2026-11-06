package com.tvflyshare.app.ftp

import android.util.Log
import com.tvflyshare.app.R
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/**
 * FTP 连接状态
 */
sealed class FtpConnectionState {
    /** 未连接 */
    object Disconnected : FtpConnectionState()

    /** 连接中 */
    object Connecting : FtpConnectionState()

    /** 已连接，host/port 为当前目标 */
    data class Connected(val host: String, val port: Int) : FtpConnectionState()

    /** 连接失败，message 为错误信息 */
    data class Error(val message: UiText) : FtpConnectionState() {
        fun getMessage(context: android.content.Context): String = message.asString(context)
    }
}

/**
 * FTP 客户端连接管理器
 *
 * 职责：
 * 1. 管理 FTP 连接的生命周期（连接 → 使用 → 断开）
 * 2. 提供 FTPClient 供 FtpBrowser / FtpFileOperator 使用
 * 3. 维护连接状态（StateFlow 驱动 UI）
 *
 * 关键 FTP 配置（避免常见坑）：
 * - 中文文件名/路径：基线 `controlEncoding = "GBK"`（国内服务器多为 GBK），并开启 `autodetectUTF8` 兼容 UTF-8 服务器
 * - 局域网数据传输：进入被动模式 `enterLocalPassiveMode()`
 * - 二进制文件（视频/APK）：`setFileType(FTP.BINARY_FILE_TYPE)`
 *
 * 线程安全：建连流程由协程 Mutex 串行化，避免并发建连产生孤儿连接。
 */
class FtpClientManager {

    private val client = FTPClient()

    @Volatile
    private var connected = false

    private val connectMutex = Mutex()

    private val _state = MutableStateFlow<FtpConnectionState>(FtpConnectionState.Disconnected)
    val state: StateFlow<FtpConnectionState> = _state.asStateFlow()

    var host: String? = null
        private set
    var port: Int = 21
        private set

    private var username = ""
    private var password = ""
    private var anonymous = false

    init {
        // 中文文件名/目录名支持：
        // - 国内多数 FTP 服务器（NAS/路由器/PC）以 GBK 编码文件名，故基线用 GBK，避免中文乱码
        // - 若服务器支持 UTF-8（发送 OPTS UTF8 ON 成功），自动升级为 UTF-8，兼顾现代服务器
        client.controlEncoding = "GBK"
        client.autodetectUTF8 = true
        // 注意：soTimeout 必须在 connect() 之后才能设置（commons-net 的 SocketClient.setSoTimeout
        // 会直接操作底层 _socket_，而 _socket_ 在 connect 前为 null，提前设置会抛 NullPointerException）。
        // 故 30s 读超时改在 connectInternal 内 connect 成功后设置（见下方）。
    }

    /**
     * 连接到 FTP 服务器
     *
     * @param host 目标主机 IP 或主机名
     * @param port 端口（默认 21）
     * @param username 用户名（匿名访问时被忽略）
     * @param password 密码（匿名访问时被忽略）
     * @param anonymous 是否匿名登录（勾选后使用 anonymous 账号）
     * @return true 连接成功，false 连接失败
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        anonymous: Boolean
    ): Boolean = connectMutex.withLock {
        connectInternal(host, port, username, password, anonymous)
    }

    private suspend fun connectInternal(
        host: String,
        port: Int,
        username: String,
        password: String,
        anonymous: Boolean
    ): Boolean {
        disconnect()

        _state.value = FtpConnectionState.Connecting
        this.host = host
        this.port = port
        this.username = username
        this.password = password
        this.anonymous = anonymous

        return try {
            withContext(Dispatchers.IO) {
                withTimeout(15000L) {
                    client.connectTimeout = 10000
                    client.connect(host, port)

                    // 控制连接读超时：FTPClient 默认 soTimeout=0（无限阻塞）——服务器接受 TCP 但不发
                    // banner / 不回命令时，login/操作会永久挂起。必须在 connect 成功、_socket_ 非 null
                    // 之后设置（2026-10-10 那次直接写在 init 里，_socket_ 为 null 导致构造即 NPE 崩）。
                    // 30s 覆盖登录与常规命令；数据连接另行设置，不影响大文件传输速率。
                    client.soTimeout = 30_000

                    // 2026-10-10 审计修复：阻塞 IO 不响应协程取消——withTimeout 超时后，
                    // 迟到的 connect/login 返回仍会继续执行，幽灵协程会把已写入的 Error 状态
                    // 覆盖回 Connected 并留下无人管理的孤儿 socket（对齐 SmbClientManager 修复）。
                    // 每个阻塞调用返回后检查取消态：已取消则就地回收，绝不改写状态。
                    if (!isActive) {
                        runCatching { client.disconnect() }
                        return@withTimeout false
                    }

                    if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                        _state.value = FtpConnectionState.Error(
                            AppException.NetworkException(
                                specificMessage = UiText.StringResource(R.string.error_ftp_connect_failed, host)
                            ).uiMessage
                        )
                        disconnect()
                        return@withTimeout false
                    }

                    val loginOk = if (anonymous) {
                        // 匿名登录：大多数匿名 FTP 接受 anonymous / 任意邮箱格式密码
                        client.login("anonymous", "anonymous@flyshare.local")
                    } else {
                        client.login(username, password)
                    }

                    if (!isActive) {
                        runCatching { client.disconnect() }
                        return@withTimeout false
                    }

                    if (!loginOk) {
                        _state.value = FtpConnectionState.Error(
                            AppException.NetworkException(
                                specificMessage = UiText.StringResource(R.string.error_ftp_login_failed)
                            ).uiMessage
                        )
                        disconnect()
                        return@withTimeout false
                    }

                    // 被动模式 + 二进制传输，确保局域网大文件可用且无损
                    client.enterLocalPassiveMode()
                    client.setFileType(FTP.BINARY_FILE_TYPE)
                    connected = true
                    _state.value = FtpConnectionState.Connected(host, port)
                    Log.i(TAG, "FTP connected: $host:$port (anonymous=$anonymous)")
                    true
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "FTP connection timeout", e)
            _state.value = FtpConnectionState.Error(
                AppException.NetworkException(
                    e, specificMessage = UiText.StringResource(R.string.error_network_timeout)
                ).uiMessage
            )
            disconnect()
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "FTP connection error", e)
            _state.value = FtpConnectionState.Error(
                AppException.NetworkException(
                    e, specificMessage = UiText.StringResource(R.string.error_ftp_connect_failed, host)
                ).uiMessage
            )
            disconnect()
            false
        }
    }

    /** 获取已连接的 FTPClient（未连接返回 null） */
    fun getFtpClient(): FTPClient? = if (connected) client else null

    /** 是否已连接 */
    fun isConnected(): Boolean = connected && client.isConnected

    /**
     * 检查并确保连接存活，断开则尝试自动重连。
     * @return true 连接存活或重连成功，false 彻底断开
     */
    suspend fun ensureConnected(): Boolean {
        val currentHost = host ?: return false
        if (isConnected()) return true
        return connectMutex.withLock {
            if (isConnected()) true
            else connectInternal(currentHost, port, username, password, anonymous)
        }
    }

    /** 断开连接，释放资源 */
    fun disconnect() {
        // 2026-10-10 审计修复：原先先 logout() 后 disconnect()，且 logout 无限时等待 QUIT 回复——
        // 服务器 TCP 可达但对命令无响应（半死进程/防火墙劫持）时 disconnect 永不返回，
        // 调用方协程永久挂起、IO 线程与 socket 泄漏。现在把 soTimeout 压到 3s 限时等待 QUIT，
        // 且无论 logout 结果如何都强制 close socket；本函数必须保证完成，不再透传取消异常。
        try {
            if (client.isConnected) {
                client.soTimeout = 3_000
                client.logout()
            }
        } catch (e: Exception) {
            Log.w(TAG, "logout failed", e)
        }
        try {
            client.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
        connected = false
        if (_state.value !is FtpConnectionState.Error) {
            _state.value = FtpConnectionState.Disconnected
        }
    }

    companion object {
        private const val TAG = "FtpClientManager"
    }
}
