package com.ambokit.aep.airdraw

/**
 * One saved drawing, with the metadata the gallery needs to show it.
 *
 * [name] is nullable and stays that way. A drawing identifies itself visually in a way a document
 * never does - a thumbnail is recognised faster than a label could be read, which is why photo
 * apps do not ask people to name photos either. So drawings are named by when they were made, and
 * the field exists from the first version purely so that adding rename later migrates nothing.
 */
data class DrawingRecord(
    val id: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val name: String?,
    val strokes: List<Stroke>
) {
    /** What the gallery lists, without carrying every point of every stroke around with it. */
    fun summary(): DrawingSummary = DrawingSummary(id, createdMs, modifiedMs, name, strokes.size)

    /**
     * A copy under a new id, which is what the palette's Save means.
     *
     * Autosave already protects the work, so Save cannot mean "commit" - there would be nothing
     * to commit. It means "keep this as it is now", leaving the live drawing to carry on
     * changing. Created and modified are both now: as a drawing, the copy is new.
     */
    fun snapshot(newId: String, nowMs: Long): DrawingRecord =
        DrawingRecord(newId, nowMs, nowMs, name, strokes)
}

/** A gallery entry: everything needed to draw a tile, and nothing else. */
data class DrawingSummary(
    val id: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val name: String?,
    val strokeCount: Int
) {
    val isEmpty: Boolean get() = strokeCount == 0
}

/**
 * Reads and writes drawings as text.
 *
 * **Why not JSON.** ADR-0001 keeps this module on the standard library, which on the JVM means no
 * JSON parser. Writing one would mean a hand-rolled parser deciding what a saved drawing means -
 * more code, and more subtle ways to be wrong, than a format designed to be trivial to parse.
 *
 * **Why refusing beats repairing.** Every parse failure returns null rather than the strokes it
 * managed to read. A drawing that loads with half its marks missing looks like a drawing somebody
 * damaged, and they would have no way to tell that from one they drew that way - so a file that
 * does not fully parse is not opened at all, and the original is left untouched on disk for
 * somebody to recover.
 */
object DrawingCodec {
    const val FORMAT = "airdraw/1"

    fun encode(record: DrawingRecord): String = buildString {
        appendLine(FORMAT)
        appendLine("id ${record.id}")
        appendLine("created ${record.createdMs}")
        appendLine("modified ${record.modifiedMs}")
        // Escaped, so a newline in a name cannot forge the rest of the file. Names arrive from
        // speech recognition, which will eventually hand us anything.
        appendLine("name ${escape(record.name)}")
        for (stroke in record.strokes) {
            appendLine("s ${stroke.argb} ${stroke.baseWidth} ${stroke.source} ${stroke.brush} ${stroke.erases}")
            append("p")
            for (point in stroke.points) {
                append(' ')
                append(point.x); append(','); append(point.y)
                append(','); append(point.pressure); append(','); append(point.timeMs)
            }
            appendLine()
        }
    }

    /** @return the drawing, or null if anything at all about the text did not parse. */
    fun decode(text: String?): DrawingRecord? {
        if (text == null) return null
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines[0].trim() != FORMAT) return null

        var id: String? = null
        var created: Long? = null
        var modified: Long? = null
        var name: String? = null
        var sawName = false
        val strokes = ArrayList<Stroke>()
        var pending: Stroke? = null

