package com.ambokit.aep.airdraw

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turning fifteen samples a second into a line worth drawing.
 *
 * This is the whole product claim. An arm is a better drawing instrument than a mouse, but the
 * signal is far worse: AmboKit's certification measured pose at 14.8-18.8 Hz, thermally bound,
 * against a mouse's 60-125. A hand crossing a 1.1 m TV at normal drawing speed moves about 6 cm
 * between samples. Join those with straight lines and the result is a visible polygon - worse
 * than the mouse it is meant to beat.
 *
 * Simulated offline before any of this was written (the workings are in the platform repo's
 * notes). What that showed, and what this implements:
 *
 *  - The curve does nearly all the work. Catmull-Rom through every sample takes the mean turn
 *    between segments from 22.8 degrees to 6.3, and slightly IMPROVES accuracy, because a curve
 *    through noisy points averages the noise.
 *  - The One Euro filter - the textbook answer for hand tracking - earns nothing at the jitter
 *    level body pose actually has, and costs up to 25 px of lag. It starts paying above roughly
 *    16 px of jitter. So it is here, and it defaults to off.
 *  - Width that varies with speed or pressure is what makes the eye read a drawn line rather
 *    than a reconstructed one.
 *
 * Hand landmarks at three metres may be noisier than the body pose those numbers came from.
 * [StrokeSmoothing.forJitter] is how that gets decided on a real TV rather than by argument.
 */
object StrokePipeline {

    /** Evaluated per display frame rather than per sample: that is where the continuity comes from. */
    const val POINTS_PER_SEGMENT = 4

