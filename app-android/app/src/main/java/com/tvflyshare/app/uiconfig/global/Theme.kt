package com.tvflyshare.app.uiconfig.global

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF06B6D4),
    tertiary = Color(0xFF8B5CF6),
    background = Color(0xFF001133), // 非常深的蓝色背景
    surface = Color(0xFF1A2744), // 深蓝色，避免弹窗变成白色
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF06B6D4),
    tertiary = Color(0xFF8B5CF6),
    background = Color(0xFF003399),
    surface = Color(0xFF1A2744), // 深蓝色，避免弹窗变成白色
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun TVTransferAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // 电视上通常不用动态取色
    themeName: String = "BLUE",
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    // 处理沉浸式状态栏 (Edge-to-edge)
    // 电视上其实没有真正的状态栏，但为了兼容性还是设置一下
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Android 在 compileSdk 35 将 Window.statusBarColor/navigationBarColor 标为废弃，
                // 但 AndroidX core-ktx 1.13.1 尚未提供非废弃等价 setter（WindowInsetsController 不提供设色能力）。
                // 保留透明沉浸效果语义，抑制平台级 deprecation 警告；待 AndroidX 提供替代 API 后迁移。
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.Transparent.toArgb()
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    val currentTvColors = when (themeName) {
        "GREEN" -> greenTvColors
        "PURPLE" -> purpleTvColors
        "ORANGE" -> orangeTvColors
        else -> blueTvColors
    }

    CompositionLocalProvider(
        LocalTvColors provides currentTvColors,
        LocalTvDimens provides defaultTvDimens,
        LocalTvTypography provides defaultTvTypography
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
