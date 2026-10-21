package app.pensy

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate

/*
 * The user manual: every part of PENSY, what it does, how to use it,
 * what it is for and why it works the way it does. It lives in
 * res/raw/manuale.txt (Italian) and res/raw-en/manuale.txt (English):
 *
 *   # Chapter title
 *   @icon
 *   First paragraph: the chapter in one or two sentences.
 *   ## Heading
 *   A paragraph (lines are joined until a blank line).
 *   - a point
 *   1. a step
 *   > Tip: a practical trick
 *   ! Why: the reason behind a choice
 *
 * Offline, searchable, and it can be saved as a PDF.
 */

class ManualBlock(val kind: Char, val text: String)  // 'h' heading, 'p' paragraph, '-' point, '1' step, '>' tip, '!' why
class ManualChapter(val n: Int, val title: String, val icon: String, val summary: String, val blocks: List<ManualBlock>)

object Manual {

    fun load(ctx: Context): List<ManualChapter> {
        val text = ctx.resources.openRawResource(R.raw.manuale).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val out = ArrayList<ManualChapter>()
        var title: String? = null
        var icon = ""
        var summary = ""
        var blocks = ArrayList<ManualBlock>()
        val para = StringBuilder()
        var paraKind = 'p'
        fun endPara() {
            if (para.isEmpty()) return
            val t = para.toString().trim()
            if (paraKind == 'p' && summary.isEmpty() && blocks.isEmpty()) summary = t else blocks += ManualBlock(paraKind, t)
            para.setLength(0)
            paraKind = 'p'
        }
        fun endChapter() {
            endPara()
            title?.let { out += ManualChapter(out.size + 1, it, icon, summary, blocks) }
            title = null; icon = ""; summary = ""; blocks = ArrayList()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("# ") -> { endChapter(); title = line.substring(2).trim() }
                title == null -> {}
                line.startsWith("@") && para.isEmpty() && blocks.isEmpty() && summary.isEmpty() -> icon = line.substring(1).trim()
                line.isEmpty() -> endPara()
                line.startsWith("## ") -> { endPara(); blocks += ManualBlock('h', line.substring(3).trim()) }
                line.startsWith("- ") -> { endPara(); blocks += ManualBlock('-', line.substring(2).trim()) }
                line.length > 2 && line[0].isDigit() && line.substringBefore(' ').endsWith(".") -> {
                    endPara(); blocks += ManualBlock('1', line.substringAfter(' ').trim())
                }
                line.startsWith("> ") -> { endPara(); para.append(line.substring(2).trim()); paraKind = '>' }
                line.startsWith("! ") -> { endPara(); para.append(line.substring(2).trim()); paraKind = '!' }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
            }
        }
        endChapter()
        return out
    }

    fun iconOf(name: String): Int = when (name) {
        "camera" -> R.drawable.ic_camera
        "home" -> R.drawable.ic_home
        "folder" -> R.drawable.ic_folder
        "notify" -> R.drawable.ic_notify
        "calendar" -> R.drawable.ic_calendar
        "chart" -> R.drawable.ic_chart
        "trending" -> R.drawable.ic_trending
        "jar" -> R.drawable.ic_jar
        "bank" -> R.drawable.ic_bank
        "health" -> R.drawable.ic_health
        "person" -> R.drawable.ic_person
        "lock" -> R.drawable.ic_lock
        "star" -> R.drawable.ic_star
        "check" -> R.drawable.ic_check
        else -> R.drawable.ic_book
    }

    /** "Consiglio: ..." → ("Consiglio", "..."); the label is what comes before the first colon. */
    fun split(t: String): Pair<String, String> {
        val i = t.indexOf(':')
        return if (i in 1..40) t.substring(0, i).trim() to t.substring(i + 1).trim() else "" to t
    }

    /** The whole manual as a PDF, on a worker thread; then the share sheet. */
    fun exportPdf(c: MainActivity) {
        val chapters = load(c)
        c.showBusy(R.string.making_pdf)
        Thread {
            val out = try {
                val w = PdfWriter(c, "PENSY · " + c.getString(R.string.manual_title))
                w.title(c.getString(R.string.manual_title))
                w.subtitle(c.getString(R.string.manual_pdf_sub, Fmt.date(LocalDate.now())))
                w.heading(c.getString(R.string.manual_contents))
                for (ch in chapters) w.bullet("${ch.n}. ${ch.title}")
                for (ch in chapters) {
                    w.gap(10f)
                    w.text("${ch.n}. ${ch.title}", 16f, bold = true, before = 8f)
                    w.text(ch.summary, 11.5f, before = 4f)
                    var step = 0
                    for (b in ch.blocks) {
                        if (b.kind != '1') step = 0
                        when (b.kind) {
                            'h' -> w.heading(b.text)
                            '-' -> w.bullet(b.text)
                            '1' -> { step++; w.text("$step.  ${b.text}", 11f, before = 3f) }
                            '>', '!' -> {
                                val (label, rest) = split(b.text)
                                w.text(if (label.isEmpty()) rest else "$label: $rest", 10.5f, color = 0xFF3F37C9.toInt(), before = 6f)
                            }
                            else -> w.text(b.text, 11f, before = 5f)
                        }
                    }
                }
                val file = Share.newFile(c, c.getString(R.string.manual_file) + ".pdf")
                w.finish(file)
                file
            } catch (t: Throwable) { null }
            c.runOnUiThread {
                c.hideBusy()
                if (out == null) c.toast(R.string.pdf_error) else Share.send(c, out, "application/pdf", c.getString(R.string.manual_title))
            }
        }.start()
    }
}

