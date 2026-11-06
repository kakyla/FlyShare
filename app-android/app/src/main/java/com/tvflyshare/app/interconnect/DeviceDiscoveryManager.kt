package com.tvflyshare.app.interconnect

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

import java.util.UUID

import com.tvflyshare.app.httpserver.HttpServerManager

object DeviceDiscoveryManager {
    private const val TAG = "DeviceDiscoveryManager"
    private const val UDP_PORT = 55667
    private const val BROADCAST_INTERVAL = 3000L // 3 seconds
    private const val DEVICE_TIMEOUT = 30000L // 30 seconds，缓解弱网下设备被误剔除导致的闪退
    private const val UDP_SO_TIMEOUT_MS = 3000 // 3 seconds

    /** 单播回包限频窗口：须小于 [DEVICE_TIMEOUT]，否则心跳间隔过长会被对端剔除 */
    private const val UNICAST_REPLY_INTERVAL_MS = 20000L

    private var broadcastJob: Job? = null
    private var receiveJob: Job? = null
    private var cleanupJob: Job? = null
    private var socket: DatagramSocket? = null
    
    private val _discoveredDevices = MutableStateFlow<List<DeviceModel>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceModel>> = _discoveredDevices.asStateFlow()

    private val deviceMap = java.util.concurrent.ConcurrentHashMap<String, DeviceModel>()

    /** 最近一次给对端 IP 发单播 hello 的时间戳（心跳限频用） */
    private val lastUnicastSent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var deviceId: String = ""
    private var deviceName: String = ""

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    fun init(context: Context) {
        // 使用 UUID 替代 ANDROID_ID，防止多开模拟器或克隆设备因 ID 相同互相过滤
        deviceId = UUID.randomUUID().toString()
        deviceName = Build.MODEL ?: "TV Device"
        
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifiManager?.createMulticastLock("DeviceDiscoveryManagerLock")
            multicastLock?.setReferenceCounted(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MulticastLock", e)
        }
    }

    private var currentHttpPort: Int = 0

    /**
     * 启动设备发现。
     *
     * 与 [stop] 同为 @Synchronized：两者都是本地快速操作（建 socket 只做 bind，不含网络往返），
     * 加锁可彻底消除 Service 反复 onDestroy/onStartCommand 时的启停交错。
     */
    @Synchronized
    fun start(httpPort: Int = 0) {
        currentHttpPort = httpPort

        // 先取到局部引用再判断：字段可能被并发的 stop() 置空，
        // 原写法 socket!!.isClosed 在启停交错时会抛 NullPointerException
        val current = socket
        if (current != null && !current.isClosed) return

        try {
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }

        socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = UDP_SO_TIMEOUT_MS
                bind(java.net.InetSocketAddress(UDP_PORT))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DatagramSocket on port $UDP_PORT", e)
            // 端口被占用时退化为随机端口：收不到别人发往固定端口的包，但仍能对外广播。
            // 退化后表现为「本机能被别人发现，但本机设备列表永远是空的」，
            // 此处打明确警告，便于 logcat 直接确诊这类单向发现问题。
            Log.w(TAG, "Port $UDP_PORT unavailable, fallback to random port: " +
                "broadcast still works but incoming discovery packets are LOST " +
                "(this device can be discovered but cannot discover others)")
            try {
                DatagramSocket().apply { broadcast = true }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to create fallback DatagramSocket", ex)
                // 启动失败必须归还组播锁，否则 WifiManager 的引用计数只增不减
                releaseMulticastLock()
                return
            }
        }

