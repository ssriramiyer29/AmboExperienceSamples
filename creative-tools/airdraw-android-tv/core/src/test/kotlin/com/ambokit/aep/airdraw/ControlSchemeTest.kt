package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.AepPosePoint
import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness
import com.ambokit.aep.core.capabilities.AepHandLandmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hand in one of the three shapes, built the same way [HandShapeReaderTest] builds them. */
private fun shaped(
    shape: HandShape,
    handedness: AepHandHandedness = AepHandHandedness.RIGHT
): AepHand {
    val curled = 1f
    val out = 2f
    val (reach, gap) = when (shape) {
        HandShape.PINCH -> out to 0.1f
        HandShape.OPEN_PALM -> out to 1f
        HandShape.FIST -> curled to 0.1f
        HandShape.UNKNOWN -> curled to 1f
    }
    val marks = mutableListOf<AepHandLandmark?>()
    fun put(n: String, x: Float, y: Float) = marks.add(AepHandLandmark(n, x.toDouble(), y.toDouble(), 0.9))
    put("wrist", 0f, 0f)
    put("middle_finger_mcp", 0f, 1f)
    val stems = listOf("index_finger", "middle_finger", "ring_finger", "pinky")
    for ((i, stem) in stems.withIndex()) {
        val x = (i - 1.5f) * 0.05f
        put("${stem}_pip", x, 1.5f)
        put("${stem}_tip", x, if (shape == HandShape.UNKNOWN && i >= 2) curled else reach)
    }
    put("thumb_tip", (0 - 1.5f) * 0.05f + gap, reach)
    return AepHand(handedness, 0.9, marks)
}

private fun handFrame(vararg hands: AepHand) =
    AepHandFrame(AepTrackingState.TRACKED, hands.toList())

private fun poseFrame(leftX: Float = 0.3f, rightX: Float = 0.7f, y: Float = 0.5f) =
    AepPoseFrame("p", 1L, 0L, 0L, AepTrackingState.TRACKED, mapOf(
        "left_wrist" to AepPosePoint("left_wrist", leftX, y, null, 0.9f),
        "right_wrist" to AepPosePoint("right_wrist", rightX, y, null, 0.9f)
    ))

class ControlSchemeTest {

    // ---- the latch and the modes under it ------------------------------------------------------

    @Test
    fun `an open palm does not draw until a pinch has turned drawing on`() {
        val c = ControlScheme()
        assertEquals(CanvasMode.IDLE, c.update(handFrame(shaped(HandShape.OPEN_PALM)), 0).mode,
            "the palm is the mode, the pinch is the permission")
        c.update(handFrame(shaped(HandShape.PINCH)), 10)
        assertEquals(CanvasMode.DRAWING, c.update(handFrame(shaped(HandShape.OPEN_PALM)), 20).mode)
    }

