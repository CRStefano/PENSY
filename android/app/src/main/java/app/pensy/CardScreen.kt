package app.pensy

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import app.pensy.core.Budget
import app.pensy.core.Card
import app.pensy.core.Code
import app.pensy.core.Deadline
import app.pensy.core.DocType
import app.pensy.core.FolderKind
import app.pensy.core.Income
import app.pensy.core.Money
import app.pensy.core.Parser
import app.pensy.core.Sorter
import app.pensy.core.Visit
import app.pensy.core.VisitStatus
import app.pensy.core.parseFields
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * The confirmation (and edit) form. Whatever the reader guessed is shown
 * here, editable; nothing is saved until the user taps "Salva".
 *
 * Every item is a document filed in a folder; it may also carry a
 * deadline (something to pay or do by a date).
 *
 *  - card != null      new document from a photo or PDF
 *  - editId != null    edit a deadline (and its document)
 *  - docId != null     edit a document; with addDeadline, attach a new deadline to it
 *  - incomeId != null  edit a payslip
 *  - none              manual entry (folderHint: where it goes)
 *
 * A medical report does not carry a deadline: the follow-up it asks for
 * ("controllo tra 12 mesi") becomes a visit to book in the health record.
 * `linkVisit`: the visit this new document is the report of.
 * `pages`: photos of the pages just read, waiting in the cache; they are
 * kept with the document only if "Conserva la foto" is on.
 */
