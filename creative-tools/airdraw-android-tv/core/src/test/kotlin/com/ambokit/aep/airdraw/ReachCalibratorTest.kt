package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.AepPosePoint
import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pose frame with only the joints an upper-body calibration should need.
 *
 * Note what is missing: ankles, knees, everything below the hip. That absence is the point -
 * these frames are what a seated player produces, and the calibrator has to work from them.
 */
private fun upperBody(
    shoulderY: Float = 0.30f,
    hipY: Float = 0.62f,
    centerX: Float = 0.50f,
    shoulderWidth: Float = 0.12f,
    confidence: Float = 0.9f,
    tracking: AepTrackingState = AepTrackingState.TRACKED,
    wrists: Pair<Pair<Float, Float>, Pair<Float, Float>>? = null,
    omit: Set<String> = emptySet()
): AepPoseFrame {
    val joints = mutableMapOf<String, AepPosePoint>()
    fun put(name: String, x: Float, y: Float) {
        if (name in omit) return
        joints[name] = AepPosePoint(name, x, y, null, confidence)
    }
    put("left_shoulder", centerX - shoulderWidth / 2f, shoulderY)
    put("right_shoulder", centerX + shoulderWidth / 2f, shoulderY)
    put("left_hip", centerX - shoulderWidth / 2.4f, hipY)
    put("right_hip", centerX + shoulderWidth / 2.4f, hipY)
    wrists?.let { (l, r) ->
        put("left_wrist", l.first, l.second)
        put("right_wrist", r.first, r.second)
    }
    return AepPoseFrame("p1", 1L, 0L, 0L, tracking, joints)
}

/**
 * A deliberate reach: wrists out to [from]..[to] and back.
 *
 * Calibration will not complete without one, which is the point - a profile derived from shoulder
 * width alone put a logged player in the middle third of their own canvas.
 */
private fun ReachCalibrator.sweepTo(from: Float = 0.25f, to: Float = 0.75f,
                                    top: Float = 0.25f, bottom: Float = 0.65f,
                                    frames: Int = 12) {
    raiseRightHand(frames)
    // The sweep measures the drawing hand only, so both wrists are moved to the same places -
    // whichever one is being measured sees the full span.
    observeSweep(upperBody(wrists = (from to top) to (from to top)))
    observeSweep(upperBody(wrists = (to to bottom) to (to to bottom)))
    observeSweep(upperBody(wrists = (from to bottom) to (from to bottom)))
    observeSweep(upperBody(wrists = (to to top) to (to to top)))
}

/** Hold the right wrist above the shoulders: how a person names their drawing hand. */
private fun ReachCalibrator.raiseRightHand(frames: Int = 12) {
    repeat(frames) {
        observeRaisedHand(upperBody(shoulderY = 0.30f, wrists = (0.30f to 0.50f) to (0.70f to 0.15f)))
    }
}

class ReachCalibratorTest {

    // ---- the reason this class is not PoseCalibrator -------------------------------------------

    /**
     * The failure being avoided. `PoseCalibrator.accept` does `leftAnkle ?: return null`, so a
     * player sitting on a sofa never calibrates and never gets past the Start Drawing button.
     */
    @Test
    fun `calibrates a seated player whose ankles are nowhere in frame`() {
        val c = ReachCalibrator(requiredStableFrames = 3)
        c.sweepTo()
        var result: ReachProfile? = null
        repeat(3) { result = c.accept(upperBody()) }
        assertNotNull(result, "shoulders and hips are enough; ankles are not required")
        assertTrue(c.isComplete)
    }

    @Test
    fun `standing perfectly still is not calibration`() {
        // The failure this guards: a profile derived from shoulder width alone came out wider
        // than the camera can see, got clamped to the maximum, and left a logged player able to
        // cover only the middle of their canvas - with no way back, because the envelope only
        // ever grows.
        val c = ReachCalibrator(requiredStableFrames = 2)
        repeat(20) { c.accept(upperBody()) }
        assertNull(c.profile, "nothing here showed how far this person can actually reach")
        assertTrue(!c.isComplete)

        c.sweepTo()
        c.accept(upperBody()); c.accept(upperBody())
        assertTrue(c.isComplete, "and a reach is all it was waiting for")
    }

    @Test
    fun `the measured reach replaces the guess rather than widening it`() {
        // A small sweep must produce a small envelope. If the derived box survived, somebody who
        // reached a narrow area would still be given the whole frame.
        val c = ReachCalibrator(requiredStableFrames = 1)
        c.sweepTo(from = 0.38f, to = 0.62f, top = 0.35f, bottom = 0.55f)
        val p = c.accept(upperBody())!!
        assertTrue(p.spanX < 0.55f, "x span was ${p.spanX}; the guess was not replaced")
        assertTrue(p.minX > 0.15f && p.maxX < 0.85f, "x was ${p.minX}..${p.maxX}")
    }

