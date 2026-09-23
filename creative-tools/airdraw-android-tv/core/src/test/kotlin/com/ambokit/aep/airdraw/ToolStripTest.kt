package com.ambokit.aep.airdraw

import com.ambokit.aep.core.capabilities.AepHandHandedness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The palette that is always there.
 *
 * Every rule here exists because the version that hid behind a gesture could strand a player on
 * a colour they did not choose.
 */
class ToolStripTest {

    private val aspect = 9f / 16f

    @Test
    fun `the strip is on the right by default, and the canvas is the rest`() {
        val strip = ToolStrip()
        assertTrue(strip.contains(0.98f))
        assertFalse(strip.contains(0.90f), "ninety percent of the width must still be canvas")
        assertFalse(strip.contains(0.02f))
    }

    @Test
    fun `flipping moves the whole thing to the other edge`() {
        val strip = ToolStrip()
        val flip = strip.cells(aspect).first { it.kind == ToolStrip.Kind.FLIP }
        strip.select(flip)

        assertEquals(AepHandHandedness.LEFT, strip.side)
        assertTrue(strip.contains(0.02f))
        assertFalse(strip.contains(0.98f))
        assertTrue(strip.cells(aspect).all { it.left < 0.07f }, "the cells must move with it")
    }

    @Test
    fun `flipping back is the same cell again`() {
        val strip = ToolStrip()
        repeat(2) { strip.select(strip.cells(aspect).first { it.kind == ToolStrip.Kind.FLIP }) }
        // A left-hander who flips, and a right-hander who flipped by accident, need the same way
        // out. There is exactly one control and it is its own undo.
        assertEquals(AepHandHandedness.RIGHT, strip.side)
    }

    @Test
    fun `every colour has a cell, plus undo and flip`() {
        val strip = ToolStrip()
        val cells = strip.cells(aspect)
        assertEquals(strip.colours.size + 2, cells.size)
        assertEquals(1, cells.count { it.kind == ToolStrip.Kind.UNDO })
        assertEquals(1, cells.count { it.kind == ToolStrip.Kind.FLIP })
    }

    @Test
    fun `cells do not overlap and fit on the screen`() {
        val strip = ToolStrip()
        val cells = strip.cells(aspect)
        for (index in 1 until cells.size) {
            assertTrue(cells[index].top >= cells[index - 1].bottom - 1e-4f,
                "cell $index overlaps the one above it")
        }
        assertTrue(cells.first().top >= 0f)
        assertTrue(cells.last().bottom <= aspect)
    }

    @Test
    fun `the targets are big enough to hit from a sofa`() {
        val strip = ToolStrip()
        val cells = strip.cells(aspect)
        // On a 1920-wide screen, pointer units multiply by 1920. Hand tracking at three metres
        // wanders by tens of pixels, so a target of a few tens would be unhittable - which is
        // why the width picker was dropped rather than squeezed in.
        val shortestPx = cells.minOf { it.height } * 1920f
        assertTrue(shortestPx > 100f, "shortest cell is only ${shortestPx}px tall")
    }

    @Test
    fun `a pinch on the canvas hits nothing`() {
        val strip = ToolStrip()
        assertNull(strip.cellAt(0.5f, aspect * 0.5f, aspect))
    }

    @Test
    fun `the hand finds the cell it is over`() {
        val strip = ToolStrip()
        for (expected in strip.cells(aspect)) {
            val found = strip.cellAt(expected.centreX, expected.centreY, aspect)
            assertNotNull(found)
            assertEquals(expected.kind, found.kind)
            assertEquals(expected.index, found.index)
        }
    }

    @Test
    fun `taking a colour keeps it`() {
        val strip = ToolStrip()
        val cell = strip.cells(aspect).first { it.kind == ToolStrip.Kind.COLOUR && it.index == 3 }
        assertEquals(cell, strip.select(cell))
        assertEquals(strip.colours[3], strip.colour)
        // And it is reachable again immediately - nothing closed, nothing to reopen. This is the
        // property the previous design lacked, and the reason it was replaced.
        val other = strip.cells(aspect).first { it.kind == ToolStrip.Kind.COLOUR && it.index == 0 }
        strip.select(other)
        assertEquals(strip.colours[0], strip.colour)
    }

