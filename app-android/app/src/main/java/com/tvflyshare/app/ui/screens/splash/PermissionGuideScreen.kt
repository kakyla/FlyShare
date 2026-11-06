package com.tvflyshare.app.ui.screens.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.util.rememberTvStoragePermissionState

@Composable
fun PermissionGuideScreen(
    onPermissionGranted: () -> Unit
) {
    val permissionState = rememberTvStoragePermissionState(
        onResult = { granted ->
            if (granted) {
                onPermissionGranted()
            }
        }
    )

    // 页面加载完成后，立刻主动拉起原生权限弹窗
    LaunchedEffect(Unit) {
        if (permissionState.isGranted) {
            onPermissionGranted()
        } else {
            permissionState.launchRequest()
        }
    }

    if (permissionState.isGranted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TvTheme.colors.bgGradientBottom)
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvTheme.colors.bgGradientBottom),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "App Logo",
                tint = TvTheme.colors.textPrimary,
                modifier = Modifier.size(TvTheme.dimens.appIconSize)
            )
            
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))
            
            Text(
                text = stringResource(id = R.string.permission_welcome, stringResource(id = R.string.app_name)),
                color = TvTheme.colors.textPrimary,
                fontSize = TvTheme.dimens.fontTitle,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))
            
            Text(
                text = stringResource(id = R.string.permission_desc),
                color = TvTheme.colors.textSecondary,
                fontSize = TvTheme.dimens.fontCaption,
                lineHeight = TvTheme.dimens.fontTitle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(TvTheme.dimens.spacing2Xl))
            
            // 给极少数拒绝了弹窗的用户留个“重试”按钮
            if (!permissionState.isGranted) {
                val retryInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .tvFocusStyle(
                            interactionSource = retryInteraction,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                            normalBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.8f),
                            focusedBgColor = TvTheme.colors.brandPrimary,
                            focusedBorderColor = TvTheme.colors.focusBorder,
                            normalBorderColor = TvTheme.colors.borderNormal,
                            borderWidth = TvTheme.dimens.borderFocused
                        )
                        .clickable(
                            interactionSource = retryInteraction,
                            indication = null
                        ) {
                            permissionState.launchRequest()
                        }
                        .padding(horizontal = TvTheme.dimens.spacingXl, vertical = TvTheme.dimens.spacingMd),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(id = R.string.permission_retry),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontCol1Text,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}