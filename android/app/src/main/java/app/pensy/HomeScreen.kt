package app.pensy

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.pensy.core.Budget
import app.pensy.core.Meds
import app.pensy.core.Money
import app.pensy.core.MonthSummary
import app.pensy.core.Reminders
import app.pensy.core.VisitStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** "Oggi": how much is left this month, warnings, and what is due next. */
class HomeScreen(act: MainActivity) : Screen(act) {

    override val tab = 0

    override fun build(): View {
        val c = act
        val today = LocalDate.now()
        val me = c.db.current()
        val dependent = me.budgetOf != null
        val all = c.db.deadlines()
        val budget = c.db.budgetDeadlines()
        val incomes = if (dependent) emptyList() else c.db.incomes()
        val inflows = if (dependent) emptyList() else c.db.inflows()
        val spends = c.db.spends()
        val jars = c.db.jars()
        val moves = c.db.jarMoves()
        val month = YearMonth.from(today)
        val flows = app.pensy.core.Invest.flows(c.db.investments(), c.db.investMoves(), month.atDay(1), month.plusMonths(3).atEndOfMonth(), today)
        val body = c.vbox()

        // Hello (the owner) or the name of the person whose papers these are; the avatar switches profile.
        val head = c.hbox().apply { gravity = Gravity.TOP }
        val hello = c.vbox()
        val title = when {
            me.id != 1L -> c.profileLabel(me)
            me.name.isBlank() -> c.getString(R.string.hello)
            else -> c.getString(R.string.hello_name, me.name)
        }
        hello.add(c.text(title, 28f, face = BOLD, lines = 1))
        hello.add(c.text(if (me.id != 1L) c.getString(R.string.profile_of_line, Fmt.dayLong(today)) else Fmt.dayLong(today), 15f, c.col(R.color.ink2)), top = 2)
        head.add(hello, w = 0, weight = 1f)
        head.add(c.iconButton(R.drawable.ic_help, R.string.help, c.col(R.color.ink2)) { c.push(HelpScreen(c)) }, w = WRAP)
        head.add(c.avatar(me, 36).apply {
            contentDescription = c.getString(R.string.profiles_title)
            setOnClickListener { c.profileSheet() }
        }, w = c.dp(36), h = c.dp(36), start = 4, top = 6)
        body.add(head, top = 14)
        body.add(c.searchPill(), top = 16)

        // The first days: what is still to do, with its button
        val steps = c.firstStepsCard()
        if (steps != null) body.add(steps, top = 16)

        val s0 = Budget.month(month, budget, incomes, spends, jars, moves, today, flows, inflows)
        body.add(if (dependent) dependentCard(s0, me) else balanceCard(s0, today), top = 20)

        if (!dependent) for (s in Budget.range(month, 3, budget, incomes, spends, jars, moves, today, flows, inflows)) {
            if (s.heavy) body.add(heavyCard(s, month), top = 12)
        }

        if (steps == null && !DataFolder.isConfigured(c)) body.add(folderCard(), top = 12)

        othersCard(today)?.let { body.add(it, top = 12) }

        healthToday(body, today)

        val overdue = all.filter { !it.paid && !it.noDue && it.due.isBefore(today) }
        if (overdue.isNotEmpty()) {
            body.add(c.section(R.string.overdue), top = 26)
            val list = c.listCard()
            for (d in overdue) list.add(c.deadlineRow(d, today))
            body.add(list, top = 10)
        }

        val upcoming = all.filter { !it.paid && !it.noDue && !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(45)) }
        body.add(c.section(R.string.upcoming), top = 26)
        if (upcoming.isEmpty()) {
            val empty = c.card(22).apply { gravity = Gravity.CENTER_HORIZONTAL }
            empty.add(c.icon(R.drawable.ic_check, c.col(R.color.good), 32), w = c.dp(32), h = c.dp(32), gravity = Gravity.CENTER_HORIZONTAL)
            empty.add(c.text(c.getString(R.string.nothing_due), 16f, face = MEDIUM).apply { gravity = Gravity.CENTER }, top = 10)
            empty.add(c.text(c.getString(R.string.nothing_due_hint), 14f, c.col(R.color.ink2)).apply { gravity = Gravity.CENTER }, top = 4)
            body.add(empty, top = 10)
        } else {
            val list = c.listCard()
            for (d in upcoming) list.add(c.deadlineRow(d, today))
            body.add(list, top = 10)
        }

        val open = all.filter { !it.paid && it.noDue }
        if (open.isNotEmpty()) {
            body.add(c.section(R.string.no_due_list), top = 26)
            val list = c.listCard()
            for (d in open) list.add(c.deadlineRow(d, today))
            body.add(list, top = 10)
        }

