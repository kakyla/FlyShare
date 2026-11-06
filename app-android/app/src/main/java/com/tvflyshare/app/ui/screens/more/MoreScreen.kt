package com.tvflyshare.app.ui.screens.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ImportantDevices
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.util.SystemIntentUtils
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.border
import com.tvflyshare.app.uiconfig.components.GlobalToastManager

@Composable
fun MoreScreen(
    onBack: () -> Unit,
    onNavigateToDeviceList: () -> Unit
) {
    BackHandler { onBack() }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var memoryInfoText by remember { mutableStateOf(context.getString(R.string.more_memory_fetching)) }

    LaunchedEffect(Unit) {
        while (true) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val totalMB = memoryInfo.totalMem / (1024 * 1024)
            val availMB = memoryInfo.availMem / (1024 * 1024)
            val usedMB = totalMB - availMB
            
            // 精简成 "12G/16G" 单行（≥1G 用整 G 四舍五入，否则用 M），避免换行
            val totalStr = if (totalMB >= 1024) "${(totalMB / 1024f).roundToInt()}G" else "${totalMB}M"
            val usedStr = if (usedMB >= 1024) "${(usedMB / 1024f).roundToInt()}G" else "${usedMB}M"
            
            memoryInfoText = context.getString(R.string.more_memory_info, usedStr, totalStr)
            
            delay(2000) // Update every 2 seconds
        }
    }

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.more_title),
            showBack = true,
            onBack = onBack,
            applyBodyPadding = false,
            scrollable = false,
        ) { windowInfo ->
            val compact = windowInfo.widthClass == WindowWidthClass.COMPACT
            val gridColumns = if (compact) 2 else 4
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = if (compact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing3Xl,
                    vertical = TvTheme.dimens.spacingXl
                ),
                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg)
            ) {
                item {
                    MoreGridItem(
                        title = stringResource(id = R.string.more_memory_title),
                        subtitle = memoryInfoText,
                        icon = androidx.compose.material.icons.Icons.Default.Build,
                        onClick = {}
                    )
                }
                item {
                    MoreGridItem(
                        title = stringResource(id = R.string.more_system_settings),
                        subtitle = null,
                        icon = androidx.compose.material.icons.Icons.Default.Settings,
                        onClick = {
                            if (!SystemIntentUtils.openNativeSettings(context)) {
                                coroutineScope.launch {
                                    GlobalToastManager.showToast(context.getString(R.string.more_system_settings_failed))
                                }
                            }
                        }
                    )
                }
                item {
                    MoreGridItem(
                        title = stringResource(id = R.string.more_developer_options),
                        subtitle = null,
                        icon = androidx.compose.material.icons.Icons.Default.DeveloperMode,
                        onClick = {
                            if (!SystemIntentUtils.openDeveloperOptions(context)) {
                                coroutineScope.launch {
                                    GlobalToastManager.showToast(context.getString(R.string.more_developer_options_failed))
                                }
                            }
                        }
                    )
                }
                item {
                    MoreGridItem(
                        title = stringResource(id = R.string.more_tv_interconnect),
                        subtitle = stringResource(id = R.string.more_tv_interconnect_desc),
                        icon = androidx.compose.material.icons.Icons.Default.Share,
                        onClick = onNavigateToDeviceList
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreGridItem(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.moreGridTileHeight)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusLg),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused,
                focusedBorderColor = TvTheme.colors.focusBorder
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(TvTheme.dimens.spacingLg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TvTheme.colors.textPrimary,
            modifier = Modifier.size(TvTheme.dimens.iconSizeLg)
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
        Text(
            text = title,
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCol1Text,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
        // 副标题按换行符拆成独立 Text 逐行渲染（内存信息为"已用 X / 共 Y"两行）。
        // 不能把含 \n 的整串交给单个 Text：搭配 Ellipsis + maxLines 时真机复现
        // 首行即被截断、第二行整行丢失（2026-10-24 手机实测）。
        // 无副标题的磁贴渲染一个空行占位，保持各磁贴标题纵向位置一致。
        val subtitleLines = if (subtitle.isNullOrEmpty()) listOf("") else subtitle.split('\n')
        subtitleLines.forEachIndexed { index, line ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
            }
            Text(
                text = line,
                color = if (subtitle != null) TvTheme.colors.textSecondary else TvTheme.colors.textSecondary.copy(alpha = 0f),
                fontSize = TvTheme.dimens.fontSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
