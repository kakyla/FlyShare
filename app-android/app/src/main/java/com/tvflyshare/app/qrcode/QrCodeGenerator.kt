package com.tvflyshare.app.qrcode

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成器
 *
 * 使用 ZXing 本地生成二维码，不依赖任何网络。
 *
 * 用法：
 *   val bitmap = QrCodeGenerator.generate("http://192.168.1.100:5566")
 *   // 然后在 Compose 中用 bitmap.asImageBitmap() 显示
 */
object QrCodeGenerator {

    /**
     * 生成二维码 Bitmap
     *
     * @param content 二维码内容字符串（如 URL）
     * @param size    输出尺寸（像素），默认 600
     * @return 白底黑码的 Bitmap；内容过长或编码失败时返回 null（不崩溃）
     */
    fun generate(
        content: String,
        size: Int = 600,
    ): Bitmap? {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )

        val bitMatrix = try {
            MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints,
            )
        } catch (e: WriterException) {
            // 内容过长超出二维码容量，或编码失败
            return null
        } catch (e: IllegalArgumentException) {
            // 2026-10-10 审计修复：ZXing 对空串等非法内容抛的是 IllegalArgumentException
            // 而非 WriterException，原先漏接与注释承诺的"不崩溃"不符
            return null
        }

        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
