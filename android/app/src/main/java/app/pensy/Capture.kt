package app.pensy

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.pensy.core.Code
import app.pensy.core.DocType
import app.pensy.core.Parser
import app.pensy.ocr.TextReader
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/*
 * From photos (or PDFs and images from the phone) to a Card waiting for confirmation:
 *
 *   for each page: load bitmap -> codes (ZXing) -> JPEG in the cache -> text (OCR)
 *   then: all the text -> Parser -> CardScreen
 *
 * One page at a time, so a 12-page PDF never holds 12 bitmaps. The JPEG
 * of each page waits in the cache: it becomes part of the document only
 * if the user keeps the photo, otherwise it is deleted.
 *
 * The pages can also go to a confirmation already open ("Aggiungi
 * pagina"), to a saved document, or become the receipt of a payment.
 */
object Capture {

    /** Where the pages being read go. */
    sealed class Target {
        object New : Target()
        class Card(val screen: CardScreen) : Target()
        /** More pages for a saved document; `photoOnly`: just keep the photo, the text is already there. */
        class Doc(val docId: Long, val photoOnly: Boolean = false) : Target()
        class Receipt(val deadlineId: Long) : Target()
    }

    /** What was read from all the pages. */
    class Read(val text: String, val codes: List<Code>, val files: List<File>)

    /** Kind suggested by the menu ("payslip"), applied if the reader is unsure. */
    var hint: DocType? = null

    /** Folder the user was in when adding: the new document goes there. */
    var folderHint: Long? = null

    /** The visit whose report is being photographed: the new document gets linked to it. */
    var visitId: Long? = null

