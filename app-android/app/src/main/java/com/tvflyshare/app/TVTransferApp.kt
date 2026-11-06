package com.tvflyshare.app

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import com.tvflyshare.app.settings.SettingsRepository
import com.tvflyshare.app.util.AppException
import java.util.Locale

class TVTransferApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        
        settingsRepository = SettingsRepository(this)
        
        // 捕获全局崩溃异常，写入本地文件
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLogLocally(thread, throwable)
            // 杀死进程
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
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
