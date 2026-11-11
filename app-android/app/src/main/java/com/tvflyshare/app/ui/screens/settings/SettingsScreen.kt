package com.tvflyshare.app.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import com.tvflyshare.app.uiconfig.components.TvConfirmDialog
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvGridMenuDialog
import com.tvflyshare.app.uiconfig.components.TvGridMenuItem
import com.tvflyshare.app.uiconfig.components.TvSwitch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.tvflyshare.app.uiconfig.components.TvTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvflyshare.app.ui.screens.settings.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.StoragePermissionUtils
import com.tvflyshare.app.ui.screens.filemanager.DirPickerScreen
import com.tvflyshare.app.filebrowser.model.FileOperationType
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showWorkgroupDialog by remember { mutableStateOf(false) }
    var showNotifPermHint by remember { mutableStateOf(false) }
    var showSavePathDialog by remember { mutableStateOf(false) }
    var showOrientationDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // 电视设备永远横屏，且无法转竖屏；该设置项对电视无意义，直接隐藏（与 MainActivity 始终锁横屏一致）
    val isTv = remember {
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // 开启通知开关时，由用户主动触发系统通知授权（Android 13+）；
    // 授权通过后才真正启用前台通知，避免首次打开时主动打扰。
    // 若权限已被永久拒绝（系统不再弹窗，直接返回 false），给出明确反馈并引导去系统设置。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.updateNotificationEnabled(true)
        } else {
            showNotifPermHint = true
        }
    }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: context.getString(R.string.settings_unknown)
        } catch (e: Exception) {
            context.getString(R.string.settings_unknown)
        }
    }

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.settings_title),
            showBack = true,
            onBack = onBack,
        ) { windowInfo ->
            val compact = windowInfo.widthClass == WindowWidthClass.COMPACT
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
            ) {
                        SettingItem(
                            title = stringResource(id = R.string.settings_smb_workgroup),
                            value = uiState.smbWorkgroup,
                            compact = compact,
                            onClick = { showWorkgroupDialog = true }
                        )

                        SettingToggleItem(
                            title = stringResource(id = R.string.settings_notification),
                            desc = stringResource(id = R.string.settings_notification_desc),
                            checked = uiState.notificationEnabled,
                            compact = compact,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.updateNotificationEnabled(true)
                                    }
                                } else {
                                    viewModel.updateNotificationEnabled(false)
                                }
                            }
                        )
                        
                        SettingToggleItem(
                            title = stringResource(id = R.string.settings_auto_fix_apk_suffix),
                            checked = uiState.autoFixApkSuffix,
                            compact = compact,
                            onCheckedChange = { checked ->
                                viewModel.updateAutoFixApkSuffix(checked)
                            }
                        )

                        SettingToggleItem(
                            title = stringResource(id = R.string.settings_built_in_preview),
                            checked = uiState.builtInPreview,
                            compact = compact,
                            onCheckedChange = { checked ->
                                viewModel.updateBuiltInPreview(checked)
                            }
                        )

                        SettingItem(
                            title = stringResource(id = R.string.settings_save_path),
                            value = if (uiState.savePath.isBlank()) stringResource(id = R.string.settings_save_path_default_display) else uiState.savePath,
                            compact = compact,
                            onClick = { showSavePathDialog = true }
                        )

                        SettingItem(
                            title = stringResource(id = R.string.settings_theme),
                            value = when (uiState.currentTheme) {
                                "GREEN" -> stringResource(id = R.string.theme_green)
                                "PURPLE" -> stringResource(id = R.string.theme_purple)
                                "ORANGE" -> stringResource(id = R.string.theme_orange)
                                else -> stringResource(id = R.string.theme_blue)
                            },
                            compact = compact,
                            onClick = {
                                val nextTheme = when (uiState.currentTheme) {
                                    "BLUE" -> "GREEN"
                                    "GREEN" -> "PURPLE"
                                    "PURPLE" -> "ORANGE"
                                    else -> "BLUE"
                                }
                                viewModel.updateTheme(nextTheme)
                            }
                        )

                        // 屏幕方向：仅非 TV 设备显示（TV 永远横屏，见 MainActivity）
                        if (!isTv) {
                            SettingItem(
                                title = stringResource(id = R.string.settings_screen_orientation),
                                value = when (uiState.screenOrientation) {
                                    "LANDSCAPE" -> stringResource(id = R.string.orientation_landscape)
                                    "PORTRAIT" -> stringResource(id = R.string.orientation_portrait)
                                    else -> stringResource(id = R.string.orientation_auto)
                                },
                                compact = compact,
                                onClick = { showOrientationDialog = true }
                            )
                        }

                        SettingItem(
                            title = stringResource(id = R.string.settings_about),
                            value = stringResource(id = R.string.settings_version, versionName),
                            compact = compact,
                            onClick = onNavigateToAbout
                        )
                    }
        }

        // SMB 工作组编辑弹窗
        if (showWorkgroupDialog) {
            var tempWorkgroup by remember { mutableStateOf(uiState.smbWorkgroup) }
            
            TvAlertDialog(
                title = stringResource(id = R.string.settings_smb_workgroup_title),
                text = stringResource(id = R.string.settings_smb_workgroup_desc),
                confirmText = stringResource(id = R.string.btn_save),
                dismissText = stringResource(id = R.string.btn_cancel),
                onConfirm = {
                    viewModel.updateSmbWorkgroup(tempWorkgroup)
                    showWorkgroupDialog = false
                },
                onDismiss = { showWorkgroupDialog = false },
                content = {
                    TvTextField(
                        value = tempWorkgroup,
                        onValueChange = { tempWorkgroup = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = TvTheme.dimens.spacingMd)
                    )
                }
            )
        }

        // 通知权限被拒 / 系统不再弹窗时的引导提示
        if (showNotifPermHint) {
            TvAlertDialog(
                title = stringResource(id = R.string.settings_notification_perm_title),
                text = stringResource(id = R.string.settings_notification_perm_desc),
                confirmText = stringResource(id = R.string.settings_notification_perm_go),
                dismissText = stringResource(id = R.string.btn_cancel),
                onConfirm = {
                    showNotifPermHint = false
                    runCatching {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                },
                onDismiss = { showNotifPermHint = false }
            )
        }

        // 文件保存路径：选文件夹（复用目录选择器，只选目录、不执行复制/移动）
        if (showSavePathDialog) {
            DirPickerScreen(
                selectedPaths = emptyList(),
                operationType = FileOperationType.SELECT_DIR,
                onComplete = { targetPath, _ ->
                    when {
                        // 空字符串来自「恢复默认」
                        targetPath.isEmpty() -> {
                            viewModel.updateSavePath("")
                            showSavePathDialog = false
                        }
                        StoragePermissionUtils.isCustomSavePathValid(context, targetPath) -> {
                            viewModel.updateSavePath(targetPath)
                            showSavePathDialog = false
                        }
                        else -> {
                            // 选到了不可用目录（如 Android 系统目录）：提示并停留在选择器
                            coroutineScope.launch {
                                GlobalToastManager.showToast(context.getString(R.string.settings_save_path_invalid))
                            }
                        }
                    }
                },
                onCancel = { showSavePathDialog = false }
            )
        }

        // 屏幕方向：三选项弹窗（横屏 / 竖屏 / 自适应），点选即生效
        if (showOrientationDialog) {
            TvGridMenuDialog(
                title = stringResource(id = R.string.settings_screen_orientation_title),
                fallbackIconText = "屏",
                onDismissRequest = { showOrientationDialog = false },
                items = listOf(
                    TvGridMenuItem(
                        text = stringResource(id = R.string.orientation_landscape),
                        icon = Icons.Default.StayCurrentLandscape
                    ) {
                        viewModel.updateScreenOrientation("LANDSCAPE")
                        showOrientationDialog = false
                    },
                    TvGridMenuItem(
                        text = stringResource(id = R.string.orientation_portrait),
                        icon = Icons.Default.StayCurrentPortrait
                    ) {
                        viewModel.updateScreenOrientation("PORTRAIT")
                        showOrientationDialog = false
                    },
                    TvGridMenuItem(
                        text = stringResource(id = R.string.orientation_auto),
                        icon = Icons.Default.ScreenRotation
                    ) {
                        viewModel.updateScreenOrientation("AUTO")
                        showOrientationDialog = false
                    }
                )
            )
        }
    }
}

