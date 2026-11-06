package com.tvflyshare.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间格式化工具，统一各页面时间戳展示，消除重复实现。
 */
object TimeUtils {
    /**
     * 将毫秒时间戳格式化为可读字符串。
     *
     * @param timestamp 毫秒时间戳；为 0 时表示未知，返回空串
     * @param withYear true 显示完整日期 yyyy/MM/dd HH:mm；false 显示 MM-dd HH:mm
     */
    fun formatTime(timestamp: Long, withYear: Boolean = true): String {
        if (timestamp == 0L) return ""
        val pattern = if (withYear) "yyyy/MM/dd HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    }
}
