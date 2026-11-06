package com.tvflyshare.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * 统一权限管理器
 *
 * 封装所有运行时权限的检查与请求：
 * - 存储权限（MANAGE_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE）
 * - 媒体权限（READ_MEDIA_IMAGES / VIDEO / AUDIO，Android 13+）
 * - 通知权限（POST_NOTIFICATIONS，Android 13+）
 * - APK 安装权限（REQUEST_INSTALL_PACKAGES）
 *
 * 使用方式：
 * 1. MainActivity.onCreate 中调用 PermissionManager.init(this)
 * 2. 需要权限时调用 hasPermission() 检查 / requestPermission() 请求
 *
 * 兼容性策略（授权重构 C1-C4）：
 * - C1 探活：跳 MANAGE 设置页前先 canResolveActivity 探活，无目标 Activity 时不硬跳。
 * - C2 SAF 兜底：魔改 ROM 缺失设置页时降级引导 OpenDocumentTree 选定目录。
 */
object PermissionManager {

    private const val TAG = "PermissionManager"

    /** 权限分组 */
    sealed class PermissionGroup {
        data object Storage : PermissionGroup()
        data object MediaImages : PermissionGroup()
        data object MediaVideo : PermissionGroup()
        data object MediaAudio : PermissionGroup()
        data object Notifications : PermissionGroup()
        data object InstallApk : PermissionGroup()
    }

    private var activityRef: WeakReference<ComponentActivity>? = null

    // 运行时权限请求 launcher（API 23+）
    private var runtimePermissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var runtimePermissionCallback: ((Map<String, Boolean>) -> Unit)? = null

    // 设置页返回 launcher（MANAGE_EXTERNAL_STORAGE / 未知来源安装）
    private var settingsLauncher: ActivityResultLauncher<Intent>? = null
    private var settingsCallback: ((Boolean) -> Unit)? = null

    // SAF 文件夹兜底 launcher（OpenDocumentTree）
    private var safLauncher: ActivityResultLauncher<Uri?>? = null
    private var safCallback: ((Uri?) -> Unit)? = null

    // 通知权限 launcher（Android 13+）
    private var notificationLauncher: ActivityResultLauncher<String>? = null
    private var notificationCallback: ((Boolean) -> Unit)? = null

    /**
     * 初始化，在 MainActivity.onCreate 中调用
     * 必须在 setContent 之前调用
     */
    fun init(activity: ComponentActivity) {
        activityRef = WeakReference(activity)

        runtimePermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            runtimePermissionCallback?.invoke(result)
            runtimePermissionCallback = null
        }

        settingsLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // 设置页返回，检查权限状态
            settingsCallback?.invoke(true)
            settingsCallback = null
        }

        safLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            safCallback?.invoke(uri)
            safCallback = null
        }

        notificationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            notificationCallback?.invoke(granted)
            notificationCallback = null
        }
    }

    /**
     * 检查权限是否已授予
     */
    fun hasPermission(context: Context, group: PermissionGroup): Boolean {
        return when (group) {
            PermissionGroup.Storage -> StoragePermissionUtils.hasStoragePermission(context)
            PermissionGroup.MediaImages -> checkRuntimePermission(context, Manifest.permission.READ_MEDIA_IMAGES)
            PermissionGroup.MediaVideo -> checkRuntimePermission(context, Manifest.permission.READ_MEDIA_VIDEO)
            PermissionGroup.MediaAudio -> checkRuntimePermission(context, Manifest.permission.READ_MEDIA_AUDIO)
            PermissionGroup.Notifications -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    true
                }
            }
            PermissionGroup.InstallApk -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else {
                    true
                }
            }
        }
    }

    /**
     * 请求权限（异步）
     * @param context 上下文
     * @param group 权限分组
     * @param onResult 回调，true 表示已授权
     */
    fun requestPermission(context: Context, group: PermissionGroup, onResult: (Boolean) -> Unit) {
        when (group) {
            PermissionGroup.Storage -> requestStoragePermission(context, onResult)
            PermissionGroup.MediaImages -> requestRuntimePermission(context, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), onResult)
            PermissionGroup.MediaVideo -> requestRuntimePermission(context, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), onResult)
            PermissionGroup.MediaAudio -> requestRuntimePermission(context, arrayOf(Manifest.permission.READ_MEDIA_AUDIO), onResult)
            PermissionGroup.Notifications -> requestNotificationPermission(context, onResult)
            PermissionGroup.InstallApk -> requestInstallApkPermission(context, onResult)
        }
    }

    // ============================================================
    // 内部实现
    // ============================================================

    private fun checkRuntimePermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission(context: Context, onResult: (Boolean) -> Unit) {
        val needsAdvanced = StoragePermissionUtils.needsSettingsPage() && !StoragePermissionUtils.hasAdvancedStoragePermission()
        if (!needsAdvanced && StoragePermissionUtils.hasStoragePermission(context)) {
            onResult(true)
            return
        }

        val activity = activityRef?.get()
        if (activity == null) {
            onResult(false)
            return
        }

        if (StoragePermissionUtils.needsSettingsPage()) {
            // Android 11+：跳设置页
            // C1 探活：确认 MANAGE 设置页存在可解析的 Activity，避免魔改 ROM 上硬跳无反应/崩溃
            val intent = StoragePermissionUtils.getManageStorageIntent(context)
            if (StoragePermissionUtils.canResolveActivity(context, intent)) {
                settingsCallback = { _ ->
                    onResult(StoragePermissionUtils.hasStoragePermission(context))
                }
                try {
                    settingsLauncher?.launch(intent)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    settingsCallback = null
                    // 启动失败，降级到 SAF 兜底
                    launchSafFallback(context, onResult)
                }
            } else {
                // C2：无 MANAGE 设置页，直接走 SAF 文件夹兜底
                launchSafFallback(context, onResult)
            }
        } else {
            // Android 6.0-10：弹 Dialog
            val perms = StoragePermissionUtils.getRuntimePermissions()
            if (perms.isEmpty()) {
                onResult(true)
                return
            }
            runtimePermissionCallback = { _ ->
                // 2026-10-04 审计 P2 修复：原先 result.values.any{it} 导致只授 READ 也算成功，
                // 而 hasStoragePermission 只认 WRITE——统一以最终权限状态为准
                onResult(StoragePermissionUtils.hasStoragePermission(context))
            }
            try {
                runtimePermissionLauncher?.launch(perms)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                runtimePermissionCallback = null
                onResult(false)
            }
        }
    }

    /**
     * C2 SAF 文件夹兜底：引导用户用系统文件选择器(OpenDocumentTree)选定目录，
     * 作为无 MANAGE 权限时的受限存储根。SAF_FALLBACK_ENABLED=false 时直接失败。
     */
    private fun launchSafFallback(context: Context, onResult: (Boolean) -> Unit) {
        if (!StoragePermissionUtils.SAF_FALLBACK_ENABLED) {
            onResult(false)
            return
        }
        safCallback = { uri ->
            if (uri != null) {
                StoragePermissionUtils.savePersistedSafUri(context, uri)
                onResult(StoragePermissionUtils.hasStoragePermission(context))
            } else {
                onResult(false)
            }
        }
        try {
            safLauncher?.launch(null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            safCallback = null
            onResult(false)
        }
    }

    private fun requestRuntimePermission(context: Context, permissions: Array<String>, onResult: (Boolean) -> Unit) {
        // 过滤出未授权的权限
        val needed = permissions.filter { !checkRuntimePermission(context, it) }
        if (needed.isEmpty()) {
            onResult(true)
            return
        }

        runtimePermissionCallback = { result ->
            val granted = needed.all { result[it] == true }
            onResult(granted)
        }
        try {
            runtimePermissionLauncher?.launch(needed.toTypedArray())
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            runtimePermissionCallback = null
            onResult(false)
        }
    }

    private fun requestNotificationPermission(context: Context, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(true)
            return
        }
        if (checkRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            onResult(true)
            return
        }
        notificationCallback = onResult
        try {
            notificationLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            notificationCallback = null
            onResult(false)
        }
    }

    private fun requestInstallApkPermission(context: Context, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onResult(true)
            return
        }
        if (context.packageManager.canRequestPackageInstalls()) {
            onResult(true)
            return
        }
        // 跳未知来源安装设置页
        settingsCallback = { _ ->
            onResult(context.packageManager.canRequestPackageInstalls())
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            settingsLauncher?.launch(intent)
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            settingsCallback = null
            onResult(false)
        }
    }
}
