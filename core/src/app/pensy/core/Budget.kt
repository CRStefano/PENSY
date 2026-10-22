package app.pensy.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/*
 * The monthly balance: "how much is left this month?"
 *
 *   remaining = income - everything due - everyday spending - money put aside - money invested
 *
 * Income is the payslip of that month (when it is missing, the most
 * recent earlier payslip is used as an estimate) plus the other money
 * that came in that month: a gift, a refund, something sold. That money
 * never replaces the payslip nor its estimate. Recurring deadlines
 * are expanded into the future as virtual occurrences, so the next
 * months can be projected without creating rows in the database.
 */

/** One expense in a month, real or projected. */
data class MonthLine(
    val deadlineId: Long,
    val type: DocType,
    val title: String,
    val issuer: String,
    val amountCents: Long,
    val due: LocalDate,
    val paid: Boolean,
    /** True when it comes from a recurrence and does not exist yet. */
    val projected: Boolean,
    val estimated: Boolean
)

data class MonthSummary(
    val month: YearMonth,
    val incomeCents: Long,
    val hasIncome: Boolean,
    val incomeEstimated: Boolean,
    val lines: List<MonthLine>,
    /** Everyday expenses of the month. */
    val spends: List<Spend> = emptyList(),
    /** Money put aside in the jars (for the coming months: the suggested quotas). */
    val savedCents: Long = 0,
    /** Money put into investments (for the coming months: the plans). */
    val investedCents: Long = 0,
    /** Money that came in outside the payslip (already included in incomeCents). */
    val inflows: List<Inflow> = emptyList(),
    /** A payslip, recorded or estimated: false when the income is only other money. */
    val salaryKnown: Boolean = hasIncome
) {
    val otherIncomeCents: Long = inflows.sumOf { it.amountCents }
    val spendCents: Long = spends.sumOf { it.amountCents }
    val outCents: Long = lines.sumOf { it.amountCents } + spendCents
    val remainingCents: Long get() = incomeCents - outCents - savedCents - investedCents
    val bigLines: List<MonthLine> = lines.filter { Budget.isBig(it.amountCents, incomeCents, hasIncome) }
    val heavy: Boolean = Budget.isHeavy(this)
}

object Budget {

    /** Below this an expense is never "big" (300 €), whatever the income. */
    const val BIG_FLOOR_CENTS = 30_000L

    /** Big expense: at least 20% of the income (never under 150 €), or 300 € when income is unknown. */
    fun isBig(amount: Long, income: Long, hasIncome: Boolean): Boolean =
        if (hasIncome && income > 0) amount >= maxOf(income / 5, BIG_FLOOR_CENTS / 2)
        else amount >= BIG_FLOOR_CENTS

    /**
     * A month is heavy when a big expense lands together with at least
     * two other expenses, or when expenses eat 60% or more of the income.
     */
    fun isHeavy(s: MonthSummary): Boolean {
        val paying = s.lines.count { it.amountCents > 0 }  // reminders (0 €) don't weigh
        if (paying == 0) return false
        if (s.hasIncome && s.incomeCents > 0 && s.outCents * 10 >= s.incomeCents * 6) return true
        return s.bigLines.isNotEmpty() && paying >= s.bigLines.size + 2
    }

    /** All expenses between two dates (inclusive), with recurring ones expanded. */
    fun lines(deadlines: List<Deadline>, from: LocalDate, to: LocalDate): List<MonthLine> {
        val out = ArrayList<MonthLine>()
        for (d in deadlines) {
            // Without a due date: counted on the day it was paid, or not at all while unpaid.
            val day = d.day ?: continue
            if (!day.isBefore(from) && !day.isAfter(to)) {
                out += MonthLine(d.id, d.type, d.title, d.issuer, d.amountCents, day, d.paid, false, d.estimated)
            }
            // Only the unpaid head of a series projects forward: a paid one
            // already has its successor stored in the database.
            if (d.recurring && !d.paid) {
                var k = 1L
                while (true) {
                    // Always from the original date: Jan 31 -> Feb 28 -> Mar 31, no drift.
                    val date = d.due.plusMonths(k * d.everyMonths)
                    if (date.isAfter(to)) break
                    if (!date.isBefore(from)) {
                        out += MonthLine(d.id, d.type, d.title, d.issuer, d.amountCents, date, false, true, true)
                    }
                    k++
                    if (k > 1200) break
                }
            }
        }
        out.sortBy { it.due }
        return out
    }

