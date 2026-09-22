package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.math.hypot

/** One hand, reduced to what a drawing tool cares about. */
data class HandPointer(
    val handedness: AepHandHandedness,
    /** Document space, already mirrored so moving right moves the cursor right. */
    val x: Float,
    val y: Float,
    /** 0..1 from the provider. */
    val pinch: Float,
    val confidence: Float
)

/**
 * What both hands are doing this frame, keyed by which hand they are.
 *
 * Keyed rather than ranked because AirDraw gives the two hands different jobs: one draws, the
 * other opens the tools. An earlier version handed back "the hand pinching hardest" as the pen,
 * which cannot express that - the off hand reaching for the tool panel would have become the pen
 * the moment it pinched harder than the hand holding it.
 */
data class HandInput(
    val tracked: Boolean,
    val left: HandPointer?,
    val right: HandPointer?,
    /**
     * A lone hand the provider would not place.
     *
     * `unknown` is a legal handedness in `camera.hand@1`. With two hands on screen, position
     * settles it; with one there is nothing to compare it against, and calling it the right hand
     * would leave a left-handed player holding a pen the app cannot see.
     */
    val unplaced: HandPointer? = null
) {
    val handCount: Int
        get() = (if (left != null) 1 else 0) + (if (right != null) 1 else 0) + (if (unplaced != null) 1 else 0)

    /** The named hand, or the lone unplaceable one, which is the only hand it could be. */
    fun hand(which: AepHandHandedness?): HandPointer? = when (which) {
        AepHandHandedness.LEFT -> left ?: unplaced
        AepHandHandedness.RIGHT -> right ?: unplaced
        else -> unplaced
    }

    fun otherThan(which: AepHandHandedness?): HandPointer? = when (which) {
        AepHandHandedness.LEFT -> right
        AepHandHandedness.RIGHT -> left
        else -> null
    }
}

/**
 * Turns `camera.hand@1` into a pen.
 *
 * The pen tip is the midpoint of the thumb and index fingertips rather than a single landmark.
 * Pinching brings those two together, so their midpoint is where a person feels the pen is — and
 * it stays put through the pinch, where either fingertip alone moves several centimetres as the
 * fingers close. A cursor that jumps when you start drawing is a cursor nobody can aim.
 *
 * Pinch detection uses hysteresis. A single threshold on a noisy signal breaks one stroke into
 * several, which on a canvas is not a glitch but a wrong drawing.
 */
