package com.tvflyshare.app.uiconfig.components

import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvflyshare.app.uiconfig.global.TvTheme

/**
 * 电视端统一的下拉操作菜单封装。
 * 内部统一应用 TV 主题表面色与文字主色，避免各页面重复内联 Material DropdownMenu + MaterialTheme。
 */
@Composable
fun TvDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = TvTheme.colors.bgGradientTop)
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.background(TvTheme.colors.bgGradientTop)
        ) {
            content()
        }
    }
}

/** 下拉菜单项：统一使用文字主色，避免每处重复 Text(color = ...) */
@Composable
fun TvDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenuItem(
        text = { Text(text = text, color = TvTheme.colors.textPrimary) },
        onClick = onClick,
        modifier = modifier
    )
}
