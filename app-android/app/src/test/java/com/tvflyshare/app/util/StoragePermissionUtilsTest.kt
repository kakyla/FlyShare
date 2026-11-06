package com.tvflyshare.app.util

import android.Manifest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [StoragePermissionUtils.hasStoragePermission] 判定逻辑回归测试。
 *
 * 覆盖目标（授权重构 C3 + C5）：
 * - API ≤ 29：仅看 WRITE_EXTERNAL_STORAGE 运行时授权；
 * - API 30+：MANAGE 为真即有权；否则需 legacy 模式 + WRITE 同时成立；两者皆无则无权。
 *
 * API 30 用例依赖 android-all-instrumented:11 镜像，早前因网络原因不可用而临时 @Ignore；
 * 现镜像已可获取，三例恢复实跑，C5 的 legacy 判定分支纳入自动化回归。
 * 真机（Android 11 电视）端到端验证见 docs/e2e-transfer-verification-2026-08-09.md。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [EnvironmentShadow::class])
class StoragePermissionUtilsTest {

    @After
    fun tearDown() {
        EnvironmentShadow.reset()
    }

    @Test
    fun api28下写权限已授予时判为有权() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(StoragePermissionUtils.hasStoragePermission(app))
    }

    @Test
    fun api28下写权限未授予时判为无权() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertFalse(StoragePermissionUtils.hasStoragePermission(app))
    }

    @Test
    fun api28下needsSettingsPage为假() {
        assertFalse(StoragePermissionUtils.needsSettingsPage())
    }

    @Test
    fun api28下hasAdvancedStoragePermission恒为真() {
        assertTrue(StoragePermissionUtils.hasAdvancedStoragePermission())
    }

    @Test
    @Config(sdk = [30])
    fun api30下MANAGE已授予时判为有权() {
        EnvironmentShadow.externalStorageManager = true
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(StoragePermissionUtils.hasStoragePermission(app))
    }

    @Test
    @Config(sdk = [30])
    fun api30下legacy模式叠加写权限时判为有权() {
        EnvironmentShadow.externalStorageManager = false
        EnvironmentShadow.externalStorageLegacy = true
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(StoragePermissionUtils.hasStoragePermission(app))
    }

    @Test
    @Config(sdk = [30])
    fun api30下非legacy且无MANAGE时判为无权() {
        EnvironmentShadow.externalStorageManager = false
        EnvironmentShadow.externalStorageLegacy = false
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertFalse(StoragePermissionUtils.hasStoragePermission(app))
    }
}
