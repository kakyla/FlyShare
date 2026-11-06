package com.tvflyshare.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CancellationException

object ImageUtils {
    /**
     * Drawable 转 Bitmap 的安全转换
     */
    fun Drawable.toBitmapSafe(): Bitmap? {
        return try {
            if (this is BitmapDrawable) {
                this.bitmap
            } else {
                val width = if (intrinsicWidth > 0) intrinsicWidth else 48
                val height = if (intrinsicHeight > 0) intrinsicHeight else 48
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
                bitmap
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
}
