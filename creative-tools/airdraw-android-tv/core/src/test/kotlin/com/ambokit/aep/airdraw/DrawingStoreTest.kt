package com.ambokit.aep.airdraw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun points(n: Int) = (0 until n).map {
    AirDrawPoint(it * 0.01f, 0.2f + it * 0.003f, 0.5f + it * 0.01f, it * 16L)
}

private fun record(
    id: String = "d1",
    name: String? = null,
    strokes: List<Stroke> = listOf(
        Stroke(0xFF112233.toInt(), 0.008f, StrokeSource.AIR, points(4)),
        Stroke(AirDrawEngine.DEFAULT_PAPER, 0.018f, StrokeSource.TOUCH, points(3),
               BrushType.PEN, erases = true)
    )
) = DrawingRecord(id, 1_700_000_000_000L, 1_700_000_060_000L, name, strokes)

class DrawingCodecTest {

    @Test
    fun `a drawing survives a round trip exactly`() {
        val original = record()
        val back = DrawingCodec.decode(DrawingCodec.encode(original))
        assertEquals(original, back, "every field, including each point, must come back identical")
    }

    @Test
    fun `an empty drawing round trips`() {
        val original = record(strokes = emptyList())
        assertEquals(original, DrawingCodec.decode(DrawingCodec.encode(original)))
    }

    @Test
    fun `a stroke with no points round trips`() {
        val original = record(strokes = listOf(Stroke(1, 0.01f, StrokeSource.AIR, emptyList())))
        assertEquals(original, DrawingCodec.decode(DrawingCodec.encode(original)))
    }

    @Test
    fun `float coordinates come back bit for bit`() {
        // Float.toString gives the shortest representation that round-trips, so this holds - but
        // it is the property the whole format rests on, so it is worth pinning rather than
        // assuming.
        val awkward = listOf(0.1f, 1f / 3f, 0.0001f, 0.9999999f, 123.456f)
        val stroke = Stroke(1, 1f / 7f, StrokeSource.AIR,
            awkward.map { AirDrawPoint(it, it, it, 1L) })
        val back = DrawingCodec.decode(DrawingCodec.encode(record(strokes = listOf(stroke))))
        assertEquals(stroke.points, back?.strokes?.get(0)?.points)
        assertEquals(stroke.baseWidth, back?.strokes?.get(0)?.baseWidth)
    }

    @Test
    fun `a name round trips, and so does not having one`() {
        assertEquals("Tuesday's dragon", DrawingCodec.decode(DrawingCodec.encode(record(name = "Tuesday's dragon")))?.name)
        assertNull(DrawingCodec.decode(DrawingCodec.encode(record(name = null)))?.name)
    }

    @Test
    fun `a newline in a name cannot forge the rest of the file`() {
        // Names will eventually come from speech recognition, which will hand us anything.
        val hostile = record(name = "x\nid evil\ns 1 1 AIR PEN false")
        val back = DrawingCodec.decode(DrawingCodec.encode(hostile))
        assertEquals("d1", back?.id, "the forged id line must not have been read as one")
        assertEquals(hostile.name, back?.name)
        assertEquals(hostile.strokes.size, back?.strokes?.size)
    }

    // ---- refusing rather than half-loading -----------------------------------------------------

    @Test
    fun `a truncated file is refused rather than partly loaded`() {
        // The failure this guards: a drawing opens with some of its marks missing, and the person
        // cannot tell that from a drawing they made that way.
        val full = DrawingCodec.encode(record())
        // Drop the last line with content in it. The encoded text ends in a newline, so dropping
        // the last element of lineSequence() removes only that empty tail - which is how the
        // first version of this test passed against a file it had not actually truncated.
        val content = full.lineSequence().filter { it.isNotBlank() }.toList()
        val truncated = content.dropLast(1).joinToString("\n")
        assertTrue(truncated.lines().size < content.size, "the test must actually remove a line")
        assertNull(DrawingCodec.decode(truncated))
    }

    @Test
    fun `a stroke header with no points is refused`() {
        assertNull(DrawingCodec.decode("""
            ${DrawingCodec.FORMAT}
            id d1
            created 1
            modified 2
            name
            s 1 0.01 AIR PEN false
            s 2 0.01 AIR PEN false
            p 0.1,0.1,1.0,0
        """.trimIndent()))
    }

    @Test
    fun `points with no stroke header are refused`() {
        assertNull(DrawingCodec.decode("""
            ${DrawingCodec.FORMAT}
            id d1
            created 1
            modified 2
            name
            p 0.1,0.1,1.0,0
        """.trimIndent()))
    }

