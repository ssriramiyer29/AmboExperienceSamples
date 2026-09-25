package com.ambokit.aep.airdraw

/**
 * Turns a camera-frame position into a place on the canvas.
 *
 * This arithmetic used to live inside [HandInterpreter], where it was inseparable from reading
 * hands. The pointer now comes from the body as often as from the hand, and both need the same
 * treatment - mirror, then map through the reach envelope - so it lives on its own rather than
 * being copied into a second place and drifting.
 *
 * Two steps, and the order matters. **Mirroring** first, because a camera facing a person sees
 * their right hand on its left, and a canvas where moving right moves the cursor left is one
 * nobody can draw on. **Then the envelope**, which is what makes the canvas fit this particular
 * person's arms rather than assuming a reach - the failure that left a logged player unable to
 * touch the bottom quarter of the drawing.
 */
class PointerMapper(
    private val mirrorX: Boolean = true,
    val reach: ReachEnvelope = ReachEnvelope()
) {
    /** The last raw position seen, before mirroring or mapping, for the diagnostics. */
    var lastRawX: Float? = null
        private set
    var lastRawY: Float? = null
        private set

    fun reset() {
        reach.reset()
        lastRawX = null
        lastRawY = null
    }

    /**
     * @param learning whether the envelope may grow from this sample. False while a stroke is in
     *   progress: a mapping that shifted mid-stroke would slide the line sideways under the hand
     *   drawing it.
     * @return x in 0..1 across the canvas and y in 0..aspect down it, matching document space.
     */
    fun map(rawX: Float, rawY: Float, aspect: Float, learning: Boolean): Pair<Float, Float>? {
        if (!rawX.isFinite() || !rawY.isFinite()) return null
        lastRawX = rawX
        lastRawY = rawY
        val mirrored = if (mirrorX) 1f - rawX else rawX
        if (learning) reach.observe(mirrored, rawY)
        // The provider normalises y over the camera frame; the canvas is 0..aspect.
        return reach.mapX(mirrored) to reach.mapY(rawY) * aspect
    }

    fun map(pointer: RawPointer?, aspect: Float, learning: Boolean): Pair<Float, Float>? {
        val p = pointer ?: return null
        return map(p.x, p.y, aspect, learning)
    }

    /**
     * Seed the envelope from a completed reach calibration.
     *
     * The calibrator measures in camera-frame coordinates and the envelope learns in mirrored
     * ones, so the rectangle is flipped on the way in. Getting this wrong is invisible on a
     * symmetric measurement and obvious on an asymmetric one - somebody standing off to one side
     * would find the canvas mapped to the opposite half of their reach.
     */
    fun seedFrom(profile: ReachProfile?) {
        val p = profile ?: return
        if (mirrorX) reach.seed(1f - p.maxX, 1f - p.minX, p.minY, p.maxY)
        else reach.seed(p.minX, p.maxX, p.minY, p.maxY)
    }
}
