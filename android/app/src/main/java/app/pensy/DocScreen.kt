package app.pensy

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import app.pensy.core.Sorter
import app.pensy.core.parseFields
import java.time.LocalDate

/** One document of the archive: where it is filed, its codes, its text, its deadlines. */
class DocScreen(act: MainActivity, private val id: Long) : Screen(act) {

    override fun build(): View {
        val c = act
        val d = c.db.doc(id) ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val folders = c.db.folders()
        val today = LocalDate.now()
        val codes = c.db.codes(id)
        val deadlines = c.db.deadlinesOfDoc(id)
        val fields = parseFields(d.fields)
        val body = c.vbox()

        val head = c.hbox()
        head.add(c.typeBadge(d.type), w = c.dp(48), h = c.dp(48))
        val names = c.vbox()
        names.add(c.text(Fmt.title(c, d.title, d.type, d.issuer), 20f, face = BOLD))
        val sub = listOf(Fmt.type(c, d.type), d.issuer).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        names.add(c.text(sub, 14f, c.col(R.color.ink2)), top = 2)
        head.add(names, w = 0, weight = 1f, start = 14)
        body.add(head, top = 4)

        // Photos of the pages (kept by choice)
        body.add(pagesBlock(d), top = 16)

        // Folder and date
        val info = c.card(16)
        val path = Sorter.path(folders, d.folderId)
        val folderRow = c.hbox().apply {
            background = c.ripple(null, 12)
            setOnClickListener { move() }
        }
        path.lastOrNull()?.let { folderRow.add(c.folderBadge(it, folders, 36), w = c.dp(36), h = c.dp(36)) }
        val fcol = c.vbox()
        fcol.add(c.text(c.getString(R.string.field_folder), 13f, c.col(R.color.ink2)))
        fcol.add(c.text(if (path.isEmpty()) "—" else path.joinToString(" › ") { it.name }, 16f, face = MEDIUM), top = 2)
        folderRow.add(fcol, w = 0, weight = 1f, start = 12)
        folderRow.add(c.text(c.getString(R.string.move), 14f, c.col(R.color.accent_ink), MEDIUM), w = WRAP)
        info.add(folderRow)
        info.add(c.stat(c.getString(R.string.field_doc_date), Fmt.date(d.shownDate), c.col(R.color.ink2), c.col(R.color.ink)), top = 14)
        body.add(info, top = 18)

        // Deadlines and reminders linked to it (for a report: its visits)
        val visits = c.db.visitsOfDoc(id)
        val report = d.type == app.pensy.core.DocType.REFERTO
        body.add(c.section(if (report) R.string.visits else R.string.doc_deadlines), top = 24)
        if (deadlines.isNotEmpty() || visits.isNotEmpty()) {
            val list = c.listCard()
            for (x in deadlines) list.add(c.deadlineRow(x, today))
            for (v in visits) list.add(c.visitRow(v, today))
            body.add(list, top = 10)
        }
        if (report) {
            body.add(c.button(c.getString(R.string.add_followup), Btn.SECONDARY, R.drawable.ic_visit) {
                c.push(VisitScreen(c, folderId = d.folderId, docId = id,
                    title = c.getString(R.string.followup_title, Fmt.title(c, d.title, d.type, d.issuer))))
            }, top = 10)
        } else {
            body.add(c.button(c.getString(R.string.add_reminder), Btn.SECONDARY, R.drawable.ic_notify) {
                c.push(CardScreen(c, docId = id, addDeadline = true))
            }, top = 10)
        }

        if (codes.isNotEmpty()) {
            body.add(c.section(R.string.codes_title), top = 24)
            for (code in codes) body.add(c.codeCard(code), top = 10)
        }

        // Electronic prescription: the number the pharmacist needs
        fields["nre"]?.let { nre ->
            body.add(c.section(R.string.nre_title), top = 24)
            val rc = c.card(16)
            rc.add(copyRow(c.getString(R.string.nre_label), Fmt.nre(nre), nre))
            rc.add(c.button(c.getString(R.string.nre_show), Btn.SECONDARY, R.drawable.ic_pill) {
                Dialogs.bigText(c, c.getString(R.string.nre_label), Fmt.nre(nre))
            }, top = 12)
            rc.add(c.text(c.getString(R.string.nre_hint), 13f, c.col(R.color.ink2)), top = 8)
            body.add(rc, top = 10)
        }

        val notice = fields["codice_avviso"]
        val cf = fields["cf_ente"]
        if (notice != null || cf != null) {
            body.add(c.section(R.string.pagopa_data), top = 24)
            val pc = c.card(16)
            if (notice != null) pc.add(copyRow(c.getString(R.string.notice_code), notice.chunked(4).joinToString(" "), notice))
            if (cf != null) pc.add(copyRow(c.getString(R.string.creditor_cf), cf, cf), top = if (notice != null) 12 else 0)
            body.add(pc, top = 10)
        }

        if (d.note.isNotBlank()) {
            body.add(c.section(R.string.field_note), top = 24)
            body.add(c.card(16).apply { add(c.text(d.note, 15f).apply { setTextIsSelectable(true) }) }, top = 10)
        }

        if (d.text.isNotBlank()) {
            body.add(c.section(R.string.doc_text), top = 24)
            body.add(c.text(d.text, 13f, c.col(R.color.ink2)).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = c.rounded(c.col(R.color.surface), 18)
                setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
            }, top = 10)
        }

