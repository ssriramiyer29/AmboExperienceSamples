package com.ambokit.aep.airdraw

import com.ambokit.aep.core.capabilities.AepHandHandedness

/**
 * Which colour and how thick.
 *
 * Holds the choice and nothing else - what moved it is [ToolPanel]'s business, and a later version
 * that drives this from a phone or a remote changes that edge rather than these rules.
 */
class ToolSurface(
    val colours: IntArray = AirDrawEngine.DEFAULT_COLOURS,
    val widths: FloatArray = AirDrawEngine.DEFAULT_WIDTHS
) {
    var colourIndex: Int = 0
        private set
    var widthIndex: Int = 1
        private set

    private var flashUntilMs: Long = 0L

    val colour: Int get() = colours[colourIndex]
    val width: Float get() = widths[widthIndex]

    /**
     * Whether to show the choice on screen while the panel itself is shut.
     *
     * A selection closes the panel, and the moment it does the player has no confirmation of what
     * they picked. This carries it for a second after the panel goes.
     */
    fun isFlashing(nowMs: Long): Boolean = nowMs < flashUntilMs

    fun selectColour(index: Int, nowMs: Long) {
        if (index !in colours.indices) return
        colourIndex = index
        flashUntilMs = nowMs + FLASH_MS
    }

    fun selectWidth(index: Int, nowMs: Long) {
        if (index !in widths.indices) return
        widthIndex = index
        flashUntilMs = nowMs + FLASH_MS
    }

    private companion object {
        const val FLASH_MS = 1_200L
    }
}

/**
 * The tool area: opened by the off hand, used by the drawing hand.
 *
 * The screen is a canvas and a strip. The strip is not there until the off hand asks for it, and
 * it overlays the canvas rather than shrinking it - a drawing that moved sideways every time the
 * tools appeared would be unusable.
 *
 * It sits on the dominant side, which is the shortest reach for the hand that has to select from
 * it, and which keeps the off hand where it already is.
 */
class ToolPanel(
    val tools: ToolSurface = ToolSurface(),
    var stripWidth: Float = 0.18f
) {
    var isOpen: Boolean = false
        private set

    private var offHandPinching = false
    private var vetoed = false

    enum class Kind { COLOUR, WIDTH, UNDO }

    /** A target in pointer space: x is 0..1 across the screen, y is 0..aspect down it. */
    data class Cell(
        val kind: Kind,
        val index: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centreX: Float get() = (left + right) * 0.5f
        val centreY: Float get() = (top + bottom) * 0.5f
        val height: Float get() = bottom - top
    }

    /**
     * The off hand's pinch, which opens and closes the panel.
     *
     * Acted on when the pinch *releases*, and only if the drawing hand stayed open throughout.
     * Both hands pinching already means zoom, and a panel that toggled the instant the off hand
     * closed would fire on the way into every zoom.
     *
     * @return true when the panel opened or closed.
     */
    fun observeOffHand(offHandPinch: Boolean, dominantPinch: Boolean): Boolean {
        if (offHandPinch) {
            if (!offHandPinching) {
                offHandPinching = true
                vetoed = false
            }
            if (dominantPinch) vetoed = true
            return false
        }
        if (!offHandPinching) return false
        offHandPinching = false
        if (vetoed) return false
        isOpen = !isOpen
        return true
    }

    fun close() {
        isOpen = false
    }

    /** The link dropped, or the drawing hand changed: no pinch is in progress any more. */
    fun reset() {
        isOpen = false
        offHandPinching = false
        vetoed = false
    }

    /**
     * Every target, laid out down the dominant side.
     *
     * Computed rather than stored so that a resize, or the player switching hands, needs no
     * invalidation step - there is no cached layout to go stale.
     */
    fun cells(dominant: AepHandHandedness?, aspect: Float): List<Cell> {
        val count = tools.colours.size + tools.widths.size + 1
        val left = if (dominant == AepHandHandedness.LEFT) 0f else 1f - stripWidth
        val right = left + stripWidth
        val top = aspect * MARGIN
        val cellHeight = (aspect * (1f - 2f * MARGIN)) / count

        return buildList(count) {
            var y = top
            for (index in tools.colours.indices) {
                add(Cell(Kind.COLOUR, index, left, y, right, y + cellHeight)); y += cellHeight
            }
            for (index in tools.widths.indices) {
                add(Cell(Kind.WIDTH, index, left, y, right, y + cellHeight)); y += cellHeight
            }
            add(Cell(Kind.UNDO, 0, left, y, right, y + cellHeight))
        }
    }

    /** The cell under the drawing hand, or null when it is over the canvas. */
    fun cellAt(x: Float, y: Float, dominant: AepHandHandedness?, aspect: Float): Cell? {
        if (!isOpen) return null
        return cells(dominant, aspect).firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
    }

    /**
     * Commit the cell under the hand.
     *
     * Selecting closes the panel: one gesture picks and puts away, rather than leaving the player
     * to work out how to dismiss a thing they have finished with.
     *
     * @return the cell that was taken, or null if the hand was not over one.
     */
    fun select(cell: Cell?, nowMs: Long): Cell? {
        if (cell == null) return null
        when (cell.kind) {
            Kind.COLOUR -> tools.selectColour(cell.index, nowMs)
            Kind.WIDTH -> tools.selectWidth(cell.index, nowMs)
            Kind.UNDO -> Unit // The caller owns the drawing; it undoes.
        }
        isOpen = false
        return cell
    }

    private companion object {
        /** Top and bottom breathing room, as a fraction of the screen's height. */
        const val MARGIN = 0.06f
    }
}

/**
 * Which hand draws.
 *
 * Asked once, at the start, by the only means available before anything else is set up: the
 * player pinches with the hand they draw with. No menu, no remote, and nothing to read from
 * across the room.
 */
class DominantHand {
    var choice: AepHandHandedness? = null
        private set

    val isChosen: Boolean get() = choice != null

    /**
     * @param lonePinching a single hand the provider would not place is pinching.
     * @return true at the moment the choice is made.
     */
    fun observe(leftPinching: Boolean, rightPinching: Boolean, lonePinching: Boolean = false): Boolean {
        if (choice != null) return false
        // Both at once says nothing, and neither says nothing. Waiting is the correct answer to
        // an ambiguous question asked once.
        choice = when {
            leftPinching && !rightPinching -> AepHandHandedness.LEFT
            rightPinching && !leftPinching -> AepHandHandedness.RIGHT
            // A hand with no side, and no other hand to compare it to. Which one it is cannot be
            // known, but it is the only hand there is, so it is the pen either way: HandInput
            // hands the unplaced hand back for whichever side is asked for.
            lonePinching && !leftPinching && !rightPinching -> AepHandHandedness.RIGHT
            else -> return false
        }
        return true
    }

    fun forget() {
        choice = null
    }
}