    @Test
    fun `undo is handed back rather than acted on`() {
        val strip = ToolStrip()
        val before = strip.colour
        val undo = strip.cells(aspect).first { it.kind == ToolStrip.Kind.UNDO }
        assertEquals(ToolStrip.Kind.UNDO, strip.select(undo)?.kind)
        assertEquals(before, strip.colour, "undo must not change the pen")
    }
}

/**
 * Zoom, and specifically zoom that holds still.
 *
 * The first version measured frame to frame, so every frame's tracking noise was applied and then
 * kept. These are the tests that would have caught that.
 */
class ZoomGestureTest {

    private fun hand(x: Float, y: Float) = HandPointer(
        handedness = AepHandHandedness.LEFT, x = x, y = y, pinch = 1f, confidence = 1f
    )

    @Test
    fun `nothing happens without two pinching hands`() {
        val zoom = ZoomGesture()
        assertNull(zoom.update(hand(0.3f, 0.3f), null, true))
        assertNull(zoom.update(hand(0.3f, 0.3f), hand(0.7f, 0.3f), false))
        assertFalse(zoom.isActive)
    }

    @Test
    fun `the first frame only takes the anchor`() {
        val zoom = ZoomGesture()
        // Grabbing must not move anything. The change has to be measured from somewhere, and that
        // somewhere is this frame.
        assertNull(zoom.update(hand(0.3f, 0.3f), hand(0.7f, 0.3f), true))
        assertTrue(zoom.isActive)
    }

    @Test
    fun `pulling apart zooms in`() {
        val zoom = ZoomGesture()
        zoom.update(hand(0.40f, 0.3f), hand(0.60f, 0.3f), true)
        var total = 1f
        // Several frames, because the separation is low-passed and does not arrive all at once.
        repeat(12) {
            zoom.update(hand(0.30f, 0.3f), hand(0.70f, 0.3f), true)?.let { total *= it.scale }
        }
        assertTrue(total > 1.5f, "separation doubled; total scale was only $total")
    }

    @Test
    fun `bringing together zooms out`() {
        val zoom = ZoomGesture()
        zoom.update(hand(0.30f, 0.3f), hand(0.70f, 0.3f), true)
        var total = 1f
        repeat(12) {
            zoom.update(hand(0.40f, 0.3f), hand(0.60f, 0.3f), true)?.let { total *= it.scale }
        }
        assertTrue(total < 0.7f, "separation halved; total scale was $total")
    }

    @Test
    fun `hands held still do not drift`() {
        val zoom = ZoomGesture()
        var seed = 99L
        fun noise(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33).toFloat() / Int.MAX_VALUE.toFloat() - 0.5f) * 2f * 0.012f
        }

        zoom.update(hand(0.30f, 0.3f), hand(0.70f, 0.3f), true)
        var total = 1f
        var pan = 0f
        // Two hands held still for thirteen seconds at 15 Hz, with the landmark noise a real
        // camera has. Measuring frame to frame turns this into a random walk and the canvas
        // crawls; against a fixed anchor it must not go anywhere.
        repeat(200) {
            val change = zoom.update(
                hand(0.30f + noise(), 0.3f + noise()),
                hand(0.70f + noise(), 0.3f + noise()),
                true
            )
            if (change != null) {
                total *= change.scale
                pan += abs(change.panX) + abs(change.panY)
            }
        }
        assertTrue(abs(total - 1f) < 0.08f, "still hands drifted the zoom to $total")
        assertTrue(pan < 0.05f, "still hands drifted the canvas by $pan")
    }

    @Test
    fun `letting go and grabbing again starts from where the canvas is`() {
        val zoom = ZoomGesture()
        zoom.update(hand(0.40f, 0.3f), hand(0.60f, 0.3f), true)
        repeat(12) { zoom.update(hand(0.30f, 0.3f), hand(0.70f, 0.3f), true) }

        zoom.update(null, null, false)
        assertFalse(zoom.isActive)

        // A fresh grab re-anchors, so the canvas does not snap back to the first gesture's scale.
        assertNull(zoom.update(hand(0.35f, 0.3f), hand(0.65f, 0.3f), true))
        assertNull(zoom.update(hand(0.35f, 0.3f), hand(0.65f, 0.3f), true),
            "an unmoved second grab should change nothing")
    }
}
