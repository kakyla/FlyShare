package com.tvflyshare.app.update

import kotlinx.serialization.Serializable

private val APK_SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

/**
 * 更新配置里的安装指纹必须是 64 位十六进制；缺失或格式不对一律拒绝。
 * 更新源解析与下载入口共用这一把尺子，避免两处判断不一致留下绕过口子。
 */
fun isValidApkSha256(value: String?): Boolean =
    value != null && APK_SHA256_PATTERN.matches(value.trim())

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val updateLog: String,
    val apkUrl: String,
    // 安装包 SHA-256，**必填**（2026-11-08 审计修复）：更新源会校验存在性与 64 位十六进制格式，
    // 安装前用它比对实际下载文件，堵住"下载链路被替换 APK"的缺口。
    // 类型保留可空，是为了让解析层能针对缺字段给出明确失败，而不是在反序列化处直接抛异常；
    // 业务上缺失即视为配置不可用，绝不放行。
    val apkSha256: String? = null
)