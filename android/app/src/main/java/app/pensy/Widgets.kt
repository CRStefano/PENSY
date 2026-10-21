package app.pensy

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pensy.core.Code
import app.pensy.core.Deadline
import app.pensy.core.DocType
import app.pensy.core.Folder
import app.pensy.core.FolderKind
import app.pensy.core.Money
import app.pensy.core.MonthLine
import app.pensy.core.Reminders
import app.pensy.core.Sorter
import java.time.LocalDate
import java.time.LocalTime

/*
 * Pieces shared by several screens: the page frame, list rows, folder
 * tiles, codes, the floating "Aggiungi" button.
 */

/**
 * Standard page: optional top bar (back arrow, title, action icons) and a
 * scrolling body. The body gets the usual side margins.
 */
fun Screen.page(
    title: String?,
    back: Boolean = false,
    actions: List<Triple<Int, Int, () -> Unit>> = emptyList(),
    body: LinearLayout
): LinearLayout {
    val c = act
    val col = c.vbox()
    if (title != null || back || actions.isNotEmpty()) {
        val bar = c.hbox().apply { setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(2)) }
        if (back) bar.add(c.iconButton(R.drawable.ic_back, R.string.back) { act.pop() }, w = WRAP)
        bar.add(c.text(title ?: "", 20f, face = BOLD, lines = 1).apply {
            setPadding(c.dp(if (back) 4 else 14), 0, 0, 0)
        }, w = 0, weight = 1f)
        for ((icon, desc, fn) in actions) bar.add(c.iconButton(icon, desc, onClick = fn), w = WRAP)
        col.add(bar)
    }
    val sv = ScrollView(c).apply {
        tag = "scroll"
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }
    body.setPadding(c.dp(20), c.dp(8), c.dp(20), c.dp(28))
    sv.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    col.add(sv, h = 0, weight = 1f)
    return col
}

/** Wraps a page with the round "Aggiungi" button in the bottom right corner. */
fun Screen.withFab(page: View, onClick: () -> Unit): View {
    val c = act
    val f = FrameLayout(c)
    f.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
    val fab = c.hbox().apply {
        gravity = Gravity.CENTER
        background = c.ripple(c.rounded(c.col(R.color.accent), 22), 22)
        elevation = c.dpf(6f)
        contentDescription = c.getString(R.string.add)
        setOnClickListener { onClick() }
        setPadding(c.dp(20), 0, c.dp(22), 0)
    }
    fab.add(c.icon(R.drawable.ic_camera, c.col(R.color.on_accent)), w = c.dp(24), h = c.dp(24))
    fab.add(c.text(c.getString(R.string.add), 16f, c.col(R.color.on_accent), MEDIUM), w = WRAP, start = 10)
    val lp = FrameLayout.LayoutParams(WRAP, c.dp(58), Gravity.BOTTOM or Gravity.END)
    lp.setMargins(0, 0, c.dp(20), c.dp(20))
    f.addView(fab, lp)
    return f
}

/** The "add something" sheet used by the button. `folderId`: where new documents go by default. */
fun MainActivity.addSheet(folderId: Long? = null) {
    Dialogs.sections(this, getString(R.string.add_title), listOf(
        getString(R.string.add_from_doc) to listOf(
            Triple(R.drawable.ic_camera, getString(R.string.add_photo)) { Capture.folderHint = folderId; Capture.camera(this) },
            Triple(R.drawable.ic_image, getString(R.string.add_file)) { Capture.folderHint = folderId; Capture.pickFile(this) },
            Triple(R.drawable.ic_payslip, getString(R.string.add_payslip_any)) { addPayslip() }
        ),
        getString(R.string.add_without_doc) to listOf(
            Triple(R.drawable.ic_receipt, getString(R.string.spend_new)) { push(SpendScreen(this)) },
            Triple(R.drawable.ic_pill, getString(R.string.meds_add)) { push(MedicineScreen(this)) },
            Triple(R.drawable.ic_visit, getString(R.string.visits_add)) { push(VisitScreen(this)) },
            Triple(R.drawable.ic_edit, getString(R.string.add_manual)) { push(CardScreen(this, folderHint = folderId)) }
        )
    ))
}

/** The payslip: a photo of the paper one, or the PDF that came by email. */
fun MainActivity.addPayslip() {
    Capture.choose(this, title = getString(R.string.add_payslip), hint = DocType.BUSTA_PAGA)
}

/** "Cerca in tutti i documenti": a tappable pill that opens the search. */
fun MainActivity.searchPill(): View = hbox().apply {
    background = ripple(rounded(col(R.color.surface), 18), 18)
    setPadding(dp(16), dp(13), dp(16), dp(13))
    add(icon(R.drawable.ic_search, col(R.color.ink2), 22), w = dp(22), h = dp(22))
    add(text(getString(R.string.search_hint), 15f, col(R.color.ink2)), w = 0, weight = 1f, start = 12)
    setOnClickListener { push(SearchScreen(this@searchPill)) }
}