    /**
     * Income for a month: the sum of its records, or, when there are none,
     * the regular salary of the latest earlier month as an estimate
     * (extra months such as the tredicesima are left out of the estimate).
     * Returns (cents, hasIncome, estimated).
     */
    fun income(month: YearMonth, incomes: List<Income>): Triple<Long, Boolean, Boolean> {
        val exact = incomes.filter { it.month == month }
        if (exact.isNotEmpty()) return Triple(exact.sumOf { it.amountCents }, true, false)
        val last = incomes.filter { !it.extra && it.month.isBefore(month) }.maxByOrNull { it.month }
            ?: return Triple(0L, false, false)
        val regular = incomes.filter { !it.extra && it.month == last.month }.sumOf { it.amountCents }
        return Triple(regular, true, true)
    }

    fun month(
        month: YearMonth, deadlines: List<Deadline>, incomes: List<Income>,
        spends: List<Spend> = emptyList(), jars: List<Jar> = emptyList(), moves: List<JarMove> = emptyList(),
        today: LocalDate = LocalDate.now(), invest: List<InvestFlow> = emptyList(), inflows: List<Inflow> = emptyList()
    ): MonthSummary {
        val (inc, has, est) = income(month, incomes)
        val mine = spends.filter { YearMonth.from(it.day) == month }.sortedByDescending { it.day }
        val came = inflows.filter { YearMonth.from(it.day) == month }.sortedByDescending { it.day }
        val other = came.sumOf { it.amountCents }
        return MonthSummary(
            month, inc + other, has || came.isNotEmpty(), est, lines(deadlines, month.atDay(1), month.atEndOfMonth()),
            mine, Jars.setAside(month, jars, moves, today),
            invest.filter { YearMonth.from(it.day) == month }.sumOf { it.amountCents },
            came, has
        )
    }

    fun range(
        first: YearMonth, count: Int, deadlines: List<Deadline>, incomes: List<Income>,
        spends: List<Spend> = emptyList(), jars: List<Jar> = emptyList(), moves: List<JarMove> = emptyList(),
        today: LocalDate = LocalDate.now(), invest: List<InvestFlow> = emptyList(), inflows: List<Inflow> = emptyList()
    ): List<MonthSummary> =
        (0 until count).map { month(first.plusMonths(it.toLong()), deadlines, incomes, spends, jars, moves, today, invest, inflows) }

    /**
     * Fixed costs: each recurring series once (its next unpaid occurrence),
     * with what it costs in a year, most expensive first. Subscriptions,
     * contracts, bills, instalments.
     */
    fun fixedCosts(deadlines: List<Deadline>): List<Pair<Deadline, Long>> {
        val heads = deadlines.filter { it.recurring && !it.paid && it.amountCents > 0 }
            .groupBy { Triple(it.type, it.issuer.trim().lowercase(), it.title.trim().lowercase()) }
            .values.map { series -> series.minByOrNull { it.due }!! }
        return heads.map { it to it.amountCents * 12 / it.everyMonths }.sortedByDescending { it.second }
    }

    /** How money moved in a period: cash, or card and account; out (everyday spending) and in (other income). */
    data class CashSplit(val cashOut: Long, val cardOut: Long, val cashIn: Long, val cardIn: Long) {
        val any: Boolean get() = cashOut != 0L || cashIn != 0L
    }

