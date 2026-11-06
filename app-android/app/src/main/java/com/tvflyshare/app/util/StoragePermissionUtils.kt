package com.tvflyshare.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.tvflyshare.app.settings.SettingsRepository
import java.io.File

/**
 * 存储权限工具
 *
 * 各版本规则：
 * - API 21-22 (Android 5.0-5.1)：安装时授权，无需运行时请求
 * - API 23-28 (Android 6.0-9.0)：WRITE_EXTERNAL_STORAGE 运行时权限
 * - API 29 (Android 10)：WRITE_EXTERNAL_STORAGE + requestLegacyExternalStorage
 * - API 30+ (Android 11+)：MANAGE_EXTERNAL_STORAGE，需跳设置页
 *
 * 兼容策略（针对魔改电视 ROM，见授权重构 C1-C4）：
 * - C1 探活：跳 MANAGE 设置页前用 [canResolveActivity] 探活，无目标 Activity 时不硬跳，避免无反应/崩溃。
 * - C2 SAF 兜底：无 MANAGE 设置页时引导用户用系统文件选择器(OpenDocumentTree)选定目录，持久化后作为受限存储根。
 * - C3/C5 判定：API30+ 仅「所有文件访问」(MANAGE) 为真才算有权；但在 targetSdk≤29 的 legacy 模式下，
 *   WRITE_EXTERNAL_STORAGE 授予即具备 /storage/emulated/0 原始路径读写能力，此时也判为有权（见 [isLegacyStorageActive]）。
 *
 * 降级策略：
 * 权限获取失败或公共目录不可写时，降级到 App 私有目录（无需权限）
 */
object StoragePermissionUtils {

    /** 公共存储目录名 */
    private const val DIR_NAME = "TVfile"

    /** SAF 文件夹兜底总开关：无 MANAGE 设置页时引导用户选文件夹。默认开启，可一键关闭。 */
    const val SAF_FALLBACK_ENABLED: Boolean = true

    /** SAF 持久化 SharedPreferences 文件名与键 */
    private const val PREFS_NAME = "flyshare_storage_prefs"
    private const val KEY_SAF_URI = "saf_tree_uri"

