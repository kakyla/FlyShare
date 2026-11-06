package com.tvflyshare.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 封装存储权限的状态机，负责处理各种系统版本和“魔改”电视系统的权限申请逻辑。
 * 提供一个单一的可信数据源：[isGranted]
 *
 * 兼容性策略（授权重构 C1-C4）：
 * - C1 探活：跳 MANAGE 设置页前用 [StoragePermissionUtils.canResolveActivity] 探活，无目标 Activity 时不硬跳，避免无反应/崩溃。
 * - C2 SAF 兜底：魔改 ROM 缺失「所有文件访问」设置页时，降级引导系统文件选择器(OpenDocumentTree)选定目录。
 */
@Stable
class TvStoragePermissionState(
    private val context: Context,
    private val onResult: (Boolean) -> Unit
) {
    var isGranted by mutableStateOf(StoragePermissionUtils.hasStoragePermission(context))
        private set

    internal var settingsLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>? = null
    internal var permissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, @JvmSuppressWildcards Boolean>>? = null
    internal var safLauncher: ManagedActivityResultLauncher<Uri?, Uri?>? = null

    /**
     * 刷新权限状态。通常在生命周期 onResume 时调用，防止用户在系统设置里改了权限但没回调。
     */
    fun refresh() {
        val current = StoragePermissionUtils.hasStoragePermission(context)
        if (isGranted != current) {
            isGranted = current
            onResult(current)
        }
    }

    /**
     * 发起基础权限请求。
     * 策略：始终优先请求基础的 READ/WRITE 运行时权限（原生弹窗）。
     * 这是对用户打扰最小、兼容性最强（包括海信等魔改系统）的方式。
     */
    fun launchRequest() {
        if (isGranted) return

        val perms = StoragePermissionUtils.getRuntimePermissions()
        if (perms.isNotEmpty()) {
            try {
                permissionLauncher?.launch(perms)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 如果原生弹窗都抛异常，再尝试跳设置页作为最后的挣扎
                launchAdvancedRequest()
            }
        } else {
            // API < 23，不需要动态权限
            refresh()
        }
    }

    /**
     * 发起高级权限请求（Android 11+ 的 MANAGE_EXTERNAL_STORAGE 设置页）。
     *
     * C1 探活：跳设置页前先 [StoragePermissionUtils.canResolveActivity] 探活，无目标 Activity 时不硬跳，
     * 避免无反应/崩溃（常见于魔改 ROM 缺失该设置页）。
     * C2 兜底：探活失败时降级引导 SAF 文件夹选择（[launchSafFallback]）。
     */
    fun launchAdvancedRequest() {
        if (StoragePermissionUtils.hasAdvancedStoragePermission()) return

        if (StoragePermissionUtils.needsSettingsPage()) {
            // C1：探活 MANAGE 设置页是否存在可解析的 Activity
            val intent = StoragePermissionUtils.getManageStorageIntent(context)
            if (StoragePermissionUtils.canResolveActivity(context, intent)) {
                try {
                    settingsLauncher?.launch(intent)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    launchSafFallback()
                }
            } else {
                // 魔改 ROM 缺失设置页，走 SAF 文件夹兜底
                launchSafFallback()
            }
        }
    }

    /**
     * C2 SAF 文件夹兜底：引导用户用系统文件选择器(OpenDocumentTree)选定目录，
     * 作为无 MANAGE 权限时的受限存储根。[StoragePermissionUtils.SAF_FALLBACK_ENABLED]=false 时直接失败。
     */
    fun launchSafFallback() {
        if (!StoragePermissionUtils.SAF_FALLBACK_ENABLED) {
            onResult(false)
            return
        }
        try {
            safLauncher?.launch(null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onResult(false)
        }
    }

    internal fun onSettingsResult() {
        refresh()
    }

    internal fun onSafResult(uri: Uri?) {
        if (uri != null) {
            StoragePermissionUtils.savePersistedSafUri(context, uri)
            refresh()
        } else {
            onResult(false)
        }
    }

    internal fun onPermissionResult(permissions: Map<String, Boolean>) {
        // 只要回调了，就直接检查系统的真实状态
        refresh()
    }
}

/**
 * 在 Compose 中记住并获取存储权限状态机
 *
 * @param onResult 权限变化时的回调，常用于重新加载数据或提示
 */
@Composable
fun rememberTvStoragePermissionState(
    onResult: (Boolean) -> Unit = {}
): TvStoragePermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val state = remember(context) {
        TvStoragePermissionState(context, onResult)
    }

    // 1. 注册 ActivityResultLauncher (必须在 Composable 层次结构中尽早注册)
    state.settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        state.onSettingsResult()
    }

    state.permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        state.onPermissionResult(result)
    }

    state.safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        state.onSafResult(uri)
    }

    // 2. 监听生命周期，从后台切回前台时自动刷新权限状态
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.settingsLauncher = null
            state.permissionLauncher = null
            state.safLauncher = null
        }
    }

    return state
}