        for (line in lines.drop(1)) {
            // A key with no value is legal and has to stay legal: a stroke with no points
            // writes a bare "p", and an unnamed drawing a bare "name". Requiring a space made
            // both unreadable, and relying on a trailing space instead would put the format at
            // the mercy of anything that strips whitespace.
            val space = line.indexOf(' ')
            if (space == 0) return null
            val key = if (space < 0) line else line.substring(0, space)
            val value = if (space < 0) "" else line.substring(space + 1)
            when (key) {
                "id" -> id = value.trim().ifEmpty { return null }
                "created" -> created = value.trim().toLongOrNull() ?: return null
                "modified" -> modified = value.trim().toLongOrNull() ?: return null
                "name" -> { name = unescape(value); sawName = true }
                "s" -> {
                    // A stroke header with no points line after it is a truncated file.
                    if (pending != null) return null
                    pending = parseStroke(value) ?: return null
                }
                "p" -> {
                    val header = pending ?: return null
                    val points = parsePoints(value) ?: return null
                    strokes += header.copy(points = points)
                    pending = null
                }
                else -> return null  // An unknown key means a newer format, not a line to skip.
            }
        }
        if (pending != null) return null
        if (id == null || created == null || modified == null || !sawName) return null
        return DrawingRecord(id, created, modified, name, strokes)
    }

    private fun parseStroke(value: String): Stroke? {
        val parts = value.trim().split(' ')
        if (parts.size != 5) return null
        val argb = parts[0].toIntOrNull() ?: return null
        val width = parts[1].toFloatOrNull() ?: return null
        val source = StrokeSource.entries.firstOrNull { it.name == parts[2] } ?: return null
        val brush = BrushType.entries.firstOrNull { it.name == parts[3] } ?: return null
        val erases = when (parts[4]) { "true" -> true; "false" -> false; else -> return null }
        return Stroke(argb, width, source, emptyList(), brush, erases)
    }

    private fun parsePoints(value: String): List<AirDrawPoint>? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = ArrayList<AirDrawPoint>()
        for (token in trimmed.split(' ')) {
            if (token.isEmpty()) continue
            val f = token.split(',')
            if (f.size != 4) return null
            val x = f[0].toFloatOrNull() ?: return null
            val y = f[1].toFloatOrNull() ?: return null
            val pressure = f[2].toFloatOrNull() ?: return null
            val time = f[3].toLongOrNull() ?: return null
            // NaN and infinity round-trip through Float.toString perfectly well and would then
            // be drawn, which turns one bad sample into a stroke that renders as nothing at all.
            if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) return null
            out += AirDrawPoint(x, y, pressure, time)
        }
        return out
    }

    private fun escape(value: String?): String = when (value) {
        null -> ""
        else -> value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun unescape(value: String): String? {
        if (value.isEmpty()) return null
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\') { out.append(c); i++; continue }
            i++
            when (value.getOrNull(i)) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append('\\')
            }
            i++
        }
        return out.toString()
    }
}

/**
 * When a drawing is due to be written to disk.
 *
 * Autosave, not an explicit Save button: somebody who draws for ten minutes and walks away should
 * still have their picture. But writing on every committed stroke would put a file write in the
 * middle of drawing, so this debounces - a write becomes due once the work has been still for a
 * moment, or once enough has changed that losing it would matter.
 *
 * It only ever *reports* that a write is due. Deciding to write, and writing, belong to the app
 * module, which is the half of this that can touch a filesystem.
 */
class Autosave(
    /** Quiet time after the last change before a write is due. */
    private val quietMs: Long = 1_500L,
    /** Changes after which a write is due regardless of how busy the person still is. */
    private val changesBeforeForcing: Int = 12
) {
    private var pendingChanges = 0
    private var lastChangeMs = 0L

    val isDirty: Boolean get() = pendingChanges > 0

    /** How many changes are waiting to be written, for the diagnostics log. */
    val pending: Int get() = pendingChanges

    /** Call when a stroke is committed, undone, redone or cleared. */
    fun changed(nowMs: Long) {
        pendingChanges++
        lastChangeMs = nowMs
    }

    fun isDue(nowMs: Long): Boolean {
        if (pendingChanges == 0) return false
        if (pendingChanges >= changesBeforeForcing) return true
        return nowMs - lastChangeMs >= quietMs
    }

    /**
     * Call once a write has actually succeeded.
     *
     * Separate from [isDue] on purpose: marking the work clean at the moment a write is *decided*
     * would lose every change made by a write that then failed, and the person would have no idea
     * until they came back to a drawing missing its last minute.
     */
    fun saved() {
        pendingChanges = 0
    }

    /** A session ending or a different drawing being opened. Anything unwritten is abandoned. */
    fun reset() {
        pendingChanges = 0
        lastChangeMs = 0L
    }
}
