package com.tvflyshare.app.uiconfig.global

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import android.os.Build
import android.view.Window
import android.view.WindowManager

/**
 * 动态主题颜色配置模型
 */
data class TvColors(
    val bgGradientTop: Color,
    val bgGradientBottom: Color,
    val brandPrimary: Color,
    val brandSuccess: Color,
    val brandWarning: Color,
    val brandError: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnTile: Color,
    val textOnTileDim: Color,
    val surface: Color,
    val surfaceHover: Color,
    val surfaceVariant: Color,
    val borderNormal: Color,
    val borderFocused: Color,
    val focusBorder: Color,
    val tileColor1: Color,
    val tileColor2A: Color,
    val tileColor2B: Color,
    val tileColor3: Color,
    val tileColor4A: Color,
    val tileColor4B: Color,
    val tileColor4C: Color,
    val selectCircleBg: Color,
    val iconFolder: Color, // 专门为文件夹指定的颜色
    val dialogBg: Color, // 弹窗与侧边栏背景
    val dialogScrim: Color, // 弹窗背后的全屏遮罩颜色
    val surfaceLight: Color, // 浅色表面：二维码承载卡片等需要高对比白底的场景
    val textOnSurfaceLight: Color, // 浅色表面上的文字色
    val mediaBackdrop: Color // 全屏媒体预览底色（看图/看文本）
)

/**
 * 动态主题尺寸配置模型
 */
data class TvDimens(
    val spacingXs: Dp,
    val spacingSm: Dp,
    val spacingMd: Dp,
    val spacingLg: Dp,
    val spacingXl: Dp,
    val spacing2Xl: Dp,
    val spacing3Xl: Dp,
    val paddingBtnVertical: Dp,
    val paddingBtnHorizontal: Dp,
    val borderNormal: Dp,
    val borderFocused: Dp,
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp,
    val radiusBtn: Dp,
    val iconSizeSm: Dp,
    val iconSizeMd: Dp,
    val iconSizeLg: Dp,
    val iconSizeXl: Dp,
    val appIconSize: Dp,
    val checkIconSize: Dp,
    val topBarHeight: Dp,
    val listItemHeight: Dp,
    val fontTitle: TextUnit,
    val fontBody: TextUnit,
    val fontCaption: TextUnit,
    val fontSmall: TextUnit,
    val fontCol1Text: TextUnit,
    val fontCol2Title: TextUnit,
    val fontCol2Desc: TextUnit,
    val fontCol3Text: TextUnit,
    val fontCol4Title: TextUnit,
    val fontCol4Desc: TextUnit,
    val lineHeightBody: TextUnit,
    // ── 组件语义尺寸 ──
    // 按"这是什么部件"命名，禁止在调用处用 spacing/icon 系列做加减乘除凑近似值：
    // 算术表达式既读不出真实像素，也让全局调参失去单一改动点。
    val cardHeightSm: Dp,
    val gridTileHeight: Dp,
    val moreGridTileHeight: Dp,
    val gridTileIconSize: Dp,
    val actionPanelWidth: Dp,
    val contentPanelHeight: Dp,
    val dialogWidthMd: Dp,
    val dialogWidthLg: Dp,
    val dialogIconSize: Dp,
    val buttonHeightLg: Dp,
    val menuItemHeight: Dp,
    val inputFieldHeight: Dp,
    val progressIndicatorSize: Dp,
    val sideMenuWidth: Dp,
    val dialogBlurRadius: Int,
    val dialogDimAmount: Float,
    // ── 滑动开关（Switch）语义尺寸 ──
    val switchWidth: Dp,
    val switchHeight: Dp,
    val switchThumbSize: Dp,
    val switchThumbPadding: Dp
)

/**
 * 动态主题字体排版配置模型
 */
data class TvTypography(
    val title: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val small: TextStyle,
    val col1Text: TextStyle,
    val col2Title: TextStyle,
    val col2Desc: TextStyle,
    val col3Text: TextStyle,
    val col4Title: TextStyle,
    val col4Desc: TextStyle
)

val LocalTvColors = staticCompositionLocalOf<TvColors> {
    error("No TvColors provided")
}

val LocalTvDimens = staticCompositionLocalOf<TvDimens> {
    error("No TvDimens provided")
}

val LocalTvTypography = staticCompositionLocalOf<TvTypography> {
    error("No TvTypography provided")
}

/**
 * 全局主题访问点，替换硬编码颜色的关键
 */
object TvTheme {
    val colors: TvColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTvColors.current

    val dimens: TvDimens
        @Composable
        @ReadOnlyComposable
        get() = LocalTvDimens.current

