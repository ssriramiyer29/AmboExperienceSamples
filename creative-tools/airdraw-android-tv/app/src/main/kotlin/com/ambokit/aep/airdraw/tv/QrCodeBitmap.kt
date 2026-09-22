package com.ambokit.aep.airdraw.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The join code as a QR the player can scan from the sofa.
 *
 * AEP hands over `joinInfo.qrUrl` as well, but that is an image the Gateway serves - fine on a
 * phone, one more network round trip on a TV that is already waiting. Rendering it locally from
 * `joinInfo.url` puts the code on screen the instant the join exists.
 */
object QrCodeBitmap {

    fun create(text: String, sizePx: Int = 640): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // A TV is watched from three metres: a quiet zone that is too small is the most
                // common reason a camera will not lock on.
                EncodeHintType.MARGIN to 2
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (problem: Exception) {
            // A missing QR is a degraded join, not a crash: the URL is still on screen to type.
            null
        }
    }
}
