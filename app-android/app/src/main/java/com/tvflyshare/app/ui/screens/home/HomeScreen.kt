package com.tvflyshare.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.network.NetworkType
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.ui.viewmodel.HomeUiEvent
import com.tvflyshare.app.ui.viewmodel.HomeUiState
import com.tvflyshare.app.ui.viewmodel.HomeViewModel
import com.tvflyshare.app.ui.viewmodel.DeviceInfo
import com.tvflyshare.app.ui.navigation.Screen
import com.tvflyshare.app.Constants

/** 首页双击退出：两次返回键的最大间隔（毫秒），超出则视为两次独立操作，重新提示 */
private const val EXIT_DOUBLE_PRESS_INTERVAL_MS = 2000L

// ============================================================
// HomeScreen — 横竖屏自适应布局
//
// 宽屏（TV / 手机横屏 / 平板横屏）：4 列 weight 比例网格（HomeWideGrid）
// 竖屏（手机 / 平板 / 车机竖屏）：双列网格（HomePortraitGrid）
// 切换依据：WindowAdaptiveInfo.isWideLayout（窗口宽度档位，见 uiconfig/global/WindowSizeClass.kt）
//
// 数据源：HomeViewModel（StateFlow）
// 后续迭代替换 ViewModel 内扫描逻辑即可，UI 组件不用动
// ============================================================

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 每次回到主页（ON_RESUME）时重新扫描文件数量
    // 解决：从文件管理页删除文件返回后，主页数量不更新的问题
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 首页双击返回退出：首次按返回仅弹出提示，2 秒内再次按返回才真正退出应用，避免误触退出。
    val context = LocalContext.current
    var lastBackPressedAt by remember { mutableStateOf(0L) }
    BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressedAt < EXIT_DOUBLE_PRESS_INTERVAL_MS) {
            // 第二次按下：终止 Activity，退出应用
            (context as? android.app.Activity)?.finish()
        } else {
            // 第一次按下：记录时间并提示，不退出
            lastBackPressedAt = now
            GlobalToastManager.showToast(context.getString(R.string.home_exit_hint))
        }
    }

    // 横竖屏自适应：TV/宽屏（横屏手机、横屏平板）走 4 列网格；竖屏（手机、平板、车机）走双列。
    // 依据窗口宽度档位而非屏幕方向：旋转/分屏时窗口宽度类自然变化，布局随之切换。
    val windowInfo = rememberWindowAdaptiveInfo()

    TVBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        // 竖屏（手机/车机/平板）收窄边距：横屏 64dp 是 TV 规格，
                        // 搬到 480dp 等效宽的竖屏手机上左右各空出 27% 屏宽，过于空旷
                        horizontal = if (windowInfo.isWideLayout) TvTheme.dimens.spacing3Xl else TvTheme.dimens.spacingXl
                    )
                    .padding(
                        top = if (windowInfo.isWideLayout) TvTheme.dimens.spacing2Xl else TvTheme.dimens.spacingXl,
                        bottom = if (windowInfo.isWideLayout) TvTheme.dimens.spacing2Xl else TvTheme.dimens.spacingXl
                    )
            ) {
                // ── 顶部栏 ──
                HomeHeader(
                    timeText = uiState.timeText,
                )

                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))

                // ── 磁贴区：宽屏 4 列 / 竖屏双列 ──
                if (windowInfo.isWideLayout) {
                    HomeWideGrid(
                        uiState = uiState,
                        onNavigate = onNavigate,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // 竖屏下右下角有版本号（绝对定位、贴屏幕底边），给磁贴区底部预留其高度+间距，
                    // 让最底行（视频/更多）上移、磁贴按比例变矮，避免与版本号重叠。电视/横屏不受影响。
                    HomePortraitGrid(
                        uiState = uiState,
                        onNavigate = onNavigate,
                        modifier = Modifier.weight(1f).padding(bottom = TvTheme.dimens.spacingXl),
                    )
                }
            }

            // 版本号 — 右下角
            Text(
                text = uiState.version,
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = TvTheme.dimens.spacingMd, bottom = TvTheme.dimens.spacingMd)
            )
        }
    }
}

