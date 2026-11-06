package com.tvflyshare.app.uiconfig.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 统一的 TV 焦点样式 Modifier。
 * 集中管理电视端焦点元素的背景色、边框、缩放动画，解决各页面硬编码不一致的问题。
 * 
 * @param interactionSource 用于监听焦点状态
 * @param shape 组件的形状
 * @param focusedScale 获取焦点时的缩放比例
 * @param normalScale 失去焦点时的缩放比例
 * @param pressedScale 触控按下时的缩放比例（适配车机/手机端）
 * @param focusedBgColor 获取焦点时的背景色
 * @param normalBgColor 失去焦点时的背景色
 * @param pressedBgColor 触控按下时的背景色
 * @param focusedBorderColor 获取焦点时的边框颜色
 * @param normalBorderColor 失去焦点时的边框颜色
 * @param borderWidth 边框宽度
 * @param isSelected 当前是否为选中状态（如多选模式下的选中）
 * @param selectedBgColor 选中状态的背景色
 * @param selectedBorderColor 选中状态的边框颜色
 */
fun Modifier.tvFocusStyle(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    focusedScale: Float = 1.02f,
    normalScale: Float = 1f,
    pressedScale: Float = 0.98f,
    focusedBgColor: Color? = null,
    normalBgColor: Color? = null,
    pressedBgColor: Color? = null,
    focusedBorderColor: Color? = null,
    normalBorderColor: Color? = null,
    borderWidth: Dp? = null,
    isSelected: Boolean = false,
    selectedBgColor: Color? = null,
    selectedBorderColor: Color? = null
): Modifier = composed {
    val defaultBorderWidth = borderWidth ?: TvTheme.dimens.borderFocused
    val defaultFocusedBgColor = focusedBgColor ?: TvTheme.colors.surfaceHover
    val defaultNormalBgColor = normalBgColor ?: TvTheme.colors.surface
    // 按下时的背景色，默认比 hover 更深/更明显一些，这里复用 surfaceHover 的稍微不同透明度，或直接用 focusBorder 变透明
    val defaultPressedBgColor = pressedBgColor ?: TvTheme.colors.surfaceHover.copy(alpha = 0.5f)
    val defaultFocusedBorderColor = focusedBorderColor ?: TvTheme.colors.focusBorder
    val defaultSelectedBgColor = selectedBgColor ?: TvTheme.colors.brandSuccess.copy(alpha = 0.2f)
    val defaultSelectedBorderColor = selectedBorderColor ?: TvTheme.colors.brandSuccess
    val defaultNormalBorderColor = normalBorderColor ?: TvTheme.colors.borderNormal

    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val targetScale = when {
        isPressed -> pressedScale
        isFocused -> focusedScale
        else -> normalScale
    }

    val currentScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 150),
        label = "scale"
    )
    
    val effectiveBgColor = when {
        isPressed -> defaultPressedBgColor
        isSelected -> defaultSelectedBgColor
        isFocused -> defaultFocusedBgColor
        else -> defaultNormalBgColor
    }
    
    val effectiveBorderColor = when {
        isPressed -> defaultFocusedBorderColor // 按下时也保留焦点边框
        isSelected -> defaultSelectedBorderColor
        isFocused -> defaultFocusedBorderColor
        else -> defaultNormalBorderColor
    }

    this
        .scale(currentScale)
        .background(color = effectiveBgColor, shape = shape)
        .border(width = defaultBorderWidth, color = effectiveBorderColor, shape = shape)
}
