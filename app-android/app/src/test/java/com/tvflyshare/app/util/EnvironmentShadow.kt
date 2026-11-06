package com.tvflyshare.app.util

import android.os.Build
import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/**
 * Environment 自定义 Shadow。
 *
 * Robolectric 内建 ShadowEnvironment 未暴露 [Environment.isExternalStorageManager] 与
 * [Environment.isExternalStorageLegacy] 的可控开关，而这两个方法正是
 * [StoragePermissionUtils.hasStoragePermission] 中 C3/C5 判定的分支条件，
 * 必须可在测试中独立置位，才能覆盖「MANAGE 已授予」「legacy + WRITE」「两者皆无」三条路径。
 *
 * 两个 @Implementation 均按真实 API 引入版本标注 minSdk，
 * 低于该版本的 android-all 镜像不含对应方法，标注可避免 Robolectric 校验失败。
 */
@Implements(Environment::class)
class EnvironmentShadow {

    companion object {

        /** 模拟「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 是否已授予 */
        @JvmStatic
        var externalStorageManager: Boolean = false

        /** 模拟当前是否处于 legacy 外部存储模式 */
        @JvmStatic
        var externalStorageLegacy: Boolean = false

        @JvmStatic
        @Implementation(minSdk = Build.VERSION_CODES.R)
        fun isExternalStorageManager(): Boolean = externalStorageManager

        @JvmStatic
        @Implementation(minSdk = Build.VERSION_CODES.Q)
        fun isExternalStorageLegacy(): Boolean = externalStorageLegacy

        /** 每个用例结束后复位，避免状态跨用例污染 */
        @JvmStatic
        @Resetter
        fun reset() {
            externalStorageManager = false
            externalStorageLegacy = false
        }
    }
}
