package com.tvflyshare.app.ui.screens.appuninstall

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.compose.runtime.DisposableEffect
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.global.*

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.uiconfig.components.TvGridMenuDialog
import com.tvflyshare.app.uiconfig.components.TvGridMenuItem
import com.tvflyshare.app.uiconfig.components.TvButton
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.tvflyshare.app.util.ImageUtils.toBitmapSafe

@Composable
fun AppUninstallScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var needRefresh by remember { mutableStateOf(false) }
    
    // 当前显示的页面类型 (true = 用户应用, false = 系统应用)
    var isShowingUserApps by remember { mutableStateOf(true) }
    
    // 是否隐藏无界面的系统组件
    var hideSystemComponents by remember { mutableStateOf(true) }
    
    // 弹窗状态管理
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // 加载应用列表
    LaunchedEffect(isShowingUserApps, hideSystemComponents) {
        loading = true
        apps = if (isShowingUserApps) {
            AppUninstallManager.loadUserApps(context)
        } else {
            AppUninstallManager.loadSystemApps(context, hideSystemComponents)
        }
        loading = false
    }

    // 监听应用卸载广播，实现精准局部刷新
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
                    // 2026-10-04 审计 P2 修复：覆盖安装（应用自更新）也发 PACKAGE_REMOVED（带 EXTRA_REPLACING），
                    // 不过滤会把刚更新的应用从列表删掉且不会自动加回
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    val packageName = intent.data?.schemeSpecificPart
                    if (packageName != null) {
                        apps = apps.filter { it.packageName != packageName }
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    TVBackground {
        ScreenScaffold(
            title = if (isShowingUserApps) stringResource(R.string.app_uninstall_my_apps) else stringResource(R.string.app_uninstall_system_apps),
            showBack = true,
            onBack = {
                if (!isShowingUserApps) {
                    isShowingUserApps = true // 从系统应用返回用户应用
                } else {
                    onBack() // 真正退出应用管理
                }
            },
            rightAction = if (isShowingUserApps) {
                {
                    TvButton(
                        text = stringResource(R.string.app_uninstall_view_system_apps),
                        onClick = { isShowingUserApps = false }
                    )
                }
            } else {
                {
                    TvButton(
                        text = if (hideSystemComponents) stringResource(R.string.app_uninstall_show_all_components) else stringResource(R.string.app_uninstall_hide_system_components),
                        onClick = { hideSystemComponents = !hideSystemComponents }
                    )
                }
            },
            scrollable = false,
            applyBodyPadding = false,
        ) { windowInfo ->

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            CircularProgressIndicator(color = TvTheme.colors.textPrimary)
                            Text(
                                text = stringResource(id = R.string.app_uninstall_scanning),
                                color = TvTheme.colors.textSecondary,
                                fontSize = TvTheme.dimens.fontCaption,
                            )
                        }
                    }
                }

                apps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val emptyText = if (isShowingUserApps) {
                            stringResource(id = R.string.app_uninstall_no_apps)
                        } else {
                            if (hideSystemComponents) stringResource(id = R.string.app_uninstall_no_system_apps) else stringResource(id = R.string.app_uninstall_no_apps)
                        }
                        Text(
                            text = emptyText,
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontCaption,
                        )
                    }
                }

                else -> {
                    if (windowInfo.isWideLayout) {
                        // 横屏/TV：4 列网格（原样保持）
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = TvTheme.dimens.spacing2Xl, vertical = TvTheme.dimens.spacingMd),
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                            horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm),
                        ) {
                            items(apps) { app ->
                                AppItemGrid(
                                    app = app,
                                    onClick = {
                                        selectedApp = app
                                        showActionDialog = true
                                    },
                                )
                            }
                        }
                    } else {
                        // 竖屏：单列行式列表——安卓通用样式，图标在左、名称在右（用户真机反馈）
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = TvTheme.dimens.spacingXl, vertical = TvTheme.dimens.spacingMd),
                            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd),
                        ) {
                            items(apps) { app ->
                                AppItemRow(
                                    app = app,
                                    onClick = {
                                        selectedApp = app
                                        showActionDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // 纯净版操作菜单弹窗
        if (showActionDialog) {
            selectedApp?.let { app ->
            val menuItems = mutableListOf<TvGridMenuItem>().apply {
                add(TvGridMenuItem(
                    text = stringResource(R.string.app_uninstall_btn_launch),
                    icon = Icons.Default.PlayArrow,
                    onClick = {
                        showActionDialog = false
                        val launched = AppUninstallManager.launchApp(context, app.packageName)
                        if (!launched) {
                            coroutineScope.launch {
                                GlobalToastManager.showToast(context.getString(R.string.app_uninstall_no_launch_intent))
                            }
                        }
                    }
                ))
                add(TvGridMenuItem(
                    // 2026-10-24：打开系统原生应用详情页（权限/存储/强行停止），部分机型无其他入口
                    text = stringResource(R.string.app_uninstall_btn_app_info),
                    icon = Icons.Default.Settings,
                    onClick = {
                        showActionDialog = false
                        if (!AppUninstallManager.openAppDetails(context, app.packageName)) {
                            coroutineScope.launch {
                                GlobalToastManager.showToast(context.getString(R.string.app_uninstall_no_details_entry))
                            }
                        }
                    }
                ))
                add(TvGridMenuItem(
                    text = stringResource(R.string.app_uninstall_btn_extract),
                    icon = Icons.Default.Build,
                    onClick = {
                        showActionDialog = false
                        coroutineScope.launch {
                            GlobalToastManager.showToast(context.getString(R.string.app_uninstall_extracting, app.name))
                            val result = AppUninstallManager.extractApk(context, app.packageName, app.name)
                            if (result.isSuccess) {
                                GlobalToastManager.showToast(context.getString(R.string.app_uninstall_extract_success))
                            } else {
                                val errorMsg = result.exceptionOrNull()?.message ?: context.getString(R.string.app_uninstall_extract_fail)
                                GlobalToastManager.showToast(errorMsg)
                            }
                        }
                    }
                ))
                if (!app.isSystem) {
                    add(TvGridMenuItem(
                        text = stringResource(R.string.app_uninstall_btn),
                        icon = Icons.Default.Delete,
                        isDestructive = true,
                        onClick = {
                            showActionDialog = false
                            try {
                                val intent = AppUninstallManager.createUninstallIntent(app.packageName)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                coroutineScope.launch {
                                    GlobalToastManager.showToast(context.getString(R.string.app_uninstall_not_supported))
                                }
                            }
                        }
                    ))
                }
                add(TvGridMenuItem(
                    text = stringResource(R.string.app_uninstall_btn_close),
                    icon = Icons.Default.Close,
                    onClick = { showActionDialog = false }
                ))
            }

            TvGridMenuDialog(
                title = app.name,
                iconBitmap = app.icon.toBitmapSafe()?.asImageBitmap(),
                fallbackIconText = app.name.firstOrNull()?.toString(),
                onDismissRequest = { showActionDialog = false },
                items = menuItems
            )
            }
        }
    }

}

@Composable
private fun AppItemGrid(
    app: AppInfo,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgModifier = if (isFocused) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
            .background(TvTheme.colors.surfaceHover)
            .border(TvTheme.dimens.borderNormal, TvTheme.colors.focusBorder, RoundedCornerShape(TvTheme.dimens.radiusLg))
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
            .background(TvTheme.colors.surface)
    }

    Column(
        modifier = bgModifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(TvTheme.dimens.spacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 应用图标
        app.icon.toBitmapSafe()?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = app.name,
                modifier = Modifier
                    .size(TvTheme.dimens.iconSizeXl)
                    .clip(RoundedCornerShape(TvTheme.dimens.radiusMd)),
            )
        } ?: Box(
            modifier = Modifier
                .size(TvTheme.dimens.iconSizeXl)
                .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                .background(TvTheme.colors.brandPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.name.firstOrNull()?.toString() ?: "?",
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol2Title,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))

        // 应用名 + 包名
        // minLines=2 固定占两行高度：名称一行的磁贴也预留两行，保证所有磁贴统一高度
        //（2026-10-24 用户平板实测反馈：名称一行/两行混杂导致磁贴高低不齐）
        Text(
            text = app.name,
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCaption,
            fontWeight = FontWeight.Medium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
        Text(
            text = app.packageName,
            color = TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppItemRow(
    app: AppInfo,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgModifier = if (isFocused) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .background(TvTheme.colors.surfaceHover)
            .border(TvTheme.dimens.borderNormal, TvTheme.colors.focusBorder, RoundedCornerShape(TvTheme.dimens.radiusMd))
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .background(TvTheme.colors.surface)
    }

    Row(
        modifier = bgModifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg),
    ) {
        // 应用图标（左）
        app.icon.toBitmapSafe()?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = app.name,
                modifier = Modifier
                    .size(TvTheme.dimens.iconSizeLg)
                    .clip(RoundedCornerShape(TvTheme.dimens.radiusMd)),
            )
        } ?: Box(
            modifier = Modifier
                .size(TvTheme.dimens.iconSizeLg)
                .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                .background(TvTheme.colors.brandPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.name.firstOrNull()?.toString() ?: "?",
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol2Title,
                fontWeight = FontWeight.Bold,
            )
        }

        // 应用名 + 包名（右）
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCaption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
            Text(
                text = app.packageName,
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
