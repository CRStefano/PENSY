package app.pensy

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import app.pensy.core.HealthKind
import app.pensy.core.MeasureKind
import app.pensy.core.Measures
import app.pensy.core.Meds
import app.pensy.core.Sorter
import app.pensy.core.Visits
import java.time.LocalDate

/**
 * "Mostra al medico": the health record as one clean page, big text, no
 * buttons in the way. The same text can be shared (to print it, or to
 * send it by the user's own choice of app): PENSY itself sends nothing.
 */
class DoctorScreen(act: MainActivity, private val folderId: Long) : Screen(act) {

    override fun build(): View {
        val c = act
        val body = c.vbox()
        val sections = sections()
        val folder = c.db.folder(folderId)
        val who = if (folder?.kind == app.pensy.core.FolderKind.SALUTE.code) c.profileLabel(c.db.current()) else folder?.name ?: ""

        if (who.isNotBlank()) body.add(c.text(who, 26f, face = BOLD), top = 6)
        body.add(c.text(c.getString(R.string.doctor_updated, Fmt.date(LocalDate.now())), 14f, c.col(R.color.ink2)), top = 2)
        for ((title, lines) in sections) {
            body.add(c.sectionText(title), top = 22)
            val card = c.card(16)
            if (lines.isEmpty()) card.add(c.text(c.getString(R.string.doctor_none), 17f, c.col(R.color.ink3)))
            lines.forEachIndexed { i, l -> card.add(c.text(l, 17f).apply { setTextIsSelectable(true) }, top = if (i == 0) 0 else 10) }
            body.add(card, top = 8)
        }
        body.add(c.button(c.getString(R.string.doctor_pdf), Btn.PRIMARY, R.drawable.ic_doc) { pdf(who, sections) }, top = 24)
        body.add(c.button(c.getString(R.string.doctor_share), Btn.SECONDARY, R.drawable.ic_share) { share(who, sections) }, top = 10)
        return page(c.getString(R.string.doctor_title), back = true, body = body)
    }

