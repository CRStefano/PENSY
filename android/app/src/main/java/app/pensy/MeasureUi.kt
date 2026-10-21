package app.pensy

import android.content.Context
import android.text.InputType
import android.view.View
import app.pensy.core.Csv
import app.pensy.core.Folder
import app.pensy.core.FolderKind
import app.pensy.core.Measure
import app.pensy.core.MeasureKind
import app.pensy.core.Measures
import app.pensy.core.Sorter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Measurements: blood pressure, glucose, weight, temperature,
 * saturation, heart rate. A list per kind with a chart over 30 days,
 * 3 months, a year or everything; minimum, maximum and average; CSV.
 * PENSY shows the numbers and the trend, it does not judge them: what
 * they mean is for the doctor to say.
 */

const val COLOR_MEASURE = 0xFF2563EB.toInt()

fun measureLabel(ctx: Context, k: MeasureKind): String = ctx.getString(
    when (k) {
        MeasureKind.PRESSURE -> R.string.m_pressure
        MeasureKind.GLUCOSE -> R.string.m_glucose
        MeasureKind.WEIGHT -> R.string.m_weight
        MeasureKind.TEMPERATURE -> R.string.m_temperature
        MeasureKind.SATURATION -> R.string.m_saturation
        MeasureKind.HEART -> R.string.m_heart
        MeasureKind.HEIGHT -> R.string.m_height
    }
)

fun measureHint(ctx: Context, k: MeasureKind): String = ctx.getString(
    when (k) {
        MeasureKind.PRESSURE -> R.string.m_pressure_hint
        MeasureKind.GLUCOSE -> R.string.m_glucose_hint
        MeasureKind.WEIGHT -> R.string.m_weight_hint
        MeasureKind.TEMPERATURE -> R.string.m_temperature_hint
        MeasureKind.SATURATION -> R.string.m_saturation_hint
        MeasureKind.HEART -> R.string.m_heart_hint
        MeasureKind.HEIGHT -> R.string.m_height_hint
    }
)

private fun decimalsOf(k: MeasureKind): Int = if (k == MeasureKind.WEIGHT || k == MeasureKind.TEMPERATURE) 1 else 0

private fun num(k: MeasureKind, v: Double): String =
    if (decimalsOf(k) == 0) Math.round(v).toString() else HFmt.number(Math.round(v * 10) / 10.0)

/** "135/85 mmHg · 72 bpm", "72,5 kg". */
fun measureValue(m: Measure): String = when (m.kind) {
    MeasureKind.PRESSURE -> num(m.kind, m.v1) + "/" + (m.v2?.let { num(m.kind, it) } ?: "?") + " mmHg" +
        (m.v3?.let { " · " + Math.round(it) + " bpm" } ?: "")
    else -> num(m.kind, m.v1) + " " + m.kind.unit
}

/** The text to put back in the field when editing. */
private fun measureInput(m: Measure): String = when (m.kind) {
    MeasureKind.PRESSURE -> num(m.kind, m.v1) + "/" + (m.v2?.let { num(m.kind, it) } ?: "") + (m.v3?.let { " " + Math.round(it) } ?: "")
    else -> num(m.kind, m.v1)
}

/** Measurements in a folder and below (without a folder: the Salute folder's). */
fun MainActivity.measuresIn(folder: Folder, folders: List<Folder>): List<Measure> {
    val subtree = Sorter.subtree(folders, folder.id)
    val saluteRoot = folder.parentId == null && folder.kind == FolderKind.SALUTE.code
    return inFolder(db.measures(), { it.folderId }, subtree, saluteRoot)
}

/** The measurements part of the health block: the last value of each kind measured. */
fun MainActivity.measuresCard(folder: Folder, folders: List<Folder>): View {
    val list = listCard()
    val all = measuresIn(folder, folders)
    for (k in MeasureKind.entries) {
        val last = Measures.latest(all, k) ?: continue
        val n = all.count { it.kind == k }
        list.add(healthRow(R.drawable.ic_chart, COLOR_MEASURE, measureLabel(this, k),
            measureValue(last) + " · " + Fmt.date(last.at.toLocalDate()),
            right = resources.getQuantityString(R.plurals.measures_count, n, n)) {
            push(MeasureScreen(this, k, folder.id))
        })
    }
    if (all.isEmpty()) list.add(text(getString(R.string.measures_empty), 14f, col(R.color.ink2)).apply {
        setPadding(dp(16), dp(14), dp(16), dp(4))
    })
    list.add(addRow(getString(R.string.measure_add)) { measureKindSheet(folder.id) })
    return list
}