        val actions = listOf(
            Triple(R.drawable.ic_move, R.string.move) { move() },
            Triple(R.drawable.ic_edit, R.string.edit) { c.push(CardScreen(c, docId = id)) },
            Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.delete_doc_title, c.getString(R.string.delete_doc_msg, Fmt.title(c, d.title, d.type, d.issuer)), R.string.delete) {
                    c.db.deleteDoc(id); c.changed(); c.pop()
                }
            }
        )
        return page("", back = true, actions = actions, body = body)
    }

    private fun pagesBlock(d: Db.DocRow): View {
        val c = act
        val pages = c.db.pages(id)
        val title = Fmt.title(c, d.title, d.type, d.issuer)
        val box = c.vbox()
        if (pages.isNotEmpty()) {
            val strip = c.hbox()
            pages.forEachIndexed { i, p ->
                val iv = ImageView(c).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = c.rounded(c.col(R.color.surface2), 12)
                    clipToOutline = true
                    Pages.thumb(c, p.name, c.dp(160))?.let { setImageBitmap(it) }
                    contentDescription = c.getString(R.string.page_n, i + 1)
                    setOnClickListener { openPages(title, i) }
                }
                strip.add(iv, w = c.dp(88), h = c.dp(120), end = 10)
            }
            box.add(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
            box.add(c.button(c.getString(R.string.page_add), Btn.GHOST, R.drawable.ic_add) {
                Capture.addPage(c, Capture.Target.Doc(id))
            }, w = WRAP, top = 10)
        } else {
            val row = c.hbox()
            row.add(c.button(c.getString(R.string.photo_keep), Btn.GHOST, R.drawable.ic_camera) {
                Capture.addPage(c, Capture.Target.Doc(id, photoOnly = true))
            }, w = 0, weight = 1f)
            row.add(c.button(c.getString(R.string.page_add), Btn.GHOST, R.drawable.ic_add) {
                Capture.addPage(c, Capture.Target.Doc(id))
            }, w = 0, weight = 1f, start = 10)
            box.add(row)
        }
        return box
    }

    private fun openPages(title: String, start: Int) {
        val c = act
        val pages = c.db.pages(id)
        Viewer.show(c, pages.map { Pages.file(c, it.name) }, start, title,
            onShare = { i -> Share.copyAndSend(c, Pages.file(c, pages[i].name), "$title ${i + 1}.jpg", "image/jpeg", title) },
            onDelete = { i ->
                Dialogs.confirm(c, R.string.page_delete_title, c.getString(R.string.page_delete_msg), R.string.delete) {
                    c.db.deletePage(pages[i]); c.changed(); refresh()
                }
            })
    }

    private fun move() {
        val c = act
        val d = c.db.doc(id) ?: return
        Dialogs.pickFolder(c, d.folderId) { folder ->
            c.db.moveDoc(id, folder)
            c.db.learn(d.issuer, folder)
            c.changed()
            c.db.folder(folder)?.let { c.toast(c.getString(R.string.moved, it.name)) }
            refresh()
        }
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