/** One deadline in a list: badge, title, date and amount. */
fun MainActivity.deadlineRow(d: Deadline, today: LocalDate): View =
    lineRow(d.id, d.type, Fmt.title(this, d), d.amountCents, d.day ?: d.due, d.paid, projected = false, estimated = d.estimated,
        today = today, noDue = d.noDue)

fun MainActivity.monthLineRow(l: MonthLine, today: LocalDate): View =
    lineRow(l.deadlineId, l.type, Fmt.title(this, l.title, l.type, l.issuer), l.amountCents, l.due, l.paid, l.projected, l.estimated, today)

private fun MainActivity.lineRow(
    id: Long, type: DocType, title: String, amount: Long, due: LocalDate,
    paid: Boolean, projected: Boolean, estimated: Boolean, today: LocalDate, noDue: Boolean = false
): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        background = ripple(null, 0)
        setOnClickListener { push(DeadlineScreen(this@lineRow, id)) }
    }
    row.add(typeBadge(type), w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(title, 16f, face = MEDIUM, lines = 1))
    val left = Reminders.daysLeft(today, due)
    val status = when {
        paid -> getString(if (amount == 0L) R.string.status_done else R.string.status_paid)
        projected -> getString(R.string.status_projected)
        else -> Fmt.relative(this, today, due)
    }
    var sub = if (noDue && !paid) getString(R.string.status_no_due) else Fmt.dateShort(due) + " · " + status
    if (estimated && !paid && amount > 0) sub += " · " + getString(R.string.status_estimated)
    val subColor = when {
        paid -> col(R.color.good)
        noDue -> col(R.color.ink2)
        projected -> col(R.color.ink3)
        left < 0 -> col(R.color.bad)
        left <= 3 -> col(R.color.warn)
        else -> col(R.color.ink2)
    }
    mid.add(text(sub, 13f, subColor, lines = 1), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    if (amount > 0) {
        row.add(text(Money.format(amount), 15f, if (projected) col(R.color.ink3) else col(R.color.ink), MEDIUM), w = WRAP, start = 8)
    } else {
        row.add(icon(R.drawable.ic_notify, col(R.color.ink3), 20), w = dp(20), h = dp(20), start = 8)
    }
    return row
}

/** One archived document: badge, title, date and folder. */
fun MainActivity.docRow(d: Db.DocRow, folders: List<Folder>, showFolder: Boolean): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        background = ripple(null, 0)
        setOnClickListener { push(DocScreen(this@docRow, d.id)) }
    }
    row.add(typeBadge(d.type), w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(Fmt.title(this, d.title, d.type, d.issuer), 16f, face = MEDIUM, lines = 1))
    var sub = Fmt.date(d.shownDate)
    if (showFolder) Sorter.path(folders, d.folderId).lastOrNull()?.let { sub += " · " + it.name }
    mid.add(text(sub, 13f, col(R.color.ink2), lines = 1), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    row.add(icon(R.drawable.ic_chevron_right, col(R.color.ink3), 20), w = dp(20), h = dp(20), start = 8)
    return row
}

/** One folder in a list: icon, name, counts. */
fun MainActivity.folderRow(f: Folder, folders: List<Folder>, counts: String, onClick: () -> Unit): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        background = ripple(null, 0)
        setOnClickListener { onClick() }
    }
    row.add(folderBadge(f, folders, 42), w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(f.name, 16f, face = MEDIUM, lines = 1))
    if (counts.isNotEmpty()) mid.add(text(counts, 13f, col(R.color.ink2), lines = 1), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    row.add(icon(R.drawable.ic_chevron_right, col(R.color.ink3), 20), w = dp(20), h = dp(20), start = 8)
    return row
}

/** Kind of a folder: its own, or the one of its top-level ancestor. */
fun folderKind(f: Folder, folders: List<Folder>): FolderKind? =
    FolderKind.of(Sorter.path(folders, f.id).firstOrNull()?.kind)

fun folderColor(kind: FolderKind?): Int = when (kind) {
    FolderKind.CASA -> 0xFF2563EB.toInt()
    FolderKind.AUTO -> 0xFF7C3AED.toInt()
    FolderKind.SALUTE -> 0xFFE11D48.toInt()
    FolderKind.ANIMALI -> 0xFFEA580C.toInt()
    FolderKind.SCUOLA -> 0xFFB7791F.toInt()
    FolderKind.LAVORO -> 0xFF16A34A.toInt()
    FolderKind.TASSE -> 0xFF475569.toInt()
    FolderKind.ALTRO -> 0xFF78716C.toInt()
    null -> 0xFF0E8FA8.toInt()
}

fun folderIcon(kind: FolderKind?): Int = when (kind) {
    FolderKind.CASA -> R.drawable.ic_home
    FolderKind.AUTO -> R.drawable.ic_car
    FolderKind.SALUTE -> R.drawable.ic_health
    FolderKind.ANIMALI -> R.drawable.ic_pets
    FolderKind.SCUOLA -> R.drawable.ic_school
    FolderKind.LAVORO -> R.drawable.ic_work
    FolderKind.TASSE -> R.drawable.ic_bank
    FolderKind.ALTRO, null -> R.drawable.ic_folder
}

