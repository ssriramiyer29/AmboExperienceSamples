package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandHandedness

/** Where a pointer position came from, so the caller can log it and a test can assert on it. */
enum class PointerOrigin {
    /** `camera.pose` wrist joint. The one this rework exists to use. */
    POSE_WRIST,

    /** `camera.hand` landmark 0, when pose is absent or stale. */
    HAND_WRIST
}

/** A pointer position in camera-frame coordinates, 0..1, before mirroring or reach mapping. */
data class RawPointer(
    val x: Float,
    val y: Float,
    val origin: PointerOrigin,
    /** Age of the sample that produced this, in milliseconds. Zero for a fresh hand frame. */
    val ageMs: Long
)

/**
 * Where the pen is, from whichever stream can currently say.
 *
 * **Why pose and not the hand.** `camera.hand@1` carries the wrist too - it is landmark 0 of 21 -
 * so this class is not about reaching a joint the hand stream lacks. It is about *continuity*.
 * [HandInterpreter]'s own measurements record the hand provider losing the hand on 13% of frames
 * in one session and 43% in another, and each loss used to break the stroke being drawn. A body
 * model does not need a successful hand-detection crop to keep tracking, so its wrist survives
 * exactly the frames where the hand's does not.
 *
 * **Why the fallback is not a nicety.** Running hand and pose inference at once on the phone is
 * untested as of this writing, and if it throttles then both streams degrade and the rework has
 * made AirDraw worse than the version it replaced. Falling back to the hand's own wrist keeps the
 * whole new control scheme - the shapes, the latch, the modes - and gives up only the continuity.
 * That is a bad day rather than a rewrite, and it is worth the indirection to have it.
 *
 * **Staleness, not presence, is the test.** On Android the Gateway runs in-process on loopback,
 * so the link stays perfect while the phone is gone; a measured session showed 23 seconds between
 * a phone leaving and anything reporting it. A pose frame that merely *exists* therefore proves
 * nothing about whether pose is still arriving, which is why this asks how old the frame is
 * rather than whether there is one.
 */
class PointerSource(
    /**
     * How old a pose frame may be before the hand's wrist is preferred.
     *
     * At 30fps a frame is 33ms, so this is about four frames: long enough to ride out the ordinary
     * jitter in arrival times, short enough that a stalled pose stream hands over before a person
     * notices the pen sticking. Deliberately far tighter than `StreamLiveness`'s 2,000ms default,
     * because that answers "should the experience react" and this answers "which number do I draw
     * with this frame".
     */
    private val poseStaleMs: Long = 130L,
    private val minimumConfidence: Float = 0.35f
) {
    private var poseFrame: AepPoseFrame? = null
    private var poseAtMs: Long = 0L

    /** The origin of the last pointer returned, for the diagnostics log. */
    var lastOrigin: PointerOrigin? = null
        private set

    /** How many times the source has changed hands, which is what a degrading stream looks like. */
    var handovers: Int = 0
        private set

    fun observePose(frame: AepPoseFrame, atMs: Long) {
        poseFrame = frame
        poseAtMs = atMs
    }

    fun reset() {
        poseFrame = null
        poseAtMs = 0L
        lastOrigin = null
        handovers = 0
    }

    /**
     * The pen position this frame, or null if neither stream can supply one.
     *
     * @param which the hand doing the drawing, so the pointer follows the same arm the gesture
     *   was read from. Pose joints are named in the person's own frame, as handedness is, so no
     *   mirroring is needed to pair them.
     */
    fun pointer(which: AepHandHandedness?, hand: AepHand?, nowMs: Long): RawPointer? {
        val fromPose = poseWrist(which, nowMs)
        val result = fromPose ?: handWrist(hand)
        if (result != null && lastOrigin != null && result.origin != lastOrigin) handovers++
        if (result != null) lastOrigin = result.origin
        return result
    }

    private fun poseWrist(which: AepHandHandedness?, nowMs: Long): RawPointer? {
        val frame = poseFrame ?: return null
        val age = nowMs - poseAtMs
        // A negative age means the clocks disagree; treat it as fresh rather than as a reason to
        // discard the only continuous signal we have.
        if (age > poseStaleMs) return null
        val name = when (which) {
            AepHandHandedness.LEFT -> "left_wrist"
            AepHandHandedness.RIGHT -> "right_wrist"
            // An unplaced hand cannot be matched to an arm. Pose has two wrists and no way to say
            // which one this hand is, and picking the nearer would be a guess that silently swaps
            // arms mid-stroke.
            else -> return null
        }
        if (frame.trackingState != AepTrackingState.TRACKED) return null
        val joint = frame[name] ?: return null
        if (joint.confidence < minimumConfidence) return null
        return RawPointer(joint.x, joint.y, PointerOrigin.POSE_WRIST, maxOf(0L, age))
    }

    private fun handWrist(hand: AepHand?): RawPointer? {
        if (hand == null) return null
        // By name, not by index: `landmarks` is a list of nullables with no schema guarantee that
        // position implies identity, and a short or reordered list would otherwise be read as a
        // different joint rather than as a missing one.
        val wrist = hand.landmarks.firstOrNull { it != null && it.name == "wrist" } ?: return null
        if (wrist.confidence < minimumConfidence) return null
        return RawPointer(wrist.x.toFloat(), wrist.y.toFloat(), PointerOrigin.HAND_WRIST, 0L)
    }
}
