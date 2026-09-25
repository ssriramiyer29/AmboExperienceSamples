package com.ambokit.aep.airdraw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ASPECT = 9f / 16f

private fun ToolStrip.tap(kind: ToolStrip.Kind) = select(cells(ASPECT).first { it.kind == kind })
private fun ToolStrip.tapColour(index: Int) =
    select(cells(ASPECT).first { it.kind == ToolStrip.Kind.COLOUR && it.index == index })

/** A stroke long enough to commit - the engine drops anything under three samples as a twitch. */
private fun AirDrawEngine.drawA(x: Float = 0.2f, y: Float = 0.2f) {
    begin(StrokeSource.AIR, AirDrawPoint(x, y, timeMs = 0))
    extend(AirDrawPoint(x + 0.10f, y, timeMs = 16))
    extend(AirDrawPoint(x + 0.20f, y, timeMs = 32))
    extend(AirDrawPoint(x + 0.30f, y, timeMs = 48))
    end()
}

class EraserTest {

    @Test
    fun `the eraser paints paper rather than deleting anything`() {
        val engine = AirDrawEngine()
        engine.drawA()
        assertEquals(1, engine.drawing.all.size)

        engine.erasing = true
        engine.drawA(y = 0.4f)
        assertEquals(2, engine.drawing.all.size, "erasing adds a mark, it does not remove one")
        assertEquals(AirDrawEngine.DEFAULT_PAPER, engine.drawing.all.last().argb)
    }

    @Test
    fun `the eraser is cream, not white`() {
        // Painting white onto cream paper leaves visible smears. This is the whole reason the
        // paper colour is a value the engine holds rather than Color.WHITE at the call site.
        assertEquals(0xFFF8F4EC.toInt(), AirDrawEngine.DEFAULT_PAPER)
        assertTrue(AirDrawEngine.DEFAULT_PAPER != 0xFFFFFFFF.toInt())
    }

    @Test
    fun `undoing an erasure brings the ink back`() {
        val engine = AirDrawEngine()
        engine.drawA()
        engine.erasing = true
        engine.drawA()
        assertEquals(2, engine.drawing.all.size)
        engine.undo()
        assertEquals(1, engine.drawing.all.size, "the ink underneath was never gone")
        assertTrue(!engine.drawing.all[0].erases)
    }

    @Test
    fun `an erasure records that it was one`() {
        // The pixels cannot say why they are cream. A later move to split-on-erase, or a canvas
        // that is not cream, needs to tell an erasure from a stroke drawn in that colour.
        val engine = AirDrawEngine()
        engine.colour = AirDrawEngine.DEFAULT_PAPER
        engine.drawA()
        assertTrue(!engine.drawing.all.last().erases, "drawn in cream on purpose")

        engine.erasing = true
        engine.drawA()
        assertTrue(engine.drawing.all.last().erases, "meant as an erasure")
    }

    @Test
    fun `an erasure is always a pen`() {
        // Speed-varying width would thin the erasure wherever the arm moved quickly, leaving the
        // old drawing showing through the middle of a sweep.
        val engine = AirDrawEngine()
        engine.brush = BrushType.CRAYON
        engine.erasing = true
        engine.drawA()
        assertEquals(BrushType.PEN, engine.drawing.all.last().brush)
    }
}

class PaletteToolsTest {

    @Test
    fun `the eraser toggles`() {
        val strip = ToolStrip()
        assertTrue(!strip.erasing)
        strip.tap(ToolStrip.Kind.ERASER)
        assertTrue(strip.erasing)
        strip.tap(ToolStrip.Kind.ERASER)
        assertTrue(!strip.erasing,
            "a control you cannot get back out of is the failure this toolbar exists to avoid")
    }

    @Test
    fun `picking a colour puts the eraser away`() {
        val strip = ToolStrip()
        strip.tap(ToolStrip.Kind.ERASER)
        strip.tapColour(2)
        assertTrue(!strip.erasing, "reaching for a colour is asking to draw in it, not to erase in it")
        assertEquals(2, strip.colourIndex)
    }

    @Test
    fun `brush cycles through every brush and comes back`() {
        val strip = ToolStrip()
        val seen = mutableListOf(strip.brush)
        repeat(ToolStrip.BRUSHES.size - 1) {
            strip.tap(ToolStrip.Kind.BRUSH)
            seen += strip.brush
        }
        assertEquals(ToolStrip.BRUSHES.size, seen.distinct().size)
        strip.tap(ToolStrip.Kind.BRUSH)
        assertEquals(seen.first(), strip.brush, "and it wraps, so nothing is one-way")
    }

    @Test
    fun `resetting the pen leaves it as it started`() {
        val strip = ToolStrip()
        strip.tapColour(4)
        strip.tap(ToolStrip.Kind.ERASER)
        strip.tap(ToolStrip.Kind.BRUSH)
        strip.resetPen()
        assertEquals(0, strip.colourIndex)
        assertEquals(1, strip.sizeIndex)
        assertEquals(ToolStrip.BRUSHES.first(), strip.brush)
        assertTrue(!strip.erasing)
    }
}

class BrushTypeTest {

    private fun widthsFor(brush: BrushType): FloatArray {
        // Deliberately uneven spacing, so a speed-sensitive brush has something to respond to
        // and a constant one visibly does not.
        val points = listOf(
            AirDrawPoint(0f, 0f, timeMs = 0),
            AirDrawPoint(0.02f, 0f, timeMs = 16),
            AirDrawPoint(0.20f, 0f, timeMs = 32),
            AirDrawPoint(0.22f, 0f, timeMs = 48),
            AirDrawPoint(0.60f, 0f, timeMs = 64)
        )
        return StrokePipeline.widths(points, 0.01f, StrokeSource.AIR, brush)
    }

    @Test
    fun `a pen holds one width whatever the hand does`() {
        val widths = widthsFor(BrushType.PEN).toList()
        assertEquals(1, widths.distinct().size, "a pen that varied would not be a pen")
        assertEquals(0.01f, widths.first())
    }

    @Test
    fun `a marker varies with speed`() {
        val widths = widthsFor(BrushType.MARKER)
        assertTrue(widths.max() > widths.min(), "the whole point of the default brush")
    }

    @Test
    fun `a crayon varies more than a marker`() {
        fun spread(b: BrushType) = widthsFor(b).let { it.max() - it.min() }
        assertTrue(spread(BrushType.CRAYON) > spread(BrushType.MARKER),
            "three brushes have to be distinguishable across a room or they are not worth strip space")
    }

    @Test
    fun `a finished stroke keeps the brush it was drawn with`() {
        val engine = AirDrawEngine()
        engine.brush = BrushType.CRAYON
        engine.drawA()
        engine.brush = BrushType.PEN
        engine.drawA(y = 0.5f)
        assertEquals(BrushType.CRAYON, engine.drawing.all[0].brush,
            "changing brushes must not rewrite the marks already made")
        assertEquals(BrushType.PEN, engine.drawing.all[1].brush)
    }
}
