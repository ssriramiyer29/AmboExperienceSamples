package com.ambokit.aep.airdraw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolStripTest {

    private val aspect = 9f / 16f

    // ---- what is on the band ------------------------------------------------------------------

    @Test
    fun `every colour, every size and every action has exactly one cell`() {
        val strip = ToolStrip()
        val cells = strip.cells(aspect)

        for (index in strip.colours.indices) {
            assertEquals(1, cells.count { it.kind == ToolStrip.Kind.COLOUR && it.index == index },
                "colour $index")
        }
        for (index in AirDrawEngine.DEFAULT_WIDTHS.indices) {
            assertEquals(1, cells.count { it.kind == ToolStrip.Kind.SIZE && it.index == index },
                "size $index")
        }
        // Read off the list rather than named here, so adding an action cannot leave this test
        // passing while the new one has no cell.
        for (kind in ToolStrip.ACTION_KINDS) {
            assertEquals(1, cells.count { it.kind == kind }, "action $kind")
        }
        assertEquals(1, cells.count { it.kind == ToolStrip.Kind.ERASER })
        assertEquals(1, cells.count { it.kind == ToolStrip.Kind.BRUSH })
    }

    @Test
    fun `the eraser sits with the colours, because it is one`() {
        val cells = ToolStrip().cells(aspect)
        val lastColour = cells.last { it.kind == ToolStrip.Kind.COLOUR }
        val eraser = cells.first { it.kind == ToolStrip.Kind.ERASER }
        assertTrue(eraser.left >= lastColour.right - 1e-4f, "the eraser follows the colours")
        val firstSize = cells.first { it.kind == ToolStrip.Kind.SIZE }
        assertTrue(eraser.right <= firstSize.left + 1e-4f, "and comes before the sizes")
    }

    @Test
    fun `the groups are separated by more space than the cells within them`() {
        // The gaps are what makes three groups readable without anything saying so.
        val cells = ToolStrip().cells(aspect)
        val withinGroup = cells[1].left - cells[0].right
        val eraser = cells.first { it.kind == ToolStrip.Kind.ERASER }
        val firstSize = cells.first { it.kind == ToolStrip.Kind.SIZE }
        assertTrue(firstSize.left - eraser.right > withinGroup + 0.01f,
            "a group boundary must read as a break")
    }

    // ---- geometry ------------------------------------------------------------------------------

    @Test
    fun `cells do not overlap and fit on the screen`() {
        val cells = ToolStrip().cells(aspect)
        for (a in cells.indices) {
            for (b in a + 1 until cells.size) {
                val one = cells[a]
                val two = cells[b]
                val overlaps = one.left < two.right - 1e-4f && two.left < one.right - 1e-4f &&
                    one.top < two.bottom - 1e-4f && two.top < one.bottom - 1e-4f
                assertTrue(!overlaps, "cell $a (${one.kind}) overlaps cell $b (${two.kind})")
            }
        }
        assertTrue(cells.minOf { it.left } >= 0f)
        assertTrue(cells.maxOf { it.right } <= 1f + 1e-4f)
        assertTrue(cells.maxOf { it.bottom } <= aspect + 1e-4f)
    }

    @Test
    fun `the targets are big enough to hit from a sofa`() {
        val cells = ToolStrip().cells(aspect)
        // Hand tracking at three metres wanders by tens of pixels, so a target of a few tens is
        // unhittable - which is why a width picker was once added to this toolbar and removed
        // again. The bar is horizontal now, so both dimensions have to clear the line.
        val shortest = cells.minOf { it.height } * 1920f
        val narrowest = cells.minOf { it.width } * 1920f
        assertTrue(shortest > 100f, "shortest cell is only ${shortest}px tall")
        assertTrue(narrowest > 100f, "narrowest cell is only ${narrowest}px wide")
    }

    @Test
    fun `the drawing keeps the great majority of the screen`() {
        val strip = ToolStrip()
        val canvasShare = strip.canvasAspect(aspect) / aspect
        assertTrue(canvasShare > 0.80f, "the canvas only gets ${canvasShare * 100}% of the height")
    }

    @Test
    fun `adding a tool would be caught rather than quietly shrinking the targets`() {
        // The rule the layout is held to. A band with far more colours than it ships with is the
        // cheapest way to prove the arithmetic is checked rather than happening to fit today.
        val crowded = ToolStrip(colours = IntArray(24) { it })
        val narrowest = crowded.cells(aspect).minOf { it.width } * 1920f
        assertTrue(narrowest < 100f,
            "this test is meant to demonstrate the failure; if it passes the bound moved")
    }

    // ---- hit testing ---------------------------------------------------------------------------

    @Test
    fun `the hand finds the cell it is over`() {
        val strip = ToolStrip()
        for (expected in strip.cells(aspect)) {
            val found = strip.cellAt(expected.centreX, expected.centreY, aspect)
            assertNotNull(found, "nothing found at ${expected.kind}")
            assertEquals(expected.kind, found.kind)
            assertEquals(expected.index, found.index)
        }
    }

    @Test
    fun `a pinch on the canvas hits nothing`() {
        val strip = ToolStrip()
        assertNull(strip.cellAt(0.5f, aspect * 0.4f, aspect))
        assertNull(strip.cellAt(0.5f, 0.02f, aspect))
    }

    @Test
    fun `reaching below the screen still hits the band`() {
        // An arm swinging at a row of targets along the bottom of a television overshoots it, and
        // there is nothing down there to hit instead.
        val strip = ToolStrip()
        val cell = strip.cells(aspect).first { it.kind == ToolStrip.Kind.UNDO }
        assertEquals(ToolStrip.Kind.UNDO, strip.cellAt(cell.centreX, aspect + 0.3f, aspect)?.kind)
    }

    @Test
    fun `the band knows where the canvas ends`() {
        val strip = ToolStrip(barHeight = 0.10f)
        assertTrue(!strip.contains(strip.barTop(aspect) - 0.01f, aspect))
        assertTrue(strip.contains(strip.barTop(aspect) + 0.01f, aspect))
        assertEquals(aspect - 0.10f, strip.canvasAspect(aspect))
    }

    // ---- selection -----------------------------------------------------------------------------

    @Test
    fun `taking a colour keeps it`() {
        val strip = ToolStrip()
        val cell = strip.cells(aspect).first { it.kind == ToolStrip.Kind.COLOUR && it.index == 3 }
        strip.select(cell)
        assertEquals(3, strip.colourIndex)
        assertEquals(strip.colours[3], strip.colour)
    }

    @Test
    fun `each size is its own target rather than a cycle`() {
        // The vertical strip had to cycle one cell through all three widths, which is a worse
        // control and only existed because there was nowhere to put the other two.
        val strip = ToolStrip()
        for (index in AirDrawEngine.DEFAULT_WIDTHS.indices) {
            strip.select(strip.cells(aspect).first { it.kind == ToolStrip.Kind.SIZE && it.index == index })
            assertEquals(AirDrawEngine.DEFAULT_WIDTHS[index], strip.strokeWidth)
        }
    }

    @Test
    fun `undo, clear and save are handed back rather than acted on`() {
        val strip = ToolStrip()
        for (kind in ToolStrip.ACTION_KINDS) {
            val taken = strip.select(strip.cells(aspect).first { it.kind == kind })
            assertEquals(kind, taken?.kind)
        }
        // None of them touched the pen.
        assertEquals(0, strip.colourIndex)
        assertEquals(1, strip.sizeIndex)
        assertTrue(!strip.erasing)
    }

    @Test
    fun `nothing is selected by a pinch that hits no cell`() {
        assertNull(ToolStrip().select(null))
    }

    // ---- the remote ----------------------------------------------------------------------------

    @Test
    fun `the remote is not on the toolbar until something puts it there`() {
        val strip = ToolStrip()
        assertTrue(!strip.hasFocus)
        assertNull(strip.focusedCell(aspect))
    }

    /**
     * A device log showed what requiring one specific key costs: somebody walked the toolbar,
     * pressed up to leave, then pressed right fourteen times expecting to be back on it. Nothing
     * happened, because only the down key was a door.
     */
    @Test
    fun `moving sideways is a way onto the toolbar, not only a way along it`() {
        val strip = ToolStrip()
        assertTrue(strip.focusMove(1, aspect), "a sideways press with nothing focused steps on")
        assertTrue(strip.hasFocus)
        assertNotNull(strip.focusedCell(aspect))

        strip.focusClear()
        assertTrue(strip.focusMove(-1, aspect), "and so does the other direction")
        assertTrue(strip.hasFocus)
    }

    @Test
    fun `stepping on lands on the colour already in use`() {
        val strip = ToolStrip()
        strip.select(strip.cells(aspect).first { it.kind == ToolStrip.Kind.COLOUR && it.index == 3 })
        strip.focusEnter(aspect)
        val cell = strip.focusedCell(aspect)
        assertEquals(ToolStrip.Kind.COLOUR, cell?.kind)
        assertEquals(3, cell?.index, "landing at the far left every time is disorienting")
    }

    @Test
    fun `the remote walks the whole band`() {
        val strip = ToolStrip()
        strip.focusEnter(aspect)
        while (strip.focusMove(-1, aspect)) { /* to the left-hand end */ }
        val seen = mutableListOf(strip.focusedCell(aspect)!!.kind)
        while (strip.focusMove(1, aspect)) seen += strip.focusedCell(aspect)!!.kind
        assertEquals(strip.cells(aspect).size, seen.size, "every cell must be reachable")
        assertTrue(ToolStrip.Kind.SAVE in seen)
        assertTrue(ToolStrip.Kind.COLOUR in seen)
    }

    @Test
    fun `the ends stop rather than wrapping`() {
        // Clear sits at the right-hand end, and a press that silently jumps the eye back to the
        // far left is how somebody lands on it by accident.
        val strip = ToolStrip()
        strip.focusEnter(aspect)
        while (strip.focusMove(1, aspect)) { /* to the right-hand end */ }
        val atEnd = strip.focusIndex
        assertTrue(!strip.focusMove(1, aspect))
        assertEquals(atEnd, strip.focusIndex)
    }

    @Test
    fun `the remote takes a cell exactly as a pinch would`() {
        val strip = ToolStrip()
        strip.focusEnter(aspect)
        while (strip.focusedCell(aspect)?.kind != ToolStrip.Kind.ERASER) strip.focusMove(1, aspect)
        assertEquals(ToolStrip.Kind.ERASER, strip.focusSelect(aspect)?.kind)
        assertTrue(strip.erasing)
    }

    @Test
    fun `stepping off leaves the toolbar alone`() {
        val strip = ToolStrip()
        strip.focusEnter(aspect)
        strip.focusMove(1, aspect)
        strip.focusClear()
        assertTrue(!strip.hasFocus)
        assertNull(strip.focusSelect(aspect), "nothing is focused, so nothing is taken")
    }
}
