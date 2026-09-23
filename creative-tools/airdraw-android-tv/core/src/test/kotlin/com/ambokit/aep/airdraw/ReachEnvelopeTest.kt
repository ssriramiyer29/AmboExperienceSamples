package com.ambokit.aep.airdraw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Learning how far somebody can reach.
 *
 * The numbers in these tests are from a logged session on a television: across two minutes the
 * player's hand covered x 0.12–0.79 and y 0.06–0.60 of the camera frame. The fixed bounds in the
 * build at the time put the canvas bottom at 0.78, so a quarter of the drawing surface was past
 * the end of their arm.
 */
class ReachEnvelopeTest {

    /** A hand wandering over the range a real one covered, a few passes over it. */
    private fun ReachEnvelope.walkTheRealSession(passes: Int = 40) {
        repeat(passes) {
            observe(0.12f, 0.06f)
            observe(0.79f, 0.60f)
            observe(0.45f, 0.33f)
        }
    }

    @Test
    fun `before it has seen anything it still maps`() {
        val reach = ReachEnvelope()
        assertFalse(reach.isLearned)
        // A player must be able to draw from the first frame, not from the first frame after
        // some calibration has silently completed.
        assertTrue(reach.mapX(0.5f) in 0f..1f)
        assertTrue(reach.mapY(0.5f) in 0f..1f)
    }

    @Test
    fun `the reach a real person had covers the whole canvas`() {
        val reach = ReachEnvelope()
        reach.walkTheRealSession()
        assertTrue(reach.isLearned)

        // The two extremes the hand actually visited must be the two ends of the canvas. Under
        // the fixed bounds this build replaced, the low one landed at 74%.
        assertEquals(0f, reach.mapY(0.06f), 0.02f, "the highest the hand went must be the top")
        assertEquals(1f, reach.mapY(0.60f), 0.02f, "the lowest it went must be the bottom")
        assertEquals(0f, reach.mapX(0.12f), 0.02f)
        assertEquals(1f, reach.mapX(0.79f), 0.02f)
    }

    @Test
    fun `the middle of the reach is the middle of the canvas`() {
        val reach = ReachEnvelope()
        reach.walkTheRealSession()
        val middle = reach.mapY((0.06f + 0.60f) / 2f)
        assertTrue(middle in 0.45f..0.55f, "the centre of the reach landed at $middle")
    }

    @Test
    fun `a child's smaller reach also covers the whole canvas`() {
        val reach = ReachEnvelope()
        // Shorter arms, standing closer, covering half the frame of an adult.
        repeat(40) {
            reach.observe(0.35f, 0.25f)
            reach.observe(0.62f, 0.48f)
        }
        assertTrue(reach.isLearned)
        assertEquals(0f, reach.mapX(0.35f), 0.03f)
        assertEquals(1f, reach.mapX(0.62f), 0.03f)
        assertEquals(0f, reach.mapY(0.25f), 0.03f)
        assertEquals(1f, reach.mapY(0.48f), 0.03f)
    }

    @Test
    fun `one bad detection barely moves the edge`() {
        val reach = ReachEnvelope()
        reach.walkTheRealSession()
        val before = reach.spanY

        // A single frame that puts a hand somewhere it never was. Taking it at face value would
        // shrink every real gesture into the top half of the canvas for the rest of the session.
        reach.observe(0.45f, 0.99f)
        assertTrue(reach.spanY - before < 0.10f,
            "one outlier grew the envelope by ${reach.spanY - before}")
    }

    @Test
    fun `but a reach that is genuinely bigger is learned`() {
        val reach = ReachEnvelope()
        reach.walkTheRealSession()
        // Someone steps back, or stretches: the same new extreme, again and again.
        repeat(40) { reach.observe(0.45f, 0.85f) }
        assertEquals(1f, reach.mapY(0.85f), 0.03f, "a reach visited repeatedly must become the edge")
    }

    @Test
    fun `nothing maps outside the canvas`() {
        val reach = ReachEnvelope()
        reach.walkTheRealSession()
        for (raw in listOf(-0.5f, 0f, 0.5f, 1f, 1.5f)) {
            assertTrue(reach.mapX(raw) in 0f..1f, "mapX($raw) escaped")
            assertTrue(reach.mapY(raw) in 0f..1f, "mapY($raw) escaped")
        }
    }

    @Test
    fun `a hand that has barely moved is not a reach`() {
        val reach = ReachEnvelope()
        // Someone holding still while the app starts. Treating a few centimetres of tremor as
        // their full range would make the cursor fly across the screen.
        repeat(30) { reach.observe(0.50f, 0.40f) }
        repeat(30) { reach.observe(0.52f, 0.41f) }
        assertFalse(reach.isLearned)
    }

    @Test
    fun `a new session starts again`() {
        val reach = ReachEnvelope()
        reach.walkTheRealSession()
        assertTrue(reach.isLearned)
        // A different person sits down. What the last one could reach says nothing about them.
        reach.reset()
        assertFalse(reach.isLearned)
    }
}
