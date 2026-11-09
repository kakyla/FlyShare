package com.tvflyshare.app.util

/**
 * HTTP 请求「门牌」（Host / Origin）校验：只认 IP 字面量，拒绝一切域名。
 *
 * 背景：网页服务不鉴权，浏览器靠同源策略隔离外部页面；DNS 重绑定能把攻击者域名
 * 在页面加载后改指到本机/内网 IP，让浏览器以为仍在跟原网站说话，从而跨过同源限制
 * 调用这些接口。被重绑定的请求里 Host 仍然是攻击者那个域名——服务端只认 IP 字面量
 * 就能把这条路径整条掐断，用户不需要登录、也不需要多做任何一步。
 *
 * 本项目所有合法访问都是 `http://IP:端口`：二维码、页面显示的地址、设备互传、
 * 本机回环调用，全库没有域名或 mDNS 主机名访问路径，故这里不设域名白名单。
 *
 * 边界：这只挡「坏网页绕进来」，挡不住同一内网里直接输 IP 访问的人；后者需要准入
 * （登录），按 2026-11-07 的拍板不做。
 */

/** Host 头是否为本服务接受的门牌（IP 字面量或 localhost，可带端口）。 */
fun isAllowedHostHeader(rawHost: String?): Boolean {
    val host = rawHost?.trim()?.lowercase() ?: return false
    if (host.isEmpty()) return false
    val authority = stripPort(host) ?: return false
    return authority == "localhost" || isIpLiteral(authority)
}

/**
 * 带 Origin 的请求必须来自本服务自己；不带 Origin 的非浏览器调用方（本机回环、
 * 设备互传的 HttpURLConnection）放行。跨站伪造与坏网页的 fetch 都会带 Origin。
 */
fun isAllowedOriginHeader(origin: String?, rawHost: String?): Boolean {
    if (origin.isNullOrBlank()) return true
    val host = rawHost?.trim()?.lowercase() ?: return false
    val authority = origin.trim().lowercase()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
    return authority == host
}

/** NanoHTTPD 通常把头名转成小写，这里仍做大小写不敏感兜底，避免依赖库实现细节。 */
fun httpHeader(headers: Map<String, String>, name: String): String? =
    headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/**
 * 一次请求的门牌总判据。服务端只在唯一入口调用它，全部路由共用同一道关；
 * 抽成纯函数是为了让「放行什么、拦什么」整条决策都能在 JVM 单测里自证。
 */
fun isAllowedRequestHeaders(headers: Map<String, String>): Boolean {
    val host = httpHeader(headers, "host")
    return isAllowedHostHeader(host) &&
        isAllowedOriginHeader(httpHeader(headers, "origin"), host)
}

/** 剥掉 `:端口`；IPv6 的 `[...]` 括号形式单独处理。格式异常返回 null 表示拒绝。 */
private fun stripPort(host: String): String? {
    if (host.startsWith("[")) {
        val close = host.indexOf(']')
        if (close <= 1) return null
        val tail = host.substring(close + 1)
        if (tail.isNotEmpty() &&
            (!tail.startsWith(":") || tail.length < 2 || !tail.substring(1).all { it in '0'..'9' })
        ) return null
        return host.substring(1, close)
    }
    val firstColon = host.indexOf(':')
    if (firstColon < 0) return host
    // 多个冒号是裸 IPv6 字面量（浏览器不会为域名生成这种 Host），不带端口
    if (host.indexOf(':', firstColon + 1) >= 0) return host
    val port = host.substring(firstColon + 1)
    if (port.isEmpty() || !port.all { it in '0'..'9' }) return null
    return host.substring(0, firstColon)
}

/**
 * 纯字面量判据，绝不调用 InetAddress 之类会触发 DNS 解析的方法——
 * 那会把「只允许 IP」变成「允许解析到任意 IP 的域名」，正是要防的那件事。
 */
private fun isIpLiteral(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value.contains(':')) {
        if (value.count { it == ':' } < 2 || value.contains(":::")) return false
        // IPv6（含 IPv4 映射尾部）：只含十六进制数字、冒号、点
        return value.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }
    }
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && part.toInt() <= 255
    }
}
