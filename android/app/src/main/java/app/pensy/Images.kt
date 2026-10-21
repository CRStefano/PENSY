package app.pensy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** The pages of what is being read: one photo, or the pages of a PDF, loaded one at a time. */
interface PageSource {
    val count: Int
    fun load(i: Int): Bitmap?
    fun close()
}

/** Loading photos and PDFs as bitmaps, upright and at a size OCR likes. */
object Images {

    private const val MAX = 2400
    /** A longer PDF is read up to here (a contract, a statement: the first pages say what matters). */
    const val MAX_PDF_PAGES = 12

    fun fromFile(f: File): Bitmap? = decode(f)

    private fun single(load: () -> Bitmap?): PageSource = object : PageSource {
        override val count = 1
        override fun load(i: Int): Bitmap? = load()
        override fun close() {}
    }

    fun photo(f: File): PageSource = single { decode(f) }

    /** At most this many pages from several picked files together. */
    const val MAX_PAGES = 20

    /** Several picked or shared files, one after the other: one document with all their pages. */
    fun openAll(ctx: Context, uris: List<Uri>): PageSource? {
        val parts = ArrayList<PageSource>()
        for (u in uris) {
            val p = try { open(ctx, u) } catch (e: Exception) { null }
            if (p != null && p.count > 0) parts += p else p?.close()
        }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        val starts = IntArray(parts.size)
        var n = 0
        for (k in parts.indices) { starts[k] = n; n += parts[k].count }
        return object : PageSource {
            override val count = minOf(n, MAX_PAGES)
            override fun load(i: Int): Bitmap? {
                var k = parts.size - 1
                while (k > 0 && starts[k] > i) k--
                return parts[k].load(i - starts[k])
            }
            override fun close() { for (p in parts) p.close() }
        }
    }

    /** A picked or shared file: every page of a PDF (up to MAX_PDF_PAGES), or the image. */
    fun open(ctx: Context, uri: Uri): PageSource? {
        val type = ctx.contentResolver.getType(uri) ?: ""
        if (type == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val renderer = try { PdfRenderer(pfd) } catch (e: Exception) { pfd.close(); return null }
            if (renderer.pageCount == 0) { renderer.close(); pfd.close(); return null }
            return object : PageSource {
                override val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
                override fun load(i: Int): Bitmap? = renderPage(renderer, i)
                override fun close() { renderer.close(); pfd.close() }
            }
        }
        return single {
            // Copy to a private file: EXIF orientation is read from a path.
            val tmp = File(ctx.cacheDir, "import.img")
            val input = ctx.contentResolver.openInputStream(uri)
            if (input == null) null else {
                input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                try { decode(tmp) } finally { tmp.delete() }
            }
        }
    }

    private fun decode(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    /** One page of a PDF, rendered on white at about 2000 px. */
    private fun renderPage(renderer: PdfRenderer, i: Int): Bitmap? {
        val page = renderer.openPage(i)
        try {
            val scale = 2000f / maxOf(page.width, page.height)
            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }
}

/**
 * Serves one private file to the camera app, which writes the photo
 * into it. The photo lives only until the document has been read.
 * (A dozen lines instead of a support-library dependency.)
 */
class PhotoProvider : ContentProvider() {

    companion object {
        const val FILE = "capture.jpg"
        fun uri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.photo/$FILE")
        fun file(ctx: Context): File = File(ctx.cacheDir, FILE)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(context!!), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> FILE
                OpenableColumns.SIZE -> file(context!!).length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
