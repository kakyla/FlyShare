package com.tvflyshare.app.uiconfig.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.global.applyTvDialogBlurEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvAlertDialog(
    title: String,
    text: String? = null,
    content: @Composable (() -> Unit)? = null,
    confirmText: String = stringResource(id = R.string.btn_confirm),
    dismissText: String? = stringResource(id = R.string.btn_cancel),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    isDanger: Boolean = false,
    defaultFocusCancel: Boolean = false,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        ),
        // 弹窗宽度：固定 dialogWidthLg（480dp），与 2.5.5 保持一致。
        // 手机上等于内容画布宽度、电视/平板上为居中定宽小窗，所有弹窗统一大小。
        modifier = Modifier.width(TvTheme.dimens.dialogWidthLg)
    ) {
        val view = LocalView.current
        // 对话框整体最大高度：抬到 85% 屏高，给 SMB/FTP 等字段较多的对话框留足空间，
        // 确保标题/内容/按钮全部显示、无需滚动（唯一允许滚动的只有更新对话框）。
        val maxDialogHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.85f).dp }
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.applyTvDialogBlurEffect()
        }

        Column(
            modifier = Modifier
                .width(TvTheme.dimens.dialogWidthLg)
                .heightIn(max = maxDialogHeight)
                .border(TvTheme.dimens.borderNormal, TvTheme.colors.borderNormal, RoundedCornerShape(TvTheme.dimens.radiusLg))
                .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
                .background(TvTheme.colors.dialogBg)
                .padding(TvTheme.dimens.spacingXl),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                color = if (isDanger) TvTheme.colors.brandError else TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))

            // 外层 Column 仅用 heightIn(max) 限制整体最大高度（0.85 屏高，纯兜底）；
            // 不挂滚动容器：所有对话框（含 SMB/FTP 连接框）内容均远低于上限，固定完整显示、不滚动。
            // 唯一允许滚动的对话框是 UpdateScreen 的更新对话框（独立自定义）。
            if (text != null) {
                Text(text, color = TvTheme.colors.textPrimary.copy(alpha = 0.9f), fontSize = TvTheme.dimens.fontCaption)
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
            }
            content?.invoke()

            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dismissText != null) {
                    val cancelInteraction = remember { MutableInteractionSource() }
                    val cancelFocusRequester = remember { FocusRequester() }
                    val cancelFocused by cancelInteraction.collectIsFocusedAsState()
                    // 高危确认默认聚焦「取消」（安全侧）：API22 焦点节点可能未挂载，包 try/catch 兜底
                    LaunchedEffect(defaultFocusCancel) {
                        if (defaultFocusCancel) {
                            try { cancelFocusRequester.requestFocus() } catch (e: IllegalStateException) {
                                // API22 焦点节点可能尚未挂载，忽略
                            }
                        }
                    }
                    val cancelBgColor = if (cancelFocused) TvTheme.colors.brandPrimary else TvTheme.colors.textPrimary.copy(alpha = 0.03f)
                    val cancelBorderColor = if (cancelFocused) TvTheme.colors.focusBorder else TvTheme.colors.borderNormal

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                            .background(cancelBgColor)
                            .border(TvTheme.dimens.borderFocused, cancelBorderColor, RoundedCornerShape(TvTheme.dimens.radiusMd))
                            .focusRequester(cancelFocusRequester)
                            .clickable(
                                interactionSource = cancelInteraction,
                                indication = null,
                                onClick = onDismiss
                            )
                            .tvFocusStyle(
                                interactionSource = cancelInteraction,
                                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                                focusedBgColor = TvTheme.colors.brandPrimary,
                                normalBgColor = TvTheme.colors.surfaceVariant,
                                focusedBorderColor = TvTheme.colors.focusBorder,
                                normalBorderColor = TvTheme.colors.borderNormal
                            )
                            .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.paddingBtnVertical),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(dismissText, color = TvTheme.colors.textPrimary.copy(alpha = 0.9f), fontSize = TvTheme.dimens.fontBody)
                    }
                }

                val confirmInteraction = remember { MutableInteractionSource() }
                val confirmFocused by confirmInteraction.collectIsFocusedAsState()
                val dangerBg = TvTheme.colors.brandError
                val bgColor = if (confirmEnabled) {
                    if (confirmFocused) (if (isDanger) dangerBg else TvTheme.colors.brandPrimary)
                    else (if (isDanger) dangerBg.copy(alpha = 0.2f) else TvTheme.colors.brandPrimary.copy(alpha = 0.2f))
                } else {
                    TvTheme.colors.textPrimary.copy(alpha = 0.1f)
                }
                val borderColor = if (confirmFocused && confirmEnabled) TvTheme.colors.focusBorder else TvTheme.colors.borderNormal

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .background(bgColor)
                        .border(TvTheme.dimens.borderFocused, borderColor, RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .clickable(
                            interactionSource = confirmInteraction,
                            indication = null,
                            enabled = confirmEnabled,
                            onClick = onConfirm
                        )
                        .tvFocusStyle(
                            interactionSource = confirmInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                            focusedBgColor = if (isDanger) dangerBg else TvTheme.colors.brandPrimary,
                            normalBgColor = TvTheme.colors.surfaceVariant,
                            focusedBorderColor = TvTheme.colors.focusBorder,
                            normalBorderColor = TvTheme.colors.borderNormal
                        )
                        .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.paddingBtnVertical),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmText, color = if (confirmEnabled) TvTheme.colors.textPrimary else TvTheme.colors.textSecondary, fontSize = TvTheme.dimens.fontBody, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
