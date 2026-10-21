package app.pensy

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.pensy.core.Boosters
import app.pensy.core.DoseStatus
import app.pensy.core.Folder
import app.pensy.core.FolderKind
import app.pensy.core.HealthItem
import app.pensy.core.HealthKind
import app.pensy.core.Medicine
import app.pensy.core.Meds
import app.pensy.core.Sorter
import app.pensy.core.StockMode
import app.pensy.core.Visit
import app.pensy.core.VisitStatus
import app.pensy.core.Visits
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * The health record on screen: words for doses and visits, the rows,
 * and the "Scheda salute" block shown at the top of the Salute folder
 * (and of a pet's folder in Animali).
 */

val MED_UNITS = listOf("compressa", "capsula", "goccia", "ml", "bustina", "siringa", "puff", "unita", "applicazione", "fiala", "cerotto")
val MED_EVERY = listOf(1, 2, 3, 7, 14, 30, 0)

const val COLOR_MED = 0xFF0D9488.toInt()
const val COLOR_VISIT = 0xFFE11D48.toInt()
const val COLOR_CONDITION = 0xFF7C3AED.toInt()
const val COLOR_ALLERGY = 0xFFD97706.toInt()

object HFmt {

    fun time(t: LocalTime): String = String.format("%02d:%02d", t.hour, t.minute)

    /** 1 → "1", 0.5 → "0,5" (in the phone's language). */
    fun number(x: Double): String =
        DecimalFormat("0.##", DecimalFormatSymbols.getInstance()).format(x)

    fun unit(ctx: Context, code: String, amount: Double): String {
        val res = when (code) {
            "capsula" -> R.plurals.unit_capsula
            "goccia" -> R.plurals.unit_goccia
            "ml" -> R.plurals.unit_ml
            "bustina" -> R.plurals.unit_bustina
            "puff" -> R.plurals.unit_puff
            "unita" -> R.plurals.unit_unita
            "applicazione" -> R.plurals.unit_applicazione
            "fiala" -> R.plurals.unit_fiala
            "cerotto" -> R.plurals.unit_cerotto
            "siringa" -> R.plurals.unit_siringa
            else -> R.plurals.unit_compressa
        }
        return ctx.resources.getQuantityString(res, if (amount == 1.0) 1 else 2)
    }

    /** "1 compressa", "20 gocce". */
    fun dose(ctx: Context, m: Medicine): String = number(m.amount) + " " + unit(ctx, m.unit, m.amount)

    fun every(ctx: Context, days: Int): String = when (days) {
        0 -> ctx.getString(R.string.med_as_needed)
        1 -> ctx.getString(R.string.med_every_1)
        2 -> ctx.getString(R.string.med_every_2)
        7 -> ctx.getString(R.string.med_every_7)
        14 -> ctx.getString(R.string.med_every_14)
        else -> ctx.getString(R.string.med_every_n, days)
    }

    /** "1 compressa · ogni giorno · 08:00, 20:00"; "1 siringa · ogni mese il giorno 15 · in giornata". */
    fun schedule(ctx: Context, m: Medicine): String {
        val parts = arrayListOf(dose(ctx, m))
        if (m.asNeeded) parts += every(ctx, 0)
        else {
            parts += ruleText(ctx, m)
            parts += times(ctx, m)
        }
        return parts.joinToString(" · ")
    }

    /** "08:00, 20:00", or "in giornata" when there is no time. */
    fun times(ctx: Context, m: Medicine): String =
        if (m.times.isEmpty()) ctx.getString(R.string.med_anytime) else m.times.sorted().joinToString(", ") { time(it) }

    /** A dose's time on screen: "08:00", or "In giornata". */
    fun slot(ctx: Context, t: LocalTime): String = if (Meds.isAnytime(t)) ctx.getString(R.string.dose_anytime) else time(t)

    private fun dayName(d: Int): String = java.time.DayOfWeek.of(d).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())

    /** When it is taken, in words: "ogni giorno", "ogni 4 settimane", "ogni lunedì, giovedì", "ogni mese il primo lunedì", "una volta, il 3 nov 2026". */
    fun ruleText(ctx: Context, m: Medicine): String = when (val r = m.schedule) {
        app.pensy.core.MedRule.Days -> when {
            m.everyDays >= 7 && m.everyDays % 7 == 0 && m.everyDays != 7 -> ctx.getString(R.string.med_every_weeks, m.everyDays / 7)
            else -> every(ctx, m.everyDays).lowercase()
        }
        is app.pensy.core.MedRule.Weekdays -> ctx.getString(R.string.med_rule_weekdays, r.days.sorted().joinToString(", ") { dayName(it) })
        is app.pensy.core.MedRule.MonthDay ->
            if (r.every == 1) ctx.getString(R.string.med_rule_monthday, r.day) else ctx.getString(R.string.med_rule_monthday_n, r.every, r.day)
        is app.pensy.core.MedRule.MonthWeekday -> {
            val words = ctx.getString(if (r.dow == 7) R.string.med_nth_f else R.string.med_nth_m).split('|')
            val nth = words.getOrElse(if (r.nth < 0) 4 else r.nth - 1) { words.last() }
            val what = nth + " " + dayName(r.dow)
            if (r.every == 1) ctx.getString(R.string.med_rule_monthweekday, what) else ctx.getString(R.string.med_rule_monthweekday_n, r.every, what)
        }
        app.pensy.core.MedRule.Once -> ctx.getString(R.string.med_rule_once, Fmt.date(m.start))
    }

    fun kindLabel(ctx: Context, k: HealthKind): String = ctx.getString(when (k) {
        HealthKind.CONDITION -> R.string.kind_condition
        HealthKind.ALLERGY -> R.string.kind_allergy
        HealthKind.SURGERY -> R.string.kind_surgery
        HealthKind.VACCINE -> R.string.kind_vaccine
    })

    fun kindsLabel(ctx: Context, k: HealthKind): String = ctx.getString(when (k) {
        HealthKind.CONDITION -> R.string.kinds_condition
        HealthKind.ALLERGY -> R.string.kinds_allergy
        HealthKind.SURGERY -> R.string.kinds_surgery
        HealthKind.VACCINE -> R.string.kinds_vaccine
    })

    fun statusLabel(ctx: Context, s: VisitStatus): String = ctx.getString(when (s) {
        VisitStatus.TODO -> R.string.status_todo
        VisitStatus.BOOKED -> R.string.status_booked
        VisitStatus.DONE -> R.string.status_done_v
    })

    /** "21 ott 2026 alle 10:30" or "21 ott 2026". */
    fun dateTime(ctx: Context, d: LocalDate, t: LocalTime?): String =
        if (t == null) Fmt.date(d) else ctx.getString(R.string.visit_booked_on, Fmt.date(d), time(t))

    /** The status line of a visit, and whether it needs attention. */
    fun visitLine(ctx: Context, v: Visit, today: LocalDate): Pair<String, Int> {
        val ink2 = ctx.getColor(R.color.ink2)
        return when {
            v.isDone(today) -> listOfNotNull(v.date?.let { Fmt.date(it) }, v.doctor.ifBlank { null }).joinToString(" · ") to ink2
            v.status == VisitStatus.BOOKED && v.date != null -> {
                val d = v.date
                val left = app.pensy.core.Reminders.daysLeft(today, d)
                val s = dateTime(ctx, d, v.time) + " · " + Fmt.relative(ctx, today, d) + (if (v.place.isNotBlank()) " · " + v.place else "")
                s to (if (left <= 1) ctx.getColor(R.color.warn) else ink2)
            }
            else -> {
                val d = v.dueBy
                when {
                    d == null -> ctx.getString(R.string.visit_no_date) to ink2
                    d.isBefore(today) -> ctx.getString(R.string.visit_overdue, Fmt.date(d)) to ctx.getColor(R.color.bad)
                    else -> ctx.getString(R.string.visit_by, Fmt.date(d)) + " · " + Fmt.relative(ctx, today, d) to
                        (if (app.pensy.core.Reminders.daysLeft(today, d) <= v.remindDays) ctx.getColor(R.color.warn) else ink2)
                }
            }
        }
    }

    /** The stock line of a medicine ("Ne restano circa 12 · finiscono il 3 nov"), and its colour. */
    fun stockLine(ctx: Context, m: Medicine, now: LocalDateTime): Pair<String, Int>? {
        val today = now.toLocalDate()
        val r = Meds.reorderOn(m, today) ?: return null
        val late = !r.isAfter(today)
        val color = ctx.getColor(if (late) R.color.warn else R.color.ink2)
        return when (m.stockMode) {
            StockMode.COUNT -> {
                val left = Meds.leftNow(m, now) ?: return null
                val out = Meds.runOut(m, today)
                val head = ctx.getString(R.string.med_left, number(Math.floor(left)))
                val tail = if (late) ctx.getString(R.string.med_order_now) else ctx.getString(R.string.med_order_by, Fmt.date(r))
                (head + (out?.let { " · " + ctx.getString(R.string.med_runs_out, Fmt.dateShort(it)) } ?: "") + " · " + tail) to color
            }
            StockMode.BOXES -> {
                val boxes = Meds.boxesLeft(m, now) ?: return null
                val head = ctx.getString(R.string.med_boxes_left, number(Math.floor(boxes * 10) / 10))
                val tail = if (late) ctx.getString(R.string.med_order_now) else ctx.getString(R.string.med_order_by, Fmt.date(r))
                (head + " · " + tail) to color
            }
            StockMode.INTERVAL -> (if (late) ctx.getString(R.string.med_order_now) else ctx.getString(R.string.med_order_on, Fmt.date(r))) to color
            StockMode.NONE -> null
        }
    }
}