    fun cashSplit(spends: List<Spend>, inflows: List<Inflow> = emptyList()): CashSplit {
        var co = 0L
        var ko = 0L
        var ci = 0L
        var ki = 0L
        for (s in spends) if (s.cash) co += s.amountCents else ko += s.amountCents
        for (i in inflows) if (i.cash) ci += i.amountCents else ki += i.amountCents
        return CashSplit(co, ko, ci, ki)
    }

    /** Everyday spending of a month by category, biggest first. */
    fun byCategory(spends: List<Spend>): List<Pair<SpendCat, Long>> =
        spends.groupBy { it.cat }.map { (k, v) -> k to v.sumOf { it.amountCents } }.sortedByDescending { it.second }

    /**
     * If a new document matches a guessed future occurrence (same kind,
     * same issuer, due within 25 days), the new data should replace it
     * instead of creating a duplicate.
     */
    fun matchEstimated(deadlines: List<Deadline>, type: DocType, issuer: String, due: LocalDate): Deadline? =
        deadlines.filter {
            it.estimated && !it.paid && !it.noDue && it.type == type &&
                it.issuer.trim().equals(issuer.trim(), ignoreCase = true) &&
                Math.abs(Reminders.daysLeft(due, it.due)) <= 25
        }.minByOrNull { Math.abs(Reminders.daysLeft(due, it.due)) }
}

/*
 * Savings jars. A jar with a goal and a date suggests a monthly quota:
 * what is still missing at the start of a month, spread over the months
 * left (that month and the goal's month included). The quota counts in
 * "how much is left" like an expense, so the money is not spent twice.
 */
object Jars {

    fun saved(jar: Jar, moves: List<JarMove>): Long = moves.filter { it.jarId == jar.id }.sumOf { it.amountCents }

    private fun savedBefore(jar: Jar, moves: List<JarMove>, day: LocalDate): Long =
        moves.filter { it.jarId == jar.id && it.day.isBefore(day) }.sumOf { it.amountCents }

    /** Months from `from` to the month of `by`, both included; at least 1. */
    fun monthsLeft(from: YearMonth, by: LocalDate): Long = maxOf(1L, ChronoUnit.MONTHS.between(from, YearMonth.from(by)) + 1)

    /** Suggested amount for `month`. 0 without a goal or a date, once the goal is reached, or after the date. */
    fun quota(jar: Jar, moves: List<JarMove>, month: YearMonth): Long {
        val by = jar.byDate ?: return 0
        if (jar.closed || jar.goalCents <= 0 || month.isAfter(YearMonth.from(by))) return 0
        val missing = jar.goalCents - savedBefore(jar, moves, month.atDay(1))
        if (missing <= 0) return 0
        val n = monthsLeft(month, by)
        return (missing + n - 1) / n
    }

    /** Fraction of the goal reached, 0..1 (0 without a goal). */
    fun progress(jar: Jar, moves: List<JarMove>): Double =
        if (jar.goalCents <= 0) 0.0 else (saved(jar, moves).toDouble() / jar.goalCents).coerceIn(0.0, 1.0)

    /**
     * Money put aside in a month, over all jars. Past months: what was
     * moved. This month: for each jar at least its quota (a withdrawal
     * still leaves the quota to put aside). Later months: the quota
     * suggested now, up to each jar's date.
     */
    fun setAside(month: YearMonth, jars: List<Jar>, moves: List<JarMove>, today: LocalDate): Long {
        val now = YearMonth.from(today)
        var total = 0L
        for (j in jars) {
            val moved = moves.filter { it.jarId == j.id && YearMonth.from(it.day) == month }.sumOf { it.amountCents }
            total += when {
                month.isBefore(now) -> moved
                month == now -> {
                    val q = quota(j, moves, now)
                    if (moved >= 0) maxOf(moved, q) else moved + q
                }
                else -> {
                    val by = j.byDate
                    if (by == null || month.isAfter(YearMonth.from(by))) 0L else quota(j, moves, now)
                }
            }
        }
        return total
    }
}
