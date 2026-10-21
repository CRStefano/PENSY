package app.pensy

import android.view.View
import app.pensy.core.Invest
import app.pensy.core.Money
import app.pensy.core.Trend
import app.pensy.core.TrendBucket
import app.pensy.core.TrendPeriod
import java.time.LocalDate
import java.time.YearMonth

/**
 * "Andamento": months put together. Income against expenses with the
 * monthly averages, year by year, everyday spending by category over the
 * whole period, the net pay over time. Only what was recorded counts.
 */
class TrendScreen(act: MainActivity) : Screen(act) {

    private var period = TrendPeriod.TWELVE

    override fun build(): View {
        val c = act
        val today = LocalDate.now()
        val now = YearMonth.from(today)
        val me = c.db.current()
        val deadlines = c.db.budgetDeadlines()
        val incomes = if (me.budgetOf != null) emptyList() else c.db.incomes()
        val spends = c.db.spends()
        val jarMoves = c.db.jarMoves()
        val investMoves = c.db.investMoves()
        val flows = Invest.flows(c.db.investments(), investMoves, LocalDate.of(1970, 1, 1), today, today)
        val first = Trend.firstMonth(now, deadlines, incomes, spends, jarMoves, investMoves)
        val (from, to) = Trend.range(period, now, first)
        val months = Trend.months(from, to, deadlines, incomes, spends, jarMoves, flows)
        val total = Trend.sum(months)
        val body = c.vbox()

        c.hintCard("trend", R.string.hint_trend)?.let { body.add(it, top = 4, bottom = 12) }
        body.add(c.chips(listOf(
            TrendPeriod.SIX to c.getString(R.string.trend_six),
            TrendPeriod.TWELVE to c.getString(R.string.trend_twelve),
            TrendPeriod.YEAR to c.getString(R.string.trend_year, now.year),
            TrendPeriod.ALL to c.getString(R.string.trend_all)
        ), period) { period = it; refresh() }, top = 4)
        body.add(c.text(c.getString(R.string.trend_range, Fmt.month(from), Fmt.month(to)), 13f, c.col(R.color.ink2)), top = 8, start = 4)

        if (total == null || (total.incomeCents == 0L && total.outCents == 0L && total.savedCents == 0L)) {
            body.add(c.text(c.getString(R.string.trend_empty), 15f, c.col(R.color.ink2)), top = 20, start = 4)
            return page(c.getString(R.string.trend_title), back = true, body = body)
        }

        // Totals and averages
        val n = maxOf(1, total.months)
        val box = c.card(18)
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.income), Money.format(total.incomeCents), c.col(R.color.ink2), c.col(R.color.good)), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.expenses), Money.format(total.outCents), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.trend_saved), Money.format(total.savedCents), c.col(R.color.ink2), c.col(R.color.accent_ink)), w = 0, weight = 1f)
        box.add(row)
        box.add(c.text(c.getString(R.string.trend_avg, Money.format(total.incomeCents / n), Money.format(total.outCents / n)), 13.5f, c.col(R.color.ink2)), top = 12)
        if (total.incomeCents > 0) {
            val left = total.leftCents / n
            box.add(c.text(c.getString(if (left >= 0) R.string.trend_left_avg else R.string.trend_missing_avg, Money.format(Math.abs(left))), 15f,
                c.col(if (left >= 0) R.color.ink else R.color.bad), MEDIUM), top = 6)
        }
        body.add(box, top = 12)

        // Income and expenses: by month, or by year over a long period
        val byYear = months.size > 24
        val buckets: List<TrendBucket> = if (byYear) Trend.byYear(months) else months
        body.add(c.section(if (byYear) R.string.trend_by_year_chart else R.string.trend_by_month_chart), top = 24)
        val chartBox = c.card(16)
        val chart = ChartView(c)
        chart.set(buckets.map { if (byYear) it.first.year.toString() else monthTick(it.first) }, listOf(
            ChartView.Series(buckets.map { it.incomeCents }, c.col(R.color.good), bars = true),
            ChartView.Series(buckets.map { it.outCents }, c.col(R.color.accent_mid), bars = true),
            ChartView.Series(buckets.map { it.savedCents.takeIf { s -> s != 0L } }, c.col(R.color.accent_ink))
        ))
        val info = c.text("", 13.5f, c.col(R.color.ink))
        chart.onSelect = { i -> info.text = bucketLine(buckets[i], byYear, today) }
        chart.selected = buckets.size - 1
        info.text = bucketLine(buckets.last(), byYear, today)
        chartBox.add(chart)
        chartBox.add(c.legend(listOf(c.col(R.color.good) to c.getString(R.string.income), c.col(R.color.accent_mid) to c.getString(R.string.expenses),
            c.col(R.color.accent_ink) to c.getString(R.string.trend_saved))), top = 8)
        chartBox.add(info, top = 8)
        body.add(chartBox, top = 10)

        // Net pay
        val pay = Trend.pay(incomes, from, to)
        if (pay.count { it.second > 0 } >= 2) {
            body.add(c.section(R.string.trend_pay), top = 24)
            val pb = c.card(16)
            val pc = ChartView(c).apply { heightDp = 160 }
            pc.set(pay.map { monthTick(it.first) }, listOf(ChartView.Series(pay.map { it.second.takeIf { v -> v > 0 } }, c.col(R.color.good), fill = true)))
            val pinfo = c.text("", 13.5f, c.col(R.color.ink2))
            pc.onSelect = { i -> pinfo.text = Fmt.month(pay[i].first) + ": " + (if (pay[i].second > 0) Money.format(pay[i].second) else c.getString(R.string.trend_no_pay)) }
            pb.add(pc)
            Trend.payChange(pay)?.let { ch -> pb.add(c.text(c.getString(R.string.trend_pay_change, Num.pct(ch)), 14f, face = MEDIUM), top = 10) }
            val extras = Trend.extras(incomes, from, to)
            if (extras.isNotEmpty()) pb.add(c.text(c.getString(R.string.trend_extras, extras.joinToString(", ") { Fmt.monthName(it.month) + " " + Money.format(it.amountCents) }), 13f, c.col(R.color.ink2)), top = 6)
            pb.add(pinfo, top = 6)
            body.add(pb, top = 10)
        }

        // Everyday spending by category, over the whole period
        val cats = Trend.categories(spends, from, to)
        if (cats.isNotEmpty()) {
            body.add(c.section(R.string.trend_cats), top = 24)
            val cb = c.card(16)
            val max = cats.first().second
            cats.forEachIndexed { i, (cat, amount) ->
                val r = c.hbox()
                r.add(c.roundIcon(spendIcon(cat), spendColor(cat), 32), w = c.dp(32), h = c.dp(32))
                val mid = c.vbox()
                val line = c.hbox()
                line.add(c.text(c.spendLabel(cat), 14f, face = MEDIUM, lines = 1), w = 0, weight = 1f)
                line.add(c.text(Money.format(amount), 14f, c.col(R.color.ink)), w = WRAP)
                mid.add(line)
                mid.add(c.progressBar(amount.toDouble() / max, spendColor(cat)), top = 4)
                mid.add(c.text(c.getString(R.string.trend_per_month, Money.format(amount / n)), 12f, c.col(R.color.ink2)), top = 3)
                r.add(mid, w = 0, weight = 1f, start = 12)
                cb.add(r, top = if (i == 0) 0 else 12)
            }
            body.add(cb, top = 10)
        }

        // Year by year
        val years = Trend.byYear(months)
        if (years.isNotEmpty()) {
            body.add(c.section(R.string.trend_years), top = 24)
            val list = c.listCard()
            for (y in years.reversed()) list.add(yearRow(y))
            body.add(list, top = 10)
        }

        body.add(c.text(c.getString(R.string.trend_note), 12.5f, c.col(R.color.ink3)), top = 18, start = 4)
        return page(c.getString(R.string.trend_title), back = true, body = body)
    }

    private fun bucketLine(b: TrendBucket, byYear: Boolean, today: LocalDate): String {
        val c = act
        val name = if (byYear) b.first.year.toString() else Fmt.month(b.first)
        var s = c.getString(R.string.trend_bucket, name, Money.format(b.incomeCents), Money.format(b.outCents))
        if (b.savedCents != 0L) s += " · " + c.getString(R.string.trend_bucket_saved, Money.format(b.savedCents))
        if (b.incomeCents > 0) s += " · " + c.getString(R.string.trend_bucket_left, Money.format(b.leftCents))
        if (!byYear && Trend.isPartial(b.first, today)) s += " · " + c.getString(R.string.trend_partial)
        return s
    }

    private fun yearRow(y: TrendBucket): View {
        val c = act
        val row = c.vbox().apply { setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12)) }
        val head = c.hbox()
        head.add(c.text(y.first.year.toString(), 16f, face = MEDIUM), w = 0, weight = 1f)
        if (y.incomeCents > 0) head.add(c.text(Money.format(y.leftCents), 15f, c.col(if (y.leftCents >= 0) R.color.good else R.color.bad), MEDIUM), w = WRAP)
        row.add(head)
        var sub = c.getString(R.string.trend_year_line, Money.format(y.incomeCents), Money.format(y.outCents))
        if (y.savedCents != 0L) sub += " · " + c.getString(R.string.trend_bucket_saved, Money.format(y.savedCents))
        if (y.months < 12) sub += " · " + c.resources.getQuantityString(R.plurals.trend_months, y.months, y.months)
        row.add(c.text(sub, 13f, c.col(R.color.ink2)), top = 2)
        return row
    }
}

/** Used by the budget: a button row under the chart. */
fun MainActivity.trendButton(): View = button(getString(R.string.trend_title), Btn.GHOST, R.drawable.ic_chart) { push(TrendScreen(this)) }
