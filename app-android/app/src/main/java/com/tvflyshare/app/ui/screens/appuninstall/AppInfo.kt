package com.tvflyshare.app.ui.screens.appuninstall

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: Drawable,
    val isSystem: Boolean = false,
    val sourceLabel: String = "", // "系统应用" / "用户安装"
)
