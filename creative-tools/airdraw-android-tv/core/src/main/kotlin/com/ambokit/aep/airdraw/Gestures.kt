package com.ambokit.aep.airdraw

import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandLandmark
import kotlin.math.hypot

/**
 * What a hand is doing, as a shape rather than a number.
 *
 * AirDraw asks the hand stream for discrete state and the pose stream for continuous position.
 * This is the discrete half: three shapes a person can hold, plus the honest fourth answer.
 */
enum class HandShape {
    /** Thumb and index together, the other three fingers out. The master toggle. */
    PINCH,

    /** Four fingers extended. Draw mode. */
    OPEN_PALM,

    /** Everything curled. Pan mode. */
    FIST,

    /**
     * Not one of the three, or not enough landmarks to say.
     *
     * A real answer, not a failure. A hand rotating between shapes passes through here, and a
     * hand pointed at the camera lives here - every landmark is foreshortened onto nearly the
     * same spot and no measurement below means anything. Guessing would be worse than declining.
     */
    UNKNOWN
}

/**
 * Reads [HandShape] out of the 21 landmarks in `camera.hand@1`.
 *
 * **Why not `pinchStrength`.** The provider hands us a pinch number, and using it would have been
 * one line. It cannot work here: `pinchStrength` measures thumb-to-index proximity, and in a
 * closed fist the thumb lies across the index finger. A fist and a pinch produce the same number.
 * Since AirDraw uses pinch as its master draw toggle and the fist to pan, that collision would
 * fire the toggle every single time somebody went to move the canvas - the two controls a person
 * uses most, wired to each other.
 *
 * So the separation is geometric: a pinch has the other three fingers *out*, a fist does not.
 * That distinction is not available in a scalar, only in the landmarks.
 *
 * **Everything is measured as a ratio of the hand's own span.** A hand two metres from the camera
 * covers a fraction of the frame that the same hand covers at one metre, so any absolute
 * threshold is really a distance-from-TV threshold wearing a disguise. Dividing by
 * wrist-to-middle-knuckle makes the measurements mean the same thing across the room.
 *
 * **Landmarks are looked up by name, never by index.** `AepHand.landmarks` is
 * `List<AepHandLandmark?>` - nullable entries, and no guarantee in the schema that position in
 * the list implies identity. Indexing would work right up until a provider sent a short list or
 * reordered one, and would then silently measure the wrong finger rather than fail.
 */
