package com.tvflyshare.app.filebrowser.model

/**
 * 一种浏览来源支持哪些操作。
 *
 * 三种协议的能力天然不同，不能用「一视同仁」的菜单硬拉平：
 * - 本地：全部支持，且有冲突策略（覆盖/跳过/重命名）与 APK 安装
 * - SMB：支持复制/移动（仅同一共享内）、下载到本机、收藏
 * - FTP：没有复制/移动、没有收藏
 * - 互传（对方设备）：走 HTTP，多一个「把本机文件发过去」，没有复制/移动与收藏
 *
 * 页面按此声明渲染菜单，能力差异集中在一处，不再散落在四个页面的 if 里。
 */
data class BrowserCapabilities(
    /** 打开文件（远端需要先下载） */
    val open: Boolean = true,
    /** 安装 APK */
    val install: Boolean = true,
    /** 重命名 */
    val rename: Boolean = true,
    /** 源头内部复制（本地、SMB 共享内） */
    val copy: Boolean = false,
    /** 源头内部移动（本地、SMB 共享内） */
    val move: Boolean = false,
    /** 下载到本机（远端来源） */
    val download: Boolean = false,
    /** 新建文件夹 */
    val createFolder: Boolean = true,
    /** 删除 */
    val delete: Boolean = true,
    /** 收藏路径（本地收藏 / 共享收藏） */
    val bookmark: Boolean = false,
    /** 上传本机文件到当前目录（互传） */
    val upload: Boolean = false,
    /** 支持网格视图 */
    val gridView: Boolean = true,
    /** 支持显示/隐藏隐藏项开关 */
    val hiddenToggle: Boolean = true,
) {
    companion object {
        /** 本地存储 */
        val LOCAL = BrowserCapabilities(
            copy = true, move = true, bookmark = true,
        )
        /** SMB 共享 */
        val SMB = BrowserCapabilities(
            copy = true, move = true, download = true, bookmark = true,
        )
        /** FTP 服务器 */
        val FTP = BrowserCapabilities(
            download = true,
        )
        /** 互传：对方设备 */
        val REMOTE = BrowserCapabilities(
            copy = false, move = false, download = true, bookmark = false,
            upload = true, gridView = false, hiddenToggle = false,
        )
    }
}