    @Test
    fun `pinching again stops drawing`() {
        val c = ControlScheme()
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM)), 10)
        c.update(handFrame(shaped(HandShape.PINCH)), 20)
        assertEquals(CanvasMode.IDLE, c.update(handFrame(shaped(HandShape.OPEN_PALM)), 30).mode)
    }

    /**
     * The collision the whole gesture design exists to avoid: if a fist read as a pinch, going to
     * pan the canvas would silently turn drawing off.
     */
    @Test
    fun `panning with a fist never disturbs the draw latch`() {
        val c = ControlScheme()
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        assertTrue(c.drawOn)
        repeat(30) { c.update(handFrame(shaped(HandShape.FIST)), (it + 1) * 10L) }
        assertTrue(c.drawOn, "thirty frames of fist must leave drawing exactly as it was")
        assertEquals(CanvasMode.DRAWING, c.update(handFrame(shaped(HandShape.OPEN_PALM)), 400).mode)
    }

    @Test
    fun `a fist pans once it is held`() {
        val c = ControlScheme(fistHoldFrames = 4)
        repeat(3) { assertEquals(CanvasMode.IDLE, c.update(handFrame(shaped(HandShape.FIST)), it * 16L).mode) }
        assertEquals(CanvasMode.PANNING, c.update(handFrame(shaped(HandShape.FIST)), 64).mode)
    }

    /**
     * The failure this guards: a hand curls on its way to somewhere else, and on a device 49 of
     * 127 diagnostic samples read as a fist while somebody was trying to draw. Every one dragged
     * the picture out from under them.
     */
    @Test
    fun `a hand curling in passing does not drag the canvas`() {
        val c = ControlScheme(fistHoldFrames = 4)
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM)), 0)
        assertEquals(CanvasMode.IDLE, c.update(handFrame(shaped(HandShape.FIST)), 16).mode)
        assertEquals(CanvasMode.IDLE, c.update(handFrame(shaped(HandShape.FIST)), 32).mode)
        // ...and back open again before it ever counted.
        c.update(handFrame(shaped(HandShape.OPEN_PALM)), 48)
        assertEquals(CanvasMode.IDLE, c.update(handFrame(shaped(HandShape.FIST)), 64).mode,
            "the count must have started again")
    }

    // ---- one hand, deliberately -----------------------------------------------------------------

    /**
     * Two-fisted zoom used to live here. It is gone, and this is what replaced it: a second fist
     * is not a mode of its own. Watching for one meant the scheme had an opinion about a hand the
     * person was not drawing with, and on a television a resting arm at the edge of frame
     * competed with the drawing one for the pointer.
     */
    @Test
    fun `a second fist is not a gesture - the drawing hand still pans`() {
        val c = ControlScheme()
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        var state = c.update(handFrame(
            shaped(HandShape.FIST, AepHandHandedness.RIGHT),
            shaped(HandShape.FIST, AepHandHandedness.LEFT)
        ), 10)
        repeat(4) {
            c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 20L + it * 16)
            state = c.update(handFrame(
                shaped(HandShape.FIST, AepHandHandedness.RIGHT),
                shaped(HandShape.FIST, AepHandHandedness.LEFT)
            ), 20L + it * 16)
        }
        assertEquals(CanvasMode.PANNING, state.mode)
        assertEquals(0.8f, state.pointer?.x, "and the position is still the drawing hand's")
    }

    /**
     * The failure this fixes: `unknown` is a legal handedness in `camera.hand@1` and the provider
     * uses it. Matching the pointer to the hand stream's own labelling meant that every time it
     * declined to name a hand, the pointer fell back to that hand's own wrist and jumped. Pose
     * names its joints unambiguously, so once the arm is known the two streams need not agree.
     */
    @Test
    fun `the position follows the named wrist even when the hand stream will not name the hand`() {
        val c = ControlScheme()
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)

        val unnamed = shaped(HandShape.OPEN_PALM, AepHandHandedness.UNKNOWN)
        val state = c.update(handFrame(unnamed), 10)
        assertEquals(PointerOrigin.POSE_WRIST, state.pointer?.origin,
            "an unnamed hand must not cost us the pose wrist we already know we want")
        assertEquals(0.8f, state.pointer?.x)
        assertEquals(AepHandHandedness.RIGHT, state.which)
    }

    @Test
    fun `without a named drawing hand it still falls back to the hand in charge`() {
        val c = ControlScheme()
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        val state = c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.LEFT)), 10)
        assertEquals(0.2f, state.pointer?.x)
    }

    // ---- the pointer -------------------------------------------------------------------------

    @Test
    fun `the pointer follows the arm the gesture was read from`() {
        val c = ControlScheme()
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        val right = c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT)), 10)
        assertEquals(0.8f, right.pointer?.x)
        val left = c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.LEFT)), 20)
        assertEquals(0.2f, left.pointer?.x)
    }

    @Test
    fun `a pinching hand takes charge over a placed one`() {
        val c = ControlScheme()
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        val state = c.update(handFrame(
            shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT),
            shaped(HandShape.PINCH, AepHandHandedness.LEFT)
        ), 10)
        assertEquals(AepHandHandedness.LEFT, state.which, "a pinch is a deliberate statement")
    }

    /**
     * Two hands sharing one hysteresis latch is how the off hand reaching for the palette used to
     * become the pen. The off hand gets its own reader so its gap cannot set the primary's state.
     */
    @Test
    fun `the off hand's pinch state does not leak into the primary hand's`() {
        val c = ControlScheme()
        c.observePose(poseFrame(), atMs = 0)
        repeat(10) {
            c.update(handFrame(
                shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT),
                shaped(HandShape.FIST, AepHandHandedness.LEFT)
            ), it * 10L)
        }
        assertTrue(!c.drawOn, "nothing here was a pinch by the hand in charge")
    }

    // ---- holding on through a gap ---------------------------------------------------------------

    @Test
    fun `hands vanishing does not put the pen down`() {
        val c = ControlScheme()
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        assertTrue(c.drawOn)
        repeat(5) { c.update(null, 10L + it * 10) }
        assertTrue(c.drawOn, "a dropout is not somebody asking to stop drawing")
    }

    // ---- a null frame is not an empty frame -----------------------------------------------------
    //
    // The distinction no test drew, and a device session paid for: the renderer runs at 60fps and
    // the hand provider sends at 11-15Hz, so three display frames in four carry no new hand frame.
    // Reading that as "no hands" dropped the shape to UNKNOWN and the mode to IDLE on 75% of
    // frames and never asked pose where the wrist was. The log showed ten stroke-starts in sixty
    // frames and thirty-one strokes on the canvas for a handful of intended marks.

    @Test
    fun `a display frame with no new hand frame holds the shape and keeps drawing`() {
        val c = ControlScheme()
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM)), 30)
        assertEquals(CanvasMode.DRAWING, c.update(handFrame(shaped(HandShape.OPEN_PALM)), 60).mode)

        // Three display frames at 60fps with no hand frame behind them.
        for (t in listOf(76L, 92L, 108L)) {
            c.observePose(poseFrame(), atMs = t)
            val state = c.update(null, t)
            assertEquals(CanvasMode.DRAWING, state.mode, "the stroke must survive a gap at t=$t")
            assertEquals(HandShape.OPEN_PALM, state.shape)
        }
    }

    @Test
    fun `a held frame still gets a position from pose`() {
        val c = ControlScheme()
        c.observePose(poseFrame(rightX = 0.61f), atMs = 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM)), 0)
        c.observePose(poseFrame(rightX = 0.64f), atMs = 16)
        val held = c.update(null, 16)
        assertEquals(PointerOrigin.POSE_WRIST, held.pointer?.origin,
            "pose is the continuous signal; a gap in the hand stream is exactly when it earns its keep")
        assertEquals(0.64f, held.pointer?.x, "and it must be the new pose, not the last one")
    }

    @Test
    fun `a run of held frames does not restart the stroke over and over`() {
        val c = ControlScheme()
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM)), 30)

        var drawing = 0
        var now = 60L
        // One second of display frames with a hand frame only every fourth one.
        repeat(60) { i ->
            c.observePose(poseFrame(), atMs = now)
            val frame = if (i % 4 == 0) handFrame(shaped(HandShape.OPEN_PALM)) else null
            if (c.update(frame, now).mode == CanvasMode.DRAWING) drawing++
            now += 16
        }
        assertEquals(60, drawing, "every frame of a held palm is a drawing frame, not one in four")
    }

    @Test
    fun `a held fist keeps panning rather than flickering to idle`() {
        val c = ControlScheme(fistHoldFrames = 1)
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.FIST)), 0)
        assertEquals(CanvasMode.PANNING, c.update(null, 16).mode)
        assertEquals(CanvasMode.PANNING, c.update(null, 32).mode)
    }

    @Test
    fun `a shape is not held past the point where the stream has stopped`() {
        val c = ControlScheme(handHoldMs = 400, fistHoldFrames = 1)
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.FIST)), 0)
        assertEquals(CanvasMode.PANNING, c.update(null, 300).mode)
        assertEquals(CanvasMode.IDLE, c.update(null, 500).mode,
            "half a second on, the fist is one the person has long since opened")
        assertEquals(HandShape.UNKNOWN, c.update(null, 600).shape)
    }

    @Test
    fun `the provider reporting no hands does clear the shape`() {
        // Distinct from a null frame: this is the provider actually saying it can see nobody.
        val c = ControlScheme(fistHoldFrames = 1)
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.FIST)), 0)
        assertEquals(CanvasMode.PANNING, c.update(null, 16).mode)
        val empty = c.update(handFrame(), 32)
        assertEquals(CanvasMode.IDLE, empty.mode)
        assertEquals(HandShape.UNKNOWN, empty.shape)
        assertEquals(CanvasMode.IDLE, c.update(null, 48).mode, "and it stays cleared")
    }

    @Test
    fun `a held frame cannot re-toggle the draw latch`() {
        val c = ControlScheme()
        c.observePose(poseFrame(), atMs = 0)
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        assertTrue(c.drawOn)
        repeat(20) { c.update(null, 16L + it * 16) }
        assertTrue(c.drawOn, "a frame carrying no new evidence is not a new pinch")
    }

    @Test
    fun `resetting clears the latch for a new session`() {
        val c = ControlScheme()
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        assertTrue(c.drawOn)
        c.reset()
        assertTrue(!c.drawOn)
    }

    @Test
    fun `without pose the scheme still works from the hand's own wrist`() {
        // The concurrency fallback, end to end: no pose frame was ever observed.
        val c = ControlScheme()
        c.update(handFrame(shaped(HandShape.PINCH)), 0)
        val state = c.update(handFrame(shaped(HandShape.OPEN_PALM)), 10)
        assertEquals(CanvasMode.DRAWING, state.mode)
        assertEquals(PointerOrigin.HAND_WRIST, state.pointer?.origin)
    }
}

