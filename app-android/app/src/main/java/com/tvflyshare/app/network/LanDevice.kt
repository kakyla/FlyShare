package com.tvflyshare.app.network
import com.tvflyshare.app.httpserver.HttpServerManager

/**
 * 局域网设备数据模型
 *
 * 表示一个通过网络发现或手动添加的设备。
 */
data class LanDevice(
    val name: String,          // 设备名称（如 "MuMu Player"）；扫描拿不到主机名时为 IP
    val ip: String,            // IP 地址（如 "192.168.101.35"）
    val port: Int = HttpServerManager.PORT,      // HTTP 服务端口
    val deviceType: String = "TV",  // 设备类型（TV/Box/Phone）
    val version: String = "1.0",   // 应用版本
    val isManual: Boolean = false, // 是否手动添加
    val isOnline: Boolean = true   // 是否在线
)
