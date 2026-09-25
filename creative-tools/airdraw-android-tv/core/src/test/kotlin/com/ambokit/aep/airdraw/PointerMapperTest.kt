package com.ambokit.aep.airdraw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ASPECT = 9f / 16f

class PointerMapperTest {

    @Test
    fun `moving right in the room moves the cursor right on screen`() {
        val mapper = PointerMapper(mirrorX = true)
        // A camera facing a person sees their right hand on its left, so raw x runs backwards.
        val left = mapper.map(0.7f, 0.5f, ASPECT, learning = false)!!
        val right = mapper.map(0.3f, 0.5f, ASPECT, learning = false)!!
        assertTrue(right.first > left.first, "un-mirrored, a canvas is impossible to draw on")
    }

    @Test
    fun `y is scaled into document space`() {
        val mapper = PointerMapper()
        val (_, y) = mapper.map(0.5f, 0.9f, ASPECT, learning = false)!!
        assertTrue(y <= ASPECT + 1e-4f, "the canvas is 0..aspect tall, not 0..1")
    }

    @Test
    fun `the envelope only grows when it is allowed to`() {
        val mapper = PointerMapper()
        repeat(30) { mapper.map(0.2f, 0.2f, ASPECT, learning = false) }
        repeat(30) { mapper.map(0.8f, 0.8f, ASPECT, learning = false) }
        assertTrue(!mapper.reach.isLearned, "a stroke in progress must not move the mapping")

        repeat(30) { mapper.map(0.2f, 0.2f, ASPECT, learning = true) }
        repeat(30) { mapper.map(0.8f, 0.8f, ASPECT, learning = true) }
        assertTrue(mapper.reach.isLearned)
    }

    @Test
    fun `a non finite sample is refused rather than poisoning the envelope`() {
        val mapper = PointerMapper()
        assertNull(mapper.map(Float.NaN, 0.5f, ASPECT, learning = true))
        assertNull(mapper.map(0.5f, Float.POSITIVE_INFINITY, ASPECT, learning = true))
        assertTrue(!mapper.reach.isLearned)
    }

    @Test
    fun `a null pointer maps to nothing`() {
        assertNull(PointerMapper().map(null, ASPECT, learning = true))
    }

    // ---- the seeding flip ----------------------------------------------------------------------

    /**
     * The calibrator measures in camera coordinates; the envelope learns in mirrored ones. Getting
     * the flip wrong is invisible on a person standing centrally and obvious on one standing to
     * the side - their canvas would map to the opposite half of their reach.
     */
    @Test
    fun `a seeded envelope maps an off-centre reach to the whole canvas`() {
        val mapper = PointerMapper(mirrorX = true)
        // Somebody standing to one side: their reach in camera coordinates runs 0.15..0.55.
        val profile = ReachProfile(
            shoulderWidth = 0.12f, neutralCenterX = 0.35f, neutralCenterY = 0.45f,
            minX = 0.15f, maxX = 0.55f, minY = 0.20f, maxY = 0.70f
        )
        mapper.seedFrom(profile)

        val atLeftEdge = mapper.map(0.55f, 0.45f, ASPECT, learning = false)!!
        val atRightEdge = mapper.map(0.15f, 0.45f, ASPECT, learning = false)!!
        assertEquals(0f, atLeftEdge.first, absoluteTolerance = 0.06f)
        assertEquals(1f, atRightEdge.first, absoluteTolerance = 0.06f)
    }

    @Test
    fun `seeding without mirroring does not flip`() {
        val mapper = PointerMapper(mirrorX = false)
        val profile = ReachProfile(0.12f, 0.35f, 0.45f, 0.15f, 0.55f, 0.20f, 0.70f)
        mapper.seedFrom(profile)
        assertEquals(0f, mapper.map(0.15f, 0.45f, ASPECT, learning = false)!!.first, absoluteTolerance = 0.06f)
        assertEquals(1f, mapper.map(0.55f, 0.45f, ASPECT, learning = false)!!.first, absoluteTolerance = 0.06f)
    }

    @Test
    fun `seeding from nothing leaves the envelope cold`() {
        val mapper = PointerMapper()
        mapper.seedFrom(null)
        assertTrue(!mapper.reach.isLearned)
    }

    @Test
    fun `the calibrator and the mapper agree end to end`() {
        // The two classes are written against each other and nothing else checks that they meet.
        val calibrator = ReachCalibrator(requiredStableFrames = 1)
        val frame = com.ambokit.aep.core.AepPoseFrame(
            "p", 1L, 0L, 0L, com.ambokit.aep.core.AepTrackingState.TRACKED,
            mapOf(
                "left_shoulder" to com.ambokit.aep.core.AepPosePoint("left_shoulder", 0.44f, 0.30f, null, 0.9f),
                "right_shoulder" to com.ambokit.aep.core.AepPosePoint("right_shoulder", 0.56f, 0.30f, null, 0.9f),
                "left_hip" to com.ambokit.aep.core.AepPosePoint("left_hip", 0.45f, 0.62f, null, 0.9f),
                "right_hip" to com.ambokit.aep.core.AepPosePoint("right_hip", 0.55f, 0.62f, null, 0.9f)
            )
        )
        // Calibration needs a reach shown, not just a body held still.
        fun sweptAt(x: Float, y: Float) = com.ambokit.aep.core.AepPoseFrame(
            "p", 2L, 0L, 0L, com.ambokit.aep.core.AepTrackingState.TRACKED,
            frame.joints + mapOf(
                "left_wrist" to com.ambokit.aep.core.AepPosePoint("left_wrist", x, y, null, 0.9f),
                "right_wrist" to com.ambokit.aep.core.AepPosePoint("right_wrist", x, y, null, 0.9f)
            )
        )
        // A raised hand names the drawing hand; calibration waits for it.
        val raised = com.ambokit.aep.core.AepPoseFrame(
            "p", 3L, 0L, 0L, com.ambokit.aep.core.AepTrackingState.TRACKED,
            frame.joints + mapOf(
                "left_wrist" to com.ambokit.aep.core.AepPosePoint("left_wrist", 0.30f, 0.50f, null, 0.9f),
                "right_wrist" to com.ambokit.aep.core.AepPosePoint("right_wrist", 0.70f, 0.12f, null, 0.9f)
            )
        )
        repeat(12) { calibrator.observeRaisedHand(raised) }
        calibrator.observeSweep(sweptAt(0.25f, 0.30f))
        calibrator.observeSweep(sweptAt(0.75f, 0.60f))
        val profile = calibrator.accept(frame)!!
        val mapper = PointerMapper()
        mapper.seedFrom(profile)
        assertTrue(mapper.reach.isLearned)
        // The middle of a symmetric reach is the middle of the canvas.
        assertEquals(0.5f, mapper.map(0.5f, 0.45f, ASPECT, learning = false)!!.first, absoluteTolerance = 0.05f)
    }
}
