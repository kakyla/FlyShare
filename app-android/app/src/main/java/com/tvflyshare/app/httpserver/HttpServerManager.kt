package com.tvflyshare.app.httpserver

import android.content.Context
import android.util.Log
import com.tvflyshare.app.R
import com.tvflyshare.app.util.AppException
import com.tvflyshare.app.util.StoragePermissionUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.TempFile
import fi.iki.elonen.NanoHTTPD.TempFileManager
import fi.iki.elonen.NanoHTTPD.TempFileManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import com.tvflyshare.app.util.FileOperations
import com.tvflyshare.app.util.isHiddenTempFile
import com.tvflyshare.app.util.isPathInsideRoot
import com.tvflyshare.app.util.isPrivateAppFile
import com.tvflyshare.app.util.isAllowedRequestHeaders
import com.tvflyshare.app.interconnect.DeviceDiscoveryManager
import com.tvflyshare.app.interconnect.RemoteFileEntry

/**
 * HTTP 服务管理器 (基于 NanoHTTPD)
 *
 * 封装 HTTP server 的启动和停止逻辑。
 * 与 Android 组件解耦，只负责 HTTP 层。
 *
 * 端口：5566
 * 引擎：NanoHTTPD
 */
object HttpServerManager {

    const val PORT = 5566

    /**
     * 落盘用的隐藏临时名前缀（形如 `._flyup_tmp_flyshare_up_123.tmp`）。
     * NanoHTTPD 的接收临时文件（bucket 与 multipart 分片）以此前缀直写目标目录，
     * 接收完成后原子 rename 为正式名——文件完全就绪前不会出现在客户端文件列表，
     * 列表查询 API 与 TV 端列表渲染均忽略此前缀。
     */
    const val INTERNAL_TMP_PREFIX = "_flyup_tmp_"

    /**
     * 上传进度采样间隔（ms）。
     * 越小进度条越平滑；过小会频繁 stat 临时文件。120ms 兼顾平滑度与开销，
     * 配合 TV 端 400ms 的列表刷新，肉眼可见进度持续增长。
     */
    private const val PROGRESS_POLL_MS = 120L

    private var server: TvNanoServer? = null
    private var webHtml: String = ""

    // ===== 上传状态管理（内存，进程级） =====

    /** 正在上传中的文件：progressKey（每请求唯一） -> 上传信息（parseBody 期间即可关联进度） */
    private val uploadingFiles = ConcurrentHashMap<String, UploadingFileInfo>()

    /** 上传进度轮询协程作用域（与 parseBody 并发运行，采样临时文件长度） */
    private val uploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 进度条目 key 计数器：每个上传请求生成唯一 key（"up_1"、"up_2"...）。
     * 不能用请求线程 id 做 key：NanoHTTPD 每个连接一个线程且 keep-alive 复用，
     * 同一连接先后两个上传会拿到同一个 id，前一次的完成标记会误杀后一次的进度条目。
     */
    private val progressKeyCounter = AtomicLong(0)

    /**
     * 当前连接线程的 TempFileManager（ThreadLocal）。
     * 已用 2.3.1 字节码证实：工厂是「每个 TCP 连接」调用一次（ClientHandler 建会话时 create），
     * 同一连接上的所有请求共用同一个管理器，NanoHTTPD 在每个请求结束后自行 clear() 临时文件；
     * 存入 ThreadLocal 供 serve()（与工厂同线程）直接取用，连接线程死亡即随线程回收，无泄漏。
     * （旧实现用全局 map 登记 + 请求结束 remove：同一连接第二次上传时登记已被删、工厂不再调用，
     *   进度条目整个上传期间不存在——表现为 TV 端传完才显示。）
     */
    private val connectionTempMgr = ThreadLocal<TrackedTempFileManager>()

    /**
     * 当前请求的临时文件落盘目录（ThreadLocal）：serve() 在接收请求体前下发目标目录，
     * 工厂在本线程创建临时文件时读取——让上传内容直写目标目录（与正式文件同分区），
     * 接收完即可原子改名，省去整文件复制（大文件复制曾需十几秒「保存中」）。
     * 请求结束移除；未下发的请求（普通 GET 等）落 cacheDir。
     */
    private val requestTempDir = ThreadLocal<File>()

    /** 上传完成/失败标记：progressKey -> true，serve() 落盘或异常后置位，轮询协程据此移除「上传中」条目并退出 */
    private val uploadDone = ConcurrentHashMap<String, Boolean>()

    // ===== 上传进度事件流（同进程推送给 TV 界面，替代轮询拉取） =====

    /**
     * 进度流内部载体：中央采样协程按 PROGRESS_POLL_MS 周期快照 uploadingFiles，
     * StateFlow 按内容去重——无变化不发射，条目出现/推进/移除即时推给订阅方（TV 界面），
     * 起步显示延迟从轮询的 ~1s 降到 ~0.1s 级。
     */
    private val _uploadingFlow = MutableStateFlow<List<UploadingFileInfo>>(emptyList())

    /** 暴露给 TV 界面订阅：当前上传中条目流（与网页端进度近乎同步出现） */
    val uploadingFlow: StateFlow<List<UploadingFileInfo>> = _uploadingFlow.asStateFlow()