/** Round badge with the folder's icon on a soft tint of its colour. */
fun MainActivity.folderBadge(f: Folder, folders: List<Folder>, sizeDp: Int): View {
    val kind = folderKind(f, folders)
    val c = folderColor(kind)
    val box = FrameLayout(this)
    box.background = rounded((c and 0x00FFFFFF) or 0x24000000, sizeDp / 3)
    val ic = icon(if (f.parentId == null) folderIcon(kind) else R.drawable.ic_folder, c, sizeDp / 2)
    box.addView(ic, FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
    return box
}

/** A QR code or barcode drawn on white, tap to show it full screen. */
fun MainActivity.codeCard(code: Code): View {
    val box = card(16, 0xFFFFFFFF.toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
    val twoD = Codes.isTwoD(code)
    val width = dp(if (twoD) 200 else 300)
    Codes.render(code, width)?.let { bmp ->
        val iv = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }
        box.add(iv, w = LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL)
    }
    box.add(text(code.format.replace('_', ' ') + " · " + getString(R.string.tap_to_enlarge), 12f, 0xFF6B6B6B.toInt()).apply {
        gravity = Gravity.CENTER
    }, top = 8)
    box.setOnClickListener { Dialogs.bigCode(this, code) }
    return box
}

/** A button showing a date; tapping it opens the date picker. */
fun MainActivity.dateButton(value: LocalDate?, emptyText: String, onPick: (LocalDate) -> Unit): TextView {
    val b = button(value?.let { Fmt.date(it) } ?: emptyText, Btn.GHOST, R.drawable.ic_calendar) {}
    b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    b.setOnClickListener {
        val d0 = value ?: LocalDate.now()
        DatePickerDialog(this, dialogTheme(), { _, y, m, d ->
            val picked = LocalDate.of(y, m + 1, d)
            b.text = Fmt.date(picked)
            onPick(picked)
        }, d0.year, d0.monthValue - 1, d0.dayOfMonth).show()
    }
    return b
}

/** A date button with a small ✕ to clear it (when `onClear` is given). */
fun MainActivity.dateRow(value: LocalDate?, emptyText: String, onPick: (LocalDate) -> Unit, onClear: (() -> Unit)?): View {
    val row = hbox()
    row.add(dateButton(value, emptyText, onPick), w = 0, weight = 1f)
    if (onClear != null) row.add(iconButton(R.drawable.ic_delete, R.string.clear, col(R.color.ink2)) { onClear() }, w = WRAP)
    return row
}

/** A button showing a time of day; tapping it opens the time picker. */
fun MainActivity.timeButton(value: LocalTime?, emptyText: String, onPick: (LocalTime) -> Unit): TextView {
    val b = button(value?.let { HFmt.time(it) } ?: emptyText, Btn.GHOST, R.drawable.ic_time) {}
    b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    b.setOnClickListener {
        val t0 = value ?: LocalTime.of(9, 0)
        TimePickerDialog(this, dialogTheme(), { _, h, m ->
            val picked = LocalTime.of(h, m)
            b.text = HFmt.time(picked)
            onPick(picked)
        }, t0.hour, t0.minute, true).show()
    }
    return b
}

/** The folder a thing is filed in, as a tappable row that opens the folder picker. */
fun MainActivity.folderPicker(folderId: Long?, onPick: (Long) -> Unit): View {
    val folders = db.folders()
    val path = Sorter.path(folders, folderId)
    val row = hbox().apply {
        background = ripple(rounded(col(R.color.surface2), 14), 14)
        setPadding(dp(12), dp(10), dp(14), dp(10))
        setOnClickListener { Dialogs.pickFolder(this@folderPicker, folderId, onPick) }
    }
    path.lastOrNull()?.let { row.add(folderBadge(it, folders, 34), w = dp(34), h = dp(34)) }
    row.add(text(if (path.isEmpty()) "—" else path.joinToString(" › ") { it.name }, 16f, face = MEDIUM), w = 0, weight = 1f, start = 12)
    row.add(text(getString(R.string.change), 14f, col(R.color.accent_ink), MEDIUM), w = WRAP)
    return row
}

/** A two-line stat used inside the balance cards. */
fun MainActivity.stat(label: String, value: String, color: Int, valueColor: Int = color): LinearLayout = vbox().apply {
    add(text(label, 13f, color))
    add(text(value, 17f, valueColor, MEDIUM), top = 2)
}

/** "12 documenti · 2 da fare" for a folder and everything below it. */
fun MainActivity.folderCounts(f: Folder, folders: List<Folder>, docs: List<Db.DocRow>, deadlines: List<Deadline>): String {
    val ids = Sorter.subtree(folders, f.id)
    val nDocs = docs.count { it.folderId in ids }
    val nTodo = deadlines.count { !it.paid && it.folderId in ids }
    val parts = ArrayList<String>()
    if (nDocs > 0) parts += resources.getQuantityString(R.plurals.docs_count, nDocs, nDocs)
    if (nTodo > 0) parts += resources.getQuantityString(R.plurals.todo_count, nTodo, nTodo)
    return if (parts.isEmpty()) getString(R.string.folder_empty_short) else parts.joinToString(" · ")
}
