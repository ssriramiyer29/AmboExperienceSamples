package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness
import com.ambokit.aep.core.capabilities.AepHandLandmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning `camera.hand@1` into two hands with jobs.
 *
 * The provider is the part of this that AirDraw does not control, so these cover what it is
 * allowed to send rather than only what a good one does send.
 */
class HandInterpreterTest {

    private val aspect = 9f / 16f

    private fun hand(
        handedness: AepHandHandedness,
        x: Double,
        pinch: Double = 0.0,
        confidence: Double = 0.9,
        y: Double = 0.43
    ) = AepHand(
        handedness = handedness,
        confidence = confidence,
        landmarks = listOf(
            AepHandLandmark(name = "thumb_tip", x = x, y = y, confidence = confidence),
            AepHandLandmark(name = "index_finger_tip", x = x, y = y, confidence = confidence)
        ),
        pinchStrength = pinch
    )

    private fun frame(vararg hands: AepHand) =
        AepHandFrame(trackingState = AepTrackingState.TRACKED, hands = hands.toList())

    @Test
    fun `hands land in the slot the provider names`() {
        val reader = HandInterpreter()
        val input = reader.read(frame(
            hand(AepHandHandedness.LEFT, 0.2),
            hand(AepHandHandedness.RIGHT, 0.8)
        ), aspect, 0L)

        assertNotNull(input.left)
        assertNotNull(input.right)
        assertEquals(2, input.handCount)
        assertNull(input.unplaced)
    }

    @Test
    fun `two unplaced hands are sorted out by where they are`() {
        val reader = HandInterpreter()
        // Mirroring flips x, so the hand at 0.8 in camera space ends up on the left of the
        // screen - which is the point of asking after mirroring rather than before.
        val input = reader.read(frame(
            hand(AepHandHandedness.UNKNOWN, 0.8),
            hand(AepHandHandedness.UNKNOWN, 0.2)
        ), aspect, 0L)

        val left = input.left
        val right = input.right
        assertNotNull(left)
        assertNotNull(right)
        assertTrue(left.x < right.x, "the left hand must be the one further left")
        assertNull(input.unplaced)
    }

    @Test
    fun `one unplaced hand stays unplaced`() {
        val reader = HandInterpreter()
        val input = reader.read(frame(hand(AepHandHandedness.UNKNOWN, 0.5)), aspect, 0L)

        assertNull(input.left)
        assertNull(input.right)
        assertNotNull(input.unplaced, "guessing a side here would hand a left-hander an invisible pen")
        assertEquals(1, input.handCount)
    }

    @Test
    fun `an unplaced hand answers for whichever side is asked for`() {
        val reader = HandInterpreter()
        val input = reader.read(frame(hand(AepHandHandedness.UNKNOWN, 0.5)), aspect, 0L)

        // It is the only hand there is, so it is the pen whichever hand the player chose.
        assertNotNull(input.hand(AepHandHandedness.LEFT))
        assertNotNull(input.hand(AepHandHandedness.RIGHT))
        // But it is nobody's other hand: there is no second hand to zoom or open tools with.
        assertNull(input.otherThan(AepHandHandedness.LEFT))
        assertNull(input.otherThan(AepHandHandedness.RIGHT))
    }

    @Test
    fun `each hand pinches on its own`() {
        val reader = HandInterpreter()
        reader.read(frame(
            hand(AepHandHandedness.LEFT, 0.2, pinch = 0.9),
            hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.0)
        ), aspect, 0L)

