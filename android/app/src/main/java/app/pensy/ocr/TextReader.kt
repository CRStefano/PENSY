package app.pensy.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import app.pensy.core.Layout
import java.io.File
import java.util.concurrent.Executors

/*
 * Reading the text of a photo, on the phone, without network.
 *
 * The engine is Tesseract (libpensyocr.so) with the Italian "fast" model
 * (and the English one, if chosen in Settings), shipped in the APK and
 * copied once to private storage. The photo is
 * turned to grayscale here, recognised natively, and the lines are put
 * back in reading order by core/Layout. If anything goes wrong (an old
 * 32-bit phone, no memory) the result is "" and the user fills the card.
 */
object TextReader {

    const val AVAILABLE = true

    /** Models shipped in the APK (assets/tessdata), copied once to private storage. */
    private const val MODEL_VERSION = "tessdata_fast-8741641"
    private const val PREFS = "pensy_device"
    const val ITA = "ita"
    const val ITA_ENG = "ita+eng"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 0 = library not loaded yet, 1 = loaded, -1 = unavailable on this phone. */
    @Volatile private var lib = 0
    /** The language(s) the engine has loaded now, or null. */
    @Volatile private var loaded: String? = null

    /** "Lingua dei documenti": Italian, or Italian and English (a little slower). A device setting. */
    fun lang(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ocr_lang", ITA) ?: ITA

    fun setLang(ctx: Context, l: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ocr_lang", l).apply()
        prepare(ctx)
    }

    /** Reads the text of the bitmap; done() is called on the main thread ("" on failure). */
    fun read(ctx: Context, bmp: Bitmap, done: (String) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            var text = ""
            try {
                if (ensure(app)) {
                    val w = bmp.width
                    val h = bmp.height
                    val raw = Tess.recognize(gray(bmp), w, h)
                    text = Layout.fromTesseract(String(raw, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                text = ""
            }
            main.post { done(text) }
        }
    }

    /** Load the model in the background while the user is taking the photo. */
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            try { ensure(app) } catch (t: Throwable) { }
        }
    }

    /** Copy one model from the APK if missing or of another version. */
    private fun install(ctx: Context, dir: File, code: String) {
        val model = File(dir, "$code.traineddata")
        val marker = File(dir, "$code.version")
        val version = "$MODEL_VERSION-$code"
        if (model.exists() && marker.exists() && marker.readText() == version) return
        val tmp = File(dir, "$code.tmp")
        ctx.assets.open("tessdata/$code.traineddata").use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        model.delete()
        if (!tmp.renameTo(model)) throw IllegalStateException("cannot install model")
        marker.writeText(version)
    }

    private fun ensure(ctx: Context): Boolean {
        if (lib == -1) return false
        if (lib == 0) {
            lib = try { System.loadLibrary("pensyocr"); 1 } catch (t: Throwable) { -1 }
            if (lib == -1) return false
        }
        val want = lang(ctx)
        if (loaded == want) return true
        return try {
            val dir = File(ctx.noBackupFilesDir, "tessdata").apply { mkdirs() }
            for (code in want.split('+')) install(ctx, dir, code)
            if (Tess.init(dir.path, want)) { loaded = want; true }
            else if (want != ITA) { install(ctx, dir, ITA); if (Tess.init(dir.path, ITA)) { loaded = ITA; true } else false }
            else false
        } catch (t: Throwable) {
            false
        }
    }

    /** RGB to 8-bit luminance, row by row (no big int[] for the whole photo). */
    private fun gray(src: Bitmap): ByteArray {
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val p = row[x]
                out[base + x] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
            }
        }
        return out
    }
}
