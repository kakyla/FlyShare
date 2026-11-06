package com.tvflyshare.app.ui.screens.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.tvflyshare.app.uiconfig.components.TvAlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.tvflyshare.app.R
import com.tvflyshare.app.uiconfig.components.ScreenScaffold
import com.tvflyshare.app.uiconfig.components.TVBackground
import com.tvflyshare.app.uiconfig.components.tvFocusStyle
import com.tvflyshare.app.uiconfig.components.GlobalToastManager
import com.tvflyshare.app.uiconfig.global.TvTheme
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigateToAgreement: () -> Unit,
    onNavigateToPrivacy: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: context.getString(R.string.about_version_unknown)
        } catch (e: Exception) {
            context.getString(R.string.about_version_unknown)
        }
    }

    TVBackground {
        ScreenScaffold(
            title = stringResource(id = R.string.about_title),
            showBack = true,
            onBack = onBack,
        ) { windowInfo ->
            val isWide = windowInfo.isWideLayout
            // 单一排列路径：横屏横排 / 竖屏竖排只由 isWideLayout 决定，
            // 不再手写两套分支各自调权重；内容块（应用信息 / 链接）单一来源，杜绝"修一处漏一处"。
            // 左右内边距仅横屏生效，竖屏走自然高度 + 间距，行为与原版一致。
            // 注意：weight 是 RowScope 扩展，必须放在 Row 内部（见 AdaptiveRowColumn），不能在此处预置。
            val blockModifier = Modifier
                .then(if (isWide) Modifier.padding(horizontal = TvTheme.dimens.spacingXl) else Modifier)

            // 顺序由 AdaptiveRowColumn 决定（横屏链接左/信息右，竖屏信息上/链接下），此处只声明两个内容块。
            AdaptiveRowColumn(
                isWide = isWide,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = TvTheme.dimens.spacingLg),
                links = {
                    AboutLinksSection(
                        onNavigateToAgreement = onNavigateToAgreement,
                        onNavigateToPrivacy = onNavigateToPrivacy,
                        modifier = blockModifier,
                    )
                },
                info = {
                    AboutAppInfoSection(
                        versionName = versionName,
                        compact = !isWide,
                        modifier = blockModifier,
                    )
                },
            )
        }
    }
}

/**
 * 自适应行/列容器：宽屏(横)用 Row 横向并排，窄屏(竖)用 Column 纵向堆叠。
 * 横屏时两个内容块各用一个 weight(1f) 的 Box 等分宽度——weight 必须在 RowScope 内调用，
 * 因此放在本函数内部而非外部预置；整页排列只有这一个调用点，
 * 消除此前横竖两套手写分支带来的连修三次。
 */
@Composable
private fun AdaptiveRowColumn(
    isWide: Boolean,
    modifier: Modifier = Modifier,
    links: @Composable () -> Unit,
    info: @Composable () -> Unit,
) {
    if (isWide) {
        // 横屏：链接在左、信息在右，两个 Box 各 weight(1f) 等分宽度，整页水平居中
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { links() }
            Box(modifier = Modifier.weight(1f)) { info() }
        }
    } else {
        // 竖屏：信息在上、链接在下，自然高度 + 间距
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingLg),
        ) {
            info()
            links()
        }
    }
}

// ============================================================
// 关于页 — 应用信息区（图标 + 名称 + 版本 + 简介 + 联系方式）
// compact=true 为竖屏紧凑横排：图标与名称间距用 Md，省出纵向空间给链接项
// ============================================================

@Composable
private fun AboutAppInfoSection(
    versionName: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // compact 仅控制「图标」与「名称」之间的间距（竖屏紧凑用 Md、横屏宽松用 Lg），
    // 其余结构横竖共用，单一来源——此前 compact/!compact 两段近重复代码已合并，杜绝"修一处忘一处"。
    val headerSpacing = if (compact) TvTheme.dimens.spacingMd else TvTheme.dimens.spacingLg
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = "App Icon",
            modifier = Modifier
                .size(TvTheme.dimens.appIconSize)
                .clip(RoundedCornerShape(TvTheme.dimens.radiusLg))
        )

        Spacer(modifier = Modifier.height(headerSpacing))

        Text(
            text = context.getString(R.string.app_name),
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontTitle,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingXs))

        Text(
            text = "Version $versionName",
            color = TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontBody
        )

        // 简介与联系方式：两种形态共用
        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingLg))

        Text(
            text = stringResource(id = R.string.about_app_desc),
            color = TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontCol1Text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TvTheme.dimens.spacingMd)
        )

        Spacer(modifier = Modifier.height(TvTheme.dimens.spacingMd))

        val contactAuthor = stringResource(id = R.string.about_contact_author)
        val contactEmail = stringResource(id = R.string.about_contact_email)
        val qqGroupLabel = stringResource(id = R.string.about_qq_group)
        val qqGroupNumber = stringResource(id = R.string.about_qq_group_number)
        Text(
            text = "$contactAuthor\n邮箱：$contactEmail\n$qqGroupLabel：$qqGroupNumber",
            color = TvTheme.colors.textSecondary,
            fontSize = TvTheme.dimens.fontBody,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TvTheme.dimens.spacingMd, vertical = TvTheme.dimens.spacingXs)
        )
    }
}

// ============================================================
// 关于页 — 链接项区（官网/协议/隐私）
// ============================================================

@Composable
private fun AboutLinksSection(
    onNavigateToAgreement: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvTheme.dimens.spacingMd)
    ) {
        val noWebsiteMsg = stringResource(id = R.string.about_no_website)
        AboutLinkItem(
            title = stringResource(id = R.string.about_official_website),
            onClick = {
                coroutineScope.launch {
                    GlobalToastManager.showToast(noWebsiteMsg)
                }
            }
        )

        AboutLinkItem(
            title = stringResource(id = R.string.about_agreement),
            onClick = onNavigateToAgreement
        )

        AboutLinkItem(
            title = stringResource(id = R.string.about_privacy),
            onClick = onNavigateToPrivacy
        )
    }
}

@Composable
private fun AboutLinkItem(
    title: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.radiusMd),
                normalBgColor = TvTheme.colors.surface,
                focusedBgColor = TvTheme.colors.brandPrimary.copy(alpha = 0.2f),
                borderWidth = TvTheme.dimens.borderFocused,
                focusedBorderColor = TvTheme.colors.focusBorder
            )
            .clip(RoundedCornerShape(TvTheme.dimens.radiusMd))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = TvTheme.dimens.spacingLg, vertical = TvTheme.dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = TvTheme.colors.textPrimary,
            fontSize = TvTheme.dimens.fontCol1Text,
            fontWeight = FontWeight.Medium
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TvTheme.colors.textPrimary,
            modifier = Modifier.size(TvTheme.dimens.iconSizeSm)
        )
    }
}
