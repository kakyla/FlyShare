package com.tvflyshare.app.ui.screens.filemanager

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvButton
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusTarget
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.components.TvFileListItem
import com.tvflyshare.app.util.FileEntry
import com.tvflyshare.app.util.FileOperations
import com.tvflyshare.app.util.ConflictStrategy
import com.tvflyshare.app.util.FileScanner
import com.tvflyshare.app.util.AppException
import java.io.File
import kotlin.comparisons.compareBy
import com.tvflyshare.app.Constants
import com.tvflyshare.app.settings.SettingsRepository
import com.tvflyshare.app.uiconfig.components.BookmarkPickerDialog
import com.tvflyshare.app.filebrowser.model.FileOperationType

// 2026-10-30 重构：FileOperationType 已归位到 filebrowser/model/FileOperationType.kt

@Composable
fun DirPickerScreen(
    selectedPaths: List<String>,
    operationType: FileOperationType,
    onComplete: (targetPath: String, message: String) -> Unit,
    onCancel: () -> Unit,
    customOperation: ((String) -> Pair<Int, List<String>>)? = null,
) {
    val context = LocalContext.current
    // 2026-10-29 适配：沿用全局窗口宽度档位（见 uiconfig/global/WindowSizeClass.kt）。
    // COMPACT（手机竖屏/窄屏）走单栏；其余（平板横屏/电视）保持两栏。
    val isCompact = rememberWindowAdaptiveInfo().widthClass == WindowWidthClass.COMPACT
    val rootPath = Constants.INTERNAL_STORAGE_ROOT
    var currentPath by remember { mutableStateOf(rootPath) }
    var fileList by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isOperating by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }


    val title = when (operationType) {
        FileOperationType.COPY -> stringResource(id = R.string.dir_picker_copy_to)
        FileOperationType.MOVE -> stringResource(id = R.string.dir_picker_move_to)
        FileOperationType.DOWNLOAD -> stringResource(id = R.string.dir_picker_download_to)
        FileOperationType.SELECT_DIR -> stringResource(id = R.string.save_path_pick_title)
    }

    LaunchedEffect(currentPath, refreshTrigger) {
        isLoading = true
        loadError = null
        FileScanner.scanDirectory(currentPath)
            .onSuccess { entries ->
                fileList = entries.filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
            .onFailure {
                val msg = (it as? AppException)?.uiMessage?.asString(context) ?: it.message
                loadError = msg ?: context.getString(R.string.dir_picker_cannot_access)
            }
        isLoading = false
    }

    fun performOperation(strategy: ConflictStrategy) {
        // 2026-10-04 审计 P2 修复：isOperating 在途守卫——原先三处赋值从未被读取，
        // 确认按钮可连点，双击会并发跑两份复制/移动（FAIL 互相报错、OVERWRITE 竞态删半成品）
        if (isOperating) return
        isOperating = true
        // 2026-10-04 审计 P1 修复：copyFilesWithFeedback/moveFilesWithFeedback 是纯阻塞流式 IO，
        // 原先跑在主线程（rememberCoroutineScope），大文件操作期间整页冻结必然 ANR。切 IO，结果回 Main。
        coroutineScope.launch(Dispatchers.IO) {
            val result = if (customOperation != null) {
                val (c, errs) = customOperation(currentPath)
                Triple(c, 0, errs)
            } else {
                val sourceEntries = selectedPaths.map { path ->
                    FileEntry(
                        name = File(path).name,
                        path = path,
                        isDirectory = false,
                        size = File(path).length(),
                        lastModified = File(path).lastModified(),
                        sizeText = "",
                        dateText = ""
                    )
                }
                if (operationType == FileOperationType.COPY) {
                    FileOperations.copyFilesWithFeedback(context.applicationContext, sourceEntries, currentPath, strategy)
                } else {
                    FileOperations.moveFilesWithFeedback(context.applicationContext, sourceEntries, currentPath, strategy)
                }
            }
            val (count, skipped, errors) = result
            withContext(Dispatchers.Main) {
                isOperating = false
                val finalMessage = if (errors.isEmpty()) {
                    val base = if (operationType == FileOperationType.COPY) context.getString(R.string.dir_picker_copied, count) else context.getString(R.string.dir_picker_moved, count)
                    if (skipped > 0) "$base\n${context.getString(R.string.dir_picker_skipped, skipped)}" else base
                } else {
                    val opLabel = if (operationType == FileOperationType.COPY) context.getString(R.string.dir_picker_copy) else context.getString(R.string.dir_picker_move)
                    val base = if (count > 0) {
                        context.getString(R.string.dir_picker_success_fail, opLabel, count, errors.size, errors.firstOrNull() ?: "")
                    } else {
                        context.getString(R.string.dir_picker_fail, opLabel, errors.firstOrNull() ?: context.getString(R.string.dir_picker_unknown_error))
                    }
                    if (skipped > 0) "$base\n${context.getString(R.string.dir_picker_skipped, skipped)}" else base
                }
                onComplete(currentPath, finalMessage)
            }
        }
    }

    BackHandler {
        if (currentPath != rootPath) {
            currentPath = File(currentPath).parent ?: rootPath
        } else {
            onCancel()
        }
    }

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
                    contentDescription = stringResource(id = R.string.file_manager_action_cancel),
                    tint = TvTheme.colors.textPrimary,
                    modifier = Modifier
                        .size(TvTheme.dimens.iconSizeLg)
                        .clickable {
                            if (currentPath != rootPath) {
                                currentPath = File(currentPath).parent ?: rootPath
                            } else {
                                onCancel()
                            }
                        }
                )
                Text(
                    text = title,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontTitle,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = TvTheme.dimens.spacingSm)
                        .weight(1f)
                )
                if (operationType != FileOperationType.SELECT_DIR) {
                    Text(
                        text = stringResource(id = R.string.dir_picker_items, selectedPaths.size),
                        color = TvTheme.colors.brandPrimary,
                        fontSize = TvTheme.dimens.fontCol2Title,
                    )
                }
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
                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                val folderInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .tvFocusStyle(
                            interactionSource = folderInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                            normalBgColor = TvTheme.colors.surface,
                            focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                            focusedBorderColor = TvTheme.colors.focusBorder,
                            borderWidth = TvTheme.dimens.borderFocused
                        )
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .clickable(
                            interactionSource = folderInteraction,
                            indication = null,
                            onClick = { showCreateFolderDialog = true }
                        )
                        .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = stringResource(id = R.string.dir_picker_create_folder),
                        tint = TvTheme.colors.textPrimary,
                        modifier = Modifier.size(TvTheme.dimens.iconSizeMd)
                    )
                    Text(
                        text = stringResource(id = R.string.dir_picker_create),
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
                    // 确认按钮逻辑（单栏/两栏共用）
                    val onConfirmTarget = {
                        if (operationType == FileOperationType.SELECT_DIR) {
                            // 只选目录：直接回传当前目录，不执行复制/移动
                            onComplete(currentPath, "")
                        } else {
                            val conflictCount = selectedPaths.count { p -> File(currentPath, File(p).name).exists() }
                            if (conflictCount > 0) {
                                showConflictDialog = true
                            } else {
                                performOperation(ConflictStrategy.FAIL)
                            }
                        }
                    }

                    if (isCompact) {
                        // 紧凑/竖屏：单栏——列表占满整行宽，确认区落在底部
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = TvTheme.dimens.spacing2Xl)
                                .padding(top = TvTheme.dimens.spacingMd, bottom = TvTheme.dimens.spacingMd)
                        ) {
                            FolderList(
                                currentPath = currentPath,
                                rootPath = rootPath,
                                fileList = fileList,
                                onUp = { currentPath = File(currentPath).parent ?: rootPath },
                                onFolderClick = { currentPath = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                            ConfirmPanel(
                                targetPath = currentPath,
                                operationType = operationType,
                                operating = isOperating,
                                onConfirm = onConfirmTarget,
                                fillHeight = false,
                                modifier = Modifier.fillMaxWidth(),
                                onUseDefault = if (operationType == FileOperationType.SELECT_DIR) ({ onComplete("", "") }) else null
                            )
                        }
                    } else {
                        // 宽屏/横屏：两栏（左列表 + 右侧确认栏）
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = TvTheme.dimens.spacing2Xl)
                                .padding(top = TvTheme.dimens.spacingMd, bottom = TvTheme.dimens.spacingMd)
                        ) {
                            FolderList(
                                currentPath = currentPath,
                                rootPath = rootPath,
                                fileList = fileList,
                                onUp = { currentPath = File(currentPath).parent ?: rootPath },
                                onFolderClick = { currentPath = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(TvTheme.dimens.spacingLg))
                            ConfirmPanel(
                                targetPath = currentPath,
                                operationType = operationType,
                                operating = isOperating,
                                onConfirm = onConfirmTarget,
                                fillHeight = true,
                                modifier = Modifier.width(TvTheme.dimens.actionPanelWidth),
                                onUseDefault = if (operationType == FileOperationType.SELECT_DIR) ({ onComplete("", "") }) else null
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 收藏夹选择（点收藏项 → 跳到该目录作为目标） ──
    if (showBookmarks) {
        val bookmarks = remember { SettingsRepository(context).getLocalBookmarks() }
        BookmarkPickerDialog(
            bookmarks = bookmarks,
            onPick = { path ->
                currentPath = path
                showBookmarks = false
            },
            onDismiss = { showBookmarks = false },
        )
    }

    // ── 重名冲突对话框 ──
    if (showConflictDialog) {
        ConflictDialog(
            conflictCount = selectedPaths.count { p -> File(currentPath, File(p).name).exists() },
            onOverwrite = { showConflictDialog = false; performOperation(ConflictStrategy.OVERWRITE) },
            onSkip = { showConflictDialog = false; performOperation(ConflictStrategy.SKIP) },
            onRename = { showConflictDialog = false; performOperation(ConflictStrategy.RENAME) },
            onCancel = { showConflictDialog = false }
        )
    }

    // ── 新建文件夹对话框 ──
    if (showCreateFolderDialog) {
        var inputName by remember { mutableStateOf("") }
        val inputInteractionSource = remember { MutableInteractionSource() }
        val isInputFocused by inputInteractionSource.collectIsFocusedAsState()
        val inputColor = if (isInputFocused) TvTheme.colors.focusBorder else TvTheme.colors.surfaceVariant
        
        TvAlertDialog(
            title = stringResource(id = R.string.dir_picker_create_folder),
            text = stringResource(id = R.string.dir_picker_input_folder_name),
            confirmText = stringResource(id = R.string.btn_confirm),
            dismissText = stringResource(id = R.string.btn_cancel),
            onConfirm = {
                if (inputName.isNotBlank()) {
                    showCreateFolderDialog = false
                    // 2026-10-10 审计修复：createFolder 含 mkdir 等磁盘 IO，切 IO 线程——
                    // 对齐同文件 performOperation 的主线程 ANR 修复，唯独新建文件夹路径漏改
                    coroutineScope.launch(Dispatchers.IO) {
                        val result = FileOperations.createFolder(context, currentPath, inputName.trim())
                        result.onSuccess {
                            GlobalToastManager.showToast(context.getString(R.string.dir_picker_created_folder, inputName.trim()))
                            refreshTrigger++
                        }.onFailure { e ->
                            val msg = (e as? AppException)?.uiMessage?.asString(context) ?: e.message
                            GlobalToastManager.showToast(msg ?: context.getString(R.string.dir_picker_create_fail))
                        }
                    }
                }
            },
            onDismiss = { 
                showCreateFolderDialog = false
            },
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvTheme.dimens.inputFieldHeight)
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .background(TvTheme.colors.textPrimary.copy(alpha = 0.05f))
                        .border(TvTheme.dimens.borderFocused, inputColor, RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .padding(horizontal = TvTheme.dimens.spacingMd),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        textStyle = TextStyle(
                            color = TvTheme.colors.textPrimary,
                            fontSize = TvTheme.dimens.fontCol2Title,
                            fontFamily = FontFamily.Default,
                        ),
                        singleLine = true,
                        interactionSource = inputInteractionSource,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (inputName.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.dir_picker_create_folder),
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontCol2Title,
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun UpFolderRow(onClick: () -> Unit) {
    TvFileListItem(
        icon = Icons.Default.FolderOpen,
        iconTint = TvTheme.colors.brandPrimary,
        title = stringResource(id = R.string.dir_picker_up_level),
        onClick = onClick
    )
}

@Composable
private fun FolderRow(entry: FileEntry, onClick: () -> Unit) {
    TvFileListItem(
        icon = Icons.Default.Folder,
        iconTint = TvTheme.colors.iconFolder,
        title = entry.name,
        onClick = onClick
    )
}

@Composable
private fun ConfirmPanel(
    targetPath: String,
    operationType: FileOperationType,
    operating: Boolean,
    onConfirm: () -> Unit,
    fillHeight: Boolean = true,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
    onUseDefault: (() -> Unit)? = null,
) {
    
    val opLabel = when (operationType) {
        FileOperationType.COPY -> stringResource(id = R.string.dir_picker_copy_here)
        FileOperationType.MOVE -> stringResource(id = R.string.dir_picker_move_here)
        FileOperationType.DOWNLOAD -> stringResource(id = R.string.dir_picker_download_here)
        FileOperationType.SELECT_DIR -> stringResource(id = R.string.save_path_pick_confirm)
    }

    Column(
        modifier = if (fillHeight) modifier.fillMaxHeight() else modifier,
        verticalArrangement = if (fillHeight) Arrangement.SpaceBetween else Arrangement.spacedBy(TvTheme.dimens.spacingSm)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)) {
            // Target label
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                    .background(TvTheme.colors.textPrimary.copy(alpha = 0.05f))
                    .padding(TvTheme.dimens.spacingMd)
            ) {
                Text(text = stringResource(id = R.string.dir_picker_target_path), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontSmall)
            }

            Text(
                text = targetPath,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontSmall,
                modifier = Modifier.padding(horizontal = TvTheme.dimens.spacingXs)
            )

            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))

            TvButton(
                text = opLabel,
                onClick = onConfirm,
                enabled = !operating,
                modifier = buttonModifier
            )
            if (onUseDefault != null) {
                TvButton(
                    text = stringResource(id = R.string.save_path_use_default),
                    onClick = onUseDefault,
                    enabled = !operating,
                )
            }
        }

        Text(
            text = stringResource(id = R.string.dir_picker_hint),
            color = TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontSmall,
            modifier = Modifier.padding(bottom = if (fillHeight) TvTheme.dimens.spacingSm else TvTheme.dimens.spacingXs)
        )
    }
}

