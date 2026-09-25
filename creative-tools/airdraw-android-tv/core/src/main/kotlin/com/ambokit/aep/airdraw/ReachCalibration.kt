package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.AepPosePoint
import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What calibration is waiting for, so the overlay can ask for the right thing. */
enum class CalibrationPhase { RAISE_A_HAND, REACH_AROUND, DONE }

/**
 * The rectangle this person's drawing wrist can comfortably cover, in camera-frame coordinates.
 *
 * Everything is 0..1 in the camera's frame, the same space [ReachEnvelope] learns in, so a
 * profile can be handed straight to it.
 */
data class ReachProfile(
    val shoulderWidth: Float,
    val neutralCenterX: Float,
    val neutralCenterY: Float,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
) {
    val spanX: Float get() = maxX - minX
    val spanY: Float get() = maxY - minY
}

/**
 * Measures a person before they draw, so the canvas fits their arms from the first stroke.
 *
 * **Why not [com.ambokit.aep.core.PoseCalibrator].** That one exists and calibrates a neutral
 * standing pose for RockDodge, and reusing it was the obvious move. It cannot be reused: it
 * requires both ankles and returns null without them. RockDodge needs ankles because stepping is
 * its control; AirDraw is an arm activity whose player may be sitting on a sofa or standing close
 * enough to the camera that their feet are out of shot. The file's own comment already notes that
 * at TV distance "a player … may well have their ankles near the edge of frame" - so reusing it
 * would have refused to calibrate exactly the people most likely to use this.
 *
 * Hips and shoulders are enough here, and both stay visible when seated.
 *
 * **Why this exists at all.** [ReachEnvelope] already sizes the canvas to the person, but it
 * learns while they draw: it starts at the first hand it sees and grows a fraction of the
 * overshoot at a time, falling back to fixed bounds until each axis has seen enough spread. That
 * means the opening strokes of every session are drawn on a mis-scaled canvas, and a logged
 * session ended at x=0.31..0.96 - pressed against the safe inset, still converging. Measuring
 * once, up front, answers the question that class was written for. The envelope stays, seeded
 * rather than cold, and keeps refining from real movement.
 */