    val typography: TvTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalTvTypography.current

    /**
     * 窗口宽度断点（dp）——横竖屏/多形态适配的统一配置，单一改动点。
     * 取值参照 Material 3 官方 adaptive breakpoints：
     *   < windowBreakpointCompactDp（600）：竖屏手机
     *   < windowBreakpointExpandedDp（840）：竖屏平板 / 小窗口 / 分屏
     *   ≥ windowBreakpointExpandedDp（840）：横屏手机、横屏平板、桌面级大屏
     * TV 设备不受断点限制，恒按横屏宽屏布局处理（720p 低端盒子仅约 640dp 宽）。
     * 调值只改此处，布局判断逻辑见 uiconfig/global/WindowSizeClass.kt。
     */
    val windowBreakpointCompactDp: Int = 600
    val windowBreakpointExpandedDp: Int = 840

    /**
     * 首页宽屏网格各列的「内容最小宽度」（dp），2026-10-29 补登。
     *
     * 这是**整套适配的核心参数**：它不是从任何设备分辨率反推的，而是由「磁贴里放了
     * 什么」直接算出——每列的最小宽度 = 该列磁贴的固定开销（左右内边距 + 图标 +
     * 图文间距）+ 该列最长的标题/副标题文字宽度。
     *
     * 各列计算依据（字号/间距均取自 TvTheme 同一处定义）：
     * - 第1列 SmallTile：内边距 16×2 + 图标 32 + 间距 16 = 80；最长标题「快速传输」
     *   4 字 × 18sp = 72 → 152
     * - 第2列 LargeTile：内边距 24×2 + 图标 48 + 间距 16 = 112；最长副标题
     *   「99 个安装包」≈ 88 → 200
     * - 第3列 DeviceListTile：外层内边距 24×2 + 设备行内边距 24×2 + 图标 24 +
     *   图文间距 8 = 128；最长设备名「内部存储」4 字 × 18sp = 72 → 200，
     *   再留 8dp 冗余 = 208（设备名来自运行时、可能长于「内部存储」，故多留一手）
     * - 第4列 MediumTile：内边距 16×2 + 图标 32 + 间距 16 = 80；最长副标题
     *   「99 张图片」≈ 70 → 150
     *
     * 【为什么它是通用的】只要文案长度、字号、图标规格不变，无论设备分辨率怎么变，
     * 每列需要的宽度都不变；反过来，改文案/改字号时只需改这一个值，布局自动跟上。
     * 这是「按内容驱动分配」的前提，替代了原先按具体设备宽度打补丁的做法。
     */
    val homeGridColumn1MinWidthDp: Int = 152
    val homeGridColumn2MinWidthDp: Int = 200
    val homeGridColumn3MinWidthDp: Int = 208
    val homeGridColumn4MinWidthDp: Int = 150

    /**
     * 窗口高度断点（dp）——官方双维度判定的第二个维度，2026-10-28 补登。
     *
     * 官方说明：宽度类与高度类各自独立分档，且宽度类通常更重要（多数布局纵向滚动）；
     * 但当「宽度够、纵向空间不足」时，双窗格布局并不实用，此时高度类决定窗格数。
     * 取值同样参照 Material 3 官方 breakpoints（compact ≤480 / medium 481–899 / expanded ≥900）。
     *
     * 典型场景：手机横屏（宽度 EXPANDED 但高度仅约 480dp），
     * 与平板竖屏（宽度 MEDIUM、高度 EXPANDED）虽都是"够宽"，但纵向空间天差地别，
     * 布局选择必须区分——这正是官方反对"只看 orientation"的原因。
     */
    val windowHeightBreakpointCompactDp: Int = 480
    val windowHeightBreakpointExpandedDp: Int = 900

    /**
     * 首页宽屏网格（4 组并排 weight 布局）所需的最小窗口宽度（dp），2026-10-28 补登。
     *
     * 由**内容实际需求**反推，不是拍脑袋的分辨率常量：
     * 4 组并排时最窄的一组只占 316/1598 权重，该组需 ≥ 152dp 才能让磁贴标题单行放下
     * （「快速传输」四字 18sp 约 72dp + 图标 32dp + 间距与内边距 48dp），
     * 反推窗口宽需 ≥ 152 + 152×1598/316 ≈ 920dp。
     *
     * 低于此宽度时改走双列竖屏网格（每列更宽，标题必然单行）。
     * 该值与分辨率无关——换任何分辨率/密度的设备，判定结论都一致。
     */
    val windowWideGridMinWidthDp: Int = 920

