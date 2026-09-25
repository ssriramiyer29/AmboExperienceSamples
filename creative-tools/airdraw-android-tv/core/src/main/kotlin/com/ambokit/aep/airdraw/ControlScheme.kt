package com.ambokit.aep.airdraw

import com.ambokit.aep.core.AepPoseFrame
import com.ambokit.aep.core.capabilities.AepHand
import com.ambokit.aep.core.capabilities.AepHandFrame
import com.ambokit.aep.core.capabilities.AepHandHandedness

/** What the canvas should be doing this frame. */
enum class CanvasMode {
    /** Nothing is being changed. The pointer may still be moving and drawing may still be latched on. */
    IDLE,

    /** Draw mode is latched on and the hand is an open palm. */
    DRAWING,

    /** A closed fist: drag the canvas. */
    PANNING

}

/** Everything the app needs to know about the player's hands this frame. */
data class ControlState(
    val mode: CanvasMode,
    /** The shape of the hand currently in charge, for the diagnostics log. */
    val shape: HandShape,
    /** Whether the master draw latch is on, which is not the same as currently drawing. */
    val drawOn: Boolean,
    /** Which hand is in charge, so the caller can ask the pointer source for the matching arm. */
    val which: AepHandHandedness?,
    /** Where the pen is, in camera-frame coordinates. Null when neither stream could say. */
    val pointer: RawPointer?
)

/**
 * The control scheme: hand shapes decide what mode the canvas is in, pose decides where.
 *
 * **The split, and why.** [HandInterpreter] asks the hand stream for a continuous pen position,
 * and its own measurements record what that costs - the provider loses the hand on 13% of frames
 * in one logged session and 43% in another, each loss breaking the stroke being drawn. Discrete
 * state does not care: a hand that is an open palm and is briefly unreadable is still an open
 * palm, because the state persists across the gap in a way a position cannot. So the hand stream
 * keeps the job it is good at and [PointerSource] supplies the position.
 *
 * **Modes, rather than separate gestures.** Pinch is a latch - it turns drawing on and leaves it
 * on. Underneath it, the shape of the hand picks what movement means: an open palm marks, a fist
 * drags, two fists scale. One thing to remember rather than six, and no gesture that can strand
 * somebody, which is the failure the palette's own history records.
 *
 * **Two fists to zoom, not two pinches.** The old scheme zoomed on two pinches, which is the
 * phone gesture and was the obvious choice. It cannot survive here: pinch is now the master draw
 * toggle, so reaching up with a second pinching hand would toggle drawing as a side effect of
 * starting a zoom. Fists are already the "move the canvas" mode, so two of them scaling it is the
 * same idea with one more hand.
 */