class HandShapeReader(
    /**
     * Thumb-index gap, as a fraction of hand span, at or below which a pinch closes.
     *
     * Hysteresis, for the same reason [HandInterpreter] uses it on `pinchStrength`: a single
     * threshold on a noisy signal chatters, and a chattering master toggle turns drawing on and
     * off under the player's hand.
     *
     * These two numbers are **not measured** - unlike the 0.50/0.22 pair in [HandInterpreter],
     * which came off a logged television session. They are geometry-derived starting points and
     * are the first thing to re-fit from a device log.
     */
    private val pinchEnter: Float = 0.35f,
    private val pinchExit: Float = 0.55f,
    /** Below this a landmark is treated as absent rather than as a position. */
    private val minimumConfidence: Float = 0.4f,
    /**
     * How many of index/middle/ring/pinky must read as extended for an open palm.
     *
     * Three of four, not four of four. One finger dipping below the confidence floor for a frame
     * is routine, and demanding all four would drop the shape to UNKNOWN mid-stroke - which in a
     * drawing app is a broken line, the exact failure [HandInterpreter]'s dropout grace exists to
     * prevent. The same tolerance in reverse would make a fist too easy to claim, so a fist
     * requires that *none* are extended.
     */
    private val palmFingersRequired: Int = 3,
    /**
     * How many of the four may still read as extended and the hand still be a fist.
     *
     * One, not zero. Demanding all four be curled was the mirror image of the palm's three-of-four
     * and it was far stricter - at three metres a single noisy fingertip pushed a real fist into
     * UNKNOWN. Measured on a television: `FIST` appeared in 2 of 84 diagnostic samples, so zoom,
     * which needs *both* hands reading as fists at once, essentially never fired.
     *
     * Safe to be generous here only because the pinch test runs first and requires two or more
     * fingers out, which a fist cannot have.
     */
    private val fistFingersAllowed: Int = 1
) {
    /** True while the pinch latch is closed, so the caller can see the hysteresis state. */
    var pinching: Boolean = false
        private set

    /** The last gap measured, as a fraction of hand span. Null where it could not be read. */
    var lastGap: Float? = null
        private set

    /** How many of the four fingers last read as extended. Null where it could not be read. */
    var lastExtended: Int? = null
        private set

    fun reset() {
        pinching = false
        lastGap = null
        lastExtended = null
    }

    fun read(hand: AepHand?): HandShape {
        if (hand == null) {
            reset()
            return HandShape.UNKNOWN
        }
        val by = index(hand)
        val wrist = by["wrist"]
        val knuckle = by["middle_finger_mcp"]
        if (wrist == null || knuckle == null) {
            // Without the span there is no scale, and without scale no threshold below is
            // meaningful. Hold the latch rather than clearing it: a missing frame is not a
            // decision to let go, which is the lesson the dropout grace already encodes.
            lastGap = null
            lastExtended = null
            return HandShape.UNKNOWN
        }

        val span = distance(wrist, knuckle)
        if (span < MINIMUM_SPAN) {
            // The hand is edge-on or pointed at the camera: every landmark projects onto nearly
            // the same point, so ratios explode and shapes become whatever noise says.
            lastGap = null
            lastExtended = null
            return HandShape.UNKNOWN
        }

        val extended = countExtended(by, wrist)
        lastExtended = extended

        val thumb = by["thumb_tip"]
        val indexTip = by["index_finger_tip"]
        val gap = if (thumb != null && indexTip != null) distance(thumb, indexTip) / span else null
        lastGap = gap

        // The gap only means "pinch" while the other fingers are out.
        //
        // Measured on a television: a closed fist reads a thumb-index gap of 0.24 to 0.33, which
        // is inside the 0.35 that closes this latch. The shape rule below correctly refused to
        // call that a pinch - no fingers are extended - but the *latch* had already closed, so
        // the moment the hand opened to draw it had four fingers out and a held pinch, read as
        // PINCH, and toggled drawing off under the person. Separating the shapes was not enough;
        // the state they share had to be separated too.
        if (gap != null && extended >= PINCH_FINGERS_REQUIRED) {
            pinching = if (pinching) gap <= pinchExit else gap <= pinchEnter
        } else if (extended <= fistFingersAllowed) {
            pinching = false
        }

        // A fist brings thumb and index close too, so both shapes look pinched to the gap
        // measurement. Two separate things keep them apart, and it is worth knowing which is
        // doing the work:
        //
        //  - the thresholds, while [fistFingersAllowed] stays below the two extended fingers a
        //    pinch requires: no hand can satisfy both rules, so the order here is irrelevant;
        //  - this ordering, if that allowance is ever raised to meet the pinch minimum: then a
        //    pinch *would* satisfy the fist rule, and only being tested first saves it.
        //
        // Spelled out because the first version of this comment claimed the ordering alone was
        // load-bearing. Swapping the branches to check left every test passing - at the default
        // allowance the two are genuinely equivalent. Both tests are below.
        return when {
            pinching && extended >= PINCH_FINGERS_REQUIRED -> HandShape.PINCH
            extended <= fistFingersAllowed -> HandShape.FIST
            extended >= palmFingersRequired -> HandShape.OPEN_PALM
            else -> HandShape.UNKNOWN
        }
    }

    private fun index(hand: AepHand): Map<String, AepHandLandmark> {
        val out = HashMap<String, AepHandLandmark>(24)
        for (landmark in hand.landmarks) {
            if (landmark == null) continue
            if (landmark.confidence < minimumConfidence) continue
            out[landmark.name] = landmark
        }
        return out
    }

    /**
     * How many of index/middle/ring/pinky are out.
     *
     * A finger is extended when its tip is further from the wrist than its middle joint is.
     * Curling moves the tip back towards the palm, so this flips cleanly and needs no angle
     * arithmetic or per-hand tuning.
     *
     * The thumb is deliberately excluded: it extends sideways rather than away from the wrist,
     * so the same test reads it as extended in a closed fist. Including it would have made a
     * fist look like a one-finger shape and landed it in UNKNOWN.
     */
    private fun countExtended(by: Map<String, AepHandLandmark>, wrist: AepHandLandmark): Int {
        var count = 0
        for ((tip, pip) in FINGERS) {
            val t = by[tip] ?: continue
            val p = by[pip] ?: continue
            if (distance(wrist, t) > distance(wrist, p)) count++
        }
        return count
    }

    private fun distance(a: AepHandLandmark, b: AepHandLandmark): Float =
        hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())

    private companion object {
        /** How many fingers must be out for a closed thumb-index gap to count as a pinch. */
        const val PINCH_FINGERS_REQUIRED = 2

        val FINGERS = listOf(
            "index_finger_tip" to "index_finger_pip",
            "middle_finger_tip" to "middle_finger_pip",
            "ring_finger_tip" to "ring_finger_pip",
            "pinky_tip" to "pinky_pip"
        )

        /**
         * Smallest wrist-to-knuckle span, in frame units, that still carries usable geometry.
         *
         * Below this the hand is either very far away or pointing at the lens, and dividing by it
         * turns small landmark errors into large ratios.
         */
        const val MINIMUM_SPAN = 0.01f
    }
}

/**
 * The master draw latch: pinch to turn drawing on, pinch again to turn it off.
 *
 * Separate from [HandShapeReader] because that class answers "what shape is the hand now" and
 * this one answers "what has the person asked for", which is a different question with its own
 * state. A pinch *held* must toggle once, not sixty times a second, so the transition is what
 * counts and the shape is only its input.
 */
class DrawLatch {
    var on: Boolean = false
        private set

    private var wasPinching = false

    /**
     * @return true when this call flipped the latch, so the caller can react once per intent.
     */
    fun update(shape: HandShape): Boolean {
        val isPinching = shape == HandShape.PINCH
        // Rising edge only. UNKNOWN is not a release: a hand passing out of readability while
        // pinched has not un-pinched, and treating it as one would toggle drawing off every time
        // tracking blinked.
        val flipped = isPinching && !wasPinching
        if (flipped) on = !on
        if (shape == HandShape.OPEN_PALM || shape == HandShape.FIST) wasPinching = false
        else if (isPinching) wasPinching = true
        return flipped
    }

    fun reset() {
        on = false
        wasPinching = false
    }
}
