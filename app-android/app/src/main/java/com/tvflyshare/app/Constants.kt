package com.tvflyshare.app

/**
 * 全局常量定义，集中管理魔法值，避免散落各处的字面量重复与失同步。
 */
object Constants {
    /** 内部存储根路径（绝大多数设备为 /storage/emulated/0） */
    const val INTERNAL_STORAGE_ROOT = "/storage/emulated/0"

    /** FileProvider authority 后缀，完整 authority 为 "${packageName}.fileprovider" */
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = "fileprovider"
}
