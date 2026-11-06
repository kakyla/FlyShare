package com.tvflyshare.app.util

import java.io.File

/**
 * 共享根目录越界校验。
 *
 * 不能用 `child.canonicalPath.startsWith(root.canonicalPath)` 做前缀判断：
 * 同级目录名共享前缀即可绕过（如 root=TVfile、path=../TVfileBackup/x，
 * child 的 canonicalPath 以 "TVfile" 开头，但已越出共享根目录）。
 *
 * 正确判据：child 等于 root 本身，或 child 的 canonicalPath 以
 * root 的 canonicalPath + 文件分隔符 为前缀（即确为 root 内部的子路径）。
 */
fun isPathInsideRoot(root: File, child: File): Boolean {
    val rootPath = root.canonicalPath
    val childPath = child.canonicalPath
    if (childPath == rootPath) return true
    return childPath.startsWith(rootPath + File.separator)
}

/**
 * 传输中间态的隐藏临时文件前缀清单：
 * - `._flyup_tmp_`：网页/互传**上传**接收阶段直写目标目录的临时文件（HttpServerManager）；
 * - `._flydl_tmp_`：互传**下载**到本机阶段的临时文件（RemoteBrowseViewModel）。
 *
 * 两者都收完即原子改名为正式名，改名前不应出现在任何文件列表里。
 * 本地各列表已按"点开头=隐藏文件"过滤；服务端两个列表 API 用 [isHiddenTempFile] 精确过滤
 * （不能用 `.` 或 `._` 做笼统前缀：用户 SD 卡上可能本来就有 macOS 资源 forks 类 `._xxx` 文件）。
 */
const val DOWNLOAD_TMP_PREFIX = "_flydl_tmp_"

val HIDDEN_TEMP_PREFIXES = listOf(
    "._flyup_tmp_",
    // 必须与生成方同式：RemoteBrowseViewModel 里是 "." + DOWNLOAD_TMP_PREFIX。
    // 2026-11-01 补单测实测暴露：这里原先写成 "._" + DOWNLOAD_TMP_PREFIX（点后两个下划线），
    // 与生成方的 "._flydl_tmp_" 对不上 —— 下载半成品漏过服务端列表过滤，
    // 被当成普通文件报给远端设备（远端看得到、能下载残缺文件，收完又凭空消失）。
    "." + DOWNLOAD_TMP_PREFIX,
)

/** 文件名是否为传输中间态的隐藏临时文件（不应出现在文件列表中） */
fun isHiddenTempFile(name: String): Boolean = HIDDEN_TEMP_PREFIXES.any { name.startsWith(it) }
