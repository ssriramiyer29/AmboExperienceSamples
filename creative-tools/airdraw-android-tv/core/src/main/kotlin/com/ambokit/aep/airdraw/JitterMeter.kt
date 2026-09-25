package com.ambokit.aep.airdraw

import kotlin.math.hypot

/**
 * How much the pointer wanders when the person is holding still.
 *
 * [StrokeSmoothing.forJitter] picks a filter from a number in pixels, and that number has to come
 * from somewhere. Choosing it by argument is how the wrong one gets chosen: the pose wrist and the
 * hand's pinch midpoint are different signals with different noise, so a setting measured for one
 * is not evidence about the other.
 *
 * **Rest, not motion.** Frame-to-frame movement during a deliberate stroke is mostly the stroke,
 * and averaging it in would read a fast drawing as a noisy sensor. So this takes a low percentile
 * of the observed steps: when somebody draws for a few seconds, most frames still catch the hand
 * between intentions, and the quiet frames are the ones that describe the noise floor.
 *
 * Deliberately not a running average. The estimate is recomputed from a whole window at once, so
 * it either has enough evidence to speak or says nothing.
 */
class JitterMeter(
    /** Frames per estimate. About four seconds at 60fps. */
    private val window: Int = 240,
    /**
     * Which percentile of frame-to-frame steps counts as the noise floor.
     *
     * The twentieth: low enough that deliberate movement is excluded, high enough that a handful
     * of unusually still frames cannot drag the estimate to zero and switch filtering off.
     */
    private val percentile: Float = 0.20f,
    /** Pointer units to pixels. Document space is 0..1 across a 1920-wide screen. */
    private val widthPx: Float = 1920f
) {
    private val steps = ArrayList<Float>(window)
    private var lastX: Float? = null
    private var lastY: Float? = null

    /** The most recent estimate in pixels, or null before the first window completes. */
    var pixels: Float? = null
        private set

    fun reset() {
        steps.clear()
        lastX = null
        lastY = null
        pixels = null
    }

    /**
     * Feed one frame's pointer position.
     *
     * @return a new estimate on the frame a window completes, null otherwise - so the caller can
     *   apply it once rather than testing for change.
     */
    fun observe(x: Float, y: Float): Float? {
        if (!x.isFinite() || !y.isFinite()) return null
        val px = lastX
        val py = lastY
        lastX = x
        lastY = y
        if (px == null || py == null) return null

        steps += hypot(x - px, y - py) * widthPx
        if (steps.size < window) return null

        steps.sort()
        val index = ((steps.size - 1) * percentile).toInt().coerceIn(0, steps.size - 1)
        val estimate = steps[index]
        steps.clear()
        pixels = estimate
        return estimate
    }

    /**
     * A gap in the pointer, which must not be measured as movement.
     *
     * The distance between where a hand vanished and where it reappeared is not jitter - it is
     * two positions separated by an unknown amount of time, and counting it would read a dropout
     * as an extremely noisy sensor and turn on the heaviest filter available.
     */
    fun interrupted() {
        lastX = null
        lastY = null
    }
}
