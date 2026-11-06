package com.tvflyshare.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置数据仓库
 * 负责管理应用的所有本地设置项，封装 SharedPreferences 的读写操作，避免硬编码 Key 散落在各处。
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "tv_transfer_settings"
        private const val KEY_SMB_WORKGROUP = "smb_workgroup"
        private const val KEY_THEME = "app_theme"
        private const val KEY_SCREEN_ORIENTATION = "screen_orientation"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_AUTO_FIX_APK_SUFFIX = "auto_fix_apk_suffix"
        private const val KEY_BUILT_IN_PREVIEW = "built_in_preview"
        private const val KEY_SAVE_PATH = "save_path"
        const val DEFAULT_SAVE_PATH = ""
        /**
         * 不依赖 App 实例，直接按 context 读保存路径设置。
         * 供 StoragePermissionUtils.getStorageDir 等没有 repository 单例处调用，避免重复声明 Key/PREFS。
         */
        fun getSavePath(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAVE_PATH, DEFAULT_SAVE_PATH) ?: DEFAULT_SAVE_PATH
        }
        const val DEFAULT_SMB_WORKGROUP = "WORKGROUP"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeFlow = MutableStateFlow(getTheme())
    val themeFlow: StateFlow<String> = _themeFlow.asStateFlow()

    private val _screenOrientationFlow = MutableStateFlow(getScreenOrientation())
    val screenOrientationFlow: StateFlow<String> = _screenOrientationFlow.asStateFlow()

    fun getTheme(): String {
        return prefs.getString(KEY_THEME, "BLUE") ?: "BLUE"
    }

    fun setTheme(theme: String) {
        prefs.edit().putString(KEY_THEME, theme).apply()
        _themeFlow.value = theme
    }

    /**
     * 屏幕方向偏好：AUTO（跟随传感器，默认）/ LANDSCAPE（锁横屏）/ PORTRAIT（锁竖屏）。
     * 仅非 TV 设备生效；TV 始终横屏（见 MainActivity）。
     */
    fun getScreenOrientation(): String {
        return prefs.getString(KEY_SCREEN_ORIENTATION, "AUTO") ?: "AUTO"
    }

    fun setScreenOrientation(value: String) {
        prefs.edit().putString(KEY_SCREEN_ORIENTATION, value).apply()
        _screenOrientationFlow.value = value
    }

    /**
     * 是否显示传输服务的常驻通知。
     * 默认关闭（false）：服务以普通后台服务运行，不显示通知；
     * 开启后服务以「前台服务」运行并保持常驻通知。
     */
    fun getNotificationEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_ENABLED, false)
    }

    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
    }

    /**
     * 是否自动修正安装包后缀（微信/QQ 接收的 APK 被改名为 .apk1/.apk.1）。
     * 默认开启（true）：进入安装包列表/刷新角标前自动还原为 .apk，详见 FileScanner.fixApkSuffix。
     */
    fun getAutoFixApkSuffix(): Boolean {
        return prefs.getBoolean(KEY_AUTO_FIX_APK_SUFFIX, true)
    }

    fun setAutoFixApkSuffix(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FIX_APK_SUFFIX, enabled).apply()
    }

    /**
     * 文本/图片是否用应用内建的预览打开。
     *
     * 默认开启（true）。关掉后「打开」恢复为原来的行为——全部交给电视上的第三方应用。
     * 留这个开关是为了万一预览页在某台设备上出问题，用户能自己当场退回，不必等新版本。
     * 每次点击「打开」时实时读取，改完立即生效。
     */
    fun getBuiltInPreview(): Boolean {
        return prefs.getBoolean(KEY_BUILT_IN_PREVIEW, true)
    }

    fun setBuiltInPreview(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUILT_IN_PREVIEW, enabled).apply()
    }

    /**
     * 用户自定义的文件保存路径（接收/下载落盘目录）。
     * 空字符串 = 使用默认 TVfile 目录。读取/落盘逻辑统一在 StoragePermissionUtils.getStorageDir。
     */
    fun getSavePath(): String {
        return prefs.getString(KEY_SAVE_PATH, DEFAULT_SAVE_PATH) ?: DEFAULT_SAVE_PATH
    }

    fun setSavePath(path: String) {
        prefs.edit().putString(KEY_SAVE_PATH, path).apply()
    }

    /**
     * 获取 SMB 默认工作组
     */
    fun getSmbWorkgroup(): String {
        return prefs.getString(KEY_SMB_WORKGROUP, DEFAULT_SMB_WORKGROUP) ?: DEFAULT_SMB_WORKGROUP
    }

    /**
     * 保存 SMB 默认工作组
     */
    fun setSmbWorkgroup(workgroup: String) {
        prefs.edit().putString(KEY_SMB_WORKGROUP, workgroup).apply()
    }

    /**
     * 获取本地收藏夹
     */
    fun getLocalBookmarks(): List<Pair<String, String>> {
        val set = prefs.getStringSet("local_bookmarks", emptySet()) ?: emptySet()
        return set.mapNotNull {
            val parts = it.split("|||")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.sortedBy { it.first }
    }

    /**
     * 添加本地收藏
     */
    fun addLocalBookmark(name: String, path: String) {
        // 2026-10-10 审计修复：名称/路径含分隔符 "|||" 时序列化后 split 字段数不符，
        // getLocalBookmarks 会静默丢弃——收藏"写入成功但永不显示"的数据黑洞，写入前拒绝
        if (name.contains("|||") || path.contains("|||")) return
        val set = prefs.getStringSet("local_bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.endsWith("|||$path") }
        set.add("$name|||$path")
        prefs.edit().putStringSet("local_bookmarks", set).apply()
    }

    /**
     * 移除本地收藏
     */
    fun removeLocalBookmark(path: String) {
        val set = prefs.getStringSet("local_bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.endsWith("|||$path") }
        prefs.edit().putStringSet("local_bookmarks", set).apply()
    }

    /**
     * 获取 SMB 收藏夹（基于 host 和 shareName 过滤）
     */
    fun getSmbBookmarks(host: String, shareName: String): List<Pair<String, String>> {
        val set = prefs.getStringSet("smb_bookmarks", emptySet()) ?: emptySet()
        return set.mapNotNull {
            val parts = it.split("|||")
            if (parts.size == 4 && parts[2] == host && parts[3] == shareName) parts[0] to parts[1] else null
        }.sortedBy { it.first }
    }

    /**
     * 添加 SMB 收藏
     */
    fun addSmbBookmark(name: String, path: String, host: String, shareName: String) {
        // 2026-10-10 审计修复：任一字段含分隔符 "|||" 都会造成解析错位/丢弃，写入前拒绝
        if (name.contains("|||") || path.contains("|||") ||
            host.contains("|||") || shareName.contains("|||")
        ) return
        val set = prefs.getStringSet("smb_bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.endsWith("|||$path|||$host|||$shareName") }
        set.add("$name|||$path|||$host|||$shareName")
        prefs.edit().putStringSet("smb_bookmarks", set).apply()
    }

    /**
     * 移除 SMB 收藏
     */
    fun removeSmbBookmark(path: String, host: String, shareName: String) {
        val set = prefs.getStringSet("smb_bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.endsWith("|||$path|||$host|||$shareName") }
        prefs.edit().putStringSet("smb_bookmarks", set).apply()
    }
}