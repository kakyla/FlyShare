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
 * 更新源地址由 BuildConfig.UPDATE_JSON_URL 注入，不写死在源码里；正式发布走 GitHub / Gitee Releases。
 * 拉取用 HttpURLConnection（不带 Referer），下载 APK 走系统 DownloadManager，两者都跟随 302 重定向。
 */
class UpdateRepository {

    companion object {
        // 更新配置地址由 BuildConfig 注入（来自本地 gradle.properties 的 UPDATE_JSON_URL，不进公开仓库；
        // 开源默认走 GitHub Releases latest/download 模板，fork 自动获得自托管升级）
        private val UPDATE_JSON_URL = BuildConfig.UPDATE_JSON_URL
        // HTTP 连接/读取超时（毫秒）
        private const val HTTP_TIMEOUT_MS = 5000
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    suspend fun fetchUpdateInfo(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(UPDATE_JSON_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS

            if (connection.responseCode == 200) {
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
                Result.success(info)
            } else {
                Result.failure(AppException.NetworkException(cause = Exception("HTTP ${connection.responseCode}")))
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("UpdateRepository", "Failed to fetch update info", AppException.fromThrowable(e))
            Result.failure(AppException.fromThrowable(e))
        } finally {
            // 2026-10-04 审计 P2 修复：原先 connection 从不 disconnect，连接靠 GC 回收
            connection?.disconnect()
        }
    }
}