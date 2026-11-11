package com.tvflyshare.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import com.tvflyshare.app.uiconfig.components.GlobalToastHost
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.tvflyshare.app.service.TransferForegroundService
import com.tvflyshare.app.ui.navigation.TVTransferNavHost
import com.tvflyshare.app.uiconfig.global.TVTransferAssistantTheme
import com.tvflyshare.app.util.PermissionManager
import com.tvflyshare.app.util.StoragePermissionUtils

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.tvflyshare.app.uiconfig.global.TvTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** 后台传输通知上的「停止后台传输」按钮带此标记。 */
        const val EXTRA_STOP_BACKGROUND = "com.tvflyshare.app.extra.STOP_BACKGROUND"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStopBackground(intent)

        val isTv = isTvDevice()

        // TV 设备锁横屏：符合 Android TV 官方清单（TV 屏固定横屏，需显式声明），且永远不读用户设置。
        // 非 TV 设备（手机/平板/车机）按「屏幕方向」设置锁定/跟随：
        //   LANDSCAPE 锁横屏 / PORTRAIT 锁竖屏 / AUTO 跟随传感器（即原默认行为）。
        // 启动时即应用一次（避免首帧闪一下），之后由下面的 LaunchedEffect 在设置变更时即时生效。
        applyScreenOrientation(isTv, (application as TVTransferApp).settingsRepository.getScreenOrientation())

        // 启用 edge-to-edge：让内容延伸到状态栏和导航栏区域，消除手机黑边。
        // 电视无系统栏，调用无副作用；但为语义清晰，仅非 TV 设备启用。
        if (!isTv) {
            enableEdgeToEdge()
        }

        // 初始化统一权限管理器（必须在 setContent 之前）
        PermissionManager.init(this)

        // 已经不需要在这里提前申请了，交给 Compose Navigation 的 Splash 路由去处理
        // StoragePermissionUtils.getStorageDir(this) 等操作会在授权完成后再进行

        setContent {
            val app = application as TVTransferApp
            val currentTheme by app.settingsRepository.themeFlow.collectAsStateWithLifecycle()
            val screenOrientation by app.settingsRepository.screenOrientationFlow.collectAsStateWithLifecycle()

            // 屏幕方向：用户在设置页改「屏幕方向」后即时生效（无需重启 App）。
            // TV 设备始终走横屏，此分支直接跳过（applyScreenOrientation 内部已判 isTv）。
            LaunchedEffect(screenOrientation) {
                applyScreenOrientation(isTv, screenOrientation)
            }

            TVTransferAssistantTheme(themeName = currentTheme) {
                // 屏幕等比缩放
                // 电视短边 600dp ≥ BASE_WIDTH_DP → scale=1.0，不缩放（零变化）。
                // 手机短边约 480dp → scale≈1.0，基本不缩放（字体保持原始大小）。
                // 用 smallestScreenWidthDp（短边）：不管横屏竖屏都取最小值，
                // 避免手机横屏时 screenWidthDp 变大导致不缩放的问题。
                //
                // 只缩 density（控制 dp→px），不缩 fontScale：
                // original.fontScale 已包含用户系统字号设置（如"大字号"1.3），
                // 若再乘 scale 会导致字号叠加放大，可能撑爆布局。保留用户字号偏好即可。
                val configuration = LocalConfiguration.current
                val smallestWidthDp = configuration.smallestScreenWidthDp.toFloat()
                // 【顶部安全区仅在竖屏生效】横屏时状态栏在侧边、内容不从顶部被遮挡，
                // 再压一段顶部内缩只会白掉一条，且横屏本就靠窗口宽度分档布局。
                // 电视无系统栏、且未启用 edge-to-edge，保持原样（零变化）。
                val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val applyTopInset = !isTv && isPortrait
                val scale = (smallestWidthDp / TvTheme.uiDensityBaseWidthDp).coerceAtMost(1f)

                val original = LocalDensity.current
                val scaledDensity = Density(
                    density = original.density * scale,
                    fontScale = original.fontScale,
                )
                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        // 竖屏内缩后露出的状态栏条用渐变顶部色，与页面渐变顶端同色、接缝无色差；
                        // 横屏与电视没有内缩，仍用原来的底部色，保持原样。
                        color = if (applyTopInset) TvTheme.colors.bgGradientTop else TvTheme.colors.bgGradientBottom,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 【竖屏顶部安全区】edge-to-edge 下内容默认从 y=0 起画，
                            // 手机竖屏会被刘海/挖孔或状态栏遮住顶部标题文字。
                            // 竖屏时把全部页面（含 TopBar 与各页自绘顶栏）统一压到状态栏/刘海下方，
                            // 背景渐变仍由各页 TVBackground 全屏铺满，只下移内容不下移背景。
                            // 只取 Top，不侵入底部（底部导航/手势条不在本次范围）。
                            val contentModifier =
                                if (applyTopInset) {
                                    Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(
                                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
                                        )
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            Box(modifier = contentModifier) {
                                TVTransferNavHost()
                            }
                            // Toast 作为全屏浮层，不参与顶部内缩
                            GlobalToastHost()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStopBackground(intent)
    }

    /**
     * 通知上的「停止后台传输」：关掉设置开关并撤回前台状态。
     * 走 Activity 而不是后台 startService，避开 Android 8+ 后台服务限制；
     * 网页服务本身保留（页面还开着时本来就该在），退出页面后仍由 30 秒规则自动收掉。
     */
    private fun handleStopBackground(source: Intent?) {
        if (source?.getBooleanExtra(EXTRA_STOP_BACKGROUND, false) != true) return
        source.removeExtra(EXTRA_STOP_BACKGROUND)
        (application as? TVTransferApp)?.settingsRepository?.setNotificationEnabled(false)
        TransferForegroundService.setNotificationEnabled(this, false)
    }

    /** 判断当前设备是否为电视（用于决定是否启用 edge-to-edge 等） */
    private fun isTvDevice(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * 按设备类型与设置应用屏幕方向。
     * - TV：永远横屏（Android TV 官方清单要求，且电视无法转竖屏）。
     * - 非 TV：LANDSCAPE 锁横屏 / PORTRAIT 锁竖屏 / 其余（含 AUTO）跟随传感器（即原默认行为）。
     */
    private fun applyScreenOrientation(isTv: Boolean, orientation: String) {
        if (isTv) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            return
        }
        requestedOrientation = when (orientation) {
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
