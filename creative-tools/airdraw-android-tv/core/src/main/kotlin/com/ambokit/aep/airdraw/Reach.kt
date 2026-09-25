package com.ambokit.aep.airdraw

/**
 * How far this particular person can reach, learned rather than assumed.
 *
 * The canvas has to map onto somebody's arms, and arms differ. Fixed bounds were tried first and
 * a logged session showed why they cannot work: the player's hand never went below 0.60 in the
 * camera frame, while the canvas bottom was pinned at 0.78, so the lowest quarter of the drawing
 * surface could not be reached at all. A child's range would be smaller again, and a camera
 * propped at a different height moves the whole window.
 *
 * It is not only a comfort problem. Reaching for an edge that is past the end of someone's arm
 * takes their hand out of the camera frame, tracking is lost, and the stroke they were drawing
 * breaks - so bad bounds show up as jitter, which is not where anyone would look for them.
 *
 * So the envelope starts at the first hand seen and grows towards wherever hands actually go,
 * a fraction of the way each time, which lets a real reach pull it out over a few passes while a
 * single bad detection barely moves it.
 *
 * Growth happens only while the pen is up. A mapping that shifted mid-stroke would slide the line
 * sideways under the hand drawing it.
 */
class ReachEnvelope(
    /** How much of the overshoot to take each time a hand goes past the current edge. */
    private val adaptRate: Float = 0.15f,
    /** Below this the envelope is treated as not yet known, and the fallback is used. */
    private val minSpan: Float = 0.12f,
    /**
     * Breathing room, taken off the inside of the envelope rather than added to the outside.
     *
     * Widening it would put the canvas corners slightly beyond the furthest the hand has ever
     * gone, which is the strain this class exists to remove. Narrowing means the corner is
     * reached a little before the end of the reach, and anything past that clamps.
     */
    private val margin: Float = 0.02f,
    private val fallbackMinX: Float = 0.15f,
    private val fallbackMaxX: Float = 0.85f,
    private val fallbackMinY: Float = 0.10f,
    private val fallbackMaxY: Float = 0.65f,
    /**
     * How close to the edge of the camera frame the envelope is allowed to grow.
     *
     * A session left it at x=0.31..0.96, which put the edge of the canvas within four percent of
     * the edge of what the camera can see - so touching that edge meant holding a hand half out
     * of frame, where the tracker loses it and the stroke dies. The envelope was faithfully
     * learning a reach that does not work.
     *
     * Reaching further still works; it simply stops moving the canvas edge out to meet it.
     */
    private val safeInset: Float = 0.08f
) {
    private var seen = false
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f

    /**
     * Whether enough ground has been covered for the envelope to mean anything - per axis.
     *
     * Asked separately because the axes fill up at different rates: someone drawing a horizon
     * sweeps across without going up or down, and their x is known long before their y is. One
     * combined answer would hold back the axis that is ready.
     */
    val isLearnedX: Boolean get() = seen && (maxX - minX) >= minSpan
    val isLearnedY: Boolean get() = seen && (maxY - minY) >= minSpan
    val isLearned: Boolean get() = isLearnedX && isLearnedY

    val spanX: Float get() = maxX - minX
    val spanY: Float get() = maxY - minY

    fun observe(rawX: Float, rawY: Float) {
        if (!rawX.isFinite() || !rawY.isFinite()) return
        if (!seen) {
            seen = true
            minX = rawX; maxX = rawX
            minY = rawY; maxY = rawY
            return
        }
        if (rawX < minX) minX += (rawX - minX) * adaptRate
        if (rawX > maxX) maxX += (rawX - maxX) * adaptRate
        if (rawY < minY) minY += (rawY - minY) * adaptRate
        if (rawY > maxY) maxY += (rawY - maxY) * adaptRate

        // Never out to where the tracker cannot see.
        minX = minX.coerceAtLeast(safeInset)
        maxX = maxX.coerceAtMost(1f - safeInset)
        minY = minY.coerceAtLeast(safeInset)
        maxY = maxY.coerceAtMost(1f - safeInset)
    }

    /** @param raw a camera-frame coordinate, 0..1. @return 0..1 across the canvas. */
    fun mapX(raw: Float): Float =
        if (isLearnedX) scale(raw, minX + margin, maxX - margin)
        else scale(raw, fallbackMinX, fallbackMaxX)

    fun mapY(raw: Float): Float =
        if (isLearnedY) scale(raw, minY + margin, maxY - margin)
        else scale(raw, fallbackMinY, fallbackMaxY)

    fun reset() {
        seen = false
        minX = 0f; maxX = 0f; minY = 0f; maxY = 0f
    }

    /**
     * Start from a measured rectangle instead of from the first hand that happens to appear.
     *
     * Everything above describes an envelope that learns while somebody draws, and it works - but
     * it converges *during* the drawing, so the opening strokes of a session are mapped through
     * whatever it knew at the time. [ReachCalibrator] measures the person before they start, and
     * this is where that measurement lands.
     *
     * Growth still applies afterwards. A seed is a better starting guess, not a final answer: an
     * arm that turns out to reach further still pulls the edge out, exactly as before.
     */
    fun seed(minX: Float, maxX: Float, minY: Float, maxY: Float) {
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return
        if (maxX <= minX || maxY <= minY) return
        seen = true
        this.minX = minX.coerceAtLeast(safeInset)
        this.maxX = maxX.coerceAtMost(1f - safeInset)
        this.minY = minY.coerceAtLeast(safeInset)
        this.maxY = maxY.coerceAtMost(1f - safeInset)
    }

    private fun scale(value: Float, min: Float, max: Float): Float {
        val span = max - min
        if (span <= 1e-4f) return 0.5f
        return ((value - min) / span).coerceIn(0f, 1f)
    }

    /** For the diagnostics, so the learned envelope can be read off a running device. */
    override fun toString(): String =
        if (!seen) "unlearned"
        else "x=%.2f..%.2f y=%.2f..%.2f%s".format(minX, maxX, minY, maxY, if (isLearned) "" else " (small)")
}
