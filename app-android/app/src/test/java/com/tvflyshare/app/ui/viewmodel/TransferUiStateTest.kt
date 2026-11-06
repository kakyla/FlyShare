package com.tvflyshare.app.ui.viewmodel

import com.tvflyshare.app.network.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TransferUiState] 默认状态契约测试（纯 JVM）。
 * 锁定传输页初始 UI 状态，防止后续改动意外改变首屏默认行为。
 */
class TransferUiStateTest {

    @Test
    fun 默认状态符合初始契约() {
        val s = TransferUiState()
        assertTrue("初始应处于加载中", s.isLoading)
        assertTrue("初始文件列表应为空", s.fileList.isEmpty())
        assertEquals("初始路径应为根目录", "", s.currentPath)
        assertFalse("初始不应处于多选模式", s.isMultiSelectMode)
        assertTrue("初始选中集合应为空", s.selectedIds.isEmpty())
        assertEquals(NetworkType.NONE, s.networkType)
        assertFalse("初始不应显示批量删除确认", s.showBatchDeleteConfirm)
    }
}