@Composable
private fun SettingItem(
    title: String,
    value: String,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused,
                focusedBorderColor = TvTheme.colors.focusBorder
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingMd)
    ) {
        if (compact) {
            // 竖屏手机：标题独占一行，值与箭头移到下一行，避免右侧被挤成竖排
            Text(
                text = title,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol1Text,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TvTheme.colors.textSecondary,
                    modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
                )
            }
        } else {
            // 横屏 / TV：标题在左、值在右，保持原布局
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontCol1Text,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingSm))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TvTheme.colors.textPrimary,
                        modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    desc: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused,
                focusedBorderColor = TvTheme.colors.focusBorder
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingMd)
    ) {
        if (compact) {
            // 竖屏窄窗口：标题与开关同排（开关恒定占位，不会被挤掉），说明文字移到下方
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontCol1Text,
                    fontWeight = FontWeight.Medium
                )
                TvSwitch(checked = checked)
            }
            desc?.let {
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                Text(
                    text = it,
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontCaption
                )
            }
        } else {
            // 横屏/TV：文字列占满剩余空间（weight 保证开关恒定 48dp 不被挤出/裁掉），开关在右
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                ) {
                    Text(
                        text = title,
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontCol1Text,
                        fontWeight = FontWeight.Medium
                    )
                    desc?.let {
                        Text(
                            text = it,
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontCaption
                        )
                    }
                }
                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                TvSwitch(checked = checked)
            }
        }
    }
}