        startReceiving()
        startBroadcasting()
        startCleanupRoutine()
    }

    /**
     * 停止设备发现。
     *
     * 方法返回时状态即为"已停止"，后续 [start] 可立即重建。
     */
    @Synchronized
    fun stop() {
        broadcastJob?.cancel()
        receiveJob?.cancel()
        cleanupJob?.cancel()

        releaseMulticastLock()

        // 同步关闭并置空。
        // 早期实现把 close 与置空丢进协程异步执行，会产生两种故障：
        // 其一，紧随其后的 start() 看到的是尚未关闭的 socket，直接 return，发现功能就此永久失效；
        // 其二，若 start() 抢先建好新 socket，异步收尾又会把新 socket 关掉，同时泄漏旧 socket。
        socket?.close()
        socket = null

        // 告别广播只能异步发送：主线程禁止网络 IO。
        // 这里改用独立的临时 socket，避免为了发这一个包而把主 socket 的关闭时机拖成异步。
        val port = currentHttpPort
        if (port != 0) {
            coroutineScope.launch { sendByeViaTemporarySocket(port) }
        }

        deviceMap.clear()
        lastUnicastSent.clear()
        _discoveredDevices.value = emptyList()
    }

    /** 归还组播锁；持有时才释放，避免 setReferenceCounted 下计数被减成负数 */
    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        }
    }

    private fun startBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = coroutineScope.launch {
            while (isActive) {
                sendBroadcast("hello")
                delay(BROADCAST_INTERVAL)
            }
        }
    }

    private fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = coroutineScope.launch {
            val buffer = ByteArray(1024)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val jsonStr = String(packet.data, 0, packet.length)
                    val senderIp = packet.address.hostAddress ?: continue
                    
                    try {
                        val message = Json.decodeFromString<DiscoveryMessage>(jsonStr)
                        if (message.deviceId == deviceId) continue // Ignore self

                        when (message.action) {
                            "hello" -> {
                                val newDevice = DeviceModel(
                                    deviceId = message.deviceId,
                                    deviceName = message.deviceName,
                                    ipAddress = senderIp,
                                    httpPort = message.httpPort,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                                
                                // 修复重复IP问题：如果存在相同IP但不同设备ID的旧记录，先移除旧记录
                                val existingWithSameIp = deviceMap.entries.find { it.value.ipAddress == senderIp }
                                if (existingWithSameIp != null && existingWithSameIp.key != message.deviceId) {
                                    deviceMap.remove(existingWithSameIp.key)
                                }
                                
                                val existing = deviceMap[message.deviceId]
                                if (existing == null || existing.ipAddress != senderIp || existing.httpPort != message.httpPort) {
                                    deviceMap[message.deviceId] = newDevice
                                    updateStateFlow()
                                } else {
                                    // Just update timestamp
                                    deviceMap[message.deviceId] = newDevice
                                }

                                // 2026-10-24 单向发现修复：收到 hello 后点对点回一条单播 hello。
                                // 部分电视 ROM/路由器会拦广播但放行单播，导致一方能被发现、
                                // 自己列表却永远是空——只要有一方能收到对方的包，
                                // 回包就能把双方互相拉进列表，不再依赖广播双向都通。
                                maybeSendUnicastHello(senderIp)
                            }
                            "bye" -> {
                                if (deviceMap.containsKey(message.deviceId)) {
                                    deviceMap.remove(message.deviceId)
                                    updateStateFlow()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors from invalid packets
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, just loop again
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error receiving UDP packet", e)
                    }
                }
            }
        }
    }

    /**
     * 收到对端 hello 后，按限频窗口回一条单播 hello（直接发给对端监听端口 [UDP_PORT]）。
     *
     * 限频窗口 [UNICAST_REPLY_INTERVAL_MS] 必须小于设备超时 [DEVICE_TIMEOUT]，
     * 否则被单播发现的设备会因心跳间隔过长被对端当「掉线」剔除。
     * 收到回包的一方因窗口内已回过、不再再回，互弹链条在第二跳自然终止，
     * 稳态下每方向每 20 秒一条心跳包（约百余字节），流量可忽略。
     */
    private fun maybeSendUnicastHello(targetIp: String) {
        val now = System.currentTimeMillis()
        val last = lastUnicastSent[targetIp] ?: 0L
        if (now - last < UNICAST_REPLY_INTERVAL_MS) return
        lastUnicastSent[targetIp] = now

        // 异步发送：接收循环里不做网络写，stop() 取消协程时随之一并停掉
        coroutineScope.launch {
            sendUnicastHello(targetIp)
        }
    }

    private suspend fun sendUnicastHello(targetIp: String) = withContext(Dispatchers.IO) {
        try {
            val s = socket ?: return@withContext
            if (s.isClosed) return@withContext

            val port = currentHttpPort
            if (port == 0) return@withContext // HTTP server not ready

            val message = DiscoveryMessage(
                action = "hello",
                deviceId = deviceId,
                deviceName = deviceName,
                httpPort = port
            )
            val data = Json.encodeToString(message).toByteArray()
            val address = InetAddress.getByName(targetIp)
            s.send(DatagramPacket(data, data.size, address, UDP_PORT))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to send unicast hello to $targetIp", e)
        }
    }

    private fun startCleanupRoutine() {
        cleanupJob?.cancel()
        cleanupJob = coroutineScope.launch {
            while (isActive) {
                delay(DEVICE_TIMEOUT / 2)
                val now = System.currentTimeMillis()
                var removed = false

                // 手动添加的设备（manual- 前缀）没有 UDP 心跳，不参与超时清理：
                // 否则用户手填 IP 添加的设备 30 秒后就被当"掉线"移除，跨网段互联形同虚设。
                val keysToRemove = deviceMap.filter {
                    !it.key.startsWith("manual-") && now - it.value.lastSeenTimestamp > DEVICE_TIMEOUT
                }.keys
                if (keysToRemove.isNotEmpty()) {
                    keysToRemove.forEach { deviceMap.remove(it) }
                    removed = true
                }
                
                if (removed) {
                    updateStateFlow()
                }
            }
        }
    }

    private suspend fun sendBroadcast(action: String) = withContext(Dispatchers.IO) {
        try {
            val s = socket ?: return@withContext
            if (s.isClosed) return@withContext

            val port = currentHttpPort
            if (port == 0) return@withContext // HTTP server not ready

            broadcastVia(s, action, port)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to send broadcast", e)
        }
    }

    /**
     * 用一个短命的临时 socket 发送告别广播。
     *
     * [stop] 已同步关掉主 socket，此处另开 socket 把 bye 送出去。
     * 接收端按报文里的 deviceId 识别来源、按包的源 IP 记录地址，与源端口无关，故不影响对端下线判定。
     */
    private suspend fun sendByeViaTemporarySocket(httpPort: Int) = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { temp ->
                temp.broadcast = true
                broadcastVia(temp, "bye", httpPort)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to send bye broadcast", e)
        }
    }

    /**
     * 向本机所有网卡的广播地址各发一份发现报文。
     *
     * @param datagramSocket 实际发包的 socket（正常心跳用主 socket，告别广播用临时 socket）
     * @param action hello 或 bye
     * @param httpPort 本机 HTTP 服务端口，供对端回连
     */
    private fun broadcastVia(datagramSocket: DatagramSocket, action: String, httpPort: Int) {
        val message = DiscoveryMessage(
            action = action,
            deviceId = deviceId,
            deviceName = deviceName,
            httpPort = httpPort
        )
        val data = Json.encodeToString(message).toByteArray()

        for (address in getBroadcastAddresses()) {
            try {
                datagramSocket.send(DatagramPacket(data, data.size, address, UDP_PORT))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send broadcast to $address", e)
            }
        }
    }
    
    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast addresses", e)
        }
        if (addresses.isEmpty()) {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        }
        return addresses
    }
    
    private fun updateStateFlow() {
        _discoveredDevices.update { deviceMap.values.toList().sortedBy { it.deviceName } }
    }

    /**
     * 手动添加对端设备（跨网段互联场景）。
     *
     * 对端（手动填入 IP 的那台设备）HTTP 服务在其本机默认端口 [HttpServerManager.PORT]（5566）监听，
     * 因此只需提供对方 IP 即可，无需用户手动输入端口。
     *
     * 注意：此处必须用固定的默认端口 [HttpServerManager.PORT]，绝不能写成 [HttpServerManager.getPort()]。
     * getPort() 返回的是"本机"实际端口，本机端口可能因被占用而回退到 5567 等非默认值，
     * 用它去连对端（对端仍监听在 5566）必然连接超时（"连接异常:timeout"）。
     * UDP 广播发现能拿到对端真实端口故无此问题，而手动添加没有广播报文，只能假设对端在默认端口。
     *
     * UDP 广播发现跨不了网段/VLAN，当两台电视处于不同子网（如一方有线、一方无线）
     * 时互相搜不到，此时可由用户在互联页手动填入对方 IP 完成互联。
     *
     * @param ip 对端 IPv4 地址（如 192.168.106.55）
     */
    fun addManualDevice(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isBlank()) return

        // 2026-10-04 审计 P2 修复：注释与实现不符——同 IP 的广播发现设备原先不会被去重，
        // 手动添加后设备列表会出现两块同一台电视的瓷砖。已存在同 IP 广播设备时直接视为已添加。
        val existingWithSameIp = deviceMap.entries.find { it.value.ipAddress == trimmed }
        if (existingWithSameIp != null) {
            if (!existingWithSameIp.key.startsWith("manual-")) {
                // 已通过广播发现同一台设备：无需重复添加
                return
            }
            // 手动→手动（重新填写同 IP）：移除旧条目后重建
            deviceMap.remove(existingWithSameIp.key)
        }

        val device = DeviceModel(
            deviceId = "manual-$trimmed",
            deviceName = trimmed,
            ipAddress = trimmed,
            // 使用固定默认端口：对端 HTTP 服务监听在 5566，不能用本机 getPort()（可能回退导致连不上）
            httpPort = HttpServerManager.PORT,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        deviceMap[device.deviceId] = device
        updateStateFlow()
    }
}
