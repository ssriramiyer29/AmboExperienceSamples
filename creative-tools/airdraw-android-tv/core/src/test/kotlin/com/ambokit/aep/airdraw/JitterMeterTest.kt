package com.ambokit.aep.airdraw

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitterMeterTest {

    @Test
    fun `says nothing until it has a full window`() {
        // A window of N *steps* needs N+1 samples: the first sample has nothing to be a step from.
        val m = JitterMeter(window = 10)
        repeat(10) { assertNull(m.observe(0.5f + it * 0.0001f, 0.5f)) }
        assertNull(m.pixels, "an estimate from a partial window is noise reported as measurement")
        assertNotNull(m.observe(0.5f, 0.5f), "the eleventh sample completes the tenth step")
        assertNotNull(m.pixels)
    }

    @Test
    fun `a perfectly still pointer measures no jitter`() {
        val m = JitterMeter(window = 20)
        var last: Float? = null
        repeat(21) { last = m.observe(0.5f, 0.5f) ?: last }
        assertEquals(0f, last)
    }

    @Test
    fun `a noisy pointer measures more jitter than a clean one`() {
        fun measure(amplitude: Float): Float {
            val m = JitterMeter(window = 200)
            var out: Float? = null
            var seed = 12345L
            repeat(201) {
                // A cheap deterministic wobble; the absolute value does not matter, only that one
                // signal is noisier than the other.
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val n = ((seed shr 33).toFloat() / Int.MAX_VALUE.toFloat())
                out = m.observe(0.5f + n * amplitude, 0.5f + n * amplitude) ?: out
            }
            return out!!
        }
        assertTrue(measure(0.02f) > measure(0.001f))
    }

    /**
     * The failure this class is shaped to avoid: a person drawing a deliberate sweep is not a
     * noisy sensor, and averaging their stroke in would switch on the heaviest filter available -
     * which is exactly the filter that makes a drawing tool feel like it is arguing with you.
     */
    @Test
    fun `a deliberate stroke is not read as jitter`() {
        val steady = JitterMeter(window = 120)
        val drawing = JitterMeter(window = 120)
        var steadyOut: Float? = null
        var drawingOut: Float? = null
        repeat(121) { i ->
            // Both have the same tiny tremor; one is also crossing the screen.
            val tremor = sin(i * 2.3f) * 0.0008f
            steadyOut = steady.observe(0.5f + tremor, 0.5f + tremor) ?: steadyOut
            drawingOut = drawing.observe(0.1f + i * 0.006f + tremor, 0.5f + tremor) ?: drawingOut
        }
        assertNotNull(steadyOut)
        assertNotNull(drawingOut)
        // The percentile keeps the moving pointer's estimate from running away, so both land in
        // the same band rather than the drawing one demanding heavy filtering.
        assertEquals(
            StrokeSmoothing.forJitter(steadyOut).enabled,
            StrokeSmoothing.forJitter(drawingOut).enabled,
            "steady=$steadyOut drawing=$drawingOut chose different filters"
        )
    }

    @Test
    fun `a gap in the pointer is not measured as movement`() {
        val m = JitterMeter(window = 4)
        m.observe(0.2f, 0.2f)
        m.interrupted()
        // Reappearing across the screen is two positions an unknown time apart, not a 0.6 step.
        // The reappearance itself is a sample with nothing before it, so five are needed for
        // four steps.
        repeat(5) { m.observe(0.8f, 0.8f) }
        val estimate = m.pixels
        assertNotNull(estimate)
        assertEquals(0f, estimate, "the jump across the screen must not be in the sample")
    }

    @Test
    fun `a non finite sample is ignored`() {
        val m = JitterMeter(window = 3)
        assertNull(m.observe(Float.NaN, 0.5f))
        assertNull(m.observe(0.5f, Float.POSITIVE_INFINITY))
        assertNull(m.pixels)
    }

    @Test
    fun `resetting forgets the measurement`() {
        val m = JitterMeter(window = 3)
        repeat(4) { m.observe(0.5f, 0.5f) }
        assertNotNull(m.pixels)
        m.reset()
        assertNull(m.pixels)
    }

    @Test
    fun `the estimate feeds the filter bands`() {
        // The whole reason this class exists: a number in pixels that forJitter can act on.
        assertTrue(!StrokeSmoothing.forJitter(4f).enabled, "a clean signal needs no filter")
        assertTrue(StrokeSmoothing.forJitter(25f).enabled)
        assertTrue(StrokeSmoothing.forJitter(60f).enabled)
    }
}