/** The manual's index: every chapter with its one-line summary, and a search. */
class ManualScreen(act: MainActivity) : Screen(act) {

    private val chapters = Manual.load(act)
    private var query = ""
    private var results: LinearLayout? = null

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.text(c.getString(R.string.manual_intro), 14f, c.col(R.color.ink2)), top = 4, start = 4)
        body.add(c.field(c.getString(R.string.manual_search), query, InputType.TYPE_CLASS_TEXT) { q -> query = q; show() }, top = 14)
        val list = c.vbox()
        results = list
        body.add(list, top = 4)
        show()
        val actions = listOf(Triple(R.drawable.ic_doc, R.string.manual_pdf) { Manual.exportPdf(c) })
        return page(c.getString(R.string.manual_title), back = true, actions = actions, body = body)
    }

    private fun show() {
        val c = act
        val box = results ?: return
        box.removeAllViews()
        val words = Guide.fold(query.trim()).split(Regex("\\s+")).filter { it.length >= 2 }
        val shown = if (words.isEmpty()) chapters else chapters.filter { ch ->
            val hay = Guide.fold(ch.title + " " + ch.summary + " " + ch.blocks.joinToString(" ") { it.text })
            words.all { hay.contains(it) }
        }
        if (shown.isEmpty()) {
            box.add(c.text(c.getString(R.string.help_none, query.trim()), 15f, c.col(R.color.ink2)), top = 18, start = 4)
            return
        }
        val list = c.listCard()
        for (ch in shown) {
            val row = c.hbox().apply {
                gravity = Gravity.TOP
                setPadding(c.dp(14), c.dp(14), c.dp(16), c.dp(14))
                background = c.ripple(null, 0)
                setOnClickListener { c.push(ManualChapterScreen(c, ch.n, query)) }
            }
            row.add(c.roundIcon(Manual.iconOf(ch.icon), c.col(R.color.accent), 40), w = c.dp(40), h = c.dp(40))
            val mid = c.vbox()
            mid.add(c.text("${ch.n}. ${ch.title}", 16f, face = MEDIUM))
            mid.add(c.text(ch.summary, 13f, c.col(R.color.ink2), lines = 2), top = 2)
            row.add(mid, w = 0, weight = 1f, start = 14)
            list.add(row)
        }
        box.add(list, top = 14)
        box.add(View(c), h = c.dp(40))
    }
}

/** One chapter: its summary, headings, paragraphs, steps, tips and the reasons behind. */
class ManualChapterScreen(act: MainActivity, private val n: Int, private val query: String = "") : Screen(act) {

