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
import com.ambokit.aep.airdraw.ToolPanel
import com.ambokit.aep.core.AepConnectionState
import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.math.min

/**
 * The canvas, and nothing else.
 *
 * Every decision about what a line looks like was made in `airdraw-core`: this walks the points
 * it hands back and puts them on a Canvas. That split is ADR-0001 rule 2, and it is also why the
 * interesting half of AirDraw has tests.
 */
class AirDrawView(
    context: Context,
    private val engine: AirDrawEngine,
    private val panel: ToolPanel
) : View(context) {

    var status: String = "Starting…"
    var joinUrl: String? = null
    var joinQr: Bitmap? = null
    var connection: AepConnectionState = AepConnectionState.CONNECTING
    var tracking: Boolean = false

    /** Null until the player has said which hand they draw with. */
    var dominant: AepHandHandedness? = null

    /** The cell the drawing hand is over, while the panel is open. */
    var hover: ToolPanel.Cell? = null

    /**
     * Where the drawing hand is, in pointer space - x across 0..1, y down 0..aspect - or null
     * when no hand is visible.
     *
     * Pointer space, not document space: the cursor follows the hand, and the hand does not move
     * when the canvas is zoomed. Putting it through the viewport made it drift away from the
     * hand at any zoom other than 1, where the two spaces happen to coincide.
     */
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
        if (tracking && dominant == null) drawHandPrompt(canvas, w, h)
        drawCursor(canvas)
        if (panel.isOpen) drawToolPanel(canvas, w, h)
        else if (panel.tools.isFlashing(System.currentTimeMillis())) drawCurrentTool(canvas, w, h)
        drawConnectionBanner(canvas, w, h)
    }

    /**
     * The one question AirDraw asks, and it asks it once.
     *
     * There is no menu because there is nothing to drive a menu with yet: hands are the only
     * input, and this is the first thing that happens. A pinch is the answer and the tutorial at
     * the same time - whoever pinches has just learned how to draw.
     */
    private fun drawHandPrompt(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = SCRIM
        val top = h * 0.36f
        val bottom = h * 0.64f
        canvas.drawRect(0f, top, w, bottom, paint)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = h * 0.052f
        canvas.drawText("Pinch with the hand you draw with", w / 2f, h * 0.49f, paint)

        paint.color = 0xFFB9C0CC.toInt()
        paint.textSize = h * 0.024f
        canvas.drawText("Your other hand opens the tools", w / 2f, h * 0.565f, paint)
    }

    /**
     * The tool strip, on the drawing hand's side.
     *
     * Laid out in `airdraw-core` and only painted here, so the targets the player reaches for and
     * the targets the hit test uses are the same list by construction. Two sources of truth for
     * where a button is would mean a button that looks like it is somewhere it is not.
     */
    private fun drawToolPanel(canvas: Canvas, w: Float, h: Float) {
        val cells = panel.cells(dominant, engine.viewport.aspect)
        if (cells.isEmpty()) return
        val scale = w // pointer space is normalised on width, for x and y alike

        val strip = RectF(
            cells.first().left * scale,
            cells.first().top * scale - h * 0.02f,
            cells.first().right * scale,
            cells.last().bottom * scale + h * 0.02f
        )
        paint.style = Paint.Style.FILL
        paint.color = SCRIM
        canvas.drawRoundRect(strip, strip.width() * 0.22f, strip.width() * 0.22f, paint)

        for (cell in cells) {
            val cx = cell.centreX * scale
            val cy = cell.centreY * scale
            val size = cell.height * scale

            if (cell == hover) {
                // The hand is over this one. Shown as a filled plate rather than an outline,
                // because an outline at three metres is the first thing to disappear.
                paint.color = HOVER
                canvas.drawRoundRect(
                    RectF(strip.left + size * 0.08f, cy - size * 0.46f, strip.right - size * 0.08f, cy + size * 0.46f),
                    size * 0.3f, size * 0.3f, paint
                )
            }

            when (cell.kind) {
                ToolPanel.Kind.COLOUR -> {
                    paint.color = panel.tools.colours[cell.index]
                    canvas.drawCircle(cx, cy, size * 0.3f, paint)
                    if (cell.index == panel.tools.colourIndex) drawSelectionRing(canvas, cx, cy, size * 0.4f)
                }
                ToolPanel.Kind.WIDTH -> {
                    // Drawn as what they are: a dot at the size the pen will be.
                    paint.color = if (cell.index == panel.tools.widthIndex) Color.WHITE else INACTIVE
                    canvas.drawCircle(cx, cy, size * (0.1f + 0.07f * cell.index), paint)
                    if (cell.index == panel.tools.widthIndex) drawSelectionRing(canvas, cx, cy, size * 0.4f)
                }
                ToolPanel.Kind.UNDO -> drawUndoGlyph(canvas, cx, cy, size * 0.3f)
            }
        }
    }

    private fun drawSelectionRing(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.18f
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /** An arrow curling back on itself - drawn rather than typed, so no font has to have it. */
    private fun drawUndoGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 20f, 280f, false, paint)

        paint.style = Paint.Style.FILL
        val head = Path().apply {
            moveTo(cx + radius * 0.95f, cy + radius * 0.20f)
            lineTo(cx + radius * 0.35f, cy + radius * 0.50f)
            lineTo(cx + radius * 1.15f, cy + radius * 0.80f)
            close()
        }
        canvas.drawPath(head, paint)
    }

    /**
     * What the pen is now, for a moment after the panel closes.
     *
     * Selecting puts the strip away, and without this the player's confirmation vanishes with it.
     */
    private fun drawCurrentTool(canvas: Canvas, w: Float, h: Float) {
        val radius = h * 0.028f
        val x = if (dominant == AepHandHandedness.LEFT) w * 0.06f else w * 0.94f
        val y = h * 0.09f

        paint.style = Paint.Style.FILL
        paint.color = SCRIM
        canvas.drawCircle(x, y, radius * 1.7f, paint)
        paint.color = panel.tools.colour
        canvas.drawCircle(x, y, radius, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y + radius * 0.02f, radius * (0.12f + 0.08f * panel.tools.widthIndex), paint)
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
        val (pointerX, pointerY) = cursor ?: return
        // Both axes are normalised on width, which is why y is scaled by it too.
        val x = pointerX * width
        val y = pointerY * width
        val radius = min(width, height) * 0.018f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.22f
        paint.color = panel.tools.colour
        canvas.drawCircle(x, y, radius, paint)

        // A dot inside the ring while the pen is down, so "am I drawing?" is answerable at a
        // glance from three metres away.
        if (engine.inProgress.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = panel.tools.colour
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
        canvas.drawText("Pinch to draw · two hands to zoom · other hand opens the tools", w / 2f, h * 0.88f, paint)
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
        const val INACTIVE = 0x66FFFFFF
        const val HOVER = 0x33FFFFFF
    }
}
