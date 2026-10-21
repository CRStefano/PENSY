package app.pensy

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import java.time.LocalDate

/**
 * Search everything: the text of every document, titles, issuers,
 * notes and folder summaries. "gamba", "cardiolog", "AB123CD"...
 */
class SearchScreen(act: MainActivity) : Screen(act) {

    private var query = ""
    private val handler = Handler(Looper.getMainLooper())
    private var results: LinearLayout? = null

    override fun build(): View {
        val c = act
        val col = c.vbox()
        val bar = c.hbox().apply { setPadding(c.dp(6), c.dp(6), c.dp(14), c.dp(6)) }
        bar.add(c.iconButton(R.drawable.ic_back, R.string.back) { hideKeyboard(); c.pop() }, w = WRAP)
        val field: EditText = c.field(c.getString(R.string.search_hint), query, InputType.TYPE_CLASS_TEXT) { q ->
            query = q
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ show() }, 180)
        }
        bar.add(field, w = 0, weight = 1f, start = 4)
        col.add(bar)

        val body = c.vbox()
        results = body
        val page = page(null, body = body)
        col.add(page, h = 0, weight = 1f)
        show()
        if (query.isEmpty()) {
            // First time here: ready to type.
            field.requestFocus()
            field.post {
                c.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return col
    }

    private fun hideKeyboard() {
        act.currentFocus?.let { v -> act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0) }
    }

    private fun show() {
        val c = act
        val body = results ?: return
        body.removeAllViews()
        val q = query.trim()
        if (q.length < 2) {
            body.add(c.text(c.getString(R.string.search_start), 14f, c.col(R.color.ink2)), top = 8, start = 4)
            return
        }
        val found = c.db.search(q)
        val folders = c.db.folders()
        val today = LocalDate.now()
        if (found.folders.isEmpty() && found.docs.isEmpty() && found.deadlines.isEmpty() && found.medicines.isEmpty() && found.visits.isEmpty()) {
            body.add(c.text(c.getString(R.string.search_none, q), 15f, c.col(R.color.ink2)), top = 8, start = 4)
            return
        }
        if (found.folders.isNotEmpty()) {
            body.add(c.section(R.string.search_folders), top = 8)
            val list = c.listCard()
            for (f in found.folders) {
                val sub = app.pensy.core.Sorter.path(folders, f.id).dropLast(1).joinToString(" › ") { it.name }
                list.add(c.folderRow(f, folders, sub) { hideKeyboard(); c.push(FolderScreen(c, f.id)) })
            }
            body.add(list, top = 10)
        }
        if (found.docs.isNotEmpty()) {
            body.add(c.section(R.string.search_docs), top = 20)
            val list = c.listCard()
            for (d in found.docs.take(50)) {
                val row = c.vbox()
                row.add(c.docRow(d, folders, showFolder = true))
                snippet(d.text + "\n" + d.note, q)?.let { s ->
                    row.add(c.text(s, 13f, c.col(R.color.ink2), lines = 2).apply {
                        setPadding(c.dp(70), 0, c.dp(16), c.dp(12))
                    })
                }
                list.add(row)
            }
            body.add(list, top = 10)
        }
        if (found.deadlines.isNotEmpty()) {
            body.add(c.section(R.string.search_deadlines), top = 20)
            val list = c.listCard()
            for (d in found.deadlines.take(50)) list.add(c.deadlineRow(d, today))
            body.add(list, top = 10)
        }
        if (found.medicines.isNotEmpty() || found.visits.isNotEmpty()) {
            body.add(c.section(R.string.home_health), top = 20)
            val list = c.listCard()
            val now = java.time.LocalDateTime.now()
            for (m in found.medicines) list.add(c.medRow(m, now))
            for (v in found.visits) list.add(c.visitRow(v, today))
            body.add(list, top = 10)
        }
    }

    /** "…visita ortopedica: dolore al ginocchio destro…" around the first match. */
    private fun snippet(text: String, q: String): String? {
        val i = text.indexOf(q, ignoreCase = true)
        if (i < 0) return null
        val a = maxOf(0, i - 40)
        val b = minOf(text.length, i + q.length + 60)
        return (if (a > 0) "…" else "") + text.substring(a, b).replace('\n', ' ').trim() + (if (b < text.length) "…" else "")
    }
}
