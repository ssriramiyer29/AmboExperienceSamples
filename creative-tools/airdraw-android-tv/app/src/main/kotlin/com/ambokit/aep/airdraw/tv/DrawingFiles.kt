package com.ambokit.aep.airdraw.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.ambokit.aep.airdraw.AirDrawEngine
import com.ambokit.aep.airdraw.DrawingCodec
import com.ambokit.aep.airdraw.DrawingRecord
import com.ambokit.aep.airdraw.DrawingSummary
import com.ambokit.aep.airdraw.Stroke
import com.ambokit.aep.airdraw.StrokePipeline
import java.io.File
import java.util.UUID

/**
 * Drawings on disk, and the pictures of them the gallery shows.
 *
 * The rules for what a saved drawing *is* live in `DrawingCodec`, in the core module, where they
 * are tested without a device. This half is the part that cannot be: files, bitmaps and the
 * Android APIs for both.
 *
 * Drawings are per-television. There is no identity on this device - no sign-in, no profile - so
 * a drawing belongs to the set on this box and not to a person. Worth saying out loud because it
 * is the sort of thing a demo can imply and then fail to deliver.
 */
class DrawingFiles(private val root: File) {

    init {
        if (!root.exists()) root.mkdirs()
    }

    private fun fileFor(id: String) = File(root, "$id.airdraw")
    private fun thumbFor(id: String) = File(root, "$id.png")

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Write a drawing, and its thumbnail beside it.
     *
     * Written to a temporary file and then renamed, because a television loses power mid-write
     * like any other computer and a half-written file is one the codec will refuse to open. A
     * rename is atomic on every filesystem this will meet, so the worst case becomes "the last
     * save did not happen" rather than "the drawing is gone".
     *
     * @return true when the drawing is safely on disk. The caller only marks the work clean on
     *   true, so a failed write is retried rather than silently dropped.
     */
    fun save(record: DrawingRecord): Boolean {
        return try {
            val target = fileFor(record.id)
            val temp = File(root, "${record.id}.tmp")
            temp.writeText(DrawingCodec.encode(record))
            if (!temp.renameTo(target)) {
                // renameTo fails rather than replacing on some filesystems.
                target.delete()
                if (!temp.renameTo(target)) { temp.delete(); return false }
            }
            writeThumbnail(record)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** @return the drawing, or null if it is missing or did not fully parse. */
    fun load(id: String): DrawingRecord? = try {
        val file = fileFor(id)
        if (file.exists()) DrawingCodec.decode(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Every drawing on this box, newest first.
     *
     * A file that will not parse is left out of the listing rather than shown as an empty tile,
     * and is *not* deleted: it is somebody's picture, and a bug in this code is a far more likely
     * explanation for a failed parse than a genuinely corrupt file.
     */
    fun list(): List<DrawingSummary> = try {
        // listFiles() then filter, rather than listFiles { ... }: File has both a FileFilter and
        // a FilenameFilter overload, and a one-parameter lambda is ambiguous between them.
        root.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".airdraw") }
            .mapNotNull { file -> DrawingCodec.decode(runCatching { file.readText() }.getOrNull())?.summary() }
            .sortedByDescending { it.modifiedMs }
    } catch (e: Exception) {
        emptyList()
    }

    fun thumbnailFile(id: String): File? = thumbFor(id).takeIf { it.exists() }

    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
        runCatching { thumbFor(id).delete() }
    }

    private fun writeThumbnail(record: DrawingRecord) {
        val bitmap = render(record.strokes, THUMB_WIDTH, THUMB_HEIGHT) ?: return
        try {
            thumbFor(record.id).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            // A missing thumbnail costs a grey tile; it must never cost the drawing.
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Draw strokes into a bitmap at whatever size the gallery wants.
     *
     * Deliberately not a screenshot of the live canvas: that would capture the palette, the
     * cursors and whatever the viewport happened to be zoomed into. A thumbnail is the drawing,
     * seen whole, in document space.
     */
    fun render(strokes: List<Stroke>, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(AirDrawEngine.DEFAULT_PAPER)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Document space is 0..1 across and 0..aspect down, so the horizontal extent sets the
        // scale and the vertical follows from it - the same mapping the viewport uses, which is
        // what makes a thumbnail look like what was on screen.
        val scale = widthPx.toFloat()
        val path = Path()
        for (stroke in strokes) {
            val curved = StrokePipeline.curve(stroke.points)
            if (curved.size < 2) continue
            val widths = StrokePipeline.widths(curved, stroke.baseWidth, stroke.source, stroke.brush)
            paint.color = stroke.argb
            // One path per stroke with a mean width, rather than a segment per sample. A
            // thumbnail is a couple of hundred pixels wide; per-point width variation is
            // invisible at that size and costs a draw call for every sample in the drawing.
            paint.strokeWidth = (widths.average().toFloat() * scale).coerceAtLeast(1f)
            path.reset()
            path.moveTo(curved[0].x * scale, curved[0].y * scale)
            for (i in 1 until curved.size) path.lineTo(curved[i].x * scale, curved[i].y * scale)
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    companion object {
        const val THUMB_WIDTH = 480
        /** 16:9, matching document space, so nothing is cropped out of a tile. */
        const val THUMB_HEIGHT = 270
    }
}
