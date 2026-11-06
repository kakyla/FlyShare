package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 电视端统一输入框封装。
 * 内置 TV 主题配色（文字主色 / 品牌主色边框 / 次要色占位与未聚焦边框），
 * 避免各页面重复定义 OutlinedTextFieldDefaults.colors(...)。
 * 个别场景需特殊配色时，可传入 colors 覆盖默认值。
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TvTheme.colors.textPrimary,
        unfocusedTextColor = TvTheme.colors.textPrimary,
        disabledTextColor = TvTheme.colors.textSecondary,
        focusedContainerColor = TvTheme.colors.borderNormal,
        unfocusedContainerColor = TvTheme.colors.borderNormal,
        cursorColor = TvTheme.colors.brandPrimary,
        focusedBorderColor = TvTheme.colors.brandPrimary,
        unfocusedBorderColor = TvTheme.colors.textSecondary.copy(alpha = 0.5f),
        focusedLabelColor = TvTheme.colors.textPrimary,
        unfocusedLabelColor = TvTheme.colors.textPrimary,
        focusedPlaceholderColor = TvTheme.colors.textSecondary,
        unfocusedPlaceholderColor = TvTheme.colors.textSecondary,
    ),
    interactionSource: MutableInteractionSource? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        colors = colors,
        interactionSource = interactionSource,
    )
}
