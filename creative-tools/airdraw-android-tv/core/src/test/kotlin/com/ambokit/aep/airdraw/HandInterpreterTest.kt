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
        confidence: Double = 0.9
    ) = AepHand(
        handedness = handedness,
        confidence = confidence,
        landmarks = listOf(
            AepHandLandmark(name = "thumb_tip", x = x, y = 0.5, confidence = confidence),
            AepHandLandmark(name = "index_finger_tip", x = x, y = 0.5, confidence = confidence)
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
        ), aspect)

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
        ), aspect)

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
        val input = reader.read(frame(hand(AepHandHandedness.UNKNOWN, 0.5)), aspect)

        assertNull(input.left)
        assertNull(input.right)
        assertNotNull(input.unplaced, "guessing a side here would hand a left-hander an invisible pen")
        assertEquals(1, input.handCount)
    }

    @Test
    fun `an unplaced hand answers for whichever side is asked for`() {
        val reader = HandInterpreter()
        val input = reader.read(frame(hand(AepHandHandedness.UNKNOWN, 0.5)), aspect)

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
        ), aspect)

        assertTrue(reader.isPinching(AepHandHandedness.LEFT))
        // The whole design rests on this: the off hand opens the tools while the pen stays open,
        // which a single shared pinch state could not express.
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT))
    }

    @Test
    fun `pinch has hysteresis, per hand`() {
        val reader = HandInterpreter()
        val right = { pinch: Double -> reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch)), aspect) }

        right(0.5)
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT), "0.5 is below the threshold to start")
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
    fun `losing tracking lets go of everything`() {
        val reader = HandInterpreter()
        reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, pinch = 0.9)), aspect)
        assertTrue(reader.isPinching(AepHandHandedness.RIGHT))

        val lost = reader.read(AepHandFrame(trackingState = AepTrackingState.LOST, hands = emptyList()), aspect)
        assertFalse(lost.tracked)
        // A hand that has left the frame is not still pinching. Leaving the latch on would keep
        // a stroke open across the gap and join it to whatever is drawn next.
        assertFalse(reader.isPinching(AepHandHandedness.RIGHT))
        assertFalse(reader.isLonePinching)
    }

    @Test
    fun `a hand the provider is unsure of is not a hand`() {
        val reader = HandInterpreter()
        val input = reader.read(frame(hand(AepHandHandedness.RIGHT, 0.8, confidence = 0.1)), aspect)
        assertFalse(input.tracked, "a low-confidence hand would draw where nobody is pointing")
    }
}
