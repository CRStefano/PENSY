package app.pensy

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import app.pensy.core.Money
import app.pensy.core.Reminders
import app.pensy.core.parseFields
import java.time.LocalDate

/** One deadline: amount, date, the code ready to scan, the document text. */
class DeadlineScreen(act: MainActivity, private val id: Long) : Screen(act) {

    override fun build(): View {
        val c = act
        val d = c.db.deadline(id) ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val today = LocalDate.now()
        val doc = d.docId?.let { c.db.doc(it) }
        val codes = c.db.codes(d.docId)
        val fields = doc?.let { parseFields(it.fields) } ?: emptyMap()
        val body = c.vbox()

        // Header
        val head = c.hbox()
        head.add(c.typeBadge(d.type), w = c.dp(48), h = c.dp(48))
        val names = c.vbox()
        names.add(c.text(Fmt.title(c, d), 20f, face = BOLD))
        val sub = listOf(Fmt.type(c, d.type), d.issuer).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        names.add(c.text(sub, 14f, c.col(R.color.ink2)), top = 2)
        head.add(names, w = 0, weight = 1f, start = 14)
        body.add(head, top = 4)

        // Amount and status
        val box = c.card(20)
        if (d.amountCents > 0) box.add(c.text(Money.format(d.amountCents), 36f, face = BOLD))
        else box.add(c.text(c.getString(R.string.reminder), 28f, face = BOLD))
        if (d.estimated && !d.paid && d.amountCents > 0) box.add(c.text(c.getString(R.string.amount_estimated), 13f, c.col(R.color.warn)), top = 2)
        val left = Reminders.daysLeft(today, d.due)
        val (statusText, statusColor) = when {
            d.paid -> c.getString(if (d.amountCents > 0) R.string.paid_on else R.string.done_on, Fmt.date(d.paidOn ?: d.due)) to c.col(R.color.good)
            d.noDue -> c.getString(R.string.no_due_detail) to c.col(R.color.ink2)
            left < 0 -> c.getString(R.string.overdue_since, Fmt.date(d.due)) to c.col(R.color.bad)
            else -> c.getString(R.string.due_on, Fmt.date(d.due), Fmt.relative(c, today, d.due)) to
                (if (left <= 3) c.col(R.color.warn) else c.col(R.color.ink2))
        }
        box.add(c.text(statusText, 15f, statusColor, MEDIUM), top = 6)
        if (!d.noDue) {
            val facts = c.hbox()
            facts.add(c.stat(c.getString(R.string.field_every), Fmt.every(c, d.everyMonths), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
            facts.add(c.stat(c.getString(R.string.field_remind),
                c.resources.getQuantityString(R.plurals.days_before, d.remindDays, d.remindDays), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
            box.add(facts, top = 16)
        } else if (d.paid) {
            box.add(c.text(c.getString(R.string.no_due_detail), 13f, c.col(R.color.ink2)), top = 4)
        }
        body.add(box, top = 18)

        // Where it is filed, and its document
        val folders = c.db.folders()
        val path = app.pensy.core.Sorter.path(folders, d.folderId)
        val where = c.card(16)
        val folderRow = c.hbox().apply {
            background = c.ripple(null, 12)
            setOnClickListener {
                Dialogs.pickFolder(c, d.folderId) { f ->
                    c.db.moveDeadline(d, f); c.db.learn(d.issuer, f); c.changed()
                    c.db.folder(f)?.let { c.toast(c.getString(R.string.moved, it.name)) }
                    refresh()
                }
            }
        }
        path.lastOrNull()?.let { folderRow.add(c.folderBadge(it, folders, 36), w = c.dp(36), h = c.dp(36)) }
        val fcol = c.vbox()
        fcol.add(c.text(c.getString(R.string.field_folder), 13f, c.col(R.color.ink2)))
        fcol.add(c.text(if (path.isEmpty()) "—" else path.joinToString(" › ") { it.name }, 16f, face = MEDIUM), top = 2)
        folderRow.add(fcol, w = 0, weight = 1f, start = 12)
        folderRow.add(c.text(c.getString(R.string.move), 14f, c.col(R.color.accent_ink), MEDIUM), w = WRAP)
        where.add(folderRow)
        if (doc != null) {
            where.add(c.button(c.getString(R.string.open_doc), Btn.GHOST, R.drawable.ic_doc) { c.push(DocScreen(c, doc.id)) }, top = 12)
        }
        body.add(where, top = 12)

        // Pay / unpay
        if (!d.paid) {
            body.add(c.button(c.getString(if (d.amountCents > 0) R.string.mark_paid else R.string.mark_done), Btn.PRIMARY, R.drawable.ic_check) {
                val next = c.db.markPaid(d.id, LocalDate.now())
                c.changed()
                if (next != null) c.toast(c.getString(R.string.next_created, Fmt.date(next.due)))
                else c.toast(R.string.marked_paid)
                c.pop()
            }, top = 14)
        } else {
            body.add(c.button(c.getString(R.string.mark_unpaid), Btn.SECONDARY) {
                c.db.markUnpaid(d.id); c.changed(); refresh()
            }, top = 14)
        }

        // The receipt of the payment
        if (d.amountCents > 0) {
            val receipt = d.receiptDocId?.let { c.db.doc(it) }
            if (receipt != null) {
                body.add(c.button(c.getString(R.string.receipt_open), Btn.GHOST, R.drawable.ic_receipt) {
                    c.push(DocScreen(c, receipt.id))
                }, top = 10)
            } else {
                body.add(c.button(c.getString(R.string.receipt_add), Btn.GHOST, R.drawable.ic_receipt) {
                    Capture.choose(c, Capture.Target.Receipt(d.id), c.getString(R.string.receipt_add))
                }, top = 10)
            }
        }

        // Codes, ready to be scanned
        if (codes.isNotEmpty()) {
            body.add(c.section(R.string.codes_title), top = 26)
            for (code in codes) body.add(c.codeCard(code), top = 10)
        }

        // pagoPA data
        val notice = fields["codice_avviso"]
        val cf = fields["cf_ente"]
        if (notice != null || cf != null) {
            body.add(c.section(R.string.pagopa_data), top = 26)
            val pc = c.card(16)
            if (notice != null) pc.add(copyRow(c.getString(R.string.notice_code), notice.chunked(4).joinToString(" "), notice))
            if (cf != null) pc.add(copyRow(c.getString(R.string.creditor_cf), cf, cf), top = if (notice != null) 12 else 0)
            body.add(pc, top = 10)
        }

        if (d.note.isNotBlank()) {
            body.add(c.section(R.string.field_note), top = 26)
            body.add(c.card(16).apply { add(c.text(d.note, 15f).apply { setTextIsSelectable(true) }) }, top = 10)
        }

        if (doc != null && doc.text.isNotBlank()) {
            body.add(c.section(R.string.doc_text), top = 26)
            body.add(c.text(doc.text, 13f, c.col(R.color.ink2)).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = c.rounded(c.col(R.color.surface), 18)
                setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
            }, top = 10)
        }

        val actions = listOf(
            Triple(R.drawable.ic_edit, R.string.edit) { c.push(CardScreen(c, editId = d.id)) },
            Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.delete_title, c.getString(R.string.delete_msg, Fmt.title(c, d)), R.string.delete) {
                    c.db.deleteDeadline(d.id); c.changed(); c.pop()
                }
            }
        )
        return page("", back = true, actions = actions, body = body)
    }

    private fun copyRow(label: String, shown: String, raw: String): View {
        val c = act
        val row = c.hbox()
        val col = c.vbox()
        col.add(c.text(label, 13f, c.col(R.color.ink2)))
        col.add(c.text(shown, 16f, face = Typeface.MONOSPACE).apply { setTextIsSelectable(true) }, top = 2)
        row.add(col, w = 0, weight = 1f)
        row.add(c.button(c.getString(R.string.copy), Btn.SECONDARY) {
            c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, raw))
            c.toast(R.string.copied)
        }, w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8)
        return row
    }
}