/** A row with a round icon, a title and a line below; `right` is a short tag on the right. */
fun MainActivity.healthRow(
    iconRes: Int, color: Int, title: String, sub: String, subColor: Int = col(R.color.ink2),
    right: String? = null, rightColor: Int = col(R.color.ink2), dim: Boolean = false, onClick: (() -> Unit)?
): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        if (onClick != null) {
            background = ripple(null, 0)
            setOnClickListener { onClick() }
        }
    }
    val badge = FrameLayout(this)
    badge.background = rounded((color and 0x00FFFFFF) or 0x24000000, 14)
    badge.addView(icon(iconRes, color, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    if (dim) badge.alpha = 0.5f
    row.add(badge, w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(title, 16f, if (dim) col(R.color.ink2) else col(R.color.ink), MEDIUM, lines = 2))
    if (sub.isNotBlank()) mid.add(text(sub, 13f, subColor, lines = 3), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    if (right != null) row.add(text(right, 13f, rightColor, MEDIUM), w = WRAP, start = 8)
    return row
}

fun MainActivity.medRow(m: Medicine, now: LocalDateTime): View {
    val stock = HFmt.stockLine(this, m, now)
    var sub = HFmt.schedule(this, m)
    if (m.purpose.isNotBlank()) sub = m.purpose + " · " + sub
    val late = stock != null && stock.second == col(R.color.warn)
    return healthRow(
        R.drawable.ic_pill, COLOR_MED, m.name, sub + (stock?.let { "\n" + it.first } ?: ""),
        subColor = if (late) col(R.color.warn) else col(R.color.ink2), dim = !m.active
    ) { push(MedicineScreen(this, m.id)) }
}

fun MainActivity.visitRow(v: Visit, today: LocalDate): View {
    val (line, color) = HFmt.visitLine(this, v, today)
    return healthRow(R.drawable.ic_visit, COLOR_VISIT, v.title, line, color) {
        push(VisitScreen(this, v.id))
    }
}

/** Adds a "+ label" row at the end of a list card. */
fun MainActivity.addRow(label: String, onClick: () -> Unit): View {
    val row = hbox().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = ripple(null, 0)
        setOnClickListener { onClick() }
    }
    row.add(icon(R.drawable.ic_add, col(R.color.accent_ink), 22), w = dp(22), h = dp(22))
    row.add(text(label, 15f, col(R.color.accent_ink), MEDIUM), w = 0, weight = 1f, start = 14)
    return row
}

/** Is this folder (or its top-level ancestor) one that holds a health record? */
fun hasHealth(f: Folder, folders: List<Folder>): Boolean {
    val k = folderKind(f, folders)
    return k == FolderKind.SALUTE || k == FolderKind.ANIMALI
}

/** The Salute folder, where health items without a folder are shown. */
fun MainActivity.healthRoot(): Folder? = db.folders().firstOrNull { it.parentId == null && it.kind == FolderKind.SALUTE.code }

/** Health items that belong to this folder or below it (items without a folder count as Salute's). */
fun <T> inFolder(items: List<T>, folderOf: (T) -> Long?, subtree: Set<Long>, isSaluteRoot: Boolean): List<T> =
    items.filter { val f = folderOf(it); if (f == null) isSaluteRoot else f in subtree }

/**
 * The "Scheda salute" block of a folder: conditions, medicines with
 * today's doses and stock, visits booked, to book and done.
 * `expanded` shows every past visit instead of the last few.
 */
fun Screen.healthBlock(folder: Folder, folders: List<Folder>, expanded: Boolean, onExpand: () -> Unit): View {
    val c = act
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val subtree = Sorter.subtree(folders, folder.id)
    val isRoot = folder.parentId == null
    val saluteRoot = isRoot && folder.kind == FolderKind.SALUTE.code
    val items = inFolder(c.db.healthItems(), { it.folderId }, subtree, saluteRoot)
    val meds = inFolder(c.db.medicines(), { it.folderId }, subtree, saluteRoot)
    val visits = inFolder(c.db.visits(), { it.folderId }, subtree, saluteRoot)
    val docs = c.db.docs().filter { it.folderId in subtree && it.type == app.pensy.core.DocType.REFERTO }
    val box = c.vbox()
    // In a sub-folder (Salute › Cuore) only what is there, compact.
    val full = isRoot || items.isNotEmpty() || meds.isNotEmpty() || visits.isNotEmpty()

    if (!full) {
        val list = c.listCard()
        list.add(c.addRow(c.getString(R.string.health_add_any)) { healthAddSheet(c, folder.id) })
        box.add(list, top = 8)
        return box
    }

    // Show to the doctor (in a sub-folder: only what is there, e.g. Salute › Cuore at the cardiologist's, or one pet)
    box.add(c.button(c.getString(R.string.show_doctor), Btn.PRIMARY, R.drawable.ic_visit) {
        c.push(DoctorScreen(c, folder.id))
    }, top = 8)
    // The emergency card: for a person (the Salute folder), not for a pet
    if (saluteRoot) box.add(c.listCard().apply { add(c.emergencyRow()) }, top = 10)

    // Conditions, allergies, operations, vaccines
    box.add(c.section(R.string.health_conditions), top = 22)
    val cond = c.listCard()
    if (items.isEmpty()) {
        cond.add(c.text(c.getString(R.string.health_items_empty), 14f, c.col(R.color.ink2)).apply {
            setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(4))
        })
    }
    for (k in HealthKind.entries) {
        for (h in items.filter { it.kind == k }) cond.add(itemRow(c, h))
    }
    cond.add(c.addRow(c.getString(R.string.health_add_item)) { c.push(HealthItemScreen(c, folderId = folder.id)) })
    box.add(cond, top = 10)

    // Medicines
    box.add(c.section(R.string.meds), top = 22)
    val active = meds.filter { it.active }
    if (Meds.schedule(active, today).isNotEmpty() || active.any { Meds.extraOn(it, today).isNotEmpty() }) {
        box.add(c.doseCard(active, today, now) { refresh() }, top = 10)
    }
    val ml = c.listCard()
    if (active.isEmpty()) {
        ml.add(c.text(c.getString(R.string.meds_empty), 14f, c.col(R.color.ink2)).apply {
            setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(4))
        })
    }
    for (m in active) ml.add(c.medRow(m, now))
    ml.add(c.addRow(c.getString(R.string.meds_add)) { c.push(MedicineScreen(c, folderId = folder.id)) })
    box.add(ml, top = 10)
    val stopped = meds.filter { !it.active }
    if (stopped.isNotEmpty()) {
        box.add(c.text(c.getString(R.string.meds_stopped), 13f, c.col(R.color.ink2), MEDIUM), top = 14, start = 4)
        val sl = c.listCard()
        for (m in stopped) sl.add(c.medRow(m, now))
        box.add(sl, top = 8)
    }

    // Measurements
    box.add(c.section(R.string.measures), top = 22)
    box.add(c.measuresCard(folder, folders), top = 10)

    // Visits
    box.add(c.section(R.string.visits), top = 22)
    val booked = Visits.upcoming(visits, today)
    val toBook = Visits.toBook(visits)
    val done = Visits.done(visits, today)
    val linked = visits.mapNotNull { it.docId }.toSet()
    val reports = docs.filter { it.id !in linked }
    val vl = c.listCard()
    if (booked.isEmpty() && toBook.isEmpty() && done.isEmpty() && reports.isEmpty()) {
        vl.add(c.text(c.getString(R.string.visits_empty), 14f, c.col(R.color.ink2)).apply {
            setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(4))
        })
    }
    fun label(res: Int) = c.text(c.getString(res), 13f, c.col(R.color.ink2), MEDIUM).apply {
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(2))
    }
    if (booked.isNotEmpty()) {
        vl.add(label(R.string.visits_booked))
        for (v in booked) vl.add(c.visitRow(v, today))
    }
    if (toBook.isNotEmpty()) {
        vl.add(label(R.string.visits_todo))
        for (v in toBook) vl.add(c.visitRow(v, today))
    }
    // Done: visits and reports, newest first
    val history: List<Pair<LocalDate, View>> =
        done.map { (it.date ?: today) to c.visitRow(it, today) } +
            reports.map { d -> d.shownDate to c.healthRow(R.drawable.ic_doc, COLOR_VISIT,
                Fmt.title(c, d.title, d.type, d.issuer), Fmt.date(d.shownDate) + " · " + c.getString(R.string.visit_report)) {
                c.push(DocScreen(c, d.id))
            } }
    if (history.isNotEmpty()) {
        vl.add(label(R.string.visits_done))
        val sorted = history.sortedByDescending { it.first }
        val shown = if (expanded) sorted else sorted.take(4)
        for ((_, v) in shown) vl.add(v)
        if (!expanded && sorted.size > 4) {
            vl.add(c.text(c.getString(R.string.visits_show_all, sorted.size), 15f, c.col(R.color.accent_ink), MEDIUM).apply {
                setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
                background = c.ripple(null, 0)
                setOnClickListener { onExpand() }
            })
        }
    }
    vl.add(c.addRow(c.getString(R.string.visits_add)) { c.push(VisitScreen(c, folderId = folder.id)) })
    box.add(vl, top = 10)
    return box
}

