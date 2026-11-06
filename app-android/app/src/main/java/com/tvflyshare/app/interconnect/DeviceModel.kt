package com.tvflyshare.app.interconnect

import kotlinx.serialization.Serializable

/**
 * 局域网内发现的 TV 设备模型
 */
data class DeviceModel(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val httpPort: Int,
    val lastSeenTimestamp: Long
)

/**
 * UDP 广播报文数据模型
 */
@Serializable
data class DiscoveryMessage(
    val action: String, // "hello" 或 "bye"
    val deviceId: String,
    val deviceName: String,
    val httpPort: Int
)

/**
 * 远程文件条目数据模型
 */
@Serializable
data class RemoteFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val path: String
)