// ============================================================
// 磁贴区 — 宽屏 4 列网格（TV / 手机横屏 / 平板横屏）
//
// 【列宽按「内容需要多宽」分配，而不是按设备宽度打补丁】（2026-10-29）
// 每列的最小宽度由该列磁贴的实际内容算出（内边距 + 图标 + 最长文案宽度，
// 四个值登记在 TvTheme），这四个值就是整套适配的唯一驱动参数：
//   - 文案/字号不变 → 换任何分辨率的设备，每列需要的宽度都不变，无需重新调整
//   - 改文案/字号  → 只改 TvTheme 里那一个值，布局自动跟上
//
// 分配分三档，全程不含任何针对具体设备的写死数值：
//   ① 够宽 → 沿用设计稿权重比例（TV / 平板横屏外观零变化）
//   ② 略窄 → 先给每列它需要的宽度，剩余再按权重分（四列内容均完整显示）
//   ③ 极窄 → 按各列需求等比压缩（缺口均匀分摊，不出现单列几乎不可显示）
//
// 这是 CSS Grid 的 repeat(auto-fit, minmax(min, 1fr)) 与 Compose
// GridCells.Adaptive 的共同思路在「固定 4 组、各组内容规格不同」场景下的等价实现。
// ============================================================

@Composable
private fun HomeWideGrid(
    uiState: HomeUiState,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 4 列的设计稿权重（宽设备下严格按此比例，保证外观与设计一致）
        val weights = floatArrayOf(316f, 416f, 466f, 400f)

        // 各列「内容所需的最小宽度」——由磁贴里放了什么算出（计算依据见 TvTheme 登记处），
        // 与任何具体设备分辨率都无关，是本套适配的唯一驱动参数。
        val needs = floatArrayOf(
            TvTheme.homeGridColumn1MinWidthDp.toFloat(),
            TvTheme.homeGridColumn2MinWidthDp.toFloat(),
            TvTheme.homeGridColumn3MinWidthDp.toFloat(),
            TvTheme.homeGridColumn4MinWidthDp.toFloat()
        )

        val totalGap = TvTheme.dimens.spacingSm * (weights.size - 1)
        // 全程用 Float 运算，最后统一转 Dp（maxWidth 为 Dp，需先取 .value）。
        // coerceAtLeast(0f)：极端情况下（如多窗口被拖到极窄、maxWidth 还不到列间距总和）
        // available 会算成负值，直接交给 Modifier.width(负dp) 会抛异常崩溃；
        // 兜到 0 后四列均为 0 宽，由各磁贴自身的 Ellipsis 收尾，不会崩。
        val available = (maxWidth.value - totalGap.value).coerceAtLeast(0f)
        val weightSum = weights.sum()
        val needSum = needs.sum()

        // 先按设计稿权重分配一份，用来判断「宽度够不够」
        val byWeight = FloatArray(weights.size) { available * weights[it] / weightSum }

        val colWidths = if (byWeight.indices.all { byWeight[it] >= needs[it] }) {
            // ① 足够宽：直接沿用设计稿权重比例 —— TV / 平板横屏外观零变化
            byWeight
        } else if (available >= needSum) {
            // ② 略窄：先给每列各自「放得下内容」的宽度，剩下的再按权重比例分。
            //    结果是四列都能完整显示自己的内容，同时仍保持设计稿的相对比例。
            val surplus = available - needSum
            FloatArray(weights.size) { needs[it] + surplus * weights[it] / weightSum }
        } else {
            // ③ 极窄：按各列内容需求等比压缩。
            //    早先只保住最窄的第 1 列、其余列被挤到几乎不可显示（第 2 列曾缺 61dp）；
            //    等比压缩把缺口均匀分摊，避免出现某一列文字几乎全被截掉的极端。
            FloatArray(weights.size) { available * needs[it] / needSum }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
        ) {
            // 第1列：5个小功能磁贴
            Column(
                modifier = Modifier.width(colWidths[0].dp),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
            ) {
                SmallTile(stringResource(R.string.home_quick_transfer), Icons.Default.SwapHoriz, TvTheme.colors.tileColor1, Modifier.weight(1f)) { onNavigate("transfer") }
                SmallTile(stringResource(R.string.home_lan), Icons.Default.DevicesOther, TvTheme.colors.tileColor1, Modifier.weight(1f)) { onNavigate("network") }
                SmallTile(stringResource(R.string.home_app_uninstall), Icons.Default.Android, TvTheme.colors.tileColor1, Modifier.weight(1f)) { onNavigate("app_uninstall") }
                SmallTile(stringResource(R.string.home_app_settings), Icons.Default.Settings, TvTheme.colors.tileColor1, Modifier.weight(1f)) { onNavigate("settings") }
                SmallTile(stringResource(R.string.home_check_update), Icons.Default.SystemUpdate, TvTheme.colors.tileColor1, Modifier.weight(1f)) { onNavigate("update") }
            }

            // 第2列：2个大磁贴
            Column(
                modifier = Modifier.width(colWidths[1].dp),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
            ) {
                LargeTile(stringResource(R.string.home_apk), if (uiState.apkCount >= 0) stringResource(R.string.home_apk_count, uiState.apkCount) else stringResource(R.string.home_scanning), Icons.Default.Android, TvTheme.colors.tileColor2A, Modifier.weight(1f)) { onNavigate(Screen.FileManager.createRoute("apk")) }
                LargeTile(stringResource(R.string.home_video), if (uiState.videoCount >= 0) stringResource(R.string.home_video_count, uiState.videoCount) else stringResource(R.string.home_scanning), Icons.Default.Theaters, TvTheme.colors.tileColor2B, Modifier.weight(1f)) { onNavigate(Screen.FileManager.createRoute("video")) }
            }

            // 第3列：1个设备列表磁贴
            DeviceListTile(
                devices = uiState.devices,
                weightModifier = Modifier.width(colWidths[2].dp),
                onDeviceClick = { path -> onNavigate(Screen.FileManager.createRoute("all", path)) },
                onAllFilesClick = { onNavigate(Screen.FileManager.createRoute("all")) },
            )

            // 第4列：3个磁贴
            Column(
                modifier = Modifier.width(colWidths[3].dp),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
            ) {
                MediumTile(stringResource(R.string.home_photo), if (uiState.photoCount >= 0) stringResource(R.string.home_photo_count, uiState.photoCount) else stringResource(R.string.home_scanning), Icons.Default.Photo, TvTheme.colors.tileColor4A, Modifier.weight(1f)) { onNavigate(Screen.FileManager.createRoute("image")) }
                MediumTile(stringResource(R.string.home_music), if (uiState.musicCount >= 0) stringResource(R.string.home_music_count, uiState.musicCount) else stringResource(R.string.home_scanning), Icons.Default.MusicNote, TvTheme.colors.tileColor4B, Modifier.weight(1f)) { onNavigate(Screen.FileManager.createRoute("audio")) }
                MoreTile(TvTheme.colors.tileColor4C, Modifier.weight(1f)) { onNavigate(Screen.More.route) }
            }
        }
    }
}

