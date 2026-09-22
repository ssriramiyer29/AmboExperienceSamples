package com.ambokit.aep.airdraw

import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSurfaceTest {

    @Test
    fun `selecting a colour keeps it and shows it briefly`() {
        val tools = ToolSurface()
        tools.selectColour(3, 1_000L)
        assertEquals(tools.colours[3], tools.colour)
        assertTrue(tools.isFlashing(1_100L), "the player needs to see what they just picked")
        assertFalse(tools.isFlashing(5_000L), "and then they need the canvas back")
    }

    @Test
    fun `a selection outside the palette changes nothing`() {
        val tools = ToolSurface()
        val before = tools.colour
        // Not defensiveness for its own sake: cells are laid out from these same arrays, so an
        // index that does not exist means the layout and the palette have come apart, and
        // silently drawing in colour zero would hide that.
        tools.selectColour(99, 0L)
        tools.selectColour(-1, 0L)
        assertEquals(before, tools.colour)
        assertFalse(tools.isFlashing(1L), "nothing happened, so nothing should be shown")
    }
}

/**
 * Opening the panel with one hand and using it with the other.
 *
 * These are the rules that keep the tools from appearing every time someone zooms, and from
 * staying up after they have finished with them.
 */
class ToolPanelTest {

    private val aspect = 9f / 16f

    @Test
    fun `the off hand opens the panel when it lets go`() {
        val panel = ToolPanel()
        assertFalse(panel.isOpen)

        // Pinching alone does nothing yet: the drawing hand might be about to join in, which
        // would make this a zoom.
        assertFalse(panel.observeOffHand(offHandPinch = true, dominantPinch = false))
        assertFalse(panel.isOpen)

        assertTrue(panel.observeOffHand(offHandPinch = false, dominantPinch = false))
        assertTrue(panel.isOpen)
    }

    @Test
    fun `the same gesture closes it again`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false)
        panel.observeOffHand(false, false)
        assertTrue(panel.isOpen)

        panel.observeOffHand(true, false)
        assertTrue(panel.observeOffHand(false, false))
        assertFalse(panel.isOpen)
    }

    @Test
    fun `a zoom does not open the panel`() {
        val panel = ToolPanel()
        // The off hand pinches, then the drawing hand joins it: that is the two-handed zoom, and
        // the tools must stay out of the way of it.
        panel.observeOffHand(offHandPinch = true, dominantPinch = false)
        panel.observeOffHand(offHandPinch = true, dominantPinch = true)
        panel.observeOffHand(offHandPinch = true, dominantPinch = true)
        assertFalse(panel.observeOffHand(offHandPinch = false, dominantPinch = false))
        assertFalse(panel.isOpen, "letting go after a zoom must not leave the tools on screen")
    }

    @Test
    fun `the veto is forgotten by the next pinch`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, true)
        panel.observeOffHand(false, false)
        assertFalse(panel.isOpen)

        // A clean pinch after a zoom still works. Without resetting the veto, the panel would be
        // unreachable for the rest of the session after the first zoom.
        panel.observeOffHand(true, false)
        assertTrue(panel.observeOffHand(false, false))
        assertTrue(panel.isOpen)
    }

    @Test
    fun `the panel sits on the drawing hand's side`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false); panel.observeOffHand(false, false)

        val right = panel.cells(AepHandHandedness.RIGHT, aspect)
        assertTrue(right.all { it.right > 0.99f }, "a right-hander's tools belong on the right")

        val left = panel.cells(AepHandHandedness.LEFT, aspect)
        assertTrue(left.all { it.left < 0.01f }, "and a left-hander's on the left")
    }

    @Test
    fun `every tool has a cell, and they do not overlap`() {
        val panel = ToolPanel()
        val cells = panel.cells(AepHandHandedness.RIGHT, aspect)
        assertEquals(panel.tools.colours.size + panel.tools.widths.size + 1, cells.size)

        for (index in 1 until cells.size) {
            // Touching edges are fine; overlap is not, or one target would sit on top of another
            // and the player would pick something they were not pointing at.
            assertTrue(cells[index].top >= cells[index - 1].bottom - 1e-4f,
                "cell $index overlaps the one above it")
        }
        assertTrue(cells.last().bottom <= aspect, "the strip must fit on the screen")
    }

    @Test
    fun `nothing is hittable while the panel is shut`() {
        val panel = ToolPanel()
        val cell = panel.cells(AepHandHandedness.RIGHT, aspect).first()
        // The hand is exactly on a target, and the target is not there. A closed panel that still
        // accepted selections would change colour whenever someone drew near that edge.
        assertNull(panel.cellAt(cell.centreX, cell.centreY, AepHandHandedness.RIGHT, aspect))
    }

    @Test
    fun `the hand finds the cell it is over`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false); panel.observeOffHand(false, false)

        val cells = panel.cells(AepHandHandedness.RIGHT, aspect)
        for (expected in cells) {
            val found = panel.cellAt(expected.centreX, expected.centreY, AepHandHandedness.RIGHT, aspect)
            assertNotNull(found)
            assertEquals(expected.kind, found.kind)
            assertEquals(expected.index, found.index)
        }
    }

    @Test
    fun `a hand over the canvas is over nothing`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false); panel.observeOffHand(false, false)
        assertNull(panel.cellAt(0.4f, aspect * 0.5f, AepHandHandedness.RIGHT, aspect))
    }

    @Test
    fun `selecting takes the tool and puts the panel away`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false); panel.observeOffHand(false, false)

        val colour = panel.cells(AepHandHandedness.RIGHT, aspect)
            .first { it.kind == ToolPanel.Kind.COLOUR && it.index == 4 }
        assertEquals(colour, panel.select(colour, 0L))
        assertEquals(panel.tools.colours[4], panel.tools.colour)
        assertFalse(panel.isOpen, "picking is finishing; the player should not have to dismiss it")
    }

    @Test
    fun `selecting nothing leaves the panel alone`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false); panel.observeOffHand(false, false)
        // Pinching over the canvas while the panel is open should not close it - the player has
        // not chosen anything yet.
        assertNull(panel.select(null, 0L))
        assertTrue(panel.isOpen)
    }

    @Test
    fun `undo is a cell like any other, and the panel closes behind it`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false); panel.observeOffHand(false, false)
        val undo = panel.cells(AepHandHandedness.RIGHT, aspect).first { it.kind == ToolPanel.Kind.UNDO }

        val colourBefore = panel.tools.colour
        assertEquals(undo, panel.select(undo, 0L))
        assertEquals(colourBefore, panel.tools.colour, "undo must not change the pen")
        assertFalse(panel.isOpen)
    }

    @Test
    fun `a dropped link leaves no pinch half-finished`() {
        val panel = ToolPanel()
        panel.observeOffHand(true, false)
        panel.reset()
        assertFalse(panel.isOpen)
        // The interrupted pinch is forgotten, so the hand coming back does not release into a
        // toggle nobody asked for.
        assertFalse(panel.observeOffHand(false, false))
        assertFalse(panel.isOpen)
    }
}