private fun itemRow(c: MainActivity, h: HealthItem): View {
    val color = if (h.kind == HealthKind.ALLERGY) COLOR_ALLERGY else COLOR_CONDITION
    val icon = when (h.kind) {
        HealthKind.ALLERGY -> R.drawable.ic_warning
        HealthKind.VACCINE -> R.drawable.ic_check
        HealthKind.SURGERY -> R.drawable.ic_visit
        HealthKind.CONDITION -> R.drawable.ic_health
    }
    val parts = arrayListOf(HFmt.kindLabel(c, h.kind))
    if (h.fromBirth && h.kind != HealthKind.SURGERY && h.kind != HealthKind.VACCINE) parts += c.getString(R.string.from_birth_short)
    else h.date?.let { d -> parts += if (h.kind == HealthKind.CONDITION || h.kind == HealthKind.ALLERGY) c.getString(R.string.since, Fmt.date(d)) else Fmt.date(d) }
    if (h.kind == HealthKind.CONDITION && !h.active) parts += c.getString(R.string.item_past)
    if (h.note.isNotBlank()) parts += h.note
    var sub = parts.joinToString(" · ")
    var subColor = c.col(R.color.ink2)
    if (h.kind == HealthKind.VACCINE) h.nextDue?.let { d ->
        val today = LocalDate.now()
        sub += "\n" + c.getString(R.string.booster_on, Fmt.date(d))
        if (Boosters.stage(today, h) != app.pensy.core.Reminders.Stage.NONE) subColor = c.col(if (d.isBefore(today)) R.color.bad else R.color.warn)
    }
    return c.healthRow(icon, color, h.name, sub, subColor = subColor, dim = !h.active) {
        c.push(HealthItemScreen(c, id = h.id))
    }
}

