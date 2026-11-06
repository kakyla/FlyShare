package com.tvflyshare.app.uiconfig.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.tvflyshare.app.uiconfig.global.TvTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局 Toast 状态管理器
 */
object GlobalToastManager {
    private val _messageFlow = MutableStateFlow<String?>(null)
    val messageFlow: StateFlow<String?> = _messageFlow.asStateFlow()

    // 自有协程作用域：Toast 的自动消失不依赖调用方生命周期。
    // 原实现为 suspend fun，delay 挂在调用方协程上，离开页面（如互联页）时调用方协程被取消，
    // messageFlow 永不清空，导致 Toast 卡死、退出页面仍在。改为自有 scope 后彻底根治。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dismissJob: Job? = null

    fun showToast(message: String, durationMillis: Long = 2000L) {
        dismissJob?.cancel()
        _messageFlow.value = message
        dismissJob = scope.launch {
            delay(durationMillis)
            if (_messageFlow.value == message) {
                _messageFlow.value = null
            }
        }
    }

    fun clearToast() {
        dismissJob?.cancel()
        _messageFlow.value = null
    }
}

/**
 * 全局 Toast 宿主组件
 * 应该被放置在导航栈（NavHost）的同级或外层，以确保其始终在最顶层显示
 */
@Composable
fun GlobalToastHost() {
    val message by GlobalToastManager.messageFlow.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            message?.let { msg ->
                Text(
                    text = msg,
                    color = TvTheme.colors.textPrimary,
                    fontSize = TvTheme.dimens.fontCol2Title,
                    modifier = Modifier
                        .padding(bottom = TvTheme.dimens.spacing2Xl)
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .background(TvTheme.colors.dialogBg)
                        .padding(horizontal = TvTheme.dimens.spacingXl, vertical = TvTheme.dimens.spacingMd)
                )
            }
        }
    }
}
