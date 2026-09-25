package com.ambokit.aep.airdraw

import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandHandedness
import com.ambokit.aep.core.capabilities.AepHandLandmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic hands, built from geometry rather than captured frames.
 *
 * The wrist sits at the origin and the knuckle one unit above it, so hand span is 1 and every
 * ratio in the reader is being fed numbers whose intended value is readable here. Fingers are
 * placed by how far past their own middle joint the tip sits, which is exactly what the reader
 * tests - so a test that passes says the rule holds, not that two implementations of the same
 * arithmetic agree.
 */
private fun hand(
    /** Distance from wrist to each fingertip, in span units. Under 1.5 reads as curled. */
    index: Float = 2f,
    middle: Float = 2f,
    ring: Float = 2f,
    pinky: Float = 2f,
    /** Gap between thumb tip and index tip, in span units. */
    thumbGap: Float = 1f,
    confidence: Float = 0.9f,
    /** Wrist-to-knuckle distance. 1 unless a test is probing the degenerate case. */
    span: Float = 1f,
    omit: Set<String> = emptySet()
): AepHand {
    val pipAt = 1.5f
    val marks = mutableListOf<AepHandLandmark?>()
    fun put(name: String, x: Float, y: Float) {
        if (name in omit) return
        marks += AepHandLandmark(name, x.toDouble(), y.toDouble(), confidence.toDouble())
    }
    put("wrist", 0f, 0f)
    put("middle_finger_mcp", 0f, span)
    // Each finger gets its own column so tips never coincide; only distance from the wrist,
    // which is what the reader measures, is varied per finger.
    val tips = listOf("index_finger" to index, "middle_finger" to middle,
                      "ring_finger" to ring, "pinky" to pinky)
    for ((i, entry) in tips.withIndex()) {
        val (stem, reach) = entry
        val x = (i - 1.5f) * 0.05f
        val prefix = if (stem == "pinky") "pinky" else stem
        put("${prefix}_pip", x, pipAt)
        put("${prefix}_tip", x, reach)
    }
    // Thumb placed on the x axis at the requested gap from the index tip.
    val indexTipX = (0 - 1.5f) * 0.05f
    put("thumb_tip", indexTipX + thumbGap, index)
    return AepHand(AepHandHandedness.RIGHT, confidence.toDouble(), marks)
}

class HandShapeReaderTest {

    // ---- the collision this class exists for -------------------------------------------------

    /**
     * The whole reason the reader does not use `pinchStrength`.
     *
     * A fist brings the thumb across the index finger, so thumb-index proximity - which is what
     * `pinchStrength` reports - is high in both shapes. If these two came back the same, the
     * master draw toggle would fire every time somebody closed a fist to pan the canvas.
     */
    @Test
    fun `a closed fist is not a pinch even though the thumb is touching the index`() {
        val reader = HandShapeReader()
        val fist = reader.read(hand(index = 1f, middle = 1f, ring = 1f, pinky = 1f, thumbGap = 0.1f))
        assertEquals(HandShape.FIST, fist)

        val pinch = HandShapeReader().read(hand(thumbGap = 0.1f))
        assertEquals(HandShape.PINCH, pinch)
        assertNotEquals(fist, pinch, "fist and pinch must not collapse to one shape")
    }

    @Test
    fun `the fist and the pinch present the same thumb gap`() {
        val fistReader = HandShapeReader()
        fistReader.read(hand(index = 1f, middle = 1f, ring = 1f, pinky = 1f, thumbGap = 0.1f))
        val pinchReader = HandShapeReader()
        pinchReader.read(hand(thumbGap = 0.1f))
        assertEquals(
            fistReader.lastGap, pinchReader.lastGap,
            "if the gaps differed the test would be proving something easier than the real case"
        )
    }

    // ---- the three shapes ---------------------------------------------------------------------

