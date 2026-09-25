package com.ambokit.aep.airdraw

import kotlin.math.hypot

/**
 * AirDraw's rules: what a hand or a finger does to the canvas.
 *
 * No renderer, no Android, no AEP session — ADR-0001 rule 2. The app module owns the screen and
 * the session; everything that decides what the drawing looks like is here, where it can be
 * exercised without a TV in the room.
 */
class AirDrawEngine(
    val drawing: Drawing = Drawing(),
    val viewport: Viewport = Viewport()
) {
    var colour: Int = DEFAULT_COLOURS[0]
    var baseWidth: Float = DEFAULT_WIDTHS[1]
    var brush: BrushType = BrushType.MARKER

    /**
     * The canvas colour, which is what the eraser paints in.
     *
     * Held here rather than read from the renderer because the stroke has to carry a colour at
     * the moment it is committed, and the drawing rules are the last place that knows what the
     * pen was doing. It is cream rather than white: painting white erasures onto cream paper
     * leaves visible smears, which is the whole reason this is a value and not `Color.WHITE`.
     */
    var paperColour: Int = DEFAULT_PAPER

    /**
     * Whether the eraser is selected.
     *
     * Erasing paints over in [paperColour] rather than deleting the stroke underneath. Deleting
     * was the better model on paper and loses to one fact about this app: a wrist-driven stroke is
     * long and sweeping, so removing the whole stroke a pointer touches would take an entire arm's
     * sweep away when somebody meant to clean up its corner.
     *
     * What it costs: erasures are strokes, so a drawing only grows, and the result is invisible
     * only while the background stays [paperColour]. What it keeps: partial erasure, undo that
     * restores the ink by removing the erasure, and no new rendering path. What makes it safe:
     * erasures are ordinary strokes in the store, so moving later to split-on-erase migrates
     * nothing - which is why [Stroke.erases] records the intent that the pixels cannot.
     */
    var erasing: Boolean = false

    /** Set from measured jitter once the app has seen enough frames to measure any. */
    var smoothing: StrokeSmoothing = StrokeSmoothing.NONE
        set(value) {
            field = value
            filterX = if (value.enabled) OneEuroFilter(value.minCutoff, value.beta) else null
            filterY = if (value.enabled) OneEuroFilter(value.minCutoff, value.beta) else null
        }

    private var filterX: OneEuroFilter? = null
    private var filterY: OneEuroFilter? = null

    private var active: MutableList<AirDrawPoint>? = null
    private var activeSource: StrokeSource = StrokeSource.AIR

    /**
     * Curve and widths for strokes already finished, computed once.
     *
     * A drawing accumulates strokes and every one of them would otherwise be re-curved sixty
     * times a second forever. The shape of a finished stroke never changes; only where it lands
     * on screen does, and that is a multiply per point. Keyed on the stroke object, so undo and
     * redo reuse what was already computed.
     */
    private val prepared = HashMap<Stroke, Prepared>()

    private class Prepared(val points: List<AirDrawPoint>, val widths: FloatArray)

    /** The stroke being drawn right now, if any — the renderer draws it over the finished ones. */
    val inProgress: List<AirDrawPoint> get() = active ?: emptyList()

    fun begin(source: StrokeSource, point: AirDrawPoint) {
        filterX?.reset(); filterY?.reset()
        activeSource = source
        active = arrayListOf(filtered(point))
    }

    fun extend(point: AirDrawPoint) {
        val current = active ?: return
        val next = filtered(point)
        // Drop samples that have not moved: a hand held still otherwise piles up coincident
        // points, which the curve turns into a visible blob.
        val last = current[current.size - 1]
        if (hypot(next.x - last.x, next.y - last.y) < MINIMUM_STEP) return
        current += next
    }

    /** @return true when a stroke was actually committed. */
    fun end(): Boolean {
        val current = active ?: return false
        active = null
        // One or two samples is a twitch, not a mark. Committing them leaves specks that a child
        // then has to find and undo.
        if (current.size < 3) return false
        drawing.add(Stroke(
            argb = if (erasing) paperColour else colour,
            baseWidth = baseWidth,
            source = activeSource,
            points = current.toList(),
            // An eraser is always a pen. Speed-varying width would thin the erasure wherever the
            // arm moved quickly, leaving streaks of the old drawing showing through the middle of
            // a sweep - which reads as the eraser not working rather than as expression.
            brush = if (erasing) BrushType.PEN else brush,
            erases = erasing
        ))
        return true
    }

    fun cancel() {
        active = null
    }

    fun undo(): Boolean = drawing.undo()
    fun redo(): Boolean = drawing.redo()
    fun clear(): Boolean {
        active = null
        return drawing.clear()
    }

    /**
     * Everything to draw, in screen pixels, curve and widths already resolved.
     *
     * Called once per display frame. The curve is evaluated here rather than when samples arrive,
     * which is what turns fifteen samples a second into a continuous line.
     */
    fun render(): List<RenderedStroke> {
        val strokes = drawing.all
        val out = ArrayList<RenderedStroke>(strokes.size + 1)
        for (stroke in strokes) {
            val ready = prepared.getOrPut(stroke) {
                val curved = StrokePipeline.curve(stroke.points)
                // The stroke's own brush, not the selected one: a drawing is a record of how each
                // mark was made, and re-rendering old marks with the current pen would rewrite it
                // every time somebody changed brushes.
                Prepared(curved, StrokePipeline.widths(curved, stroke.baseWidth, stroke.source, stroke.brush))
            }
            out += toScreen(ready.points, ready.widths, stroke.argb)
        }
        // The stroke being drawn is re-curved every frame, because it is still growing. It is one
        // stroke, and it is the only one whose shape can still change.
        active?.let {
            val curved = StrokePipeline.curve(it)
            val liveBrush = if (erasing) BrushType.PEN else brush
            out += toScreen(
                curved,
                StrokePipeline.widths(curved, baseWidth, activeSource, liveBrush),
                if (erasing) paperColour else colour
            )
        }
        if (prepared.size > strokes.size * 2 + 16) prepared.keys.retainAll(strokes.toSet())
        return out
    }

    private fun toScreen(points: List<AirDrawPoint>, widths: FloatArray, argb: Int): RenderedStroke {
        val screen = FloatArray(points.size * 2)
        for (i in points.indices) {
            screen[i * 2] = viewport.toScreenX(points[i].x)
            screen[i * 2 + 1] = viewport.toScreenY(points[i].y)
        }
        val screenWidths = FloatArray(widths.size)
        for (i in widths.indices) screenWidths[i] = viewport.toScreenWidth(widths[i])
        return RenderedStroke(argb, screen, screenWidths)
    }

    private fun filtered(point: AirDrawPoint): AirDrawPoint {
        val fx = filterX
        val fy = filterY
        if (fx == null || fy == null) return point
        return point.copy(
            x = fx.filter(point.x, point.timeMs),
            y = fy.filter(point.y, point.timeMs)
        )
    }

    companion object {
        /** Document-space distance below which a new sample is treated as the hand standing still. */
        const val MINIMUM_STEP = 0.0015f

        /**
         * A child-facing palette: saturated, distinguishable on a TV across the room, and
         * distinguishable from each other for the colour-blind — which rules out the obvious
         * red/green pairing that most default palettes reach for first.
         */
        val DEFAULT_COLOURS = intArrayOf(
            0xFF111827.toInt(),  // near-black
            0xFFEF4444.toInt(),  // red
            0xFF2563EB.toInt(),  // blue
            0xFFF59E0B.toInt(),  // amber
            0xFF10B981.toInt(),  // green
            0xFFA855F7.toInt()   // purple
        )

        /** In document space, so a stroke is the same weight on any screen. */
        val DEFAULT_WIDTHS = floatArrayOf(0.004f, 0.008f, 0.018f)

        /**
         * The canvas colour the eraser paints in.
         *
         * Cream, not white, and it has to stay in step with the renderer's own paper colour - a
         * mismatch would make every erasure a faintly visible rectangle of the wrong shade.
         * `AirDrawViewTest` is not a thing that exists, so the renderer reads this rather than
         * holding its own copy.
         */
        const val DEFAULT_PAPER = 0xFFF8F4EC.toInt()
    }
}
