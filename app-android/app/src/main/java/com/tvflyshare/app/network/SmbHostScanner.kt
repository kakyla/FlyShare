package com.tvflyshare.app.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SMB 主机扫描器
 *
 * 职责：扫描局域网 /24 子网内所有开启了 SMB 共享（445 端口）的设备。
 *
 * 原理：
 * 1. 从本机 IP 算出子网（如 192.168.101.0/24）
 * 2. 并发对 .1 ~ .254 的 445 端口做 Socket 连接探测（超时 500ms）
 * 3. 连通的 IP 用 NetBIOS Node Status 查询（UDP 137）获取计算机名
 *    - 不需要认证，Windows / 大多数 NAS 默认开启
 *    - 拿不到主机名时（如关闭 NetBIOS 的 Linux SMB）用 IP 兜底
 * 4. 结果通过 StateFlow 实时推送给 UI
 *
 * 并发控制：Semaphore(85)，避免阻塞网络/被路由器限流
 *
 * 生命周期：
 *   scanner.start()  → 开始扫描
 *   scanner.stop()   → 中止扫描
 *
 * 线程安全：StateFlow + 协程，UI 订阅 hosts 即可
 */
class SmbHostScanner {

    companion object {
        private const val TAG = "SmbHostScanner"

        /** SMB 端口 */
        private const val SMB_PORT = 445

        /** NetBIOS Name Service 端口（UDP） */
        private const val NETBIOS_PORT = 137

        /** 端口连接 / UDP 查询超时（毫秒） */
        private const val CONNECT_TIMEOUT_MS = 500

        /** 最大并发数 */
        private const val MAX_CONCURRENCY = 85

        /** 子网主机 IP 范围（.1 ~ .254，.0 是网络地址，.255 是广播地址） */
        private const val SUBNET_HOST_START = 1
        private const val SUBNET_HOST_END = 254
    }

    private val _hosts = MutableStateFlow<List<LanDevice>>(emptyList())
    val hosts: StateFlow<List<LanDevice>> = _hosts.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * 扫描是否正常完成（true = 扫完所有 IP；false = 被手动停止或未启动）
     *
     * UI 据此区分文案：完成显示"扫描完成"，停止显示"已停止"
     */
    private val _scanCompleted = MutableStateFlow(false)
    val scanCompleted: StateFlow<Boolean> = _scanCompleted.asStateFlow()

    // 2026-10-04 审计 P2 修复：scope 改 var——release() 取消 scope 后若复用实例再 start()，
    // launch 会产出立即取消的 job，_isRunning 永久卡在 true 且无法再扫描；start 时按需重建
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    /**
     * 启动扫描
     *
     * 流程：算子网 → 并发探测 445 → 匿名获取主机名 → 实时更新列表
     * 扫描完所有 IP 后自动停止，isRunning 置 false
     *
     * @return true 启动成功；false 无网络或已在扫描中
     */
    fun start(): Boolean {
        if (_isRunning.value) {
            Log.d(TAG, "Scan already running")
            return false
        }

        // 2026-10-04 审计 P2 修复：release() 后 scope 已取消，重建后才能再次扫描
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        val subnetBase = NetworkUtils.getSubnetBase()
        if (subnetBase == null) {
            Log.w(TAG, "Cannot determine subnet, no network")
            return false
        }

        val localIp = NetworkUtils.getLocalIpAddress()

        _isRunning.value = true
        _scanCompleted.value = false
        _hosts.value = emptyList()
        Log.i(TAG, "Start scanning subnet: $subnetBase.1 ~ $subnetBase.254")

        scanJob = scope.launch {
            val semaphore = Semaphore(MAX_CONCURRENCY)
            val jobs = mutableListOf<Job>()

            // 并发探测 .1 ~ .254
            for (i in SUBNET_HOST_START..SUBNET_HOST_END) {
                val ip = "$subnetBase.$i"
                // 跳过本机 IP，避免扫到自己
                if (ip == localIp) continue

                val job = launch {
                    semaphore.withPermit {
                        if (probePort(ip, SMB_PORT)) {
                            // 445 通了，用 NetBIOS 查询获取计算机名（不需要认证）
                            val hostName = queryNetBiosName(ip)
                            val device = LanDevice(
                                name = hostName ?: ip,
                                ip = ip,
                                port = SMB_PORT,
                                deviceType = "SMB",
                                version = "",
                                isManual = false,
                                isOnline = true,
                            )
                            _hosts.update { current ->
                                if (current.any { it.ip == ip }) current else current + device
                            }
                            Log.i(TAG, "Found SMB host: $ip (name=${hostName ?: "unknown"})")
                        }
                    }
                }
                jobs.add(job)
            }

            // 等待所有探测完成
            jobs.forEach { it.join() }

            _isRunning.value = false
            _scanCompleted.value = true
            Log.i(TAG, "Scan finished, found ${_hosts.value.size} hosts")
        }
        return true
    }