    @Test
    fun `four fingers out and thumb away is an open palm`() {
        assertEquals(HandShape.OPEN_PALM, HandShapeReader().read(hand(thumbGap = 1f)))
    }

    @Test
    fun `an open palm survives one finger dropping below confidence`() {
        val shape = HandShapeReader().read(hand(thumbGap = 1f, omit = setOf("pinky_tip")))
        assertEquals(
            HandShape.OPEN_PALM, shape,
            "three of four is the rule; a flicker on one finger must not break a stroke"
        )
    }

    @Test
    fun `a fist survives one finger reading as extended`() {
        // The failure this fixes: demanding all four curled was stricter than the palm's
        // three-of-four, and on a television one noisy fingertip put a real fist into UNKNOWN -
        // 2 fists in 84 samples, so two-fisted zoom never fired at all.
        val shape = HandShapeReader().read(hand(index = 2f, middle = 1f, ring = 1f, pinky = 1f,
                                                thumbGap = 0.1f))
        assertEquals(HandShape.FIST, shape)
    }

    @Test
    fun `a pinch is still not a fist, even with the fist rule relaxed`() {
        assertEquals(HandShape.PINCH, HandShapeReader().read(hand(thumbGap = 0.1f)))
    }

    /**
     * The pinch survives even a fist rule permissive enough to overlap it.
     *
     * Two things keep the shapes apart: the thresholds, while the fist allowance stays below the
     * two fingers a pinch needs; and the order of the branches, once it does not. This covers the
     * second. It exists because the source once claimed the ordering alone did the work -
     * reordering the branches to check left every test green, because at the default allowance
     * the two rules cannot both match anything.
     */
    /**
     * The failure this fixes, measured on a television: a closed fist reads a thumb-index gap of
     * 0.24 to 0.33, inside the 0.35 that closes the pinch latch. The shape rule refused to call
     * it a pinch, but the latch had closed anyway - so opening the hand afterwards gave four
     * fingers out and a held pinch, which read as PINCH and toggled drawing off.
     */
    @Test
    fun `a fist does not leave the pinch latch closed behind it`() {
        val reader = HandShapeReader(pinchEnter = 0.35f, pinchExit = 0.55f)
        // A fist, with the gap a real one produces.
        assertEquals(HandShape.FIST,
            reader.read(hand(index = 1f, middle = 1f, ring = 1f, pinky = 1f, thumbGap = 0.30f)))
        assertTrue(!reader.pinching, "the fist must not have closed the latch")

        // Opening the hand: gap 0.45 is past enter but short of exit, so a latch left closed
        // here would hold and report a pinch.
        assertEquals(HandShape.OPEN_PALM, reader.read(hand(thumbGap = 0.45f)))
    }

    @Test
    fun `a permissive fist rule still does not swallow a pinch`() {
        val pinchingHand = hand(thumbGap = 0.1f, ring = 1f, pinky = 1f)
        assertEquals(HandShape.PINCH, HandShapeReader().read(pinchingHand),
            "two fingers out and a closed gap is a pinch")
        assertEquals(HandShape.PINCH, HandShapeReader(fistFingersAllowed = 3).read(pinchingHand),
            "and it stays one even where the fist rule would also have matched")
    }

    @Test
    fun `two fingers out is neither a palm nor a fist`() {
        val shape = HandShapeReader().read(hand(ring = 1f, pinky = 1f, thumbGap = 1f))
        assertEquals(HandShape.UNKNOWN, shape)
    }

    // ---- hysteresis ---------------------------------------------------------------------------

    @Test
    fun `a pinch hovering at the threshold does not chatter`() {
        val reader = HandShapeReader(pinchEnter = 0.35f, pinchExit = 0.55f)
        assertEquals(HandShape.OPEN_PALM, reader.read(hand(thumbGap = 0.40f)),
            "0.40 is above the enter threshold, so no pinch yet")
        assertEquals(HandShape.PINCH, reader.read(hand(thumbGap = 0.30f)))
        assertEquals(HandShape.PINCH, reader.read(hand(thumbGap = 0.45f)),
            "0.45 is past enter but short of exit: the latch holds")
        assertEquals(HandShape.OPEN_PALM, reader.read(hand(thumbGap = 0.60f)),
            "past exit, the latch opens")
    }

