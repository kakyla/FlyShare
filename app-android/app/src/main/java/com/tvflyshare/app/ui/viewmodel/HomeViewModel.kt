package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.util.StorageDeviceScanner
import com.tvflyshare.app.util.StorageDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tvflyshare.app.util.FileScanner
import com.tvflyshare.app.TVTransferApp

// ============================================================
// 数据模型
// ============================================================

/** 设备信息 */
data class DeviceInfo(
    val name: String,
    val capacity: String,
    val path: String = "",
    val usedRatio: Float = 0f,
)

/** 主页 UI 状态 */
data class HomeUiState(
    val timeText: String = "",
    val version: String = "v2.0.00",
    val apkCount: Int = -1,
    val videoCount: Int = -1,
    val photoCount: Int = -1,
    val musicCount: Int = -1,
    val devices: List<DeviceInfo> = emptyList(),
)

sealed class HomeUiEvent {
    object Refresh : HomeUiEvent()
}

// ============================================================
// HomeViewModel
//
// 扫描真实数据：
// - 全部使用 FileScanner 解耦实现
// - 视频/图片/音乐：通过 MediaStore 获取数量
// - APK 数量：通过 FileScanner (深度6层) 遍历
// - 设备列表：StorageDeviceScanner
// ============================================================

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            version = getAppVersionName(),
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private fun getAppVersionName(): String {
        return try {
            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            "v${pInfo.versionName}"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "v2.0.0"
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 时间刷新协程引用，便于在 onCleared 取消 */
    private var timeJob: kotlinx.coroutines.Job? = null

    init {
        updateTime()
        refresh()
        startTimeUpdater()
    }

    private fun updateTime() {
        _uiState.value = _uiState.value.copy(
            timeText = timeFormat.format(Date())
        )
    }

    /**
     * 启动时间刷新：每秒更新一次时间显示
     *
     * 对齐到下一秒，之后每 1 秒刷新一次。
     */
    private fun startTimeUpdater() {
        timeJob = viewModelScope.launch {
            while (true) {
                // 计算到下一秒的毫秒数
                val now = System.currentTimeMillis()
                val delayMs = 1000L - (now % 1000L)
                delay(delayMs)
                updateTime()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timeJob?.cancel()
    }

    /**
     * 重新扫描所有文件数量
     *
     * 由 HomeScreen 在 ON_RESUME 时调用，确保从子页面返回后数量刷新。
     * 静默刷新：不显示"刷新中..."，扫描完直接更新数字，避免页面闪烁。
     */
    fun refresh() {
        // 2026-10-10 审计修复：ON_RESUME 高频触发且原先无并发去重——快速进出子页面时
        // 多批扫描并行，后完成的旧批会用旧数据覆盖新批结果（首页数字回跳）。
        // 取消上一批未完成的扫描再启动新一批。
        scanJob?.cancel()
        scanJob = scanAll()
    }

    private fun scanAll(): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            val context = getApplication<Application>()
            // 开启「自动修正安装包后缀」时，先还原微信/QQ 改名后的 .apk1/.apk.1，
            // 改名完成后再统计 .apk 数量，确保首页角标反映真实可安装数量。
            // 自动修正安装包后缀：在统计 .apk 数量的同一次遍历中顺手还原 .apk1/.apk.1，避免二次遍历
            val autoFixSuffix = (getApplication() as TVTransferApp).settingsRepository.getAutoFixApkSuffix()
            // 并行扫描各类数据
            val apkDeferred = async { FileScanner.getApkCount(6, autoFixSuffix = autoFixSuffix) }
            val videoDeferred = async { FileScanner.getMediaCount(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) }
            val photoDeferred = async { FileScanner.getMediaCount(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) }
            val musicDeferred = async { FileScanner.getMediaCount(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) }
            val devicesDeferred = async { scanDevices() }

            val apkCount = apkDeferred.await()
            val videoCount = videoDeferred.await()
            val photoCount = photoDeferred.await()
            val musicCount = musicDeferred.await()
            val devices = devicesDeferred.await()

            _uiState.value = _uiState.value.copy(
                apkCount = apkCount,
                videoCount = videoCount,
                photoCount = photoCount,
                musicCount = musicCount,
                devices = devices,
            )
        }
    }

    /** 当前进行中的扫描任务（refresh 去重用） */
    private var scanJob: kotlinx.coroutines.Job? = null

    /** 扫描存储设备列表 */
    private suspend fun scanDevices(): List<DeviceInfo> = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            val devices = StorageDeviceScanner.scan(context).getOrDefault(emptyList())
            devices.map { device ->
                DeviceInfo(
                    name = device.name,
                    capacity = "${device.usedGB} / ${device.totalGB} GB",
                    path = device.path,
                    usedRatio = device.usedRatio,
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }
}

