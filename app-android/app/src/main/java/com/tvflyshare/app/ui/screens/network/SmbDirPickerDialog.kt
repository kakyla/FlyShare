package com.tvflyshare.app.ui.screens.network

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.tvflyshare.app.R
import com.tvflyshare.app.settings.SettingsRepository
import com.tvflyshare.app.uiconfig.components.BookmarkPickerDialog
import com.tvflyshare.app.smb.SmbBrowser
import com.tvflyshare.app.smb.SmbFileEntry
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.components.TvFileListItem

/**
 * SMB 目录选择器
 *
 * 用于"复制/移动到 SMB 其他目录"时选择目标目录。
 * 只显示目录（不显示文件），支持进入子目录、返回上级。
 *
 * 复用 SmbBrowser.listDirectory 获取目录列表。
 *
 * @param browser 已初始化的 SmbBrowser
 * @param initialPath 初始路径（默认根目录 "\\"）
 * @param onConfirm 选中目录后回调，参数为选中的 SMB 路径
 * @param onCancel 取消回调
 */
@Composable
fun SmbDirPickerDialog(
    browser: SmbBrowser,
    host: String,
    shareName: String,
    initialPath: String = "\\",
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var dirList by remember { mutableStateOf<List<SmbFileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showBookmarks by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isCompact = rememberWindowAdaptiveInfo().widthClass == WindowWidthClass.COMPACT

    // 进入目录时加载
    // listDirectory 是同步 SMB 网络操作，必须切到 IO 线程避免主线程 ANR
    LaunchedEffect(currentPath) {
        isLoading = true
        loadError = null
        try {
            val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                browser.listDirectory(currentPath)
            }
            // 只保留目录
            dirList = all.filter { it.isDirectory }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            loadError = e.message ?: context.getString(R.string.smb_load_failed)
            dirList = emptyList()
        } finally {
            isLoading = false
        }
    }

    // 用 Dialog 创建独立 Window，隔离 TV 遥控器焦点，避免方向键穿透到背景目录列表
    // 之前用 Box + 半透明背景直接铺在 Compose 树里，焦点会穿透，上下键操作的是背景目录
    Dialog(
        onDismissRequest = { onCancel() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        TVBackground {
            Column(modifier = Modifier.fillMaxSize()) {
                // TopBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvTheme.dimens.topBarHeight)
                        .padding(horizontal = TvTheme.dimens.spacing2Xl),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(id = R.string.back),
                        tint = TvTheme.colors.textPrimary,
                        modifier = Modifier
                            .size(TvTheme.dimens.iconSizeLg)
                            .clickable {
                                if (currentPath != "\\" && currentPath.isNotEmpty()) {
                                    val parent = currentPath.substringBeforeLast("\\", "\\")
                                    currentPath = if (parent.isEmpty()) "\\" else parent
                                } else {
                                    onCancel()
                                }
                            }
                    )
                    Text(
                        text = stringResource(id = R.string.smb_dir_picker_target) + "   $currentPath",
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontTitle,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = TvTheme.dimens.spacingSm)
                            .weight(1f)
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                    // 收藏夹入口：跳到已收藏目录作为目标（用户要求移动/复制时可从收藏夹选目标）
                    val bookmarkInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = bookmarkInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                                normalBgColor = TvTheme.colors.surface,
                                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                                focusedBorderColor = TvTheme.colors.focusBorder,
                                borderWidth = TvTheme.dimens.borderFocused
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                            .clickable(
                                interactionSource = bookmarkInteraction,
                                indication = null,
                                onClick = { showBookmarks = true }
                            )
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = stringResource(id = R.string.action_bookmark),
                            tint = TvTheme.colors.textPrimary,
                            modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
                        )
                        Text(
                            text = stringResource(id = R.string.action_bookmark),
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontCaption,
                        )
                    }
                }

                // Content
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = TvTheme.colors.brandPrimary,
                                strokeWidth = TvTheme.dimens.borderFocused,
                                modifier = Modifier.size(TvTheme.dimens.iconSizeXl)
                            )
                        }
                    }
                    loadError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = loadError ?: "", color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontTitle)
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = if (isCompact) TvTheme.dimens.spacingXl else TvTheme.dimens.spacing3Xl)
                                .padding(top = TvTheme.dimens.spacingMd, bottom = TvTheme.dimens.spacingMd)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (!isCompact) Modifier.padding(end = TvTheme.dimens.sideMenuWidth + TvTheme.dimens.spacingLg) else Modifier)
                            ) {
                            // Left: folder list（竖屏让出底部 footer 高度，避免最后一排文件夹被确认栏遮住）
                            LazyColumn(
                                modifier = if (isCompact) Modifier.weight(1f) else Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                            ) {
                                // Up one level
                                if (currentPath != "\\" && currentPath.isNotEmpty()) {
                                    item {
                                        TvFileListItem(
                                            icon = Icons.Default.FolderOpen,
                                            iconTint = TvTheme.colors.brandPrimary,
                                            title = stringResource(id = R.string.smb_return_up_level),
                                            onClick = {
                                                val parent = currentPath.substringBeforeLast("\\", "\\")
                                                currentPath = if (parent.isEmpty()) "\\" else parent
                                            }
                                        )
                                    }
                                }

                                items(dirList) { dir ->
                                    TvFileListItem(
                                        icon = Icons.Default.Folder,
                                        iconTint = TvTheme.colors.iconFolder,
                                        title = dir.name,
                                        onClick = { currentPath = dir.path }
                                    )
                                }

                                if (dirList.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(TvTheme.dimens.listItemHeight),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = stringResource(id = R.string.smb_dir_picker_empty),
                                                color = TvTheme.colors.textSecondary,
                                                fontSize = TvTheme.dimens.fontCol1Text
                                            )
                                        }
                                    }
                                }
                            }
                            // 竖屏：确认栏作为列表底部固定 footer（不遮挡文件夹列表）
                            if (isCompact) {
                                SmbDirConfirmPanel(
                                    currentPath = currentPath,
                                    onConfirm = onConfirm,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = TvTheme.dimens.spacingSm),
                                )
                            }
                            } // inner Column end (folder list)

                            // 宽屏：确认栏在右侧固定宽整高（竖屏已在列表底部作为 footer 渲染）
                            if (!isCompact) {
                                SmbDirConfirmPanel(
                                    currentPath = currentPath,
                                    onConfirm = onConfirm,
                                    fillHeight = true,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(TvTheme.dimens.sideMenuWidth)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 收藏夹选择（SMB 内）：点收藏项 → 跳到该目录作为目标
    if (showBookmarks) {
        val bookmarks = remember { SettingsRepository(context).getSmbBookmarks(host, shareName) }
        BookmarkPickerDialog(
            bookmarks = bookmarks,
            onPick = { path ->
                currentPath = path
                showBookmarks = false
            },
            onDismiss = { showBookmarks = false },
        )
    }
}

/**
 * SMB 目录选择器的确认栏（目标路径展示 + 确认按钮）。
 * 宽屏作为右侧固定宽整高面板（fillHeight=true，信息在上/按钮贴底），
 * 竖屏作为列表底部 footer（wrap 高度，信息与按钮紧邻）。
 */
@Composable
private fun SmbDirConfirmPanel(
    currentPath: String,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = if (fillHeight) {
            Arrangement.SpaceBetween
        } else {
            Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                    .background(TvTheme.colors.surface.copy(alpha = 0.5f))
                    .padding(TvTheme.dimens.spacingMd)
            ) {
                Text(
                    text = stringResource(id = R.string.smb_dir_picker_target),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall
                )
            }
            Text(
                text = currentPath,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontSmall,
                modifier = Modifier.padding(horizontal = TvTheme.dimens.spacingXs)
            )
        }

        val confirmInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvTheme.dimens.buttonHeightLg)
                .tvFocusStyle(
                    interactionSource = confirmInteraction,
                    shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                    normalBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                    focusedBorderColor = TvTheme.colors.focusBorder,
                    borderWidth = TvTheme.dimens.borderFocused
                )
                .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                .clickable(
                    interactionSource = confirmInteraction,
                    indication = null
                ) { onConfirm(currentPath) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(id = R.string.smb_dir_picker_confirm),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol2Title,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
