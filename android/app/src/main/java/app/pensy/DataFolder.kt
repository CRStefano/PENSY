package app.pensy

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Base64
import app.pensy.core.Pack
import app.pensy.core.Vault
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/*
 * PENSY_DATA: the folder that holds everything.
 *
 * The live database stays in the app's private storage (fast, never
 * half-written). A couple of seconds after every change, a sealed copy
 * is written into the PENSY_DATA folder the user chose:
 *
 *   PENSY_DATA/pensy.dat      the database, encrypted with the password
 *   PENSY_DATA/pagine/        kept photos of documents, one sealed file each
 *   PENSY_DATA/copie/         the last 7 days' copies of pensy.dat
 *   PENSY_DATA/versione.txt   format, schema and date of the last save
 *   PENSY_DATA/LEGGIMI.txt    how to restore it on a new phone
 *
 * Moving to a new phone = copy the folder, then "Importa" in PENSY.
 * Nothing ever leaves the phone unless the user moves the folder.
 *
 * The folder location and the derived key are device settings: they
 * live in private SharedPreferences and are never part of the export.
 */
object DataFolder {

    private const val PREFS = "pensy_device"
    const val DATA_FILE = "pensy.dat"
    const val DIR_NAME = "PENSY_DATA"
    private const val PAGES_DIR = "pagine"
    private const val COPIES_DIR = "copie"
    const val KEEP_COPIES = 7

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var pending = false
    private val saveRunnable = Runnable { appCtx?.let { saveNow(it) } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(ctx: Context): Uri? = prefs(ctx).getString("tree", null)?.let(Uri::parse)
    fun dirUri(ctx: Context): Uri? = prefs(ctx).getString("dir", null)?.let(Uri::parse)
    fun hasKey(ctx: Context): Boolean = prefs(ctx).contains("key")
    fun isConfigured(ctx: Context): Boolean = dirUri(ctx) != null && hasKey(ctx)
    fun lastSave(ctx: Context): LocalDateTime? = prefs(ctx).getString("last_save", null)?.let { LocalDateTime.parse(it) }
    fun lastError(ctx: Context): String? = prefs(ctx).getString("save_error", null)

    /** Human name of the chosen folder, e.g. "Documents/PENSY_DATA". */
    fun folderLabel(ctx: Context): String? {
        val tree = treeUri(ctx) ?: return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val path = id.substringAfter(':', id)
        return if (path.endsWith(DIR_NAME)) path else if (path.isEmpty()) DIR_NAME else "$path/$DIR_NAME"
    }

    private fun key(ctx: Context): Triple<ByteArray, ByteArray, Int>? {
        val p = prefs(ctx)
        val k = p.getString("key", null) ?: return null
        val s = p.getString("salt", null) ?: return null
        return Triple(Base64.decode(k, Base64.NO_WRAP), Base64.decode(s, Base64.NO_WRAP), p.getInt("iter", Vault.DEFAULT_ITERATIONS))
    }

    private fun storeKey(ctx: Context, key: ByteArray, salt: ByteArray, iter: Int) {
        prefs(ctx).edit()
            .putString("key", Base64.encodeToString(key, Base64.NO_WRAP))
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt("iter", iter)
            .apply()
    }

    // ------------------------------------------------------- choose a folder

    fun chooseFolder(act: MainActivity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            act.startExternal(i, MainActivity.REQ_TREE)
        } catch (e: ActivityNotFoundException) {
            act.toast(R.string.no_picker)
        }
    }

    fun onTreePicked(act: MainActivity, tree: Uri) {
        try {
            act.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            act.toast(R.string.folder_error); return
        }
        act.showBusy(R.string.preparing_folder)
        worker.execute {
            val dir = try { ensureDir(act.contentResolver, tree) } catch (e: Exception) { null }
            main.post {
                act.hideBusy()
                if (dir == null) { act.toast(R.string.folder_error); return@post }
                prefs(act).edit().putString("tree", tree.toString()).putString("dir", dir.toString()).apply()
                if (hasKey(act)) { saveNow(act); act.refresh() }
                else setPassword(act, R.string.password_new_title) { saveNow(act); act.refresh() }
            }
        }
    }

