package app.pensy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.UUID

/*
 * Photos of documents, kept only when the user wants them.
 *
 * While a document is being read, each page is saved as a JPEG in the
 * cache ("pending"). When the user saves with "Conserva la foto" on, the
 * files move to files/pages and get a row in the database; otherwise
 * they are deleted. In PENSY_DATA each page is a sealed file of its own
 * (pagine/<name>.pensy), so saving after a change never rewrites them.
 */
object Pages {

    private const val DIR = "pages"
    private const val PENDING = "pending"
    private const val MAX_SIDE = 1800
    private const val QUALITY = 82
    private const val PREFS = "pensy_device"

    /** Small pictures for the strips, by "name@size". */
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun file(ctx: Context, name: String): File = File(File(ctx.filesDir, DIR).apply { mkdirs() }, name)

    private fun pendingDir(ctx: Context): File = File(ctx.cacheDir, PENDING).apply { mkdirs() }

    /** A new file name: "p-" and 24 hex characters (see Pack.isSafeName). */
    fun newName(): String = "p-" + UUID.randomUUID().toString().replace("-", "").take(24) + ".jpg"

    /** "Keep photos of new documents": a device setting, off by default. */
    fun keepByDefault(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keep_photos", false)

    fun setKeepByDefault(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("keep_photos", on).apply()
    }

    /** Save a page as JPEG in the cache, until the user decides. Null if it cannot be written. */
    fun stash(ctx: Context, bmp: Bitmap): File? = try {
        val scaled = scale(bmp, MAX_SIDE)
        val f = File(pendingDir(ctx), newName())
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (scaled !== bmp) scaled.recycle()
        f
    } catch (t: Throwable) {
        null
    }

    /** Keep pending files as the next pages of a document. */
    fun adopt(ctx: Context, docId: Long, files: List<File>) {
        for (f in files) {
            if (!f.exists()) continue
            val target = file(ctx, f.name)
            if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
            ctx.db.addPage(docId, f.name)
        }
    }

    fun discard(files: List<File>) {
        for (f in files) f.delete()
    }

    /** Leftovers of a confirmation that was never saved. */
    fun cleanPending(ctx: Context) {
        val limit = System.currentTimeMillis() - 24 * 3600 * 1000L
        pendingDir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    /** A page scaled to fit `maxSide`, or null if missing. */
    fun load(ctx: Context, name: String, maxSide: Int): Bitmap? = decode(file(ctx, name), maxSide)

    fun loadFile(f: File, maxSide: Int): Bitmap? = decode(f, maxSide)

    fun thumb(ctx: Context, name: String, side: Int): Bitmap? {
        val key = "$name@$side"
        thumbs.get(key)?.let { return it }
        val b = load(ctx, name, side) ?: return null
        thumbs.put(key, b)
        return b
    }

    private fun decode(f: File, maxSide: Int): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scale(b: Bitmap, max: Int): Bitmap {
        val side = maxOf(b.width, b.height)
        if (side <= max) return b
        val k = max.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }
}