    /**
     * 停止扫描
     *
     * 同时取消协程 scope，避免反复进出页面累积未取消的 scope（资源泄漏）
     */
    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _isRunning.value = false
        Log.i(TAG, "Scan stopped")
    }

    /**
     * 释放资源
     *
     * 取消整个协程 scope，供 UI 的 DisposableEffect 在离开页面时调用
     */
    fun release() {
        stop()
        scope.cancel()
        Log.i(TAG, "Scope cancelled")
    }

    /**
     * 探测目标 IP 的指定端口是否可连
     *
     * @return true 端口可连
     */
    private fun probePort(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.isConnected
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /**
     * 用 NetBIOS Node Status 查询获取计算机名（UDP 137）
     *
     * 优势：
     * - 不需要认证，Windows / 大多数 NAS 默认响应
     * - 速度极快（毫秒级），失败也只是超时返回 null
     *
     * 局限：
     * - 关闭了 NetBIOS over TCP/IP 的 Linux SMB 服务器不会响应 → 返回 null
     *
     * @param ip 目标主机 IP
     * @return 计算机名（如 "ZSJT-PC"）或 null
     */
    private fun queryNetBiosName(ip: String): String? {
        return try {
            val request = buildNetBiosNodeStatusRequest()
            DatagramSocket().use { socket ->
                socket.soTimeout = CONNECT_TIMEOUT_MS
                val packet = DatagramPacket(
                    request, request.size,
                    InetAddress.getByName(ip), NETBIOS_PORT,
                )
                socket.send(packet)

                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                parseNetBiosName(response.data, response.length)
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d(TAG, "NetBIOS query to $ip failed: ${e.message}")
            null
        }
    }

    /**
     * 构造 NetBIOS Node Status 请求包
     *
     * 查询通配名 `*`（编码为 `CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`），
     * 让服务器返回所有 NetBIOS 名称记录。
     *
     * 包结构（NBNS header + query）：
     *   Transaction ID (2) + Flags (2) + QDCOUNT (2) + ANCOUNT (2) + NSCOUNT (2) + ARCOUNT (2)
     *   + Query Name (34) + Query Type (2) + Query Class (2)
     */
    private fun buildNetBiosNodeStatusRequest(): ByteArray {
        return byteArrayOf(
            // Header
            0x00, 0x00,  // Transaction ID
            0x00, 0x00,  // Flags
            0x00, 0x01,  // QDCOUNT = 1
            0x00, 0x00,  // ANCOUNT = 0
            0x00, 0x00,  // NSCOUNT = 0
            0x00, 0x00,  // ARCOUNT = 0
            // Query Name: 通配名 `*` 半字节编码后补空格至 16 字符 = 32 字节
            0x20,        // Length = 32
            // CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
            0x00,        // Name terminator
            // Query Type = NBSTAT (0x0021)
            0x00, 0x21,
            // Query Class = IN (0x0001)
            0x00, 0x01,
        )
    }

    /**
     * 解析 NetBIOS Node Status 响应包，提取计算机名
     *
     * 响应包结构：
     *   Header (12) + Answer Name (34) + Type (2) + Class (2) + TTL (4) + RDLENGTH (2)
     *   + RDATA:
     *       - Number of names (1)
     *       - Name entries (18 each: 15 字符名 + 1 后缀 + 2 flags)
     *       - MAC (6)
     *
     * 选取后缀 0x00（工作站服务）且非组名的第一条作为计算机名。
     */
    private fun parseNetBiosName(data: ByteArray, length: Int): String? {
        // 最小长度：header(12) + answer name(34) + type(2) + class(2) + ttl(4) + rdlength(2) + numNames(1) + 1 entry(18)
        if (length < 75) return null

        var offset = 56  // 跳到 RDATA 起始的 numNames 字节
        val numNames = data[offset].toInt() and 0xFF
        offset += 1

        for (i in 0 until numNames) {
            if (offset + 18 > length) return null
            val nameBytes = data.copyOfRange(offset, offset + 15)
            val suffix = data[offset + 15].toInt() and 0xFF
            val flags = ((data[offset + 16].toInt() and 0xFF) shl 8) or
                    (data[offset + 17].toInt() and 0xFF)
            offset += 18

            // flags 第 15 位 = 1 表示组名，跳过
            val isGroup = (flags and 0x8000) != 0
            // 后缀 0x00 = Workstation Service（即计算机名本身）
            if (suffix == 0x00 && !isGroup) {
                val name = String(nameBytes, Charsets.US_ASCII).trim()
                if (name.isNotBlank()) return name
            }
        }
        return null
    }

    /**
     * 移除指定设备（UI 多选删除用）
     */
    fun removeDevice(device: LanDevice) {
        _hosts.update { current ->
            current.filterNot { it.ip == device.ip }
        }
    }
}