// ============================================================
// 磁贴区 — 竖屏双列网格（手机竖屏 / 平板竖屏 / 车机竖屏）
//
// 双列宽度比例沿用宽屏 4 列的列组权重：左列 = 第1列+第2列（316+416=732），
// 右列 = 第3列+第4列（466+400=866），视觉密度与横屏一致。
// 左列：4 个小功能磁贴 + 安装包 + 视频 + 检测更新（4 小 + 3 中；检测更新按用户要求置于末行，中号高度与右下"更多"等高）
// 右列：设备列表（占一半高度，保持"主卡片"地位）+ 图片(中号) + 音乐 + 更多（3 中号与左列末三行等高）
// ============================================================

@Composable
private fun HomePortraitGrid(
    uiState: HomeUiState,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = TvTheme.dimens.spacingSm

    // 竖屏双列共用同一套行网格，左右磁贴边界才能逐行对齐：
    // 左列 = 4 小号行（功能磁贴）+ 3 中号行（安装包/视频/检测更新，1.4 倍）；
    // 右列 = 设备列表 4 小号行 + 图片/音乐/更多 3 中号行。
    // 8.2 = 4×1.0 + 3×1.4，两列同高（8.2 行）故底边对齐且逐行对齐。
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val smallRow = (maxHeight - gap * 6) / 8.2f
        val mediumRow = smallRow * 1.4f

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            // 左列（698）：4 个小功能磁贴 + 安装包 + 视频 + 检测更新（末行为中号，与右下"更多"等高）
            Column(
                modifier = Modifier.weight(698f),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                SmallTile(stringResource(R.string.home_quick_transfer), Icons.Default.SwapHoriz, TvTheme.colors.tileColor1, Modifier.height(smallRow)) { onNavigate("transfer") }
                SmallTile(stringResource(R.string.home_lan), Icons.Default.DevicesOther, TvTheme.colors.tileColor1, Modifier.height(smallRow)) { onNavigate("network") }
                SmallTile(stringResource(R.string.home_app_uninstall), Icons.Default.Android, TvTheme.colors.tileColor1, Modifier.height(smallRow)) { onNavigate("app_uninstall") }
                SmallTile(stringResource(R.string.home_app_settings), Icons.Default.Settings, TvTheme.colors.tileColor1, Modifier.height(smallRow)) { onNavigate("settings") }
                // 分类磁贴占中号行（1.4 倍）：中磁贴规格（18sp 标题+14sp 数量两行+16dp 内边距≈75dp）
                // 在小号行（≈68dp）放不下会裁副标题，加权到约 95dp 完整显示
                MediumTile(stringResource(R.string.home_apk), if (uiState.apkCount >= 0) stringResource(R.string.home_apk_count, uiState.apkCount) else stringResource(R.string.home_scanning), Icons.Default.Android, TvTheme.colors.tileColor2A, Modifier.height(mediumRow)) { onNavigate(Screen.FileManager.createRoute("apk")) }
                MediumTile(stringResource(R.string.home_video), if (uiState.videoCount >= 0) stringResource(R.string.home_video_count, uiState.videoCount) else stringResource(R.string.home_scanning), Icons.Default.Theaters, TvTheme.colors.tileColor2B, Modifier.height(mediumRow)) { onNavigate(Screen.FileManager.createRoute("video")) }
                // 检测更新：按用户要求置于左列最末行；改为中号高度，与右下"更多"等高，两列底边对齐
                SmallTile(stringResource(R.string.home_check_update), Icons.Default.SystemUpdate, TvTheme.colors.tileColor1, Modifier.height(mediumRow)) { onNavigate("update") }
            }

            // 右列（900）：设备列表 + 图片(中号) + 音乐 + 更多（与左列末三行等高对齐）
            // 右列比左列宽：设备卡片底部"全部文件"行需要文字 80 + 图标 + 间距 + 内边距约 116dp，
            // 竖屏手机上右列约 180dp 才放得下横版四字（原 866 比例仅约 156dp 会被省略号截断）
            Column(
                modifier = Modifier.weight(900f),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                // 设备列表占 4 个小号行（4 行高 + 3 条行间缝）：底边与左列"应用设置"底边对齐，
                // 维持横屏中的主卡片地位；下方图片/音乐/更多依次与安装包/视频/检测更新同行（均为中号，逐行对齐）
                DeviceListTile(
                    devices = uiState.devices,
                    weightModifier = Modifier.height(smallRow * 4 + gap * 3),
                    onDeviceClick = { path -> onNavigate(Screen.FileManager.createRoute("all", path)) },
                    onAllFilesClick = { onNavigate(Screen.FileManager.createRoute("all")) },
                    // 竖屏触屏：底部「全部文件」可点，点它就是进文件管理器
                    allFilesRowClickable = true,
                )
                // 图片升为中号高度（原被压成小号）：使右列 = 4 小 + 3 中 = 8.2 行，与左列同高、底边对齐
                MediumTile(stringResource(R.string.home_photo), if (uiState.photoCount >= 0) stringResource(R.string.home_photo_count, uiState.photoCount) else stringResource(R.string.home_scanning), Icons.Default.Photo, TvTheme.colors.tileColor4A, Modifier.height(mediumRow)) { onNavigate(Screen.FileManager.createRoute("image")) }
                MediumTile(stringResource(R.string.home_music), if (uiState.musicCount >= 0) stringResource(R.string.home_music_count, uiState.musicCount) else stringResource(R.string.home_scanning), Icons.Default.MusicNote, TvTheme.colors.tileColor4B, Modifier.height(mediumRow)) { onNavigate(Screen.FileManager.createRoute("audio")) }
                MoreTile(TvTheme.colors.tileColor4C, Modifier.height(mediumRow)) { onNavigate(Screen.More.route) }
            }
        }
    }
}

