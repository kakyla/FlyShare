package com.tvflyshare.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isAllowedRequestHeaders] / [isAllowedHostHeader] / [isAllowedOriginHeader] 回归测试（纯 JVM，无 Android 依赖）。
 *
 * 为什么单独上测试：这是「只认 IP 门牌」这条防线的全部逻辑，判据一旦退化成
 * 「非空就放行」或误用会触发 DNS 解析的方法，DNS 重绑定就重新成立，而正常使用时
 * 完全看不出异常（坏网页只是安静地多了一条路）。正常访问路径必须同时被测到，
 * 防止加固顺手把扫码/输 IP 的用法挡掉。
 */
class HostSafetyTest {

    @Test
    fun 扫码与手动输IP的门牌放行() {
        assertTrue(isAllowedHostHeader("192.168.1.5:5566"))
        assertTrue(isAllowedHostHeader("10.0.0.7:5566"))
        assertTrue(isAllowedHostHeader("172.16.3.9"))
        assertTrue(isAllowedHostHeader("192.168.1.5:5566 ".trim()))
    }

    @Test
    fun 本机回环与IPv6字面量放行() {
        assertTrue(isAllowedHostHeader("localhost:5566"))
        assertTrue(isAllowedHostHeader("LOCALHOST:5566"))
        assertTrue(isAllowedHostHeader("127.0.0.1:5566"))
        assertTrue(isAllowedHostHeader("[::1]:5566"))
        assertTrue(isAllowedHostHeader("::1"))
        assertTrue(isAllowedHostHeader("fe80::1"))
        assertTrue(isAllowedHostHeader("::ffff:192.168.1.5"))
    }

    @Test
    fun 域名门牌一律拒绝_这正是DNS重绑定的形态() {
        assertFalse(isAllowedHostHeader("evil.com"))
        assertFalse(isAllowedHostHeader("attack.evil.com:5566"))
        assertFalse(isAllowedHostHeader("tv.local:5566"))
        assertFalse(isAllowedHostHeader("EVIL.COM"))
        assertFalse(isAllowedHostHeader("192.168.1.5.evil.com:5566"))
    }

    @Test
    fun 空值与畸形门牌拒绝() {
        assertFalse(isAllowedHostHeader(null))
        assertFalse(isAllowedHostHeader(""))
        assertFalse(isAllowedHostHeader("   "))
        assertFalse(isAllowedHostHeader("192.168.1.5:abc"))
        assertFalse(isAllowedHostHeader("192.168.1.5:"))
        assertFalse(isAllowedHostHeader("[::1"))
        assertFalse(isAllowedHostHeader("[::1]:abc"))
        assertFalse(isAllowedHostHeader("999.1.1.1"))
        assertFalse(isAllowedHostHeader("192.168.1"))
        assertFalse(isAllowedHostHeader("192.168.1.5.6"))
    }

    @Test
    fun 同源Origin放行_异源与null字面量拒绝() {
        val host = "192.168.1.5:5566"
        assertTrue(isAllowedOriginHeader(null, host))
        assertTrue(isAllowedOriginHeader("http://192.168.1.5:5566", host))
        assertTrue(isAllowedOriginHeader("http://192.168.1.5:5566/", host))
        assertTrue(isAllowedOriginHeader("HTTP://192.168.1.5:5566", host))
        assertFalse(isAllowedOriginHeader("https://evil.com", host))
        assertFalse(isAllowedOriginHeader("http://evil.com", host))
        assertFalse(isAllowedOriginHeader("null", host))
        assertFalse(isAllowedOriginHeader("http://192.168.1.6:5566", host))
        assertFalse(isAllowedOriginHeader("http://192.168.1.5:5566", null))
    }

    @Test
    fun 请求头名大小写不敏感兜底() {
        assertTrue(isAllowedHostHeader(httpHeader(mapOf("Host" to "192.168.1.5:5566"), "host")))
        assertTrue(isAllowedHostHeader(httpHeader(mapOf("HOST" to "127.0.0.1:5566"), "host")))
        assertTrue(isAllowedHostHeader(httpHeader(mapOf("host" to "192.168.1.5:5566"), "host")))
        assertFalse(isAllowedHostHeader(httpHeader(mapOf("Host" to "evil.com:5566"), "host")))
        assertTrue(isAllowedOriginHeader(httpHeader(mapOf("Origin" to "http://192.168.1.5:5566"), "origin"), "192.168.1.5:5566"))
    }

    @Test
    fun 整包请求头判据_正常用法放行坏网页拒绝() {
        // 扫码 / 手动输 IP 打开页面：浏览器 GET 不带 Origin
        assertTrue(isAllowedRequestHeaders(mapOf("host" to "192.168.1.5:5566")))
        // 网页端上传 / 删除：带同源 Origin
        assertTrue(isAllowedRequestHeaders(mapOf(
            "host" to "192.168.1.5:5566",
            "origin" to "http://192.168.1.5:5566"
        )))
        // 设备互传 / 本机回环：HttpURLConnection 不带 Origin
        assertTrue(isAllowedRequestHeaders(mapOf("host" to "127.0.0.1:5566")))
        // DNS 重绑定：门牌仍是攻击者域名
        assertFalse(isAllowedRequestHeaders(mapOf("host" to "attack.evil.com:5566")))
        // 门牌对得上、但请求来自坏网页（跨站伪造）
        assertFalse(isAllowedRequestHeaders(mapOf(
            "host" to "192.168.1.5:5566",
            "origin" to "https://evil.com"
        )))
        // 连 Host 都没有的畸形请求
        assertFalse(isAllowedRequestHeaders(emptyMap()))
    }
}
