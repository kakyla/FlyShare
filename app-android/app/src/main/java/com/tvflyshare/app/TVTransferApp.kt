package com.tvflyshare.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import com.tvflyshare.app.settings.SettingsRepository
import com.tvflyshare.app.service.TransferForegroundService
import com.tvflyshare.app.util.AppException
import java.util.Locale

class TVTransferApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    /** 处于 RESUMED 的 Activity 数量：0→1 视为回到前台，1→0 视为退到后台。 */
    private var resumedActivityCount = 0

    /** App 当前是否处于后台（供传输服务被系统重建时判断是否要补升前台）。 */
    @Volatile
    var isAppInBackground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        
        settingsRepository = SettingsRepository(this)
        registerAppForegroundTracker()
        
        // 捕获全局崩溃异常，写入本地文件
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLogLocally(thread, throwable)
            // 杀死进程
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    /**
     * 跟踪 App 整体前后台（不依赖任何页面）：退到后台时让传输服务升为前台保活，
     * 回到前台时撤通知并自检 5566。只在确实有页面在用服务时才投递意图，
     * 避免为了一次前后台切换把不设登录的网页端口凭空打开。
     */
    private fun registerAppForegroundTracker() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (resumedActivityCount == 0) {
                    isAppInBackground = false
                    TransferForegroundService.onAppForeground(this@TVTransferApp)
                }
                resumedActivityCount++
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
                if (resumedActivityCount == 0) {
                    isAppInBackground = true
                    // onPause 时 App 仍可见，此时升前台不受 Android 12+ 后台启动限制
                    TransferForegroundService.onAppBackground(this@TVTransferApp)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun saveCrashLogLocally(thread: Thread, throwable: Throwable) {
        try {
            // 2026-10-04 审计 P2 修复：原先只写共享存储根（授权前的崩溃——最关键的首次崩溃现场——
            // 静默丢失），且回退到 App 私有目录；同时加 2MB 轮转，防 crash_log.txt 无限增长
            var logDir = File(Environment.getExternalStorageDirectory(), "TVfile")
            if (!logDir.exists() || !logDir.canWrite()) {
                logDir = File(getExternalFilesDir(null) ?: filesDir, "TVfile")
                logDir.mkdirs()
            }

            val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val logFile = File(logDir, "crash_log.txt")

            // 简单轮转：超过 2MB 时旧文件改名 .1（存在即覆盖），本次从头写
            try {
                if (logFile.exists() && logFile.length() > 2L * 1024 * 1024) {
                    val rotated = File(logDir, "crash_log.1.txt")
                    rotated.delete()
                    logFile.renameTo(rotated)
                }
            } catch (_: Exception) {
                // 轮转失败不影响本次写日志
            }

            // 使用追加模式写入同一个文件
            PrintWriter(FileWriter(logFile, true)).use { writer ->
                writer.println("\n\n=======================================================")
                writer.println("--- TVTransferAssistant Crash Log ---")
                writer.println("Time: $time")
                writer.println("Thread: ${thread.name} (ID: ${thread.id})")
                writer.println("App Version: ${getAppVersion()}")
                writer.println("Android OS: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                writer.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                writer.println("\n--- Stack Trace ---")
                throwable.printStackTrace(writer)
                writer.println("=======================================================")
            }
            Log.e("TVTransferApp", "Fatal Crash! Log saved to: ${logFile.absolutePath}", throwable)
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("TVTransferApp", "Failed to save crash log", AppException.fromThrowable(e))
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
            "v${pInfo.versionName} ($vCode)"
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            "Unknown"
        }
    }
}
