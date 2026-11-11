package com.tvflyshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.tvflyshare.app.MainActivity
import com.tvflyshare.app.R
import com.tvflyshare.app.TVTransferApp
import com.tvflyshare.app.httpserver.HttpServerManager
import com.tvflyshare.app.interconnect.DeviceDiscoveryManager
import com.tvflyshare.app.network.NetworkUtils

/**
 * 文件传输服务（托管 HTTP 服务与局域网发现）
 *
 * 生命周期采用「引用计数 + 延迟关闭」策略：
 * - 进入需要本服务的页面（快速传输、TV 互联）时 [acquire] 登记持有者；
 * - 离开页面（ViewModel.onCleared）时 [release] 注销持有者；
 * - 所有持有者都离开后延迟 [AUTO_STOP_DELAY_MS] 自动 stopSelf，关闭 HTTP/UDP 服务。
 * 持有者集合放在进程级，服务被系统回收重建后不会误判「没人用」而把自己关掉。
 *
 * 这样既不长期挂着服务，也不会在页面还开着时被误关。
 *
 * 后台保活（设置项「切到后台继续传」，默认关闭）：
 * - 关闭：全程普通后台服务、零通知；切到后台后系统可能回收服务（属预期取舍）。
 * - 开启：留在应用内仍是普通服务、零通知；只有 App 真正退到后台时才原地升为前台服务，
 *   显示一条带访问地址和「停止后台传输」的通知；回到前台立即撤掉通知并自检 5566。
 *   前台服务通知是 Android 8+ 的强制要求，无法隐藏，故只在真正需要保活时出现。
 *
 * 前后台切换由 TVTransferApp 的 ActivityLifecycleCallbacks 在 onActivityPaused / onActivityResumed
 * 投递（onPause 时 App 仍可见，避开 Android 12+ 「禁止从后台启动前台服务」的限制）。
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
        const val ACTION_APP_BACKGROUND = "com.tvflyshare.app.action.APP_BACKGROUND"
        const val ACTION_APP_FOREGROUND = "com.tvflyshare.app.action.APP_FOREGROUND"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_ENABLED = "enabled"

        /**
         * 进程级持有者集合：不能放在 Service 实例里。
         * 普通后台服务被系统回收后按 START_STICKY 重建时，实例字段会重置为空，
         * 新实例误判「已经没人用」→ 30 秒后把自己关掉，页面还开着、端口却没了
         * （用户实测「切到后台再回来网页连不上，必须退出重进」的根因）。
         * 放在进程级后，重建实例仍知道有页面在用，配合回前台自检即可自动恢复。
         */
        private val owners: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

        /** 当前是否还有页面在用传输服务（供前后台切换前判断是否值得升前台）。 */
        fun hasOwners(): Boolean = owners.isNotEmpty()

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

        /** 设置项变化：只同步运行状态，不为了改开关而把 5566 端口拉起来。 */
        fun setNotificationEnabled(context: Context, enabled: Boolean) {
            // 设置值已由 SettingsViewModel 落盘。服务没在跑时什么都不做——否则打开一个开关
            // 会凭空启动网页服务，把不设登录的端口打开，与「不默认扩大暴露面」的取舍冲突。
            if (!HttpServerManager.isRunning()) return
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_SET_NOTIFICATION
                putExtra(EXTRA_ENABLED, enabled)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("TransferForegroundService", "set notification state failed", e)
            }
        }

        /** App 退到后台（由 onActivityPaused 投递，此时 App 仍可见，升前台不受后台启动限制）。 */
        fun onAppBackground(context: Context) {
            if (!hasOwners() || !isNotificationEnabled(context)) return
            try {
                context.startService(Intent(context, TransferForegroundService::class.java).apply {
                    action = ACTION_APP_BACKGROUND
                })
            } catch (e: Exception) {
                Log.e("TransferForegroundService", "app background switch failed", e)
            }
        }

        /** App 回到前台：撤掉后台通知并自检 5566，服务被系统收走时在这里拉回来。 */
        fun onAppForeground(context: Context) {
            // 没有页面在用服务时不要发，否则 startService 会凭空把网页服务拉起来。
            if (!hasOwners()) return
            try {
                context.startService(Intent(context, TransferForegroundService::class.java).apply {
                    action = ACTION_APP_FOREGROUND
                })
            } catch (e: Exception) {
                Log.e("TransferForegroundService", "app foreground switch failed", e)
            }
        }

        private fun isNotificationEnabled(context: Context): Boolean {
            val app = context.applicationContext as? TVTransferApp
            return app?.settingsRepository?.getNotificationEnabled() ?: false
        }

        private fun startSafely(context: Context, intent: Intent) {
            try {
                // 一律以普通服务投递：前台状态只由 APP_BACKGROUND / APP_FOREGROUND 决定。
                // 不再用 startForegroundService——它要求 5 秒内 startForeground，且 Android 12+
                // 禁止从后台调用，正是「关了通知还弹系统后台运行提示」的来源之一。
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("TransferForegroundService", "start service failed", e)
            }
        }
    }

    // 持有者集合在 companion object（进程级），不能随本实例重建而丢失。
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
        // 前台状态变更统一延后到「首次启动完成之后」再应用：
        // applyForegroundState 对未初始化的服务会早退，若服务刚被系统重建，
        // 在分支里直接调用会静默丢失这次升前台 / 撤通知。
        var foregroundWanted: Boolean? = null

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
                // 打开开关只记录意图，真正升前台发生在下次退到后台（onActivityPaused）；
                // 关闭开关则立刻撤掉已经显示的常驻通知。
                if (!intent.getBooleanExtra(EXTRA_ENABLED, false)) {
                    foregroundWanted = false
                }
            }
            ACTION_APP_BACKGROUND -> {
                // App 即将退到后台：此时仍可见，原地升前台不受 Android 12+ 后台启动限制。
                if (isNotificationEnabled(this) && hasOwners()) {
                    foregroundWanted = true
                }
            }
            ACTION_APP_FOREGROUND -> {
                // 回到前台：撤掉后台通知，并取消「无人使用」的延迟关闭。
                foregroundWanted = false
                handler.removeCallbacks(stopRunnable)
            }
            // 无 action（如系统重启投递）：保持运行，但下方按 owners 是否为空决定是否延迟关闭
        }

        // 首次启动：一律以普通服务初始化 HTTP + 发现服务，不显示通知。
        // 前台状态只由 APP_BACKGROUND / APP_FOREGROUND 驱动，留在应用内始终零通知。
        if (!isServiceStarted) {
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

        // 每次事件都自检重联：服务被系统收走、重建后端口没起来、或首次 start 失败，
        // 都在这里把 5566 拉回来。isRunning() 为真时几乎零开销。
        ensureHttpRunning()

        // 依当前持有者调整发现状态：互传/远程浏览才广播，快速传输不广播
        updateDiscoveryState()

        // 服务被系统回收后 START_STICKY 重建（intent 为 null）时，没有任何前后台事件会再来一次：
        // 若用户仍在后台且开着「切到后台继续传」，这里主动补上升前台，否则新实例又变回
        // 普通后台服务、可能再次被杀，形成「后台反复断」。
        if (foregroundWanted == null &&
            isNotificationEnabled(this) &&
            hasOwners() &&
            (applicationContext as? TVTransferApp)?.isAppInBackground == true
        ) {
            foregroundWanted = true
        }

        foregroundWanted?.let { applyForegroundState(it) }

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
        // owners 是进程级集合，不能在这里清空：Service 重建后仍需知道有页面在用服务。
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
     * 回前台自检重联：5566 没在听就重新拉起。
     * init() 幂等（只读 assets 模板），start() 内部对「已在监听」直接返回，重复调用无副作用。
     */
    private fun ensureHttpRunning() {
        if (HttpServerManager.isRunning()) return
        HttpServerManager.init(this)
        HttpServerManager.start()
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
     * 切换前台/普通运行状态，只在服务已初始化之后调用。
     * 撤销时无条件调 stopForegroundSafely：服务被系统重建后 isForegroundActive 会重置为 false，
     * 但旧实例留下的通知可能仍挂在通知栏，只按这个标志判断会漏撤
     * （用户反馈的「关了还是有通知」）。stopForeground 在非前台时是安全空操作。
     */
    private fun applyForegroundState(enabled: Boolean) {
        if (!isServiceStarted) return
        if (enabled) {
            if (!isForegroundActive) {
                startForeground(NOTIFICATION_ID, buildNotification())
                isForegroundActive = true
            }
        } else {
            stopForegroundSafely()
            isForegroundActive = false
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

    // addAction(int, CharSequence, PendingIntent) 在 API 20 起被标记弃用，
    // 但替代写法（Icon / NotificationCompat）会拉低 minSdk 或引入额外依赖；
    // 项目开了 -Werror，这里统一压制 SDK 内已存在的弃用警告。
    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        // 点通知回到应用；通知里直接给出访问地址——切到别的 App 后不用回来抄二维码。
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags
        )
        // 「停止后台传输」走「打开应用处理」而不是直接 getService：点击通知属于用户交互，
        // Activity 回到前台后再投递停止意图，可避开 Android 8+ 后台 startService 限制
        // （否则按钮在 App 后台时可能点了没反应）。
        val stopPending = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                action = "com.tvflyshare.app.action.STOP_BACKGROUND_FROM_NOTIFICATION"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_STOP_BACKGROUND, true)
            },
            pendingFlags
        )
        val ip = NetworkUtils.getLocalIpAddress()
        val address = if (ip != null) {
            "http://$ip:${HttpServerManager.PORT}"
        } else {
            getString(R.string.transfer_service_address_unknown)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.transfer_service_running))
            .setContentText(getString(R.string.transfer_service_address, address))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.transfer_service_stop),
                stopPending
            )
            .setOngoing(true)
            .build()
    }
}
