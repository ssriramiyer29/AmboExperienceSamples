package com.ambokit.aep.airdraw.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.RectF
import android.view.View
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.AirDrawPoint
import com.ambokit.aep.airdraw.BrushType
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

    /**
     * A hand on screen. Pointer space: x across 0..1, y down 0..aspect.
     *
     * [waiting] means the pen is down but the tracker has lost the hand: the cursor is showing
     * where it last was, and is drawn hollow to say so.
     */
    data class Cursor(
        val x: Float,
        val y: Float,
        val pinching: Boolean,
        val waiting: Boolean = false
    )

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

    /**
     * Called each frame: the surface can be resized under us and the viewport must follow.
     *
     * The viewport describes the *drawing area*, not the screen. The toolbar band along the
     * bottom is not canvas, so document space stops where the band starts - otherwise a stroke
     * taken to the bottom of the drawing would be stored underneath the tools.
     */
    fun sizeViewport() {
        if (width <= 0 || height <= 0) return
        val sheet = canvasRect(width.toFloat(), height.toFloat())
        // Document space spans the sheet, not the screen: x 0..1 across the paper and y 0..aspect
        // down it. Anything else and a stroke taken to the edge of the drawing would be stored
        // out under the chrome.
        engine.viewport.widthPx = sheet.width()
        engine.viewport.heightPx = sheet.height()
        engine.viewport.aspect = sheet.height() / sheet.width()
    }

    /**
     * A pointer position turned into a place on the drawing.
     *
     * Pointer space covers the whole screen; document space covers only the sheet. Keeping the
     * conversion here means the one place that knows where the sheet is is the one that draws it -
     * the alternative is the activity recomputing the same insets and the two drifting apart.
     *
     * @return null when the pointer is not over the paper at all.
     */
    fun documentPoint(pointerX: Float, pointerY: Float, timeMs: Long): AirDrawPoint? {
        if (width <= 0 || height <= 0) return null
        val sheet = canvasRect(width.toFloat(), height.toFloat())
        if (sheet.width() <= 0f) return null
        val screenX = pointerX * width - sheet.left
        val screenY = pointerY * width - sheet.top
        // Off the paper is not a place on the drawing. Without this, a pointer up in the header
        // or down in the toolbar would be stored at a negative or past-the-end coordinate.
        if (screenX < 0f || screenY < 0f ||
            screenX > sheet.width() || screenY > sheet.height()) return null
        return AirDrawPoint(
            x = engine.viewport.toDocumentX(screenX),
            y = engine.viewport.toDocumentY(screenY),
            pressure = 1f,
            timeMs = timeMs
        )
    }

    /**
     * The screen's shape in pointer units.
     *
     * Public because it is the space the *pointer* lives in - the whole screen, toolbar included.
     * The viewport's aspect describes only the sheet, and mapping the pointer through that one
     * put its maximum y above where the toolbar begins, so the band was unreachable by
     * arithmetic: no amount of reaching could produce a y inside it.
     */
    val screenAspect: Float
        get() = if (width > 0) height.toFloat() / width.toFloat() else ToolStrip.DEFAULT_ASPECT

    /** The white sheet: everything above the toolbar, inset a little from the screen edges. */
    private fun canvasRect(w: Float, h: Float): RectF {
        val top = h * HEADER_SHARE
        val bottom = strip.barTop(screenAspect) * w
        return RectF(w * CANVAS_INSET, top, w - w * CANVAS_INSET, bottom)
    }

    fun recycleBitmaps() {
        joinQr?.let { if (!it.isRecycled) it.recycle() }
        joinQr = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Dark chrome around a bright sheet. The drawing is the brightest thing on screen and
        // everything else recedes, which is the whole reason the chrome is not paper-coloured:
        // a cream toolbar on cream paper made the tools compete with the picture.
        canvas.drawColor(CHROME)

        val sheet = canvasRect(w, h)
        paint.style = Paint.Style.FILL
        paint.color = PAPER
        canvas.drawRoundRect(sheet, w * SHEET_RADIUS, w * SHEET_RADIUS, paint)

        // Strokes are clipped to the sheet, so a mark taken past its edge stops at the paper
        // rather than running out across the chrome.
        val saved = canvas.save()
        canvas.clipRect(sheet.left, sheet.top, sheet.right, sheet.bottom)
        canvas.translate(sheet.left, sheet.top)
        drawStrokes(canvas)
        canvas.restoreToCount(saved)

        drawHeader(canvas, w, h)
        if (!tracking && engine.drawing.isEmpty) {
            if (streaming) drawWaitingForHands(canvas, w, h) else drawJoinPanel(canvas, w, h)
        }
        drawToolStrip(canvas, w)
        drawCursors(canvas)
        drawConnectionBanner(canvas, w, h)
    }

    /**
     * The wordmark, so the screen says what it is.
     *
     * Drawn rather than shipped as an image: one less asset per screen density, and it scales
     * with the television instead of in steps.
     */
    private fun drawHeader(canvas: Canvas, w: Float, h: Float) {
        val baseline = h * HEADER_SHARE * 0.62f
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.WHITE
        paint.textSize = h * 0.058f
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        canvas.drawText("AirDraw", w * CANVAS_INSET, baseline, paint)
        val markWidth = paint.measureText("AirDraw")

        paint.setTypeface(Typeface.DEFAULT)
        paint.color = HEADER_MUTED
        paint.textSize = h * 0.028f
        canvas.drawText("Move. Draw. Create.", w * CANVAS_INSET + markWidth + w * 0.018f, baseline, paint)
    }

    /**
     * The toolbar, permanently, across the bottom.
     *
     * An early version hid it behind a gesture and saved the space; what that cost was a player
     * stuck on a colour they had not chosen, with the gesture that would have released them not
     * firing. Space is cheaper - that decision is closed.
     *
     * A later version put it down the right-hand edge. That worked and read as a wall of twelve
     * targets, so it is a band now, and the space between the groups does the explaining that a
     * uniform column could not.
     */
    private fun drawToolStrip(canvas: Canvas, w: Float) {
        val cells = strip.cells(screenAspect)
        if (cells.isEmpty()) return

        for ((index, cell) in cells.withIndex()) {
            val rect = RectF(cell.left * w, cell.top * w, cell.right * w, cell.bottom * w)

            // Where the remote is. Drawn behind the cell's own mark so it reads as the cell being
            // lit rather than as a seventh kind of icon.
            if (index == strip.focusIndex) {
                paint.style = Paint.Style.FILL
                paint.color = FOCUS_FILL
                val pad = rect.height() * 0.06f
                canvas.drawRoundRect(
                    RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad),
                    rect.height() * 0.3f, rect.height() * 0.3f, paint
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = rect.height() * 0.05f
                paint.color = Color.WHITE
                canvas.drawRoundRect(
                    RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad),
                    rect.height() * 0.3f, rect.height() * 0.3f, paint
                )
                paint.style = Paint.Style.FILL
            }
            val cx = cell.centreX * w
            val cy = cell.centreY * w
            val size = cell.height * w

            when (cell.kind) {
                ToolStrip.Kind.COLOUR -> {
                    paint.style = Paint.Style.FILL
                    paint.color = strip.colours[cell.index]
                    canvas.drawCircle(cx, cy, size * 0.24f, paint)
                    if (!strip.erasing && cell.index == strip.colourIndex) {
                        // A ring, not a tick. At three metres a mark drawn on top of a swatch is
                        // the first thing to disappear.
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = size * 0.05f
                        paint.color = Color.WHITE
                        canvas.drawCircle(cx, cy, size * 0.33f, paint)
                        paint.style = Paint.Style.FILL
                    }
                }
                ToolStrip.Kind.ERASER -> drawEraserGlyph(canvas, cx, cy, size * 0.19f, strip.erasing)
                ToolStrip.Kind.SIZE -> {
                    // The dot is the width it selects, so the control shows what it does.
                    val dot = size * (0.075f + 0.045f * cell.index)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    canvas.drawCircle(cx, cy, dot, paint)
                    if (cell.index == strip.sizeIndex) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = size * 0.045f
                        paint.color = ACCENT
                        canvas.drawCircle(cx, cy, dot + size * 0.09f, paint)
                        paint.style = Paint.Style.FILL
                    }
                }
                ToolStrip.Kind.BRUSH -> drawBrushGlyph(canvas, cx, cy, size * 0.19f, strip.brush)
                ToolStrip.Kind.UNDO ->
                    drawActionCell(canvas, rect, "Undo") { x, y, r -> drawUndoGlyph(canvas, x, y, r) }
                ToolStrip.Kind.CLEAR ->
                    drawActionCell(canvas, rect, "Clear") { x, y, r -> drawClearGlyph(canvas, x, y, r) }
                ToolStrip.Kind.SAVE ->
                    drawActionCell(canvas, rect, "Save", filled = true) { x, y, r -> drawSaveGlyph(canvas, x, y, r) }
            }
        }
    }

    /**
     * An action: a rounded button with a glyph and a word.
     *
     * Labelled, unlike the pen controls. A colour swatch explains itself and an icon for "clear"
     * does not - and clearing is the one mistake on this toolbar that costs anything, even with
     * undo behind it.
     */
    private fun drawActionCell(
        canvas: Canvas,
        rect: RectF,
        label: String,
        filled: Boolean = false,
        glyph: (Float, Float, Float) -> Unit
    ) {
        val radius = rect.height() * 0.30f
        paint.style = Paint.Style.FILL
        paint.color = if (filled) ACCENT else PANEL
        canvas.drawRoundRect(rect, radius, radius, paint)

        val glyphY = rect.centerY()
        glyph(rect.left + rect.width() * 0.26f, glyphY, rect.height() * 0.14f)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = rect.height() * 0.21f
        canvas.drawText(label, rect.left + rect.width() * 0.46f,
                        glyphY + paint.textSize * 0.35f, paint)
    }

    /** The eraser: a block, filled while it is the selected tool. */
    private fun drawEraserGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float, selected: Boolean) {
        if (selected) {
            paint.style = Paint.Style.FILL
            paint.color = ACCENT
            canvas.drawCircle(cx, cy, radius * 1.85f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(cx - radius, cy - radius * 0.72f, cx + radius, cy + radius * 0.72f),
            radius * 0.3f, radius * 0.3f, paint
        )
        paint.color = if (selected) ACCENT else PANEL
        canvas.drawRect(RectF(cx - radius, cy + radius * 0.12f, cx + radius, cy + radius * 0.72f), paint)
        paint.color = Color.WHITE
    }

    /** A stroke that is even, tapered, or ragged - one silhouette per brush. */
    private fun drawBrushGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float, brush: BrushType) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        when (brush) {
            BrushType.PEN -> {
                paint.strokeWidth = radius * 0.3f
                canvas.drawLine(cx - radius, cy + radius * 0.45f, cx + radius, cy - radius * 0.45f, paint)
            }
            BrushType.MARKER -> {
                paint.strokeWidth = radius * 0.6f
                canvas.drawLine(cx - radius, cy + radius * 0.45f, cx + radius, cy - radius * 0.45f, paint)
            }
            BrushType.CRAYON -> {
                paint.strokeWidth = radius * 0.34f
                var x = cx - radius
                var up = true
                while (x < cx + radius) {
                    val next = minOf(x + radius * 0.5f, cx + radius)
                    canvas.drawLine(x, cy + if (up) radius * 0.5f else -radius * 0.2f,
                                    next, cy + if (up) -radius * 0.2f else radius * 0.5f, paint)
                    x = next
                    up = !up
                }
            }
        }
        paint.style = Paint.Style.FILL
    }

    /** A bin: clear the drawing. Undo still gets it back, which is why this can exist at all. */
    private fun drawClearGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(cx - radius * 0.75f, cy - radius * 0.55f, cx + radius * 0.75f, cy + radius),
            radius * 0.22f, radius * 0.22f, paint
        )
        canvas.drawRect(RectF(cx - radius, cy - radius * 0.85f, cx + radius, cy - radius * 0.55f), paint)
    }

    /** An arrow into a tray: keep a copy of this. */
    private fun drawSaveGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.28f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        canvas.drawLine(cx, cy - radius, cx, cy + radius * 0.25f, paint)
        canvas.drawLine(cx - radius * 0.55f, cy - radius * 0.25f, cx, cy + radius * 0.25f, paint)
        canvas.drawLine(cx + radius * 0.55f, cy - radius * 0.25f, cx, cy + radius * 0.25f, paint)
        canvas.drawLine(cx - radius * 0.8f, cy + radius * 0.85f, cx + radius * 0.8f, cy + radius * 0.85f, paint)
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
    /**
     * The pinching hand gets a pen; any other hand gets a quieter ring.
     *
     * Sized for a television rather than for a monitor. The first version drew the idle hand as a
     * small dot at twenty percent opacity on near-white paper, which from three metres away is
     * indistinguishable from nothing - reported, accurately, as losing the pointer.
     *
     * Every cursor carries a white halo under it. The pen takes the current colour, and the
     * current colour can be the near-black one, or the amber one over a yellow stroke: without
     * something behind it, the cursor disappears exactly when the canvas gets busy.
     */
    private fun drawCursors(canvas: Canvas) {
        val radius = min(width, height) * 0.026f
        for (cursor in cursors) {
            // Both axes are normalised on width, which is why y is scaled by it too.
            val x = cursor.x * width
            val y = cursor.y * width

            if (!cursor.pinching) {
                halo(canvas, x, y, radius * 0.62f, radius * 0.2f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.2f
                paint.color = IDLE_HAND
                canvas.drawCircle(x, y, radius * 0.62f, paint)
                continue
            }

            halo(canvas, x, y, radius, radius * 0.26f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.26f
            paint.color = strip.colour
            canvas.drawCircle(x, y, radius, paint)

            if (cursor.waiting) {
                // The pen is down and the hand is gone. Held where it last was, with an outer
                // ring to say the app is waiting rather than drawing - a cursor that vanished
                // here read as the app quitting.
                paint.strokeWidth = radius * 0.12f
                canvas.drawCircle(x, y, radius * 1.6f, paint)
                continue
            }

            // Filled while the pen is down, so "am I drawing?" is answerable from the sofa.
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius * 0.42f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** A white ring under a cursor, so it survives whatever it is drawn over. */
    private fun halo(canvas: Canvas, x: Float, y: Float, radius: Float, weight: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = weight * 2.1f
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, radius, paint)
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
        const val PAPER = 0xFFFFFFFF.toInt()

        /** The chrome around the sheet: dark, so the drawing is the brightest thing on screen. */
        const val CHROME = 0xFF0B2545.toInt()
        const val PANEL = 0xFF16325C.toInt()
        const val ACCENT = 0xFF2E9BF0.toInt()
        const val HEADER_MUTED = 0xFF9DB0D8.toInt()
        const val FOCUS_FILL = 0x552E9BF0

        /** How much of the screen's height the wordmark band takes. */
        const val HEADER_SHARE = 0.11f

        /** The sheet's inset from the screen edges, as a fraction of the width. */
        const val CANVAS_INSET = 0.022f
        const val SHEET_RADIUS = 0.016f
        const val INK = 0xFF111827.toInt()
        const val MUTED = 0xFF6B7280.toInt()
        const val SCRIM = 0xE6111827.toInt()
        const val IDLE_HAND = 0xAA4B5563.toInt()
    }
}