    /**
     * 是否已拥有可用的存储访问权限。
     *
     * 判定逻辑（C3 修正 + C5 legacy 兼容）：
     * 1. MANAGE_EXTERNAL_STORAGE 为真（Android 11+ 已授予「所有文件访问」）→ 有权；
     * 2. 否则，若处于 legacy 外部存储模式（targetSdk≤29 的应用在 Android 11~14 上仍保持），
     *    且 WRITE_EXTERNAL_STORAGE 已运行时授予 → 视为有权（该模式下具备 /storage/emulated/0 原始读写）；
     * 3. 其余情况 → 无权。
     */
    fun hasStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            // Android 11+ 的 legacy 模式（仅 targetSdk≤29 的应用在 Android 11~14 上保持）：
            // WRITE_EXTERNAL_STORAGE 仍具真实效力，授予即能访问公共目录。
            if (isLegacyStorageActive() && hasBasicWritePermission(context)) return true
            return false
        }
        return hasBasicWritePermission(context)
    }

    /** 是否拥有基础写权限（WRITE/READ_EXTERNAL_STORAGE 运行时授权） */
    private fun hasBasicWritePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 当前是否处于 legacy 外部存储模式。
     * 仅 API 29+ 有意义；API<29 不存在 scoped storage，按非 legacy 处理。
     * 任何异常均保守返回 false（非 legacy），避免误判为「有权」。
     */
    private fun isLegacyStorageActive(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Environment.isExternalStorageLegacy()
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /** 是否拥有高级权限（Android 11+ 的 MANAGE_EXTERNAL_STORAGE） */
    fun hasAdvancedStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Android 10 及以下，拥有基础读写即拥有全部权限
        }
    }

    /**
     * 需要运行时动态请求的权限列表
     * 仅 API 23-29 使用，API 21-22 和 API 30+ 返回空数组
     */
    fun getRuntimePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            emptyArray()
        }
    }

    /**
     * 是否需要跳设置页请求（仅 Android 11+）
     */
    fun needsSettingsPage(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    /**
     * Android 11+ 跳设置页的 Intent
     */
    fun getManageStorageIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 探活：给定 Intent 是否存在可解析的目标 Activity。
     * 用于魔改 ROM 缺失「所有文件访问」设置页时的前置判断，避免硬跳导致无反应或崩溃。
     * 任何异常（含系统包管理器临时不可用）均返回 false，交由调用方降级处理。
     */
    fun canResolveActivity(context: Context, intent: Intent): Boolean {
        return try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /** 持久化 SAF 选中的目录 Tree Uri，供后续会话免重复选择 */
    fun savePersistedSafUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SAF_URI, uri.toString()).apply()
    }

    /** 读取已持久化的 SAF 目录 Uri；无则返回 null */
    fun getPersistedSafUri(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAF_URI, null) ?: return null
        return try {
            Uri.parse(stored)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * 获取存储目录（接收/下载/互传的落盘根目录）
     *
     * 优先级：
     * 1. 用户在设置中自定义的保存路径（若填写且合法/可写，见 [resolveCustomSaveDir]）；
     * 2. 默认公共目录 /storage/emulated/0/TVfile/（需存储权限）；
     * 3. 降级到 App 私有目录 getExternalFilesDir(null)/TVfile/（无需权限）。
     *
     * 自定义路径被刻意排除 Android/data、Android/obb、Android/media 等系统/私有子树：
     * 这些目录对文件管理器与电脑 USB 隐藏，写进去后用户很难把收到的文件拷出来，且易误伤其它 App。
     *
     * @param context 上下文
     * @return 存储目录 File，已确保存在
     */
    fun getStorageDir(context: Context): File {
        // 1. 用户自定义路径优先（留空则用默认）
        val rawPath = SettingsRepository.getSavePath(context).trim()
        if (rawPath.isNotEmpty()) {
            val customDir = resolveCustomSaveDir(context, rawPath)
            if (customDir != null) return customDir
            // 自定义路径不可用（越界/不可写）→ 回退默认，避免把文件写丢
        }

        // 2. 默认公共目录 / TVfile（需权限）
        if (hasStoragePermission(context)) {
            val publicDir = File(Environment.getExternalStorageDirectory(), DIR_NAME)
            if (ensureDirWritable(publicDir)) {
                return publicDir
            }
        }

        // 3. 降级到 App 私有目录（2026-10-04 审计 P2 修复：存储未挂载时 getExternalFilesDir 可返回 null，
        // 原先 File(File?, String) 退化成相对路径，mkdirs 在进程 CWD 静默失败——回退内部 filesDir）
        val externalFilesDir = context.getExternalFilesDir(null)
        val privateDir = if (externalFilesDir != null) File(externalFilesDir, DIR_NAME) else File(context.filesDir, DIR_NAME)
        ensureDirWritable(privateDir)
        return privateDir
    }

    /**
     * 解析用户自定义的保存路径：仅接受内置存储根（/storage/emulated/0）下的目录，
     * 且排除 Android/data、Android/obb、Android/media 等系统/私有子树。
     * 相对路径（不以 / 开头）按"存储根下的子文件夹名"处理。
     * 返回已创建且可写的目录；任何不合规/不可写情况返回 null（交由调用方回退默认）。
     */
    private fun resolveCustomSaveDir(context: Context, rawPath: String): File? {
        val root = Environment.getExternalStorageDirectory() ?: return null
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val candidate = if (rawPath.startsWith("/")) File(rawPath) else File(root, rawPath)
        val candCanonical = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        // 必须在内置存储根内部（含根本身），防止越出到其它卷/系统分区
        if (candCanonical != rootCanonical && !candCanonical.startsWith(rootCanonical + File.separator)) return null
        // 禁止落到 Android 系统/私有子树（对用户不可见、易误伤其它 App）
        val relative = candCanonical.removePrefix(rootCanonical + File.separator)
        if (relative.split(File.separator).firstOrNull()?.equals("Android", ignoreCase = true) == true) return null
        return if (ensureDirWritable(candidate)) candidate else null
    }

    /**
     * 校验用户填写的保存路径是否可用（供设置页弹窗实时提示）。
     * 空字符串 = 使用默认，始终合法。
     */
    fun isCustomSavePathValid(context: Context, rawPath: String): Boolean {
        val p = rawPath.trim()
        if (p.isEmpty()) return true
        return resolveCustomSaveDir(context, p) != null
    }

    /** 确保目录存在且可写 */
    private fun ensureDirWritable(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.isDirectory && dir.canWrite()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}
