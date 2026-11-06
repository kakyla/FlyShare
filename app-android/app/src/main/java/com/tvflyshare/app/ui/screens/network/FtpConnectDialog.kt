package com.tvflyshare.app.ui.screens.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import com.tvflyshare.app.uiconfig.components.TvTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.tvflyshare.app.R
import com.tvflyshare.app.ftp.FtpClientManager
import com.tvflyshare.app.ftp.FtpConnectionState
import com.tvflyshare.app.network.NetworkUtils
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvSwitch
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.global.TvTheme
import kotlinx.coroutines.launch

/**
 * FTP 连接对话框
 *
 * 字段：host + port（默认 21）+ user + pass，并提供「匿名登录」滑动开关。
 * 点"连接"时先试探连接验证，成功才回调 onConfirm；失败在对话框内显示原因。
 *
 * 与 SMB 对话框的差异：
 * - 多了 port 字段（FTP 必须明确端口）
 * - 用滑动开关（TvSwitch）切换匿名登录，匿名时禁用账号密码输入
 */
@Composable
fun FtpConnectDialog(
    initialHost: String = "",
    initialPort: Int = 21,
    initialUser: String = "",
    initialPass: String = "",
    initialAnonymous: Boolean = false,
    onConfirm: (host: String, port: Int, username: String, password: String, anonymous: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var hostText by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(if (initialPort == 21) "" else initialPort.toString()) }
    var userText by remember { mutableStateOf(initialUser) }
    var passText by remember { mutableStateOf(initialPass) }
    var anonymous by remember { mutableStateOf(initialAnonymous) }
    var inputError by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val testClient = remember { FtpClientManager() }

    DisposableEffect(Unit) {
        // 2026-10-04 审计 P2 修复：disconnect 内含 logout() 网络写，onDispose 在主线程回调——切 IO 收尾
        onDispose {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                testClient.disconnect()
            }
        }
    }

    TvAlertDialog(
        title = stringResource(id = R.string.ftp_connect_title),
        confirmText = if (isConnecting) stringResource(id = R.string.ftp_connecting) else stringResource(id = R.string.ftp_connect),
        dismissText = stringResource(id = R.string.btn_cancel),
        confirmEnabled = !isConnecting,
        onConfirm = {
            if (isConnecting) return@TvAlertDialog
            connectError = null
            inputError = false
            val host = hostText.trim()
            val port = portText.trim().toIntOrNull() ?: 21
            val user = userText.trim()
            val pass = passText.trim()

            if (!NetworkUtils.isValidHost(host) || port <= 0 || port > 65535) {
                inputError = true
                return@TvAlertDialog
            }

            isConnecting = true
            scope.launch {
                val success = testClient.connect(host, port, user, pass, anonymous)
                if (success) {
                    isConnecting = false
                    onConfirm(host, port, user, pass, anonymous)
                } else {
                    isConnecting = false
                    connectError = when (val state = testClient.state.value) {
                        is FtpConnectionState.Error -> state.getMessage(context)
                        else -> context.getString(R.string.ftp_connect_failed_fallback)
                    }
                }
            }
        },
        onDismiss = { if (!isConnecting) onDismiss() },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // IP/主机名 与端口放在同一行，节省对话框高度
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
                ) {
                    TvTextField(
                        value = hostText,
                        onValueChange = { hostText = it; inputError = false; connectError = null },
                        label = { Text(stringResource(id = R.string.ftp_host_label)) },
                        placeholder = { Text(stringResource(id = R.string.ftp_host_placeholder)) },
                        singleLine = true,
                        isError = inputError,
                        enabled = !isConnecting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(3f),
                    )

                    TvTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }; inputError = false; connectError = null },
                        label = { Text(stringResource(id = R.string.ftp_port_label)) },
                        placeholder = { Text(stringResource(id = R.string.ftp_port_placeholder)) },
                        singleLine = true,
                        isError = inputError,
                        enabled = !isConnecting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                    )
                }

                // 匿名登录开关
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = TvTheme.dimens.spacingMd)
                        .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
                        .tvFocusStyle(
                            interactionSource = interactionSource,
                            shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                            normalBgColor = TvTheme.colors.borderNormal,
                            focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.15f),
                            focusedBorderColor = TvTheme.colors.brandPrimary,
                            normalBorderColor = TvTheme.colors.borderNormal,
                            borderWidth = TvTheme.dimens.borderFocused,
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { anonymous = !anonymous }
                        )
                        .padding(vertical = TvTheme.dimens.spacingMd, horizontal = TvTheme.dimens.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(id = R.string.ftp_anonymous),
                        color = TvTheme.colors.textPrimary,
                        fontSize = TvTheme.dimens.fontCol1Text,
                        fontWeight = FontWeight.Medium,
                    )
                    TvSwitch(checked = anonymous)
                }

                TvTextField(
                    value = userText,
                    onValueChange = { userText = it; connectError = null },
                    label = { Text(stringResource(id = R.string.ftp_user_label)) },
                    placeholder = { Text(stringResource(id = R.string.ftp_user_placeholder)) },
                    singleLine = true,
                    enabled = !isConnecting && !anonymous,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = TvTheme.dimens.spacingMd),
                )

                TvTextField(
                    value = passText,
                    onValueChange = { passText = it; connectError = null },
                    label = { Text(stringResource(id = R.string.ftp_pass_label)) },
                    singleLine = true,
                    enabled = !isConnecting && !anonymous,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = TvTheme.dimens.spacingMd),
                )

                if (inputError) {
                    Text(
                        text = stringResource(id = R.string.ftp_input_error),
                        color = TvTheme.colors.brandError,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier.padding(top = TvTheme.dimens.spacingXs),
                    )
                }

                if (connectError != null) {
                    Text(
                        text = connectError ?: "",
                        color = TvTheme.colors.brandError,
                        fontSize = TvTheme.dimens.fontSmall,
                        modifier = Modifier.padding(top = TvTheme.dimens.spacingMd),
                    )
                }
            }
        }
    )
}
