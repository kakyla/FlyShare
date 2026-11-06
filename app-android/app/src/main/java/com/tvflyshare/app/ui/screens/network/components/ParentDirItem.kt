package com.tvflyshare.app.ui.screens.network.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.tvflyshare.app.R
import androidx.compose.ui.text.style.TextOverflow
import com.tvflyshare.app.uiconfig.global.TvTheme
import com.tvflyshare.app.uiconfig.components.*
import com.tvflyshare.app.smb.SmbFileEntry
import com.tvflyshare.app.ui.viewmodel.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.tvflyshare.app.ui.screens.network.SmbAction

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ParentDirItem(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvTheme.dimens.listItemHeight)
            .tvFocusStyle(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(TvTheme.dimens.spacingSm),
                normalBgColor = TvTheme.colors.surface.copy(alpha=0.1f),
                borderWidth = TvTheme.dimens.borderNormal
            )
            .clip(RoundedCornerShape(TvTheme.dimens.spacingSm))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = TvTheme.dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            tint = TvTheme.colors.brandPrimary,
            modifier = Modifier.size(TvTheme.dimens.iconSizeXl)
        )
        Spacer(modifier = Modifier.width(TvTheme.dimens.spacingMd))
        Text(
            text = stringResource(id = R.string.smb_return_up_level),
            color = TvTheme.colors.brandPrimary,
            fontSize = TvTheme.dimens.fontCol2Title,
            fontWeight = FontWeight.Medium,
        )
    }
}