package com.ambokit.aep.airdraw

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stroke pipeline is the product claim, so it is the thing with tests.
 *
 * AirDraw exists because an arm beats a mouse for freehand drawing, and the only thing that could
 * falsify that is what 15 Hz does to a line. These assert the properties the simulation
 * established before any of it was written: the curve passes through the samples, it takes the
 * polygon out, and it does not wander off the path the hand actually took.
 */
class StrokePipelineTest {

    /** A hand moving the way a hand moves: curving, and not at a constant speed. */
    private fun handPath(samples: Int, jitter: Float = 0f): List<AirDrawPoint> {
        var seed = 12345L
        fun noise(): Float {
            // A deterministic wobble. A test that fails one run in twenty teaches people to
            // re-run it rather than read it.
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33).toFloat() / Int.MAX_VALUE.toFloat() - 0.5f) * 2f * jitter
        }
        return (0 until samples).map { i ->
            val t = i.toFloat() / samples
            AirDrawPoint(
                x = 0.5f + 0.3f * kotlin.math.sin(t * 6.283f * 2f) + noise(),
                y = 0.28f + 0.16f * kotlin.math.cos(t * 6.283f * 3f) + noise(),
                timeMs = (i * 1000L / 15L)
            )
        }
    }

    @Test
    fun `the curve passes through every sample`() {
        val samples = handPath(12)
        val curved = StrokePipeline.curve(samples)
        // Every original sample from the second to the second-to-last is a control point the
        // curve is required to hit. A curve that only approximates the samples feels, to someone
        // drawing, like the pen is arguing with them.
        for (i in 1 until samples.size - 2) {
            val sample = samples[i]
            val nearest = curved.minOf { hypot(it.x - sample.x, it.y - sample.y) }
            assertTrue(nearest < 1e-3f, "sample $i is ${nearest} from the curve")
        }
    }

    @Test
    fun `the curve removes the polygon`() {
        val samples = handPath(30)
        val rawTurn = meanTurnDegrees(samples)
        val curvedTurn = meanTurnDegrees(StrokePipeline.curve(samples))
        assertTrue(curvedTurn < rawTurn / 2f,
            "expected the curve to more than halve the mean turn; raw $rawTurn, curved $curvedTurn")
    }

    @Test
    fun `the curve does not overshoot between samples`() {
        val samples = handPath(30)
        val curved = StrokePipeline.curve(samples)

        // Measured against how far apart the samples actually are, not against a fixed number.
        // Distance-to-nearest-sample has a floor of half the sample spacing no matter how good
        // the curve is, so an absolute threshold tests sample density rather than curve quality -
        // which is what the first version of this test did, and it failed for that reason.
        var widestGap = 0f
        for (i in 1 until samples.size) {
            widestGap = maxOf(widestGap, hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y))
        }

        for (point in curved) {
            val nearest = samples.minOf { hypot(it.x - point.x, it.y - point.y) }
            assertTrue(nearest <= widestGap,
                "curve reached $nearest from every sample, further than the ${widestGap} between them")
        }
    }

    @Test
    fun `too few samples pass through untouched`() {
        val samples = handPath(3)
        assertEquals(samples, StrokePipeline.curve(samples))
    }

    @Test
    fun `touch width follows pressure`() {
        val points = listOf(
            AirDrawPoint(0.1f, 0.1f, pressure = 0.1f),
            AirDrawPoint(0.2f, 0.1f, pressure = 0.5f),
            AirDrawPoint(0.3f, 0.1f, pressure = 1.0f)
        )
        val widths = StrokePipeline.widths(points, baseWidth = 10f, source = StrokeSource.TOUCH)
        assertTrue(widths[0] < widths[2], "heavier pressure must draw a wider line")
        assertTrue(widths.all { it > 0f }, "no pressure may make the pen invisible")
    }

    @Test
    fun `air width follows speed, and keeps the stroke's weight`() {
        // Same shape, drawn at varying speed: closely spaced points are slow, distant ones fast.
        val points = listOf(
            AirDrawPoint(0.10f, 0.1f), AirDrawPoint(0.11f, 0.1f), AirDrawPoint(0.12f, 0.1f),
            AirDrawPoint(0.30f, 0.1f), AirDrawPoint(0.50f, 0.1f), AirDrawPoint(0.70f, 0.1f)
        )
        val widths = StrokePipeline.widths(points, baseWidth = 10f, source = StrokeSource.AIR)
        assertTrue(widths[1] > widths[4], "a slow stroke must be thicker than a fast one")

        val mean = widths.average().toFloat()
        // Varying the width must change the stroke's character, not its weight. Without this the
        // whole line simply gets thinner, which reads as worse rather than as brushlike.
        assertTrue(abs(mean - 10f) < 3f, "mean width drifted to $mean from a base of 10")
    }

    @Test
    fun `smoothing is off until the jitter justifies it`() {
        // Simulation put the crossover near 16 px on a 1920-wide screen: below it the curve alone
        // wins and a filter only adds lag.
        assertFalse(StrokeSmoothing.forJitter(4f).enabled)
        assertFalse(StrokeSmoothing.forJitter(15f).enabled)
        assertTrue(StrokeSmoothing.forJitter(20f).enabled)
        assertTrue(StrokeSmoothing.forJitter(60f).enabled)
    }

    @Test
    fun `the filter tracks a moving signal instead of lagging forever`() {
        val filter = OneEuroFilter(minCutoff = 6f, beta = 1.5f)
        var out = 0f
        for (i in 0 until 40) out = filter.filter(i * 0.01f, i * 66L)
        // After forty samples of steady motion the filter should be close behind, not stuck.
        assertTrue(abs(out - 0.39f) < 0.05f, "filter output $out is not tracking the input")
    }

    private fun meanTurnDegrees(points: List<AirDrawPoint>): Float {
        if (points.size < 3) return 0f
        var total = 0f
        var count = 0
        for (i in 1 until points.size - 1) {
            val ax = points[i].x - points[i - 1].x
            val ay = points[i].y - points[i - 1].y
            val bx = points[i + 1].x - points[i].x
            val by = points[i + 1].y - points[i].y
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            if (la < 1e-6f || lb < 1e-6f) continue
            val dot = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
            total += acos(dot) * 57.2958f
            count++
        }
        return if (count == 0) 0f else total / count
    }
}