class HandInterpreter(
    private val mirrorX: Boolean = true,
    private val pinchEnter: Float = 0.6f,
    private val pinchExit: Float = 0.35f,
    private val minimumConfidence: Float = 0.4f
) {
    private val leftPinch = PinchLatch(pinchEnter, pinchExit)
    private val rightPinch = PinchLatch(pinchEnter, pinchExit)
    private val lonePinch = PinchLatch(pinchEnter, pinchExit)

    /** True while that hand is pinched, after hysteresis. */
    fun isPinching(which: AepHandHandedness?): Boolean = when (which) {
        AepHandHandedness.LEFT -> leftPinch.on
        AepHandHandedness.RIGHT -> rightPinch.on
        else -> false
    }

    /** True while a lone hand the provider would not place is pinched. */
    val isLonePinching: Boolean get() = lonePinch.on

    fun reset() {
        leftPinch.reset()
        rightPinch.reset()
        lonePinch.reset()
    }

    fun read(frame: AepHandFrame?, aspect: Float): HandInput {
        if (frame == null || frame.trackingState == AepTrackingState.LOST) {
            reset()
            return HandInput(tracked = false, left = null, right = null)
        }

        val pointers = resolveSides(frame.hands.mapNotNull { toPointer(it, aspect) })
        if (pointers.isEmpty()) {
            reset()
            return HandInput(tracked = false, left = null, right = null)
        }

        val left = pointers.firstOrNull { it.handedness == AepHandHandedness.LEFT }
        val right = pointers.firstOrNull { it.handedness == AepHandHandedness.RIGHT }
        val unplaced = pointers.firstOrNull { it.handedness == AepHandHandedness.UNKNOWN }
        leftPinch.update(left?.pinch)
        rightPinch.update(right?.pinch)
        lonePinch.update(unplaced?.pinch)

        return HandInput(tracked = true, left = left, right = right, unplaced = unplaced)
    }

    /**
     * Give every hand a side where that can be worked out, and admit it where it cannot.
     *
     * `unknown` is a legal value in `camera.hand@1`, and an experience that assumed left or right
     * would simply stop working against a provider that reports it. With two hands on screen,
     * position settles it: after mirroring, the hand further left is the left hand. That is not
     * always true - arms cross - but it is true while someone is drawing, which is when it is
     * asked.
     *
     * With one hand there is nothing to compare it against, so it stays unknown rather than being
     * guessed at. A guess of "right" would hand a left-handed player a pen the app cannot see.
     */
    private fun resolveSides(pointers: List<HandPointer>): List<HandPointer> {
        if (pointers.none { it.handedness == AepHandHandedness.UNKNOWN }) return pointers
        if (pointers.size < 2) return pointers

        val ordered = pointers.sortedBy { it.x }
        return ordered.mapIndexed { index, pointer ->
            if (pointer.handedness != AepHandHandedness.UNKNOWN) pointer
            else pointer.copy(
                handedness = if (index == 0) AepHandHandedness.LEFT else AepHandHandedness.RIGHT
            )
        }
    }

    /** Pinch detection with hysteresis, per hand. */
    private class PinchLatch(private val enter: Float, private val exit: Float) {
        var on = false
            private set

        fun update(strength: Float?) {
            if (strength == null) { on = false; return }
            on = if (on) strength > exit else strength >= enter
        }

        fun reset() { on = false }
    }

    private fun toPointer(hand: AepHand, aspect: Float): HandPointer? {
        if (hand.confidence < minimumConfidence) return null
        val thumb = landmark(hand, "thumb_tip")
        val index = landmark(hand, "index_finger_tip")
        if (thumb == null || index == null) return null

        val rawX = ((thumb.first + index.first) * 0.5f)
        val rawY = ((thumb.second + index.second) * 0.5f)
        return HandPointer(
            handedness = hand.handedness,
            x = if (mirrorX) 1f - rawX else rawX,
            // The provider normalises y over the camera frame; the canvas is 0..aspect.
            y = rawY * aspect,
            pinch = (hand.pinchStrength ?: 0.0).toFloat(),
            confidence = hand.confidence.toFloat()
        )
    }

    private fun landmark(hand: AepHand, name: String): Pair<Float, Float>? {
        // Landmarks are positional and may carry nulls where a joint was not seen, which is why
        // the generated model types them nullable. Reading one by name rather than by index is
        // the experience's job - AEP hands over the protocol's shape, not MediaPipe's numbering.
        for (point in hand.landmarks) {
            if (point != null && point.name == name) {
                if (point.confidence < minimumConfidence) return null
                return point.x.toFloat() to point.y.toFloat()
            }
        }
        return null
    }
}

/**
 * Two-handed pinch to zoom and pan, the gesture everyone already knows from a phone.
 *
 * Tracked as a delta from where the gesture started rather than frame to frame, so a dropped
 * frame cannot leave the canvas permanently scaled wrong.
 */
class ZoomGesture {
    private var active = false
    private var startSeparation = 0f
    private var startMidX = 0f
    private var startMidY = 0f

    val isActive: Boolean get() = active

    fun end() {
        active = false
    }

    /**
     * @return scale factor and pan delta since the last update, or null when not zooming.
     */
    fun update(a: HandPointer?, b: HandPointer?, bothPinching: Boolean): Change? {
        if (a == null || b == null || !bothPinching) {
            active = false
            return null
        }
        val separation = hypot(a.x - b.x, a.y - b.y)
        val midX = (a.x + b.x) * 0.5f
        val midY = (a.y + b.y) * 0.5f

        if (!active) {
            active = true
            startSeparation = separation
            startMidX = midX
            startMidY = midY
            return null
        }
        if (startSeparation <= 1e-4f) return null

        val change = Change(
            scale = separation / startSeparation,
            panX = midX - startMidX,
            panY = midY - startMidY,
            focusX = midX,
            focusY = midY
        )
        startSeparation = separation
        startMidX = midX
        startMidY = midY
        return change
    }

    data class Change(
        val scale: Float,
        val panX: Float,
        val panY: Float,
        val focusX: Float,
        val focusY: Float
    )
}
