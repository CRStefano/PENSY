package app.pensy

import app.pensy.core.DocType
import app.pensy.core.Parser
import java.time.LocalDate

/**
 * The receipt of a payment, photographed from the deadline: a document
 * of kind "Ricevuta" in the same folder, linked to the deadline, with
 * its photo always kept (a receipt is worth its picture). A deadline
 * still open is marked paid on the receipt's date.
 */
fun MainActivity.saveReceipt(deadlineId: Long, read: Capture.Read) {
    val d = db.deadline(deadlineId)
    if (d == null) { Pages.discard(read.files); return }
    val card = Parser.parse(read.text, read.codes, LocalDate.now())
    val day = card.docDate ?: LocalDate.now()
    val title = getString(R.string.receipt_of, Fmt.title(this, d))
    val docId = db.saveDoc(DocType.RICEVUTA, d.issuer, title, read.text, card.fields(), d.folderId, day)
    db.saveCodes(docId, read.codes)
    Pages.adopt(this, docId, read.files)
    db.saveDeadline(d.copy(receiptDocId = docId))
    val next = if (!d.paid) db.markPaid(d.id, day) else null
    changed()
    toast(when {
        next != null -> getString(R.string.receipt_saved_next, Fmt.date(next.due))
        !d.paid -> getString(R.string.receipt_saved_paid)
        else -> getString(R.string.receipt_saved)
    })
    refresh()
}