@Composable
private fun FolderList(
    currentPath: String,
    rootPath: String,
    fileList: List<FileEntry>,
    onUp: () -> Unit,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingXs)
    ) {
        // Up one level
        if (currentPath != rootPath) {
            item {
                UpFolderRow(onClick = onUp)
            }
        }

        items(fileList, key = { it.path }) { entry ->
            if (entry.isDirectory) {
                FolderRow(entry = entry, onClick = { onFolderClick(entry.path) })
            } else {
                FileRow(entry = entry)
            }
        }

        if (fileList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TvTheme.dimens.listItemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(id = R.string.dir_picker_empty_dir),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontBody
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry) {
    TvFileListItem(
        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
        iconTint = TvTheme.colors.textSecondary,
        title = entry.name,
        subtitle = entry.sizeText.ifBlank { null },
        onClick = {}
    )
}

@Composable
private fun ConflictDialog(
    conflictCount: Int,
    onOverwrite: () -> Unit,
    onSkip: () -> Unit,
    onRename: () -> Unit,
    onCancel: () -> Unit,
) {
    val isCompact = rememberWindowAdaptiveInfo().widthClass == WindowWidthClass.COMPACT
    val items = listOf(
        Pair(stringResource(id = R.string.dir_picker_overwrite), onOverwrite),
        Pair(stringResource(id = R.string.dir_picker_skip), onSkip),
        Pair(stringResource(id = R.string.dir_picker_rename), onRename),
        Pair(stringResource(id = R.string.btn_cancel), onCancel),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvTheme.colors.dialogScrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .then(if (isCompact) Modifier.fillMaxWidth().padding(horizontal = TvTheme.dimens.spacingLg) else Modifier.width(TvTheme.dimens.dialogWidthLg))
                .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                .background(TvTheme.colors.dialogBg)
                .border(TvTheme.dimens.borderNormal, TvTheme.colors.borderNormal, RoundedCornerShape(TvTheme.dimens.radiusLg))
                .padding(TvTheme.dimens.spacing2Xl),
            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
        ) {
            Text(
                text = stringResource(id = R.string.dir_picker_conflict_title),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(id = R.string.dir_picker_conflict_msg, conflictCount),
                color = TvTheme.colors.textPrimary.copy(alpha = 0.9f),
                fontSize = TvTheme.dimens.fontCaption
            )
            items.forEach { (label, action) ->
                TvButton(text = label, onClick = action)
            }
        }
    }
}