    /** Target of the camera or picker now open (the result comes back without it). */
    private var target: Target = Target.New

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun camera(act: MainActivity, to: Target = Target.New) {
        TextReader.prepare(act)
        PhotoProvider.file(act).delete()
        val uri = PhotoProvider.uri(act)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        target = to
        try {
            act.startExternal(i, MainActivity.REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            target = Target.New
            act.toast(R.string.no_camera)
        }
    }

    /** A PDF or images from the phone (an attachment saved from an email, a photo from a chat); several images = several pages. */
    fun pickFile(act: MainActivity, to: Target = Target.New) {
        TextReader.prepare(act)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        target = to
        try {
            act.startExternal(i, MainActivity.REQ_FILE)
        } catch (e: ActivityNotFoundException) {
            target = Target.New
            act.toast(R.string.no_picker)
        }
    }

    /**
     * "Scatta una foto" or "Carica un file": every way into PENSY offers both,
     * because many papers arrive by email and are never printed. The hints
     * are set only once a source is picked, so a closed sheet leaves nothing behind.
     */
    fun choose(
        act: MainActivity, to: Target = Target.New, title: String? = null,
        hint: DocType? = null, folder: Long? = null, visit: Long? = null
    ) {
        val go = { file: Boolean ->
            if (to is Target.New) { this.hint = hint; folderHint = folder; visitId = visit }
            if (file) pickFile(act, to) else camera(act, to)
        }
        Dialogs.sheet(act, title ?: act.getString(R.string.source_title), listOf(
            Triple(R.drawable.ic_camera, act.getString(R.string.source_camera)) { go(false) },
            Triple(R.drawable.ic_image, act.getString(R.string.source_file)) { go(true) }
        ))
    }

    /** Ask "photo or file?" and read into `to`. */
    fun addPage(act: MainActivity, to: Target) = choose(act, to, act.getString(R.string.page_add))

    /** The camera or the picker was closed without a result. */
    fun cancelled() {
        if (target is Target.New) reset()
        target = Target.New
    }

    fun onCameraResult(act: MainActivity) {
        val f = PhotoProvider.file(act)
        if (!f.exists() || f.length() == 0L) { cancelled(); act.toast(R.string.photo_missing); return }
        process(act, takeTarget()) { Images.photo(f) }
    }

    fun onFiles(act: MainActivity, uris: List<Uri>) = process(act, takeTarget()) { Images.openAll(act, uris) }

    /** Files shared to PENSY from another app (or a PDF opened with it): always a new document. */
    fun onShared(act: MainActivity, uris: List<Uri>) {
        target = Target.New
        reset()
        process(act, Target.New) { Images.openAll(act, uris) }
    }

    private fun takeTarget(): Target = target.also { target = Target.New }

    private fun reset() {
        hint = null; folderHint = null; visitId = null
    }

    private fun process(act: MainActivity, to: Target, open: () -> PageSource?) {
        act.showBusy(R.string.reading_document)
        worker.execute {
            val src = try { open() } catch (e: Exception) { null }
            if (src == null || src.count == 0) {
                src?.close()
                if (to is Target.New) reset()
                main.post { act.hideBusy(); act.toast(R.string.read_error) }
                return@execute
            }
            val ocr = !(to is Target.Doc && to.photoOnly)
            readPages(act, src, 0, ocr, StringBuilder(), LinkedHashMap(), ArrayList()) { read ->
                PhotoProvider.file(act).delete()
                deliver(act, to, read)
            }
        }
    }

    /** Page i on the worker thread, its text on the main thread, then the next page. */
    private fun readPages(
        act: MainActivity, src: PageSource, i: Int, ocr: Boolean, text: StringBuilder,
        codes: LinkedHashMap<String, Code>, files: ArrayList<File>, done: (Read) -> Unit
    ) {
        if (i >= src.count) {
            src.close()
            done(Read(text.toString().trim(), codes.values.toList(), files))
            return
        }
        val bmp: Bitmap? = try { src.load(i) } catch (e: Throwable) { null }
        if (bmp == null) {
            readPages(act, src, i + 1, ocr, text, codes, files, done)
            return
        }
        Pages.stash(act, bmp)?.let { files += it }
        if (!ocr) {
            bmp.recycle()
            readPages(act, src, i + 1, ocr, text, codes, files, done)
            return
        }
        try { for (c in Codes.decode(bmp)) codes.putIfAbsent(c.format + "|" + c.content, c) } catch (e: Throwable) {}
        if (src.count > 1) main.post { act.showBusy(act.getString(R.string.reading_page, i + 1, src.count)) }
        main.post {
            TextReader.read(act, bmp) { t ->
                worker.execute {
                    bmp.recycle()
                    if (t.isNotBlank()) { if (text.isNotEmpty()) text.append("\n\n"); text.append(t) }
                    readPages(act, src, i + 1, ocr, text, codes, files, done)
                }
            }
        }
    }

    private fun deliver(act: MainActivity, to: Target, read: Read) {
        when (to) {
            is Target.New -> {
                var card = Parser.parse(read.text, read.codes, LocalDate.now())
                hint?.let { h ->
                    if (card.type != h && (card.type == DocType.ALTRO || h == DocType.BUSTA_PAGA)) {
                        card = card.copy(type = h, due = if (h == DocType.BUSTA_PAGA) null else card.due)
                    }
                }
                val folder = folderHint
                val visit = visitId
                reset()
                val finalCard = card
                main.post {
                    act.hideBusy()
                    act.push(CardScreen(act, card = finalCard, folderHint = folder, linkVisit = visit, pages = read.files))
                }
            }
            is Target.Card -> main.post {
                act.hideBusy()
                to.screen.addPages(read)
            }
            is Target.Doc -> {
                val db = act.db
                val known = db.codes(to.docId).map { it.format + "|" + it.content }.toSet()
                db.appendDocText(to.docId, read.text)
                db.saveCodes(to.docId, read.codes.filter { (it.format + "|" + it.content) !in known })
                // The photos are kept if the document already keeps them, or by the general setting.
                if (to.photoOnly || db.pages(to.docId).isNotEmpty() || Pages.keepByDefault(act)) Pages.adopt(act, to.docId, read.files)
                else Pages.discard(read.files)
                main.post {
                    act.hideBusy()
                    act.changed()
                    act.toast(if (to.photoOnly) R.string.photo_kept else R.string.page_added)
                    act.refresh()
                }
            }
            is Target.Receipt -> main.post {
                act.hideBusy()
                act.saveReceipt(to.deadlineId, read)
            }
        }
    }
}