// ============================================================
// 顶部栏
// ============================================================

@Composable
private fun HomeHeader(timeText: String) {
    val context = LocalContext.current
    
    // 监听网络状态变化
    var networkType by remember { mutableStateOf(NetworkUtils.getNetworkType(context)) }

    // TODO(Kaky, M7): 网络连通性监听直连 android.net（ConnectivityManager/NetworkCallback），
    // 应下沉为 connectivity Repository/StateFlow 观察者，Composable 仅订阅状态，隔离平台 API。
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络连接时，重新获取类型
                networkType = NetworkUtils.getNetworkType(context)
            }

            override fun onLost(network: Network) {
                // 网络断开时，重新获取类型（可能有其他网络依然可用，所以不是直接设为 NONE）
                networkType = NetworkUtils.getNetworkType(context)
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                 networkType = NetworkUtils.getNetworkType(context)
            }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
             connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } else {
             val request = NetworkRequest.Builder().build()
             connectivityManager?.registerNetworkCallback(request, networkCallback)
        }

        onDispose {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：图标 + 标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            Surface(
                modifier = Modifier.size(TvTheme.dimens.iconSizeLg),
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                color = TvTheme.colors.borderNormal
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_tv_transfer_logo),
                        contentDescription = null,
                        tint = Color.Unspecified, // 使用矢量图本身的颜色
                        modifier = Modifier.fillMaxSize() // 撑满 Surface
                    )
                }
            }
            Text(
                text = stringResource(id = R.string.app_name).replace("TV", "TV "),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Medium,
            )
        }

        // 右侧：WiFi + 时间
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
        ) {
            val isDisconnected = networkType == NetworkType.NONE
            Icon(
                imageVector = when (networkType) {
                    NetworkType.WIFI -> Icons.Default.Wifi
                    NetworkType.ETHERNET -> Icons.Default.SettingsEthernet
                    NetworkType.CELLULAR -> Icons.Default.CellTower
                    NetworkType.NONE -> Icons.Default.WifiOff
                },
                contentDescription = when (networkType) {
                    NetworkType.WIFI -> "WiFi"
                    NetworkType.ETHERNET -> stringResource(R.string.transfer_network_ethernet)
                    NetworkType.CELLULAR -> stringResource(R.string.transfer_network_cellular)
                    NetworkType.NONE -> stringResource(R.string.transfer_network_none)
                },
                tint = if (isDisconnected) TvTheme.colors.brandError else TvTheme.colors.textPrimary,
                modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
            )
            Text(
                text = timeText,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol2Title,
            )
        }
    }
}

