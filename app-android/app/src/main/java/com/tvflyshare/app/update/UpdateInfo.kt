package com.tvflyshare.app.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val updateLog: String,
    val apkUrl: String,
    // 2026-10-04 审计 P1 修复：安装包 SHA-256（可选，兼容旧 update.json）——
    // 存在时安装前强制校验，堵住"明文 HTTP 下载可被中间人替换 APK"的缺口
    val apkSha256: String? = null
)