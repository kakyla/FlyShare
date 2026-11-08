package com.tvflyshare.app.update

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.tvflyshare.app.util.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.tvflyshare.app.R

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class NoUpdate(val message: String) : UpdateState()
    data class HasUpdate(val info: UpdateInfo) : UpdateState()
    // 2026-10-04 审计 P2 修复：waitingNetwork——断网/等待 Wi-Fi 时不再只显示冻结的进度条
    data class Downloading(val progress: Int, val waitingNetwork: Boolean = false) : UpdateState()
    object DownloadComplete : UpdateState()
    data class Error(val error: String) : UpdateState()
}

sealed class UpdateUiEvent {
    object ResetState : UpdateUiEvent()
    object CheckUpdate : UpdateUiEvent()
    data class StartDownload(val url: String, val versionCode: Int, val sha256: String? = null) : UpdateUiEvent()
    object InstallApk : UpdateUiEvent()
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val PREFS_NAME = "update_prefs"
        private const val PREFS_KEY_LAST_DOWNLOAD_VERSION = "last_download_version"
        private const val PREFS_KEY_LAST_DOWNLOAD_ID = "last_download_id"
        private const val PREFS_KEY_LAST_DOWNLOAD_URL = "last_download_url"
        // 文件指纹是必需项（2026-11-08 审计修复），缺了就不允许下载/安装
    }

    private val repository = UpdateRepository()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var downloadId: Long = -1L
    private val downloadManager = application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // 2026-10-04 审计 P1 修复：installApk 的 check-then-act 非原子（广播/轮询/缓存命中三条路径
    // 都可能触发），CAS 保证并发时仅一路拉起安装器
    private val installing = java.util.concurrent.atomic.AtomicBoolean(false)
    // 当前更新包的期望 SHA-256。更新源已强制要求携带，安装前必校；为空一律拒绝安装。
    private var pendingSha256: String? = null
    
    private val jsonFormat = Json { ignoreUnknownKeys = true }
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 监听下载完成的广播
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && downloadId != -1L) {
                // 2026-10-10 审计修复：不再无条件覆写 DownloadComplete——installApk 内部失败
                // （SHA 校验不匹配/URI 缺失等）会置 Error，随即被覆盖会导致错误原因不可见
                installApk()
            }
        }
    }

    init {
        // 注册广播接收器，兼容 Android 14+ 的安全要求
        ContextCompat.registerReceiver(
            application,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // 防止重复注销异常，通常由 Android 系统抛出 IllegalArgumentException
        }
    }

    fun onEvent(event: UpdateUiEvent) {
        when (event) {
            is UpdateUiEvent.ResetState -> resetState()
            is UpdateUiEvent.CheckUpdate -> checkUpdate()
            is UpdateUiEvent.StartDownload -> startDownload(event.url, event.versionCode, event.sha256)
            is UpdateUiEvent.InstallApk -> installApk()
        }
    }

    private fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    fun checkUpdate() {
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            // First, restore downloadId from prefs
            if (downloadId == -1L) {
                downloadId = prefs.getLong(PREFS_KEY_LAST_DOWNLOAD_ID, -1L)
            }

            try {
                val result = repository.fetchUpdateInfo()
                if (result.isSuccess) {
                    val updateInfo = result.getOrNull()
                    if (updateInfo != null) {
                        val appVersionCodeResult = getAppVersionCode()
                        if (appVersionCodeResult.isSuccess) {
                            val currentVersionCode = appVersionCodeResult.getOrThrow()
                            if (updateInfo.versionCode.toLong() > currentVersionCode) {
                                // 复用已下载包前必须做双重校验：
                                // 1) versionCode 与 apkUrl 均一致（防止服务端覆盖新包但 versionCode 未变时被误用旧缓存）
                                // 2) 实际下载文件仍存在且非空（防止 DownloadManager 记录 SUCCESSFUL 但文件已被清理导致“解析出错”）
                                val savedVersion = prefs.getInt(PREFS_KEY_LAST_DOWNLOAD_VERSION, -1)
                                val savedUrl = prefs.getString(PREFS_KEY_LAST_DOWNLOAD_URL, "") ?: ""
                                if (downloadId != -1L && savedVersion == updateInfo.versionCode
                                    && savedUrl == updateInfo.apkUrl
                                ) {
                                    val status = queryDownloadStatus(downloadId)
                                    when {
                                        // 记录已完成且文件真实存在：复用缓存，等用户点「立即安装」
                                        status == DownloadManager.STATUS_SUCCESSFUL && isCachedFileValid(downloadId) -> {
                                            pendingSha256 = updateInfo.apkSha256
                                            _updateState.value = UpdateState.DownloadComplete
                                        }
                                        // 2026-10-10 审计修复：下载仍在进行（如进程被杀后系统继续下载、
                                        // 或下载中再次触发检查更新）——原先 getUriForDownloadedFile 对
                                        // 进行中记录返回 null，被判「缓存失效」后 cleanupStaleDownload
                                        // 会把快完成的下载取消掉。改为重新挂上进度轮询续接。
                                        status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED -> {
                                            pendingSha256 = updateInfo.apkSha256
                                            _updateState.value = UpdateState.Downloading(0)
                                            trackDownloadProgress()
                                        }
                                        // 记录失效/文件缺失：清理后回到「有更新」状态
                                        else -> {
                                            cleanupStaleDownload(downloadId)
                                            _updateState.value = UpdateState.HasUpdate(updateInfo)
                                        }
                                    }
                                } else {
                                    // 缓存失效（版本/URL 不符、文件缺失或为空）：清理陈旧下载记录与残留文件，
                                    // 回到“有更新”状态，由用户点击“立即更新”走 startDownload 重新干净下载
                                    cleanupStaleDownload(downloadId)
                                    _updateState.value = UpdateState.HasUpdate(updateInfo)
                                }
                            } else {
                                _updateState.value = UpdateState.NoUpdate(getApplication<Application>().getString(R.string.update_already_latest))
                            }
                        } else {
                            val err = appVersionCodeResult.exceptionOrNull()
                            _updateState.value = UpdateState.Error(if (err != null) AppException.fromThrowable(err).uiMessage.asString(getApplication<Application>()) else getApplication<Application>().getString(R.string.update_get_version_failed))
                        }
                    } else {
                        _updateState.value = UpdateState.Error(getApplication<Application>().getString(R.string.update_get_info_failed_empty))
                    }
                } else {
                    val err = result.exceptionOrNull()
                    _updateState.value = UpdateState.Error(if (err != null) AppException.fromThrowable(err).uiMessage.asString(getApplication<Application>()) else getApplication<Application>().getString(R.string.update_unknown_error))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _updateState.value = UpdateState.Error(AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()))
            }
        }
    }

    /**
     * 2026-10-10 审计修复：查询 DownloadManager 记录状态。
     * isCachedFileValid 依赖 getUriForDownloadedFile（仅 SUCCESSFUL 返回非空），
     * 无法区分「进行中」与「失效」——本方法补上状态维度，避免取消进行中的下载。
     * 记录不存在返回 null。
     */
    private fun queryDownloadStatus(id: Long): Int? {
        if (id == -1L) return null
        return try {
            downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (col != -1) cursor.getInt(col) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * 校验指定 downloadId 对应的已下载文件是否真实存在且非空。
     * DownloadManager 在文件被清理后仍可能保留 STATUS_SUCCESSFUL 记录，
     * 因此必须实际打开文件确认，避免复用已失效的缓存导致“解析出错”。
     */
    private fun isCachedFileValid(id: Long): Boolean {
        if (id == -1L) return false
        val uri = downloadManager.getUriForDownloadedFile(id) ?: return false
        return try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 清理失效的下载缓存：移除 DownloadManager 中的陈旧记录、删除目录下的残留更新包，
     * 并清空相关 SharedPreferences，保证下一次“立即更新”会重新干净下载。
     */
    private fun cleanupStaleDownload(id: Long) {
        if (id != -1L) {
            try {
                downloadManager.remove(id)
            } catch (e: Exception) {
                // ignore
            }
        }
        val appNameEn = getApplication<Application>().getString(R.string.app_name_en)
        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { file ->
            if (file.name.startsWith("${appNameEn}_update") && file.name.endsWith(".apk")) {
                file.delete()
            }
        }
        prefs.edit()
            .remove(PREFS_KEY_LAST_DOWNLOAD_ID)
            .remove(PREFS_KEY_LAST_DOWNLOAD_VERSION)
            .remove(PREFS_KEY_LAST_DOWNLOAD_URL)
            .apply()
        downloadId = -1L
    }

    private fun getAppVersionCode(): Result<Long> {
        return try {
            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            Result.success(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo))
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(AppException.fromThrowable(e))
        }
    }

    private fun startDownload(url: String, versionCode: Int, sha256: String? = null) {
        // 2026-10-04 审计 P1 修复：无幂等检查——遥控器连按「立即更新」会并发双下载、双轮询协程，
        // 最终可能连弹两个安装器
        if (_updateState.value is UpdateState.Downloading) return
        // 2026-11-08 审计修复：源头 UpdateRepository 已挡掉无指纹配置，这里再兜一道，
        // 防止其它调用路径绕过。复用「校验失败」文案，不新增对外文案。
        if (!isValidApkSha256(sha256)) {
            _updateState.value = UpdateState.Error(
                getApplication<Application>().getString(R.string.update_checksum_mismatch)
            )
            return
        }
        _updateState.value = UpdateState.Downloading(0)
        pendingSha256 = sha256
        
        try {
            // 移除上一次（可能残留/失效）的下载记录，避免 DownloadManager 中留存旧 id 造成干扰
            if (downloadId != -1L) {
                try {
                    downloadManager.remove(downloadId)
                } catch (e: Exception) {
                    // ignore
                }
            }

            // 给 URL 加上时间戳参数，强制跳过 DownloadManager 的网络缓存
            val finalUrl = if (url.contains("?")) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}"
            val uri = Uri.parse(finalUrl)
            
            val appNameEn = getApplication<Application>().getString(R.string.app_name_en)
            val fileName = "${appNameEn}_update_${System.currentTimeMillis()}.apk"
            
            val request = DownloadManager.Request(uri).apply {
                setTitle("${getApplication<Application>().getString(R.string.app_name)}${getApplication<Application>().getString(R.string.update_title_suffix)}")
                setDescription(getApplication<Application>().getString(R.string.update_downloading_desc))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(getApplication(), Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            // 清理该目录下的所有历史更新包，避免占用空间和干扰
            val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            dir?.listFiles()?.forEach { file ->
                if (file.name.startsWith("${appNameEn}_update") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }

            downloadId = downloadManager.enqueue(request)
            prefs.edit()
                .putLong(PREFS_KEY_LAST_DOWNLOAD_ID, downloadId)
                .putInt(PREFS_KEY_LAST_DOWNLOAD_VERSION, versionCode)
                .putString(PREFS_KEY_LAST_DOWNLOAD_URL, url)
                .apply()
                
            trackDownloadProgress()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _updateState.value = UpdateState.Error(AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()))
        }
    }

    private fun trackDownloadProgress() {
        viewModelScope.launch(Dispatchers.IO) {
            var downloading = true
            while (downloading && downloadId != -1L) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor == null) {
                    // 2026-10-04 审计 P1 修复：query 返回 null 同样按失败处理，避免死循环
                    downloading = false
                    _updateState.value = UpdateState.Error(getApplication<Application>().getString(R.string.update_download_interrupted))
                } else cursor.use {
                    if (it.moveToFirst()) {
                        val statusColumn = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (statusColumn != -1) it.getInt(statusColumn) else DownloadManager.STATUS_FAILED

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            // 2026-10-10 审计修复：状态由 installApk 成功后才置 DownloadComplete，
                            // 失败时保留 Error 不被覆盖
                            installApk()
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloading = false
                            _updateState.value = UpdateState.Error(getApplication<Application>().getString(R.string.update_download_failed))
                        } else if (status == DownloadManager.STATUS_PAUSED) {
                            // 2026-10-04 审计 P2 修复：断网/等待 Wi-Fi 时给出提示，不再是无提示的冻结进度条
                            val reasonColumn = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonColumn != -1) it.getInt(reasonColumn) else -1
                            val waiting = reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ||
                                reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                            val downloadedColumn = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalColumn = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val bytesDownloaded = if (downloadedColumn != -1) it.getLong(downloadedColumn) else 0L
                            val bytesTotal = if (totalColumn != -1) it.getLong(totalColumn) else 0L
                            val progress = if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt() else 0
                            _updateState.value = UpdateState.Downloading(progress, waitingNetwork = waiting)
                        } else {
                            val downloadedColumn = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalColumn = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                            if (downloadedColumn != -1 && totalColumn != -1) {
                                val bytesDownloaded = it.getLong(downloadedColumn)
                                val bytesTotal = it.getLong(totalColumn)
                                if (bytesTotal > 0) {
                                    val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                                    _updateState.value = UpdateState.Downloading(progress)
                                }
                            }
                        }
                    } else {
                        // 2026-10-04 审计 P1 修复：DownloadManager 记录被系统清理/回收后 query 返回空游标，
                        // 原先既不退出也不报错——死循环空转 + BackHandler 把用户困死在更新页
                        downloading = false
                        _updateState.value = UpdateState.Error(getApplication<Application>().getString(R.string.update_download_interrupted))
                    }
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * 安装前强制校验 APK 指纹。
     * 2026-11-08 审计修复：原先 expected 为空直接返回 true，与对外 README
     * 「按配置里的文件指纹校验后安装」的承诺不符；现在缺指纹一律判失败，不静默放行。
     */
    private fun verifyApkSha256(uri: Uri, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            Log.e("UpdateViewModel", "更新配置没有文件指纹，拒绝安装")
            return false
        }
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            hex == expected.trim().lowercase()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    private fun installApk() {
        val context = getApplication<Application>()
        // 2026-10-04 审计 P1 修复：并发触发时仅一路进入
        if (!installing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                // 2026-10-10 审计修复：SHA-256 校验需逐块读整个 APK（可达上百 MB），切 IO 线程——
                // 广播接收器回调在主线程，原先直接在主线程哈希，慢速存储上可能逼近
                // onReceive 的 ~10s 上限造成 ANR
                val uri = withContext(Dispatchers.IO) {
                    val u = downloadManager.getUriForDownloadedFile(downloadId)
                    if (u != null && !verifyApkSha256(u, pendingSha256)) {
                        Log.e("UpdateViewModel", "APK sha256 mismatch, abort installation")
                        cleanupStaleDownload(downloadId)
                        pendingSha256 = null
                        _updateState.value = UpdateState.Error(context.getString(R.string.update_checksum_mismatch))
                        null
                    } else {
                        u
                    }
                }
                if (uri == null) {
                    // SHA 校验失败路径已置 Error；其余 URI 缺失情形补「解析失败」提示，
                    // 均不覆盖已存在的 Error
                    if (_updateState.value !is UpdateState.Error) {
                        _updateState.value = UpdateState.Error(context.getString(R.string.update_parse_apk_failed))
                    }
                    return@launch
                }
                // DownloadManager 返回的 uri 是 content:// 类型的，可以直接用于安装
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
                // 2026-10-10 审计修复：仅在安装流程成功发起后才置 DownloadComplete
                _updateState.value = UpdateState.DownloadComplete
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("UpdateViewModel", "APK installation failed", AppException.fromThrowable(e))
                _updateState.value = UpdateState.Error(AppException.fromThrowable(e).uiMessage.asString(getApplication<Application>()))
            } finally {
                // 用户从安装器返回后允许再次点「立即安装」
                installing.set(false)
            }
        }
    }
}
