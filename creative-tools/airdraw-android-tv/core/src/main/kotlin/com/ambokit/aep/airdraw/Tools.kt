package com.ambokit.aep.airdraw

/**
 * The toolbar: a band across the bottom, always there.
 *
 * **Always there, because of what happened when it wasn't.** An early version hid this behind a
 * gesture - the off hand pinched to open and close it. On a television at three metres that failed
 * in the worst way available: the gesture fired when it should not have, and then would not fire
 * when it should, so a wrong colour could be selected and not unselected. A control you cannot get
 * back to is worse than one that takes up space. That decision is closed.
 *
 * **Across the bottom, rather than down one edge.** The edge strip worked and read badly. Twelve
 * targets stacked in a narrow column is a wall, reported from a device as "very cluttered", and
 * the cells were already the largest the geometry allowed - the problem was the shape, not the
 * size. Laid out horizontally there is room to *group*: colours together, sizes together, actions
 * together, with space between the groups doing the explaining that a uniform grid could not.
 *
 * It also settles a question by removing it. An edge strip has to be on somebody's left or right,
 * which is a choice to offer, explain and remember. A bottom band has no handedness.
 */
class ToolStrip(
    val colours: IntArray = AirDrawEngine.DEFAULT_COLOURS,
    /**
     * The band's height, as a fraction of the screen's *width* - the same unit everything else in
     * pointer space uses, so it means the same thing on any television.
     *
     * 0.10 is 192px on a 1080-line screen, about a sixth of its height. Deep enough that a target
     * is comfortably taller than the tens of pixels hand tracking wanders at three metres, shallow
     * enough to leave the drawing the great majority of the screen.
     */
    var barHeight: Float = 0.10f
) {
    var colourIndex: Int = 0
        private set

    val colour: Int get() = colours[colourIndex]

    /** Which brush width is selected, as an index into [AirDrawEngine.DEFAULT_WIDTHS]. */
    var sizeIndex: Int = 1
        private set

    var brushIndex: Int = 0
        private set

    val brush: BrushType get() = BRUSHES[brushIndex]

    /**
     * Whether the eraser is selected.
     *
     * The eraser is a colour, not a mode of the engine: it paints in the paper colour. That is why
     * it sits at the end of the colours rather than with the actions, and why picking any colour
     * puts it away - reaching for red while still erasing would otherwise erase in red.
     */
    var erasing: Boolean = false
        private set

    enum class Kind { COLOUR, ERASER, SIZE, BRUSH, UNDO, CLEAR, SAVE }

    /**
     * Which cell the remote is on, or [NO_FOCUS] when the remote is not on the toolbar at all.
     *
     * The toolbar is reachable two ways and they do not interfere. A hand pinches a cell
     * directly; a remote walks along the band. Having both matters more than it sounds: reaching
     * down to the bottom of the screen is the furthest a drawing arm has to go, and on a device
     * it was the last thing to become reliable. A remote makes picking a colour a button press
     * whether or not the reach is working that day.
     *
     * A left-right axis is the whole reason this is cheap. Down the old edge strip the natural
     * D-pad direction was the same one that scrolls everything else on a television.
     */
    var focusIndex: Int = NO_FOCUS
        private set

    val hasFocus: Boolean get() = focusIndex != NO_FOCUS

    fun focusedCell(aspect: Float): Cell? = cells(aspect).getOrNull(focusIndex)

    /** Step onto the toolbar, at whichever cell is already selected where that makes sense. */
    fun focusEnter(aspect: Float) {
        if (hasFocus) return
        val cells = cells(aspect)
        // Landing on the colour already in use is less disorienting than always landing at the
        // far left, and it puts the neighbouring colours one press away.
        focusIndex = cells.indexOfFirst { it.kind == Kind.COLOUR && it.index == colourIndex }
            .takeIf { it >= 0 } ?: 0
    }

    fun focusClear() {
        focusIndex = NO_FOCUS
    }

    /**
     * Move along the band.
     *
     * Stops at the ends rather than wrapping. Wrapping a row of targets on a television means a
     * press at the right-hand end silently jumps the eye back to the left, and the one control
     * here that costs something - Clear - sits at that end.
     */
    fun focusMove(delta: Int, aspect: Float): Boolean {
        // Stepping sideways onto the band counts as stepping on.
        //
        // It used to require a down-press first, and a device log showed exactly what that costs:
        // somebody walked the toolbar, pressed up to leave, then pressed right fourteen times
        // expecting to be back on it. Nothing happened, because only one key was a door. Any
        // press that means "move along the toolbar" now means "be on the toolbar".
        if (!hasFocus) {
            focusEnter(aspect)
            return true
        }
        val last = cells(aspect).size - 1
        val next = (focusIndex + delta).coerceIn(0, last)
        if (next == focusIndex) return false
        focusIndex = next
        return true
    }

    /** Take whatever the remote is on, exactly as a pinch on it would. */
    fun focusSelect(aspect: Float): Cell? = select(focusedCell(aspect))

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
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** Where the band starts, in pointer units. Everything above this is canvas. */
    fun barTop(aspect: Float): Float = aspect - barHeight

    /** Whether a point is in the band at all - the test that decides mark from selection. */
    fun contains(y: Float, aspect: Float = DEFAULT_ASPECT): Boolean = y >= barTop(aspect)

    /** How much of the screen's height the drawing gets. */
    fun canvasAspect(aspect: Float): Float = barTop(aspect)

    /**
     * Every target, left to right.
     *
     * Laid out in three groups with a gap between them, and the gaps are the point: a person reads
     * "colours, then sizes, then actions" from the spacing without anything having to say so.
     * Computed rather than stored, so there is no cached layout to go stale.
     */
    fun cells(aspect: Float): List<Cell> {
        val groups = groups()
        val units = groups.sumOf { it.size } + (groups.size - 1) * GAP_UNITS
        val cellWidth = (1f - 2f * SIDE_MARGIN) / units
        val top = barTop(aspect) + barHeight * VERTICAL_INSET
        val bottom = aspect - barHeight * VERTICAL_INSET

        return buildList {
            var x = SIDE_MARGIN
            for ((groupIndex, group) in groups.withIndex()) {
                if (groupIndex > 0) x += cellWidth * GAP_UNITS
                for (entry in group) {
                    add(Cell(entry.first, entry.second, x, top, x + cellWidth, bottom))
                    x += cellWidth
                }
            }
        }
    }

    /**
     * The cell under a pointer, forgiving overshoot below the screen.
     *
     * Reaching *past* the bottom edge is not a miss - there is nothing down there to hit instead,
     * and an arm swinging at a row of targets along the bottom of a television naturally
     * overshoots. So y is pulled back into the band before the test. Deliberately only y and only
     * downward: the space above the band is canvas somebody may be drawing on.
     */
    fun cellAt(x: Float, y: Float, aspect: Float): Cell? {
        if (!contains(y, aspect)) return null
        // Clamped to the targets' own bottom edge, not the screen's: the band carries padding
        // below the cells, so clamping to the screen left an overshoot sitting in that padding
        // and hitting nothing - which is the exact miss this is here to prevent.
        val clampedY = y.coerceAtMost(aspect - barHeight * VERTICAL_INSET - 1e-4f)
        return cells(aspect).firstOrNull {
            x >= it.left && x <= it.right && clampedY >= it.top && clampedY <= it.bottom
        }
    }

    /**
     * Take the cell.
     *
     * @return the cell taken, or null if there was nothing there. [Kind.UNDO], [Kind.CLEAR] and
     *   [Kind.SAVE] are handed back rather than acted on: the drawing belongs to the caller, and
     *   so does the store it would be saved into.
     */
    fun select(cell: Cell?): Cell? {
        if (cell == null) return null
        when (cell.kind) {
            Kind.COLOUR -> {
                colourIndex = cell.index
                // Reaching for a colour is asking to draw in it. Leaving the eraser selected
                // would quietly erase in red.
                erasing = false
            }
            Kind.ERASER -> erasing = !erasing
            // Three sizes, each its own target, because a horizontal band has the room. The
            // vertical strip had to cycle one cell through all three, which is a worse control
            // and only ever existed because there was nowhere to put the other two.
            Kind.SIZE -> sizeIndex = cell.index.coerceIn(AirDrawEngine.DEFAULT_WIDTHS.indices)
            Kind.BRUSH -> brushIndex = (brushIndex + 1) % BRUSHES.size
            Kind.UNDO, Kind.CLEAR, Kind.SAVE -> Unit
        }
        return cell
    }

    /** The selected width, in document space. */
    val strokeWidth: Float get() = AirDrawEngine.DEFAULT_WIDTHS[sizeIndex]

    /** Put the pen back as it started. */
    fun resetPen() {
        colourIndex = 0
        sizeIndex = 1
        brushIndex = 0
        erasing = false
    }

    private fun groups(): List<List<Pair<Kind, Int>>> = listOf(
        colours.indices.map { Kind.COLOUR to it } + (Kind.ERASER to 0),
        AirDrawEngine.DEFAULT_WIDTHS.indices.map { Kind.SIZE to it } + (Kind.BRUSH to 0),
        ACTION_KINDS.map { it to 0 }
    )

    companion object {
        val BRUSHES = listOf(BrushType.MARKER, BrushType.PEN, BrushType.CRAYON)

        /** The things that act on the drawing rather than on the pen. */
        val ACTION_KINDS = listOf(Kind.UNDO, Kind.CLEAR, Kind.SAVE)

        /**
         * Smallest a target may be, in pointer units, before it stops being hittable.
         *
         * 100px on a 1920-wide screen, which is where `ToolStripTest` has held the line since a
         * width picker was squeezed into the old vertical strip and had to be taken out again.
         * Hand tracking at three metres wanders by tens of pixels, so a target of a few tens is
         * one nobody can land on. It applies to both dimensions now that the band is horizontal.
         */
        const val MINIMUM_CELL_SIZE = 100f / 1920f

        /** The remote is not on the toolbar. */
        const val NO_FOCUS = -1

        /** 16:9, the only shape a television is. Used where a caller has no aspect to hand. */
        const val DEFAULT_ASPECT = 9f / 16f

        /** Breathing room at each end of the band, as a fraction of the screen's width. */
        const val SIDE_MARGIN = 0.02f

        /** The gap between groups, measured in cell widths. */
        const val GAP_UNITS = 1

        /** How much of the band's height is padding above and below the targets. */
        const val VERTICAL_INSET = 0.12f
    }
}