class PrimaryHandStabilityTest {

    /**
     * The failure: with both hands visible and neither pinching, the hand in charge was "the
     * first one in the provider's list with a side" - and that ordering is not stable. The
     * pointer flipped between the two wrists frame to frame, which on a device read as the cursor
     * jumping left and right while the person held still, laying down strokes nobody asked for.
     */
    @Test
    fun `the hand in charge does not flip when the provider reorders its list`() {
        val c = ControlScheme()
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)

        val right = shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT)
        val left = shaped(HandShape.OPEN_PALM, AepHandHandedness.LEFT)

        val first = c.update(handFrame(right, left), 0)
        assertEquals(AepHandHandedness.RIGHT, first.which)

        // Same two hands, list reversed - which a provider is entitled to do.
        repeat(10) { i ->
            // Pose re-observed each frame; otherwise it goes stale partway through and the test
            // would be measuring the hand-wrist fallback rather than the hand-in-charge choice.
            c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 16L + i * 16)
            val state = c.update(handFrame(left, right), 16L + i * 16)
            assertEquals(AepHandHandedness.RIGHT, state.which, "the hand in charge changed at frame $i")
            assertEquals(0.8f, state.pointer?.x, "and so did the position")
        }
    }

    @Test
    fun `a deliberate pinch still takes charge from the other hand`() {
        val c = ControlScheme()
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT)), 0)
        val state = c.update(handFrame(
            shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT),
            shaped(HandShape.PINCH, AepHandHandedness.LEFT)
        ), 16)
        assertEquals(AepHandHandedness.LEFT, state.which, "a pinch is a deliberate statement")
    }

    @Test
    fun `the other hand takes over when the first one leaves`() {
        val c = ControlScheme()
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT)), 0)
        val state = c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.LEFT)), 16)
        assertEquals(AepHandHandedness.LEFT, state.which, "sticking to a hand that is gone is worse")
    }
}

