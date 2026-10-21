package app.pensy

import android.view.Gravity
import android.view.View
import app.pensy.core.Budget
import app.pensy.core.HealthCalendar
import app.pensy.core.HealthEvent
import app.pensy.core.Money
import java.time.LocalDate
import java.time.YearMonth

/** The internal calendar: every deadline on its day, with the month total, plus visits and medicines to order. */
class CalendarScreen(act: MainActivity) : Screen(act) {

    override val tab = 2
    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate? = LocalDate.now()

    override fun build(): View {
        val c = act
        val today = LocalDate.now()
        val all = c.db.deadlines()
        val summary = Budget.month(month, all, c.db.incomes())
        val events = healthEvents(today)
        val body = c.vbox()

        // Month switcher
        val head = c.hbox()
        head.add(c.iconButton(R.drawable.ic_chevron_left, R.string.prev_month) { month = month.minusMonths(1); selected = null; refresh() }, w = WRAP)
        head.add(c.text(Fmt.month(month), 18f, face = BOLD).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
        head.add(c.iconButton(R.drawable.ic_chevron_right, R.string.next_month) { month = month.plusMonths(1); selected = null; refresh() }, w = WRAP)
        body.add(head)
        c.hintCard("calendar", R.string.hint_calendar)?.let { body.add(it, top = 8) }

        // Grid
        val marks = HashMap<LocalDate, Int>()
        for (l in summary.lines) {
            val bit = when {
                l.paid -> 2
                l.projected -> 8
                l.due.isBefore(today) -> 4
                else -> 1
            }
            marks[l.due] = (marks[l.due] ?: 0) or bit
        }
        for (e in events) marks[e.date] = (marks[e.date] ?: 0) or 16
        val grid = CalendarGridView(c).apply {
            this.month = this@CalendarScreen.month
            this.marks = marks
            this.selected = this@CalendarScreen.selected?.takeIf { YearMonth.from(it) == this@CalendarScreen.month }
            onDay = { d -> selected = d; refresh() }
        }
        val gridCard = c.card(10)
        gridCard.add(grid)
        body.add(gridCard, top = 8)

        // The day tapped, right under the grid: only what is on that day.
        val sel = selected?.takeIf { YearMonth.from(it) == month }
        if (sel != null) {
            val lines = summary.lines.filter { it.due == sel }
            val dayEvents = events.filter { it.date == sel }
            val titleRow = c.hbox()
            titleRow.add(c.text(Fmt.dayLong(sel), 17f, face = BOLD), w = 0, weight = 1f)
            titleRow.add(c.button(c.getString(R.string.show_month), Btn.GHOST) { selected = null; refresh() }, w = WRAP)
            body.add(titleRow, top = 16)
            if (lines.isEmpty() && dayEvents.isEmpty()) {
                body.add(c.text(c.getString(R.string.nothing_this_day), 15f, c.col(R.color.ink2)), top = 8, start = 4)
            } else {
                val list = c.listCard()
                for (l in lines) list.add(c.monthLineRow(l, today))
                for (e in dayEvents) list.add(eventRow(e, false))
                body.add(list, top = 10)
            }
        }

        // Month total
        val totals = c.card(16)
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.month_expenses), Money.format(summary.outCents), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        val unpaid = summary.lines.filter { !it.paid }.sumOf { it.amountCents }
        row.add(c.stat(c.getString(R.string.still_to_pay), Money.format(unpaid), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        totals.add(row)
        if (summary.heavy) {
            totals.add(c.text(c.getString(R.string.heavy_badge), 14f, c.col(R.color.warn), MEDIUM), top = 10)
        }
        body.add(totals, top = if (sel != null) 22 else 12)

        // The whole month, when no day is picked
        if (sel == null) {
            body.add(c.text(c.getString(R.string.whole_month), 16f, face = MEDIUM), top = 22)
            body.add(c.text(c.getString(R.string.calendar_tap_day), 13f, c.col(R.color.ink2)), top = 2)
            if (summary.lines.isEmpty() && events.isEmpty()) {
                body.add(c.text(c.getString(R.string.nothing_this_month), 15f, c.col(R.color.ink2)), top = 10, start = 4)
            } else {
                if (summary.lines.isNotEmpty()) {
                    val list = c.listCard()
                    for (l in summary.lines) list.add(c.monthLineRow(l, today))
                    body.add(list, top = 10)
                }
                if (events.isNotEmpty()) {
                    body.add(c.section(R.string.home_health), top = 18)
                    val list = c.listCard()
                    for (e in events) list.add(eventRow(e, true))
                    body.add(list, top = 10)
                }
            }
        }
        body.add(View(c), h = c.dp(80))
        return withFab(page(c.getString(R.string.tab_calendar), body = body)) { c.addSheet() }
    }

    /**
     * Visits, operations, doses and orders of the month: the person in use,
     * and the other people too, with their name in front ("Luca · Visita").
     */
    private fun healthEvents(today: LocalDate): List<HealthEvent> {
        val c = act
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val out = ArrayList(HealthCalendar.events(c.db.medicines(), c.db.visits(), from, to, today, c.db.healthItems()))
        val me = c.db.profile
        val people = c.db.profiles().filter { it.id != me }
        if (people.isNotEmpty()) {
            val meds = c.db.allMedicines()
            val visits = c.db.allVisits()
            val items = c.db.allHealthItems()
            for (p in people) {
                val name = c.profileLabel(p)
                for (e in HealthCalendar.events(meds.filter { it.profileId == p.id }, visits.filter { it.profileId == p.id }, from, to, today,
                    items.filter { it.profileId == p.id })) out += e.copy(title = name + " · " + e.title)
            }
        }
        return out.sortedBy { it.date }
    }

    private fun eventRow(e: HealthEvent, showDate: Boolean): View {
        val c = act
        val day = if (showDate) Fmt.dateShort(e.date) + " · " else ""
        return when (e.kind) {
            HealthEvent.Kind.VISIT -> c.healthRow(R.drawable.ic_visit, COLOR_VISIT, e.title,
                day + c.getString(R.string.ev_visit) + (e.time?.let { " · " + HFmt.time(it) } ?: "")) { c.push(VisitScreen(c, e.id)) }
            HealthEvent.Kind.TO_BOOK -> c.healthRow(R.drawable.ic_visit, COLOR_VISIT, e.title,
                day + c.getString(R.string.ev_to_book), c.col(R.color.warn)) { c.push(VisitScreen(c, e.id)) }
            HealthEvent.Kind.REORDER -> c.healthRow(R.drawable.ic_cart, COLOR_MED, e.title,
                day + c.getString(R.string.ev_reorder), c.col(R.color.warn)) { c.push(MedicineScreen(c, e.id)) }
            HealthEvent.Kind.LAST_DOSE -> c.healthRow(R.drawable.ic_pill, COLOR_MED, e.title,
                day + c.getString(R.string.ev_last_dose)) { c.push(MedicineScreen(c, e.id)) }
            HealthEvent.Kind.BOOSTER -> c.healthRow(R.drawable.ic_check, COLOR_CONDITION, e.title,
                day + c.getString(R.string.ev_booster), c.col(R.color.warn)) { c.push(HealthItemScreen(c, id = e.id)) }
            HealthEvent.Kind.SURGERY -> c.healthRow(R.drawable.ic_health, COLOR_VISIT, e.title,
                day + c.getString(R.string.ev_surgery)) { c.push(HealthItemScreen(c, id = e.id)) }
            HealthEvent.Kind.DOSE -> c.healthRow(R.drawable.ic_pill, COLOR_MED, e.title,
                day + c.getString(R.string.ev_dose) + (e.time?.let { " · " + HFmt.time(it) } ?: "")) { c.push(MedicineScreen(c, e.id)) }
        }
    }
}
