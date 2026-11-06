package com.tvflyshare.app.ui.screens.network

import android.content.Context
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import com.tvflyshare.app.uiconfig.components.TvTextField
import com.tvflyshare.app.uiconfig.components.GlobalToastManager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.tvflyshare.app.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvflyshare.app.smb.SmbClientManager
import com.tvflyshare.app.smb.SmbConnectionState
import kotlinx.coroutines.launch
import com.tvflyshare.app.network.NetworkUtils

// Windows 非法字符（共享名校验用）
private val INVALID_SHARE_CHARS = Regex("""[\\/:*?"<>|]""")

/**
 * 把 smbj 抛出的原始异常消息翻译成用户能看懂的提示
 * （提取到文件顶层，避免在 Composable 内每次重组重建）
 */
private fun friendlyError(context: Context, raw: String): String = when {
    // 已在 SmbClientManager 中友好化的消息，直接透传
    raw.startsWith(context.getString(R.string.error_smb_resolve_hostname).substringBefore("'"), ignoreCase = true) -> raw
    raw.contains("STATUS_LOGON_FAILURE", ignoreCase = true) -> context.getString(R.string.error_smb_logon_failure)
    raw.contains("STATUS_BAD_NETWORK_NAME", ignoreCase = true) -> context.getString(R.string.error_smb_bad_network_name)
    raw.contains("STATUS_ACCESS_DENIED", ignoreCase = true) -> context.getString(R.string.error_smb_access_denied)
    raw.contains("STATUS_LOGON_TYPE_NOT_GRANTED", ignoreCase = true) -> context.getString(R.string.error_smb_access_denied) // Reuse
    raw.contains("STATUS_NO_LOGON_SERVERS", ignoreCase = true) -> context.getString(R.string.error_smb_cannot_connect) // Reuse
    raw.contains("网络连接失败", ignoreCase = true) ||
        raw.contains("transport", ignoreCase = true) ||
        raw.contains("Connection refused", ignoreCase = true) ||
        raw.contains("timeout", ignoreCase = true) ||
        raw.contains("超时", ignoreCase = true) ||
        raw.contains("UnresolvedAddress", ignoreCase = true) ->
        context.getString(R.string.error_smb_cannot_connect)
    else -> context.getString(R.string.error_smb_generic_status, raw)
}

/**
 * SMB 连接对话框
 *
 * 使用 Dialog + 居中卡片，卡片自身深色不透明背景。
 * 不铺全屏遮罩，卡片外可见背景但不干扰对话框内容。
 *
 * 点"连接"时会先做一次试探连接验证：
 * - 成功：断开试探连接，回调 onConfirm（由上层保存+跳转浏览页，浏览页会重新连接）
 * - 失败：在对话框内显示具体错误原因（IP 不通/账号密码错/共享名不存在等），不保存不跳转
 */
@Composable
fun SmbConnectDialog(
    initialHost: String = "",
    initialShare: String = "",
    initialUser: String = "",
    initialPass: String = "",
    onConfirm: (host: String, shareName: String, username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var hostText by remember { mutableStateOf(initialHost) }
    var shareText by remember { mutableStateOf(initialShare) }
    var userText by remember { mutableStateOf(initialUser) }
    var passText by remember { mutableStateOf(initialPass) }
    // 输入校验错误（格式不对）
    var inputError by remember { mutableStateOf(false) }
    // 连接验证错误（连不上时的具体原因）
    var connectError by remember { mutableStateOf<String?>(null) }
    // 是否正在试探连接
    var isConnecting by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 试探连接用的临时 client，验证完即断开
    val testClient = remember { SmbClientManager() }
    // 2026-10-04 审计 P2 修复：试探连接原先硬编码 domain=""，与浏览页实际连接（读设置里的工作组）不一致——
    // 域/工作组环境的 NAS 会被误报"无法连接"。读取同一份设置保持两处行为一致。
    val workgroup = remember {
        (context.applicationContext as? com.tvflyshare.app.TVTransferApp)
            ?.settingsRepository?.getSmbWorkgroup() ?: ""
    }

    // 对话框销毁时确保断开试探连接，避免泄漏（close 是网络 IO，切 IO 线程防主线程阻塞）
    DisposableEffect(Unit) {
        onDispose {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                testClient.disconnect()
            }
        }
    }

    TvAlertDialog(
        title = stringResource(id = R.string.smb_connect_title),
        confirmText = if (isConnecting) stringResource(id = R.string.smb_connecting) else stringResource(id = R.string.smb_connect),
        dismissText = stringResource(id = R.string.btn_cancel),
        confirmEnabled = !isConnecting,
        onConfirm = {
            if (isConnecting) return@TvAlertDialog
            connectError = null
            inputError = false
            val host = hostText.trim()
            val share = shareText.trim()
            val user = userText.trim()
            val pass = passText.trim()

            if (!NetworkUtils.isValidHost(host) || share.isEmpty() || INVALID_SHARE_CHARS.containsMatchIn(share)) {
                inputError = true
                return@TvAlertDialog
            }

            isConnecting = true
            scope.launch {
                // 试探性连接，只 verify 不保留长连接（避免阻塞主页）
                val success = testClient.connect(host, share, user, pass, workgroup)
                if (success) {
                    isConnecting = false
                    onConfirm(host, share, user, pass)
                } else {
                    isConnecting = false
                    val msg = when (val state = testClient.state.value) {
                        is SmbConnectionState.Error -> friendlyError(context, state.message.asString(context))
                        else -> context.getString(R.string.smb_connect_failed)
                    }
                    connectError = msg
                    // 对话框内红字在 TV 上可能被滚动区裁掉而看不到，这里用全局 Toast 兜底，确保错误一定可见
                    GlobalToastManager.showToast(msg)
                }
            }
        },
        onDismiss = { if (!isConnecting) onDismiss() },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                TvTextField(
                    value = hostText,
                    onValueChange = { hostText = it; inputError = false; connectError = null },
                    label = { Text(stringResource(id = R.string.smb_host_label)) },
                    placeholder = { Text(stringResource(id = R.string.smb_host_placeholder)) },
                    singleLine = true,
                    isError = inputError,
                    enabled = !isConnecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

            TvTextField(
                value = shareText,
                onValueChange = { shareText = it; inputError = false; connectError = null },
                label = { Text(stringResource(id = R.string.smb_share_label)) },
                placeholder = { Text(stringResource(id = R.string.smb_share_placeholder)) },
                singleLine = true,
                isError = inputError,
                enabled = !isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TvTheme.dimens.spacingMd),
            )

            TvTextField(
                value = userText,
                onValueChange = { userText = it; connectError = null },
                label = { Text(stringResource(id = R.string.smb_user_label)) },
                placeholder = { Text(stringResource(id = R.string.smb_user_placeholder)) },
                singleLine = true,
                enabled = !isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TvTheme.dimens.spacingMd),
            )

            TvTextField(
                value = passText,
                onValueChange = { passText = it; connectError = null },
                label = { Text(stringResource(id = R.string.smb_pass_label)) },
                singleLine = true,
                enabled = !isConnecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TvTheme.dimens.spacingMd),
            )

            // 错误提示统一显示在底部（格式校验 / 连接验证）
            if (inputError) {
                Text(
                    text = stringResource(id = R.string.smb_input_error),
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
                    modifier = Modifier.padding(top = if (inputError) TvTheme.dimens.spacingXs else TvTheme.dimens.spacingMd),
                )
            }
            }
        }
    )
}
