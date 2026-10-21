package app.pensy

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/*
 * Handing a file to another app, only when the user asks: a page photo,
 * a PDF for the doctor, a CSV, the .pensy file for another phone.
 *
 * The file is copied into cache/share and served read-only by
 * ShareProvider for the time the other app needs it. Nothing is sent by
 * PENSY itself: the user picks where it goes (Quick Share, Bluetooth,
 * a USB cable, an email...).
 */
object Share {

    private const val DIR = "share"

    fun dir(ctx: Context): File = File(ctx.cacheDir, DIR).apply { mkdirs() }

    /** A file in cache/share, ready to be written and then shared. Old shares are cleaned first. */
    fun newFile(ctx: Context, name: String): File {
        val limit = System.currentTimeMillis() - 6 * 3600 * 1000L
        dir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        return File(dir(ctx), safe(name))
    }

    private fun safe(name: String): String =
        name.replace(Regex("""[^\p{L}\p{N} ._\-()]"""), "_").trim().ifEmpty { "file" }.take(80)

    fun uri(ctx: Context, f: File): Uri = Uri.parse("content://${ctx.packageName}.share/" + Uri.encode(f.name))

    /** Open the share sheet for a file already in cache/share. */
    fun send(act: MainActivity, f: File, mime: String, title: String) {
        val uri = uri(act, f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(title, uri)
        try {
            act.startExternal(Intent.createChooser(i, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 0)
        } catch (e: ActivityNotFoundException) {
            act.toast(R.string.no_share_app)
        }
    }

    /** Copy any file to cache/share and open the share sheet. */
    fun copyAndSend(act: MainActivity, src: File, name: String, mime: String, title: String) {
        val f = newFile(act, name)
        src.copyTo(f, overwrite = true)
        send(act, f, mime, title)
    }
}

/** Serves the files of cache/share, read-only, to the app the user picked. */
class ShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileOf(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw SecurityException("no file")
        if (name.contains('/') || name == "." || name == "..") throw SecurityException("bad name")
        val f = File(Share.dir(context!!), name)
        if (!f.exists()) throw java.io.FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment?.lowercase() ?: ""
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".csv") -> "text/csv"
            n.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
