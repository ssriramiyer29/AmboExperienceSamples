package com.ambokit.aep.airdraw

/**
 * Where a point came from, because it decides how the line gets its width.
 *
 * Touch carries real pressure from the phone's digitiser. Air carries none, so width is inferred
 * from speed. Keeping the source on the point means a drawing made with both — a big shape thrown
 * with the arm, detail added on the phone — still renders each stroke the way it was made.
 */
enum class StrokeSource { AIR, TOUCH }

/**
 * How a stroke's width responds to how it was drawn.
 *
 * Three, not a dozen. Each one has to be distinguishable from the others across a room, and
 * anything subtler than that is a setting nobody can see the effect of - which on a palette that
 * costs permanent screen width is worse than not offering it.
 */
enum class BrushType {
    /** Constant width. The one to reach for when the line matters more than the gesture. */
    PEN,

    /** Width falls off with speed. The original AirDraw stroke, and still the default. */
    MARKER,

    /** Marker, exaggerated: fast strokes go much thinner, slow ones much fatter. */
    CRAYON
}

/**
 * One sample of the drawing instrument, in document space.
 *
 * Document space is 0..1 horizontally and 0..aspect vertically, independent of the TV's
 * resolution and of the current zoom. Strokes are stored here and transformed on the way out,
 * which is what lets the canvas be zoomed without the drawing turning to mush.
 */
data class AirDrawPoint(
    val x: Float,
    val y: Float,
    /** 0..1. Real for touch; 1 for air, where speed decides width instead. */
    val pressure: Float = 1f,
    /** Host clock, milliseconds. Speed needs real time, not sample counts. */
    val timeMs: Long = 0L
)

/**
 * A finished mark: the points as drawn, plus how to draw them.
 *
 * Strokes are immutable and kept as data rather than painted into a bitmap. That is what gives
 * undo, save, and sharp zoom at any scale — a bitmap gives none of the three, and a children's
 * drawing app that cannot undo is a children's drawing app nobody finishes a picture in.
 */
data class Stroke(
    val argb: Int,
    val baseWidth: Float,
    val source: StrokeSource,
    val points: List<AirDrawPoint>,
    /**
     * Defaulted, so every stroke written before brushes existed still means what it meant - both
     * in the tests above this line and in any drawing already saved to disk.
     */
    val brush: BrushType = BrushType.MARKER,
    /**
     * Whether this stroke was laid down by the eraser.
     *
     * The eraser paints in the paper colour rather than removing anything, so on the canvas this
     * changes nothing - the mark is an ordinary stroke and undo, zoom and save all treat it as
     * one. It is recorded because the pixels alone cannot say why they are cream: a later change
     * to split-on-erase, or a canvas that is not cream, needs to know which marks were meant as
     * erasures and which were drawn in that colour on purpose.
     */
    val erases: Boolean = false
)

/**
 * A stroke ready to draw: screen-space coordinates and a width for each one.
 *
 * The renderer does no arithmetic beyond putting these on a canvas. Everything that decides how
 * the line looks — smoothing, curve, width — happened in this module, where it can be tested
 * without a device and where both renderers would get identical results.
 */
class RenderedStroke(
    val argb: Int,
    /** x0, y0, x1, y1, … in screen pixels. */
    val points: FloatArray,
    /** One width per point, in screen pixels. */
    val widths: FloatArray
) {
    val size: Int get() = widths.size
}
