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

/** What both hands are doing this frame. */
data class HandInput(
    val tracked: Boolean,
    val drawing: HandPointer?,
    val other: HandPointer?
) {
    val handCount: Int get() = (if (drawing != null) 1 else 0) + (if (other != null) 1 else 0)
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
    private var pinching = false

    /** True while the pen is down, after hysteresis. */
    val isPinching: Boolean get() = pinching

    fun reset() {
        pinching = false
    }

    fun read(frame: AepHandFrame?, aspect: Float): HandInput {
        if (frame == null || frame.trackingState == AepTrackingState.LOST) {
            pinching = false
            return HandInput(tracked = false, drawing = null, other = null)
        }

        val pointers = frame.hands
            .mapNotNull { toPointer(it, aspect) }
            .sortedByDescending { it.pinch }

        if (pointers.isEmpty()) {
            pinching = false
            return HandInput(tracked = false, drawing = null, other = null)
        }

        // The hand doing the most pinching is the one drawing. That beats fixing it to the right
        // hand, which is a decision about the player rather than about what they are doing.
        val drawing = pointers[0]
        pinching = if (pinching) drawing.pinch > pinchExit else drawing.pinch >= pinchEnter

        return HandInput(
            tracked = true,
            drawing = drawing,
            other = pointers.getOrNull(1)
        )
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