    /** The PENSY_DATA folder inside the chosen one (or the chosen one itself if it is PENSY_DATA). */
    private fun ensureDir(cr: ContentResolver, tree: Uri): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        if (displayName(cr, root) == DIR_NAME) return root
        findChild(cr, tree, rootId, DIR_NAME)?.let { return it }
        return DocumentsContract.createDocument(cr, root, DocumentsContract.Document.MIME_TYPE_DIR, DIR_NAME)
            ?: throw IllegalStateException("cannot create folder")
    }

    private fun displayName(cr: ContentResolver, doc: Uri): String? =
        cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun findChild(cr: ContentResolver, tree: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        cr.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    /** Names and URIs of the files in a folder. */
    private fun children(cr: ContentResolver, tree: Uri, parentId: String): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return out
    }

    private fun writeFile(ctx: Context, tree: Uri, dir: Uri, name: String, mime: String, bytes: ByteArray) {
        val cr = ctx.contentResolver
        val dirId = DocumentsContract.getDocumentId(dir)
        val target = findChild(cr, tree, dirId, name)
            ?: DocumentsContract.createDocument(cr, dir, mime, name)
            ?: throw IllegalStateException("cannot create $name")
        val out = try { cr.openOutputStream(target, "wt") } catch (e: Exception) { null }
            ?: cr.openOutputStream(target, "w")
            ?: throw IllegalStateException("cannot write $name")
        out.use { it.write(bytes) }
    }

    // ------------------------------------------------------------- password

    /** Ask a new password, derive the key (about a second), store it. */
    fun setPassword(act: MainActivity, title: Int, then: () -> Unit) {
        Dialogs.newPassword(act, title) { pw ->
            act.showBusy(R.string.securing)
            worker.execute {
                val salt = Vault.random(16)
                val key = Vault.deriveKey(pw, salt, Vault.DEFAULT_ITERATIONS)
                pw.fill('\u0000')
                storeKey(act, key, salt, Vault.DEFAULT_ITERATIONS)
                main.post { act.hideBusy(); then() }
            }
        }
    }

    // ----------------------------------------------------------------- save

    /** Call after any change: saves a couple of seconds later, once. */
    fun saveSoon(ctx: Context) {
        if (!isConfigured(ctx)) return
        appCtx = ctx.applicationContext
        pending = true
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    /** Save now if a change is waiting (when the app goes to background). */
    fun flush(ctx: Context) {
        if (pending) saveNow(ctx)
    }

    fun saveNow(ctx: Context) {
        main.removeCallbacks(saveRunnable)
        pending = false
        val tree = treeUri(ctx) ?: return
        val dir = dirUri(ctx) ?: return
        val (key, salt, iter) = key(ctx) ?: return
        val app = ctx.applicationContext
        val bytes = app.db.snapshot()
        val now = LocalDateTime.now().withNano(0)
        worker.execute {
            try {
                val blob = Vault.seal(bytes, key, salt, iter)
                rotateCopies(app, tree, dir, now.toLocalDate())
                writeFile(app, tree, dir, DATA_FILE, "application/octet-stream", blob)
                writeFile(app, tree, dir, "versione.txt", "text/plain", versionText(now).toByteArray())
                if (findChild(app.contentResolver, tree, DocumentsContract.getDocumentId(dir), "LEGGIMI.txt") == null) {
                    val readme = app.resources.openRawResource(R.raw.leggimi).use { it.readBytes() }
                    writeFile(app, tree, dir, "LEGGIMI.txt", "text/plain", readme)
                }
                syncPages(app, tree, dir, key, salt, iter)
                prefs(app).edit().putString("last_save", now.toString()).remove("save_error").apply()
            } catch (e: Exception) {
                prefs(app).edit().putString("save_error", e.javaClass.simpleName + ": " + (e.message ?: "")).apply()
            }
        }
    }

    /**
     * Once a day, before the first save of the day, the pensy.dat already
     * in the folder (the state of the last day PENSY was used) is copied
     * to copie/pensy-<that day>.dat. The seven most recent stay.
     */
    private fun rotateCopies(ctx: Context, tree: Uri, dir: Uri, today: LocalDate) {
        val p = prefs(ctx)
        if (p.getString("last_copy_day", null) == today.toString()) return
        val cr = ctx.contentResolver
        val dirId = DocumentsContract.getDocumentId(dir)
        val current = findChild(cr, tree, dirId, DATA_FILE)
        if (current != null) {
            val day = lastSave(ctx)?.toLocalDate() ?: today.minusDays(1)
            val bytes = cr.openInputStream(current)?.use { it.readBytes() }
            if (bytes != null && bytes.size > Vault.HEADER_SIZE) {
                val copies = findChild(cr, tree, dirId, COPIES_DIR)
                    ?: DocumentsContract.createDocument(cr, dir, DocumentsContract.Document.MIME_TYPE_DIR, COPIES_DIR)
                if (copies != null) {
                    writeFile(ctx, tree, copies, "pensy-$day.dat", "application/octet-stream", bytes)
                    val all = children(cr, tree, DocumentsContract.getDocumentId(copies)).filterKeys { it.startsWith("pensy-") && it.endsWith(".dat") }
                    for (old in all.keys.sorted().dropLast(KEEP_COPIES)) runCatching { DocumentsContract.deleteDocument(cr, all.getValue(old)) }
                }
            }
        }
        p.edit().putString("last_copy_day", today.toString()).apply()
    }

    /** "Ripristina una copia": the copies in the folder, newest first, to pick one. */
    fun chooseCopy(act: MainActivity) {
        val tree = treeUri(act) ?: run { act.toast(R.string.copies_none); return }
        val dir = dirUri(act) ?: run { act.toast(R.string.copies_none); return }
        act.showBusy(R.string.reading_document)
        worker.execute {
            val list = try {
                val cr = act.contentResolver
                val copies = findChild(cr, tree, DocumentsContract.getDocumentId(dir), COPIES_DIR)
                if (copies == null) emptyList() else children(cr, tree, DocumentsContract.getDocumentId(copies))
                    .filterKeys { it.startsWith("pensy-") && it.endsWith(".dat") }.toList().sortedByDescending { it.first }
            } catch (e: Exception) { emptyList() }
            main.post {
                act.hideBusy()
                if (list.isEmpty()) { act.toast(R.string.copies_none); return@post }
                Dialogs.sheet(act, act.getString(R.string.copies_title), list.map { (name, uri) ->
                    val day = runCatching { LocalDate.parse(name.removePrefix("pensy-").removeSuffix(".dat")) }.getOrNull()
                    Triple(R.drawable.ic_time, day?.let { act.getString(R.string.copies_of, Fmt.dayLong(it)) } ?: name) { restoreCopy(act, uri) }
                })
            }
        }
    }

    private fun restoreCopy(act: MainActivity, copy: Uri) {
        val tree = treeUri(act) ?: return
        val dir = dirUri(act) ?: return
        act.showBusy(R.string.reading_document)
        worker.execute {
            val blob = try { act.contentResolver.openInputStream(copy)?.use { it.readBytes() } } catch (e: Exception) { null }
            main.post {
                act.hideBusy()
                if (blob == null) { act.toast(R.string.import_bad_file); return@post }
                try { Vault.header(blob) } catch (e: Vault.BadFormat) { act.toast(R.string.import_bad_file); return@post }
                askAndOpen(act, blob, tree, DocumentsContract.getDocumentId(dir))
            }
        }
    }

    /** The sealed name of a page in PENSY_DATA/pagine. */
    private fun sealedName(page: String) = page.removeSuffix(".jpg") + ".pensy"

    /**
     * Kept photos: each page is sealed once into pagine/ and never
     * rewritten. Pages deleted more than a week ago are removed (until
     * then the rotating copies may still need them). After a password
     * change every page is sealed again with the new key.
     */
    private fun syncPages(ctx: Context, tree: Uri, dir: Uri, key: ByteArray, salt: ByteArray, iter: Int) {
        val db = ctx.db
        val names = db.allPageNames()
        val gone = db.pagesGoneBefore(LocalDate.now().minusDays(8))
        if (names.isEmpty() && gone.isEmpty()) return
        val cr = ctx.contentResolver
        val dirId = DocumentsContract.getDocumentId(dir)
        val pagesDir = findChild(cr, tree, dirId, PAGES_DIR)
            ?: DocumentsContract.createDocument(cr, dir, DocumentsContract.Document.MIME_TYPE_DIR, PAGES_DIR)
            ?: return
        val saltText = Base64.encodeToString(salt, Base64.NO_WRAP)
        val sameKey = prefs(ctx).getString("pages_salt", null) == saltText
        val existing = if (sameKey) children(cr, tree, DocumentsContract.getDocumentId(pagesDir)) else emptyMap()
        for (n in names) {
            val sealed = sealedName(n)
            if (sealed in existing) continue
            val f = Pages.file(ctx, n)
            if (!f.exists()) continue
            writeFile(ctx, tree, pagesDir, sealed, "application/octet-stream", Vault.seal(f.readBytes(), key, salt, iter))
        }
        if (!sameKey) prefs(ctx).edit().putString("pages_salt", saltText).apply()
        val all = if (gone.isEmpty()) emptyMap() else children(cr, tree, DocumentsContract.getDocumentId(pagesDir))
        for (n in gone) all[sealedName(n)]?.let { runCatching { DocumentsContract.deleteDocument(cr, it) } }
        db.forgetGone(gone)
    }

    /** After an import: bring back the photos the database lists and this phone lacks. Returns how many are missing. */
    private fun restorePages(ctx: Context, tree: Uri?, dirId: String?, names: Set<String>, key: ByteArray): Int {
        val missing = names.filter { !Pages.file(ctx, it).exists() }
        if (missing.isEmpty()) return 0
        if (tree == null || dirId == null) return missing.size
        val cr = ctx.contentResolver
        val pagesDir = findChild(cr, tree, dirId, PAGES_DIR) ?: return missing.size
        val files = children(cr, tree, DocumentsContract.getDocumentId(pagesDir))
        var lost = 0
        for (n in missing) {
            val ok = try {
                val u = files[sealedName(n)]
                val blob = u?.let { cr.openInputStream(it)?.use { s -> s.readBytes() } }
                if (blob == null) false else { Pages.file(ctx, n).writeBytes(Vault.open(blob, key)); true }
            } catch (e: Exception) { false }
            if (!ok) lost++
        }
        return lost
    }

    /** Photos on this phone that the database (just imported) does not list. */
    private fun dropOrphanPages(ctx: Context, keep: Set<String>) {
        File(ctx.filesDir, "pages").listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun versionText(now: LocalDateTime): String =
        "PENSY_DATA\n" +
            "formato: ${Vault.FORMAT}\n" +
            "schema: ${Db.VERSION}\n" +
            "ultimo salvataggio: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}\n"

    // --------------------------------------------------------------- export

    fun exportFile(act: MainActivity) {
        if (!hasKey(act)) { setPassword(act, R.string.password_new_title) { exportFile(act) }; return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, "PENSY_DATA-${LocalDate.now()}.pensy")
        try {
            act.startExternal(i, MainActivity.REQ_EXPORT)
        } catch (e: ActivityNotFoundException) {
            act.toast(R.string.no_picker)
        }
    }

    fun onExportTarget(act: MainActivity, uri: Uri) {
        act.showBusy(R.string.exporting)
        worker.execute {
            val ok = try {
                val blob = sealedExport(act) ?: throw IllegalStateException("no key")
                act.contentResolver.openOutputStream(uri, "w")?.use { it.write(blob) } != null
            } catch (e: Throwable) { false }
            main.post { act.hideBusy(); act.toast(if (ok) R.string.export_done else R.string.export_error) }
        }
    }

    /**
     * Everything in one sealed file: the database alone (as always) or,
     * when photos are kept, database and photos together (see core/Pack).
     */
    fun sealedExport(ctx: Context): ByteArray? {
        val (key, salt, iter) = key(ctx) ?: return null
        val db = ctx.db
        val bytes = db.snapshot()
        val names = db.allPageNames()
        val plain = if (names.isEmpty()) bytes else {
            val pages = LinkedHashMap<String, ByteArray>()
            for (n in names) { val f = Pages.file(ctx, n); if (f.exists()) pages[n] = f.readBytes() }
            Pack.pack(bytes, pages)
        }
        return Vault.seal(plain, key, salt, iter)
    }

    /** "Invia a un altro telefono": the sealed file, through the share sheet (Quick Share, Bluetooth, cable...). */
    fun sendToPhone(act: MainActivity) {
        if (!hasKey(act)) { setPassword(act, R.string.password_new_title) { sendToPhone(act) }; return }
        act.showBusy(R.string.exporting)
        worker.execute {
            val f = try {
                val blob = sealedExport(act) ?: throw IllegalStateException("no key")
                Share.newFile(act, "PENSY_DATA-${LocalDate.now()}.pensy").apply { writeBytes(blob) }
            } catch (e: Throwable) { null }
            main.post {
                act.hideBusy()
                if (f == null) act.toast(R.string.export_error)
                else Share.send(act, f, "application/octet-stream", act.getString(R.string.send_phone_title))
            }
        }
    }

    // --------------------------------------------------------------- import

    fun chooseImport(act: MainActivity) {
        Dialogs.sheet(act, act.getString(R.string.import_title), listOf(
            Triple(R.drawable.ic_folder, act.getString(R.string.import_from_folder)) {
                start(act, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                ), MainActivity.REQ_IMPORT_TREE)
            },
            Triple(R.drawable.ic_doc, act.getString(R.string.import_from_file)) {
                start(act, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    MainActivity.REQ_IMPORT_FILE)
            }
        ))
    }

    private fun start(act: MainActivity, i: Intent, req: Int) {
        try { act.startExternal(i, req) } catch (e: ActivityNotFoundException) { act.toast(R.string.no_picker) }
    }

    fun onImportSource(act: MainActivity, uri: Uri, isTree: Boolean) {
        act.showBusy(R.string.reading_document)
        worker.execute {
            var dirId: String? = null
            val blob: ByteArray? = try {
                val cr = act.contentResolver
                if (isTree) {
                    val rootId = DocumentsContract.getTreeDocumentId(uri)
                    val direct = findChild(cr, uri, rootId, DATA_FILE)
                    var nestedDir: String? = null
                    val nested = if (direct == null) findChild(cr, uri, rootId, DIR_NAME)?.let {
                        nestedDir = DocumentsContract.getDocumentId(it)
                        findChild(cr, uri, nestedDir!!, DATA_FILE)
                    } else null
                    dirId = if (direct != null) rootId else nestedDir
                    (direct ?: nested)?.let { f -> cr.openInputStream(f)?.use { it.readBytes() } }
                } else {
                    cr.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: Exception) { null }
            val fDir = dirId
            main.post {
                act.hideBusy()
                if (blob == null) { act.toast(R.string.import_not_found); return@post }
                try { Vault.header(blob) } catch (e: Vault.BadFormat) { act.toast(R.string.import_bad_file); return@post }
                askAndOpen(act, blob, if (isTree) uri else null, fDir)
            }
        }
    }

    /** A .pensy file opened from another app (Files, Quick Share): import it. */
    fun onOpenFile(act: MainActivity, uri: Uri) = onImportSource(act, uri, isTree = false)

    private fun askAndOpen(act: MainActivity, blob: ByteArray, tree: Uri?, dirId: String?) {
        Dialogs.password(act, R.string.import_password_title, act.getString(R.string.import_password_msg)) { pw ->
            act.showBusy(R.string.opening)
            worker.execute {
                var error = 0
                var tmp: File? = null
                var summary: Db.Summary? = null
                var key: ByteArray? = null
                var packed: Map<String, ByteArray> = emptyMap()
                try {
                    val (opened, k) = Vault.openWithPassword(blob, pw)
                    key = k
                    var plain = opened
                    if (Pack.isZip(plain)) {
                        val content = Pack.unpack(plain)
                        plain = content.db
                        packed = content.pages
                    }
                    if (!Vault.isSqlite(plain)) throw Vault.BadFormat("not a database")
                    val f = File(act.cacheDir, "import.db")
                    f.writeBytes(plain)
                    tmp = f
                    val sm = act.db.summaryOf(f)
                    summary = sm
                    if (sm.version > Db.VERSION) error = R.string.import_newer
                } catch (e: Vault.WrongPassword) {
                    error = R.string.wrong_password
                } catch (e: Exception) {
                    error = R.string.import_bad_file
                } finally {
                    pw.fill('\u0000')
                }
                val fError = error
                val fTmp = tmp
                val fSummary = summary
                val fKey = key
                val fPacked = packed
                main.post {
                    act.hideBusy()
                    if (fError != 0 || fSummary == null || fTmp == null || fKey == null) {
                        fTmp?.delete()
                        act.toast(if (fError != 0) fError else R.string.import_bad_file)
                        return@post
                    }
                    Dialogs.confirm(act, R.string.import_confirm_title,
                        act.getString(R.string.import_confirm_msg, fSummary.docs, fSummary.deadlines, fSummary.incomes) +
                            (if (fSummary.health > 0) "\n\n" + act.resources.getQuantityString(R.plurals.import_health, fSummary.health, fSummary.health) else "") +
                            (if (fSummary.profiles > 1) "\n\n" + act.resources.getQuantityString(R.plurals.import_profiles, fSummary.profiles, fSummary.profiles) else "") +
                            (if (fSummary.pages.isNotEmpty()) "\n\n" + act.resources.getQuantityString(R.plurals.import_pages_count, fSummary.pages.size, fSummary.pages.size) else ""),
                        R.string.import_do) {
                        act.db.replaceWith(fTmp)
                        fTmp.delete()
                        val h = Vault.header(blob)
                        storeKey(act, fKey, h.salt, h.iterations)
                        if (tree != null) {
                            // Keep using the imported folder as this phone's PENSY_DATA.
                            try {
                                act.contentResolver.takePersistableUriPermission(
                                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                                val dir = ensureDirSafe(act.contentResolver, tree)
                                if (dir != null) prefs(act).edit().putString("tree", tree.toString()).putString("dir", dir.toString()).apply()
                            } catch (e: SecurityException) {
                                // read-only folder: fine, the user can choose another one
                            }
                        }
                        Notify.schedule(act)
                        Doses.schedule(act)
                        Tour.setDone(act)  // data from the old phone: no first-launch steps
                        act.showTab(0)
                        // The photos: from the file itself, or from PENSY_DATA/pagine.
                        act.showBusy(R.string.import_pages)
                        worker.execute {
                            for ((n, b) in fPacked) if (n in fSummary.pages) runCatching { Pages.file(act, n).writeBytes(b) }
                            dropOrphanPages(act, fSummary.pages)
                            val lost = restorePages(act, tree, dirId, fSummary.pages, fKey)
                            main.post {
                                act.hideBusy()
                                if (lost > 0) act.toast(act.resources.getQuantityString(R.plurals.import_pages_lost, lost, lost))
                                else act.toast(R.string.import_done)
                                act.refresh()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ensureDirSafe(cr: ContentResolver, tree: Uri): Uri? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        if (findChild(cr, tree, rootId, DATA_FILE) != null) return root
        return findChild(cr, tree, rootId, DIR_NAME)
    }
}