        assertTrue(reader.isPinching(AepHandHandedness.LEFT))
        // The whole design rests on this: the off hand opens the tools while the pen stays open,
        // which a single shared pinch state could not express.
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT))
    }

    @Test
    fun `pinch has hysteresis, per hand`() {
        // Explicit thresholds: the defaults are provisional and will move once there are numbers
        // from a real television, and a test of the mechanism should not break when they do.
        val reader = HandInterpreter(pinchEnter = 0.6f, pinchExit = 0.35f)
        val right = { pinch: Double -> reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch)), aspect, 0L) }

        right(0.5)
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT), "below the threshold to start")
        right(0.7)
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT))
        // Staying on through the middle is the point: a single threshold on a noisy signal breaks
        // one stroke into several, which on a canvas is a wrong drawing rather than a glitch.
        right(0.5)
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT))
        right(0.2)
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT))
    }

    @Test
    fun `a blink does not let go of the pen`() {
        val reader = HandInterpreter()
        reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)), aspect, 0L)
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT))

        // The provider lost the hand entirely in thirteen percent of frames on a real television,
        // and every one of those ended the stroke. One frame of absence is the tracker blinking.
        val lost = reader.read(AepHandFrame(trackingState = AepTrackingState.LOST, hands = emptyList()), aspect, 95L)
        assertFalse(lost.tracked, "the frame really is empty, and the view should say so")
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT), "the pen is still down")
        assertTrue(reader.isBridging)
    }

    @Test
    fun `but a hand that has gone is gone`() {
        // The grace is stated here rather than taken from the default, which moves as sessions on
        // real hardware say more about how long the tracker actually goes blind for.
        val reader = HandInterpreter(dropoutGraceMs = 400L)
        reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)), aspect, 0L)

        reader.read(AepHandFrame(trackingState = AepTrackingState.LOST, hands = emptyList()), aspect, 200L)
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT), "still inside the grace")
        // Past it, this is a person who has put their arm down, and the stroke must end.
        reader.read(AepHandFrame(trackingState = AepTrackingState.LOST, hands = emptyList()), aspect, 900L)
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT))
        assertFalse(reader.isBridging)
    }

    @Test
    fun `an open hand that is actually seen is believed at once`() {
        val reader = HandInterpreter()
        reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)), aspect, 0L)
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT))

        // The grace is for absence, not for disagreement. A hand in plain view with its fingers
        // open has let go, and waiting 300ms to notice would make the pen feel sticky.
        reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.0)), aspect, 20L)
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT))
        assertFalse(reader.isBridging)
    }

    @Test
    fun `the pen is held even while no hand is visible`() {
        val reader = HandInterpreter()
        reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)), aspect, 0L)
        val blink = reader.read(AepHandFrame(trackingState = AepTrackingState.LOST, hands = emptyList()), aspect, 90L)

        assertTrue(reader.isPenHeld, "the pen is down")
        assertNull(reader.penHand(blink), "and there is nowhere to draw, which is a separate fact")
    }

    @Test
    fun `the canvas stretches to the reach that was actually seen`() {
        val reader = HandInterpreter()
        // A hand covering the range a real one covered on a television, a few times over.
        repeat(40) {
            reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.06)), aspect, 0L)
            reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.60)), aspect, 0L)
        }
        // Only the vertical was exercised, and only the vertical needs to be known for this.
        assertTrue(reader.reach.isLearnedY)

        val low = reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.60)), aspect, 0L)
        assertEquals(aspect, low.right!!.y, 0.03f, "the lowest the hand went must be the canvas bottom")
        val high = reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.06)), aspect, 0L)
        assertEquals(0f, high.right!!.y, 0.03f)
    }

    @Test
    fun `the reach does not move while a stroke is being drawn`() {
        val reader = HandInterpreter()
        repeat(40) {
            reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.20)), aspect, 0L)
            reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.50)), aspect, 0L)
        }
        val span = reader.reach.spanY

        // Mid-stroke, the hand goes somewhere new. Growing the envelope now would rescale the
        // canvas under the line being drawn and slide it sideways.
        repeat(20) {
            reader.read(frame(hand(AepHandHandedness.RIGHT, 0.5, y = 0.90)), aspect, 0L, learningReach = false)
        }
        assertEquals(span, reader.reach.spanY, 1e-4f)
    }

    @Test
    fun `a hand that can be seen pinching keeps the pen from one that cannot`() {
        val reader = HandInterpreter()
        // Both hands pinching, both visible.
        reader.read(frame(
            hand(AepHandHandedness.LEFT, 0.2, pinch = 0.9),
            hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)
        ), aspect, 0L)
        assertNull(reader.penHand(reader.read(frame(
            hand(AepHandHandedness.LEFT, 0.2, pinch = 0.9),
            hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)
        ), aspect, 10L)), "two hands pinching is a zoom, not a pen")

        // Now the right hand vanishes while still latched. Its bridged latch used to count as a
        // second pinch, so "exactly one hand is pinching" went false and the pen was taken away
        // from the left hand that was visibly drawing with it.
        val blink = reader.read(frame(hand(AepHandHandedness.LEFT, 0.2, pinch = 0.9)), aspect, 100L)
        assertTrue(reader.isPenHeld, "the visible pinching hand still has the pen")
        assertNotNull(reader.penHand(blink))
        assertEquals(AepHandHandedness.LEFT, reader.penHand(blink)!!.handedness)
    }

    @Test
    fun `a hand the provider is unsure of is not a hand`() {
        val reader = HandInterpreter()
        val input = reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, confidence = 0.1)), aspect, 0L)
        assertFalse(input.tracked, "a low-confidence hand would draw where nobody is pointing")
    }
}
