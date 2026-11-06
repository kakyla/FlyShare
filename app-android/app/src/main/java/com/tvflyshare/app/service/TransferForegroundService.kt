package com.tvflyshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.tvflyshare.app.R
import com.tvflyshare.app.TVTransferApp
import com.tvflyshare.app.httpserver.HttpServerManager
import com.tvflyshare.app.interconnect.DeviceDiscoveryManager

/**
 * 文件传输前台服务
 *
 * 托管 HTTP 服务，保证后台持续运行不被系统杀死。
 *
 * 生命周期采用"引用计数 + 延迟关闭"策略：
 * - 进入需要本服务的页面（快速传输、TV 互联）时 [acquire] 登记持有者；
 * - 离开页面（ViewModel.onCleared）时 [release] 注销持有者；
 * - 当所有持有者都已离开，延迟 [AUTO_STOP_DELAY_MS] 后自动 stopSelf，
 *   关闭前台通知与 HTTP/UDP 服务；若延迟期内再次进入则取消关闭。
 *
 * 这样既能在需要时保持互联/可接收，又不会在退出后一直挂着通知。
 *
 * 通知开关（设置项「传输服务通知」，默认关闭）：
 * - 开启：以「前台服务」运行，调用 startForeground 显示常驻通知；
 * - 关闭：以普通后台服务运行，不显示通知（系统可能在后台回收，属预期取舍）。
 */
class TransferForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "transfer_service_channel"
        private const val NOTIFICATION_ID = 1

        /** 所有持有者离开后，多久自动关闭传输服务（毫秒） */
        const val AUTO_STOP_DELAY_MS = 30_000L

        const val ACTION_ACQUIRE = "com.tvflyshare.app.action.ACQUIRE"
        const val ACTION_RELEASE = "com.tvflyshare.app.action.RELEASE"
        const val ACTION_SET_NOTIFICATION = "com.tvflyshare.app.action.SET_NOTIFICATION"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_ENABLED = "enabled"

        /** 持有者标记：快速传输页 */
        const val OWNER_TRANSFER = "transfer"
        /** 持有者标记：TV 互联发现页（设备列表） */
        const val OWNER_DISCOVERY = "discovery"
        /** 持有者标记：TV 互联浏览对端设备页（RemoteBrowse） */
        const val OWNER_REMOTE_BROWSE = "remote_browse"

        fun acquire(context: Context, owner: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_OWNER, owner)
            }
            startSafely(context, intent)
        }

        fun release(context: Context, owner: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_OWNER, owner)
            }
            startSafely(context, intent)
        }

        /** 动态切换通知开关：通知服务是否以「前台服务」运行 */
        fun setNotificationEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_SET_NOTIFICATION
                putExtra(EXTRA_ENABLED, enabled)
            }
            try {
                // 必须以「目标状态」(enabled) 而非「已存储设置」决定是否以前台服务启动：
                // 关闭通知时若误用 startForegroundService，系统会因"启动后未在规定时间内调用 startForeground"
                // 而强制弹出系统级"后台运行"通知，表现为"关闭通知后仍有通知"。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && enabled) {
                    context.startForegroundService(intent)
                } else {
                    // 关闭通知：仅以普通后台服务投递意图（内部会 stopForeground 移除常驻通知），
                    // 切勿调用 startForegroundService。
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("TransferForegroundService", "start service failed", e)
            }
        }

        private fun isNotificationEnabled(context: Context): Boolean {
            val app = context.applicationContext as? TVTransferApp
            return app?.settingsRepository?.getNotificationEnabled() ?: false
        }

        private fun startSafely(context: Context, intent: Intent) {
            try {
                // 仅当通知开启时才以「前台服务」启动（否则系统会强制显示通知）；
                // 关闭时以普通后台服务启动，不显示常驻通知。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isNotificationEnabled(context)) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("TransferForegroundService", "start service failed", e)
            }
        }
    }

    private val owners = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }
    /** HTTP/发现服务是否已初始化（无论是否前台） */
    private var isServiceStarted = false
    /** 当前是否已调用 startForeground（显示常驻通知） */
    private var isForegroundActive = false
    /** 局域网发现（UDP 广播+监听）当前是否运行 */
    private var isDiscoveryRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE -> {
                val owner = intent.getStringExtra(EXTRA_OWNER)
                if (owner != null) {
                    val hadOwners = owners.isNotEmpty()
                    owners.remove(owner)
                    if (hadOwners && owners.isEmpty()) {
                        // 所有使用者都已离开，延迟自动关闭
                        handler.removeCallbacks(stopRunnable)
                        handler.postDelayed(stopRunnable, AUTO_STOP_DELAY_MS)
                    }
                }
                return START_STICKY
            }
            ACTION_ACQUIRE -> {
                val owner = intent.getStringExtra(EXTRA_OWNER)
                if (owner != null) {
                    owners.add(owner)
                    // 再次进入，取消可能挂起的延迟关闭
                    handler.removeCallbacks(stopRunnable)
                }
            }
            ACTION_SET_NOTIFICATION -> {
                // 运行时动态切换前台/普通状态（若服务已初始化则原地切换）
                applyForegroundState(intent.getBooleanExtra(EXTRA_ENABLED, false))
                // 2026-10-10 审计修复：不能在此提前 return——服务尚未初始化时，设置页打开通知
                // 会经 startForegroundService 启动本服务，而 applyForegroundState 的
                // !isServiceStarted 早退导致 startForeground 永不被调用，系统 5 秒超时强杀
                // （ForegroundServiceDidNotStartInTimeException）。落到下方初始化分支，
                // 由其读取已持久化的开关（SettingsViewModel 先存后发 intent）调用 startForeground。
            }
            // 无 action（如系统重启投递）：保持运行，但下方按 owners 是否为空决定是否延迟关闭
        }

        // 首次启动：初始化 HTTP 服务 + 发现服务；是否前台取决于通知开关
        if (!isServiceStarted) {
            if (isNotificationEnabled(this)) {
                startForeground(NOTIFICATION_ID, buildNotification())
                isForegroundActive = true
            }

            // 初始化 Web 页面（从 assets 读取）
            HttpServerManager.init(this)
            // 启动 HTTP 服务
            HttpServerManager.start()

            // 初始化局域网设备发现服务：仅 init，不在此全局启动。
            // 发现改为跟随互传持有者（见 updateDiscoveryState）——快速传输页只起 HTTP、
            // 不广播，故不会被任何互传列表发现；只有互传/远程浏览界面可见时才广播。
            DeviceDiscoveryManager.init(this)

            isServiceStarted = true
        }

        // 依当前持有者调整发现状态：互传/远程浏览才广播，快速传输不广播
        updateDiscoveryState()

        // 若此时没有任何持有者（如系统重启重建服务、或 release 后恰好重建），
        // 不应让通知永久挂起，延迟自动关闭以免出现"僵尸通知"。
        if (owners.isEmpty()) {
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, AUTO_STOP_DELAY_MS)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        owners.clear()
        if (isForegroundActive) {
            stopForegroundSafely()
            isForegroundActive = false
        }
        if (isDiscoveryRunning) {
            DeviceDiscoveryManager.stop()
            isDiscoveryRunning = false
        }
        HttpServerManager.stop()
        super.onDestroy()
    }

    /**
     * 根据当前持有者决定局域网发现（UDP 广播+监听）是否运行。
     *
     * 仅当互传相关界面（设备列表 [OWNER_DISCOVERY] 或对端浏览 [OWNER_REMOTE_BROWSE]）
     * 持有时才广播/监听；快速传输页 ([OWNER_TRANSFER]) 只启 HTTP 服务、不广播，
     * 因此不会出现在任何互传列表中。进入互传页会立即触发 [DeviceDiscoveryManager.start]
     * 并发出一次 hello，对端约一个广播周期内即可出现。
     */
    private fun updateDiscoveryState() {
        if (!isServiceStarted) return
        val shouldRun = owners.contains(OWNER_DISCOVERY) || owners.contains(OWNER_REMOTE_BROWSE)
        if (shouldRun && !isDiscoveryRunning) {
            DeviceDiscoveryManager.start(HttpServerManager.getPort())
            isDiscoveryRunning = true
        } else if (!shouldRun && isDiscoveryRunning) {
            DeviceDiscoveryManager.stop()
            isDiscoveryRunning = false
        }
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // 兜底：部分 ROM 在 stopForeground 后通知仍可能残留，显式取消一次确保移除。
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID)
        }
    }

    /**
     * 根据通知开关，动态切换前台/普通运行状态。
     * 仅在服务已初始化时生效；未启动时由下次启动读取设置决定。
     */
    private fun applyForegroundState(enabled: Boolean) {
        if (!isServiceStarted) return
        if (enabled) {
            if (!isForegroundActive) {
                startForeground(NOTIFICATION_ID, buildNotification())
                isForegroundActive = true
            }
        } else {
            // 关闭通知：无论当前是否已在前台都尝试退出前台并移除常驻通知，
            // 避免 isForegroundActive 状态偶发不一致导致通知残留。
            if (isForegroundActive) {
                stopForegroundSafely()
                isForegroundActive = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transfer_service_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.transfer_service_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.transfer_service_running))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}