/**
 * Today's doses, each with "presa" and "salta"; once marked, "annulla".
 * Extra doses of medicines taken when needed are listed too.
 */
fun MainActivity.doseCard(meds: List<Medicine>, today: LocalDate, now: LocalDateTime, onChange: () -> Unit): View {
    val card = card(16, col(R.color.surface))
    card.add(text(getString(R.string.meds_today), 13f, col(R.color.ink2), MEDIUM))
    for ((t, m) in Meds.schedule(meds, today)) {
        val status = Meds.statusOf(m, today, t)
        val anytime = Meds.isAnytime(t)
        val due = !anytime && !t.isAfter(now.toLocalTime())
        val row = hbox()
        row.add(text(HFmt.slot(this, t), if (anytime) 12.5f else 16f, col(if (status != null) R.color.ink3 else R.color.ink), BOLD), w = dp(58))
        val mid = vbox()
        mid.add(text(m.name, 15f, col(if (status != null) R.color.ink2 else R.color.ink), MEDIUM, lines = 1))
        val (sub, subColor) = when (status) {
            DoseStatus.TAKEN -> getString(R.string.dose_taken) to col(R.color.good)
            DoseStatus.SKIPPED -> getString(R.string.dose_skipped) to col(R.color.ink2)
            null -> (HFmt.dose(this, m) + (if (due) " · " + getString(R.string.dose_due_now) else if (anytime) " · " + getString(R.string.dose_due_today) else "")) to
                col(if (due) R.color.warn else R.color.ink2)
        }
        mid.add(text(sub, 13f, subColor, lines = 1), top = 1)
        row.add(mid, w = 0, weight = 1f)
        if (status == null) {
            row.add(iconButton(R.drawable.ic_check, R.string.dose_taken_action, col(R.color.good)) {
                db.logDose(m.id, today, t, DoseStatus.TAKEN); Doses.cancelIfDone(this, m.profileId, today, t); changed(); onChange()
            }, w = WRAP)
            row.add(iconButton(R.drawable.ic_close, R.string.dose_skip_action, col(R.color.ink2)) {
                db.logDose(m.id, today, t, DoseStatus.SKIPPED); Doses.cancelIfDone(this, m.profileId, today, t); changed(); onChange()
            }, w = WRAP)
        } else {
            row.add(text(getString(R.string.dose_undo), 13f, col(R.color.accent_ink), MEDIUM).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ripple(null, 12)
                setOnClickListener { db.unlogDose(m.id, today, t); changed(); onChange() }
            }, w = WRAP)
        }
        card.add(row, top = 8)
    }
    for (m in meds) for (l in Meds.extraOn(m, today)) {
        val row = hbox()
        row.add(text(HFmt.time(l.at.toLocalTime()), 16f, col(R.color.ink3), BOLD), w = dp(58))
        row.add(text(m.name + " · " + getString(R.string.dose_extra), 14f, col(R.color.ink2), lines = 1), w = 0, weight = 1f)
        card.add(row, top = 8)
    }
    return card
}