    @Test
    fun `a missing required field is refused`() {
        for (drop in listOf("id", "created", "modified", "name")) {
            val text = DrawingCodec.encode(record())
                .lineSequence().filterNot { it.startsWith("$drop ") || it == "name" }.joinToString("\n")
            assertNull(DrawingCodec.decode(text), "a file with no $drop must not load")
        }
    }

    @Test
    fun `an unknown key is refused rather than skipped`() {
        // An unknown key means a file written by something newer. Skipping it would open a
        // drawing while quietly discarding whatever that newer thing considered important.
        val text = DrawingCodec.encode(record()).replace("id d1", "id d1\nlayer 3")
        assertNull(DrawingCodec.decode(text))
    }

    @Test
    fun `the wrong format marker is refused`() {
        assertNull(DrawingCodec.decode(DrawingCodec.encode(record()).replace(DrawingCodec.FORMAT, "airdraw/2")))
        assertNull(DrawingCodec.decode("not a drawing at all"))
        assertNull(DrawingCodec.decode(""))
        assertNull(DrawingCodec.decode(null))
    }

    @Test
    fun `malformed numbers are refused`() {
        val base = DrawingCodec.encode(record())
        assertNull(DrawingCodec.decode(base.replace("created 1700000000000", "created yesterday")))
        assertNull(DrawingCodec.decode(base.replace("0.008", "wide")))
    }

    @Test
    fun `an unrecognised brush or source is refused`() {
        val base = DrawingCodec.encode(record())
        assertNull(DrawingCodec.decode(base.replace(" AIR ", " SMOKE ")))
        assertNull(DrawingCodec.decode(base.replace("MARKER", "AIRBRUSH")))
    }

    @Test
    fun `a point that is not finite is refused`() {
        // NaN and infinity round-trip through Float.toString perfectly well, and would then be
        // drawn - turning one bad sample into a stroke that renders as nothing.
        val base = DrawingCodec.encode(record())
        assertNull(DrawingCodec.decode(base.replace("0.0,", "NaN,")))
        assertNull(DrawingCodec.decode(base.replace("0.0,", "Infinity,")))
    }
}

class DrawingRecordTest {

    @Test
    fun `a snapshot is a new drawing, not a new version of the old one`() {
        val original = record()
        val copy = original.snapshot("d2", nowMs = 1_700_000_999_000L)
        assertNotEquals(original.id, copy.id)
        assertEquals(original.strokes, copy.strokes)
        assertEquals(1_700_000_999_000L, copy.createdMs, "as a drawing, the copy is new")
        assertEquals(copy.createdMs, copy.modifiedMs)
    }

    @Test
    fun `a summary carries what a gallery tile needs and nothing more`() {
        val s = record().summary()
        assertEquals("d1", s.id)
        assertEquals(2, s.strokeCount)
        assertTrue(!s.isEmpty)
        assertTrue(record(strokes = emptyList()).summary().isEmpty)
    }
}

class AutosaveTest {

    @Test
    fun `nothing is due when nothing has changed`() {
        val a = Autosave()
        assertTrue(!a.isDirty)
        assertTrue(!a.isDue(10_000))
    }

    @Test
    fun `a write becomes due once the drawing has been still for a moment`() {
        val a = Autosave(quietMs = 1_500)
        a.changed(1_000)
        assertTrue(!a.isDue(2_000), "still drawing; a file write here lands mid-stroke")
        assertTrue(a.isDue(2_500))
    }

    @Test
    fun `a busy session still saves, rather than waiting for a pause that never comes`() {
        val a = Autosave(quietMs = 1_500, changesBeforeForcing = 5)
        for (i in 1..5) a.changed(1_000L + i * 100)
        assertTrue(a.isDue(1_500), "five changes in half a second is worth protecting")
    }

    @Test
    fun `marking saved clears the debt`() {
        val a = Autosave(quietMs = 100)
        a.changed(0)
        assertTrue(a.isDue(1_000))
        a.saved()
        assertTrue(!a.isDirty)
        assertTrue(!a.isDue(10_000))
    }

    /**
     * The reason `saved()` is separate from `isDue()`: clearing the debt when a write is decided
     * rather than when it succeeds loses every change made by a write that then failed, and
     * nobody finds out until the drawing comes back short.
     */
    @Test
    fun `a write that never happened leaves the work still dirty`() {
        val a = Autosave(quietMs = 100)
        a.changed(0)
        assertTrue(a.isDue(1_000))
        // ...the write throws, so saved() is never called.
        assertTrue(a.isDirty)
        assertTrue(a.isDue(2_000), "and it stays due, so the next tick tries again")
    }

    @Test
    fun `opening a different drawing abandons unwritten changes deliberately`() {
        val a = Autosave()
        a.changed(0)
        a.reset()
        assertTrue(!a.isDirty)
    }
}