    @Test
    fun `progress reaches one exactly when the profile arrives`() {
        val c = ReachCalibrator(requiredStableFrames = 4)
        assertEquals(0f, c.progress)
        c.sweepTo()
        c.accept(upperBody()); c.accept(upperBody()); c.accept(upperBody())
        assertEquals(0.75f, c.progress)
        assertTrue(!c.isComplete, "the button must still be disabled here")
        c.accept(upperBody())
        assertEquals(1f, c.progress)
        assertTrue(c.isComplete)
    }

    @Test
    fun `the profile is handed back once, not on every later frame`() {
        val c = ReachCalibrator(requiredStableFrames = 2)
        c.sweepTo()
        c.accept(upperBody())
        assertNotNull(c.accept(upperBody()))
        assertNull(c.accept(upperBody()), "a second non-null would read as calibrating again")
    }

    // ---- the rectangle -------------------------------------------------------------------------

    @Test
    fun `a wider person gets a wider canvas`() {
        fun spanFor(width: Float): Float {
            val c = ReachCalibrator(requiredStableFrames = 1)
            // A sweep small enough that the derived floor is what decides the answer.
            c.sweepTo(from = 0.46f, to = 0.68f, top = 0.40f, bottom = 0.52f)
            return c.accept(upperBody(shoulderWidth = width))!!.spanX
        }
        assertTrue(spanFor(0.16f) > spanFor(0.08f),
            "the derived floor is built from shoulder width, so it has to move with it")
    }

    @Test
    fun `the rectangle never extends past where the tracker can see`() {
        // A very wide shoulder measurement would derive a reach beyond the frame entirely.
        val c = ReachCalibrator(requiredStableFrames = 1, safeInset = 0.08f)
        c.sweepTo(from = 0.02f, to = 0.98f, top = 0.02f, bottom = 0.98f)
        val p = c.accept(upperBody(shoulderWidth = 0.45f))!!
        assertTrue(p.minX >= 0.08f && p.maxX <= 0.92f, "x was ${p.minX}..${p.maxX}")
        assertTrue(p.minY >= 0.08f && p.maxY <= 0.92f, "y was ${p.minY}..${p.maxY}")
    }

    @Test
    fun `the canvas extends further above the shoulder than below the hip`() {
        val c = ReachCalibrator(requiredStableFrames = 1)
        // A sweep narrow enough vertically that the derived shape is what is being measured.
        c.sweepTo(from = 0.25f, to = 0.75f, top = 0.44f, bottom = 0.48f)
        val p = c.accept(upperBody(shoulderY = 0.30f, hipY = 0.62f))!!
        val above = 0.30f - p.minY
        val below = p.maxY - 0.62f
        assertTrue(above > below, "people draw above their shoulders, not at their knees ($above vs $below)")
    }

    // ---- measurement beats derivation ----------------------------------------------------------

    @Test
    fun `a wrist that actually reaches further widens the rectangle`() {
        val c = ReachCalibrator(requiredStableFrames = 1)
        c.sweepTo(from = 0.40f, to = 0.60f, top = 0.40f, bottom = 0.50f)
        val derived = c.accept(upperBody())!!
        // The drawing hand is the right one, so it is the right wrist that has to go there.
        c.observeSweep(upperBody(wrists = (0.50f to 0.50f) to (0.10f to 0.50f)))
        c.observeSweep(upperBody(wrists = (0.50f to 0.50f) to (0.90f to 0.50f)))
        val swept = c.profile!!
        assertTrue(swept.minX < derived.minX, "an arm that reached to 0.10 outranks the estimate")
        assertTrue(swept.maxX > derived.maxX)
    }

    @Test
    fun `a sweep never narrows the rectangle`() {
        val c = ReachCalibrator(requiredStableFrames = 1)
        c.sweepTo()
        val derived = c.accept(upperBody())!!
        // Hands held together in the middle: real movement, but no new reach.
        c.observeSweep(upperBody(wrists = (0.49f to 0.45f) to (0.51f to 0.45f)))
        val after = c.profile!!
        assertTrue(after.spanX >= derived.spanX, "narrowing would shrink the canvas under the player")
    }

    @Test
    fun `a sweep made before the drawing hand is known is discarded`() {
        // Two arms moving at once describes an arm span, not a reach. Keeping it would hand the
        // drawing hand a canvas twice the size of anything it can cover.
        val c = ReachCalibrator(requiredStableFrames = 2)
        c.observeSweep(upperBody(wrists = (0.10f to 0.50f) to (0.90f to 0.50f)))
        assertTrue(c.sweptSpanX > 0.7f, "both wrists were seen, so the span is an arm span")

        c.raiseRightHand(frames = 5)
        assertEquals(0f, c.sweptSpanX, "and it starts again once there is one arm to measure")
        assertNull(c.accept(upperBody()), "so calibration is not complete on that evidence")
    }

    @Test
    fun `the reach measured is the drawing hand's, not both arms'`() {
        val c = ReachCalibrator(requiredStableFrames = 2)
        c.raiseRightHand(frames = 5)
        // The left hand flails to the far edge; the right one stays in a modest area.
        repeat(4) {
            c.observeSweep(upperBody(wrists = (0.02f to 0.10f) to (0.45f to 0.40f)))
            c.observeSweep(upperBody(wrists = (0.98f to 0.90f) to (0.72f to 0.60f)))
        }
        c.accept(upperBody())
        val p = c.accept(upperBody())!!
        assertTrue(p.minX > 0.20f, "the left hand's reach must not be in it; x was ${p.minX}..${p.maxX}")
        assertTrue(p.maxX < 0.90f)
    }

