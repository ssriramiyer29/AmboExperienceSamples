package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepTrackingState
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.math.abs
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
    /**
     * Measured, at last.
     *
     * From a logged session on a television: a pinch the player is deliberately holding reads a
     * median of 0.72 and dips as low as 0.36; an open hand reads a median of 0.00 and reaches
     * 0.35 at its ninetieth percentile. The two barely overlap, so entering at 0.50 and leaving
     * at 0.22 sits in the gap with room on both sides.
     */
    private val pinchEnter: Float = 0.50f,
    private val pinchExit: Float = 0.22f,
    /**
     * How long a pinch survives the hand vanishing.
     *
     * In that same session the provider lost the hand entirely in thirteen percent of frames -
     * twelve times in two minutes - and each loss ended the stroke and started a new one. Thirty
     * nine strokes were recorded for a handful of intended marks. The dropouts are one or two
     * frames at about 10 Hz, so holding the pinch across 300ms bridges them without holding a pen
     * down that the player has genuinely let go of.
     */
    private val dropoutGraceMs: Long = 300L,
    private val minimumConfidence: Float = 0.4f,
    /** The part of the camera frame that maps onto the canvas. See [remap]. */
    private val activeMinX: Float = 0.10f,
    private val activeMaxX: Float = 0.90f,
    private val activeMinY: Float = 0.08f,
    private val activeMaxY: Float = 0.78f
) {
    /** The last raw camera-frame position, before mirroring and remapping, for diagnostics. */
    var lastRawX: Float? = null
        private set
    var lastRawY: Float? = null
        private set

    /** The pinch strengths seen this frame, for diagnostics. Null where no hand was there. */
    var lastLeftPinch: Float? = null
        private set
    var lastRightPinch: Float? = null
        private set

    private val leftPinch = PinchLatch(pinchEnter, pinchExit, dropoutGraceMs)
    private val rightPinch = PinchLatch(pinchEnter, pinchExit, dropoutGraceMs)
    private val lonePinch = PinchLatch(pinchEnter, pinchExit, dropoutGraceMs)

    /** True while any pinch is being held open across a dropout rather than actually seen. */
    val isBridging: Boolean
        get() = leftPinch.bridging || rightPinch.bridging || lonePinch.bridging

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
        lastLeftPinch = null
        lastRightPinch = null
    }

    /**
     * The hand that is drawing: whichever one is pinching.
     *
     * Not the hand the player nominated at the start. An earlier version asked them to choose,
     * and then chose wrong - a hand curled around a phone being set down looks exactly like a
     * pinch - which left the pen on the wrong hand with no way back. Asking the question at all
     * was the mistake: the hand that is pinching is the hand that is drawing, and it needs no
     * setting up and cannot be got wrong.
     */
    fun penHand(input: HandInput): HandPointer? = when {
        input.unplaced != null -> if (lonePinch.on) input.unplaced else null
        leftPinch.on && !rightPinch.on -> input.left
        rightPinch.on && !leftPinch.on -> input.right
        else -> null
    }

    /**
     * Whether a pen is being held, whether or not a hand is visible this frame.
     *
     * Separate from [penHand] because during a dropout the pen is still down and there is simply
     * nowhere to draw. The caller holds the stroke open and adds no points, rather than ending it.
     */
    val isPenHeld: Boolean
        get() = lonePinch.on || (leftPinch.on != rightPinch.on)

    fun read(frame: AepHandFrame?, aspect: Float, nowMs: Long): HandInput {
        // A lost frame is not a reset. The provider drops the hand for a frame or two several
        // times a minute, and tearing everything down on each blink is what turned a handful of
        // drawn marks into thirty-nine strokes.
        val hands = if (frame == null || frame.trackingState == AepTrackingState.LOST) emptyList()
        else resolveSides(frame.hands.mapNotNull { toPointer(it, aspect) })

        val left = hands.firstOrNull { it.handedness == AepHandHandedness.LEFT }
        val right = hands.firstOrNull { it.handedness == AepHandHandedness.RIGHT }
        val unplaced = hands.firstOrNull { it.handedness == AepHandHandedness.UNKNOWN }
        leftPinch.update(left?.pinch, nowMs)
        rightPinch.update(right?.pinch, nowMs)
        lonePinch.update(unplaced?.pinch, nowMs)
        lastLeftPinch = left?.pinch
        lastRightPinch = (right ?: unplaced)?.pinch

        return HandInput(tracked = hands.isNotEmpty(), left = left, right = right, unplaced = unplaced)
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

    /**
     * Pinch detection with hysteresis, and with a hand that keeps disappearing.
     *
     * Hysteresis alone assumes the signal is continuous. This one is not: the provider loses the
     * hand outright for a frame or two, several times a minute, and a latch that believes every
     * gap chops a drawn line into pieces. A gap shorter than the grace is treated as the tracker
     * blinking rather than as the player letting go.
     */
    private class PinchLatch(
        private val enter: Float,
        private val exit: Float,
        private val graceMs: Long
    ) {
        var on = false
            private set

        /** True while [on] is being sustained by the grace rather than by a reading. */
        var bridging = false
            private set

        private var lastSeenOnMs = Long.MIN_VALUE

        fun update(strength: Float?, nowMs: Long) {
            if (strength == null) {
                // The hand is not there at all. Hold on if it was pinching a moment ago.
                if (on && nowMs - lastSeenOnMs <= graceMs) { bridging = true; return }
                on = false
                bridging = false
                return
            }
            val held = if (on) strength > exit else strength >= enter
            if (held) {
                on = true
                bridging = false
                lastSeenOnMs = nowMs
                return
            }
            // Seen, and open. A reading below the exit threshold is the player letting go, and is
            // believed immediately - the grace is for absence, not for disagreement.
            on = false
            bridging = false
        }

        fun reset() {
            on = false
            bridging = false
            lastSeenOnMs = Long.MIN_VALUE
        }
    }

    private fun toPointer(hand: AepHand, aspect: Float): HandPointer? {
        if (hand.confidence < minimumConfidence) return null
        val thumb = landmark(hand, "thumb_tip")
        val index = landmark(hand, "index_finger_tip")
        if (thumb == null || index == null) return null

        val rawX = ((thumb.first + index.first) * 0.5f)
        val rawY = ((thumb.second + index.second) * 0.5f)
        lastRawX = rawX
        lastRawY = rawY

        val mirrored = if (mirrorX) 1f - rawX else rawX
        return HandPointer(
            handedness = hand.handedness,
            x = remap(mirrored, activeMinX, activeMaxX),
            // The provider normalises y over the camera frame; the canvas is 0..aspect.
            y = remap(rawY, activeMinY, activeMaxY) * aspect,
            pinch = (hand.pinchStrength ?: 0.0).toFloat(),
            confidence = hand.confidence.toFloat()
        )
    }

    /**
     * Stretch the comfortable part of the camera frame across the whole canvas.
     *
     * Without this, reaching the bottom of the screen means putting your hand at the bottom of
     * what the camera can see - which is somewhere around your knees, and was reported as the
     * bottom of the canvas and the lower half of the tool strip being hard to get to. Mapping a
     * sub-rectangle onto the full canvas means the corners are reachable from a position someone
     * can hold.
     *
     * The bounds are a first cut and the diagnostics print the raw values, so they can be set
     * from where hands actually go rather than from where it seems like they would.
     */
    private fun remap(value: Float, min: Float, max: Float): Float =
        ((value - min) / (max - min)).coerceIn(0f, 1f)

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
 * Everything is measured from a single anchor taken when the gesture starts, and never from the
 * previous frame. That distinction is the whole difference between a zoom that holds still and
 * one that crawls: with a frame-to-frame delta, each frame's tracking noise is applied and then
 * kept, so the canvas performs a random walk and drifts while two hands are held perfectly
 * still. Against a fixed anchor the same noise only wobbles around the truth.
 *
 * On top of that: the separation and midpoint are low-passed, because hand landmarks at three
 * metres are noisy at exactly the frequency that reads as shake; small changes are ignored
 * entirely; and the gesture does nothing at all until the hands have moved enough to mean it, so
 * grabbing does not jolt the canvas.
 */
class ZoomGesture(
    private val smoothing: Float = 0.25f,
    /**
     * Deadbands large enough to swallow the noise, which is what makes them worth having.
     *
     * They are measured against what has already been applied, not against the previous frame,
     * so a slow deliberate pan is not suppressed - the unapplied difference accumulates until it
     * crosses, and then moves. Only movement that never goes anywhere is ignored.
     */
    private val scaleDeadband: Float = 0.02f,
    private val panDeadband: Float = 0.01f,
    private val engageSlop: Float = 0.03f
) {
    private var active = false
    private var engaged = false

    private var anchorSeparation = 0f
    private var anchorMidX = 0f
    private var anchorMidY = 0f

    private var smoothSeparation = 0f
    private var smoothMidX = 0f
    private var smoothMidY = 0f

    /** What has already been handed to the viewport, so the next change is only the difference. */
    private var appliedRatio = 1f
    private var appliedPanX = 0f
    private var appliedPanY = 0f

    val isActive: Boolean get() = active

    fun end() {
        active = false
        engaged = false
    }

    /**
     * @return the change to apply since the last one, or null when there is nothing worth applying.
     */
    fun update(a: HandPointer?, b: HandPointer?, bothPinching: Boolean): Change? {
        if (a == null || b == null || !bothPinching) {
            end()
            return null
        }
        val separation = hypot(a.x - b.x, a.y - b.y)
        val midX = (a.x + b.x) * 0.5f
        val midY = (a.y + b.y) * 0.5f

        if (!active) {
            active = true
            engaged = false
            anchorSeparation = separation
            anchorMidX = midX
            anchorMidY = midY
            smoothSeparation = separation
            smoothMidX = midX
            smoothMidY = midY
            appliedRatio = 1f
            appliedPanX = 0f
            appliedPanY = 0f
            return null
        }
        if (anchorSeparation <= 1e-4f) return null

        smoothSeparation += smoothing * (separation - smoothSeparation)
        smoothMidX += smoothing * (midX - smoothMidX)
        smoothMidY += smoothing * (midY - smoothMidY)

        val ratio = smoothSeparation / anchorSeparation
        val panX = smoothMidX - anchorMidX
        val panY = smoothMidY - anchorMidY

        // Nothing happens until the hands have said something. Without this, the act of closing
        // both pinches moves the canvas before the player has begun the gesture they intended.
        if (!engaged) {
            if (abs(ratio - 1f) < engageSlop && hypot(panX, panY) < engageSlop) return null
            engaged = true
        }

        // Scale and pan are released independently. Sharing one gate meant that zooming, which
        // crosses its threshold constantly, dragged every scrap of midpoint noise onto the canvas
        // with it.
        val scale = ratio / appliedRatio
        val deltaX = panX - appliedPanX
        val deltaY = panY - appliedPanY
        val movesScale = abs(scale - 1f) >= scaleDeadband
        val movesPan = hypot(deltaX, deltaY) >= panDeadband
        if (!movesScale && !movesPan) return null

        if (movesScale) appliedRatio = ratio
        if (movesPan) { appliedPanX = panX; appliedPanY = panY }
        return Change(
            scale = if (movesScale) scale else 1f,
            panX = if (movesPan) deltaX else 0f,
            panY = if (movesPan) deltaY else 0f,
            focusX = smoothMidX,
            focusY = smoothMidY
        )
    }

    data class Change(
        val scale: Float,
        val panX: Float,
        val panY: Float,
        val focusX: Float,
        val focusY: Float
    )
}