    /**
     * Catmull-Rom through every point.
     *
     * Through, not near. A person drawing expects the line to arrive where their hand was; a
     * curve that merely approximates the samples feels like the pen is being argued with.
     */
    fun curve(points: List<AirDrawPoint>, perSegment: Int = POINTS_PER_SEGMENT): List<AirDrawPoint> {
        if (points.size < 4) return points
        val out = ArrayList<AirDrawPoint>((points.size - 3) * perSegment + 1)
        for (i in 0 until points.size - 3) {
            val p0 = points[i]; val p1 = points[i + 1]; val p2 = points[i + 2]; val p3 = points[i + 3]
            for (step in 0 until perSegment) {
                val t = step.toFloat() / perSegment
                val t2 = t * t
                val t3 = t2 * t
                out += AirDrawPoint(
                    x = 0.5f * ((2 * p1.x) + (-p0.x + p2.x) * t +
                        (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                        (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
                    y = 0.5f * ((2 * p1.y) + (-p0.y + p2.y) * t +
                        (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                        (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3),
                    // Pressure is interpolated linearly. It changes far more slowly than position
                    // and a curve through it buys nothing.
                    pressure = p1.pressure + (p2.pressure - p1.pressure) * t,
                    timeMs = p1.timeMs + ((p2.timeMs - p1.timeMs) * t).toLong()
                )
            }
        }
        out += points[points.size - 2]
        out += points[points.size - 1]
        return out
    }

    /**
     * Width for each point: pressure when the phone is drawing, speed when the arm is.
     *
     * Fast strokes thin, slow strokes thick, which is what a brush does — and what makes the
     * residual straightness between samples stop reading as straightness.
     */
    fun widths(
        points: List<AirDrawPoint>,
        baseWidth: Float,
        source: StrokeSource,
        brush: BrushType = BrushType.MARKER
    ): FloatArray {
        val widths = FloatArray(points.size)
        if (points.isEmpty()) return widths

        // A pen is a pen whatever it is held in: constant width, no speed term, no pressure term.
        // Returned before the source split because that split exists to decide how to vary width,
        // and this brush is the one that does not.
        if (brush == BrushType.PEN) {
            widths.fill(baseWidth)
            return widths
        }

        if (source == StrokeSource.TOUCH) {
            for (i in points.indices) {
                // A digitiser reports 1.0 for an ordinary press and can exceed it. Half width at
                // the lightest touch, half again at the heaviest, so pressure is expressive
                // without ever becoming invisible.
                widths[i] = baseWidth * (0.5f + 0.75f * min(points[i].pressure, 1.5f))
            }
            return smooth(widths)
        }

        var meanStep = 0f
        for (i in 1 until points.size) {
            meanStep += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        meanStep = if (points.size > 1) meanStep / (points.size - 1) else 0f
        if (meanStep <= 0f) {
            widths.fill(baseWidth)
            return widths
        }
        // A crayon is the marker's speed response, pushed. The floor drops and the ceiling rises
        // so the same gesture produces a wider spread of widths; the shape of the curve is
        // unchanged, because that shape is what makes the marker feel like a pen at all.
        val floor = if (brush == BrushType.CRAYON) 0.20f else 0.35f
        val ceiling = if (brush == BrushType.CRAYON) 2.4f else 1.6f
        val response = if (brush == BrushType.CRAYON) 1.6f else 0.9f
        for (i in points.indices) {
            val step = if (i == 0) meanStep
            else hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            val speed = step / meanStep
            widths[i] = baseWidth * max(floor, min(ceiling, 1f / (1f + response * speed)))
        }
        // Normalised so varying the width changes the stroke's character rather than its weight:
        // without this the whole line simply gets thinner, which reads as worse.
        var mean = 0f
        for (w in widths) mean += w
        mean /= widths.size
        if (mean > 0f) for (i in widths.indices) widths[i] = widths[i] * baseWidth / mean
        return smooth(widths)
    }

    /** Width steps visibly wherever the speed estimate jumps, so the width is smoothed too. */
    private fun smooth(widths: FloatArray, window: Int = 5): FloatArray {
        if (widths.size <= window) return widths
        val out = FloatArray(widths.size)
        val half = window / 2
        for (i in widths.indices) {
            var sum = 0f
            var count = 0
            for (j in max(0, i - half)..min(widths.size - 1, i + half)) {
                sum += widths[j]; count++
            }
            out[i] = sum / count
        }
        return out
    }
}

/**
 * How much to smooth incoming samples, and why that is a setting rather than a constant.
 *
 * Simulation put the crossover at roughly 16 px of jitter on a 1920-wide TV: below it the curve
 * alone wins and filtering only adds lag; above it the filter starts paying for itself. Body pose
 * sits well below. Hand landmarks at three metres are unmeasured, so the app measures them and
 * this picks a setting from the measurement.
 */
data class StrokeSmoothing(val minCutoff: Float, val beta: Float, val enabled: Boolean) {
    companion object {
        /** The default: no filtering, which simulation says is right for body-pose jitter. */
        val NONE = StrokeSmoothing(minCutoff = 0f, beta = 0f, enabled = false)

        /**
         * A setting chosen from measured jitter, in pixels of a 1920-wide screen.
         *
         * Deliberately three bands rather than a formula. The underlying relationship is not
         * known well enough for a formula to be more than false precision, and bands are
         * something a person can reason about when they read a log line.
         */
        fun forJitter(pixels: Float): StrokeSmoothing = when {
            pixels < 16f -> NONE
            pixels < 40f -> StrokeSmoothing(minCutoff = 6f, beta = 1.5f, enabled = true)
            else -> StrokeSmoothing(minCutoff = 2f, beta = 1.0f, enabled = true)
        }
    }
}

/**
 * A low-pass filter whose cutoff rises with speed: heavy smoothing at rest, light in motion.
 *
 * One instance per axis. Standard One Euro, roughly thirty lines and no dependencies.
 */
class OneEuroFilter(private val minCutoff: Float, private val beta: Float) {
    private var previous = 0f
    private var previousDerivative = 0f
    private var previousTimeMs = -1L

    fun reset() {
        previousTimeMs = -1L
        previousDerivative = 0f
    }

    fun filter(value: Float, timeMs: Long): Float {
        if (previousTimeMs < 0) {
            previousTimeMs = timeMs
            previous = value
            return value
        }
        val dt = max((timeMs - previousTimeMs).toFloat() / 1000f, 1e-4f)
        previousTimeMs = timeMs

        var derivative = (value - previous) / dt
        val alphaD = alpha(DERIVATIVE_CUTOFF, dt)
        derivative = alphaD * derivative + (1 - alphaD) * previousDerivative
        previousDerivative = derivative

        val cutoff = minCutoff + beta * abs(derivative)
        val a = alpha(cutoff, dt)
        val filtered = a * value + (1 - a) * previous
        previous = filtered
        return filtered
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI * cutoff)
        return 1f / (1f + tau / dt)
    }

    private companion object {
        const val PI = 3.1415927f
        const val DERIVATIVE_CUTOFF = 1f
    }
}