        body.add(View(c), h = c.dp(80))
        return withFab(page(null, body = body)) { c.addSheet() }
    }

    /** Today's medicines, what to order, visits coming up or to book soon. Nothing when there is no health data. */
    private fun healthToday(body: LinearLayout, today: LocalDate) {
        val c = act
        val now = LocalDateTime.now()
        val meds = c.db.medicines().filter { it.active }
        val visits = c.db.visits()
        val doses = Meds.schedule(meds, today)
        val order = meds.filter { m -> Meds.reorderOn(m, today)?.let { !it.isAfter(today.plusDays(2)) } ?: false }
        val soon = visits.filter { v ->
            when (v.status) {
                VisitStatus.BOOKED -> v.date?.let { !it.isBefore(today) && !it.isAfter(today.plusDays(14)) } ?: false
                VisitStatus.TODO -> v.dueBy?.let { Reminders.daysLeft(today, it) <= v.remindDays } ?: false
                VisitStatus.DONE -> false
            }
        }.sortedBy { it.date ?: it.dueBy }
        val extra = meds.any { Meds.extraOn(it, today).isNotEmpty() }
        if (doses.isEmpty() && !extra && order.isEmpty() && soon.isEmpty()) return
        body.add(c.section(R.string.home_health), top = 26)
        if (doses.isNotEmpty() || extra) body.add(c.doseCard(meds, today, now) { refresh() }, top = 10)
        if (order.isEmpty() && soon.isEmpty()) return
        val list = c.listCard()
        for (m in order) {
            list.add(c.healthRow(R.drawable.ic_cart, COLOR_MED, m.name, HFmt.stockLine(c, m, now)?.first ?: c.getString(R.string.med_order_now),
                c.col(R.color.warn)) { c.push(MedicineScreen(c, m.id)) })
        }
        for (v in soon) list.add(c.visitRow(v, today))
        body.add(list, top = 10)
    }

    private fun balanceCard(s: MonthSummary, today: LocalDate): View {
        val c = act
        val onAccent = c.col(R.color.on_accent)
        val soft = c.col(R.color.on_accent_soft)
        val box = c.card(22, c.col(R.color.accent))
        box.add(c.text(c.getString(R.string.left_this_month, Fmt.monthName(s.month)), 15f, soft))
        if (s.hasIncome) {
            val remaining = s.remainingCents
            box.add(c.text(Money.format(remaining), 40f, onAccent, BOLD), top = 2)
            if (s.incomeEstimated) box.add(c.text(c.getString(R.string.income_estimated), 13f, soft), top = 2)
        } else {
            box.add(c.text("—", 40f, onAccent, BOLD), top = 2)
            box.add(c.text(c.getString(R.string.add_payslip_hint), 14f, soft), top = 4)
        }
        val unpaid = s.lines.filter { !it.paid }.sumOf { it.amountCents }
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.income), if (s.hasIncome) Money.format(s.incomeCents) else "—", soft, onAccent), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.expenses), Money.format(s.outCents), soft, onAccent), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.still_to_pay), Money.format(unpaid), soft, onAccent), w = 0, weight = 1f)
        box.add(row, top = 18)
        if (s.savedCents > 0) box.add(c.text(c.getString(R.string.budget_saved, Money.format(s.savedCents)), 13f, soft), top = 10)
        if (s.investedCents > 0) box.add(c.text(c.getString(R.string.budget_invested, Money.format(s.investedCents)), 13f, soft), top = if (s.savedCents > 0) 2 else 10)
        val actions = c.hbox()
        if (!s.salaryKnown) {
            actions.add(c.button(c.getString(R.string.add_payslip), Btn.ON_ACCENT, R.drawable.ic_payslip) { c.addPayslip() }, w = WRAP, end = 10)
        }
        actions.add(c.button(c.getString(R.string.spend_quick), Btn.ON_ACCENT, R.drawable.ic_add) { c.push(SpendScreen(c)) }, w = WRAP)
        box.add(actions, top = 16)
        return box
    }

    /** A profile whose expenses count in someone else's balance: its month at a glance. */
    private fun dependentCard(s: MonthSummary, me: app.pensy.core.Profile): View {
        val c = act
        val onAccent = c.col(R.color.on_accent)
        val soft = c.col(R.color.on_accent_soft)
        val box = c.card(22, c.col(R.color.accent))
        box.add(c.text(c.getString(R.string.dependent_month, Fmt.monthName(s.month)), 15f, soft))
        box.add(c.text(Money.format(s.outCents), 40f, onAccent, BOLD), top = 2)
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.deadlines_short), Money.format(s.lines.sumOf { it.amountCents }), soft, onAccent), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.spends_short), Money.format(s.spendCents), soft, onAccent), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.still_to_pay), Money.format(s.lines.filter { !it.paid }.sumOf { it.amountCents }), soft, onAccent), w = 0, weight = 1f)
        box.add(row, top = 18)
        val parent = me.budgetOf?.let { c.db.profileOf(it) }
        if (parent != null) box.add(c.text(c.getString(R.string.budget_counts_in, c.profileLabel(me), c.profileLabel(parent)), 13f, soft), top = 12)
        box.add(c.button(c.getString(R.string.spend_quick), Btn.ON_ACCENT, R.drawable.ic_add) { c.push(SpendScreen(c)) }, w = WRAP, top = 16)
        return box
    }

    private fun heavyCard(s: MonthSummary, now: YearMonth): View {
        val c = act
        val box = c.card(18, c.col(R.color.warn_soft))
        val head = c.hbox()
        head.add(c.icon(R.drawable.ic_warning, c.col(R.color.warn), 22), w = c.dp(22), h = c.dp(22))
        val title = if (s.month == now) c.getString(R.string.heavy_this_month)
        else c.getString(R.string.heavy_month, Fmt.cap(Fmt.monthName(s.month)))
        head.add(c.text(title, 16f, c.col(R.color.ink), MEDIUM), w = 0, weight = 1f, start = 10)
        box.add(head)
        val big = s.bigLines.maxByOrNull { it.amountCents }
        val others = s.lines.size - 1
        val msg = when {
            big == null -> c.getString(R.string.heavy_share, Money.format(s.outCents))
            others == 0 -> c.getString(R.string.heavy_single, Fmt.title(c, big.title, big.type, big.issuer), Money.format(big.amountCents))
            else -> c.resources.getQuantityString(
                R.plurals.heavy_detail, others,
                Fmt.title(c, big.title, big.type, big.issuer), Money.format(big.amountCents), others, Money.format(s.outCents)
            )
        }
        box.add(c.text(msg, 14f, c.col(R.color.ink2)), top = 6)
        box.setOnClickListener { c.showTab(3) }
        return box
    }

    /** The other people's deadlines that need attention, one line each; tap to switch. */
    private fun othersCard(today: LocalDate): View? {
        val c = act
        val me = c.db.profile
        val others = c.db.profiles().filter { it.id != me }
        if (others.isEmpty()) return null
        val byProfile = c.db.allDeadlines().filter { !it.paid && !it.noDue && it.profileId != me }.groupBy { it.profileId }
        val list = c.listCard()
        var any = false
        for (p in others) {
            val ds = byProfile[p.id] ?: continue
            val late = ds.count { it.due.isBefore(today) }
            val soon = ds.count { !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(14)) }
            if (late == 0 && soon == 0) continue
            any = true
            val parts = ArrayList<String>()
            if (late > 0) parts += c.resources.getQuantityString(R.plurals.others_late, late, late)
            if (soon > 0) parts += c.resources.getQuantityString(R.plurals.others_soon, soon, soon)
            val row = c.hbox().apply {
                setPadding(c.dp(14), c.dp(12), c.dp(16), c.dp(12))
                background = c.ripple(null, 0)
                setOnClickListener { c.switchProfile(p.id) }
            }
            row.add(c.avatar(p, 36), w = c.dp(36), h = c.dp(36))
            val mid = c.vbox()
            mid.add(c.text(c.profileLabel(p), 15f, face = MEDIUM, lines = 1))
            mid.add(c.text(parts.joinToString(" · "), 13f, c.col(if (late > 0) R.color.bad else R.color.ink2), lines = 1), top = 1)
            row.add(mid, w = 0, weight = 1f, start = 12)
            row.add(c.icon(R.drawable.ic_chevron_right, c.col(R.color.ink3), 20), w = c.dp(20), h = c.dp(20))
            list.add(row)
        }
        return if (any) list else null
    }

    private fun folderCard(): View {
        val c = act
        val box = c.card(18, c.col(R.color.surface))
        val head = c.hbox()
        head.add(c.icon(R.drawable.ic_folder, c.col(R.color.accent_ink), 22), w = c.dp(22), h = c.dp(22))
        head.add(c.text(c.getString(R.string.folder_card_title), 16f, face = MEDIUM), w = 0, weight = 1f, start = 10)
        box.add(head)
        box.add(c.text(c.getString(R.string.folder_card_msg), 14f, c.col(R.color.ink2)), top = 6)
        box.add(c.button(c.getString(R.string.folder_choose), Btn.SECONDARY) { DataFolder.chooseFolder(c) },
            w = LinearLayout.LayoutParams.WRAP_CONTENT, top = 12)
        return box
    }
}
