package app.pensy.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/*
 * The health record: conditions, medicines, visits.
 *
 * Everything belongs to a folder (Salute, or a pet in Animali), so the
 * same record works for a person and for a dog. Like the rest of the
 * core it is plain data and pure functions: what to take today, when a
 * box runs out, when to order the next one, which visit to remind.
 */

/** Conditions, allergies, past operations, vaccines: the things a doctor asks first. */
enum class HealthKind(val code: String) {
    CONDITION("patologia"),
    ALLERGY("allergia"),
    SURGERY("intervento"),
    VACCINE("vaccino");

    companion object {
        fun of(code: String?): HealthKind = entries.firstOrNull { it.code == code } ?: CONDITION
    }
}

data class HealthItem(
    val id: Long = 0,
    val profileId: Long = 0,
    val folderId: Long? = null,
    val kind: HealthKind = HealthKind.CONDITION,
    val name: String,
    /** Since when (condition), when (operation, vaccine). */
    val date: LocalDate? = null,
    /** A condition (or allergy) the person has had since birth: no date. */
    val fromBirth: Boolean = false,
    /** False for a condition that is over: kept in the history. */
    val active: Boolean = true,
    val note: String = "",
    /** Vaccines: when the next dose (booster, "richiamo") is due. */
    val nextDue: LocalDate? = null,
    /** Highest reminder stage already notified for the booster. */
    val notified: Int = 0
)

/** A dose the user marked: taken or skipped. */
enum class DoseStatus(val code: String) {
    TAKEN("taken"),
    SKIPPED("skipped");

    companion object {
        fun of(code: String?): DoseStatus = entries.firstOrNull { it.code == code } ?: TAKEN
    }
}

/**
 * One marked dose. `slot` is the time in the schedule it answers; null
 * for an extra dose (a medicine taken when needed). Doses never marked
 * are assumed taken for the stock, and counted as "not marked" for
 * adherence.
 */
data class DoseLog(
    val id: Long = 0,
    val medId: Long,
    val day: LocalDate,
    val slot: LocalTime?,
    val status: DoseStatus,
    val at: LocalDateTime
)

/** How PENSY knows when to order a medicine. */
enum class StockMode(val code: Int) {
    /** Not tracked. */
    NONE(0),
    /** Counts the pills left: it knows the day the box runs out. */
    COUNT(1),
    /** "I order it every N days", whatever is left. */
    INTERVAL(2),
    /** Counts whole boxes: "I have 3, I want to keep 1 aside". */
    BOXES(3);

    companion object {
        fun of(code: Int): StockMode = entries.firstOrNull { it.code == code } ?: NONE
    }
}

data class Medicine(
    val id: Long = 0,
    val profileId: Long = 0,
    val folderId: Long? = null,
    val name: String,
    /** What it is for, in the user's words ("pressione"). */
    val purpose: String = "",
    /** How much each time, in `unit`s: 1 tablet, 0.5 tablet, 20 drops. */
    val amount: Double = 1.0,
    /** Unit code: compressa, capsula, goccia, ml, bustina, puff, unita, applicazione, fiala, cerotto, siringa. */
    val unit: String = "compressa",
    /** Times of the day; empty = some time during the day (no alarm, a reminder in the morning). */
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    /** 1 every day, 2 every other day, 7 weekly...; 0 only when needed (no schedule). Used when `rule` is empty. */
    val everyDays: Int = 1,
    /** Other schedules (see MedRule): certain weekdays, a day of the month, once. */
    val rule: String = "",
    val start: LocalDate,
    /** Last day of the therapy; null = no end. */
    val until: LocalDate? = null,
    /** Notify at each dose time. */
    val remind: Boolean = true,
    val stockMode: StockMode = StockMode.NONE,
    /** Units left at the start of `stockDate` (COUNT mode). */
    val stock: Double? = null,
    val stockDate: LocalDate? = null,
    /** Units in a new box, added by "I bought a box". */
    val pack: Int = 0,
    /** Order this many days before the box runs out. */
    val leadDays: Int = 7,
    /** BOXES mode: boxes to keep aside; when only these are left, it is time to order. */
    val reserve: Int = 0,
    /** INTERVAL mode: order every N days, counting from `lastOrder`. */
    val orderEvery: Int = 0,
    val lastOrder: LocalDate? = null,
    /** The reorder date already notified, so it is said once. */
    val orderNotified: LocalDate? = null,
    /** False when the therapy was stopped: kept in the history. */
    val active: Boolean = true,
    val note: String = "",
    /** Marked doses (loaded with the medicine, not stored in its row). */
    val log: List<DoseLog> = emptyList()
) {
    val asNeeded: Boolean get() = everyDays <= 0 && rule.isBlank()
    /** The stock is counted (in units, or in boxes of `pack` units). */
    val counted: Boolean get() = stockMode == StockMode.COUNT || stockMode == StockMode.BOXES
    val schedule: MedRule get() = MedRule.parse(rule)
}