    @Test
    fun `both hands raised is not an answer`() {
        val c = ReachCalibrator(requiredStableFrames = 2)
        repeat(10) {
            c.observeRaisedHand(upperBody(shoulderY = 0.30f, wrists = (0.30f to 0.12f) to (0.70f to 0.12f)))
        }
        assertNull(c.drawingHand, "somebody stretching is not somebody choosing")
    }

    @Test
    fun `a hand passing through does not decide it`() {
        val c = ReachCalibrator(requiredStableFrames = 6)
        repeat(2) {
            c.observeRaisedHand(upperBody(shoulderY = 0.30f, wrists = (0.30f to 0.50f) to (0.70f to 0.12f)))
        }
        // ...and now the other one goes up instead.
        repeat(2) {
            c.observeRaisedHand(upperBody(shoulderY = 0.30f, wrists = (0.30f to 0.12f) to (0.70f to 0.50f)))
        }
        assertNull(c.drawingHand)
    }

    @Test
    fun `the raised hand is the one that is tracked`() {
        val c = ReachCalibrator(requiredStableFrames = 2)
        repeat(4) {
            c.observeRaisedHand(upperBody(shoulderY = 0.30f, wrists = (0.30f to 0.12f) to (0.70f to 0.50f)))
        }
        assertEquals(AepHandHandedness.LEFT, c.drawingHand)
    }

    @Test
    fun `the phase says what is being asked for`() {
        val c = ReachCalibrator(requiredStableFrames = 2)
        assertEquals(CalibrationPhase.RAISE_A_HAND, c.phase)
        c.raiseRightHand(frames = 5)
        assertEquals(CalibrationPhase.REACH_AROUND, c.phase)
        c.sweepTo(frames = 0)
        c.accept(upperBody()); c.accept(upperBody())
        assertEquals(CalibrationPhase.DONE, c.phase)
    }

    // ---- declining rather than guessing --------------------------------------------------------

    @Test
    fun `an untracked frame interrupts the run but does not discard the attempt`() {
        val c = ReachCalibrator(requiredStableFrames = 3)
        c.sweepTo()
        c.accept(upperBody()); c.accept(upperBody())
        c.accept(upperBody(tracking = AepTrackingState.LOST))
        assertEquals(0f, c.progress, "the run of stillness restarts")
        repeat(3) { c.accept(upperBody()) }
        assertTrue(c.isComplete, "but the person can simply hold still again")
    }

    @Test
    fun `a person edge-on to the camera does not calibrate`() {
        val c = ReachCalibrator(requiredStableFrames = 1)
        // Shoulders projecting onto nearly the same x: width is noise, and a reach derived from
        // it would be the size of the room.
        assertNull(c.accept(upperBody(shoulderWidth = 0.005f)))
    }

    @Test
    fun `missing hips do not calibrate rather than calibrating wrongly`() {
        val c = ReachCalibrator(requiredStableFrames = 1)
        assertNull(c.accept(upperBody(omit = setOf("left_hip"))))
    }

    @Test
    fun `low confidence joints are treated as absent`() {
        val c = ReachCalibrator(requiredStableFrames = 1, minimumConfidence = 0.5f)
        assertNull(c.accept(upperBody(confidence = 0.2f)))
    }

    // ---- handing the result to the envelope ----------------------------------------------------

    @Test
    fun `seeding the envelope replaces the cold start`() {
        val envelope = ReachEnvelope()
        assertTrue(!envelope.isLearned, "cold, it falls back to fixed bounds")

        val c = ReachCalibrator(requiredStableFrames = 1)
        c.sweepTo()
        c.accept(upperBody())
        c.seed(envelope)

        assertTrue(envelope.isLearned, "after calibration the first stroke is already mapped right")
        val p = c.profile!!
        assertEquals(0f, envelope.mapX(p.minX + 0.02f), absoluteTolerance = 0.01f)
        assertEquals(1f, envelope.mapX(p.maxX - 0.02f), absoluteTolerance = 0.01f)
    }

    @Test
    fun `a seeded envelope still grows when an arm reaches further`() {
        val envelope = ReachEnvelope()
        val c = ReachCalibrator(requiredStableFrames = 1)
        c.sweepTo(from = 0.35f, to = 0.65f)
        c.accept(upperBody()); c.seed(envelope)
        val before = envelope.spanX
        repeat(20) { envelope.observe(0.10f, 0.5f) }
        assertTrue(envelope.spanX > before, "a seed is a better guess, not a final answer")
    }

    @Test
    fun `seeding does nothing when calibration has not completed`() {
        val envelope = ReachEnvelope()
        ReachCalibrator(requiredStableFrames = 5).seed(envelope)
        assertTrue(!envelope.isLearned)
    }
}
