package com.tvflyshare.app.ui.screens.appuninstall

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.tvflyshare.app.R
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

import com.tvflyshare.app.util.StoragePermissionUtils

object AppUninstallManager {

    /**
     * 扫描已安装的用户应用（排除系统核心应用）
     */
    suspend fun loadUserApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        apps
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    name = pm.getApplicationLabel(it).toString(),
                    icon = pm.getApplicationIcon(it),
                    isSystem = false,
                    sourceLabel = context.getString(R.string.app_uninstall_user_installed),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 扫描系统自带应用
     */
    suspend fun loadSystemApps(context: Context, hideComponents: Boolean = true): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        apps
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            .filter {
                if (hideComponents) {
                    pm.getLaunchIntentForPackage(it.packageName) != null
                } else {
                    true
                }
            }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    name = pm.getApplicationLabel(it).toString(),
                    icon = pm.getApplicationIcon(it),
                    isSystem = true,
                    sourceLabel = context.getString(R.string.app_uninstall_system_apps),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 启动目标应用
     * @return 如果应用没有启动入口（如某些底层服务），返回 false
     */
    fun launchApp(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return true
        }
        return false
    }

    /**
     * 打开系统原生应用详情页（应用设置：存储/权限/强行停止等）
     *
     * 说明：卸载流程刻意不用此页面（部分定制电视系统魔改详情页导致卸载按钮缺失，见 createUninstallIntent 注释），
     * 但作为独立入口仍有价值——部分机型用户需要进原生设置管理应用。
     * @return 设备不支持该 ACTION 时返回 false
     */
    fun openAppDetails(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("AppUninstallManager", "openAppDetails failed for $packageName", e)
            false
        }
    }

    /**
     * 创建应用卸载 Intent
     *
     * 优化说明：
     * 之前使用的是 ACTION_APPLICATION_DETAILS_SETTINGS (应用详情页)，在部分定制化电视系统上
     * 该页面会被魔改，导致用户无法找到卸载按钮，甚至提示"不可卸载请到设置里"等误导信息。
     * 现在改为使用标准的 ACTION_DELETE (或 ACTION_UNINSTALL_PACKAGE)，直接拉起系统的原生卸载确认弹窗。
     */
    fun createUninstallIntent(packageName: String): Intent {
        // 使用 ACTION_DELETE 直接呼出卸载弹窗
        return Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    /**
     * 提取已安装应用为 APK 文件
     * @return 提取成功返回目标文件路径，失败返回错误信息
     */
    suspend fun extractApk(context: Context, packageName: String, appName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val sourceFile = File(appInfo.sourceDir)

            if (!sourceFile.exists()) {
                return@withContext Result.failure(Exception(context.getString(R.string.app_uninstall_error_source_not_found)))
            }

            // 目标目录：统一使用全局 StoragePermissionUtils 获取
            val targetDir = StoragePermissionUtils.getStorageDir(context)
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    return@withContext Result.failure(Exception(context.getString(R.string.app_uninstall_error_create_dir)))
                }
            }

            // 过滤非法字符
            val safeAppName = appName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val targetFile = File(targetDir, "${safeAppName}_$packageName.apk")

            // 2026-10-04 审计 P2 修复：先写隐藏临时名再原子改名，失败清理——
            // 原先直接覆盖写，IO 中途异常（空间不足/拔出）会留下截断的正式名 APK
            val tmpFile = File(targetDir, ".${targetFile.name}.tmp")
            try {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                tmpFile.takeIf { it.exists() }?.delete()
                throw e
            }

            return@withContext Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            Log.e("AppUninstallManager", "Failed to export APK", e)
            return@withContext Result.failure(e)
        }
    }
}
