package com.tvflyshare.app.network

import android.util.Log
import com.tvflyshare.app.httpserver.HttpServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本机 HTTP 客户端工具
 *
 * 封装 HttpURLConnection 样板代码，供 TV 端调用本机 5566 端口服务。
 * 不引入额外网络库，只用 JDK 自带 HttpURLConnection，稳定可靠。
 *
 * 统一管理：端口、超时、连接/断开、异常处理，避免各处重复编写。
 */
object LocalHttpClient {

    private const val HOST = "127.0.0.1"
    private const val TIMEOUT_MS = 3000

    /**
     * GET 请求
     *
     * @param path 请求路径，如 "/api/files"
     * @return HttpResponse（含状态码和响应体）；连接失败返回 null
     */
    suspend fun get(path: String): HttpResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(path, "GET")
                try {
                    val code = conn.responseCode
                    val body = if (code == 200) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else null
                    HttpResponse(code, body)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("LocalHttpClient", "GET 请求失败: $path", e)
                null
            }
        }
    }

    /**
     * DELETE 请求
     *
     * @param path 请求路径，如 "/api/files/test.jpg"
     * @return HTTP 状态码；连接失败返回 -1
     */
    suspend fun delete(path: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                val conn = openConnection(path, "DELETE")
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("LocalHttpClient", "DELETE 请求失败: $path", e)
                -1
            }
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val url = URL("http://$HOST:${HttpServerManager.PORT}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
    }
}

/** HTTP 响应结果 */
data class HttpResponse(
    val code: Int,
    val body: String?,
)