/**
 * When a medicine is taken, beyond "every N days" (stored as text in
 * Medicine.rule):
 *
 *   ""            every `everyDays` days from the start (0 = when needed)
 *   "w:1,4"       on these weekdays (1 = Monday ... 7 = Sunday)
 *   "m:15:1"      on day 15 of the month, every 1 month (the last day when shorter)
 *   "mw:1:1:1"    on the first Monday of the month, every 1 month (-1 = the last one)
 *   "once"        only on the start day
 */
sealed class MedRule {
    object Days : MedRule()
    class Weekdays(val days: Set<Int>) : MedRule()
    class MonthDay(val day: Int, val every: Int) : MedRule()
    class MonthWeekday(val nth: Int, val dow: Int, val every: Int) : MedRule()
    object Once : MedRule()

    fun code(): String = when (this) {
        Days -> ""
        is Weekdays -> "w:" + days.sorted().joinToString(",")
        is MonthDay -> "m:$day:$every"
        is MonthWeekday -> "mw:$nth:$dow:$every"
        Once -> "once"
    }

    companion object {
        fun parse(s: String?): MedRule {
            val t = s?.trim().orEmpty()
            if (t.isEmpty()) return Days
            if (t == "once") return Once
            val p = t.split(':')
            return try {
                when (p[0]) {
                    "w" -> Weekdays(p[1].split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()).takeIf { it.days.isNotEmpty() } ?: Days
                    "m" -> MonthDay(p[1].toInt().coerceIn(1, 31), p.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                    "mw" -> MonthWeekday(p[1].toInt().let { if (it < 0) -1 else it.coerceIn(1, 4) }, p[2].toInt().coerceIn(1, 7),
                        p.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                    else -> Days
                }
            } catch (e: Exception) { Days }
        }

        /** The n-th (1..4, or -1 for the last) given weekday of a month. */
        fun nthWeekday(m: java.time.YearMonth, nth: Int, dow: Int): LocalDate {
            if (nth < 0) {
                var d = m.atEndOfMonth()
                while (d.dayOfWeek.value != dow) d = d.minusDays(1)
                return d
            }
            var d = m.atDay(1)
            while (d.dayOfWeek.value != dow) d = d.plusDays(1)
            return d.plusWeeks((nth - 1).toLong())
        }
    }
}

/** Doses due, taken and skipped over a period. */
data class Adherence(val due: Int, val taken: Int, val skipped: Int) {
    val unmarked: Int get() = (due - taken - skipped).coerceAtLeast(0)
    /** Taken out of the marked ones, in percent; null when nothing was marked. */
    val percent: Int? get() = if (taken + skipped == 0) null else (taken * 100 + (taken + skipped) / 2) / (taken + skipped)
}

enum class VisitStatus(val code: String) {
    /** To book: maybe by a date (a follow-up, a prescription's priority). */
    TODO("todo"),
    /** Booked: day, maybe time and place. */
    BOOKED("booked"),
    /** Done. */
    DONE("done");

    companion object {
        fun of(code: String?): VisitStatus = entries.firstOrNull { it.code == code } ?: TODO
    }
}

/** A visit or a test: to book, booked, or done. */
data class Visit(
    val id: Long = 0,
    val profileId: Long = 0,
    val folderId: Long? = null,
    /** The report, once there is one. */
    val docId: Long? = null,
    val title: String,
    val status: VisitStatus = VisitStatus.TODO,
    /** Day of the visit (booked or done). */
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    /** To book by this day. */
    val dueBy: LocalDate? = null,
    /** Priority class written on the prescription: U, B, D, P or "". */
    val priority: String = "",
    val doctor: String = "",
    val place: String = "",
    /** First reminder, days before the visit (booked) or before dueBy (to book). */
    val remindDays: Int = 1,
    /** Highest reminder stage already notified. */
    val notified: Int = 0,
    val note: String = ""
) {
    /** Booked for a day already gone: it happened. */
    fun isPast(today: LocalDate): Boolean = status == VisitStatus.BOOKED && date != null && date.isBefore(today)
    fun isDone(today: LocalDate): Boolean = status == VisitStatus.DONE || isPast(today)
    fun isUpcoming(today: LocalDate): Boolean = status == VisitStatus.BOOKED && date != null && !date.isBefore(today)
}

/** Something health-related on a day of the calendar. */
data class HealthEvent(val date: LocalDate, val kind: Kind, val id: Long, val title: String, val time: LocalTime? = null) {
    enum class Kind { VISIT, TO_BOOK, REORDER, LAST_DOSE, BOOSTER, SURGERY, DOSE }
}

/*
 * Sorting note: only the single-selector compareBy { } and thenBy { } are used.
 * Both are inline; compareBy(a, b) with several selectors is not, and inside the
 * Kotlin library it uses invokedynamic, which crashes on Android with our dexer
 * (tools/check-indy.py fails the build if such a call comes back).
 */

/** The later of two days (maxOf would give a ChronoLocalDate). */
internal fun later(a: LocalDate, b: LocalDate): LocalDate = if (b.isAfter(a)) b else a

object Meds {

    /**
     * The slot of a dose with no time ("some time today"). A time no clock
     * picker can give, so it never clashes with a real one; it never rings.
     */
    val ANYTIME: LocalTime = LocalTime.of(0, 0, 0, 1)

    fun isAnytime(t: LocalTime): Boolean = t == ANYTIME

    /** The slots of a day with doses: the times, or one dose "during the day". */
    fun slots(m: Medicine): List<LocalTime> = if (m.times.isEmpty()) listOf(ANYTIME) else m.times.sorted()

    /** Does the schedule ask for a dose on this day? */
    fun takesOn(m: Medicine, day: LocalDate): Boolean {
        if (!m.active || m.asNeeded) return false
        if (day.isBefore(m.start)) return false
        if (m.until != null && day.isAfter(m.until)) return false
        return when (val r = m.schedule) {
            MedRule.Days -> m.everyDays > 0 && ChronoUnit.DAYS.between(m.start, day) % m.everyDays == 0L
            is MedRule.Weekdays -> day.dayOfWeek.value in r.days
            is MedRule.MonthDay -> {
                val ym = java.time.YearMonth.from(day)
                ChronoUnit.MONTHS.between(java.time.YearMonth.from(m.start), ym) % r.every == 0L &&
                    day.dayOfMonth == minOf(r.day, ym.lengthOfMonth())
            }
            is MedRule.MonthWeekday -> {
                val ym = java.time.YearMonth.from(day)
                ChronoUnit.MONTHS.between(java.time.YearMonth.from(m.start), ym) % r.every == 0L &&
                    day == MedRule.nthWeekday(ym, r.nth, r.dow)
            }
            MedRule.Once -> day == m.start
        }
    }

    fun dosesOn(m: Medicine, day: LocalDate): List<LocalTime> = if (takesOn(m, day)) slots(m) else emptyList()

    /** The next day (from `from`, included) with a dose, within about 13 months; null when none. */
    fun nextDay(m: Medicine, from: LocalDate): LocalDate? {
        var d = later(from, m.start)
        repeat(400) {
            if (m.until != null && d.isAfter(m.until)) return null
            if (takesOn(m, d)) return d
            d = d.plusDays(1)
        }
        return null
    }

    /** Units used on a day: the doses of the schedule not skipped, plus extra doses taken. */
    fun usedOn(m: Medicine, day: LocalDate): Double {
        val sched = dosesOn(m, day)
        if (m.log.isEmpty()) return sched.size * m.amount
        var n = sched.size
        for (l in m.log) {
            if (l.day != day) continue
            if (l.status == DoseStatus.SKIPPED && l.slot != null && l.slot in sched) n--
            if (l.status == DoseStatus.TAKEN && l.slot == null) n++
        }
        return n * m.amount
    }

    /** Units used today up to `now`: past doses not skipped, doses marked taken early, extra doses. */
    private fun usedSoFar(m: Medicine, now: LocalDateTime): Double {
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        val sched = dosesOn(m, today)
        var n = sched.count { !it.isAfter(t) }
        for (l in m.log) {
            if (l.day != today) continue
            val slot = l.slot
            when {
                slot == null -> if (l.status == DoseStatus.TAKEN && !l.at.isAfter(now)) n++
                slot !in sched -> {}
                l.status == DoseStatus.SKIPPED && !slot.isAfter(t) -> n--
                l.status == DoseStatus.TAKEN && slot.isAfter(t) -> n++
            }
        }
        return n * m.amount
    }

    /** How a dose of the schedule was marked, or null. */
    fun statusOf(m: Medicine, day: LocalDate, slot: LocalTime): DoseStatus? =
        m.log.lastOrNull { it.day == day && it.slot == slot }?.status

    /** Extra doses (when needed) taken on a day. */
    fun extraOn(m: Medicine, day: LocalDate): List<DoseLog> =
        m.log.filter { it.day == day && it.slot == null && it.status == DoseStatus.TAKEN }

    /** Doses due from `from` up to `now`, and how they were marked. */
    fun adherence(m: Medicine, from: LocalDate, now: LocalDateTime): Adherence {
        var due = 0
        var taken = 0
        var skipped = 0
        var d = later(from, m.start)
        var guard = 0
        while (!d.isAfter(now.toLocalDate()) && guard++ < 4000) {
            for (t in dosesOn(m, d)) {
                if (d == now.toLocalDate() && t.isAfter(now.toLocalTime())) continue
                due++
                when (statusOf(m, d, t)) {
                    DoseStatus.TAKEN -> taken++
                    DoseStatus.SKIPPED -> skipped++
                    null -> {}
                }
            }
            d = d.plusDays(1)
        }
        return Adherence(due, taken, skipped)
    }

    /** Units per day on average (0 when only as needed). */
    fun perDay(m: Medicine): Double {
        if (m.asNeeded) return 0.0
        val n = slots(m).size * m.amount
        return when (val r = m.schedule) {
            MedRule.Days -> n / m.everyDays
            is MedRule.Weekdays -> n * r.days.size / 7.0
            is MedRule.MonthDay -> n / (30.44 * r.every)
            is MedRule.MonthWeekday -> n / (30.44 * r.every)
            MedRule.Once -> 0.0
        }
    }

    /** Today's doses of every medicine, in time order. */
    fun schedule(meds: List<Medicine>, day: LocalDate): List<Pair<LocalTime, Medicine>> =
        meds.flatMap { m -> dosesOn(m, day).map { it to m } }
            .sortedWith(compareBy<Pair<LocalTime, Medicine>> { it.first }.thenBy { it.second.name.lowercase() })

    /** Units left at the start of `day`, assuming every dose since the count was taken. Null when not counted. */
    fun stockOn(m: Medicine, day: LocalDate): Double? {
        if (!m.counted) return null
        val s = m.stock ?: return null
        var d = m.stockDate ?: return s
        var left = s
        var guard = 0
        while (d.isBefore(day) && guard++ < 4000) {
            left -= usedOn(m, d)
            d = d.plusDays(1)
        }
        return maxOf(0.0, left)
    }

    /** Units left right now: the start of today minus what was used today so far. */
    fun leftNow(m: Medicine, now: LocalDateTime): Double? {
        val s = stockOn(m, now.toLocalDate()) ?: return null
        return maxOf(0.0, s - usedSoFar(m, now))
    }

    /**
     * The value to store when the user says "I have N now": the count at
     * the start of today, adding back what was used today so far.
     */
    fun stockAtStartOfDay(m: Medicine, haveNow: Double, now: LocalDateTime): Double = haveNow + usedSoFar(m, now)

    /** First day whose doses the stock cannot cover. Null when not counted, or not within about two years. */
    fun runOut(m: Medicine, from: LocalDate): LocalDate? {
        if (!m.counted || !m.active || m.asNeeded) return null
        var left = stockOn(m, from) ?: return null
        var d = from
        repeat(800) {
            if (m.until != null && d.isAfter(m.until)) return null
            val need = usedOn(m, d)
            if (need > 0 && left + 1e-9 < need) return d
            left -= need
            d = d.plusDays(1)
        }
        return null
    }

    /** Day to order the next box (or ask the doctor for the prescription). */
    fun reorderOn(m: Medicine, today: LocalDate): LocalDate? {
        if (!m.active) return null
        return when (m.stockMode) {
            StockMode.COUNT -> runOut(m, today)?.minusDays(m.leadDays.toLong())
            StockMode.BOXES -> if (m.reserve <= 0 || m.pack <= 0) runOut(m, today)?.minusDays(m.leadDays.toLong()) else reserveReached(m, today)
            StockMode.INTERVAL -> {
                if (m.orderEvery <= 0) return null
                val next = (m.lastOrder ?: m.start).plusDays(m.orderEvery.toLong())
                if (m.until != null && next.isAfter(m.until)) null else next
            }
            StockMode.NONE -> null
        }
    }

    /** BOXES mode: the first day only the boxes to keep aside are left (today when it already happened). */
    fun reserveReached(m: Medicine, today: LocalDate): LocalDate? {
        if (m.asNeeded) return null
        val keep = m.reserve.toDouble() * m.pack
        var left = stockOn(m, today) ?: return null
        var d = today
        repeat(800) {
            if (left <= keep + 1e-9) return d
            if (m.until != null && d.isAfter(m.until)) return null
            left -= usedOn(m, d)
            d = d.plusDays(1)
        }
        return null
    }

    /** Boxes left now (BOXES mode), e.g. 2.5. */
    fun boxesLeft(m: Medicine, now: LocalDateTime): Double? {
        if (m.pack <= 0) return null
        return leftNow(m, now)?.let { it / m.pack }
    }

    /** Medicines whose order day has come and was not notified yet. */
    fun toOrder(meds: List<Medicine>, today: LocalDate): List<Pair<Medicine, LocalDate>> =
        meds.mapNotNull { m ->
            val r = reorderOn(m, today) ?: return@mapNotNull null
            if (!r.isAfter(today) && m.orderNotified != r) m to r else null
        }

    /** Next dose time of one medicine strictly after `now` (doses with no time never ring). */
    fun nextFor(m: Medicine, now: LocalDateTime): LocalDateTime? {
        if (!m.active || m.asNeeded || m.times.isEmpty()) return null
        var d = later(now.toLocalDate(), m.start)
        val window = if (m.schedule == MedRule.Days) m.everyDays + 2 else 400
        repeat(window) {
            for (t in dosesOn(m, d)) {
                val at = d.atTime(t)
                if (at.isAfter(now)) return at
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** The next moment some medicine (with reminders on) is due, and which ones. */
    fun nextDose(meds: List<Medicine>, now: LocalDateTime): Pair<LocalDateTime, List<Medicine>>? {
        var best: LocalDateTime? = null
        val at = ArrayList<Medicine>()
        for (m in meds) {
            if (!m.remind) continue
            val t = nextFor(m, now) ?: continue
            when {
                best == null || t.isBefore(best) -> { best = t; at.clear(); at += m }
                t == best -> at += m
            }
        }
        return best?.let { it to at.toList() }
    }

    /** Doses (reminders on, not marked yet) in the window (after, until]: what to notify when the alarm rings. */
    fun dosesBetween(meds: List<Medicine>, after: LocalDateTime, until: LocalDateTime): List<Pair<LocalDateTime, Medicine>> {
        val out = ArrayList<Pair<LocalDateTime, Medicine>>()
        var d = after.toLocalDate()
        while (!d.isAfter(until.toLocalDate())) {
            for (m in meds) {
                if (!m.remind) continue
                for (t in dosesOn(m, d)) {
                    if (isAnytime(t)) continue
                    val at = d.atTime(t)
                    if (at.isAfter(after) && !at.isAfter(until) && statusOf(m, d, t) == null) out += at to m
                }
            }
            d = d.plusDays(1)
        }
        out.sortBy { it.first }
        return out
    }
}

object Visits {

    /**
     * Days a prescription's priority class allows, from the national plan
     * on waiting times: U within 72 hours, B within 10 days, D within 30
     * days (60 for tests), P within 120 days.
     */
    fun priorityDays(p: String): Int? = when (p.uppercase()) {
        "U" -> 3
        "B" -> 10
        "D" -> 30
        "P" -> 120
        else -> null
    }

    fun stage(today: LocalDate, v: Visit): Reminders.Stage = when (v.status) {
        VisitStatus.DONE -> Reminders.Stage.NONE
        VisitStatus.BOOKED -> {
            val d = v.date
            if (d == null) Reminders.Stage.NONE else {
                val left = Reminders.daysLeft(today, d)
                when {
                    left < 0 -> Reminders.Stage.NONE
                    left == 0L -> Reminders.Stage.TODAY
                    left == 1L -> Reminders.Stage.TOMORROW
                    left <= v.remindDays -> Reminders.Stage.EARLY
                    else -> Reminders.Stage.NONE
                }
            }
        }
        VisitStatus.TODO -> {
            val d = v.dueBy
            if (d == null) Reminders.Stage.NONE else {
                val left = Reminders.daysLeft(today, d)
                when {
                    left < 0 -> Reminders.Stage.OVERDUE
                    left == 0L -> Reminders.Stage.TODAY
                    left == 1L -> Reminders.Stage.TOMORROW
                    left <= v.remindDays -> Reminders.Stage.EARLY
                    else -> Reminders.Stage.NONE
                }
            }
        }
    }

    /** Visits to notify today, with the stage reached (same rule as deadlines). */
    fun due(today: LocalDate, visits: List<Visit>): List<Pair<Visit, Reminders.Stage>> =
        visits.mapNotNull { v ->
            val s = stage(today, v)
            if (s != Reminders.Stage.NONE && s.level > v.notified) v to s else null
        }

    /** Booked visits still to come, soonest first. */
    fun upcoming(visits: List<Visit>, today: LocalDate): List<Visit> =
        visits.filter { it.isUpcoming(today) }.sortedWith(compareBy<Visit> { it.date }.thenBy { it.time })

    /** Visits to book: those with a deadline first, by deadline. */
    fun toBook(visits: List<Visit>): List<Visit> =
        visits.filter { it.status == VisitStatus.TODO }.sortedWith(compareBy<Visit> { it.dueBy == null }.thenBy { it.dueBy })

    /** Visits done (or booked in the past), most recent first. */
    fun done(visits: List<Visit>, today: LocalDate): List<Visit> =
        visits.filter { it.isDone(today) }.sortedWith(compareByDescending<Visit> { it.date }.thenByDescending { it.time })
}

object HealthCalendar {

    /** Health events between two days (inclusive), for the calendar. */
    fun events(
        meds: List<Medicine>, visits: List<Visit>, from: LocalDate, to: LocalDate, today: LocalDate,
        items: List<HealthItem> = emptyList()
    ): List<HealthEvent> {
        val out = ArrayList<HealthEvent>()
        fun inside(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
        for (h in items) {
            if (h.kind == HealthKind.VACCINE) h.nextDue?.takeIf(::inside)?.let { out += HealthEvent(it, HealthEvent.Kind.BOOSTER, h.id, h.name) }
            if (h.kind == HealthKind.SURGERY) h.date?.takeIf(::inside)?.let { out += HealthEvent(it, HealthEvent.Kind.SURGERY, h.id, h.name) }
        }
        for (v in visits) {
            when (v.status) {
                VisitStatus.BOOKED, VisitStatus.DONE ->
                    v.date?.takeIf(::inside)?.let { out += HealthEvent(it, HealthEvent.Kind.VISIT, v.id, v.title, v.time) }
                VisitStatus.TODO ->
                    v.dueBy?.takeIf(::inside)?.let { out += HealthEvent(it, HealthEvent.Kind.TO_BOOK, v.id, v.title) }
            }
        }
        for (m in meds) {
            if (!m.active) continue
            // An order day already gone stays on today until the box is bought.
            Meds.reorderOn(m, today)?.let { later(it, today) }?.takeIf(::inside)?.let {
                out += HealthEvent(it, HealthEvent.Kind.REORDER, m.id, m.name)
            }
            m.until?.takeIf { inside(it) && !it.isBefore(m.start) }?.let { out += HealthEvent(it, HealthEvent.Kind.LAST_DOSE, m.id, m.name) }
            // Medicines taken now and then (weekly, monthly, once): their days are worth seeing.
            if (m.schedule != MedRule.Days || m.everyDays >= 7) {
                var d = later(from, m.start)
                var guard = 0
                while (!d.isAfter(to) && guard++ < 62) {
                    if (takesDay(m, d)) out += HealthEvent(d, HealthEvent.Kind.DOSE, m.id, m.name, m.times.minOrNull())
                    d = d.plusDays(1)
                }
            }
        }
        out.sortWith(compareBy<HealthEvent> { it.date }.thenBy { it.time ?: LocalTime.MIN }.thenBy { it.kind })
        return out
    }
}

private fun takesDay(m: Medicine, d: LocalDate): Boolean = Meds.takesOn(m, d)

/**
 * Vaccine boosters: a reminder a month before, a week before, the day
 * before, the day. Operations booked ahead: a week before, the day
 * before, the day.
 */
object Boosters {

    const val REMIND_DAYS = 30
    const val SURGERY_DAYS = 7

    fun stage(today: LocalDate, h: HealthItem): Reminders.Stage {
        if (h.kind == HealthKind.SURGERY) {
            val d = h.date ?: return Reminders.Stage.NONE
            if (d.isBefore(today)) return Reminders.Stage.NONE
            return Reminders.stageFor(today, d, SURGERY_DAYS)
        }
        val due = h.nextDue
        if (h.kind != HealthKind.VACCINE || due == null) return Reminders.Stage.NONE
        return Reminders.stageFor(today, due, REMIND_DAYS)
    }

    /** Boosters to notify today, with the stage reached. */
    fun due(today: LocalDate, items: List<HealthItem>): List<Pair<HealthItem, Reminders.Stage>> =
        items.mapNotNull { h ->
            val s = stage(today, h)
            if (s != Reminders.Stage.NONE && s.level > h.notified) h to s else null
        }
}

/** What can be measured at home (and, for a pet, its weight). */
enum class MeasureKind(val code: String, val unit: String) {
    PRESSURE("pressione", "mmHg"),
    GLUCOSE("glicemia", "mg/dL"),
    WEIGHT("peso", "kg"),
    TEMPERATURE("temperatura", "°C"),
    SATURATION("saturazione", "%"),
    HEART("battito", "bpm"),
    HEIGHT("altezza", "cm");

    companion object {
        fun of(code: String?): MeasureKind = entries.firstOrNull { it.code == code } ?: WEIGHT
    }
}

/** One measurement. Blood pressure: v1 systolic, v2 diastolic, v3 pulse (optional). */
data class Measure(
    val id: Long = 0,
    val profileId: Long = 0,
    val folderId: Long? = null,
    val kind: MeasureKind,
    val at: LocalDateTime,
    val v1: Double,
    val v2: Double? = null,
    val v3: Double? = null,
    val note: String = ""
)

object Measures {

    data class Stats(
        val count: Int, val min: Double, val max: Double, val avg: Double,
        val min2: Double?, val max2: Double?, val avg2: Double?
    )

    /** Measurements of a kind between two moments (inclusive), oldest first. */
    fun of(list: List<Measure>, kind: MeasureKind, from: LocalDateTime? = null, to: LocalDateTime? = null): List<Measure> =
        list.filter { it.kind == kind && (from == null || !it.at.isBefore(from)) && (to == null || !it.at.isAfter(to)) }
            .sortedBy { it.at }

    fun latest(list: List<Measure>, kind: MeasureKind): Measure? = list.filter { it.kind == kind }.maxByOrNull { it.at }

    fun stats(list: List<Measure>): Stats? {
        if (list.isEmpty()) return null
        val v1 = list.map { it.v1 }
        val v2 = list.mapNotNull { it.v2 }
        return Stats(
            list.size, v1.minOrNull()!!, v1.maxOrNull()!!, v1.average(),
            v2.minOrNull(), v2.maxOrNull(), if (v2.isEmpty()) null else v2.average()
        )
    }

    private val NUM = Regex("""\d+(?:[.,]\d+)?""")

    /**
     * Values typed in one field: "120/80", "120 80 72", "72,5", "36.8".
     * Blood pressure needs two numbers (systolic above diastolic); a third is the pulse.
     * Null when the text does not fit the kind or the numbers are not plausible.
     */
    fun parse(kind: MeasureKind, text: String): Triple<Double, Double?, Double?>? {
        val n = NUM.findAll(text).map { it.value.replace(',', '.').toDouble() }.toList()
        if (n.isEmpty()) return null
        return when (kind) {
            MeasureKind.PRESSURE -> {
                if (n.size < 2) return null
                val sys = n[0]
                val dia = n[1]
                if (sys !in 50.0..300.0 || dia !in 20.0..200.0 || dia >= sys) return null
                Triple(sys, dia, n.getOrNull(2)?.takeIf { it in 20.0..250.0 })
            }
            MeasureKind.GLUCOSE -> n[0].takeIf { it in 10.0..1000.0 }?.let { Triple(it, null, null) }
            MeasureKind.WEIGHT -> n[0].takeIf { it in 0.05..400.0 }?.let { Triple(it, null, null) }
            MeasureKind.TEMPERATURE -> n[0].takeIf { it in 30.0..45.0 }?.let { Triple(it, null, null) }
            MeasureKind.SATURATION -> n[0].takeIf { it in 50.0..100.0 }?.let { Triple(it, null, null) }
            MeasureKind.HEART -> n[0].takeIf { it in 20.0..250.0 }?.let { Triple(it, null, null) }
            MeasureKind.HEIGHT -> n[0].let { if (it in 0.3..2.6) it * 100 else it }.takeIf { it in 30.0..260.0 }?.let { Triple(it, null, null) }
        }
    }

    /** Body mass index from a weight in kg and a height in cm (one decimal); null when it cannot be worked out. */
    fun bmi(weightKg: Double, heightCm: Double): Double? {
        if (heightCm < 50 || weightKg <= 0) return null
        val m = heightCm / 100
        return Math.round(weightKg / (m * m) * 10) / 10.0
    }
}