class ControlScheme(
    private val reader: HandShapeReader = HandShapeReader(),
    private val latch: DrawLatch = DrawLatch(),
    private val pointers: PointerSource = PointerSource(),
    /**
     * How long a hand shape survives with no new hand frame behind it.
     *
     * The provider sends at 11-15Hz, so 400ms is about five missed frames - long enough to ride
     * out the ordinary gap between frames and the dropouts on top of it, short enough that a
     * stream which has genuinely stopped does not leave the canvas acting on a stale fist.
     */
    private val handHoldMs: Long = 400L,
    /**
     * How many consecutive frames a fist must hold before it starts dragging the canvas.
     *
     * A hand curls on its way to somewhere else - lowering to rest, turning over, passing out of
     * frame - and on a device 49 of 127 diagnostic samples read as a fist while somebody was
     * trying to draw. Every one of those dragged the picture out from under them. Drawing has no
     * equivalent guard because a stroke is already gated behind a deliberate pinch; panning is
     * the only mode a hand falls into by accident.
     */
    private val fistHoldFrames: Int = 4
) {
    private var lastShape = HandShape.UNKNOWN
    private var lastWhich: AepHandHandedness? = null
    private var lastHandFrameMs = 0L
    private var fistFrames = 0

    /**
     * The hand the person said they draw with, by raising it during calibration.
     *
     * When set, it settles the question outright. The fallbacks below - a pinch, then the hand in
     * charge last frame, then whichever has a side - all exist because nothing had asked.
     */
    var drawingHand: AepHandHandedness? = null

    /** True while the master latch is on. Exposed so the app can show it without a frame. */
    val drawOn: Boolean get() = latch.on

    val pointerSource: PointerSource get() = pointers

    /**
     * How many fingers the hand in charge last read as extended, and its thumb gap.
     *
     * For the diagnostics. A shape of UNKNOWN says only that none of the rules matched; these two
     * say which way it missed, which is the difference between "this person's fist is being read
     * as two fingers out" and "the landmarks were never readable".
     */
    val lastExtended: Int? get() = reader.lastExtended
    val lastGap: Float? get() = reader.lastGap

    fun observePose(frame: AepPoseFrame, atMs: Long) = pointers.observePose(frame, atMs)

    fun reset() {
        lastShape = HandShape.UNKNOWN
        lastWhich = null
        lastHandFrameMs = 0L
        fistFrames = 0
        reader.reset()
        latch.reset()
        pointers.reset()
    }

    /**
     * What the hands are doing this frame.
     *
     * **A null `frame` is not "no hands".** The renderer draws at 60fps and the hand provider
     * sends at 11-15Hz, so on roughly three display frames in four there is simply no *new* hand
     * frame - and the two mean completely different things. Reading null as "no hands" dropped the
     * shape to UNKNOWN and the mode to IDLE on 75% of frames, never asked pose where the wrist
     * was, and shredded every stroke into pieces: a device session recorded ten stroke-starts in
     * sixty frames and thirty-one strokes on the canvas for a handful of intended marks. That is
     * the same failure [HandInterpreter]'s dropout grace was written to fix, reintroduced from a
     * different direction.
     *
     * So a null frame *holds* the last shape and re-reads the position from pose, which is the
     * entire point of splitting the two streams: state persists across a gap in a way a position
     * cannot, and pose supplies the position meanwhile. An empty `hands` list, by contrast, is the
     * provider actually saying it can see nobody, and that does clear the shape.
     */
    fun update(frame: AepHandFrame?, nowMs: Long): ControlState {
        if (frame == null) return held(nowMs)

        val hands = frame.hands
        lastHandFrameMs = nowMs
        if (hands.isEmpty()) {
            // No hands is not a decision to stop drawing. The latch holds, exactly as the pinch
            // latch holds across a dropout, because a hand leaving the frame for two frames is
            // not somebody asking to put the pen down.
            forget()
            return ControlState(CanvasMode.IDLE, reader.read(null), latch.on, null, null)
        }

        val primary = choosePrimary(hands)
        val shape = reader.read(primary)
        latch.update(shape)

        fistFrames = if (shape == HandShape.FIST) fistFrames + 1 else 0
        lastShape = shape
        lastWhich = primary?.handedness
        return resolve(shape, primary, nowMs)
    }

    /**
     * A display frame with no new hand frame behind it.
     *
     * The shape is held, but not forever: past [handHoldMs] the hand stream has genuinely stopped
     * rather than merely being slower than the display, and continuing to act on a shape from a
     * second ago would leave the canvas panning because of a fist somebody has long since opened.
     *
     * The hand itself is deliberately not passed on. Its landmarks are as old as the frame they
     * came from, so letting the hand-wrist fallback answer here would report a stale position as
     * a current one - worse than reporting nothing, which is what a null pointer honestly says.
     */
    private fun held(nowMs: Long): ControlState {
        if (lastShape == HandShape.UNKNOWN || nowMs - lastHandFrameMs > handHoldMs) {
            forget()
            return ControlState(CanvasMode.IDLE, HandShape.UNKNOWN, latch.on, lastWhich,
                                pointers.pointer(pointerHand(), null, nowMs))
        }
        // The latch is deliberately not updated: a frame that carried no new evidence cannot be a
        // new pinch, and feeding the held shape back in would re-toggle drawing every frame.
        return resolve(lastShape, null, nowMs)
    }

    /**
     * Which arm the position comes from.
     *
     * The hand the person raised, whenever they raised one - and *regardless of what the hand
     * stream calls the hand it can see*. `unknown` is a legal handedness in `camera.hand@1` and
     * the provider uses it, so matching the pointer to the hand stream's own labelling meant that
     * every time it declined to name a hand the pointer fell back to that hand's own wrist and
     * jumped. Pose names its joints unambiguously; once the arm is known, the position can come
     * straight from it and the two streams no longer have to agree on anything.
     */
    private fun pointerHand(): AepHandHandedness? = drawingHand ?: lastWhich

    /** Shape plus position to mode, shared by a real frame and a held one so they cannot diverge. */
    private fun resolve(shape: HandShape, primary: AepHand?, nowMs: Long): ControlState {
        val which = pointerHand()
        val pointer = pointers.pointer(which, primary, nowMs)
        val mode = when {
            shape == HandShape.FIST -> if (fistFrames >= fistHoldFrames) CanvasMode.PANNING
                                       else CanvasMode.IDLE
            latch.on && shape == HandShape.OPEN_PALM -> CanvasMode.DRAWING
            else -> CanvasMode.IDLE
        }
        return ControlState(mode, shape, latch.on, which, pointer)
    }

    private fun forget() {
        lastShape = HandShape.UNKNOWN
        fistFrames = 0
    }

    /**
     * Which hand is in charge this frame.
     *
     * A pinching hand wins, because a pinch is a deliberate statement and the person making it
     * means that hand. Failing that, the first hand the provider placed - and an unplaced hand
     * only if there is nothing better, since [PointerSource] cannot match one to an arm and it
     * will fall back to the hand's own wrist.
     */
    private fun choosePrimary(hands: List<AepHand>): AepHand? {
        // Asked for, so there is nothing to work out - and nothing to fall back to either.
        //
        // This used to fall through to the other hand when the named one was not matched, which
        // left the position locked to the drawing wrist while the *shape* came from whichever
        // hand happened to be readable. An off hand curling into a fist could put the canvas into
        // pan mode while the pointer followed the drawing hand, which is precisely the "it is
        // still watching my other hand" that a device session reported. If the hand they named is
        // not there, the honest answer is that there is no hand in charge.
        drawingHand?.let { chosen ->
            hands.firstOrNull { it.handedness == chosen }?.let { return it }
            // Except for a lone hand the provider would not place: `unknown` is a legal
            // handedness and there is no other candidate for it to be. With two on screen an
            // unplaced one could be either, so it is declined.
            return hands.singleOrNull()?.takeIf { it.handedness == AepHandHandedness.UNKNOWN }
        }
        if (hands.size == 1) return hands[0]
        val pinching = hands.firstOrNull { probe.read(it) == HandShape.PINCH }
        if (pinching != null) return pinching
        // Failing a pinch, stay with the hand that was in charge last frame.
        //
        // Without this the choice was "the first hand in the list that has a side", and the
        // provider's ordering is not stable - so with both hands visible and neither pinching,
        // the pointer flipped between the two wrists frame to frame. On a device that read as
        // the cursor jumping left and right while the person held still, and every jump while
        // drawing laid down a stroke nobody asked for.
        lastWhich?.let { previous ->
            hands.firstOrNull { it.handedness == previous }?.let { return it }
        }
        return hands.firstOrNull { it.handedness != AepHandHandedness.UNKNOWN } ?: hands.firstOrNull()
    }

    /**
     * A throwaway reader for the "is this hand pinching" question above.
     *
     * Deliberately not [reader]: that one carries the hysteresis latch for the hand in charge,
     * and running other hands through it would let the off hand's gap set the primary hand's
     * pinch state. Two hands sharing one latch is how the off hand reaching for the palette used
     * to become the pen.
     */
    private val probe = HandShapeReader()
}