    init {
        // 中央采样：只做内存快照（磁盘长度由各请求自己的轮询协程按同周期更新进 map），开销可忽略
        uploadScope.launch {
            while (true) {
                _uploadingFlow.value = getUploadingFiles()
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    /** 暴露给 UI 层：当前正在上传的文件列表（按开始时间倒序，最新在最前） */
    fun getUploadingFiles(): List<UploadingFileInfo> =
        uploadingFiles.values.sortedByDescending { it.startedAt }

    /** 最近完成的文件事件列表（最多保留 20 条，使用 CopyOnWriteArrayList 保证并发读写安全） */
    private val recentCompleted = CopyOnWriteArrayList<CompletedFileEvent>()

    /** 事件自增 ID，TV 端用此 ID 做去重 */
    private val eventIdCounter = AtomicLong(0)

    private var appContext: Context? = null

    /** 初始化：读取 Web 页面 + 设置存储目录（优先公共目录，降级到 App 私有目录） */
    fun init(context: Context) {
        appContext = context.applicationContext
        val appName = context.getString(R.string.app_name)
        val appNameEn = context.getString(R.string.app_name_en)
        val htmlTemplate = try {
            context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("HttpServerManager", "Failed to load web/index.html", AppException.fromThrowable(e))
            "<html><body><h1>页面加载失败</h1></body></html>"
        }
        webHtml = htmlTemplate
            .replace("\${APP_NAME}", appName)
            .replace("\${APP_NAME_EN}", appNameEn)
    }

    private var actualPort: Int = PORT

    fun getPort(): Int = actualPort

    /** 构造 TvNanoServer 并挂上自定义 TempFileManager 工厂（用于登记上传临时文件，供进度采样） */
    private fun createServer(port: Int, serverAppContext: Context, html: String): TvNanoServer {
        val s = TvNanoServer(port, serverAppContext, html)
        s.setTempFileManagerFactory(TrackedTempFileManagerFactory())
        return s
    }

    /**
     * 启动 HTTP 服务，端口固定为 [PORT]=5566（不回退、不随机分配）。
     * 快速传输(getPort) 与互联(写死 PORT) 共用同一端口，保证两端始终一致，
     * 不会再出现「扫码能传、手动互连连不上」的端口分裂问题。
     * 若 5566 被占用启动失败：记录明确日志并保持 actualPort=PORT，
     * 避免 getPort() 返回随机端口（5567+/系统分配）导致互联与快速传输端口不一致。
     */
    fun start() {
        if (server != null && server?.isAlive == true) return
        val ctx = appContext ?: return

        try {
            server = createServer(PORT, ctx, webHtml)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            actualPort = PORT
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("HttpServerManager", "Failed to start server on port $PORT (端口被占用?)", AppException.fromThrowable(e))
            server?.stop()
            server = null
            actualPort = PORT
        }
    }

    /** 停止 HTTP 服务 */
    fun stop() {
        server?.stop()
        server = null
    }

    /** 添加完成事件，保留最近 20 条 */
    @Synchronized
    private fun addCompletedEvent(event: CompletedFileEvent) {
        recentCompleted.add(event)
        while (recentCompleted.size > 20) {
            recentCompleted.removeAt(0)
        }
    }

    private class TvNanoServer(
        port: Int,
        private val serverAppContext: Context,
        private val html: String
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            // 门牌校验：DNS 重绑定后请求仍带着攻击者域名作 Host，只认 IP 字面量即可掐断，
            // 用户无需登录、无需多一步；本项目二维码/页面地址/互传全是 IP 直连，无域名用法。
            // 带 Origin 的浏览器请求（上传、删除等）还必须是本服务自己的来源。
            if (!isAllowedRequestHeaders(session.headers)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"invalid host"}""")
            }

            // 每次请求实时读取最新保存路径（用户在设置里改路径后立即生效，无需重启服务）
            val dir = StoragePermissionUtils.getStorageDir(serverAppContext)
            val uri = session.uri
            val method = session.method

            try {
                // 上传管理页面
                if (method == Method.GET && uri == "/") {
                    val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
                    response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    response.addHeader("Pragma", "no-cache")
                    response.addHeader("Expires", "0")
                    return response
                }

                // 健康检查
                if (method == Method.GET && uri == "/api/health") {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                }

                // ==========================================
                // TV互联互传 API
                // ==========================================

                // 1. 获取文件列表 API
                if (method == Method.GET && uri == "/api/v1/tvfile/list") {
                    val reqPath = session.parameters["path"]?.firstOrNull() ?: ""
                    val targetDir = if (reqPath.isEmpty()) dir else File(dir, reqPath)

                    // 安全校验，防止跨目录访问
                    if (!isPathInsideRoot(dir, targetDir)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }

                    if (targetDir.exists() && targetDir.isDirectory) {
                        // 支持分页，避免超大目录一次性加载内存飙升
                        // 2026-10-10 审计修复：limit/offset 负数会令 take/drop 抛 IllegalArgumentException
                        // 落 500——非法分页参数防御性收口为 0
                        val limitStr = session.parameters["limit"]?.firstOrNull()
                        val offsetStr = session.parameters["offset"]?.firstOrNull()
                        val limit = (limitStr?.toIntOrNull() ?: Int.MAX_VALUE).coerceAtLeast(0)
                        val offset = (offsetStr?.toIntOrNull() ?: 0).coerceAtLeast(0)

                        val fileList = targetDir.listFiles()
                            ?.filter { !isHiddenTempFile(it.name) && !isPrivateAppFile(it.name) }
                            ?.map { f ->
                            val relPath = f.canonicalPath.substringAfter(dir.canonicalPath).removePrefix(File.separator)
                            RemoteFileEntry(
                                name = f.name,
                                isDirectory = f.isDirectory,
                                size = f.length(),
                                lastModified = f.lastModified(),
                                path = relPath
                            )
                        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                         ?.drop(offset)
                         ?.take(limit) ?: emptyList()

                        return newFixedLengthResponse(Response.Status.OK, "application/json", Json.encodeToString(fileList))
                    } else {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_dir_not_found)}"}""")
                    }
                }

                // 2. 远程文件下载 API
                if (method == Method.GET && uri == "/api/v1/tvfile/download") {
                    val reqPath = session.parameters["path"]?.firstOrNull()
                    if (reqPath.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_path_required)}"}""")
                    }

                    val targetFile = File(dir, reqPath)

                    // 安全校验
                    // 崩溃日志等 App 内部文件不得经无鉴权网页端下载（2026-11-08 安全审计）
                    if (!targetFile.exists() || targetFile.isDirectory || isPrivateAppFile(targetFile.name)
                        || !isPathInsideRoot(dir, targetFile)) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_file_not_found)}"}""")
                    }

                    val encodedFileName = URLEncoder.encode(targetFile.name, "UTF-8").replace("+", "%20")
                    val fileLen = targetFile.length()
                    var startFrom: Long = 0
                    var endAt: Long = -1

                    val rangeHeader = session.headers["range"]
                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        val range = rangeHeader.substring(6).split("-")
                        try {
                            startFrom = range[0].toLong()
                            if (range.size > 1 && range[1].isNotEmpty()) {
                                endAt = range[1].toLong()
                            }
                        } catch (e: NumberFormatException) {
                            startFrom = 0
                        }
                    }

                    if (endAt < 0) {
                        endAt = fileLen - 1
                    }

                    // 2026-10-04 审计 P2 修复：Range 起点越界（bytes=99999- 超过文件长度）时
                    // 原先返回 206 + 非法 Content-Range（start>end），合规客户端解析异常——按规范回 416
                    if (startFrom >= fileLen && startFrom > 0) {
                        return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "application/json", """{"error":"range not satisfiable"}""")
                    }

                    // 2026-10-10 审计修复：end 超过文件长度时按 RFC 7233 clamp 到 fileLen-1——
                    // 原先按虚高值下发 Content-Length/Content-Range，播放器/下载器按分块发送
                    // bytes=0-999999999 这类显式大 end 时会解析异常、反复重试
                    if (endAt > fileLen - 1) {
                        endAt = fileLen - 1
                    }

                    // 2026-10-10 审计修复：bytes=start-end 且 start>end（如 bytes=5-3）为非法
                    // Range，按规范回 416（原先钳为 0 返回 206 + 非法 Content-Range）。
                    // 无 Range 头的空文件（endAt=-1、startFrom=0）属合法空响应，不受此限。
                    if (rangeHeader != null && startFrom > endAt) {
                        return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "application/json", """{"error":"range not satisfiable"}""")
                    }

                    var newLen = endAt - startFrom + 1
                    if (newLen < 0) newLen = 0

                    // 2026-10-30 修复：改用自关闭响应体流 DownloadFileInputStream。
                    // 原先裸 FileInputStream 交给 NanoHTTPD，客户端断连时它不代为关闭，
                    // 句柄泄漏会累积直至耗尽（表现为服务端卡死），见该类注释。
                    val response = newFixedLengthResponse(
                        if (startFrom > 0) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                        "application/octet-stream",
                        DownloadFileInputStream(targetFile, startFrom, newLen),
                        newLen
                    )
                    response.addHeader("Accept-Ranges", "bytes")
                    // 2026-10-10 审计修复：Content-Range 仅在客户端请求了 Range 时返回，
                    // 200 全量响应携带 Content-Range 语义错误（严格客户端可能解析异常）
                    if (rangeHeader != null) {
                        response.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
                    }
                    response.addHeader("Content-Disposition", "attachment; filename=\"$encodedFileName\"; filename*=utf-8''$encodedFileName")
                    return response
                }

                // 3. 远端新建文件夹 API（互联页「新建文件夹」使用）
                if (method == Method.POST && uri == "/api/v1/tvfile/mkdir") {
                    val reqPath = session.parameters["path"]?.firstOrNull() ?: ""
                    val rawName = session.parameters["name"]?.firstOrNull() ?: ""

                    val targetParent = if (reqPath.isEmpty()) dir else File(dir, reqPath)
                    // 安全校验：父目录必须落在共享根内
                    if (!isPathInsideRoot(dir, targetParent)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }

                    // 名称清洗：去除路径分隔符与首尾空白，拒绝空名/危险名
                    val folderName = rawName.trim().replace("/", "_").replace("\\", "_").trim()
                    if (folderName.isEmpty() || folderName == "." || folderName == "..") {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_invalid_name)}"}""")
                    }

                    val newDir = File(targetParent, folderName)
                    if (!isPathInsideRoot(dir, newDir)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }

                    return try {
                        val created = newDir.mkdirs()
                        if (created || newDir.isDirectory) {
                            newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"created","name":"${jsonEscape(folderName)}"}""")
                        } else {
                            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_mkdir_failed)}"}""")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("HttpServerManager", "Failed to mkdir $folderName", AppException.fromThrowable(e))
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_mkdir_failed)}"}""")
                    }
                }

                // 4. 远端删除文件/文件夹 API（互联页「删除」使用；文件夹递归删除）
                if (method == Method.DELETE && uri == "/api/v1/tvfile/delete") {
                    val reqPath = session.parameters["path"]?.firstOrNull()
                    if (reqPath.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_path_required)}"}""")
                    }
                    val targetFile = File(dir, reqPath)
                    if (!targetFile.exists() || isPrivateAppFile(targetFile.name) || !isPathInsideRoot(dir, targetFile)) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_file_not_found)}"}""")
                    }
                    // 2026-10-04 审计 P1 修复：path="."（或 sub/..）时 target 即共享根本身，
                    // isPathInsideRoot 按"child==root 合法"的列表语义返回 true——不拦住就会递归清空整个共享根
                    if (targetFile.canonicalPath == dir.canonicalPath) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_root_forbidden)}"}""")
                    }
                    val ctx = appContext
                    if (ctx == null) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"server not ready"}""")
                    }
                    val ok = deleteFileRecursive(targetFile, ctx)
                    if (ok) {
                        FileOperations.triggerMediaScan(ctx, targetFile.absolutePath)
                        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"deleted"}""")
                    }
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_delete_failed)}"}""")
                }

                // 5. 远端重命名 API（互联页「重命名」使用）
                if (method == Method.POST && uri == "/api/v1/tvfile/rename") {
                    val reqPath = session.parameters["path"]?.firstOrNull()
                    val rawName = session.parameters["name"]?.firstOrNull() ?: ""
                    if (reqPath.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_path_required)}"}""")
                    }
                    val sourceFile = File(dir, reqPath)
                    // 不挡就能把崩溃日志改名成普通文件名再下载，列表/下载过滤会被绕过
                    if (!sourceFile.exists() || isPrivateAppFile(sourceFile.name) || !isPathInsideRoot(dir, sourceFile)) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_file_not_found)}"}""")
                    }
                    // 2026-10-04 审计 P1 修复：与 delete 同规——共享根本身不可作为重命名源
                    if (sourceFile.canonicalPath == dir.canonicalPath) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_root_forbidden)}"}""")
                    }

                    // 名称清洗与 mkdir 同规：去路径分隔符与首尾空白，拒绝空名/危险名
                    val newName = rawName.trim().replace("/", "_").replace("\\", "_").trim()
                    if (newName.isEmpty() || newName == "." || newName == "..") {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_invalid_name)}"}""")
                    }
                    val targetFile = File(sourceFile.parentFile, newName)
                    if (!isPathInsideRoot(dir, targetFile)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }
                    if (targetFile.exists()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_target_exists)}"}""")
                    }

                    val renamed = try { sourceFile.renameTo(targetFile) } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("HttpServerManager", "Failed to rename $reqPath to $newName", AppException.fromThrowable(e))
                        false
                    }
                    return if (renamed && targetFile.exists()) {
                        appContext?.let { ctx -> FileOperations.triggerMediaScan(ctx, targetFile.absolutePath) }
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"renamed","name":"${jsonEscape(newName)}"}""")
                    } else {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_rename_failed)}"}""")
                    }
                }

                // ==========================================
                // 传统网页上传 API
                // ==========================================
                // 上传状态
                if (method == Method.GET && uri == "/api/status") {
                    val status = UploadStatus(
                        uploading = uploadingFiles.values.map { it.name },
                        completed = recentCompleted.toList(),
                    )
                    return newFixedLengthResponse(Response.Status.OK, "application/json", Json.encodeToString(status))
                }

                // 文件列表（支持目录浏览：path 为相对共享根目录的路径，空字符串表示根目录）
                if (method == Method.GET && uri == "/api/files") {
                    val reqPath = session.parameters["path"]?.firstOrNull() ?: ""
                    val targetDir = if (reqPath.isEmpty()) dir else File(dir, reqPath)

                    // 安全校验，防止跨目录访问
                    if (!isPathInsideRoot(dir, targetDir)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }

                    if (targetDir.exists() && targetDir.isDirectory) {
                        val files = targetDir.listFiles()
                            ?.filter { !isHiddenTempFile(it.name) && !isPrivateAppFile(it.name) }
                            ?.map { f ->
                            val relPath = f.canonicalPath.substringAfter(dir.canonicalPath).removePrefix(File.separator)
                            UploadedFile(
                                id = relPath,
                                name = f.name,
                                size = if (f.isDirectory) 0 else f.length(),
                                lastModified = f.lastModified(),
                                isDirectory = f.isDirectory,
                            )
                        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()

                        return newFixedLengthResponse(Response.Status.OK, "application/json", Json.encodeToString(files))
                    } else {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_dir_not_found)}"}""")
                    }
                }

                // 下载文件（path 为相对共享根目录的路径，支持子目录；支持 Range 断点续传）
                if (method == Method.GET && uri == "/api/files/download") {
                    val reqPath = session.parameters["path"]?.firstOrNull()
                    if (reqPath.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_path_required)}"}""")
                    }
                    val targetFile = File(dir, reqPath)
                    if (targetFile.exists() && targetFile.isFile && !isPrivateAppFile(targetFile.name)
                        && isPathInsideRoot(dir, targetFile)) {
                        val encodedFileName = URLEncoder.encode(targetFile.name, "UTF-8").replace("+", "%20")
                        val fileLen = targetFile.length()
                        var startFrom: Long = 0
                        var endAt: Long = -1

                        val rangeHeader = session.headers["range"]
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val range = rangeHeader.substring(6).split("-")
                            try {
                                startFrom = range[0].toLong()
                                if (range.size > 1 && range[1].isNotEmpty()) {
                                    endAt = range[1].toLong()
                                }
                            } catch (e: NumberFormatException) {
                                Log.e("HttpServerManager", "NumberFormatException in Range header", AppException.fromThrowable(e))
                                startFrom = 0
                            }
                        }

                    if (endAt < 0) {
                        endAt = fileLen - 1
                    }

                    // 2026-10-04 审计 P2 修复：Range 起点越界回 416（对齐 tvfile/download）
                    if (startFrom >= fileLen && startFrom > 0) {
                        return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "application/json", """{"error":"range not satisfiable"}""")
                    }

                    // 2026-10-10 审计修复：end 越界 clamp + start>end 非法 Range 回 416（对齐 tvfile/download）
                    if (endAt > fileLen - 1) {
                        endAt = fileLen - 1
                    }
                    if (rangeHeader != null && startFrom > endAt) {
                        return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "application/json", """{"error":"range not satisfiable"}""")
                    }

                    var newLen = endAt - startFrom + 1
                    if (newLen < 0) newLen = 0

                    // 2026-10-30 修复：同样改用自关闭响应体流（理由见 tvfile/download 分支）
                    val response = newFixedLengthResponse(
                            if (startFrom > 0) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                            "application/octet-stream",
                            DownloadFileInputStream(targetFile, startFrom, newLen),
                            newLen
                        )
                        response.addHeader("Accept-Ranges", "bytes")
                        if (rangeHeader != null) {
                            response.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
                        }
                        response.addHeader("Content-Disposition", "attachment; filename=\"$encodedFileName\"; filename*=utf-8''$encodedFileName")
                        return response
                    } else {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"file not found"}""")
                    }
                }

                // 删除文件或文件夹（path 为相对共享根目录的路径；文件夹递归删除）
                if (method == Method.DELETE && uri == "/api/files") {
                    val reqPath = session.parameters["path"]?.firstOrNull()
                    if (reqPath.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_path_required)}"}""")
                    }
                    val targetFile = File(dir, reqPath)
                    // 2026-10-04 审计 P1 修复：path="." 时 target 即共享根本身，禁止删除（对齐 tvfile/delete）
                    if (targetFile.exists() && !isPrivateAppFile(targetFile.name)
                        && isPathInsideRoot(dir, targetFile) && targetFile.canonicalPath != dir.canonicalPath) {
                        val ctx = appContext
                        if (ctx == null) {
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"server not ready"}""")
                        }
                        val ok = deleteFileRecursive(targetFile, ctx)
                        if (ok) {
                            FileOperations.triggerMediaScan(ctx, targetFile.absolutePath)
                            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"deleted"}""")
                        }
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"file not found"}""")
                }

                // 新建文件夹（path 为父目录相对路径，name 为文件夹名）
                if (method == Method.POST && uri == "/api/mkdir") {
                    val reqPath = session.parameters["path"]?.firstOrNull() ?: ""
                    val rawName = session.parameters["name"]?.firstOrNull() ?: ""

                    val targetParent = if (reqPath.isEmpty()) dir else File(dir, reqPath)
                    if (!isPathInsideRoot(dir, targetParent)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }

                    val folderName = rawName.trim().replace("/", "_").replace("\\", "_").trim()
                    if (folderName.isEmpty() || folderName == "." || folderName == "..") {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_invalid_name)}"}""")
                    }

                    val newDir = File(targetParent, folderName)
                    if (!isPathInsideRoot(dir, newDir)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                    }

                    return try {
                        val created = newDir.mkdirs()
                        if (created || newDir.isDirectory) {
                            newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"created","name":"${jsonEscape(folderName)}"}""")
                        } else {
                            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_mkdir_failed)}"}""")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("HttpServerManager", "Failed to mkdir $folderName", AppException.fromThrowable(e))
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_mkdir_failed)}"}""")
                    }
                }

                // 文件上传
                if (method == Method.POST && uri == "/api/upload") {
                    // 进度条目用「每请求唯一 key」：登记 / 更新 / 清理三方都用它，互不串扰。
                    // 不能用线程 id 做 key（keep-alive 连接复用同一线程，先后两个上传会撞 key）；
                    // 也不能用临时文件路径做 key（bucket 临时文件与 multipart 分片文件路径不同）。
                    val progressKey = "up_" + progressKeyCounter.incrementAndGet()
                    // 连接的临时文件管理器：工厂在建连接时（本线程）调用 create 并存入 ThreadLocal，
                    // 此处同步取用，无需等待；管理器随连接线程回收，无泄漏。
                    val trackedMgr = connectionTempMgr.get()
                    val files = HashMap<String, String>()

                    // 文件名在请求头阶段已解析，提前取出供进度条目展示
                    val b64name = session.parameters["b64name"]?.firstOrNull()
                    val originalName = if (!b64name.isNullOrEmpty()) {
                        try {
                            String(android.util.Base64.decode(b64name, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("HttpServerManager", "Failed to decode b64name", AppException.fromThrowable(e))
                            "unknown_file"
                        }
                    } else {
                        val rawName = session.parameters["file"]?.firstOrNull() ?: "unknown_file"
                        try {
                            String(rawName.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("HttpServerManager", "Failed to decode rawName", AppException.fromThrowable(e))
                            rawName
                        }
                    }

                    // 目标目录与最终文件名在请求头阶段即可确定（path/b64name 都是 URL 参数），
                    // 提前算好并让本请求的临时文件直写进目标目录（同分区隐藏名）：
                    // 接收完一步 renameTo 即成正式文件，省去整文件复制（大文件复制曾需十几秒「保存中」）。
                    val uploadPath = session.parameters["path"]?.firstOrNull() ?: ""
                    val uploadParent = if (uploadPath.isEmpty()) dir else File(dir, uploadPath)
                    val pathValid = isPathInsideRoot(dir, uploadParent)
                    var target: File? = null
                    if (pathValid) {
                        uploadParent.mkdirs()
                        val safeName = FileOperations.sanitizeFileName(originalName)
                        var dest = File(uploadParent, safeName)
                        var counter = 1
                        while (dest.exists()) {
                            val dotIndex = safeName.lastIndexOf('.')
                            val baseName = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
                            val ext = if (dotIndex > 0) safeName.substring(dotIndex) else ""
                            dest = File(uploadParent, "${baseName}_$counter$ext")
                            counter++
                        }
                        target = dest
                    }
                    // 通知工厂：本请求的临时文件直写目标目录（请求结束在 finally 移除，避免串到同连接下一请求）
                    requestTempDir.set(if (pathValid) uploadParent else null)

                    val totalSize = session.headers["content-length"]?.toLongOrNull() ?: 0L

                    // multipart 分支才启动轮询协程；原始流式分支的进度条目由接收循环直接管理
                    var pollerLaunched = false

                    try {
                        // 目标目录非法：在接收请求体之前直接拒绝，不产生进度条目。
                        // 拒绝前必须把请求体读完：NanoHTTPD 不会代为消费未读的请求体，
                        // keep-alive 连接上残留的字节会被当作下一个请求解析，污染后续响应。
                        if (!pathValid) {
                            drainRequestBody(session, totalSize)
                            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_forbidden)}"}""")
                        }
                        // var：改名前可能因同名并发上传 TOCTOU 重算 _N 后缀（见 resolveUploadTargetIfTaken）
                        var targetFile = target ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_no_file_received)}"}""")

                        // ===== 原始流式上传（网页端，octet-stream）：请求体即文件本体 =====
                        // 边收边写目标目录（全程只写一遍盘：无 multipart 的 bucket 中转、无收完后的分片提取复制），
                        // 收完原子改名——100% 后无任何等待。进度由接收循环直接更新（100ms 节流），无需轮询协程。
                        val contentType = (session.headers["content-type"] ?: "").lowercase()
                        if (contentType.contains("octet-stream")) {
                            if (totalSize <= 0L) {
                                // 2026-10-10 审计修复：与 catch 路径同规——强制断连。keep-alive 连接上
                                // 若实际存在未被 Content-Length 声明的请求体，直接返回会残留字节污染下一请求
                                val resp = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_no_file_received)}"}""")
                                resp.addHeader("Connection", "close")
                                return resp
                            }
                            uploadingFiles[progressKey] = UploadingFileInfo(originalName, totalSize, 0L, System.currentTimeMillis(), id = progressKey)
                            val tempFile = File.createTempFile(".$INTERNAL_TMP_PREFIX" + "flyshare_up_", ".tmp", uploadParent)
                            var uploadedFile: UploadedFile? = null
                            try {
                                tempFile.outputStream().use { out ->
                                    val input = session.inputStream
                                    val buf = ByteArray(64 * 1024)
                                    var received = 0L
                                    var lastUpdate = 0L
                                    while (received < totalSize) {
                                        val want = minOf(buf.size.toLong(), totalSize - received).toInt()
                                        val n = input.read(buf, 0, want)
                                        if (n < 0) throw java.io.IOException("client aborted")
                                        out.write(buf, 0, n)
                                        received += n
                                        val nowMs = System.currentTimeMillis()
                                        if (nowMs - lastUpdate >= 100) {
                                            uploadingFiles[progressKey]?.let { uploadingFiles[progressKey] = it.copy(received = received) }
                                            lastUpdate = nowMs
                                        }
                                    }
                                }
                                // 收完：切「保存中」→ 原子改名（毫秒级）→ 入库
                                uploadingFiles[progressKey]?.let { uploadingFiles[progressKey] = it.copy(received = totalSize, stage = UploadStage.SAVING) }
                                // 2026-10-04 审计 P2 修复：同名并发上传 TOCTOU——接收期间对端可能已落盘同名文件，
                                // Linux renameTo 对已存在目标会静默覆盖；改名前复查重名并重算 _N 后缀
                                targetFile = resolveUploadTargetIfTaken(targetFile, uploadParent)
                                val renamed = try { tempFile.renameTo(targetFile) } catch (e: Exception) { Log.w("HttpServerManager", "Rename to target failed, fallback to copy", AppException.fromThrowable(e)); false }
                                if (!renamed) {
                                    try { tempFile.copyTo(targetFile, overwrite = true) } catch (e: Exception) { Log.w("HttpServerManager", "Rename fallback copy failed", AppException.fromThrowable(e)) }
                                }
                                if (targetFile.exists() && targetFile.length() == totalSize) {
                                    appContext?.let { ctx -> FileOperations.triggerMediaScan(ctx, targetFile.absolutePath) }
                                    addCompletedEvent(
                                        CompletedFileEvent(
                                            eventIdCounter.incrementAndGet(),
                                            id = targetFile.name,
                                            name = targetFile.name,
                                            size = targetFile.length(),
                                            timestamp = System.currentTimeMillis(),
                                        )
                                    )
                                    uploadedFile = UploadedFile(
                                        id = targetFile.canonicalPath.substringAfter(dir.canonicalPath).removePrefix(File.separator),
                                        name = targetFile.name,
                                        size = targetFile.length(),
                                    )
                                }
                            } finally {
                                // 无轮询协程负责本条目，直接移除；失败清理隐藏临时文件
                                uploadingFiles.remove(progressKey)
                                if (uploadedFile == null) {
                                    tempFile.takeIf { it.exists() }?.delete()
                                    // 2026-10-04 审计 P2 修复：copyTo(overwrite=true) 半途失败（磁盘满/IO 错）时
                                    // 会留下用正式文件名可见的截断残骸——对齐 multipart 分支的对称清理
                                    targetFile.takeIf { it.exists() }?.delete()
                                }
                            }
                            if (uploadedFile != null) {
                                return newFixedLengthResponse(Response.Status.OK, "application/json", Json.encodeToString(uploadedFile))
                            } else {
                                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_no_file_received)}"}""")
                            }
                        }

                        // ===== multipart 分支（保留给非网页客户端）：NanoHTTPD 收满 bucket 后再提取分片 =====
                        // 进度轮询协程：parseBody 在请求线程把上传内容写进临时文件的过程中，
                        // 本协程在 IO 线程周期性采样临时文件的磁盘长度，实时更新 uploadingFiles.received，
                        // 使 UI 进度条平滑增长，而不是 0% 卡住、完成后直接跳 100%。
                        pollerLaunched = true
                        uploadScope.launch {
                            // 管理器已在 serve() 线程从 ThreadLocal 取好（工厂建连接时登记，早于本请求），直接采样
                            if (trackedMgr == null) return@launch
                            val startedAt = System.currentTimeMillis()
                            while (isActive) {
                                // NanoHTTPD 用 RandomAccessFile / FileOutputStream 直接写临时文件，
                                // 全程不调用 TempFile.open()，因此「临时文件的磁盘长度」是唯一能反映
                                // 真实接收字节数的进度来源（旧实现靠 open() 包装计数，恒为 0，进度条不动）。
                                // 取最大值：上传过程中可能同时存在 bucket 与分片文件，bucket 即整个请求体。
                                val received = trackedMgr.tempFiles.maxOfOrNull { it.file.length() } ?: 0L
                                val prev = uploadingFiles[progressKey]
                                uploadingFiles[progressKey] = if (prev == null) {
                                    UploadingFileInfo(
                                        name = originalName,
                                        size = totalSize,
                                        received = received,
                                        startedAt = startedAt,
                                        id = progressKey
                                    )
                                } else {
                                    prev.copy(received = received)
                                }
                                // serve() 完成落盘/失败后置 uploadDone，轮询协程移除条目并退出
                                if (uploadDone[progressKey] == true) {
                                    uploadingFiles.remove(progressKey)
                                    uploadDone.remove(progressKey)
                                    break
                                }
                                delay(PROGRESS_POLL_MS)
                            }
                        }

                        session.parseBody(files)

                        var uploadedFile: UploadedFile? = null
                        val tempFilePath = files["file"]
                        // 请求体已完整接收，进入落盘阶段：UI 由「接收中 xx%」切到「保存中」。
                        // 分母保持 Content-Length（与 bucket 临时文件长度严格一致，接收完恰好 100%）。
                        if (tempFilePath != null) {
                            uploadingFiles[progressKey]?.let { uploadingFiles[progressKey] = it.copy(stage = UploadStage.SAVING) }

                            var isSuccess = false
                            try {
                                val tempFile = File(tempFilePath)
                                val expectedLen = tempFile.length()
                                // 临时文件已直写在目标目录（隐藏前缀，列表不可见）：
                                // 同目录改名 = 同分区原子操作，毫秒级完成；仅极端文件系统限制下回退复制。
                                val renamed = try {
                                    // 2026-10-04 审计 P2 修复：multipart 分支同样做 TOCTOU 重名复查
                                    targetFile = resolveUploadTargetIfTaken(targetFile, uploadParent)
                                    tempFile.renameTo(targetFile)
                                } catch (_: Exception) { false }
                                if (!renamed) {
                                    try { tempFile.copyTo(targetFile, overwrite = true) } catch (e: Exception) { Log.w("HttpServerManager", "Rename fallback copy failed", AppException.fromThrowable(e)) }
                                }
                                isSuccess = targetFile.exists() && targetFile.length() == expectedLen

                                // 触发系统媒体库扫描，确保刚上传的文件能立刻在首页分类（视频、图片、音频）中统计到
                                if (isSuccess) {
                                    appContext?.let { ctx ->
                                        FileOperations.triggerMediaScan(ctx, targetFile.absolutePath)
                                    }

                                    val event = CompletedFileEvent(
                                        eventId = eventIdCounter.incrementAndGet(),
                                        id = targetFile.name,
                                        name = targetFile.name,
                                        size = targetFile.length(),
                                        timestamp = System.currentTimeMillis(),
                                    )
                                    addCompletedEvent(event)

                                    val uploadedRelPath = targetFile.canonicalPath.substringAfter(dir.canonicalPath).removePrefix(File.separator)
                                    uploadedFile = UploadedFile(
                                        id = uploadedRelPath,
                                        name = targetFile.name,
                                        size = targetFile.length(),
                                    )
                                }
                            } finally {
                                // 通知轮询协程移除「上传中」条目（由轮询协程负责移除，避免并发重复创建）
                                uploadDone[progressKey] = true
                                if (!isSuccess) {
                                    targetFile.takeIf { it.exists() }?.delete()
                                }
                            }
                        }

                        if (uploadedFile != null) {
                            return newFixedLengthResponse(Response.Status.OK, "application/json", Json.encodeToString(uploadedFile))
                        } else {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_no_file_received)}"}""")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("HttpServerManager", "File upload failed", AppException.fromThrowable(e))
                        val ctx = appContext
                        val errStr = if (ctx != null) AppException.fromThrowable(e).uiMessage.asString(ctx) else "upload failed"
                        // 2026-10-04 审计 P2 修复：异常路径（磁盘满/client aborted 等）请求体未读完，
                        // keep-alive 连接上残留字节会被当作下一个请求解析污染后续响应——
                        // 对错误响应强制断连，最简且不会长时间阻塞（与 642 行路径非法早退的 drain 同目的）
                        val errResp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_upload_failed, errStr)}"}""")
                        errResp.addHeader("Connection", "close")
                        return errResp
                    } finally {
                        // multipart 分支：置位 uploadDone 让轮询协程移除「上传中」条目（避免 parseBody 异常时条目卡死）；
                        // 原始流式分支无轮询协程、条目已直接移除，置位只会残留一条无人清理的标记，故按分支区分。
                        // 不要清理连接的 TempFileManager 登记（NanoHTTPD 每连接只建一次，同连接后续上传还要用）；
                        // 清除本请求的临时目录指示，避免串到同连接的下一个请求。
                        if (pollerLaunched) {
                            uploadDone[progressKey] = true
                        }
                        requestTempDir.remove()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("HttpServerManager", "Error serving HTTP request", AppException.fromThrowable(e))
                val ctx = appContext
                val errStr = if (ctx != null) AppException.fromThrowable(e).uiMessage.asString(ctx) else "internal error"
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"${appContext?.getString(R.string.http_api_error_internal, errStr) ?: "internal error"}"}""")
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }

        /**
         * 丢弃本请求尚未读取的请求体（按 Content-Length 设上限）。
         * 用于「不读请求体就返回错误响应」的路径（如上传 path 非法直接拒绝）：
         * NanoHTTPD 的 keep-alive 由请求头决定，响应侧无法强制断连，
         * 未消费的请求体会残留在连接流里，被下一个请求的解析阶段当作请求行/请求头，
         * 导致同连接后续请求全部错乱（表现为浏览器后续请求 400/解析失败）。
         */
        private fun drainRequestBody(session: IHTTPSession, contentLength: Long) {
            if (contentLength <= 0L) return
            try {
                val input = session.inputStream
                val buf = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    remaining -= n
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("HttpServerManager", "Drain request body failed", AppException.fromThrowable(e))
            }
        }

        /**
         * 同名并发上传 TOCTOU 防护（2026-10-04 审计 P2 修复）：
         * 文件名去重检查发生在接收请求体之前，两个连接并发上传同名文件会算出同一个目标；
         * Linux renameTo 对已存在目标是静默覆盖，后完成者会顶掉先完成者的文件。
         * 改名前复查重名，已被占用则重算 name_N 后缀。
         */
        private fun resolveUploadTargetIfTaken(targetFile: File, uploadParent: File): File {
            if (!targetFile.exists()) return targetFile
            val dotIndex = targetFile.name.lastIndexOf('.')
            val baseName = if (dotIndex > 0) targetFile.name.substring(0, dotIndex) else targetFile.name
            val ext = if (dotIndex > 0) targetFile.name.substring(dotIndex) else ""
            var counter = 1
            var candidate = File(uploadParent, "${baseName}_$counter$ext")
            while (candidate.exists()) {
                counter++
                candidate = File(uploadParent, "${baseName}_$counter$ext")
            }
            return candidate
        }
    }

    // ===== 上传实时进度：字节计数临时文件 =====

    /**
     * 登记型 TempFile：仅持有所创建的临时文件句柄。
     *
     * 注意：不要在 open() 里做字节计数——NanoHTTPD 2.3.1 的上传链路（getTmpBucket 用 RandomAccessFile、
     * saveTmpFile 用 FileOutputStream）都是直接按文件名写盘，全程不调用 open()，
     * 计数永远为 0。真实进度只能靠轮询 [file] 的磁盘长度获得（见 serve() 中的进度轮询协程）。
     */
    private class TrackedTempFile(val file: File) : TempFile {
        override fun open(): OutputStream = file.outputStream()
        override fun getName(): String = file.absolutePath
        override fun delete() {
            if (file.exists()) file.delete()
        }
    }

    /**
     * 自定义 TempFileManager：为每个上传请求收集其 [TrackedTempFile]，
     * 供进度轮询协程按磁盘长度计算已接收字节。NanoHTTPD 会在 parseBody 每个文件分片时调用 createTempFile。
     */
    private class TrackedTempFileManager : TempFileManager {
        val tempFiles = CopyOnWriteArrayList<TrackedTempFile>()

        override fun createTempFile(filename: String?): TempFile {
            // 注意：NanoHTTPD 的 getTmpBucket() 会以 null 调用 createTempFile（源码签名允许 null），
            // 此处参数必须声明为可空 String?，否则非空参数收到 null 会抛 NPE，导致每次上传闪退。
            // 临时文件直写在目标目录（serve() 经 requestTempDir 下发，与正式文件同分区，
            // 收完即可原子改名，免去整文件复制）；统一用隐藏前缀，列表查询 API 已过滤，客户端不可见。
            val tmp = File.createTempFile(
                ".$INTERNAL_TMP_PREFIX" + "flyshare_up_", ".tmp",
                requestTempDir.get() ?: appContext?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            )
            val ct = TrackedTempFile(tmp)
            tempFiles.add(ct)
            return ct
        }

        override fun clear() {
            tempFiles.forEach { it.delete() }
            tempFiles.clear()
        }
    }

    /**
     * 自定义 TempFileManager 工厂：NanoHTTPD 在每个连接建立时调用 create()，
     * 在此存入 ThreadLocal（连接线程存活期间有效），供该连接后续所有上传请求的进度轮询采样。
     */
    private class TrackedTempFileManagerFactory : TempFileManagerFactory {
        override fun create(): TempFileManager {
            val tmp = TrackedTempFileManager()
            // NanoHTTPD 2.3.1：每个 TCP 连接只调用一次 create（ClientHandler 建会话时），
            // 该连接后续所有请求共用此管理器；存入 ThreadLocal 供 serve()（同线程）取用。
            connectionTempMgr.set(tmp)
            return tmp
        }
    }
}

// ===== 数据模型 =====

@Serializable
data class UploadedFile(
    val id: String,           // 相对共享根目录的路径（文件夹即文件夹路径，用于进入/删除/下载）
    val name: String,         // 仅文件名（不含路径）
    val size: Long,
    val lastModified: Long = 0L,
    val isDirectory: Boolean = false,
)

/**
 * JSON 字符串转义：手动拼接 JSON 响应体用，防名称含引号/反斜杠时产出非法 JSON
 * （名称清洗只替换了路径分隔符，a"b 这类合法名会破坏 {"name":"..."} 结构）。
 */
private fun jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * 递归删除文件或目录（兼容 minSdk 21，不使用 File.deleteRecursively）。
 * 删除每个文件后同步清理 MediaStore 记录，避免残留。
 */
private fun deleteFileRecursive(file: File, context: Context): Boolean {
    if (file.isDirectory) {
        file.listFiles()?.forEach { deleteFileRecursive(it, context) }
    }
    val deleted = file.delete()
    if (deleted) {
        FileOperations.deleteViaMediaStore(context, file.absolutePath)
    }
    return deleted
}

@Serializable
data class CompletedFileEvent(
    val eventId: Long,
    val id: String,
    val name: String,
    val size: Long,
    val timestamp: Long,
)

@Serializable
data class UploadStatus(
    val uploading: List<String>,
    val completed: List<CompletedFileEvent>,
)

/** 上传所处阶段：接收中（网络传输）/ 保存中（已收完，正在写入目标目录） */
enum class UploadStage {
    RECEIVING,
    SAVING,
}

/** 单个正在上传的文件信息（内存态，不序列化） */
data class UploadingFileInfo(
    val name: String,         // 显示文件名（未 sanitize 的原始名）
    val size: Long,           // 上传总字节数（取自 Content-Length，与请求体长度严格一致）
    val received: Long = 0,   // 已接收字节数（轮询临时文件磁盘长度获得，接收完即等于 size）
    val startedAt: Long,      // 开始时间戳
    val stage: UploadStage = UploadStage.RECEIVING,
    // 每请求唯一 id（"up_N"，progressKey 计数器）——2026-10-04 审计 P0 修复：
    // TV 端 LazyColumn key 原用 startedAt+name，两个浏览器标签页同毫秒并发上传同名文件会撞 key 崩溃
    val id: String = "",
)