    // ---- degrading honestly -------------------------------------------------------------------

    @Test
    fun `a hand with no wrist landmark is unknown rather than a guess`() {
        val reader = HandShapeReader()
        assertEquals(HandShape.UNKNOWN, reader.read(hand(omit = setOf("wrist"))))
        assertNull(reader.lastGap)
    }

    @Test
    fun `a hand pointed at the camera is unknown rather than whatever noise says`() {
        // Span collapses when the hand foreshortens onto the lens axis.
        assertEquals(HandShape.UNKNOWN, HandShapeReader().read(hand(span = 0.001f)))
    }

    @Test
    fun `low confidence landmarks are treated as absent`() {
        val reader = HandShapeReader(minimumConfidence = 0.5f)
        assertEquals(HandShape.UNKNOWN, reader.read(hand(confidence = 0.2f)))
    }

    @Test
    fun `a null hand is unknown and clears the latch`() {
        val reader = HandShapeReader()
        reader.read(hand(thumbGap = 0.1f))
        assertTrue(reader.pinching)
        assertEquals(HandShape.UNKNOWN, reader.read(null))
        assertTrue(!reader.pinching, "a hand that is gone is not a hand that is pinching")
    }

    @Test
    fun `landmarks are found by name not by position in the list`() {
        // Same hand, list reversed. Index-based lookup would read the pinky as the wrist.
        val forward = hand(thumbGap = 0.1f)
        val reversed = AepHand(forward.handedness, forward.confidence, forward.landmarks.reversed())
        assertEquals(HandShapeReader().read(forward), HandShapeReader().read(reversed))
    }
}

class DrawLatchTest {

    @Test
    fun `a held pinch toggles once, not once per frame`() {
        val latch = DrawLatch()
        assertTrue(latch.update(HandShape.PINCH), "first frame flips it")
        assertTrue(latch.on)
        repeat(60) { assertTrue(!latch.update(HandShape.PINCH), "a held pinch must not re-toggle") }
        assertTrue(latch.on)
    }

    @Test
    fun `pinching again turns it back off`() {
        val latch = DrawLatch()
        latch.update(HandShape.PINCH)
        latch.update(HandShape.OPEN_PALM)
        assertTrue(latch.update(HandShape.PINCH))
        assertTrue(!latch.on)
    }

    /**
     * The failure this guards: tracking blinks mid-stroke, the shape drops to UNKNOWN, and if
     * that counted as releasing the pinch then the next readable pinch frame would toggle
     * drawing off under the player's hand.
     */
    @Test
    fun `tracking dropping out is not a release`() {
        val latch = DrawLatch()
        latch.update(HandShape.PINCH)
        assertTrue(latch.on)
        repeat(5) { latch.update(HandShape.UNKNOWN) }
        assertTrue(!latch.update(HandShape.PINCH), "the pinch was never let go")
        assertTrue(latch.on, "drawing must still be on")
    }

    @Test
    fun `an open palm between pinches is a genuine release`() {
        val latch = DrawLatch()
        latch.update(HandShape.PINCH)
        latch.update(HandShape.UNKNOWN)
        latch.update(HandShape.OPEN_PALM)
        assertTrue(latch.update(HandShape.PINCH))
        assertTrue(!latch.on)
    }

    @Test
    fun `a fist does not toggle drawing`() {
        val latch = DrawLatch()
        latch.update(HandShape.PINCH)
        assertTrue(latch.on)
        repeat(10) { assertTrue(!latch.update(HandShape.FIST)) }
        assertTrue(latch.on, "panning must not turn drawing off")
    }
}