    /**
     * UI 等比缩放基座宽度（dp），2026-10-28 补登。
     *
     * MainActivity 按此基座整体缩放 Compose 渲染密度：
     * scale = min(smallestScreenWidthDp / uiDensityBaseWidthDp, 1.0)。
     *
     * 实测各形态的 scale（2026-10-29 审计时按真实设备参数核算修正，
     * 此前注释称「手机 scale≈1.0 不缩放」与实际相反，已纠正）：
     * - 手机短边典型 360~411dp → scale ≈ 0.75~0.86：
     *   即**手机上 UI 被等比缩小**，把屏幕换算拉宽到等效 480dp 宽再渲染，
     *   这是刻意做的「窄屏也能完整放下 4 组内容」的补偿，不是不缩放。
     * - 平板 / TV 短边 ≥600dp → scale = 1.0，不缩放，按原始 dp 渲染。
     *
     * 登记在此处的目的：窗口自适应判定需要按**同一公式**反算 Compose 实际可用布局尺寸
     * （见 WindowSizeClass.rememberWindowAdaptiveInfo），避免两处各写一份常量而漂移。
     */
    const val uiDensityBaseWidthDp: Float = 480f
}

// ============================================================
// 默认深色主题实例（第一步：完全复刻当前的硬编码常量作为初始值）
// ============================================================
val defaultTvColors = TvColors(
    // 背景渐变两端色：整屏从上到下只有这么一点点色差（每通道约 13/255）。
    // 这是刻意压过的——原先两端相差 51 级（约两成亮度），摊满全屏后被 8bit 量化切成
    // 一层层约 14px 的色阶，电视面板上中间那道接缝看得出来，像是屏幕坏了。
    // 改这两个色时务必保持"两端差 ≤ 约 15 级"，不要拉大。
    bgGradientTop = Color(0xFF0046AC),
    bgGradientBottom = Color(0xFF0053B9),
    brandPrimary = Color(0xFF3B82F6),
    brandSuccess = Color(0xFF10B981),
    brandWarning = Color(0xFFF97316),
    brandError = Color(0xFFEF4444),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xB3FFFFFF),
    textTertiary = Color(0x80FFFFFF),
    textOnTile = Color(0xFFFFFFFF),
    textOnTileDim = Color(0xCCFFFFFF),
    surface = Color(0x1AFFFFFF),
    surfaceHover = Color(0x33FFFFFF),
    surfaceVariant = Color(0x14FFFFFF),
    borderNormal = Color.Transparent,
    borderFocused = Color.White,
    focusBorder = Color(0xFFFFFFFF),
    tileColor1 = Color(0xFF3B82F6),
    tileColor2A = Color(0xFF10B981),
    tileColor2B = Color(0xFFEC4899),
    tileColor3 = Color(0xFF8B5CF6),
    tileColor4A = Color(0xFFF97316),
    tileColor4B = Color(0xFFF59E0B),
    tileColor4C = Color(0xFF06B6D4),
    selectCircleBg = Color(0x33FFFFFF),
    iconFolder = Color(0xFFFFB300), // 经典的文件夹黄色
    dialogBg = Color(0xA62A2A2A), // 65%不透明深灰色 (A6 = 166 ≈ 255 * 0.65)，增加透明度让毛玻璃更明显
    dialogScrim = Color(0x80000000), // 弹窗背后的全屏遮罩颜色（半透明黑）
    surfaceLight = Color(0xFFFFFFFF), // 二维码必须白底才能被摄像头稳定识别，故不随主题变化
    textOnSurfaceLight = Color(0xFF000000),
    // 全屏看图/看文本的底色必须是中性纯黑：图片本身有自己的色彩，底板若带主题色偏
    // （蓝/绿/紫底）会整体染色，看照片时会误以为是文件颜色不对。四套主题统一取纯黑。
    mediaBackdrop = Color(0xFF000000)
)

