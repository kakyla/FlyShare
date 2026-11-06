package com.tvflyshare.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.settings.SettingsRepository
import kotlinx.coroutines.launch
import com.tvflyshare.app.service.TransferForegroundService
import com.tvflyshare.app.util.FileScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.tvflyshare.app.TVTransferApp

data class SettingsUiState(
    val smbWorkgroup: String = SettingsRepository.DEFAULT_SMB_WORKGROUP,
    val appVersion: String = "",
    val currentTheme: String = "BLUE",
    val notificationEnabled: Boolean = false,
    val autoFixApkSuffix: Boolean = true,
    val builtInPreview: Boolean = true,
    val savePath: String = "",
    val screenOrientation: String = "AUTO"
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = (application as TVTransferApp).settingsRepository
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        loadSettings()
    }
    
    private fun loadSettings() {
        val version = try {
            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            "v${pInfo.versionName}"
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            // 2026-10-04 审计 P2 修复：原先回退硬编码 "v2.1.0"（早已过时）比"未知版本"更误导
            "v?"
        }
        
        _uiState.value = SettingsUiState(
            smbWorkgroup = repository.getSmbWorkgroup(),
            appVersion = version,
            currentTheme = repository.getTheme(),
            notificationEnabled = repository.getNotificationEnabled(),
            autoFixApkSuffix = repository.getAutoFixApkSuffix(),
            builtInPreview = repository.getBuiltInPreview(),
            savePath = repository.getSavePath(),
            screenOrientation = repository.getScreenOrientation()
        )
    }
    
    fun updateSmbWorkgroup(workgroup: String) {
        val newWorkgroup = if (workgroup.isBlank()) SettingsRepository.DEFAULT_SMB_WORKGROUP else workgroup
        repository.setSmbWorkgroup(newWorkgroup)
        _uiState.value = _uiState.value.copy(smbWorkgroup = newWorkgroup)
    }

    fun updateTheme(theme: String) {
        repository.setTheme(theme)
        _uiState.value = _uiState.value.copy(currentTheme = theme)
    }

    fun updateNotificationEnabled(enabled: Boolean) {
        repository.setNotificationEnabled(enabled)
        _uiState.value = _uiState.value.copy(notificationEnabled = enabled)
        // 通知服务动态切换前台/普通运行状态
        TransferForegroundService.setNotificationEnabled(getApplication(), enabled)
    }

    fun updateAutoFixApkSuffix(enabled: Boolean) {
        repository.setAutoFixApkSuffix(enabled)
        _uiState.value = _uiState.value.copy(autoFixApkSuffix = enabled)
        // 开关被打开的瞬间，立刻跑一次改名，把已经躺在那里的旧 .apk1/.apk.1 一次性修好
        if (enabled) {
            viewModelScope.launch { FileScanner.fixApkSuffix(6) }
        }
    }

    /** 文本/图片是否用应用内建预览打开（关掉后恢复「全部交给系统应用」的旧行为） */
    fun updateBuiltInPreview(enabled: Boolean) {
        repository.setBuiltInPreview(enabled)
        _uiState.value = _uiState.value.copy(builtInPreview = enabled)
    }

    fun updateSavePath(path: String) {
        val newPath = path.trim()
        repository.setSavePath(newPath)
        _uiState.value = _uiState.value.copy(savePath = newPath)
    }

    /** 屏幕方向：AUTO / LANDSCAPE / PORTRAIT（仅非 TV 生效，TV 永远横屏） */
    fun updateScreenOrientation(value: String) {
        repository.setScreenOrientation(value)
        _uiState.value = _uiState.value.copy(screenOrientation = value)
    }
}