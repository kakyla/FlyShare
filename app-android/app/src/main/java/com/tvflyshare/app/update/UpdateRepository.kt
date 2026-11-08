package com.tvflyshare.app.update

import android.util.Log
import com.tvflyshare.app.BuildConfig
import com.tvflyshare.app.util.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新仓库：负责与远程服务器交互，获取最新的更新配置。
 * 双源（2026-11-06 用户拍板「两个都结合，优先 Gitee」）：Gitee 优先（国内 TV 可达、仓库文件固定地址），
 * GitHub Releases 兜底；两者都由 BuildConfig 注入，源码里不写死私有地址。
 * 拉取用 HttpURLConnection（不带 Referer），下载 APK 走系统 DownloadManager，两者都跟随 302 重定向。
 */
class UpdateRepository {

    companion object {
        private const val TAG = "UpdateRepository"
        // 地址由 BuildConfig 注入（本机 gradle.properties 可覆盖；开源默认值公开）。
        // 过滤空串与重复项，避免某源未配置时发出无效请求。
        private val UPDATE_JSON_URLS = listOf(
            BuildConfig.UPDATE_JSON_URL,
            BuildConfig.UPDATE_JSON_URL_FALLBACK,
        ).filter { it.isNotBlank() }.distinct()
        // 单个更新源的 HTTP 连接/读取超时（毫秒）
        private const val HTTP_TIMEOUT_MS = 5000
        // 2026-11-08 审计修复：对外 README 承诺「按配置里的文件指纹校验后安装」，
        // 而旧实现里 apkSha256 缺失会直接跳过校验，承诺与代码不符。指纹现在是必需项。
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    suspend fun fetchUpdateInfo(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var success: Result<UpdateInfo?>? = null
        var lastError: Throwable? = null
        for (urlString in UPDATE_JSON_URLS) {
            if (success != null) break
            val attempt = fetchOne(urlString)
            if (attempt.isSuccess) {
                success = attempt
            } else {
                lastError = attempt.exceptionOrNull()
            }
        }
        success ?: Result.failure<UpdateInfo?>(
            AppException.NetworkException(cause = lastError ?: Exception("无可用更新源"))
        )
    }

    /** 拉单个更新源；失败只记录、不抛出，交给调用方尝试下一个地址。 */
    private fun fetchOne(urlString: String): Result<UpdateInfo?> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS

            if (connection.responseCode != 200) {
                Log.w(TAG, "更新源返回 HTTP ${connection.responseCode}：$urlString")
                Result.failure<UpdateInfo?>(
                    AppException.NetworkException(cause = Exception("HTTP ${connection.responseCode}"))
                )
            } else {
                var jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                // 剔除可能存在的 UTF-8 BOM 头，防止 JSON 解析崩溃
                if (jsonString.startsWith("\uFEFF")) {
                    jsonString = jsonString.substring(1)
                }
                var info = jsonFormat.decodeFromString<UpdateInfo>(jsonString)
                // APK 下载地址同样强制 HTTPS（2026-10-04 审计 P1 修复）
                if (info.apkUrl.startsWith("http://")) {
                    info = info.copy(apkUrl = "https://" + info.apkUrl.removePrefix("http://"))
                }
                // 必填校验：缺指纹或格式不对的配置一律视为不可用，自动尝试下一个更新源，
                // 绝不在「没有指纹」的情况下放行到下载/安装环节。
                val sha = info.apkSha256?.trim()
                if (sha == null || !isValidApkSha256(sha)) {
                    Log.w(TAG, "更新配置缺少合法的文件指纹：$urlString")
                    Result.failure<UpdateInfo?>(
                        AppException.fromThrowable(IllegalArgumentException("更新配置缺少合法的文件指纹"))
                    )
                } else {
                    Log.i(TAG, "更新配置来自 $urlString")
                    Result.success(info.copy(apkSha256 = sha.lowercase()))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "更新源不可达，尝试下一个：$urlString", e)
            Result.failure<UpdateInfo?>(AppException.fromThrowable(e))
        } finally {
            // 2026-10-04 审计 P2 修复：原先 connection 从不 disconnect，连接靠 GC 回收
            connection?.disconnect()
        }
    }
}
