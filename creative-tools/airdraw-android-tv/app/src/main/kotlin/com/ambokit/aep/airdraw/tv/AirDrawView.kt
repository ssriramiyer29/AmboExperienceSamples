package com.ambokit.aep.airdraw.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.core.AepConnectionState
import kotlin.math.min

/**
 * The canvas, and nothing else.
 *
 * Every decision about what a line looks like was made in `airdraw-core`: this walks the points
 * it hands back and puts them on a Canvas. That split is ADR-0001 rule 2, and it is also why the
 * interesting half of AirDraw has tests.
 */
class AirDrawView(context: Context, private val engine: AirDrawEngine) : View(context) {

    var status: String = "Starting…"
    var joinUrl: String? = null
    var joinQr: Bitmap? = null
    var connection: AepConnectionState = AepConnectionState.CONNECTING
    var tracking: Boolean = false

    /** Document-space cursor, or null when no hand is visible. */
    var cursor: Pair<Float, Float>? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Called each frame: the surface can be resized under us and the viewport must follow. */
    fun sizeViewport() {
        if (width <= 0 || height <= 0) return
        engine.viewport.widthPx = width.toFloat()
        engine.viewport.heightPx = height.toFloat()
        engine.viewport.aspect = height.toFloat() / width.toFloat()
    }

    fun recycleBitmaps() {
        joinQr?.let { if (!it.isRecycled) it.recycle() }
        joinQr = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Paper, not a dark UI. The drawing is the interface.
        canvas.drawColor(PAPER)

        drawStrokes(canvas)
        if (!tracking && engine.drawing.isEmpty) drawJoinPanel(canvas, w, h)
        drawCursor(canvas)
        drawConnectionBanner(canvas, w, h)
    }

    private fun drawStrokes(canvas: Canvas) {
        for (stroke in engine.render()) {
            strokePaint.color = stroke.argb
            // Width varies along the stroke, so each segment is drawn with its own. A Path would
            // be fewer calls and one uniform width, which is the thing that makes a line look
            // machine-made rather than drawn.
            var i = 0
            while (i < stroke.size - 1) {
                strokePaint.strokeWidth = stroke.widths[i]
                canvas.drawLine(
                    stroke.points[i * 2], stroke.points[i * 2 + 1],
                    stroke.points[(i + 1) * 2], stroke.points[(i + 1) * 2 + 1],
                    strokePaint
                )
                i++
            }
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val (docX, docY) = cursor ?: return
        val x = engine.viewport.toScreenX(docX)
        val y = engine.viewport.toScreenY(docY)
        val radius = min(width, height) * 0.018f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.22f
        paint.color = engine.colour
        canvas.drawCircle(x, y, radius, paint)

        // A dot inside the ring while the pen is down, so "am I drawing?" is answerable at a
        // glance from three metres away.
        if (engine.inProgress.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius * 0.45f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawJoinPanel(canvas: Canvas, w: Float, h: Float) {
        paint.color = INK
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * 0.045f
        canvas.drawText("AirDraw", w / 2f, h * 0.16f, paint)

        paint.textSize = h * 0.026f
        paint.color = MUTED
        canvas.drawText(status, w / 2f, h * 0.23f, paint)

        val qr = joinQr
        if (qr != null && !qr.isRecycled) {
            val size = min(w * 0.22f, h * 0.38f)
            val left = w / 2f - size / 2f
            val top = h * 0.30f
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(left - 16f, top - 16f, left + size + 16f, top + size + 16f), 20f, 20f, paint)
            canvas.drawBitmap(qr, null, RectF(left, top, left + size, top + size), paint)
        }

        joinUrl?.let {
            paint.color = MUTED
            paint.textSize = h * 0.018f
            canvas.drawText(it, w / 2f, h * 0.78f, paint)
        }

        paint.color = INK
        paint.textSize = h * 0.022f
        canvas.drawText("Pinch to draw · two hands to zoom · or draw on the phone", w / 2f, h * 0.88f, paint)
    }

    /**
     * The one piece of UI that must survive every other screen.
     *
     * A link that drops mid-drawing otherwise shows the player nothing: the cursor simply stops,
     * which reads as the app breaking rather than as the phone dropping.
     */
    private fun drawConnectionBanner(canvas: Canvas, w: Float, h: Float) {
        val message = when (connection) {
            AepConnectionState.RECONNECTING -> "Reconnecting to your phone…"
            AepConnectionState.REJOIN_REQUIRED -> "Connection lost — scan the code again"
            AepConnectionState.CLOSED -> "Disconnected"
            else -> return
        }
        val bannerHeight = h * 0.07f
        paint.style = Paint.Style.FILL
        paint.color = 0xF2181210.toInt()
        canvas.drawRect(0f, 0f, w, bannerHeight, paint)
        paint.color = 0xFFFFA84C.toInt()
        canvas.drawRect(0f, bannerHeight - h * 0.004f, w, bannerHeight, paint)

        paint.color = 0xFFFFE2C4.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * 0.026f
        canvas.drawText(message, w / 2f, bannerHeight * 0.64f, paint)
    }

    private companion object {
        const val PAPER = 0xFFF8F4EC.toInt()
        const val INK = 0xFF111827.toInt()
        const val MUTED = 0xFF6B7280.toInt()
    }
}
