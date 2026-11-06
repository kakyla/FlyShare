package com.tvflyshare.app.util

import com.tvflyshare.app.Constants

/**
 * 删除风险分级：文件管理器删除前的「高危目录」识别与警示。
 *
 * 背景（已代码核查确认，见 .workbuddy/memory/2026-08-15.md）：
 * - FlyShare 声明 MANAGE_EXTERNAL_STORAGE，授权后具备 /storage/emulated/0 原始读写；
 * - 文件管理器根目录 = Constants.INTERNAL_STORAGE_ROOT = "/storage/emulated/0"（存储卷根），
 *   用户对别家 Android/data 是能进能删的；
 * - 原删除逻辑（FileOperations.deleteFiles / deleteFileWithFeedback）为裸删除，无高危识别。
 * 故在删除确认前做分级，避免误删系统数据目录 / 整块存储用户文件。
 *
 * 分级：
 * - NONE                 普通文件/文件夹，按原确认流程（仅普通「不可恢复」提示）。
 * - APP_EXTERNAL         命中 <root>/Android/{data,obb,media}/<pkg>（单应用外部数据）：普通确认加一行提示；
 *                        自家包名 com.tvflyshare.app 视为无害，降级为 NONE。
 * - ANDROID_TREE         命中 <root>/Android 整树或 <root>/Android/{data,obb,media} 整子树（系统数据目录）：高危红色二次确认。
 * - STORAGE_ROOT_SELECT_ALL  处于存储卷根且已选中当前目录全部可见项（根目录全选）：高危红色二次确认（防照片视频下载被误清）。
 */
enum class DeleteRiskLevel { NONE, APP_EXTERNAL, ANDROID_TREE, STORAGE_ROOT_SELECT_ALL }

data class DeleteRisk(
    val level: DeleteRiskLevel,
    val appPackage: String? = null
) {
    val isHighRisk: Boolean
        get() = level == DeleteRiskLevel.ANDROID_TREE || level == DeleteRiskLevel.STORAGE_ROOT_SELECT_ALL
}

object DeleteRiskClassifier {
    const val OWN_PACKAGE = "com.tvflyshare.app"
    private const val ANDROID_DIR = "Android"
    private val ANDROID_SUBDIRS = setOf("data", "obb", "media")

    /**
     * 分类一组待删路径。
     * @param paths         待删绝对路径列表
     * @param isStorageRoot 当前是否处于存储卷根目录（用于根目录全选判定）
     * @param isSelectAll   是否已选中当前目录全部可见项（根目录全选）
     */
    fun classify(paths: List<String>, isStorageRoot: Boolean, isSelectAll: Boolean): DeleteRisk {
        if (paths.isEmpty()) return DeleteRisk(DeleteRiskLevel.NONE)

        // 根目录全选删除：清掉照片/视频/下载/应用数据等全部用户文件，最高危
        if (isStorageRoot && isSelectAll) {
            return DeleteRisk(DeleteRiskLevel.STORAGE_ROOT_SELECT_ALL)
        }

        var hasAndroidTree = false
        var appPkg: String? = null

        for (raw in paths) {
            // 2026-10-04 审计 P1 修复：入参是带存储卷根前缀的绝对路径（/storage/emulated/0/...），
            // 原先直接拆段后 segs[1]=="emulated"，segs[1]=="Android" 永不命中——
            // ANDROID_TREE / APP_EXTERNAL 两级警示在真实运行中 100% 不触发。
            // 2026-10-10 审计修复：removePrefix 无分隔符边界校验——/storage/emulated/0Backup/...
            // 这类同形前缀会被误剥离导致漏判；且 /sdcard/... 别名写法原先实际不剥离（注释与实现
            // 不符）。改为显式按「根 + 分隔符」前缀剥离，并兼容 /sdcard 别名。
            val trimmed = raw.trimEnd('/')
            val rel = when {
                trimmed == Constants.INTERNAL_STORAGE_ROOT -> ""
                trimmed.startsWith("${Constants.INTERNAL_STORAGE_ROOT}/") ->
                    trimmed.removePrefix("${Constants.INTERNAL_STORAGE_ROOT}/")
                trimmed.startsWith("/sdcard/") -> trimmed.removePrefix("/sdcard/")
                else -> trimmed.trimStart('/')
            }
            val segs = rel.split('/').filter { it.isNotEmpty() }
            // 仅「存储卷根下的第一层 Android」才视为系统数据目录，排除用户自建同名文件夹
            if (segs.isNotEmpty() && segs[0] == ANDROID_DIR) {
                when {
                    segs.size == 1 -> hasAndroidTree = true // <root>/Android 整树
                    segs.size == 2 && segs[1] in ANDROID_SUBDIRS -> hasAndroidTree = true // <root>/Android/{data,obb,media}
                    segs.size >= 3 && segs[1] in ANDROID_SUBDIRS -> {
                        // <root>/Android/{data,obb,media}/<pkg> 或更深：单应用外部数据
                        val pkg = segs[2]
                        if (pkg != OWN_PACKAGE) appPkg = pkg
                    }
                }
            }
        }

        return when {
            hasAndroidTree -> DeleteRisk(DeleteRiskLevel.ANDROID_TREE)
            appPkg != null -> DeleteRisk(DeleteRiskLevel.APP_EXTERNAL, appPkg)
            else -> DeleteRisk(DeleteRiskLevel.NONE)
        }
    }
}
