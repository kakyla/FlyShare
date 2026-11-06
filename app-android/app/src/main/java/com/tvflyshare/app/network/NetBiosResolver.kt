package com.tvflyshare.app.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * NetBIOS 名称解析器
 *
 * 作用：把局域网内的 NetBIOS 计算机名（如 "ZS-CWK004"）解析成 IP 地址。
 *
 * 为什么需要它：
 * - Android 系统默认不做 NetBIOS 名称解析（只走 DNS），所以 smbj 用计算机名连接会失败
 * - 通过向子网广播地址发送 NetBIOS Name Query（UDP 137），拥有该名字的设备会响应自己的 IP
 *
 * 原理：
 * 1. 算出子网广播地址（如 192.168.101.255）
 * 2. 向广播地址:137 发送 Name Query Request（查询类型 0x0020 = NB）
 * 3. 拥有该名字的设备响应，从响应包的源 IP 即可拿到目标 IP
 *
 * 局限：
 * - 关闭了 NetBIOS over TCP/IP 的设备不会响应 → 返回 null，调用方应回退提示用户用 IP
 * - 仅适用于 /24 子网（家庭网络常见场景）
 */
object NetBiosResolver {

    private const val TAG = "NetBiosResolver"

    /** NetBIOS Name Service 端口（UDP） */
    private const val NETBIOS_PORT = 137

    /** 查询超时（毫秒） */
    private const val RESOLVE_TIMEOUT_MS = 1500

    /**
     * 解析计算机名为 IP 地址
     *
     * @param name NetBIOS 计算机名（如 "ZS-CWK004"），大小写不敏感
     * @return 解析到的 IP（如 "192.168.101.38"），失败返回 null
     */
    fun resolve(name: String): String? {
        if (name.isBlank()) return null

        val broadcastAddr = getBroadcastAddress() ?: run {
            Log.w(TAG, "Cannot determine broadcast address, no network")
            return null
        }

        // NetBIOS 名长度上限 15 字符，超长截断（Windows 计算机名规范）
        val truncated = if (name.length > 15) name.take(15) else name
        // 2026-10-10 审计修复：Transaction ID 随机化——原先硬编码 1，子网内任何主机都能
        // 伪造 txnId=1 的响应包劫持解析结果（txnId 校验虽已做，但可预测等于没做）
        val expectedTxnId = (0..0xFFFF).random()
        val request = buildNameQueryRequest(truncated, expectedTxnId)

        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = RESOLVE_TIMEOUT_MS
                socket.broadcast = true

                val packet = DatagramPacket(
                    request, request.size,
                    broadcastAddr, NETBIOS_PORT,
                )
                socket.send(packet)

                val buffer = ByteArray(1024)
                // 2026-10-04 审计 P2 修复：原先收到任何 UDP 137 响应就把源 IP 当解析结果——
                // 不校验 Transaction ID / RCODE，负响应、名字冲突响应或无关设备的包都会被误采信。
                // 循环收取直到超时，只接受"本查询的合法正响应"（txnId=1、response 位、RCODE=0）。
                val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
                var resolvedIp: String? = null
                while (resolvedIp == null && System.currentTimeMillis() < deadline) {
                    socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    }
                    val data = response.data
                    if (response.length < 12) continue
                    val txnId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                    val isResponse = (flags and 0x8000) != 0
                    val rcode = flags and 0x000F
                    if (txnId != expectedTxnId || !isResponse || rcode != 0) continue
                    // 响应包的源地址即为目标主机 IP
                    resolvedIp = response.address.hostAddress
                }
                if (resolvedIp != null) {
                    Log.i(TAG, "Resolved '$name' -> $resolvedIp")
                }
                resolvedIp
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d(TAG, "Resolve '$name' failed: ${e.message}")
            null
        }
    }

    /**
     * 计算子网广播地址（如 192.168.101.255）
     *
     * 仅支持 /24 子网（家庭网络最常见的掩码）。
     */
    private fun getBroadcastAddress(): InetAddress? {
        val subnetBase = NetworkUtils.getSubnetBase() ?: return null
        return InetAddress.getByName("$subnetBase.255")
    }

    /**
     * 构造 NetBIOS Name Query 请求包
     *
     * 查询类型 0x0020 (NB)，广播给整个子网，让拥有该名字的设备响应。
     *
     * 包结构：
     *   Header (12) + Query Name (34) + Query Type (2) + Query Class (2)
     *
     * Name 编码：每个字符按 'A' + char/16, 'A' + char%16 转成两个半字节字符
     *   然后补空格（空格同样按上述规则编码）至 15 字符 = 30 字节
     *   再加后缀 0x00（Workstation Service）编码 = 2 字节，共 32 字节
     */
    private fun buildNameQueryRequest(name: String, txnId: Int): ByteArray {
        // 1. 把名字补空格到 15 字符，加后缀 0x00，共 16 字符
        val padded = name.uppercase().padEnd(15, ' ') + 0.toChar()
        // 2. 半字节编码：每字符 → 2 字节
        val encoded = ByteArray(32)
        for (i in 0 until 16) {
            val c = padded[i].code
            encoded[i * 2] = ('A'.code + (c shr 4)).toByte()
            encoded[i * 2 + 1] = ('A'.code + (c and 0x0F)).toByte()
        }

        // 3. 组装完整包
        val packet = ByteArray(12 + 1 + 32 + 1 + 2 + 2) // header + len + name + terminator + type + class
        var p = 0
        // Header
        packet[p++] = ((txnId shr 8) and 0xFF).toByte()  // Transaction ID（随机化）
        packet[p++] = (txnId and 0xFF).toByte()
        packet[p++] = 0x01; packet[p++] = 0x10  // Flags: 标准查询 + 广播
        packet[p++] = 0x00; packet[p++] = 0x01  // QDCOUNT = 1
        packet[p++] = 0x00; packet[p++] = 0x00  // ANCOUNT = 0
        packet[p++] = 0x00; packet[p++] = 0x00  // NSCOUNT = 0
        packet[p++] = 0x00; packet[p++] = 0x00  // ARCOUNT = 0
        // Query Name
        packet[p++] = 0x20  // Length = 32
        System.arraycopy(encoded, 0, packet, p, 32)
        p += 32
        packet[p++] = 0x00  // Name terminator
        // Query Type = NB (0x0020)
        packet[p++] = 0x00; packet[p++] = 0x20
        // Query Class = IN (0x0001)
        packet[p++] = 0x00; packet[p++] = 0x01
        return packet
    }
}