class DrawingHandExclusivityTest {

    /**
     * The gap this closes: locking the *position* to the named wrist was only half of it. The
     * shape still came from whichever hand `choosePrimary` could match, so an off hand curling
     * into a fist put the canvas into pan mode while the pointer followed the drawing hand -
     * reported from a device as "it is still trying to detect my other wrist".
     */
    @Test
    fun `the off hand cannot drive the modes`() {
        val c = ControlScheme(fistHoldFrames = 1)
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)

        // Only the left hand is in frame, and it is a fist.
        val state = c.update(handFrame(shaped(HandShape.FIST, AepHandHandedness.LEFT)), 10)
        assertEquals(HandShape.UNKNOWN, state.shape, "that is not the hand they named")
        assertEquals(CanvasMode.IDLE, state.mode, "so it must not pan the canvas")
    }

    @Test
    fun `the off hand cannot toggle drawing either`() {
        val c = ControlScheme()
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(), atMs = 0)
        repeat(10) {
            c.update(handFrame(shaped(HandShape.PINCH, AepHandHandedness.LEFT)), it * 16L)
        }
        assertTrue(!c.drawOn, "a pinch by the other hand is not a request to start drawing")
    }

    @Test
    fun `the named hand still drives everything when it is there`() {
        val c = ControlScheme(fistHoldFrames = 1)
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(leftX = 0.2f, rightX = 0.8f), atMs = 0)
        val state = c.update(handFrame(
            shaped(HandShape.FIST, AepHandHandedness.LEFT),
            shaped(HandShape.OPEN_PALM, AepHandHandedness.RIGHT)
        ), 10)
        assertEquals(HandShape.OPEN_PALM, state.shape)
        assertEquals(0.8f, state.pointer?.x)
    }

    /**
     * `unknown` is a legal handedness and the provider uses it. A single unplaced hand is the one
     * they raised - there is nothing else it could be - so refusing it would make the scheme
     * useless against a provider that never names hands.
     */
    @Test
    fun `a lone unplaced hand is accepted as the named one`() {
        val c = ControlScheme()
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(rightX = 0.8f), atMs = 0)
        val state = c.update(handFrame(shaped(HandShape.OPEN_PALM, AepHandHandedness.UNKNOWN)), 10)
        assertEquals(HandShape.OPEN_PALM, state.shape)
        assertEquals(0.8f, state.pointer?.x, "and the position is still the named wrist's")
    }

    @Test
    fun `two unplaced hands are declined, because either could be the one`() {
        val c = ControlScheme(fistHoldFrames = 1)
        c.drawingHand = AepHandHandedness.RIGHT
        c.observePose(poseFrame(), atMs = 0)
        val state = c.update(handFrame(
            shaped(HandShape.FIST, AepHandHandedness.UNKNOWN),
            shaped(HandShape.FIST, AepHandHandedness.UNKNOWN)
        ), 10)
        assertEquals(HandShape.UNKNOWN, state.shape)
        assertEquals(CanvasMode.IDLE, state.mode)
    }
}