fun MainActivity.measureKindSheet(folderId: Long?) {
    Dialogs.sheet(this, getString(R.string.measure_add), MeasureKind.entries.map { k ->
        Triple(R.drawable.ic_chart, measureLabel(this, k)) { push(MeasureEditScreen(this, kind = k, folderId = folderId)) }
    })
}

/** One kind of measurement: the chart, the numbers, the list. */
class MeasureScreen(act: MainActivity, private val kind: MeasureKind, private val folderId: Long) : Screen(act) {

    /** Days shown: 30, 90, 365, or 0 for everything. */
    private var days = 30

    override fun build(): View {
        val c = act
        val folders = c.db.folders()
        val folder = folders.firstOrNull { it.id == folderId }
        val all = (if (folder != null) c.measuresIn(folder, folders) else c.db.measures()).filter { it.kind == kind }
        val from = if (days > 0) LocalDate.now().minusDays(days.toLong()).atStartOfDay() else null
        val shown = Measures.of(all, kind, from)
        val body = c.vbox()

        body.add(c.chips(listOf(30 to c.getString(R.string.range_30), 90 to c.getString(R.string.range_90),
            365 to c.getString(R.string.range_365), 0 to c.getString(R.string.range_all)), days) { days = it; refresh() }, top = 4)

        val chartCard = c.card(14)
        if (shown.isEmpty()) {
            chartCard.add(c.text(c.getString(R.string.measures_none_range), 14f, c.col(R.color.ink2)))
        } else {
            chartCard.add(LineChartView(c).apply { decimals = decimalsOf(kind); points = shown })
            if (kind == MeasureKind.PRESSURE) {
                val legend = c.hbox()
                legend.add(c.text("● " + c.getString(R.string.m_systolic), 12f, c.col(R.color.accent)), w = WRAP)
                legend.add(c.text("● " + c.getString(R.string.m_diastolic), 12f, COLOR_MED), w = WRAP, start = 16)
                chartCard.add(legend, top = 6)
            }
        }
        body.add(chartCard, top = 12)

        Measures.stats(shown)?.let { st ->
            val box = c.card(16)
            val row = c.hbox()
            fun v(a: Double, b: Double?) = num(kind, a) + (b?.let { "/" + num(kind, it) } ?: "")
            row.add(c.stat(c.getString(R.string.m_min), v(st.min, st.min2), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
            row.add(c.stat(c.getString(R.string.m_avg), v(st.avg, st.avg2), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
            row.add(c.stat(c.getString(R.string.m_max), v(st.max, st.max2), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
            box.add(row)
            box.add(c.text(c.resources.getQuantityString(R.plurals.measures_count, st.count, st.count) + " · " + kind.unit, 13f, c.col(R.color.ink2)), top = 8)
            // Weight and height together: the body mass index, as a number (what it means is for the doctor).
            if (kind == MeasureKind.WEIGHT || kind == MeasureKind.HEIGHT) {
                val everything = if (folder != null) c.measuresIn(folder, folders) else c.db.measures()
                val w = Measures.latest(everything, MeasureKind.WEIGHT)
                val h = Measures.latest(everything, MeasureKind.HEIGHT)
                if (w != null && h != null) Measures.bmi(w.v1, h.v1)?.let { bmi ->
                    box.add(c.text(c.getString(R.string.m_bmi, HFmt.number(bmi), num(MeasureKind.HEIGHT, h.v1)), 13.5f, c.col(R.color.ink)), top = 8)
                }
            }
            body.add(box, top = 10)
        }

        body.add(c.button(c.getString(R.string.measure_add), Btn.PRIMARY, R.drawable.ic_add) {
            c.push(MeasureEditScreen(c, kind = kind, folderId = folderId))
        }, top = 14)

        body.add(c.section(R.string.measures_list), top = 24)
        val list = c.listCard()
        for (m in shown.reversed()) {
            val r = c.hbox().apply {
                setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
                background = c.ripple(null, 0)
                setOnClickListener { c.push(MeasureEditScreen(c, id = m.id, kind = kind, folderId = folderId)) }
            }
            val mid = c.vbox()
            mid.add(c.text(measureValue(m), 16f, face = MEDIUM))
            val sub = Fmt.date(m.at.toLocalDate()) + " · " + HFmt.time(m.at.toLocalTime()) + (if (m.note.isNotBlank()) " · " + m.note else "")
            mid.add(c.text(sub, 13f, c.col(R.color.ink2), lines = 2), top = 2)
            r.add(mid, w = 0, weight = 1f)
            list.add(r)
        }
        if (shown.isEmpty()) list.add(c.text(c.getString(R.string.measures_none_range), 14f, c.col(R.color.ink2)).apply {
            setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
        })
        body.add(list, top = 10)
        body.add(c.text(c.getString(R.string.measures_note), 13f, c.col(R.color.ink2)), top = 14, start = 4)

        val actions = listOf(Triple(R.drawable.ic_share, R.string.export_csv) { exportCsv(all) })
        return page(measureLabel(c, kind), back = true, actions = actions, body = body)
    }

    private fun exportCsv(list: List<Measure>) {
        val c = act
        if (list.isEmpty()) { c.toast(R.string.measures_empty); return }
        val header = if (kind == MeasureKind.PRESSURE)
            listOf(c.getString(R.string.csv_date), c.getString(R.string.csv_time), c.getString(R.string.m_systolic), c.getString(R.string.m_diastolic),
                c.getString(R.string.m_pulse), c.getString(R.string.field_note))
        else listOf(c.getString(R.string.csv_date), c.getString(R.string.csv_time), measureLabel(c, kind) + " (" + kind.unit + ")", c.getString(R.string.field_note))
        val rows = list.sortedBy { it.at }.map { m ->
            val base = listOf(m.at.toLocalDate().toString(), HFmt.time(m.at.toLocalTime()))
            if (kind == MeasureKind.PRESSURE) base + listOf(Csv.number(m.v1, 0), m.v2?.let { Csv.number(it, 0) } ?: "", m.v3?.let { Csv.number(it, 0) } ?: "", m.note)
            else base + listOf(Csv.number(m.v1, decimalsOf(kind)), m.note)
        }
        val f = Share.newFile(c, "PENSY " + measureLabel(c, kind) + " " + LocalDate.now() + ".csv")
        f.writeText(Csv.table(header, rows))
        Share.send(c, f, "text/csv", measureLabel(c, kind))
    }
}

/** A new measurement, or edit one. One field: "135/85 72", "72,5"... */
class MeasureEditScreen(act: MainActivity, private val id: Long? = null, kind: MeasureKind, private val folderId: Long?) : Screen(act) {

    private val original: Measure? = id?.let { act.db.measure(it) }
    private var kind = original?.kind ?: kind
    private var valueText = original?.let { measureInput(it) } ?: ""
    private var day: LocalDate = original?.at?.toLocalDate() ?: LocalDate.now()
    private var time: LocalTime = original?.at?.toLocalTime() ?: LocalTime.now().withSecond(0).withNano(0)
    private var note = original?.note ?: ""
    private var focused = original != null

    override fun build(): View {
        val c = act
        val body = c.vbox()
        if (original == null) {
            body.add(c.labeled(c.getString(R.string.field_type), c.chips(MeasureKind.entries.map { it to measureLabel(c, it) }, kind) {
                kind = it; refresh()
            }), top = 8)
        }
        val field = c.field(measureHint(c, kind), valueText, InputType.TYPE_CLASS_TEXT) { valueText = it }.apply {
            textSize = 26f
            typeface = MEDIUM
            if (kind != MeasureKind.PRESSURE) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        body.add(c.labeled(measureLabel(c, kind) + " (" + kind.unit + ")", field), top = 16)
        if (kind == MeasureKind.PRESSURE) body.add(c.text(c.getString(R.string.m_pressure_explain), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        if (!focused) field.post {
            focused = true
            field.requestFocus()
            c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(field, 0)
        }
        val when_ = c.hbox()
        when_.add(c.labeled(c.getString(R.string.spend_day), c.dateButton(day, "") { day = it }), w = 0, weight = 1f)
        when_.add(c.labeled(c.getString(R.string.m_time), c.timeButton(time, "") { time = it }), w = 0, weight = 1f, start = 10)
        body.add(when_, top = 14)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.m_note_hint), note) { note = it }), top = 14)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        if (original != null) {
            body.add(c.button(c.getString(R.string.delete), Btn.DANGER, R.drawable.ic_delete) {
                c.db.deleteMeasure(original.id); c.changed(); c.pop()
            }, top = 12)
        }
        return page(c.getString(if (original == null) R.string.measure_new else R.string.measure_edit), back = true, body = body)
    }

    private fun save() {
        val c = act
        val v = Measures.parse(kind, valueText) ?: run {
            c.toast(if (kind == MeasureKind.PRESSURE) R.string.m_bad_pressure else R.string.m_bad_value); return
        }
        val folder = folderId ?: original?.folderId ?: c.healthRoot()?.id
        val m = (original ?: Measure(kind = kind, at = LocalDateTime.now(), v1 = v.first)).copy(
            kind = kind, at = day.atTime(time), v1 = v.first, v2 = v.second, v3 = v.third, note = note.trim(), folderId = folder
        )
        c.db.saveMeasure(m)
        c.changed()
        c.toast(R.string.saved)
        c.pop()
    }
}
