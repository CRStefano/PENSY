package app.pensy

import android.view.Gravity
import android.view.View
import app.pensy.core.Budget
import app.pensy.core.Card
import app.pensy.core.DocType
import app.pensy.core.Invest
import app.pensy.core.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * "Bilancio": income, expenses and what is left, month by month, past and
 * projected; everyday spending by category; the savings jars.
 *
 * A profile whose expenses count in someone else's balance (a child)
 * shows its own expenses only, and says where they are counted.
 */
class BudgetScreen(act: MainActivity) : Screen(act) {

    override val tab = 3
    private var month: YearMonth = YearMonth.now()
    private var allSpends = false

    override fun build(): View {
        val c = act
        val today = LocalDate.now()
        val now = YearMonth.from(today)
        val me = c.db.current()
        val dependent = me.budgetOf != null
        val all = c.db.budgetDeadlines()
        val incomes = if (dependent) emptyList() else c.db.incomes()
        val inflows = if (dependent) emptyList() else c.db.inflows()
        val spends = c.db.spends()
        val jars = c.db.jars()
        val moves = c.db.jarMoves()
        val names = c.db.profiles().associateBy({ it.id }, { c.profileLabel(it) })
        val investments = c.db.investments()
        val investMoves = c.db.investMoves()
        // Twelve months: five behind, the current one, six ahead.
        val first = now.minusMonths(5)
        val flows = Invest.flows(investments, investMoves, minOf(first, month).atDay(1), maxOf(first.plusMonths(11), month.plusMonths(1)).atEndOfMonth(), today)
        val series = Budget.range(first, 12, all, incomes, spends, jars, moves, today, flows, inflows)
        val s = Budget.month(month, all, incomes, spends, jars, moves, today, flows, inflows)
        val body = c.vbox()
        c.hintCard("budget", R.string.hint_budget)?.let { body.add(it, top = 4, bottom = 8) }

        // Chart
        val chartCard = c.card(16)
        val chart = BarChartView(c).apply {
            data = series
            current = 5
            selected = series.indexOfFirst { it.month == month }.coerceAtLeast(0)
            onSelect = { i -> month = series[i].month; allSpends = false; refresh() }
        }
        chartCard.add(chart)
        val legend = c.hbox()
        legend.add(c.text("■ " + c.getString(R.string.expenses), 12f, c.col(R.color.accent_mid)), w = WRAP)
        if (!dependent) legend.add(c.text("▬ " + c.getString(R.string.income), 12f, c.col(R.color.good)), w = WRAP, start = 16)
        legend.add(c.text("■ " + c.getString(R.string.projected), 12f, c.col(R.color.accent_soft_ink)), w = WRAP, start = 16)
        chartCard.add(legend, top = 8)
        body.add(chartCard, top = 4)
        body.add(c.trendButton(), w = WRAP, top = 10)

        // Month switcher
        val head = c.hbox()
        head.add(c.iconButton(R.drawable.ic_chevron_left, R.string.prev_month) { month = month.minusMonths(1); allSpends = false; refresh() }, w = WRAP)
        head.add(c.text(Fmt.month(month), 18f, face = BOLD).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
        head.add(c.iconButton(R.drawable.ic_chevron_right, R.string.next_month) { month = month.plusMonths(1); allSpends = false; refresh() }, w = WRAP)
        body.add(head, top = 14)

        // Summary
        val box = c.card(18)
        val row = c.hbox()
        if (!dependent) row.add(c.stat(c.getString(R.string.income), if (s.hasIncome) Money.format(s.incomeCents) else "—", c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.expenses), Money.format(s.outCents), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        if (dependent) row.add(c.stat(c.getString(R.string.still_to_pay), Money.format(s.lines.filter { !it.paid }.sumOf { it.amountCents }),
            c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        box.add(row)
        if (dependent) {
            box.add(c.text(c.getString(R.string.budget_counts_in, c.profileLabel(me), names[me.budgetOf] ?: "—"), 13f, c.col(R.color.ink2)), top = 12)
        } else if (s.hasIncome) {
            val r = s.remainingCents
            box.add(c.text(c.getString(if (r >= 0) R.string.left else R.string.missing), 14f, c.col(R.color.ink2)), top = 14)
            box.add(c.text(Money.format(if (r >= 0) r else -r), 30f, c.col(if (r >= 0) R.color.ink else R.color.bad), BOLD))
            if (s.incomeEstimated) box.add(c.text(c.getString(R.string.income_estimated), 13f, c.col(R.color.ink2)), top = 2)
        }
        val detail = ArrayList<String>()
        if (s.otherIncomeCents > 0) detail += c.getString(R.string.budget_of_which_other, Money.format(s.otherIncomeCents))
        if (s.spendCents > 0) detail += c.getString(R.string.budget_of_which_spends, Money.format(s.spendCents))
        if (s.savedCents > 0) detail += c.getString(if (month.isAfter(now)) R.string.budget_saved_planned else R.string.budget_saved, Money.format(s.savedCents))
        if (s.savedCents < 0) detail += c.getString(R.string.budget_taken, Money.format(-s.savedCents))
        if (s.investedCents > 0) detail += c.getString(if (month.isAfter(now)) R.string.budget_invested_planned else R.string.budget_invested, Money.format(s.investedCents))
        if (s.investedCents < 0) detail += c.getString(R.string.budget_disinvested, Money.format(-s.investedCents))
        if (detail.isNotEmpty()) box.add(c.text(detail.joinToString("\n"), 13f, c.col(R.color.ink2)), top = 10)
        if (s.heavy) box.add(c.text(c.getString(R.string.heavy_badge), 14f, c.col(R.color.warn), MEDIUM), top = 10)
        // Compared with the previous month
        val prev = Budget.month(month.minusMonths(1), all, incomes, spends, jars, moves, today, flows, inflows)
        if (prev.outCents > 0 && s.outCents > 0) {
            val pct = ((s.outCents - prev.outCents) * 100 / prev.outCents).toInt()
            val word = Fmt.monthName(prev.month)
            val msg = when {
                pct > 0 -> c.getString(R.string.compare_more, pct, word)
                pct < 0 -> c.getString(R.string.compare_less, -pct, word)
                else -> c.getString(R.string.compare_same, word)
            }
            box.add(c.text(msg, 13f, c.col(R.color.ink2)), top = 10)
        }
        body.add(box, top = 8)

        // Income of the month: payslips, then the other money that came in
        if (!dependent) {
            val incomeHead = c.hbox()
            incomeHead.add(c.section(R.string.income), w = 0, weight = 1f)
            if (s.hasIncome) incomeHead.add(c.text(Money.format(s.incomeCents), 14f, c.col(R.color.ink2), MEDIUM), w = WRAP, end = 4)
            body.add(incomeHead, top = 24)
            val mine = incomes.filter { it.month == month }
            val pay = c.listCard()
            for (i in mine) {
                val r = c.hbox().apply {
                    setPadding(c.dp(14), c.dp(12), c.dp(16), c.dp(12))
                    background = c.ripple(null, 0)
                    setOnClickListener { c.push(CardScreen(c, incomeId = i.id)) }
                }
                r.add(c.typeBadge(DocType.BUSTA_PAGA), w = c.dp(42), h = c.dp(42))
                r.add(c.text(c.getString(if (i.extra) R.string.payslip_extra else R.string.payslip_regular), 16f, face = MEDIUM), w = 0, weight = 1f, start = 14)
                r.add(c.text(Money.format(i.amountCents), 15f, c.col(R.color.good), MEDIUM), w = WRAP)
                pay.add(r)
            }
            if (mine.isEmpty()) {
                val r = c.vbox().apply { setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14)) }
                r.add(c.text(c.getString(if (s.incomeEstimated) R.string.no_payslip_estimate else R.string.no_payslip), 14f, c.col(R.color.ink2)))
                pay.add(r)
            }
            for (x in s.inflows) pay.add(c.inflowRow(x))
            body.add(pay, top = 10)
            val split = Budget.cashSplit(emptyList(), s.inflows)
            if (split.any) body.add(c.text(c.getString(R.string.inflows_cash_line, Money.format(split.cardIn), Money.format(split.cashIn)),
                13f, c.col(R.color.ink2)), top = 8, start = 4)
            body.add(c.hbox().apply {
                add(c.button(c.getString(R.string.add_payslip), Btn.SECONDARY, R.drawable.ic_payslip) { c.addPayslip() }, w = 0, weight = 1f)
                add(c.button(c.getString(R.string.by_hand), Btn.GHOST, R.drawable.ic_edit) {
                    c.push(CardScreen(c, card = Card(type = DocType.BUSTA_PAGA, payMonth = month)))
                }, w = 0, weight = 1f, start = 10)
            }, top = 10)
            if (!month.isAfter(now)) {
                val day0 = if (month == now) today else month.atEndOfMonth()
                body.add(c.button(c.getString(R.string.inflow_add), Btn.SECONDARY, R.drawable.ic_wallet) { c.push(InflowScreen(c, day0 = day0)) },
                    w = WRAP, top = 10)
            }
        }

        // Everyday spending
        val spendHead = c.hbox()
        spendHead.add(c.section(R.string.spends_title), w = 0, weight = 1f)
        if (s.spendCents > 0) spendHead.add(c.text(Money.format(s.spendCents), 14f, c.col(R.color.ink2), MEDIUM), w = WRAP, end = 4)
        body.add(spendHead, top = 24)
        if (s.spends.isEmpty()) {
            body.add(c.text(c.getString(if (month.isAfter(now)) R.string.spends_future else R.string.spends_none), 14f, c.col(R.color.ink2)), top = 8, start = 4)
        } else {
            val split = Budget.cashSplit(s.spends)
            if (split.any) body.add(c.text(c.getString(R.string.spends_cash_line, Money.format(split.cardOut), Money.format(split.cashOut)),
                13f, c.col(R.color.ink2)), top = 6, start = 4)
            body.add(categoryCard(s.spends), top = 10)
            val list = c.listCard()
            val shown = if (allSpends) s.spends else s.spends.take(6)
            for (x in shown) list.add(c.spendRow(x, if (x.profileId != me.id) names[x.profileId] else null))
            if (shown.size < s.spends.size) {
                val more = c.hbox().apply {
                    setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
                    background = c.ripple(null, 0)
                    setOnClickListener { allSpends = true; refresh() }
                }
                more.add(c.text(c.getString(R.string.spends_all, s.spends.size), 15f, c.col(R.color.accent_ink), MEDIUM))
                list.add(more)
            }
            body.add(list, top = 10)
        }
        if (!month.isAfter(now)) {
            val day0 = if (month == now) today else month.atEndOfMonth()
            body.add(c.button(c.getString(R.string.spend_add), Btn.SECONDARY, R.drawable.ic_add) { c.push(SpendScreen(c, day0 = day0)) }, w = WRAP, top = 10)
        }

        // Fixed costs, and the 730
        c.fixedCostsCard()?.let { body.add(it, top = 24) }
        body.add(c.button(c.getString(R.string.tax_title), Btn.GHOST, R.drawable.ic_bank) { c.push(TaxScreen(c)) }, w = WRAP, top = 12)

        // Savings and investments
        body.add(c.section(R.string.savings_title), top = 24)
        body.add(savingsCard(jars, moves, investments, investMoves, today), top = 10)
        for (j in jars.filter { !it.closed }) body.add(c.jarCard(j, moves, today), top = 10)
        val sb = c.hbox()
        sb.add(c.button(c.getString(R.string.jar_new), Btn.GHOST, R.drawable.ic_jar) { c.push(JarEditScreen(c)) }, w = 0, weight = 1f)
        sb.add(c.button(c.getString(R.string.inv_title), Btn.GHOST, R.drawable.ic_trending) { c.push(InvestScreen(c)) }, w = 0, weight = 1f, start = 10)
        body.add(sb, top = 10)

        // Deadlines of the month
        body.add(c.section(R.string.month_deadlines), top = 24)
        val bills = s.lines.filter { it.amountCents > 0 }
        if (bills.isEmpty()) {
            body.add(c.text(c.getString(R.string.nothing_this_month), 15f, c.col(R.color.ink2)), top = 10, start = 4)
        } else {
            val list = c.listCard()
            for (l in bills) list.add(c.monthLineRow(l, today))
            body.add(list, top = 10)
        }
        body.add(View(c), h = c.dp(80))
        val actions = listOf(Triple(R.drawable.ic_share, R.string.export_title) { c.exportSheet() })
        return withFab(page(c.getString(R.string.tab_budget), actions = actions, body = body)) { c.addSheet() }
    }

    /** Everything put aside: jars and investments, with the gain; opens "Risparmi e investimenti". */
    private fun savingsCard(jars: List<app.pensy.core.Jar>, moves: List<app.pensy.core.JarMove>, invs: List<app.pensy.core.Investment>,
                            investMoves: List<app.pensy.core.InvestMove>, today: LocalDate): View {
        val c = act
        val jarTotal = jars.filter { !it.closed }.sumOf { app.pensy.core.Jars.saved(it, moves) }
        val tot = Invest.total(invs, investMoves, c.db.investValues(), today)
        val box = c.card(16).apply {
            background = c.ripple(c.rounded(c.col(R.color.surface), 22), 22)
            setOnClickListener { c.push(InvestScreen(c)) }
        }
        val head = c.hbox()
        head.add(c.roundIcon(R.drawable.ic_trending, c.col(R.color.accent), 40), w = c.dp(40), h = c.dp(40))
        val mid = c.vbox()
        mid.add(c.text(c.getString(R.string.savings_total), 13f, c.col(R.color.ink2)))
        mid.add(c.text(Money.format(jarTotal + tot.valueCents), 20f, face = BOLD), top = 1)
        head.add(mid, w = 0, weight = 1f, start = 12)
        head.add(c.icon(R.drawable.ic_chevron_right, c.col(R.color.ink3), 20), w = c.dp(20), h = c.dp(20))
        box.add(head)
        var line = c.getString(R.string.savings_line, Money.format(jarTotal), Money.format(tot.valueCents))
        if (tot.investedCents > 0) line += " (" + Num.pct(tot.gainPct) + ")"
        box.add(c.text(line, 13f, c.col(R.color.ink2)), top = 8)
        return box
    }

    /** Spending by category: a bar per category, biggest first. */
    private fun categoryCard(spends: List<app.pensy.core.Spend>): View {
        val c = act
        val box = c.card(16)
        val cats = Budget.byCategory(spends)
        val max = cats.firstOrNull()?.second ?: 1L
        cats.forEachIndexed { i, (cat, amount) ->
            val r = c.hbox()
            r.add(c.roundIcon(spendIcon(cat), spendColor(cat), 32), w = c.dp(32), h = c.dp(32))
            val mid = c.vbox()
            val line = c.hbox()
            line.add(c.text(c.spendLabel(cat), 14f, face = MEDIUM, lines = 1), w = 0, weight = 1f)
            line.add(c.text(Money.format(amount), 14f, c.col(R.color.ink2)), w = WRAP)
            mid.add(line)
            mid.add(c.progressBar(amount.toDouble() / max, spendColor(cat)), top = 4)
            r.add(mid, w = 0, weight = 1f, start = 12)
            box.add(r, top = if (i == 0) 0 else 10)
        }
        return box
    }
}
