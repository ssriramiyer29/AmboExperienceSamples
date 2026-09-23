package com.ambokit.aep.airdraw

import com.ambokit.aep.core.capabilities.AepHandHandedness

/**
 * The palette: a strip down one edge, always there.
 *
 * An earlier version hid this behind a gesture - the off hand pinched to open and close it. On a
 * television at three metres that failed in the worst way available: the gesture fired when it
 * should not have, and then would not fire when it should, so a wrong colour could be selected
 * and not unselected. A control you cannot get back to is worse than one that takes up space.
 *
 * So it takes up space. Six percent of the width, permanently, and the remaining ninety-four is
 * canvas. Nothing to open, nothing to remember, and nothing that can strand you.
 */
class ToolStrip(
    val colours: IntArray = AirDrawEngine.DEFAULT_COLOURS,
    var width: Float = 0.06f
) {
    /** Which edge the strip lives on. Changed by the player, not decided for them. */
    var side: AepHandHandedness = AepHandHandedness.RIGHT
        private set

    var colourIndex: Int = 0
        private set

    val colour: Int get() = colours[colourIndex]

    enum class Kind { COLOUR, UNDO, FLIP }

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

    /** Whether a point is over the strip at all - the test that decides mark from selection. */
    fun contains(x: Float): Boolean =
        if (side == AepHandHandedness.LEFT) x <= width else x >= 1f - width

    /**
     * Every target, top to bottom.
     *
     * Computed rather than stored, so flipping sides or resizing needs no invalidation step:
     * there is no cached layout to go stale.
     */
    fun cells(aspect: Float): List<Cell> {
        val count = colours.size + 2
        val left = if (side == AepHandHandedness.LEFT) 0f else 1f - width
        val right = left + width
        val top = aspect * MARGIN
        val cellHeight = (aspect * (1f - 2f * MARGIN)) / count

        return buildList(count) {
            var y = top
            for (index in colours.indices) {
                add(Cell(Kind.COLOUR, index, left, y, right, y + cellHeight)); y += cellHeight
            }
            add(Cell(Kind.UNDO, 0, left, y, right, y + cellHeight)); y += cellHeight
            add(Cell(Kind.FLIP, 0, left, y, right, y + cellHeight))
        }
    }

    fun cellAt(x: Float, y: Float, aspect: Float): Cell? =
        cells(aspect).firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    /**
     * Take the cell.
     *
     * @return the cell taken, or null if there was nothing there. UNDO is handed back rather than
     *   acted on: the drawing belongs to the caller.
     */
    fun select(cell: Cell?): Cell? {
        if (cell == null) return null
        when (cell.kind) {
            Kind.COLOUR -> colourIndex = cell.index
            Kind.FLIP -> side =
                if (side == AepHandHandedness.LEFT) AepHandHandedness.RIGHT else AepHandHandedness.LEFT
            Kind.UNDO -> Unit
        }
        return cell
    }

    private companion object {
        /** Top and bottom breathing room, as a fraction of the screen's height. */
        const val MARGIN = 0.04f
    }
}