    /** The record as (section title, lines). */
    fun sections(): List<Pair<String, List<String>>> {
        val c = act
        val today = LocalDate.now()
        val folders = c.db.folders()
        val subtree = Sorter.subtree(folders, folderId)
        val saluteRoot = c.db.folder(folderId)?.let { it.parentId == null && it.kind == app.pensy.core.FolderKind.SALUTE.code } ?: false
        val items = inFolder(c.db.healthItems(), { it.folderId }, subtree, saluteRoot)
        val meds = inFolder(c.db.medicines(), { it.folderId }, subtree, saluteRoot).filter { it.active }
        val visits = inFolder(c.db.visits(), { it.folderId }, subtree, saluteRoot)
        val out = ArrayList<Pair<String, List<String>>>()

        fun itemLine(name: String, date: LocalDate?, note: String, since: Boolean, fromBirth: Boolean = false): String {
            var s = name
            if (fromBirth) s += " (" + c.getString(R.string.from_birth_short) + ")"
            else if (date != null) s += " (" + (if (since) c.getString(R.string.since, Fmt.date(date)) else Fmt.date(date)) + ")"
            if (note.isNotBlank()) s += " — " + note
            return s
        }
        out += HFmt.kindsLabel(c, HealthKind.CONDITION) to items.filter { it.kind == HealthKind.CONDITION && it.active }
            .map { itemLine(it.name, it.date, it.note, true, it.fromBirth) }
        out += HFmt.kindsLabel(c, HealthKind.ALLERGY) to items.filter { it.kind == HealthKind.ALLERGY }
            .map { itemLine(it.name, null, it.note, false, it.fromBirth) }
        out += c.getString(R.string.doctor_meds) to meds.map { m ->
            var s = m.name + " — " + HFmt.dose(c, m) + ", " +
                (if (m.asNeeded) c.getString(R.string.doctor_as_needed) else
                    HFmt.times(c, m) + " · " + HFmt.ruleText(c, m))
            if (m.purpose.isNotBlank()) s += " (" + m.purpose + ")"
            if (m.until != null) s += " · " + c.getString(R.string.med_last_day, Fmt.date(m.until))
            s
        }
        val past = items.filter { it.kind == HealthKind.CONDITION && !it.active }
        if (past.isNotEmpty()) out += c.getString(R.string.conditions_past) to past.map { itemLine(it.name, it.date, it.note, true) }
        val surg = items.filter { it.kind == HealthKind.SURGERY }
        if (surg.isNotEmpty()) out += HFmt.kindsLabel(c, HealthKind.SURGERY) to surg.map { itemLine(it.name, it.date, it.note, false) }
        val vacc = items.filter { it.kind == HealthKind.VACCINE }
        if (vacc.isNotEmpty()) out += HFmt.kindsLabel(c, HealthKind.VACCINE) to vacc.map { itemLine(it.name, it.date, it.note, false) }
        val upcoming = Visits.upcoming(visits, today)
        if (upcoming.isNotEmpty()) out += c.getString(R.string.doctor_upcoming) to upcoming.map { v ->
            v.title + " — " + HFmt.dateTime(c, v.date!!, v.time) + (if (v.place.isNotBlank()) " · " + v.place else "")
        }
        val done = Visits.done(visits, today).take(10)
        val linked = visits.mapNotNull { it.docId }.toSet()
        val reports = c.db.docs().filter { it.folderId in subtree && it.type == app.pensy.core.DocType.REFERTO && it.id !in linked }
        val history = (done.map { (it.date ?: today) to (it.title + (if (it.doctor.isNotBlank()) " · " + it.doctor else "")) } +
            reports.map { it.shownDate to Fmt.title(c, it.title, it.type, it.issuer) })
            .sortedByDescending { it.first }.take(10)
            .map { Fmt.date(it.first) + " — " + it.second }
        out += c.getString(R.string.doctor_last_visits) to history
        c.db.folder(folderId)?.note?.takeIf { it.isNotBlank() }?.let { note ->
            val lines = note.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith(":") }
            if (lines.isNotEmpty()) out += c.getString(R.string.doctor_notes) to lines
        }
        return out
    }

    /**
     * The same record as a PDF, with the measurements of the last three
     * months (chart and numbers) and how the medicines were taken.
     */
    private fun pdf(who: String, sections: List<Pair<String, List<String>>>) {
        val c = act
        c.showBusy(R.string.making_pdf)
        val today = LocalDate.now()
        val folder = c.db.folder(folderId)
        val measures = if (folder != null) c.measuresIn(folder, c.db.folders()) else emptyList()
        val subtree = Sorter.subtree(c.db.folders(), folderId)
        val saluteRoot = folder?.let { it.parentId == null && it.kind == app.pensy.core.FolderKind.SALUTE.code } ?: false
        val meds = inFolder(c.db.medicines(), { it.folderId }, subtree, saluteRoot).filter { it.active && !it.asNeeded }
        Thread {
            val f = try {
                val w = PdfWriter(c, "PENSY · " + c.getString(R.string.doctor_updated, Fmt.date(today)))
                w.title(c.getString(R.string.doctor_share_title) + if (who.isNotBlank()) " · $who" else "")
                w.subtitle(c.getString(R.string.doctor_updated, Fmt.date(today)))
                for ((title, lines) in sections) {
                    w.heading(title)
                    if (lines.isEmpty()) w.text(c.getString(R.string.doctor_none), 11f, color = 0xFF9A98A1.toInt())
                    for (l in lines) w.bullet(l)
                }
                // Measurements, last 90 days
                val from = today.minusDays(90).atStartOfDay()
                for (k in MeasureKind.entries) {
                    val list = Measures.of(measures, k, from)
                    if (list.isEmpty()) continue
                    w.heading(measureLabel(c, k) + " · " + c.getString(R.string.pdf_last_90))
                    val dec = if (k == MeasureKind.WEIGHT || k == MeasureKind.TEMPERATURE) 1 else 0
                    if (list.size > 1) w.chart(list, decimals = dec)
                    Measures.stats(list)?.let { st ->
                        fun v(a: Double, b: Double?) = (if (dec == 0) Math.round(a).toString() else HFmt.number(Math.round(a * 10) / 10.0)) +
                            (b?.let { "/" + Math.round(it) } ?: "")
                        w.text(c.getString(R.string.pdf_stats, v(st.min, st.min2), v(st.avg, st.avg2), v(st.max, st.max2), st.count) + " · " + k.unit,
                            10f, color = 0xFF66646C.toInt(), before = 4f)
                    }
                    for (m in list.takeLast(6).reversed()) w.bullet(Fmt.date(m.at.toLocalDate()) + " " + HFmt.time(m.at.toLocalTime()) + " — " + measureValue(m))
                }
                // How the medicines were taken (only if doses were marked)
                val adh = meds.mapNotNull { m ->
                    val a = Meds.adherence(m, today.minusDays(29), java.time.LocalDateTime.now())
                    if (a.taken + a.skipped == 0) null else m to a
                }
                if (adh.isNotEmpty()) {
                    w.heading(c.getString(R.string.pdf_adherence))
                    for ((m, a) in adh) w.bullet(m.name + ": " + c.getString(R.string.adherence_line, a.taken, a.skipped, a.unmarked) +
                        (a.percent?.let { " · $it%" } ?: ""))
                }
                w.text(c.getString(R.string.pdf_disclaimer), 8.5f, color = 0xFF9A98A1.toInt(), before = 18f)
                val out = Share.newFile(c, c.getString(R.string.doctor_share_title) + (if (who.isNotBlank()) " $who" else "") + " $today.pdf")
                w.finish(out)
                out
            } catch (t: Throwable) { null }
            c.runOnUiThread {
                c.hideBusy()
                if (f == null) c.toast(R.string.pdf_error) else Share.send(c, f, "application/pdf", c.getString(R.string.doctor_share_title))
            }
        }.start()
    }

    private fun share(who: String, sections: List<Pair<String, List<String>>>) {
        val c = act
        val txt = buildString {
            append(c.getString(R.string.doctor_share_title))
            if (who.isNotBlank()) append(" · ").append(who)
            append('\n').append(c.getString(R.string.doctor_updated, Fmt.date(LocalDate.now()))).append('\n')
            for ((title, lines) in sections) {
                append('\n').append(title.uppercase()).append('\n')
                if (lines.isEmpty()) append("– ").append(c.getString(R.string.doctor_none)).append('\n')
                for (l in lines) append("– ").append(l).append('\n')
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, c.getString(R.string.doctor_share_title))
            .putExtra(Intent.EXTRA_TEXT, txt)
        try {
            c.startActivity(Intent.createChooser(send, c.getString(R.string.doctor_share)))
        } catch (e: Exception) {
            c.toast(R.string.no_picker)
        }
    }
}
