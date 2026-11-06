package com.tvflyshare.app.ui.screens.update

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.*
import com.tvflyshare.app.update.UpdateState
import com.tvflyshare.app.update.UpdateUiEvent
import com.tvflyshare.app.update.UpdateViewModel

@Composable
fun UpdateScreen(
    onBack: () -> Unit,
    viewModel: UpdateViewModel = viewModel()
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    // 修复技术债2: 在下载过程中拦截返回键，防止意外退出导致 ViewModel 被销毁，进而导致广播失效（下载完不安装）
    BackHandler(enabled = updateState is UpdateState.Downloading) {
        // 下载中忽略返回操作
    }

    LaunchedEffect(Unit) {
        viewModel.onEvent(UpdateUiEvent.CheckUpdate)
    }

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.home_check_update),
            // 2026-10-04 审计 P1 修复：下载中隐藏返回箭头——BackHandler 只拦系统返回键，
            // 点箭头会销毁 ViewModel 注销广播，"下载完自动安装"失效
            showBack = updateState !is UpdateState.Downloading,
            onBack = onBack,
            scrollable = false,
        ) { windowInfo ->
            val isCompact = windowInfo.widthClass == WindowWidthClass.COMPACT
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = updateState) {
                        is UpdateState.Idle, is UpdateState.Checking -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = TvTheme.colors.brandPrimary, modifier = Modifier.size(TvTheme.dimens.iconSizeXl))
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                Text(text = stringResource(id = R.string.checking_for_updates), color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody)
                            }
                        }
                        is UpdateState.NoUpdate -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = TvTheme.colors.brandSuccess,
                                    modifier = Modifier.size(TvTheme.dimens.spacing3Xl)
                                )
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                Text(text = state.message, color = TvTheme.colors.textPrimary, fontSize = TvTheme.dimens.fontTitle, fontWeight = FontWeight.Medium)
                            }
                        }
                        is UpdateState.HasUpdate -> {
                            val scrollState = rememberScrollState()
                            // 全屏遮罩：让更新提示成为"固定窗口"的模态层（修复反馈：窗口不固定）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(TvTheme.colors.dialogScrim),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier
                                        .then(if (isCompact) Modifier.fillMaxWidth().padding(horizontal = TvTheme.dimens.spacingLg) else Modifier.width(TvTheme.dimens.dialogWidthLg))
                                        .fillMaxHeight(0.85f)
                                        .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                                        .background(TvTheme.colors.dialogBg)
                                        .border(TvTheme.dimens.borderNormal, TvTheme.colors.borderNormal, RoundedCornerShape(TvTheme.dimens.radiusLg))
                                        .padding(TvTheme.dimens.spacingXl),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = TvTheme.colors.brandPrimary,
                                        modifier = Modifier.size(TvTheme.dimens.spacing3Xl)
                                    )
                                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                    Text(
                                        text = "${stringResource(id = R.string.new_version_found)}${state.info.versionName}",
                                        color = TvTheme.colors.textPrimary,
                                        fontSize = TvTheme.dimens.fontTitle,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                    // 更新内容区：可聚焦 + 垂直滚动。
                                    // fill=false + weight 固定其高度上限，focusable 让遥控器方向键可滚动长文本（修复反馈：内容不能滚动）
                                    Column(
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .fillMaxWidth()
                                            .verticalScroll(scrollState)
                                            .focusable()
                                    ) {
                                        Text(
                                            text = state.info.updateLog,
                                            color = TvTheme.colors.textSecondary,
                                            fontSize = TvTheme.dimens.fontBody,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXl))
                                Row(horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg)) {
                                    val updateFocusRequester = remember { FocusRequester() }
                                    LaunchedEffect(Unit) {
                                        try {
                                            updateFocusRequester.requestFocus()
                                        } catch (e: IllegalStateException) {
                                            Log.w("UpdateScreen", "requestFocus 跳过：焦点节点尚未挂载", e)
                                        }
                                    }
                                    UpdateBtn(
                                        text = stringResource(id = R.string.later),
                                        isPrimary = false,
                                        onClick = onBack
                                    )
                                    UpdateBtn(
                                        text = stringResource(id = R.string.update_now),
                                        isPrimary = true,
                                        modifier = Modifier.focusRequester(updateFocusRequester),
                                        onClick = { viewModel.onEvent(UpdateUiEvent.StartDownload(state.info.apkUrl, state.info.versionCode, state.info.apkSha256)) }
                                    )
                                }
                            }
                            }
                        }
                        is UpdateState.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .then(if (isCompact) Modifier.fillMaxWidth().padding(horizontal = TvTheme.dimens.spacingLg) else Modifier.width(TvTheme.dimens.dialogWidthLg))
                                    .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                                    .background(TvTheme.colors.dialogBg)
                                    .border(TvTheme.dimens.borderNormal, TvTheme.colors.borderNormal, RoundedCornerShape(TvTheme.dimens.radiusLg))
                                    .padding(TvTheme.dimens.spacingXl),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = stringResource(id = R.string.downloading_update), color = TvTheme.colors.textPrimary, fontSize = TvTheme.dimens.fontCol2Title, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(TvTheme.dimens.spacingSm)
                                        .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                        .background(TvTheme.colors.textSecondary.copy(alpha = 0.3f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(state.progress / 100f)
                                            .height(TvTheme.dimens.spacingSm)
                                            .clip(RoundedCornerShape(TvTheme.dimens.radiusSm))
                                            .background(TvTheme.colors.brandPrimary)
                                    )
                                }
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                // 2026-10-04 审计 P2 修复：断网/等待 Wi-Fi 时显示提示，替代无说明的冻结进度
                                if (state.waitingNetwork) {
                                    Text(
                                        text = stringResource(id = R.string.update_waiting_for_network),
                                        color = TvTheme.colors.brandError,
                                        fontSize = TvTheme.dimens.fontSmall
                                    )
                                    Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))
                                }
                                Text(text = "${state.progress}%", color = TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontSmall)
                            }
                        }
                        is UpdateState.DownloadComplete -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = TvTheme.colors.brandSuccess,
                                    modifier = Modifier.size(TvTheme.dimens.spacing3Xl)
                                )
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                Text(
                                    text = stringResource(id = R.string.update_download_complete),
                                    color = TvTheme.colors.textPrimary,
                                    fontSize = TvTheme.dimens.fontCol2Title,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                                val installFocusRequester = remember { FocusRequester() }
                                LaunchedEffect(Unit) {
                                    try {
                                        installFocusRequester.requestFocus()
                                    } catch (e: IllegalStateException) {
                                        Log.w("UpdateScreen", "requestFocus 跳过：焦点节点尚未挂载", e)
                                    }
                                }
                                UpdateBtn(
                                    text = stringResource(id = R.string.update_install_now),
                                    isPrimary = true,
                                    modifier = Modifier.focusRequester(installFocusRequester),
                                    onClick = { viewModel.onEvent(UpdateUiEvent.InstallApk) }
                                )
                            }
                        }
                        is UpdateState.Error -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = TvTheme.colors.brandError,
                                    modifier = Modifier.size(TvTheme.dimens.spacing3Xl)
                                )
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
                                Text(text = state.error, color = TvTheme.colors.textPrimary, fontSize = TvTheme.dimens.fontCol2Title, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
                                UpdateBtn(
                                    text = stringResource(id = R.string.retry),
                                    isPrimary = true,
                                    onClick = { viewModel.onEvent(UpdateUiEvent.CheckUpdate) }
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun UpdateBtn(
    text: String,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val textColor = if (isFocused) {
        TvTheme.colors.textPrimary
    } else {
        if (isPrimary) TvTheme.colors.textPrimary else TvTheme.colors.textSecondary
    }

    val boxModifier = modifier
        .tvFocusStyle(
            interactionSource = interactionSource,
            shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
            focusedScale = 1.05f,
            normalBgColor = if (isPrimary) TvTheme.colors.brandPrimary.copy(alpha = 0.2f) else TvTheme.colors.borderNormal,
            focusedBgColor = TvTheme.colors.brandPrimary,
            borderWidth = TvTheme.dimens.borderFocused
        )
        .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
        .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingMd)

    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontSize = TvTheme.dimens.fontCaption, fontWeight = FontWeight.Medium)
    }
}
