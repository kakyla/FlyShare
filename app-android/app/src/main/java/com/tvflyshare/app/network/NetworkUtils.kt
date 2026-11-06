package com.tvflyshare.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络连接类型
 *
 * 用于 UI 根据 WiFi/有线显示不同图标。
 */
enum class NetworkType { WIFI, ETHERNET, CELLULAR, NONE }

/**
 * 网络信息工具类
 *
 * 获取本机 IP 地址和网络状态。
 * 兼容 WiFi 和以太网（TV Box 常用有线连接）。
 */
object NetworkUtils {

    /** 网络接口名优先级前缀：wlan > eth > en */
    private val prioritizedNames = listOf("wlan", "eth", "en")

    /**
     * 获取本机 IPv4 地址
     *
     * 遍历所有网络接口，过滤回环和虚拟接口。
     * 优先返回 WiFi 接口的 IP，其次以太网。
     *
     * @return IPv4 地址字符串（如 "192.168.1.100"），无网络时返回 null
     */
    fun getLocalIpAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null

        // 优先级：wlan > eth > 其他
        for (prefix in prioritizedNames) {
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                if (!iface.name.startsWith(prefix)) continue

                val addresses = iface.inetAddresses?.toList() ?: continue
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        }

        // 兜底：遍历所有接口
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val addresses = iface.inetAddresses?.toList() ?: continue
            for (addr in addresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }

        return null
    }

    /**
     * 获取本机所在的 /24 子网基址
     *
     * 如本机 IP 为 "192.168.101.35"，返回 "192.168.101"
     * 用于 SMB 主机扫描时遍历子网内所有 IP（.1 ~ .254）
     *
     * @return 子网前三段，无网络时返回 null
     */
    fun getSubnetBase(): String? {
        val ip = getLocalIpAddress() ?: return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    /** 主机名/FQDN 正则：支持字母数字、连字符、下划线、点号；每段不以连字符开头；总长 1~253 */
    private val HOSTNAME_PATTERN = Regex(
        """^(?=.{1,253}$)([a-zA-Z0-9_](?:[a-zA-Z0-9_-]*[a-zA-Z0-9_])?)(\.[a-zA-Z0-9_](?:[a-zA-Z0-9_-]*[a-zA-Z0-9_])?)*$"""
    )

    /**
     * 判断字符串是否为合法的 IPv4 地址（四段，每段 0~255）。
     */
    fun isIpv4(text: String): Boolean {
        val parts = text.split(".")
        if (parts.size != 4) return false
        return parts.all {
            // 2026-10-10 审计修复：拒绝前导零段（"010" 在部分平台按八进制解析为 8，
            // 歧义写法可能连到非预期主机）
            it.toIntOrNull()?.let { n -> n in 0..255 } == true &&
                !(it.length > 1 && it.startsWith("0"))
        }
    }

    /**
     * 判断字符串是否为合法的 SMB 连接主机（IPv4 地址或合法主机名/FQDN）。
     * 空白字符串视为非法。
     */
    fun isValidHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (isIpv4(host)) return true
        return HOSTNAME_PATTERN.matches(host)
    }

    /**
     * 获取当前网络连接类型（WiFi / 以太网 / 无）
     *
     * 用于 UI 根据 WiFi/有线显示不同图标。
     */
    fun getNetworkType(context: Context): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.NONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return NetworkType.NONE
            val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                else -> NetworkType.NONE
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return NetworkType.NONE
            @Suppress("DEPRECATION")
            return when (info.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                else -> if (info.isConnected) NetworkType.CELLULAR else NetworkType.NONE
            }
        }
    }
}
