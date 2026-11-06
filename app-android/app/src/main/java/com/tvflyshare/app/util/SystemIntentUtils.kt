package com.tvflyshare.app.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.tvflyshare.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.tvflyshare.app.uiconfig.components.GlobalToastManager

object SystemIntentUtils {
    // 复用的协程作用域（带 SupervisorJob），避免每次调用都新建未受控作用域导致 Job 泄漏
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 尝试打开系统原生设置页面
     */
    fun openNativeSettings(context: Context): Boolean {
        // 定义多种可能的原生设置 Intent
        val intents = listOf(
            // 尝试显式调用 Android TV 原生设置包名
            Intent().apply {
                setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
            },
            // 尝试显式调用原生手机/平板设置包名
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.Settings")
            },
            // 尝试使用特殊的 Action（部分电视未拦截这些 Action）
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            // 最后回退到常规的 ACTION_SETTINGS
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 忽略异常，继续尝试下一个 Intent
            }
        }

        // 如果全部失败，提示用户
        scope.launch {
            GlobalToastManager.showToast(context.getString(R.string.more_system_settings_failed))
        }
        return false
    }

    /**
     * 尝试打开开发者选项页面
     */
    fun openDeveloperOptions(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            true
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is CancellationException) throw e
            Log.e("SystemIntentUtils", "Failed to open developer options settings", e)
            scope.launch {
                GlobalToastManager.showToast(context.getString(R.string.more_developer_options_failed))
            }
            false
        }
    }
}