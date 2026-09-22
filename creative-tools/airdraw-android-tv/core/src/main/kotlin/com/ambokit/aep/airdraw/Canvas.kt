package com.ambokit.aep.airdraw

import kotlin.math.max
import kotlin.math.min

/**
 * What is on the canvas, and what was on it before.
 *
 * Strokes are immutable, so undo is dropping the last one and redo is putting it back. That falls
 * out of storing strokes as data instead of painting them into a bitmap, and it is the reason
 * that decision was made.
 */
class Drawing {
    private val strokes = ArrayList<Stroke>()
    private val undone = ArrayList<Stroke>()

    val all: List<Stroke> get() = strokes
    val isEmpty: Boolean get() = strokes.isEmpty()
    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun add(stroke: Stroke) {
        strokes += stroke
        // A new mark ends the redo history, as it does in every editor anyone has used.
        undone.clear()
    }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        undone += strokes.removeAt(strokes.size - 1)
        return true
    }

    fun redo(): Boolean {
        if (undone.isEmpty()) return false
        strokes += undone.removeAt(undone.size - 1)
        return true
    }

    /** Clear is undoable in one step: a child who waves at the wrong moment has not lost the picture. */
    fun clear(): Boolean {
        if (strokes.isEmpty()) return false
        undone.clear()
        undone.addAll(strokes.asReversed())
        strokes.clear()
        return true
    }

    fun restoreAll() {
        while (redo()) { /* keep going */ }
    }
}

/**
 * Which part of the drawing is on screen.
 *
 * Document space is 0..1 across and 0..[aspect] down, so a drawing means the same thing on any
 * TV. The viewport scales and offsets that onto pixels. Zooming stored strokes is sharp at any
 * scale, which is the other half of why strokes are data.
 */
class Viewport(var aspect: Float = 9f / 16f) {
    var scale: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    /** Screen size in pixels, set by the renderer when the surface is sized. */
    var widthPx: Float = 1920f
    var heightPx: Float = 1080f

    fun reset() {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    /**
     * Zoom about a fixed point, so the thing under the player's hands stays under their hands.
     *
     * Zooming about the centre of the screen instead is the single most common way to make a
     * zoom feel wrong: the drawing slides away from wherever you were looking.
     */
    fun zoomAbout(focusDocX: Float, focusDocY: Float, factor: Float) {
        val next = max(MIN_SCALE, min(MAX_SCALE, scale * factor))
        if (next == scale) return
        // Keep the focus point stationary: solve for the offset that maps it to the same screen
        // position before and after.
        offsetX = focusDocX - (focusDocX - offsetX) * (scale / next)
        offsetY = focusDocY - (focusDocY - offsetY) * (scale / next)
        scale = next
        clamp()
    }

    fun panBy(docDx: Float, docDy: Float) {
        offsetX -= docDx
        offsetY -= docDy
        clamp()
    }

    /** Document space is finite, so panning stops at its edges rather than losing the drawing. */
    private fun clamp() {
        val visibleW = 1f / scale
        val visibleH = aspect / scale
        offsetX = max(0f, min(1f - visibleW, offsetX))
        offsetY = max(0f, min(aspect - visibleH, offsetY))
    }

    fun toScreenX(docX: Float): Float = (docX - offsetX) * scale * widthPx
    fun toScreenY(docY: Float): Float = (docY - offsetY) * scale * (widthPx)

    /** The inverse, for turning a cursor position into a place on the drawing. */
    fun toDocumentX(screenX: Float): Float = screenX / (scale * widthPx) + offsetX
    fun toDocumentY(screenY: Float): Float = screenY / (scale * widthPx) + offsetY

    /** Widths scale with the zoom, or a line drawn zoomed-in looks like a different pen. */
    fun toScreenWidth(docWidth: Float): Float = docWidth * scale * widthPx

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
    }
}
