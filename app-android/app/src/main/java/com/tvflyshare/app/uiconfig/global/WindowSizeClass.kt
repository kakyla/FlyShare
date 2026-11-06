package com.tvflyshare.app.uiconfig.global

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 窗口宽度档位（Material 3 官方 adaptive breakpoints）。
 *
 * - COMPACT：< 600dp，竖屏手机
 * - MEDIUM：600–839dp，横屏手机 / 竖屏平板 / 分屏
 * - EXPANDED：≥ 840dp，横屏平板 / 桌面级大屏
 */
enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

/**
 * 窗口高度档位（Material 3 官方双维度判定的第二个维度）。
 *
 * 各档对应的真实形态已按 Compose 实际布局尺寸实测核对（2026-10-29 审计修正，
 * 注意这里的高度是「已含 480dp 基座缩放」的可用高度，不是屏幕物理 dp）：
 * - COMPACT：≤ 480dp —— 手机横屏（如 2400×1080@2.75 → 480dp）
 * - MEDIUM：481–899dp —— 横屏平板（如 2176×1600@2.0 → 800dp）
 * - EXPANDED：≥ 900dp —— 竖屏手机（1067dp）/ 竖屏平板（1088dp）
 *
 * 注意不要把「竖屏手机」当成 MEDIUM：竖屏手机高度约 1067dp，属 EXPANDED；
 * 真正落在 MEDIUM 的是横屏平板。这条此前注释写反，已按实测修正。
 */
enum class WindowHeightClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

/**
 * 窗口自适应信息：宽度档位 + 高度档位 + 是否 TV 设备 + 实际布局尺寸。
 *
 * 【数据源：必须用 Compose 实际可用布局尺寸，不能直接用 Configuration.screenWidthDp】
 * MainActivity 按 480dp 基座缩放了 LocalDensity（scale = smallestWidthDp/480，上限 1.0），
 * 而 Configuration.screenWidthDp 由**系统密度**推导、不含该缩放，两者相差可达 22%：
 * 1080×2400@2.75 手机横屏时 screenWidthDp=873，但 Compose 实际可用布局宽达 1067dp。
 * 用系统值判档会系统性低估手机可用空间，正是 2026-10-24「横屏误用竖屏网格」的直接原因。
 * 故此处按同一公式反算：Compose 实际布局宽 = screenWidthDp ÷ densityScale
 * （densityScale 与 MainActivity 的缩放完全同源，基座常量已登记在 TvTheme.uiDensityBaseWidthDp）。
 *
 * isTv 单独暴露的原因：部分低端电视/盒子（如 720p，宽约 640dp）会落入 MEDIUM 档，
 * 但电视场景必须按横屏宽屏布局处理（TV 设备运行时已锁横屏，见 MainActivity），
 * 布局判断需以 isTv 优先，不能只看宽度断点。
 */
