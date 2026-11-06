package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.components.TvButton

/**
 * 电视端通用的空状态/错误状态占位图组件
 * 包含图标、标题、副标题，以及可选的重试/操作按钮
 *
 * @param focusRequester 可选。传入后将其绑定到操作按钮，便于在错误态出现时
 *   由调用方通过 [androidx.compose.runtime.LaunchedEffect] 自动请求焦点（电视端遥控直达重试）。
 */
@Composable
fun TvEmptyState(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.ErrorOutline,
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(TvTheme.dimens.spacing2Xl)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(TvTheme.dimens.spacing3Xl),
                tint = TvTheme.colors.textSecondary
            )
            
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
            
            Text(
                text = title,
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacingSm))
                Text(
                    text = subtitle,
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontBody,
                    textAlign = TextAlign.Center
                )
            }
            
            if (actionText != null && onActionClick != null) {
                Spacer(modifier = Modifier.height(TvTheme.dimens.spacing2Xl))
                TvButton(
                    text = actionText,
                    onClick = onActionClick,
                    normalBgColor = TvTheme.colors.brandPrimary,
                    textColor = TvTheme.colors.textPrimary,
                    modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
                )
            }
        }
    }
}