/** "What do you want to add?" for a health folder. */
fun healthAddSheet(c: MainActivity, folderId: Long) {
    Dialogs.sections(c, c.getString(R.string.add_title), listOf(
        c.getString(R.string.add_from_doc) to listOf(
            Triple(R.drawable.ic_camera, c.getString(R.string.add_photo_health)) { Capture.folderHint = folderId; Capture.camera(c) },
            Triple(R.drawable.ic_image, c.getString(R.string.add_file)) { Capture.folderHint = folderId; Capture.pickFile(c) }
        ),
        c.getString(R.string.add_without_doc) to listOf(
            Triple(R.drawable.ic_pill, c.getString(R.string.meds_add)) { c.push(MedicineScreen(c, folderId = folderId)) },
            Triple(R.drawable.ic_visit, c.getString(R.string.visits_add)) { c.push(VisitScreen(c, folderId = folderId)) },
            Triple(R.drawable.ic_health, c.getString(R.string.health_add_item)) { c.push(HealthItemScreen(c, folderId = folderId)) },
            Triple(R.drawable.ic_edit, c.getString(R.string.add_manual)) { c.push(CardScreen(c, folderHint = folderId)) }
        )
    ))
}

/** Small helper used by the forms: a LinearLayout of labelled pieces in a card. */
fun Context.formCard(): LinearLayout = card(16, col(R.color.surface))
