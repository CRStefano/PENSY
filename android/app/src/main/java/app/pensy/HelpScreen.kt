package app.pensy

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import java.text.Normalizer

/** One question of the guide. */
class Faq(val section: String, val q: String, val a: String)

object Guide {

    /**
     * The guide lives in res/raw/faq.txt (Italian) and res/raw-en/faq.txt
     * (English), in a tiny format:
     *
     *   # Section
     *   ? Question
     *   Answer, one or more paragraphs.
     */
    fun load(ctx: Context): List<Faq> {
        val text = ctx.resources.openRawResource(R.raw.faq).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val out = ArrayList<Faq>()
        var section = ""
        var q: String? = null
        val a = StringBuilder()
        fun flush() {
            q?.let { out += Faq(section, it, a.toString().trim().replace(Regex("\n{3,}"), "\n\n")) }
            q = null
            a.setLength(0)
        }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") -> { flush(); section = line.substring(2).trim() }
                line.startsWith("? ") -> { flush(); q = line.substring(2).trim() }
                q != null -> {
                    if (line.isBlank()) a.append("\n\n")
                    else { if (a.isNotEmpty() && !a.endsWith("\n")) a.append(' '); a.append(line.trim()) }
                }
            }
        }
        flush()
        return out
    }

    /** Lower case, no accents: "perché" matches "perche". */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}

/**
 * The "?" page: how PENSY works, and where to go to do things (pay a
 * pagoPA notice, book a visit, renew a document). Searchable, offline.
 */
class HelpScreen(act: MainActivity) : Screen(act) {

    private val faqs = Guide.load(act)
    private var query = ""
    private var results: LinearLayout? = null
    private val open = HashSet<String>()

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.text(c.getString(R.string.help_intro), 14f, c.col(R.color.ink2)), top = 4, start = 4)
        body.add(c.field(c.getString(R.string.help_search), query, InputType.TYPE_CLASS_TEXT) { q -> query = q; show() }, top = 14)
        val b = c.hbox()
        b.add(c.button(c.getString(R.string.manual_title), Btn.SECONDARY, R.drawable.ic_book) { c.push(ManualScreen(c)) }, w = 0, weight = 1f)
        b.add(c.button(c.getString(R.string.tour_again), Btn.GHOST, R.drawable.ic_help) { c.push(TourScreen(c)) }, w = 0, weight = 1f, start = 10)
        body.add(b, top = 12)
        val list = c.vbox()
        results = list
        body.add(list, top = 4)
        show()
        return page(c.getString(R.string.help), back = true, body = body)
    }

    private fun show() {
        val c = act
        val box = results ?: return
        box.removeAllViews()
        val q = Guide.fold(query.trim())
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }
        val shown = if (words.isEmpty()) faqs else faqs.filter { f ->
            val hay = Guide.fold(f.q + " " + f.a + " " + f.section)
            words.all { hay.contains(it) }
        }
        if (shown.isEmpty()) {
            box.add(c.text(c.getString(R.string.help_none, query.trim()), 15f, c.col(R.color.ink2)), top = 18, start = 4)
            return
        }
        for ((section, items) in shown.groupBy { it.section }) {
            box.add(c.sectionText(section), top = 22)
            val card = c.listCard()
            for (f in items) card.add(row(f))
            box.add(card, top = 10)
        }
        box.add(View(c), h = c.dp(40))
    }

    private fun row(f: Faq): View {
        val c = act
        val key = f.section + "|" + f.q
        val v = c.vbox().apply {
            setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
            background = c.ripple(null, 0)
        }
        val head = c.hbox()
        head.add(c.text(f.q, 16f, face = MEDIUM), w = 0, weight = 1f)
        val arrow = c.text("▾", 16f, c.col(R.color.ink2))
        head.add(arrow, w = WRAP, start = 8)
        v.add(head)
        val answer = c.text(f.a, 15f, c.col(R.color.ink2)).apply {
            setLineSpacing(0f, 1.18f)
            setTextIsSelectable(true)
        }
        v.add(answer, top = 8)
        fun paint() {
            val on = key in open
            answer.visibility = if (on) View.VISIBLE else View.GONE
            arrow.text = if (on) "▴" else "▾"
        }
        paint()
        v.setOnClickListener { if (!open.remove(key)) open += key; paint() }
        return v
    }
}
