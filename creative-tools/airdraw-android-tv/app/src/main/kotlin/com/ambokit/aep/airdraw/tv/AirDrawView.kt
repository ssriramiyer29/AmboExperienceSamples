package com.ambokit.aep.airdraw.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.ToolStrip
import com.ambokit.aep.core.AepConnectionState
import kotlin.math.min

/**
 * The canvas, and the strip down its edge.
 *
 * Every decision about what a line looks like was made in `airdraw-core`: this walks the points
 * it hands back and puts them on a Canvas. That split is ADR-0001 rule 2, and it is also why the
 * interesting half of AirDraw has tests.
 */
class AirDrawView(
    context: Context,
    private val engine: AirDrawEngine,
    private val strip: ToolStrip
) : View(context) {

    /** A hand on screen. Pointer space: x across 0..1, y down 0..aspect. */
    data class Cursor(val x: Float, val y: Float, val pinching: Boolean)

    var status: String = "Starting…"
    var joinUrl: String? = null
    var joinQr: Bitmap? = null
    var connection: AepConnectionState = AepConnectionState.CONNECTING
    var tracking: Boolean = false

    /**
     * Every visible hand, not only the one drawing.
     *
     * Two cursors is the honest picture, and it answers the question a player actually has when
     * nothing is happening - can it see me? - without them having to guess.
     */
    var cursors: List<Cursor> = emptyList()

    /** True once capability payloads are arriving - the phone is connected and sending. */
    var streaming: Boolean = false

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
        if (!tracking && engine.drawing.isEmpty) {
            if (streaming) drawWaitingForHands(canvas, w, h) else drawJoinPanel(canvas, w, h)
        }
        drawToolStrip(canvas, w)
        drawCursors(canvas)
        drawConnectionBanner(canvas, w, h)
    }

    /**
     * The palette, permanently.
     *
     * Six percent of the width, and the canvas is the rest. An earlier version hid this behind a
     * gesture and saved the space; what it cost was a player stuck on a colour they had not
     * chosen, with the gesture that would have released them not firing. Space is cheaper.
     */
    private fun drawToolStrip(canvas: Canvas, w: Float) {
        val cells = strip.cells(engine.viewport.aspect)
        if (cells.isEmpty()) return

        val panel = RectF(
            cells.first().left * w,
            cells.first().top * w,
            cells.first().right * w,
            cells.last().bottom * w
        )
        paint.style = Paint.Style.FILL
        paint.color = SCRIM
        canvas.drawRoundRect(panel, panel.width() * 0.3f, panel.width() * 0.3f, paint)

        for (cell in cells) {
            val cx = cell.centreX * w
            val cy = cell.centreY * w
            val size = cell.height * w

            when (cell.kind) {
                ToolStrip.Kind.COLOUR -> {
                    paint.style = Paint.Style.FILL
                    paint.color = strip.colours[cell.index]
                    canvas.drawCircle(cx, cy, size * 0.32f, paint)
                    if (cell.index == strip.colourIndex) {
                        // A ring, not a tick. At three metres a mark drawn on top of a swatch is
                        // the first thing to disappear.
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = size * 0.07f
                        paint.color = Color.WHITE
                        canvas.drawCircle(cx, cy, size * 0.42f, paint)
                        paint.style = Paint.Style.FILL
                    }
                }
                ToolStrip.Kind.UNDO -> drawUndoGlyph(canvas, cx, cy, size * 0.3f)
                ToolStrip.Kind.FLIP -> drawFlipGlyph(canvas, cx, cy, size * 0.28f)
            }
        }
    }

    /** An arrow curling back on itself - drawn rather than typed, so no font has to have it. */
    private fun drawUndoGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 20f, 280f, false, paint)

        paint.style = Paint.Style.FILL
        canvas.drawPath(Path().apply {
            moveTo(cx + radius * 0.95f, cy + radius * 0.20f)
            lineTo(cx + radius * 0.35f, cy + radius * 0.50f)
            lineTo(cx + radius * 1.15f, cy + radius * 0.80f)
            close()
        }, paint)
    }

    /** Two arrowheads pointing apart: move this strip to the other side. */
    private fun drawFlipGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFB9C0CC.toInt()
        canvas.drawPath(Path().apply {
            moveTo(cx - radius, cy)
            lineTo(cx - radius * 0.2f, cy - radius * 0.6f)
            lineTo(cx - radius * 0.2f, cy + radius * 0.6f)
            close()
            moveTo(cx + radius, cy)
            lineTo(cx + radius * 0.2f, cy - radius * 0.6f)
            lineTo(cx + radius * 0.2f, cy + radius * 0.6f)
            close()
        }, paint)
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

    /**
     * The pinching hand gets a pen; any other hand gets a faint dot.
     *
     * Both were drawn the same size at first, and a resting hand the camera happened to see read
     * as a second pen - the player had to physically hide their other hand to make the screen
     * make sense. A hand that is not drawing still deserves to be acknowledged, but quietly.
     */
    private fun drawCursors(canvas: Canvas) {
        val radius = min(width, height) * 0.018f
        for (cursor in cursors) {
            // Both axes are normalised on width, which is why y is scaled by it too.
            val x = cursor.x * width
            val y = cursor.y * width

            if (!cursor.pinching) {
                paint.style = Paint.Style.FILL
                paint.color = IDLE_HAND
                canvas.drawCircle(x, y, radius * 0.28f, paint)
                continue
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.22f
            paint.color = strip.colour
            canvas.drawCircle(x, y, radius, paint)
            // Filled while the pen is down, so "am I drawing?" is answerable from the sofa.
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius * 0.45f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /**
     * The gap between scanning and drawing.
     *
     * It takes the Companion several seconds to grant the camera, open it and load the model, and
     * for that whole time the old screen showed a stale QR code - so the player scans, nothing
     * happens, and the reasonable conclusion is that the scan failed. Saying what is being waited
     * for costs one line and removes the doubt.
     */
    private fun drawWaitingForHands(canvas: Canvas, w: Float, h: Float) {
        paint.color = INK
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * 0.045f
        canvas.drawText("AirDraw", w / 2f, h * 0.44f, paint)

        paint.color = MUTED
        paint.textSize = h * 0.028f
        canvas.drawText("Phone connected — hold up your hand", w / 2f, h * 0.52f, paint)
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
        canvas.drawText("Pinch to draw · pinch on the strip to pick · two hands to zoom", w / 2f, h * 0.88f, paint)
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
        const val SCRIM = 0xE6111827.toInt()
        const val IDLE_HAND = 0x33111827
    }
}