class DominantHandTest {

    @Test
    fun `the first hand to pinch alone is the drawing hand`() {
        val hand = DominantHand()
        assertFalse(hand.isChosen)

        assertTrue(hand.observe(leftPinching = false, rightPinching = true))
        assertEquals(AepHandHandedness.RIGHT, hand.choice)
    }

    @Test
    fun `a left-hander is heard`() {
        val hand = DominantHand()
        hand.observe(leftPinching = true, rightPinching = false)
        assertEquals(AepHandHandedness.LEFT, hand.choice)
    }

    @Test
    fun `an ambiguous answer is not an answer`() {
        val hand = DominantHand()
        assertFalse(hand.observe(leftPinching = false, rightPinching = false))
        // Both at once says nothing. Guessing here would hand someone the wrong drawing hand for
        // the whole session, off a gesture they did not mean to make.
        assertFalse(hand.observe(leftPinching = true, rightPinching = true))
        assertFalse(hand.isChosen)

        assertTrue(hand.observe(leftPinching = true, rightPinching = false))
        assertEquals(AepHandHandedness.LEFT, hand.choice)
    }

    @Test
    fun `a hand with no side still counts, when it is the only hand`() {
        val hand = DominantHand()
        // A provider that reports `unknown` and one hand on screen. Which hand it is cannot be
        // known - but it is the only hand there is, so it is the pen either way.
        assertTrue(hand.observe(leftPinching = false, rightPinching = false, lonePinching = true))
        assertTrue(hand.isChosen)
    }

    @Test
    fun `a placed hand outranks an unplaced one`() {
        val hand = DominantHand()
        // Both cannot really happen at once, but if a provider does send it, the hand it could
        // name is the better answer than the one it could not.
        hand.observe(leftPinching = true, rightPinching = false, lonePinching = true)
        assertEquals(AepHandHandedness.LEFT, hand.choice)
    }

    @Test
    fun `the choice is made once and then left alone`() {
        val hand = DominantHand()
        hand.observe(leftPinching = false, rightPinching = true)
        // The off hand pinches constantly from here on - it is what opens the tools - and must
        // never take the pen away.
        assertFalse(hand.observe(leftPinching = true, rightPinching = false))
        assertEquals(AepHandHandedness.RIGHT, hand.choice)
    }
}
