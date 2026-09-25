package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.AepPosePoint
import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandHandedness
import com.ambokit.aep.core.capabilities.AepHandLandmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun pose(
    leftX: Float = 0.3f,
    rightX: Float = 0.7f,
    y: Float = 0.5f,
    confidence: Float = 0.9f,
    tracking: AepTrackingState = AepTrackingState.TRACKED,
    omit: Set<String> = emptySet()
): AepPoseFrame {
    val joints = mutableMapOf<String, AepPosePoint>()
    if ("left_wrist" !in omit) joints["left_wrist"] = AepPosePoint("left_wrist", leftX, y, null, confidence)
    if ("right_wrist" !in omit) joints["right_wrist"] = AepPosePoint("right_wrist", rightX, y, null, confidence)
    return AepPoseFrame("p1", 1L, 0L, 0L, tracking, joints)
}

private fun handWith(wristX: Float = 0.42f, wristY: Float = 0.61f, confidence: Float = 0.9f,
                     includeWrist: Boolean = true): AepHand {
    val marks = mutableListOf<AepHandLandmark?>()
    if (includeWrist) marks += AepHandLandmark("wrist", wristX.toDouble(), wristY.toDouble(), confidence.toDouble())
    marks += AepHandLandmark("index_finger_tip", 0.5, 0.2, 0.9)
    return AepHand(AepHandHandedness.RIGHT, confidence.toDouble(), marks)
}

class PointerSourceTest {

    @Test
    fun `pose wrist is preferred when it is fresh`() {
        val source = PointerSource()
        source.observePose(pose(rightX = 0.72f), atMs = 1_000)
        val p = source.pointer(AepHandHandedness.RIGHT, handWith(), nowMs = 1_010)
        assertEquals(PointerOrigin.POSE_WRIST, p?.origin)
        assertEquals(0.72f, p?.x)
    }

    @Test
    fun `handedness picks the matching arm`() {
        val source = PointerSource()
        source.observePose(pose(leftX = 0.21f, rightX = 0.79f), atMs = 0)
        assertEquals(0.21f, source.pointer(AepHandHandedness.LEFT, handWith(), 10)?.x)
        assertEquals(0.79f, source.pointer(AepHandHandedness.RIGHT, handWith(), 10)?.x)
    }

    // ---- the fallback, which is the whole reason for the indirection ---------------------------

    /**
     * The scenario this class is insurance against: hand and pose inference together throttle the
     * phone, pose frames stop arriving, and AirDraw has to keep drawing rather than stop.
     */
    @Test
    fun `a stale pose frame hands over to the hand wrist rather than sticking`() {
        val source = PointerSource(poseStaleMs = 130)
        source.observePose(pose(), atMs = 1_000)
        assertEquals(PointerOrigin.POSE_WRIST, source.pointer(AepHandHandedness.RIGHT, handWith(), 1_100)?.origin)

        val late = source.pointer(AepHandHandedness.RIGHT, handWith(wristX = 0.42f), nowMs = 1_400)
        assertEquals(PointerOrigin.HAND_WRIST, late?.origin, "300ms is well past stale")
        assertEquals(0.42f, late?.x, "and it must be the hand's own wrist, not the last pose value")
    }

    @Test
    fun `an existing pose frame is not evidence that pose is still arriving`() {
        // The Gateway runs in-process on loopback, so the link stays perfect while the phone is
        // gone - a measured session showed 23 seconds before anything noticed. Presence proves
        // nothing; age is the only usable test.
        val source = PointerSource(poseStaleMs = 130)
        source.observePose(pose(), atMs = 0)
        val muchLater = source.pointer(AepHandHandedness.RIGHT, handWith(), nowMs = 23_340)
        assertEquals(PointerOrigin.HAND_WRIST, muchLater?.origin)
    }

    @Test
    fun `handovers are counted so a degrading stream is visible in the log`() {
        val source = PointerSource(poseStaleMs = 100)
        source.observePose(pose(), atMs = 0)
        source.pointer(AepHandHandedness.RIGHT, handWith(), 10)     // pose
        source.pointer(AepHandHandedness.RIGHT, handWith(), 500)    // -> hand
        source.observePose(pose(), atMs = 600)
        source.pointer(AepHandHandedness.RIGHT, handWith(), 610)    // -> pose
        assertEquals(2, source.handovers)
    }

    @Test
    fun `age is reported so the caller can see how fresh the position is`() {
        val source = PointerSource()
        source.observePose(pose(), atMs = 1_000)
        assertEquals(40L, source.pointer(AepHandHandedness.RIGHT, handWith(), 1_040)?.ageMs)
    }

    // ---- declining rather than guessing --------------------------------------------------------

    @Test
    fun `an unplaced hand does not get a pose wrist`() {
        // Pose has two wrists and nothing that says which arm an unplaced hand belongs to.
        // Choosing the nearer one would swap arms mid-stroke without saying so.
        val source = PointerSource()
        source.observePose(pose(), atMs = 0)
        val p = source.pointer(AepHandHandedness.UNKNOWN, handWith(), 10)
        assertEquals(PointerOrigin.HAND_WRIST, p?.origin)
    }

    @Test
    fun `an untracked pose frame is not used`() {
        val source = PointerSource()
        source.observePose(pose(tracking = AepTrackingState.LOST), atMs = 0)
        assertEquals(PointerOrigin.HAND_WRIST, source.pointer(AepHandHandedness.RIGHT, handWith(), 10)?.origin)
    }

    @Test
    fun `a low confidence wrist joint is not used`() {
        val source = PointerSource(minimumConfidence = 0.35f)
        source.observePose(pose(confidence = 0.1f), atMs = 0)
        assertEquals(PointerOrigin.HAND_WRIST, source.pointer(AepHandHandedness.RIGHT, handWith(), 10)?.origin)
    }

    @Test
    fun `a missing wrist joint falls back rather than throwing`() {
        val source = PointerSource()
        source.observePose(pose(omit = setOf("right_wrist")), atMs = 0)
        assertEquals(PointerOrigin.HAND_WRIST, source.pointer(AepHandHandedness.RIGHT, handWith(), 10)?.origin)
    }

    @Test
    fun `no pose and no hand wrist yields nothing at all`() {
        val source = PointerSource()
        assertNull(source.pointer(AepHandHandedness.RIGHT, handWith(includeWrist = false), 10))
        assertNull(source.pointer(AepHandHandedness.RIGHT, null, 10))
    }

    @Test
    fun `the hand wrist is found by name not by position`() {
        val source = PointerSource()
        val hand = handWith(wristX = 0.33f)
        val reversed = AepHand(hand.handedness, hand.confidence, hand.landmarks.reversed())
        assertEquals(0.33f, source.pointer(AepHandHandedness.RIGHT, reversed, 10)?.x)
    }

    @Test
    fun `clocks disagreeing does not discard the only continuous signal`() {
        val source = PointerSource()
        source.observePose(pose(), atMs = 5_000)
        val p = source.pointer(AepHandHandedness.RIGHT, handWith(), nowMs = 4_900)
        assertEquals(PointerOrigin.POSE_WRIST, p?.origin)
        assertTrue((p?.ageMs ?: -1L) >= 0L, "a negative age must never reach the caller")
    }
}
