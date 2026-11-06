package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.tvflyshare.app.network.NetworkType
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.uiconfig.global.TvTheme

import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R

/**
 * 子页面通用顶栏 — 深蓝背景适配，白色文字
 */
@Composable
fun TopBar(
    title: String,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    deviceInfo: String? = null,
    timeText: String? = null,
    rightAction: @Composable (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 2026-10-04 审计 P2 修复：网络类型图标原先只读一次，页面停留期间 WiFi/网线切换或断网不更新——
    // 注册 ConnectivityManager 回调驱动刷新（页面销毁时注销）
    var networkRefreshTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(context) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networkRefreshTick++ }
            override fun onLost(network: android.net.Network) { networkRefreshTick++ }
            // 2026-11-03 修复：仅 onAvailable/onLost 会在 WiFi 刚连上、activeNetwork 的
            // capabilities 尚未就绪时读到旧的 NONE，且此后无回调再刷新 → 图标卡在"无网"。
            // 补 capabilities/linkProperties 变化回调，等连接真正就绪后重新查询网络类型。
            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) { networkRefreshTick++ }
            override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: android.net.LinkProperties) { networkRefreshTick++ }
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            cm?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // 注册失败（极端 ROM）退化为静态图标
        }
        onDispose {
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // 注销失败（回调未注册成功/极端 ROM）：仅记录，不影响页面销毁
                android.util.Log.w("TopBar", "unregisterNetworkCallback failed", e)
            }
        }
    }
    val networkType = remember(networkRefreshTick) { NetworkUtils.getNetworkType(context) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.topBarHeight)
            .padding(horizontal = TvTheme.dimens.spacingXl),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            if (showBack) {
                // 2026-10-04 审计 P2 修复：返回箭头可聚焦但原先零焦点视觉（无法分辨焦点是否在返回键上）——
                // 挂 tvFocusStyle 统一焦点样式
                val backInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(id = R.string.back),
                    tint = TvTheme.colors.textPrimary,
                    modifier = Modifier
                        .size(TvTheme.dimens.iconSizeMd)
                        .tvFocusStyle(
                            interactionSource = backInteraction,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(TvTheme.dimens.radiusSm),
                            normalBgColor = TvTheme.colors.surface,
                            focusedBgColor = TvTheme.colors.brandPrimary,
                            borderWidth = TvTheme.dimens.borderNormal
                        )
                        .clickable(
                            interactionSource = backInteraction,
                            indication = null
                        ) { onBack() }
                )
            }
            Text(
                text = title,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Medium,
            )
        }

        if (deviceInfo != null) {
            Text(
                text = deviceInfo,
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontCaption,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            if (rightAction != null) {
                rightAction()
            }
            if (timeText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
                ) {
                    Icon(
                        imageVector = when (networkType) {
                            NetworkType.WIFI -> Icons.Default.Wifi
                            NetworkType.ETHERNET -> Icons.Default.SettingsEthernet
                            NetworkType.CELLULAR -> Icons.Default.CellTower
                            NetworkType.NONE -> Icons.Default.WifiOff
                        },
                        contentDescription = when (networkType) {
                            NetworkType.WIFI -> "WiFi"
                            NetworkType.ETHERNET -> stringResource(id = R.string.transfer_network_ethernet)
                            NetworkType.CELLULAR -> stringResource(id = R.string.transfer_network_cellular)
                            NetworkType.NONE -> stringResource(id = R.string.transfer_network_none)
                        },
                        tint = TvTheme.colors.textPrimary,
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
    }
}
