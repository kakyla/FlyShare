package com.tvflyshare.app.ui.screens.about

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.global.TvTheme
import kotlinx.coroutines.launch

@Composable
fun PrivacyScreen(
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.about_privacy_title),
            showBack = true,
            onBack = onBack,
            scrollable = false,
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // 2026-10-04 审计 P1 修复：正文承载焦点 + 方向键驱动滚动（与 AgreementScreen 同规）
                val bodyInteraction = remember { MutableInteractionSource() }
                // 滚动步长在组合作用域取好（TvTheme.dimens 是 @Composable getter，不能在按键回调里读）
                val scrollStep = TvTheme.dimens.fontBody.value * 4f
                val bodyFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    try {
                        bodyFocusRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                        // 焦点节点未挂载时跳过
                    }
                }
                Text(
                    text = stringResource(id = R.string.about_privacy_content),
                    color = TvTheme.colors.textSecondary,
                    fontSize = TvTheme.dimens.fontBody,
                    lineHeight = TvTheme.dimens.lineHeightBody,
                    modifier = Modifier
                        .focusRequester(bodyFocusRequester)
                        .focusable(interactionSource = bodyInteraction)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                    scope.launch { scrollState.animateScrollBy(scrollStep) }
                                    true
                                }
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                    scope.launch { scrollState.animateScrollBy(-scrollStep) }
                                    true
                                }
                                else -> false
                            }
                        }
                )
            }
        }
    }
}