class ReachCalibrator(
    /**
     * The same evidence thresholds RockDodge calibrates on - validated on hardware and held
     * across renderers by `check_api_parity.py`. Reused deliberately: how much evidence is enough
     * is not a question AirDraw should answer differently, even though what it measures differs.
     *
     * `minimumTrackedLandmarks` is not applied here. It is 12, which suits a full-body pose; an
     * upper-body calibration that insisted on 12 confident joints would fail a seated player for
     * the same reason the ankle requirement does.
     */
    private val requiredStableFrames: Int = 8,
    private val minimumConfidence: Float = 0.35f,
    /**
     * How far the wrist reaches from the shoulder, as a multiple of shoulder width.
     *
     * **Deliberately conservative, and only a floor.** This was 1.65 - the anthropometric figure
     * for a real arm - and on a television it produced a rectangle wider than the camera can see.
     * Clamped to the frame, every person got the maximum envelope, so reaching the left edge of
     * the canvas meant putting a wrist at the edge of the camera's view. Nobody could: a logged
     * session covered only the middle of the canvas and never reached the toolbar at all. Worse,
     * [ReachEnvelope] only ever grows, so an envelope seeded too wide can never correct itself.
     *
     * So the derived box is now a small, certainly-reachable area, and the *sweep* is what
     * establishes the real reach. A floor that is too small costs a moment of calibration; a
     * ceiling that is too large costs the whole canvas.
     */
    private val reachPerShoulder: Float = 0.80f,
    /**
     * How much of maximum reach counts as comfortable.
     *
     * Mapping the canvas edge to full extension is the strain [ReachEnvelope]'s margin exists to
     * remove, and worse, a fully extended arm puts the hand near the edge of frame where tracking
     * dies mid-stroke.
     */
    private val comfortable: Float = 0.85f,
    /** Kept identical to [ReachEnvelope]'s, so a seeded envelope cannot exceed what it allows. */
    private val safeInset: Float = 0.08f,
    /**
     * How far the wrists must actually travel before calibration will complete.
     *
     * The measurement this whole class exists to take. Without it the profile is guesswork from
     * shoulder width, and guesswork is what put a logged player in the middle third of their own
     * canvas. A fifth of the frame is a deliberate reach - an arm going out and back - and not
     * something somebody produces by standing still.
     */
    private val requiredSweepSpan: Float = 0.20f
) {
    private var stableFrames = 0
    private val widths = mutableListOf<Float>()
    private val centerXs = mutableListOf<Float>()
    private val centerYs = mutableListOf<Float>()
    private val shoulderYs = mutableListOf<Float>()
    private val hipYs = mutableListOf<Float>()

    /** Measured bounds from actual wrist movement, which outrank the derived rectangle. */
    private var sweptMinX = Float.NaN
    private var sweptMaxX = Float.NaN
    private var sweptMinY = Float.NaN
    private var sweptMaxY = Float.NaN

    /**
     * Which hand the person draws with, learned by asking them to raise it.
     *
     * Asked rather than inferred. The scheme used to pick whichever hand the provider listed
     * first with a side, which is not stable frame to frame - on a device the pointer flipped
     * between somebody's two wrists while they held still, laying down strokes nobody asked for.
     * Hysteresis papered over that; a raised hand removes the question.
     *
     * It also fixes the measurement. [observeSweep] recorded *both* wrists, so the envelope
     * spanned a whole arm span while the pointer only ever followed one hand - which left the
     * drawing hand able to cover a fraction of its own canvas. Once this is known the sweep
     * restarts and measures that arm alone.
     */
    var drawingHand: AepHandHandedness? = null
        private set

    var profile: ReachProfile? = null
        private set

    /** What the person is being asked for right now, so the overlay can say it. */
    val phase: CalibrationPhase
        get() = when {
            profile != null -> CalibrationPhase.DONE
            drawingHand == null -> CalibrationPhase.RAISE_A_HAND
            else -> CalibrationPhase.REACH_AROUND
        }

    /** How far the drawing hand has actually swept, as a fraction of the frame. For the overlay. */
    val sweptSpanX: Float
        get() = if (sweptMinX.isNaN()) 0f else sweptMaxX - sweptMinX

    /**
     * 0..1, for the overlay's progress indicator. Reaching 1 is what enables Start Drawing.
     *
     * Two things have to happen, so this is the lesser of them: the body has to be readable, and
     * the person has to have actually reached around. The second is the one that matters - it is
     * the only evidence of what they can cover - so it is worth showing them how far along it is
     * rather than letting the bar sit full while nothing is happening.
     */
    val progress: Float
        get() {
            val body = if (requiredStableFrames <= 0) 1f
                       else min(1f, stableFrames.toFloat() / requiredStableFrames)
            val sweep = if (requiredSweepSpan <= 0f) 1f else min(1f, sweptSpanX / requiredSweepSpan)
            return min(body, sweep)
        }

    val isComplete: Boolean get() = profile != null

    fun reset() {
        drawingHand = null
        raisedCandidate = null
        raisedFrames = 0
        stableFrames = 0
        widths.clear(); centerXs.clear(); centerYs.clear(); shoulderYs.clear(); hipYs.clear()
        sweptMinX = Float.NaN; sweptMaxX = Float.NaN; sweptMinY = Float.NaN; sweptMaxY = Float.NaN
        profile = null
    }

    /**
     * Feed a pose frame while the person holds still.
     *
     * @return the profile on the frame that completes calibration, null before that. Returning it
     *   only once means the caller cannot mistake "still calibrating" for "calibrated again".
     */
    fun accept(frame: AepPoseFrame): ReachProfile? {
        if (profile != null) return null
        if (frame.trackingState != AepTrackingState.TRACKED) {
            // Not a reset of the whole attempt - just this run of stillness. A person who blinks
            // out for a frame should not start from zero, which is why only the counter drops.
            stableFrames = 0
            return null
        }

        val leftShoulder = joint(frame, "left_shoulder") ?: return miss()
        val rightShoulder = joint(frame, "right_shoulder") ?: return miss()
        val leftHip = joint(frame, "left_hip") ?: return miss()
        val rightHip = joint(frame, "right_hip") ?: return miss()

        val width = abs(leftShoulder.x - rightShoulder.x)
        if (width < MINIMUM_SHOULDER_WIDTH) {
            // Edge-on to the camera, or so far away that shoulder width is noise. Deriving a
            // reach from it would produce a canvas the size of the room.
            return miss()
        }

        widths += width
        centerXs += (leftShoulder.x + rightShoulder.x) * 0.5f
        shoulderYs += (leftShoulder.y + rightShoulder.y) * 0.5f
        hipYs += (leftHip.y + rightHip.y) * 0.5f
        centerYs += (leftShoulder.y + rightShoulder.y + leftHip.y + rightHip.y) * 0.25f
        stableFrames++

        if (stableFrames < requiredStableFrames) return null
        // The body is readable; now the reach has to have been shown. Standing still perfectly
        // is not calibration, it is only half of it.
        if (drawingHand == null) return null
        if (sweptSpanX < requiredSweepSpan) return null
        val built = build()
        profile = built
        return built
    }

    /**
     * Feed a pose frame while the person moves their arms, before or after completion.
     *
     * Anything a wrist actually reaches beats anything derived from shoulder width, so this
     * widens the rectangle and never narrows it. Safe to call every frame; safe never to call.
     */
    /**
     * Watch for a hand held up above the shoulder, which is how the person names their drawing hand.
     *
     * Requires one wrist clearly up and the other clearly not: both hands raised is somebody
     * stretching, not answering. Held for [requiredStableFrames] so a hand passing through on its
     * way somewhere else does not decide it.
     */
    fun observeRaisedHand(frame: AepPoseFrame) {
        if (drawingHand != null) return
        if (frame.trackingState != AepTrackingState.TRACKED) return
        val shoulderY = listOfNotNull(joint(frame, "left_shoulder"), joint(frame, "right_shoulder"))
            .takeIf { it.size == 2 }?.let { (a, b) -> (a.y + b.y) * 0.5f } ?: return
        val left = joint(frame, "left_wrist")
        val right = joint(frame, "right_wrist")

        // Camera frames put y at the top, so a raised wrist is a *smaller* y than the shoulders.
        val leftUp = left != null && shoulderY - left.y > RAISE_MARGIN
        val rightUp = right != null && shoulderY - right.y > RAISE_MARGIN
        val candidate = when {
            leftUp && !rightUp -> AepHandHandedness.LEFT
            rightUp && !leftUp -> AepHandHandedness.RIGHT
            else -> null
        }
        if (candidate == null || candidate != raisedCandidate) {
            raisedCandidate = candidate
            raisedFrames = 0
            return
        }
        raisedFrames++
        if (raisedFrames < requiredStableFrames) return

        drawingHand = candidate
        // Anything swept before this was two arms at once, which describes an arm span rather
        // than a reach. Start the measurement again now there is one arm to measure.
        sweptMinX = Float.NaN; sweptMaxX = Float.NaN
        sweptMinY = Float.NaN; sweptMaxY = Float.NaN
    }

    fun observeSweep(frame: AepPoseFrame) {
        if (frame.trackingState != AepTrackingState.TRACKED) return
        for (name in wristsToMeasure()) {
            val wrist = joint(frame, name) ?: continue
            sweptMinX = nanMin(sweptMinX, wrist.x)
            sweptMaxX = nanMax(sweptMaxX, wrist.x)
            sweptMinY = nanMin(sweptMinY, wrist.y)
            sweptMaxY = nanMax(sweptMaxY, wrist.y)
        }
        // Once calibrated, keep the published profile in step with what has been seen since.
        if (profile != null) profile = build()
    }

    /** Hand the measured rectangle to the envelope so it starts warm instead of cold. */
    fun seed(envelope: ReachEnvelope) {
        val p = profile ?: return
        envelope.seed(p.minX, p.maxX, p.minY, p.maxY)
    }

    /**
     * Which wrists count towards the reach.
     *
     * The drawing hand alone once it is known. Before that, both - so that a sweep started early
     * is not thrown away entirely, even though it will be restarted the moment a hand goes up.
     */
    private fun wristsToMeasure(): List<String> = when (drawingHand) {
        AepHandHandedness.LEFT -> listOf("left_wrist")
        AepHandHandedness.RIGHT -> listOf("right_wrist")
        else -> WRISTS
    }

    private var raisedCandidate: AepHandHandedness? = null
    private var raisedFrames = 0

    private fun miss(): ReachProfile? {
        stableFrames = 0
        return null
    }

    private fun build(): ReachProfile {
        val width = widths.average().toFloat()
        val centerX = centerXs.average().toFloat()
        val centerY = centerYs.average().toFloat()
        val shoulderY = shoulderYs.average().toFloat()
        val hipY = hipYs.average().toFloat()

        val reach = width * reachPerShoulder * comfortable
        // Horizontally the arms start at the shoulders, so half the shoulder width is free before
        // the arm has to extend at all.
        var minX = centerX - (width * 0.5f + reach)
        var maxX = centerX + (width * 0.5f + reach)
        // Vertically the shoulder is the pivot going up, and the hip roughly bounds how far down
        // a wrist goes before the person is reaching behind their own leg. Down is deliberately
        // shorter than up: a drawing surface that extends below the hip is one people stoop for.
        var minY = shoulderY - reach * UPWARD
        var maxY = max(hipY, shoulderY) + reach * DOWNWARD

        // Anything actually reached outranks anything derived - in both directions.
        //
        // The union used to only widen, which meant a derived box that was too big survived a
        // sweep that proved it wrong. Where there is a real measurement it *replaces* the guess;
        // the derived box only fills in an axis the sweep never covered.
        if (!sweptMinX.isNaN() && sweptMaxX - sweptMinX >= requiredSweepSpan) {
            minX = sweptMinX - width * SWEEP_MARGIN
            maxX = sweptMaxX + width * SWEEP_MARGIN
        }
        if (!sweptMinY.isNaN() && sweptMaxY - sweptMinY >= requiredSweepSpan * 0.5f) {
            minY = sweptMinY - width * SWEEP_MARGIN
            maxY = sweptMaxY + width * SWEEP_MARGIN
        }

        // Never past where the tracker can see, for the reason ReachEnvelope gives: touching an
        // edge that sits at the frame boundary means holding a hand half out of shot, where
        // tracking is lost and the stroke dies.
        minX = minX.coerceIn(safeInset, 1f - safeInset)
        maxX = maxX.coerceIn(safeInset, 1f - safeInset)
        minY = minY.coerceIn(safeInset, 1f - safeInset)
        maxY = maxY.coerceIn(safeInset, 1f - safeInset)

        return ReachProfile(width, centerX, centerY, min(minX, maxX), max(minX, maxX),
                            min(minY, maxY), max(minY, maxY))
    }

    private fun joint(frame: AepPoseFrame, name: String): AepPosePoint? {
        val point = frame[name] ?: return null
        return if (point.confidence < minimumConfidence) null else point
    }

    private fun nanMin(a: Float, b: Float) = if (a.isNaN()) b else min(a, b)
    private fun nanMax(a: Float, b: Float) = if (a.isNaN()) b else max(a, b)

    private companion object {
        val WRISTS = listOf("left_wrist", "right_wrist")

        /**
         * How far above the shoulders a wrist must be to count as raised, in frame units.
         *
         * Far enough that a hand resting at chest height is not an answer, close enough that
         * somebody does not have to reach for the ceiling.
         */
        const val RAISE_MARGIN = 0.06f

        /** Below this, shoulder width is noise rather than a measurement. */
        const val MINIMUM_SHOULDER_WIDTH = 0.02f

        /** Reach above the shoulder line, as a fraction of arm reach. Derived, not measured. */
        const val UPWARD = 0.9f

        /** Reach below the hip line. Shorter than [UPWARD]: people do not draw at their knees. */
        const val DOWNWARD = 0.25f

        /**
         * A little past what was actually swept, in shoulder widths.
         *
         * Mapping the canvas edge to the furthest a wrist happened to go during calibration means
         * the edge can only be touched by repeating that exact reach. A small margin makes the
         * corners comfortable instead of a stretch.
         */
        const val SWEEP_MARGIN = 0.35f
    }
}
