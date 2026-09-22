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
 *
 * **Never call this on the main thread.** The first version did, and built the bitmap with
 * `setPixel` in a nested loop - 409,600 JNI crossings, each with its own bounds and recycle
 * checks. On a television's SoC that took longer than the five seconds Android waits before
 * declaring an application unresponsive, so AirDraw was killed on startup, intermittently,
 * depending on whether the loop finished before the window took focus. The fix is one call with
 * a prepared array, which is roughly two orders of magnitude cheaper, and doing it off the main
 * thread anyway because no amount of cheap makes image encoding a thing the UI thread should do.
 */
object QrCodeBitmap {

    fun create(text: String, sizePx: Int = 512): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // A TV is watched from three metres: a quiet zone that is too small is the most
                // common reason a camera will not lock on.
                EncodeHintType.MARGIN to 2
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height

            // One row at a time into a plain int array, then one call to hand the whole thing to
            // the bitmap. Every pixel is still visited; none of them crosses into native code.
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (problem: Exception) {
            // A missing QR is a degraded join, not a crash: the URL is still on screen to type.
            null
        }
    }
}