val defaultTvDimens = TvDimens(
    spacingXs = 4.dp,
    spacingSm = 8.dp,
    spacingMd = 16.dp,
    spacingLg = 24.dp,
    spacingXl = 32.dp,
    spacing2Xl = 48.dp,
    spacing3Xl = 64.dp,
    paddingBtnVertical = 10.dp,
    paddingBtnHorizontal = 24.dp,
    borderNormal = 1.dp,
    borderFocused = 2.dp,
    radiusSm = 4.dp,
    radiusMd = 8.dp,
    radiusLg = 12.dp,
    radiusBtn = 20.dp,
    iconSizeSm = 16.dp,
    iconSizeMd = 24.dp,
    iconSizeLg = 32.dp,
    iconSizeXl = 48.dp,
    appIconSize = 120.dp,
    checkIconSize = 20.dp,
    topBarHeight = 72.dp,
    listItemHeight = 72.dp,
    fontTitle = 24.sp,
    fontBody = 18.sp,
    fontCaption = 16.sp,
    fontSmall = 14.sp,
    fontCol1Text = 18.sp,
    fontCol2Title = 20.sp,
    fontCol2Desc = 14.sp,
    fontCol3Text = 18.sp,
    fontCol4Title = 18.sp,
    fontCol4Desc = 14.sp,
    lineHeightBody = 24.sp,
    cardHeightSm = 80.dp,
    gridTileHeight = 140.dp,
    moreGridTileHeight = 160.dp,
    gridTileIconSize = 56.dp,
    actionPanelWidth = 200.dp,
    contentPanelHeight = 450.dp,
    dialogWidthMd = 400.dp,
    dialogWidthLg = 480.dp,
    dialogIconSize = 64.dp,
    buttonHeightLg = 56.dp,
    menuItemHeight = 52.dp,
    inputFieldHeight = 52.dp,
    progressIndicatorSize = 36.dp,
    sideMenuWidth = 240.dp,
    dialogBlurRadius = 40,
    dialogDimAmount = 0.8f,
    switchWidth = 48.dp,
    switchHeight = 28.dp,
    switchThumbSize = 22.dp,
    switchThumbPadding = 3.dp
)

val defaultTvTypography = TvTypography(
    title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = defaultTvDimens.fontTitle
    ),
    body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = defaultTvDimens.fontBody
    ),
    caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = defaultTvDimens.fontCaption
    ),
    small = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = defaultTvDimens.fontSmall
    ),
    col1Text = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = defaultTvDimens.fontCol1Text
    ),
    col2Title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = defaultTvDimens.fontCol2Title
    ),
    col2Desc = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = defaultTvDimens.fontCol2Desc
    ),
    col3Text = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = defaultTvDimens.fontCol3Text
    ),
    col4Title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = defaultTvDimens.fontCol4Title
    ),
    col4Desc = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = defaultTvDimens.fontCol4Desc
    )
)

val blueTvColors = defaultTvColors

val greenTvColors = defaultTvColors.copy(
    bgGradientTop = Color(0xFF004613),
    bgGradientBottom = Color(0xFF005320),
    brandPrimary = Color(0xFF10B981),
    tileColor1 = Color(0xFF10B981),
    tileColor2A = Color(0xFF059669),
    tileColor2B = Color(0xFF34D399),
    tileColor3 = Color(0xFF047857),
    tileColor4A = Color(0xFF6EE7B7),
    tileColor4B = Color(0xFF10B981),
    tileColor4C = Color(0xFF059669)
)

val purpleTvColors = defaultTvColors.copy(
    bgGradientTop = Color(0xFF380062),
    bgGradientBottom = Color(0xFF3F006E),
    brandPrimary = Color(0xFF8B5CF6),
    tileColor1 = Color(0xFF8B5CF6),
    tileColor2A = Color(0xFF7C3AED),
    tileColor2B = Color(0xFFA78BFA),
    tileColor3 = Color(0xFF6D28D9),
    tileColor4A = Color(0xFFC4B5FD),
    tileColor4B = Color(0xFF8B5CF6),
    tileColor4C = Color(0xFF7C3AED)
)

val orangeTvColors = defaultTvColors.copy(
    bgGradientTop = Color(0xFF6A2400),
    bgGradientBottom = Color(0xFF7D2A00),
    brandPrimary = Color(0xFFF97316),
    tileColor1 = Color(0xFFF97316),
    tileColor2A = Color(0xFFEA580C),
    tileColor2B = Color(0xFFFB923C),
    tileColor3 = Color(0xFFC2410C),
    tileColor4A = Color(0xFFFDBA74),
    tileColor4B = Color(0xFFF97316),
    tileColor4C = Color(0xFFEA580C)
)

/**
 * 统一的弹窗模糊/变暗背景处理逻辑
 * @param blurRadius 模糊半径
 * @param dimAmount 不支持模糊时的降级变暗透明度
 */
fun Window.applyTvDialogBlurEffect(
    blurRadius: Int = defaultTvDimens.dialogBlurRadius,
    dimAmount: Float = defaultTvDimens.dialogDimAmount
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        attributes.blurBehindRadius = blurRadius
        attributes = attributes
    } else {
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes.dimAmount = dimAmount
        attributes = attributes
    }
}
