package com.tvflyshare.app.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 进程内「文件已落盘」事件总线。
 *
 * 背景：本应用所有写入路径（网页上传、互传下载、SMB/FTP 下载、复制/移动/删除）最终都会
 * 调用 [FileOperations.triggerMediaScan]，由那里在「媒体扫描完成」后统一发一个事件。
 * 文件管理器订阅此事件，即可在文件真正进入媒体库的瞬间静默刷新列表——
 * 属于事件驱动、近实时，且没有任何定时轮询开销（对齐「全部文件」视图的目录监听体验）。
 *
 * 线程安全：所有写入方（IO 线程、HTTP 服务线程等）都通过 [notifyChanged] 投递；
 * 使用 DROP_OLDEST 缓冲，突发批量写入只会折叠成「刷新一次」的意图，不堆积、不阻塞调用方。
 */
object MediaChangeNotifier {

    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 订阅方（文件管理器 ViewModel）收集此流，触发静默刷新 */
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** 通知「有文件落盘/媒体库已更新」，调用方无需关心是否有订阅者 */
    fun notifyChanged() {
        _events.tryEmit(Unit)
    }
}
