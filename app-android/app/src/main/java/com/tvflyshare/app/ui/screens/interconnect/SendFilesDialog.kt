package com.tvflyshare.app.ui.screens.interconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvflyshare.app.R
import com.tvflyshare.app.ui.viewmodel.RemoteBrowseViewModel
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.TvCheckbox
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.rememberWindowAdaptiveInfo
import com.tvflyshare.app.uiconfig.global.WindowWidthClass
import com.tvflyshare.app.util.FileVisualUtils
import com.tvflyshare.app.util.StoragePermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 本机文件条目（相对本机共享根目录） */
data class LocalFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val relativePath: String,
)

/**
 * 「发送本机文件到对方」对话框：浏览本机共享目录、勾选文件、推送到对端当前浏览目录。
 *
 * 发送走 [RemoteBrowseViewModel.sendFilesToDevice]（对端 /api/upload octet-stream 流式上传），
 * 进度在对话框内展示；完成后 Toast 结果并关闭。
 */
@Composable
fun SendFilesDialog(
    remoteDir: String,
    onDismiss: () -> Unit,
    viewModel: RemoteBrowseViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isCompact = rememberWindowAdaptiveInfo().widthClass == WindowWidthClass.COMPACT

    var localPath by remember { mutableStateOf("") } // 相对本机共享根目录
    var entries by remember { mutableStateOf<List<LocalFileEntry>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    var isSending by remember { mutableStateOf(false) }
    var sendLine by remember { mutableStateOf("") }
    var sendFileProgress by remember { mutableStateOf(0f) }
    var sendSpeed by remember { mutableStateOf(0L) }

    LaunchedEffect(localPath) {
        val list = withContext(Dispatchers.IO) {
            val root = StoragePermissionUtils.getStorageDir(context)
            val dir = if (localPath.isEmpty()) root else File(root, localPath)
            dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.map {
                    LocalFileEntry(
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.length(),
                        relativePath = if (localPath.isEmpty()) it.name else "$localPath/${it.name}",
                    )
                }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }
        entries = list
    }

    // 2026-10-10 审计修复：改用 Dialog 独立 Window 隔离 TV 遥控器焦点（对齐 SmbDirPickerDialog 范式）——
    // 原先 Box overlay 直接铺在 Compose 树里：方向键穿透操作背景的远端文件列表，
    // 系统返回键也不关对话框而是执行背景页的返回导航。dismissOnBackPress 一并修复返回键。
    // 发送中不允许返回键/点击外部关闭（此时 onDismiss 会遗弃进行中的发送，取消请用「取消发送」）。
    Dialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        TVBackground {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvTheme.colors.dialogScrim),
                contentAlignment = Alignment.Center
            ) {
                Column(
            modifier = Modifier
                .then(if (isCompact) Modifier.fillMaxWidth().padding(horizontal = TvTheme.dimens.spacingLg) else Modifier.width(TvTheme.dimens.dialogWidthMd))
                .fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                .background(TvTheme.colors.dialogBg)
                .padding(TvTheme.dimens.spacingXl)
        ) {
            Text(
                text = stringResource(id = R.string.remote_browse_send_title),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontCol1Text,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
            Text(
                text = stringResource(id = R.string.remote_browse_send_target_dir, remoteDir.ifEmpty { "/" }),
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))

            if (isSending) {
                // 发送进度区
                Text(
                    text = sendLine,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontCol1Text
                )
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                LinearProgressIndicator(
                    progress = { sendFileProgress },
                    color = TvTheme.colors.brandPrimary,
                    trackColor = TvTheme.colors.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                Text(
                    text = if (sendSpeed > 0) {
                        "${(sendFileProgress * 100).toInt()}%  •  ${FileVisualUtils.formatFileSize(sendSpeed)}/s"
                    } else {
                        "${(sendFileProgress * 100).toInt()}%"
                    },
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontSmall
                )
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                // 2026-10-04 审计 P1 修复：发送中提供「取消发送」入口——原先整个进度区没有
                // 任何取消/关闭按钮，大文件发错只能等完或杀进程；cancelSend 后 onComplete
                // 不会回调（协程被取消），因此必须在此处自行复位 isSending
                val cancelSendInteraction = remember { MutableInteractionSource() }
                Text(
                    text = stringResource(id = R.string.remote_browse_send_cancel),
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontSmall,
                    modifier = Modifier
                        .tvFocusStyle(
                            interactionSource = cancelSendInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                            normalBgColor = TvTheme.colors.borderNormal,
                            focusedBgColor = TvTheme.colors.brandPrimary,
                            borderWidth = TvTheme.dimens.borderNormal
                        )
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                        .clickable(
                            interactionSource = cancelSendInteraction,
                            indication = null
                        ) {
                            viewModel.cancelSend()
                            isSending = false
                            sendFileProgress = 0f
                            sendSpeed = 0L
                            GlobalToastManager.showToast(
                                context.getString(R.string.remote_browse_send_cancelled)
                            )
                        }
                        .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                )
            } else {
                // 返回上级
                if (localPath.isNotEmpty()) {
                    val upInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = stringResource(id = R.string.remote_browse_send_up),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = upInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                normalBgColor = TvTheme.colors.borderNormal,
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderNormal
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .clickable(
                                interactionSource = upInteraction,
                                indication = null
                            ) {
                                localPath = if (localPath.contains('/')) {
                                    localPath.substringBeforeLast('/')
                                } else {
                                    ""
                                }
                            }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                    )
                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                }

                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(id = R.string.remote_browse_empty_dir),
                            color = TvTheme.colors.textSecondary,
                            fontSize = TvTheme.dimens.fontCol1Text
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingSm)
                    ) {
                        items(entries, key = { it.relativePath }) { entry ->
                            val interactionSource = remember { MutableInteractionSource() }
                            val isSelected = entry.relativePath in selectedPaths
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusStyle(
                                        interactionSource = interactionSource,
                                        shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                        normalBgColor = if (isSelected) TvTheme.colors.brandPrimary.copy(alpha = 0.15f) else TvTheme.colors.surface,
                                        focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                                        borderWidth = TvTheme.dimens.borderNormal
                                    )
                                    .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) {
                                        if (entry.isDirectory) {
                                            localPath = entry.relativePath
                                            selectedPaths = selectedPaths.filterNot { it.startsWith("${entry.relativePath}/") }.toSet()
                                        } else {
                                            selectedPaths = if (isSelected) {
                                                selectedPaths - entry.relativePath
                                            } else {
                                                selectedPaths + entry.relativePath
                                            }
                                        }
                                    }
                                    .padding(
                                        horizontal = TvTheme.dimens.spacingMd,
                                        vertical = TvTheme.dimens.spacingSm
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = TvTheme.colors.brandPrimary,
                                    modifier = Modifier.width(TvTheme.dimens.iconSizeLg)
                                )
                                Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                                Text(
                                    text = entry.name,
                                    color = TvTheme.colors.textPrimary,
                                    fontSize = TvTheme.dimens.fontSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        text = FileVisualUtils.formatFileSize(entry.size),
                                        color = TvTheme.colors.textSecondary,
                                        fontSize = TvTheme.dimens.fontSmall
                                    )
                                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                                    TvCheckbox(checked = isSelected)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    val cancelInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = stringResource(id = R.string.btn_cancel),
                        color = TvTheme.colors.textSecondary,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = cancelInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                normalBgColor = TvTheme.colors.borderNormal,
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderNormal
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .clickable(interactionSource = cancelInteraction, indication = null) { onDismiss() }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                    )
                    Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
                    val sendInteraction = remember { MutableInteractionSource() }
                    val hasSelection = selectedPaths.isNotEmpty()
                    Text(
                        text = if (hasSelection) {
                            stringResource(id = R.string.remote_browse_send_btn_count, selectedPaths.size)
                        } else {
                            stringResource(id = R.string.remote_browse_btn_send)
                        },
                        color = if (!hasSelection) TvTheme.colors.textSecondary else TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier
                            .tvFocusStyle(
                                interactionSource = sendInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusSm),
                                normalBgColor = TvTheme.colors.borderNormal,
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                borderWidth = TvTheme.dimens.borderNormal
                            )
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                            .clickable(
                                enabled = hasSelection,
                                interactionSource = sendInteraction,
                                indication = null
                            ) {
                                val root = StoragePermissionUtils.getStorageDir(context)
                                val files = selectedPaths.map { File(root, it) }.filter { it.isFile }
                                if (files.isNotEmpty()) {
                                    isSending = true
                                    sendFileProgress = 0f
                                    sendSpeed = 0L
                                    sendLine = context.getString(R.string.remote_browse_send_sending, 1, files.size)
                                    viewModel.sendFilesToDevice(
                                        files = files,
                                        remoteDir = remoteDir,
                                        onProgress = { progress ->
                                            sendLine = context.getString(R.string.remote_browse_send_sending, progress.index, progress.total)
                                            sendFileProgress = progress.fileProgress
                                            sendSpeed = progress.speedBytesPerSec
                                        },
                                        onComplete = { ok, fail ->
                                            isSending = false
                                            coroutineScope.launch {
                                                GlobalToastManager.showToast(
                                                    context.getString(R.string.remote_browse_send_result, ok, fail)
                                                )
                                            }
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                            .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
                    )
                }
            }
        }
            }
        }
    }
}