// ============================================================
// 磁贴组件 — 参数化，可复用
// ============================================================

/**
 * 小磁贴 — 第1列用，横向布局（图标+文字），weight 均分列高度
 */
@Composable
private fun SmallTile(
    title: String,
    icon: ImageVector,
    color: Color,
    weightModifier: Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = weightModifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalBgColor = color,
                focusedBgColor = color
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = TvTheme.dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = TvTheme.colors.textOnTile,
                modifier = Modifier.size(TvTheme.dimens.iconSizeLg)
            )
            Text(
                text = title,
                color = TvTheme.colors.textOnTile,
                fontSize = TvTheme.dimens.fontCol1Text,
                fontWeight = FontWeight.Medium,
                // 单行兜底：列宽被压缩到标题放不下时优雅截断，
                // 而不是折成两行后被磁贴边界裁掉半截（2026-10-28 平板竖屏真机实证）。
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 大磁贴 — 第2列用，横向布局（大图标+标题+副标题）
 */
@Composable
private fun LargeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    weightModifier: Modifier,
    onClick: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = weightModifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalBgColor = color,
                focusedBgColor = color
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(TvTheme.dimens.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = TvTheme.colors.textOnTile,
                modifier = Modifier.size(TvTheme.dimens.iconSizeXl)
            )
            Column {
                Text(
                    text = title,
                    color = TvTheme.colors.textOnTile,
                    fontSize = TvTheme.dimens.fontCol2Title,
                    fontWeight = FontWeight.Medium,
                    // 单行兜底：列宽不足时优雅截断而非折行被裁（2026-10-28 真机实证）
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
                Text(
                    text = subtitle,
                    color = TvTheme.colors.textOnTileDim,
                    fontSize = TvTheme.dimens.fontCol2Desc,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 超大磁贴 — 第3列用，设备列表
 * 每个设备独立可聚焦点击，带容量条
 */
@Composable
private fun DeviceListTile(
    devices: List<DeviceInfo>,
    weightModifier: Modifier,
    onDeviceClick: (String) -> Unit = {},
    onAllFilesClick: () -> Unit = {},
    // 竖屏（触屏）时把底部「全部文件」行也做成可点；电视保持静态标题
    allFilesRowClickable: Boolean = false,
) {
    Surface(
        modifier = weightModifier
            .fillMaxHeight(),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.tileColor3,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TvTheme.dimens.spacingLg),
        ) {
            // 设备列表（占满剩余空间，且整体垂直居中）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                if (devices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_no_storage),
                        color = TvTheme.colors.textOnTileDim,
                        fontSize = TvTheme.dimens.fontCol2Desc,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    // "内部存储" 固定在第一个
                    val internalDevice = devices.firstOrNull { it.path == Constants.INTERNAL_STORAGE_ROOT }
                    // U盘等外接设备
                    val externalDevices = devices.filter { it.path != Constants.INTERNAL_STORAGE_ROOT }
                    
                    // 为了保证主磁贴点击"全部文件"能够生效，我们将内部存储的点击行为设为 onAllFilesClick，
                    // 而 U盘等其他外接设备，点击后则进入对应 U盘的目录
                    if (internalDevice != null) {
                        DeviceRow(internalDevice, onClick = onAllFilesClick)
                    }
                    externalDevices.forEach { device ->
                        DeviceRow(device, onClick = { onDeviceClick(device.path) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))

            // 底部 "全部文件"：电视上保持静态标题；竖屏(触屏)可点，直接进文件管理器
            AllFilesRow(onClick = if (allFilesRowClickable) onAllFilesClick else null)
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.borderNormal,
                focusedBgColor = TvTheme.colors.textPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingSm),
        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (device.path == Constants.INTERNAL_STORAGE_ROOT) Icons.Default.DevicesOther else Icons.Default.Usb,
                    contentDescription = null,
                    tint = TvTheme.colors.textOnTile,
                    modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
                )
                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                Text(
                    text = device.name,
                    color = TvTheme.colors.textOnTile,
                    fontSize = TvTheme.dimens.fontCol3Text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Text(
                text = device.capacity,
                color = TvTheme.colors.textOnTileDim,
                fontSize = TvTheme.dimens.fontCol2Desc,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        // 容量条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvTheme.dimens.spacingXs)
                .clip(RoundedCornerShape(TvTheme.dimens.borderFocused))
                .background(TvTheme.colors.textOnTileDim.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(device.usedRatio.coerceIn(0f, 1f))
                    .height(TvTheme.dimens.spacingXs)
                    .clip(RoundedCornerShape(TvTheme.dimens.borderFocused))
                    .background(TvTheme.colors.textOnTile)
            )
        }
    }
}

@Composable
private fun AllFilesRow(onClick: (() -> Unit)? = null) {
    // 竖屏（触屏）时整行可点，直接进文件管理器；电视上 onClick 为 null，外观保持原样
    val clickModifier = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable { onClick() }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            // 竖屏右列仅约 156dp：内边距用 spacingMd（16dp）而非 spacingLg，
            // 为"全部文件"横排文字让出空间
            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = TvTheme.colors.textOnTile,
            modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
        )
        Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
        Text(
            text = stringResource(R.string.home_all_files),
            color = TvTheme.colors.textOnTile,
            fontSize = TvTheme.dimens.fontCol2Title, // 使用类似大磁贴标题的字号
            fontWeight = FontWeight.Medium,
            // 竖屏双列下右列较窄：不限制单行时"全部文件"会被换行成竖排（一字一行），
            // 强制单行 + 省略号兜底，保证横版显示
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * 中磁贴 — 第4列用，横向布局（图标+标题+副标题）
 */
@Composable
private fun MediumTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    weightModifier: Modifier,
    onClick: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = weightModifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalBgColor = color,
                focusedBgColor = color
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(TvTheme.dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = TvTheme.colors.textOnTile,
                modifier = Modifier.size(TvTheme.dimens.iconSizeLg)
            )
            Column {
                Text(
                    text = title,
                    color = TvTheme.colors.textOnTile,
                    fontSize = TvTheme.dimens.fontCol4Title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
                Text(
                    text = subtitle,
                    color = TvTheme.colors.textOnTileDim,
                    fontSize = TvTheme.dimens.fontCol4Desc,
                    // 竖屏窄列兜底：副标题（如"7 张图片"）单行+省略号，防止换行撑高磁贴
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * "更多"磁贴 — 第4列底部
 */
@Composable
private fun MoreTile(color: Color, weightModifier: Modifier, onClick: () -> Unit = {}) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = weightModifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                focusedScale = 1.03f,
                normalBgColor = color,
                focusedBgColor = color
            ),
        shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
        color = TvTheme.colors.borderNormal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(TvTheme.dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = stringResource(R.string.home_more),
                tint = TvTheme.colors.textOnTile,
                modifier = Modifier.size(TvTheme.dimens.iconSizeLg)
            )
            Text(
                text = stringResource(R.string.home_more),
                color = TvTheme.colors.textOnTile,
                fontSize = TvTheme.dimens.fontCol4Title,
                fontWeight = FontWeight.Medium,
                // 单行兜底：与其它磁贴保持一致（列宽被压缩时优雅截断而非折行被裁）
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