class CardScreen(
    act: MainActivity,
    private val card: Card? = null,
    private val editId: Long? = null,
    private val incomeId: Long? = null,
    private val docId: Long? = null,
    private val addDeadline: Boolean = false,
    private val folderHint: Long? = null,
    private val linkVisit: Long? = null,
    pages: List<File> = emptyList()
) : Screen(act) {

    /** The reading so far (updated when pages are added). */
    private var parsed: Card? = card
    private val pending = ArrayList<File>(pages)
    private var keepPhoto = Pages.keepByDefault(act)
    private var saved = false

    private var type: DocType = DocType.ALTRO
    private var issuer = ""
    private var title = ""
    private var amountText = ""
    private var due: LocalDate? = null
    private var every = 0
    private var remind = act.db.remindDays()
    private var remindTouched = false
    /** Subscriptions: the last day to cancel before it renews (a second reminder). */
    private var cancelBy: LocalDate? = null
    private var paid = false
    private var paidOn: LocalDate = LocalDate.now()
    /** No due date: turned on by itself when "already paid" is ticked (the user can turn it off). */
    private var noDue = false
    private var noDueAuto = false
    private var visitRemind = 30
    private var note = ""
    private var payMonth: YearMonth = YearMonth.now()
    private var extra = false
    private val codes = ArrayList<Code>()
    private var docText = ""
    private var folderId: Long? = null
    private var docDate: LocalDate? = null
    private var hasDeadline = true
    private var folderTouched = false
    private var deadlineTouched = false
    private var original: Deadline? = null
    private var originalIncome: Income? = null
    private var originalDoc: Db.DocRow? = null

    init {
        val db = act.db
        when {
            editId != null -> db.deadline(editId)?.let { d ->
                original = d
                val doc = d.docId?.let { db.doc(it) }
                originalDoc = doc
                type = d.type; issuer = d.issuer; title = doc?.title ?: d.title
                amountText = if (d.amountCents > 0) Money.toInput(d.amountCents) else ""
                due = d.due; every = d.everyMonths
                remind = d.remindDays; paid = d.paid; note = d.note
                noDue = d.noDue; paidOn = d.paidOn ?: LocalDate.now()
                codes += db.codes(d.docId)
                docText = doc?.text ?: ""
                // A warranty keeps the price in its document, not in the reminder.
                if (d.type == DocType.GARANZIA) parseFields(doc?.fields ?: "")["prezzo"]?.toLongOrNull()?.let { amountText = Money.toInput(it) }
                folderId = d.folderId ?: doc?.folderId
                docDate = doc?.date
                hasDeadline = true
            }
            incomeId != null -> db.income(incomeId)?.let { i ->
                originalIncome = i
                val doc = i.docId?.let { db.doc(it) }
                originalDoc = doc
                type = DocType.BUSTA_PAGA; amountText = Money.toInput(i.amountCents)
                payMonth = i.month; extra = i.extra; note = i.note
                docText = doc?.text ?: ""
                folderId = doc?.folderId ?: topFolder(FolderKind.LAVORO)
            }
            docId != null -> db.doc(docId)?.let { doc ->
                originalDoc = doc
                type = doc.type; issuer = doc.issuer; title = doc.title
                folderId = doc.folderId; docDate = doc.date; note = doc.note
                codes += db.codes(docId)
                docText = doc.text
                hasDeadline = addDeadline
                if (addDeadline) { note = ""; due = null }
            }
            card != null -> {
                type = card.type; issuer = card.issuer
                amountText = card.amountCents?.let { Money.toInput(it) } ?: ""
                due = card.due ?: card.followUp.takeIf { !card.type.payable }
                every = card.everyMonths
                card.payMonth?.let { payMonth = it }
                codes += card.codes
                docText = card.text
                docDate = card.docDate
                hasDeadline = card.type.payable || card.amountCents != null || card.due != null || card.followUp != null
                remind = Fmt.defaultRemind(card.type, db.remindDays())
                folderId = folderHint ?: suggest()
                if (folderHint != null) folderTouched = true
            }
            else -> {
                folderId = folderHint ?: topFolder(FolderKind.ALTRO)
                folderTouched = folderHint != null
            }
        }
    }

    private fun topFolder(kind: FolderKind): Long? =
        act.db.folders().firstOrNull { it.parentId == null && it.kind == kind.code }?.id

    private fun suggest(): Long? {
        val db = act.db
        return Sorter.suggest(docText + "\n" + title, type, issuer, db.folders(), db.rules())
    }

    private val isPayslip get() = type == DocType.BUSTA_PAGA
    /** A warranty: the date is its end, the amount is the price paid (it does not count as an expense again). */
    private val isWarranty get() = type == DocType.GARANZIA
    /** A report: what is "to do" is a visit to book, not a deadline. */
    private val visitMode get() = type == DocType.REFERTO && editId == null && !addDeadline
    private val isNewDoc get() = editId == null && incomeId == null && docId == null
    private val isNewDeadline get() = editId == null

    override fun build(): View {
        val c = act
        val body = c.vbox()

        if (card != null) {
            val found = card.amountCents != null || card.due != null || codes.isNotEmpty() || card.docDate != null
            body.add(hint(c.getString(if (found) R.string.card_check else R.string.card_nothing)), bottom = 6)
            if (!card.type.payable && card.followUp != null && card.due == null) {
                val res = if (card.type == DocType.REFERTO) R.string.followup_visit else R.string.followup_found
                body.add(hint(c.getString(res, Fmt.date(card.followUp))), top = 8, bottom = 6)
            }
        }

        body.add(c.labeled(c.getString(R.string.field_type), c.chips(DocType.entries.map { it to Fmt.type(c, it) }, type) { t ->
            val wasPayslip = isPayslip
            type = t
            var rebuild = wasPayslip != isPayslip
            if (isNewDoc && !folderTouched) { folderId = if (isPayslip) topFolder(FolderKind.LAVORO) else suggest(); rebuild = true }
            if (!deadlineTouched && isNewDoc && !isPayslip) {
                val want = t.payable || amountText.isNotBlank() || due != null
                if (want != hasDeadline) { hasDeadline = want; rebuild = true }
            }
            if (isNewDeadline && !remindTouched) {
                val r = Fmt.defaultRemind(t, act.db.remindDays())
                if (r != remind) { remind = r; rebuild = true }
            }
            if (rebuild) refresh()
        }), top = 12)

        if (isPayslip) buildPayslip(body) else buildDocument(body)

        if (isNewDoc) body.add(pagesSection(), top = 18)

        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.field_note_hint), note, lines = 3) { note = it }), top = 18)

        if (docText.isNotBlank()) body.add(textSection(), top = 18)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { act.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)

        if (incomeId != null) {
            body.add(c.button(c.getString(R.string.delete), Btn.DANGER, R.drawable.ic_delete) {
                Dialogs.confirm(act, R.string.delete_income_title, c.getString(R.string.delete_income_msg), R.string.delete) {
                    act.db.deleteIncome(incomeId); act.changed(); act.pop()
                }
            }, top = 12)
        }

        val titleRes = when {
            isPayslip && incomeId == null -> R.string.title_new_payslip
            isPayslip -> R.string.title_edit_payslip
            addDeadline -> R.string.add_reminder
            docId != null -> R.string.title_edit_doc
            editId != null -> R.string.title_edit_deadline
            else -> R.string.title_new_doc
        }
        return page(c.getString(titleRes), back = true, body = body)
    }

    private fun hint(s: String): View = act.text(s, 14f, act.col(R.color.ink2)).apply {
        background = act.rounded(act.col(R.color.accent_soft), 14)
        setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
    }

    private fun amountField(hintRes: Int): View = act.field(act.getString(hintRes), amountText,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { amountText = it }.apply {
        keyListener = DigitsKeyListener.getInstance("0123456789,.")
        textSize = 22f
        typeface = MEDIUM
    }

    private fun dateButton(value: LocalDate?, emptyRes: Int, onPick: (LocalDate) -> Unit): View {
        val c = act
        val b = c.button(value?.let { Fmt.date(it) } ?: c.getString(emptyRes), Btn.GHOST, R.drawable.ic_calendar) {}
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        b.setOnClickListener {
            val d0 = value ?: LocalDate.now()
            DatePickerDialog(c, c.dialogTheme(), { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                b.text = Fmt.date(picked)
                onPick(picked)
            }, d0.year, d0.monthValue - 1, d0.dayOfMonth).show()
        }
        return b
    }

    private fun folderButton(): View {
        val c = act
        val folders = c.db.folders()
        val path = Sorter.path(folders, folderId)
        val row = c.hbox().apply {
            background = c.ripple(c.rounded(c.col(R.color.surface2), 14), 14)
            setPadding(c.dp(12), c.dp(10), c.dp(14), c.dp(10))
            setOnClickListener {
                Dialogs.pickFolder(c, folderId) { id -> folderId = id; folderTouched = true; refresh() }
            }
        }
        path.lastOrNull()?.let { row.add(c.folderBadge(it, folders, 34), w = c.dp(34), h = c.dp(34)) }
        row.add(c.text(if (path.isEmpty()) "—" else path.joinToString(" › ") { it.name }, 16f, face = MEDIUM), w = 0, weight = 1f, start = 12)
        row.add(c.text(c.getString(R.string.change), 14f, c.col(R.color.accent_ink), MEDIUM), w = WRAP)
        return row
    }

    private fun buildDocument(body: LinearLayout) {
        val c = act
        body.add(c.labeled(c.getString(R.string.field_issuer), c.field(c.getString(R.string.field_issuer_hint), issuer) { issuer = it }), top = 18)
        body.add(c.labeled(c.getString(R.string.field_title), c.field(c.getString(R.string.field_title_hint), title) { title = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.field_folder), folderButton()), top = 14)
        body.add(c.labeled(c.getString(R.string.field_doc_date), dateButton(docDate, R.string.field_date_none) { docDate = it }), top = 14)

        body.add(c.toggleRow(c.getString(if (visitMode) R.string.field_followup else R.string.field_has_deadline), hasDeadline) {
            hasDeadline = it; deadlineTouched = true; refresh()
        }, top = 16)

        if (hasDeadline && visitMode) {
            // A report: the follow-up goes to the visits to book.
            val box = c.card(16, c.col(R.color.surface))
            box.add(c.labeled(c.getString(R.string.followup_by), c.dateRow(due, c.getString(R.string.field_date_none),
                { due = it; refresh() }, if (due != null) ({ due = null; refresh() }) else null)))
            if (due != null) {
                box.add(c.labeled(c.getString(R.string.visit_remind), c.chips(listOf(7, 15, 30, 60).map {
                    it to c.resources.getQuantityString(R.plurals.days_before, it, it)
                }, visitRemind) { visitRemind = it }), top = 14)
            }
            box.add(c.text(c.getString(R.string.followup_explain), 13f, c.col(R.color.ink2)), top = 10, start = 4)
            body.add(box, top = 8)
        } else if (hasDeadline) {
            val box = c.card(16, c.col(R.color.surface))
            box.add(c.labeled(c.getString(if (isWarranty) R.string.field_price else R.string.field_amount), amountField(R.string.field_amount_hint)))
            if (isWarranty) box.add(c.text(c.getString(R.string.field_price_hint), 13f, c.col(R.color.ink2)), top = 4, start = 4)
            val orig = original
            if (isNewDeadline && !isWarranty) {
                box.add(c.toggleRow(c.getString(R.string.field_already_paid), paid) {
                    paid = it
                    // Paid already: a due date makes no sense any more, so "no due date" turns on.
                    if (it && !noDue) { noDue = true; noDueAuto = true }
                    else if (!it && noDueAuto) { noDue = false; noDueAuto = false }
                    refresh()
                }, top = 12)
            }
            if (paid || (orig != null && orig.paid)) {
                box.add(c.labeled(c.getString(R.string.field_paid_on), c.dateButton(paidOn, "") { paidOn = it }), top = 8)
            }
            if (!noDue) {
                box.add(c.labeled(c.getString(if (isWarranty) R.string.field_warranty_end else R.string.field_due),
                    c.dateButton(due, c.getString(R.string.field_due_pick)) { due = it }), top = 14)
            }
            if (!isWarranty) box.add(c.toggleRow(c.getString(R.string.field_no_due), noDue) { noDue = it; noDueAuto = false; refresh() }, top = if (noDue) 12 else 4)
            if (!noDue && !isWarranty) {
                box.add(c.labeled(c.getString(R.string.field_every),
                    c.chips(Fmt.EVERY_OPTIONS.map { it to Fmt.every(c, it) }, every) { every = it }), top = 12)
            }
            if (!noDue) {
                box.add(c.labeled(c.getString(R.string.field_remind),
                    c.chips(Fmt.REMIND_OPTIONS.map { it to c.resources.getQuantityString(R.plurals.days_before, it, it) }, remind) {
                        remind = it; remindTouched = true
                    }), top = 16)
            }
            if (type == DocType.ABBONAMENTO && isNewDeadline && !noDue) {
                box.add(c.labeled(c.getString(R.string.field_cancel_by), c.dateRow(cancelBy, c.getString(R.string.field_cancel_none),
                    { cancelBy = it; refresh() }, if (cancelBy != null) ({ cancelBy = null; refresh() }) else null)), top = 16)
                box.add(c.text(c.getString(R.string.field_cancel_hint), 13f, c.col(R.color.ink2)), top = 4, start = 4)
            }
            body.add(box, top = 8)
        }

        parsed?.nre?.let { nre ->
            body.add(c.labeled(c.getString(R.string.nre_label), c.text(Fmt.nre(nre), 19f, face = Typeface.MONOSPACE).apply {
                setTextIsSelectable(true)
            }), top = 18)
        }
        parsed?.noticeCode?.let { notice ->
            body.add(c.labeled(c.getString(R.string.notice_code), c.text(notice.chunked(4).joinToString(" "), 17f, face = Typeface.MONOSPACE).apply {
                setTextIsSelectable(true)
            }), top = 18)
        }
        body.add(codesSection(), top = 18)
    }

    private fun buildPayslip(body: LinearLayout) {
        val c = act
        body.add(c.labeled(c.getString(R.string.field_net), amountField(R.string.field_net_hint)), top = 18)
        val monthBtn = c.button(Fmt.month(payMonth), Btn.GHOST, R.drawable.ic_calendar) {}
        monthBtn.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        monthBtn.setOnClickListener {
            DatePickerDialog(c, c.dialogTheme(), { _, y, m, _ ->
                payMonth = YearMonth.of(y, m + 1)
                monthBtn.text = Fmt.month(payMonth)
            }, payMonth.year, payMonth.monthValue - 1, 1).show()
        }
        body.add(c.labeled(c.getString(R.string.field_month), monthBtn), top = 14)
        body.add(c.toggleRow(c.getString(R.string.field_extra), extra) { extra = it }, top = 14)
        body.add(c.text(c.getString(R.string.field_extra_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        if (parsed != null || originalDoc != null) body.add(c.labeled(c.getString(R.string.field_folder), folderButton()), top = 14)
    }

    /** Photos of the pages read so far: add one more, keep them or not. */
    private fun pagesSection(): View {
        val c = act
        val box = c.vbox()
        box.add(c.text(c.getString(R.string.pages_title), 13f, c.col(R.color.ink2), MEDIUM), start = 4)
        if (pending.isNotEmpty()) {
            val strip = c.hbox()
            pending.forEachIndexed { i, f ->
                val iv = ImageView(c).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = c.rounded(c.col(R.color.surface2), 10)
                    clipToOutline = true
                    Pages.loadFile(f, c.dp(120))?.let { setImageBitmap(it) }
                    setOnClickListener { Viewer.show(c, pending.toList(), i, c.getString(R.string.pages_title)) }
                }
                strip.add(iv, w = c.dp(64), h = c.dp(86), end = 8)
            }
            val sc = android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
            box.add(sc, top = 8)
            box.add(c.text(c.resources.getQuantityString(R.plurals.pages_read, pending.size, pending.size), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        }
        box.add(c.button(c.getString(R.string.page_add), Btn.SECONDARY, R.drawable.ic_add) {
            Capture.addPage(c, Capture.Target.Card(this))
        }, w = WRAP, top = 8)
        if (pending.isNotEmpty()) {
            box.add(c.toggleRow(c.getString(R.string.keep_photo), keepPhoto) { keepPhoto = it }, top = 8)
            box.add(c.text(c.getString(R.string.keep_photo_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        }
        return box
    }

    /** More pages read for this document: their text joins the rest, empty fields are filled again. */
    fun addPages(read: Capture.Read) {
        val c = act
        pending += read.files
        if (read.text.isNotBlank()) docText = if (docText.isBlank()) read.text else docText + "\n\n" + read.text
        for (code in read.codes) if (codes.none { it.format == code.format && it.content == code.content }) codes += code
        val again = Parser.parse(docText, codes, LocalDate.now())
        parsed = (parsed ?: again).copy(
            text = docText, codes = codes.toList(),
            noticeCode = parsed?.noticeCode ?: again.noticeCode, creditorCf = parsed?.creditorCf ?: again.creditorCf,
            nre = parsed?.nre ?: again.nre
        )
        if (type == DocType.ALTRO && again.type != DocType.ALTRO) type = again.type
        if (issuer.isBlank()) issuer = again.issuer
        if (amountText.isBlank()) again.amountCents?.let { amountText = Money.toInput(it) }
        if (due == null && !noDue) due = again.due
        if (docDate == null) docDate = again.docDate
        if (!deadlineTouched && !hasDeadline && (again.due != null || again.amountCents != null)) hasDeadline = true
        c.toast(c.resources.getQuantityString(R.plurals.pages_added, read.files.size.coerceAtLeast(1), read.files.size.coerceAtLeast(1)))
        refresh()
    }

    override fun onClose() {
        if (!saved) Pages.discard(pending)
    }

    private fun codesSection(): View {
        val c = act
        val box = c.vbox()
        box.add(c.text(c.getString(R.string.codes_title), 13f, c.col(R.color.ink2), MEDIUM), start = 4)
        if (codes.isEmpty()) {
            box.add(c.text(c.getString(R.string.codes_none), 14f, c.col(R.color.ink3)), top = 6, start = 4)
            return box
        }
        for (code in codes.toList()) {
            val row = c.card(12)
            val top = c.hbox()
            val bmp = Codes.render(code, c.dp(if (Codes.isTwoD(code)) 64 else 120))
            if (bmp != null) {
                val iv = ImageView(c).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
                }
                top.add(iv, w = WRAP, h = c.dp(64))
            }
            val info = c.vbox()
            info.add(c.text(code.format.replace('_', ' '), 14f, face = MEDIUM))
            info.add(c.text(code.content, 12f, c.col(R.color.ink2), lines = 2).apply { typeface = Typeface.MONOSPACE }, top = 2)
            top.add(info, w = 0, weight = 1f, start = 12)
            if (isNewDoc) top.add(c.iconButton(R.drawable.ic_delete, R.string.delete, c.col(R.color.ink2)) {
                codes.remove(code); refresh()
            }, w = WRAP)
            row.add(top)
            box.add(row, top = 8)
        }
        return box
    }

    private fun textSection(): View {
        val c = act
        val box = c.vbox()
        val head = c.hbox().apply { background = c.ripple(null, 12); setPadding(c.dp(4), c.dp(6), c.dp(4), c.dp(6)) }
        head.add(c.text(c.getString(R.string.doc_text), 13f, c.col(R.color.ink2), MEDIUM), w = 0, weight = 1f)
        val arrow = c.text("▾", 16f, c.col(R.color.ink2))
        head.add(arrow, w = WRAP)
        val txt = c.text(docText, 13f, c.col(R.color.ink2)).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = c.rounded(c.col(R.color.surface2), 14)
            setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            visibility = View.GONE
        }
        head.setOnClickListener {
            val open = txt.visibility != View.VISIBLE
            txt.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "▴" else "▾"
        }
        box.add(head)
        box.add(txt, top = 6)
        return box
    }

    // ------------------------------------------------------------------ save

    /** The new document is saved: its page photos become part of it, or go. */
    private fun keepPages(docId: Long) {
        if (keepPhoto) Pages.adopt(act, docId, pending) else Pages.discard(pending)
        pending.clear()
        saved = true
    }

    private fun save() {
        val c = act
        val db = c.db
        val amount = if (amountText.isBlank()) 0L else Money.parse(amountText) ?: run { c.toast(R.string.bad_amount); return }

        if (isPayslip) {
            if (amount <= 0) { c.toast(R.string.need_net); return }
            val folder = folderId ?: topFolder(FolderKind.LAVORO)
            val doc = originalDoc
            val read = parsed
            val savedDoc = when {
                doc != null -> { db.updateDoc(doc.id, type, issuer.trim(), doc.title, folder, payMonth.atDay(1), doc.note); doc.id }
                read != null -> db.saveDoc(type, issuer.trim(), "", docText, read.copy(payMonth = payMonth).fields(), folder, payMonth.atDay(1))
                    .also { id -> db.saveCodes(id, codes); keepPages(id) }
                else -> null
            }
            // A deadline turned into a payslip while editing: remove the deadline.
            original?.let { db.deleteDeadline(it.id) }
            db.saveIncome(Income(id = originalIncome?.id ?: 0, month = payMonth, amountCents = amount, docId = savedDoc ?: originalIncome?.docId,
                extra = extra, note = note.trim()))
            c.toast(R.string.saved_income)
            c.changed()
            c.pop()
            return
        }

        val d = due
        if (hasDeadline && !visitMode && !noDue && d == null) { c.toast(R.string.need_due); return }
        val folder = folderId
        val cleanTitle = title.trim()
        val cleanIssuer = issuer.trim()

        // 1. The document: every item lives in a folder.
        //    (A deadline PENSY created from a recurrence has no document: keep it that way.)
        val existing = originalDoc ?: original?.docId?.let { db.doc(it) }
        val theDocId: Long? = if (existing != null) {
            val docNote = if (editId != null) existing.note else note.trim()
            db.updateDoc(existing.id, type, cleanIssuer, cleanTitle, folder, docDate, if (addDeadline) existing.note else docNote)
            existing.id
        } else if (editId != null) {
            null
        } else {
            val price = if (isWarranty && amount > 0) "prezzo=$amount\n" else ""
            val id = db.saveDoc(type, cleanIssuer, cleanTitle, docText, (parsed?.fields() ?: "") + price, folder, docDate,
                note = if (hasDeadline && !visitMode) "" else note.trim())
            db.saveCodes(id, codes)
            keepPages(id)
            id
        }
        if (existing != null && isWarranty) db.setDocField(existing.id, "prezzo", if (amount > 0) amount.toString() else null)
        if (isNewDoc || folderTouched) db.learn(cleanIssuer, folder)
        // The report of a visit: link them.
        if (linkVisit != null && theDocId != null) db.visit(linkVisit)?.let { db.saveVisit(it.copy(docId = theDocId)) }

        // 2a. A report with a follow-up: a visit to book.
        if (visitMode) {
            if (hasDeadline) {
                db.saveVisit(Visit(
                    docId = theDocId, folderId = folder, status = VisitStatus.TODO, dueBy = d,
                    title = c.getString(R.string.followup_title, Fmt.title(c, cleanTitle, type, cleanIssuer)),
                    remindDays = visitRemind
                ))
                c.toast(R.string.saved_visit)
            } else c.toast(R.string.saved)
            c.changed()
            c.pop()
            return
        }

        // 2. The deadline, if any.
        val orig = original
        if (hasDeadline && (d != null || noDue)) {
            val dlAmount = if (isWarranty) 0L else amount
            val dlTitle = if (!isWarranty && (type.payable || amount > 0)) cleanTitle
            else Fmt.reminderTitle(c, type, Fmt.title(c, cleanTitle, type, cleanIssuer))
            // Without a due date the stored day is the payment day (or, while unpaid, the day it was added).
            val isPaid = paid || (orig != null && orig.paid)
            val day = when {
                !noDue -> d!!
                isPaid -> paidOn
                else -> orig?.due ?: LocalDate.now()
            }
            if (orig != null) {
                val dueChanged = orig.due != day || orig.remindDays != remind || orig.noDue != noDue
                db.saveDeadline(orig.copy(
                    type = type, issuer = cleanIssuer, title = dlTitle, amountCents = dlAmount, due = day,
                    everyMonths = if (noDue || isWarranty) 0 else every, remindDays = remind, note = note.trim(), docId = theDocId, folderId = folder,
                    estimated = if (orig.amountCents != dlAmount) false else orig.estimated,
                    notified = if (dueChanged) 0 else orig.notified,
                    noDue = noDue, paidOn = if (orig.paid) paidOn else orig.paidOn
                ))
                c.toast(R.string.saved)
            } else {
                // If PENSY had guessed this occurrence from a recurrence, fill it in instead of duplicating it.
                val match = if (paid || noDue || !type.payable) null else Budget.matchEstimated(db.deadlines(), type, cleanIssuer, day)
                val id = db.saveDeadline(Deadline(
                    id = match?.id ?: 0, docId = theDocId, type = type, title = dlTitle, issuer = cleanIssuer,
                    amountCents = dlAmount, due = day, everyMonths = if (noDue || isWarranty) 0 else every, remindDays = remind,
                    note = if (existing == null || addDeadline) note.trim() else "", folderId = folder, noDue = noDue
                ))
                if (paid && !isWarranty) db.markPaid(id, paidOn)
                // A subscription: the last day to cancel, as its own reminder (every year if the contract is yearly).
                val cb = cancelBy
                if (type == DocType.ABBONAMENTO && cb != null && !noDue) db.saveDeadline(Deadline(
                    docId = theDocId, type = DocType.ABBONAMENTO, title = c.getString(R.string.rem_cancel, Fmt.title(c, cleanTitle, type, cleanIssuer)),
                    issuer = cleanIssuer, amountCents = 0, due = cb, everyMonths = if (every >= 12) every else 0, remindDays = 30, folderId = folder
                ))
                if (match != null) c.toast(R.string.saved_updated_estimate)
                else { c.changed(); c.pop(); whereNotice(folder, if (noDue) null else day, remind, !paid || isWarranty); return }
            }
        } else {
            if (orig != null) db.deleteDeadline(orig.id)
            if (existing == null && editId == null) { c.changed(); c.pop(); whereNotice(folder, null, 0, false); return }
            c.toast(R.string.saved)
        }
        c.changed()
        c.pop()
    }

    /** After saving something new: where it went and when PENSY will remind it, with "Vedi" to open its folder. */
    private fun whereNotice(folder: Long?, due: LocalDate?, remind: Int, open: Boolean) {
        val c = act
        val path = app.pensy.core.Sorter.path(c.db.folders(), folder).joinToString(" › ") { it.name }
        var msg = if (path.isEmpty()) c.getString(R.string.saved) else c.getString(R.string.saved_in, path)
        val today = LocalDate.now()
        if (due != null && open) {
            val on = due.minusDays(remind.toLong())
            msg += " " + when {
                remind > 0 && on.isAfter(today) -> c.getString(R.string.saved_remind_on, Fmt.date(on))
                !due.isBefore(today) -> c.getString(R.string.saved_due, Fmt.relative(c, today, due))
                else -> c.getString(R.string.saved_late)
            }
        }
        if (folder == null) { c.notice(msg); return }
        c.notice(msg, c.getString(R.string.saved_see)) { c.showTab(1); c.push(FolderScreen(c, folder)) }
    }
}
