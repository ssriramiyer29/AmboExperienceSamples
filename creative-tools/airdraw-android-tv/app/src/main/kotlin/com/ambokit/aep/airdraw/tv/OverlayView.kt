package com.ambokit.aep.airdraw.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.CalibrationPhase
import com.ambokit.aep.airdraw.DrawingSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen a person meets before they draw: what the controls are, and what to open.
 *
 * **Why Android views.** This is the screen driven by the remote, and remote navigation means
 * focus - which control is selected, where a D-pad press goes, what selected looks like. Android
 * TV gives all of that to ordinary focusable views; hand-drawing it into a Canvas would mean
 * writing focus traversal from scratch.
 *
 * **Why the remote at all.** Nothing gesture-driven can work here: hand and pose input are not
 * trustworthy until calibration finishes, and calibration is what the button waits for.
 *
 * **No XML, no resources.** Built in code, so there are no layout files, no `R` identifiers and no
 * drawable assets to fall out of step - and the pictograms are drawn from the same primitives as
 * the rest of the app rather than shipped as an image per screen density.
 */
class OverlayView(context: Context) : FrameLayout(context) {

    /** Called with the drawing to open, or null for a blank canvas. */
    var onStart: ((String?) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    private val statusText: TextView
    private val startButton: TextView
    private val galleryRow: LinearLayout
    private val progressBar: ProgressStrip
    private val progressLabel: TextView

    private var selectedId: String? = null
    private val tiles = mutableListOf<View>()
    private var calibrated = false

    init {
        setBackgroundColor(SCRIM)
        isFocusable = true
        isClickable = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56f), dp(24f), dp(56f), dp(24f))
        }
        // Scrollable, so the content can never be clipped by a screen shorter than it.
        //
        // The first version stacked everything in a plain column inside this frame. On a
        // 1080-line television the content came to more than the screen's height, a LinearLayout
        // in a FrameLayout clips rather than scrolls, and the Start Drawing button people were
        // waiting for simply was not on screen. Densities differ between televisions, so a layout
        // that merely fits the one on the desk is not a fix - this one cannot clip at any height,
        // and D-pad focus scrolls it into view on its own.
        val scroller = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            setFillViewport(true)
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        column.addView(label("AirDraw", 30f, INK, bold = true))
        column.addView(label("Your hand is the pen", 16f, MUTED).apply {
            setPadding(0, dp(2f), 0, dp(16f))
        })

        column.addView(controlsRow(context))

        // Calibration: a labelled bar that disappears once it is done, so a full blue bar can
        // never sit on screen looking like something to press.
        val progressBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(16f), 0, 0)
        }
        progressLabel = label("Raise the hand you want to draw with", 14f, MUTED)
        progressBlock.addView(progressLabel)
        progressBar = ProgressStrip(context)
        progressBlock.addView(progressBar, LinearLayout.LayoutParams(dp(260f), dp(5f)).apply {
            topMargin = dp(8f)
        })
        column.addView(progressBlock)

        statusText = label("Stand where the camera can see you", 15f, MUTED).apply {
            setPadding(0, dp(12f), 0, dp(4f))
        }
        column.addView(statusText)

        column.addView(label("Open a drawing", 14f, MUTED, bold = true).apply {
            gravity = Gravity.START
            setPadding(dp(2f), dp(14f), 0, dp(6f))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        galleryRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(galleryRow)
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(124f))
        )

        // Anchored to the bottom of the frame rather than added to the end of the column.
        //
        // Stacked in the column it was the last thing laid out, and on a 1080-line television the
        // column's content came to more than the screen's height - so a LinearLayout inside a
        // FrameLayout simply clipped it, and the button people were waiting for was off the
        // bottom edge. Anchoring it means it is visible whatever the content above does, and the
        // column reserves room for it instead of running underneath it.
        startButton = button("Start Drawing") { if (calibrated) onStart?.invoke(selectedId) }
        addView(startButton, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20f)
        })
        column.setPadding(dp(56f), dp(20f), dp(56f), dp(20f) + BUTTON_RESERVE_DP)

        showGallery(emptyList(), null)
        setCalibration(0f, CalibrationPhase.RAISE_A_HAND, seenPerson = false)
    }

    private fun label(text: String, sizeSp: Float, colour: Int, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(colour)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }

    private fun pill(colour: Int, radiusDp: Float = 26f, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(colour)
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(2f), it) }
        }

    /**
     * A button, built from a TextView rather than [android.widget.Button].
     *
     * A platform Button carries a themed background and text appearance that differ between TV
     * launchers, and replacing its background later re-applies the new drawable's padding - which
     * silently collapsed the padding set at construction and left a coloured shape with its label
     * squeezed out of view. Building it here means text, colour, size and padding are all stated
     * once and restated after every background change, and nothing is inherited from a theme.
     */
    private fun button(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTypeface(null, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        minimumWidth = dp(240f)
        minimumHeight = dp(52f)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, _ -> paintStart() }
    }

    /**
     * Padding is always set *after* the background, never before.
     *
     * `setBackground` applies the drawable's own padding over whatever the view had, so a button
     * restyled on a state change loses its padding unless it is restored here every time.
     */
    private fun style(view: TextView, background: GradientDrawable, colour: Int,
                      h: Float, v: Float) {
        view.background = background
        view.setPadding(dp(h), dp(v), dp(h), dp(v))
        view.setTextColor(colour)
    }

    private fun paintStart() {
        val focused = startButton.hasFocus()
        val fill = when {
            !calibrated -> DISABLED
            focused -> ACCENT_BRIGHT
            else -> ACCENT
        }
        style(startButton, pill(fill, stroke = if (focused) Color.WHITE else null),
              if (calibrated) Color.WHITE else 0xFF7C8394.toInt(), 32f, 14f)
    }

    private fun controlsRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((glyph, caption) in CONTROLS) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10f), 0, dp(10f), 0)
            }
            cell.addView(GlyphView(context, glyph), LinearLayout.LayoutParams(dp(62f), dp(52f)))
            cell.addView(TextView(context).apply {
                text = caption
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(INK)
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, 0)
            }, LinearLayout.LayoutParams(dp(96f), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(cell)
        }
        return row
    }

    /**
     * Calibration progress, and whether the button is live yet.
     *
     * @param progress 0..1. Reaching 1 enables Start Drawing, which is why this screen waits
     *   rather than letting somebody through to a canvas that would not fit their arms.
     */
    fun setCalibration(progress: Float, phase: CalibrationPhase, seenPerson: Boolean) {
        val complete = phase == CalibrationPhase.DONE
        calibrated = complete
        progressBar.progress = progress.coerceIn(0f, 1f)
        progressBar.invalidate()

        // Hidden once done, rather than left sitting there full: a solid blue bar with no label
        // on it is a thing people try to press.
        val showProgress = !complete && seenPerson
        progressBar.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE
        progressLabel.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE

        // One instruction at a time. Asking for a raised hand and a sweep at once got neither.
        progressLabel.text = when (phase) {
            CalibrationPhase.RAISE_A_HAND -> "Raise the hand you want to draw with"
            CalibrationPhase.REACH_AROUND -> "Now reach around the space you want to draw in"
            CalibrationPhase.DONE -> ""
        }
        statusText.text = when {
            complete -> "Ready — press Start Drawing"
            !seenPerson -> "Stand where the camera can see you"
            phase == CalibrationPhase.RAISE_A_HAND -> "Hold it up above your shoulder"
            else -> "Move it out wide, then up and down"
        }
        statusText.setTextColor(if (complete) INK else MUTED)
        paintStart()
        if (complete && findFocus() == null) startButton.requestFocus()
    }

    /** Rebuild the gallery. [files] supplies the thumbnails; a missing one shows blank paper. */
    fun showGallery(drawings: List<DrawingSummary>, files: DrawingFiles?) {
        galleryRow.removeAllViews()
        tiles.clear()
        galleryRow.addView(tileFor(null, "New Drawing", null))
        for (drawing in drawings) {
            galleryRow.addView(
                tileFor(drawing.id, label(drawing), files?.thumbnailFile(drawing.id)?.absolutePath)
            )
        }
        select(selectedId.takeIf { id -> drawings.any { it.id == id } })
    }

    private fun label(drawing: DrawingSummary): String =
        drawing.name ?: DATE_FORMAT.format(Date(drawing.modifiedMs))

    private fun tileFor(id: String?, caption: String, thumbnailPath: String?): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(AirDrawEngine.DEFAULT_PAPER)
                cornerRadius = dp(8f).toFloat()
                setStroke(dp(2f), OUTLINE)
            }
            clipToOutline = true
        }
        if (thumbnailPath != null) {
            runCatching { BitmapFactory.decodeFile(thumbnailPath) }.getOrNull()
                ?.let { image.setImageBitmap(it) }
        } else if (id == null) {
            image.setImageDrawable(PlusDrawable(dp(30f).toFloat()))
        }
        tile.addView(image, LinearLayout.LayoutParams(dp(152f), dp(86f)))
        tile.addView(TextView(context).apply {
            text = caption
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6f), 0, 0)
        }, LinearLayout.LayoutParams(dp(152f), LinearLayout.LayoutParams.WRAP_CONTENT))

        tile.setOnClickListener { select(id) }
        // Selection follows focus, so a D-pad user never presses twice: move to a tile and it is
        // the one that opens.
        tile.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) select(id) }
        tile.tag = id
        tiles.add(tile)
        return tile
    }

    private fun select(id: String?) {
        selectedId = id
        for (tile in tiles) {
            val chosen = tile.tag == id
            val focused = tile.hasFocus()
            tile.background = pill(
                if (chosen) SELECTED else Color.TRANSPARENT,
                radiusDp = 12f,
                stroke = if (focused) ACCENT else null
            )
            tile.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
    }

    private enum class Glyph { PINCH, PALM, FIST, PALETTE }

    /**
     * A control pictogram, drawn rather than shipped.
     *
     * Simplified deliberately: a recognisable hand at 68dp across a living room is a blob, so
     * these are diagrams of what to do rather than pictures of a hand.
     */
    private class GlyphView(context: Context, private val glyph: Glyph) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val unit = minOf(w, h) / 6f
            paint.color = INK
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            when (glyph) {
                Glyph.PINCH -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = unit * 0.45f
                    canvas.drawArc(RectF(cx - unit * 2f, cy - unit * 2f, cx, cy), 300f, 120f, false, paint)
                    canvas.drawArc(RectF(cx, cy - unit * 2f, cx + unit * 2f, cy), 120f, 120f, false, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = ACCENT
                    canvas.drawCircle(cx, cy - unit * 0.4f, unit * 0.5f, paint)
                }
                Glyph.PALM -> {
                    paint.style = Paint.Style.FILL
                    for (i in -1..2) {
                        val x = cx + i * unit * 0.85f - unit * 0.42f
                        canvas.drawRoundRect(
                            RectF(x - unit * 0.28f, cy - unit * 2f, x + unit * 0.28f, cy + unit * 0.4f),
                            unit * 0.28f, unit * 0.28f, paint
                        )
                    }
                    canvas.drawRoundRect(
                        RectF(cx - unit * 1.7f, cy, cx + unit * 1.7f, cy + unit * 2f),
                        unit * 0.6f, unit * 0.6f, paint
                    )
                }
                Glyph.FIST -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(
                        RectF(cx - unit * 1.6f, cy - unit * 1.4f, cx + unit * 1.6f, cy + unit * 1.6f),
                        unit * 0.9f, unit * 0.9f, paint
                    )
                    paint.color = AirDrawEngine.DEFAULT_PAPER
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = unit * 0.24f
                    for (i in 0..2) {
                        val y = cy - unit * 0.7f + i * unit * 0.75f
                        canvas.drawLine(cx - unit * 0.9f, y, cx + unit * 0.9f, y, paint)
                    }
                }
                Glyph.PALETTE -> {
                    paint.style = Paint.Style.FILL
                    val colours = AirDrawEngine.DEFAULT_COLOURS
                    val strip = RectF(cx - unit * 1f, cy - unit * 2.2f, cx + unit * 1f, cy + unit * 2.2f)
                    paint.color = OUTLINE
                    canvas.drawRoundRect(strip, unit * 0.5f, unit * 0.5f, paint)
                    for (i in 0 until minOf(4, colours.size)) {
                        paint.color = colours[i]
                        canvas.drawCircle(cx, strip.top + unit * 0.75f + i * unit * 1f, unit * 0.34f, paint)
                    }
                }
            }
        }
    }

    /** The thin bar that fills while calibration gathers evidence. */
    private class ProgressStrip(context: Context) : View(context) {
        var progress: Float = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val r = h / 2f
            paint.color = OUTLINE
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, paint)
            if (progress <= 0f) return
            paint.color = ACCENT
            canvas.drawRoundRect(RectF(0f, 0f, width * progress, h), r, r, paint)
        }
    }

    /** A plus sign for the New Drawing tile, so no drawable resource is needed. */
    private class PlusDrawable(private val arm: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            paint.strokeWidth = arm * 0.22f
            canvas.drawLine(cx - arm / 2f, cy, cx + arm / 2f, cy, paint)
            canvas.drawLine(cx, cy - arm / 2f, cx, cy + arm / 2f, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Drawable, still abstract on older API levels.")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        val CONTROLS = listOf(
            Glyph.PINCH to "Pinch to start\nand stop drawing",
            Glyph.PALM to "Open hand\ndraws",
            Glyph.FIST to "Fist moves\nthe canvas",
            Glyph.PALETTE to "Pinch the strip\nto pick a tool"
        )

        val DATE_FORMAT = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        // The same chrome as the drawing screen, so arriving at the canvas is not a jolt.
        const val SCRIM = 0xFF0B2545.toInt()
        const val INK = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFF9DB0D8.toInt()
        const val OUTLINE = 0xFF1E3A63.toInt()
        const val ACCENT = 0xFF2E9BF0.toInt()
        const val ACCENT_BRIGHT = 0xFF54B4FF.toInt()
        const val DISABLED = 0xFF1E3A63.toInt()
        const val SELECTED = 0x332E9BF0

        /**
         * Height kept clear at the bottom of the column for the anchored button.
         *
         * In raw pixels rather than dp because it is added to a padding already in pixels; the
         * button's own height plus its margin, with room to spare.
         */
        const val BUTTON_RESERVE_DP = 200
    }
}