    override fun build(): View {
        val c = act
        val chapters = Manual.load(c)
        val ch = chapters.firstOrNull { it.n == n } ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val body = c.vbox()

        val head = c.hbox()
        head.add(c.roundIcon(Manual.iconOf(ch.icon), c.col(R.color.accent), 52), w = c.dp(52), h = c.dp(52))
        head.add(c.text(c.getString(R.string.manual_chapter, ch.n, chapters.size), 13f, c.col(R.color.ink2), MEDIUM), w = 0, weight = 1f, start = 14)
        body.add(head, top = 4)
        body.add(c.text(ch.title, 26f, face = BOLD), top = 14)
        body.add(c.text(ch.summary, 17f, c.col(R.color.ink)).apply { setLineSpacing(0f, 1.2f) }, top = 8)

        var step = 0
        for (b in ch.blocks) {
            if (b.kind != '1') step = 0
            when (b.kind) {
                'h' -> body.add(c.text(b.text, 19f, face = BOLD), top = 24)
                '-' -> {
                    val r = c.hbox().apply { gravity = Gravity.TOP }
                    r.add(c.text("•", 16f, c.col(R.color.accent_ink), BOLD), w = c.dp(18))
                    r.add(c.text(b.text, 15.5f).apply { setLineSpacing(0f, 1.18f) }, w = 0, weight = 1f)
                    body.add(r, top = 8)
                }
                '1' -> {
                    step++
                    val r = c.hbox().apply { gravity = Gravity.TOP }
                    r.add(c.text(step.toString(), 13f, c.col(R.color.on_accent), BOLD).apply {
                        gravity = Gravity.CENTER
                        background = c.rounded(c.col(R.color.accent), 12)
                    }, w = c.dp(24), h = c.dp(24))
                    r.add(c.text(b.text, 15.5f).apply { setLineSpacing(0f, 1.18f) }, w = 0, weight = 1f, start = 12, top = 1)
                    body.add(r, top = 10)
                }
                '>', '!' -> {
                    val (label, rest) = Manual.split(b.text)
                    val tip = b.kind == '>'
                    val box = c.card(14, c.col(if (tip) R.color.accent_soft else R.color.surface))
                    val top = c.hbox()
                    top.add(c.icon(if (tip) R.drawable.ic_star else R.drawable.ic_help, c.col(R.color.accent_ink), 18), w = c.dp(18), h = c.dp(18))
                    top.add(c.text(label.ifEmpty { c.getString(if (tip) R.string.manual_tip else R.string.manual_why) }, 13.5f, c.col(R.color.accent_ink), MEDIUM),
                        w = 0, weight = 1f, start = 8)
                    box.add(top)
                    box.add(c.text(rest, 15f).apply { setLineSpacing(0f, 1.18f) }, top = 6)
                    body.add(box, top = 16)
                }
                else -> body.add(c.text(b.text, 15.5f).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }, top = 10)
            }
        }

        // Previous / next
        val nav = c.hbox()
        if (n > 1) nav.add(c.button(c.getString(R.string.manual_prev), Btn.GHOST, R.drawable.ic_chevron_left) {
            c.pop(); c.push(ManualChapterScreen(c, n - 1))
        }, w = 0, weight = 1f)
        else nav.add(View(c), w = 0, h = 1, weight = 1f)
        if (n < chapters.size) nav.add(c.button(c.getString(R.string.manual_next), Btn.SECONDARY, R.drawable.ic_chevron_right) {
            c.pop(); c.push(ManualChapterScreen(c, n + 1))
        }, w = 0, weight = 1f, start = 10)
        body.add(nav, top = 28)
        body.add(View(c), h = c.dp(24))
        return page(c.getString(R.string.manual_title), back = true, body = body)
    }
}

/** "Se ti serve un manuale d'uso, toccalo qui": a small link used by the first launch and the tour. */
fun MainActivity.manualLink(): View = hbox().apply {
    background = ripple(rounded(col(R.color.surface), 16), 16)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    add(icon(R.drawable.ic_book, col(R.color.accent_ink), 22), w = dp(22), h = dp(22))
    add(text(getString(R.string.manual_link), 14.5f, col(R.color.ink), MEDIUM), w = 0, weight = 1f, start = 12)
    add(icon(R.drawable.ic_chevron_right, col(R.color.ink3), 20), w = dp(20), h = dp(20))
    setOnClickListener { push(ManualScreen(this@manualLink)) }
}