data class WindowAdaptiveInfo(
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass,
    val isTv: Boolean,
    val widthDp: Float,
    val heightDp: Float
) {
    /**
     * 是否使用宽屏布局（首页 4 组并排的 weight 网格）。
     *
     * 判定采用「官方双维度 + 适配度」三重约束：
     *
     * 1. TV 设备：恒用宽屏布局（TV 运行时锁横屏，见 MainActivity）。
     * 2. 宽度类 COMPACT：紧凑布局（官方 Compact = 1 窗格）。
     * 3. 宽度够放 4 组并排（widthDp ≥ [TvTheme.windowWideGridMinWidthDp]）：
     *    走宽屏布局。该阈值由**内容实际需求**反推得出，不是拍脑袋的分辨率——
     *    4 组并排时最窄的一组只占 316/1598 权重，需 ≥ 152dp 才能让「快速传输」
     *    四字（18sp 约 72dp）+ 图标 32dp + 间距与内边距 48dp 单行放下，
     *    反推得窗口宽需 ≥ 152 + 152×1598/316 ≈ 920dp。
     * 4. 高度类 COMPACT（纵向空间不足）且宽度已达 MEDIUM：走宽屏布局。
     *    官方说明：宽度够但高度紧凑时，双窗格布局并不实用（双列网格左列要竖排 7 个
     *    磁贴，纵向空间不足时每行被压得极扁）。
     * 5. 其余（宽度够但纵向空间充裕，如竖屏平板）：走双列竖屏网格。
     *
     * 【为什么用「宽度类 × 高度类」而不用 orientation】
     * 官方 adaptive 指南的避免清单明确写：
     * "Making layout decisions from orientation. A landscape phone and a portrait
     * tablet can have the same width class for opposite reasons"
     * —— 横屏手机与竖屏平板可能宽度相同但成因相反，只看朝向（或只看宽度）都会误判。
     * 官方正解是宽度类与高度类两个维度各自独立分档，共同描述窗口形状。
     */
    val isWideLayout: Boolean
        get() = isTv ||
            (
                widthClass != WindowWidthClass.COMPACT &&
                    (
                        widthDp >= TvTheme.windowWideGridMinWidthDp ||
                            heightClass == WindowHeightClass.COMPACT
                        )
                )
}

/**
 * 计算当前窗口自适应信息，窗口尺寸变化时自动重组。
 */
@Composable
fun rememberWindowAdaptiveInfo(): WindowAdaptiveInfo {
    val configuration = LocalConfiguration.current

    // 【关键】Configuration.screenWidthDp/screenHeightDp 由**系统密度**推导，
    // 不含 MainActivity 的 480dp 基座缩放（scale = smallestWidthDp/480，上限 1.0），
    // 直接拿来判档会系统性低估约 22%（1080×2400@2.75 手机横屏：
    // screenWidthDp=873，而 Compose 实际可用布局宽达 1067dp）——
    // 这正是 2026-10-24「横屏误用竖屏网格」误判的直接原因。
    //
    // 换算：Compose 实际布局宽 = screenWidthDp ÷ scale
    // （scale 与 MainActivity 中 density 缩放完全同源，同一常量、同一公式）。
    //
    // coerceIn(0.25f, 1f) 的上下界：
    //   上界 1.0 —— 与 MainActivity 一致（scale 不超过 1，大屏不放大）。
    //   下界 0.25 —— 防御 smallestScreenWidthDp 返回 0（极少数异常 ROM/窗口尚未布局完成时
    //   会短暂为 0），否则除零会让 widthDp 变成 Infinity 导致布局异常。
    //   0.25 对应 smallestWidthDp=120dp，远低于任何真实设备（最小也在 320dp 左右），
    //   取它既能拦住除零，又不会影响任何真实形态的判定结论。
    val densityScale = (configuration.smallestScreenWidthDp.toFloat() /
        TvTheme.uiDensityBaseWidthDp).coerceIn(0.25f, 1f)
    val widthDp: Float = configuration.screenWidthDp / densityScale
    val heightDp: Float = configuration.screenHeightDp / densityScale
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION

    return remember(widthDp, heightDp, isTv) {
        val compactWidth = TvTheme.windowBreakpointCompactDp.toFloat()
        val expandedWidth = TvTheme.windowBreakpointExpandedDp.toFloat()
        val compactHeight = TvTheme.windowHeightBreakpointCompactDp.toFloat()
        val expandedHeight = TvTheme.windowHeightBreakpointExpandedDp.toFloat()
        WindowAdaptiveInfo(
            widthClass = when {
                widthDp < compactWidth -> WindowWidthClass.COMPACT
                widthDp < expandedWidth -> WindowWidthClass.MEDIUM
                else -> WindowWidthClass.EXPANDED
            },
            heightClass = when {
                heightDp <= compactHeight -> WindowHeightClass.COMPACT
                heightDp < expandedHeight -> WindowHeightClass.MEDIUM
                else -> WindowHeightClass.EXPANDED
            },
            isTv = isTv,
            widthDp = widthDp,
            heightDp = heightDp
        )
    }
}
