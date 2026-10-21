package app.pensy.core

import java.time.LocalDate
import java.time.YearMonth

/*
 * "Andamento": months put together. Only what really happened counts
 * here: the payslips recorded (no estimate), the expenses that exist
 * (no projected occurrence), the money really put aside or invested.
 */

/** A month, or several months added up (a year). */
data class TrendBucket(
    val first: YearMonth,
    val last: YearMonth,
    val incomeCents: Long,
    val outCents: Long,
    val spendCents: Long,
    /** Put into jars and investments (minus what was taken out). */
    val savedCents: Long,
    val months: Int
) {
    val leftCents: Long get() = incomeCents - outCents - savedCents
}

enum class TrendPeriod { SIX, TWELVE, YEAR, ALL }

object Trend {

    /** Never further back than this, whatever the data. */
    const val MAX_MONTHS = 120

    /** The first month with something in it, not after `now` (now when there is nothing). */
    fun firstMonth(
        now: YearMonth, deadlines: List<Deadline>, incomes: List<Income>, spends: List<Spend>,
        jarMoves: List<JarMove> = emptyList(), investMoves: List<InvestMove> = emptyList()
    ): YearMonth {
        var first = now
        fun see(m: YearMonth) { if (m.isBefore(first)) first = m }
        for (i in incomes) see(i.month)
        for (s in spends) see(YearMonth.from(s.day))
        for (d in deadlines) d.day?.let { see(YearMonth.from(it)) }
        for (m in jarMoves) see(YearMonth.from(m.day))
        for (m in investMoves) see(YearMonth.from(m.day))
        val limit = now.minusMonths((MAX_MONTHS - 1).toLong())
        return if (first.isBefore(limit)) limit else first
    }

    /** The months of a period, ending with `now`. */
    fun range(period: TrendPeriod, now: YearMonth, first: YearMonth): Pair<YearMonth, YearMonth> = when (period) {
        TrendPeriod.SIX -> now.minusMonths(5) to now
        TrendPeriod.TWELVE -> now.minusMonths(11) to now
        TrendPeriod.YEAR -> YearMonth.of(now.year, 1) to now
        TrendPeriod.ALL -> (if (first.isAfter(now)) now else first) to now
    }

    /** What really happened in a month. */
    fun month(
        m: YearMonth, deadlines: List<Deadline>, incomes: List<Income>, spends: List<Spend>,
        jarMoves: List<JarMove> = emptyList(), invest: List<InvestFlow> = emptyList()
    ): TrendBucket {
        val income = incomes.filter { it.month == m }.sumOf { it.amountCents }
        val due = Budget.lines(deadlines, m.atDay(1), m.atEndOfMonth()).filter { !it.projected }.sumOf { it.amountCents }
        val spent = spends.filter { YearMonth.from(it.day) == m }.sumOf { it.amountCents }
        val saved = jarMoves.filter { YearMonth.from(it.day) == m }.sumOf { it.amountCents } +
            invest.filter { !it.planned && YearMonth.from(it.day) == m }.sumOf { it.amountCents }
        return TrendBucket(m, m, income, due + spent, spent, saved, 1)
    }

    fun months(
        from: YearMonth, to: YearMonth, deadlines: List<Deadline>, incomes: List<Income>, spends: List<Spend>,
        jarMoves: List<JarMove> = emptyList(), invest: List<InvestFlow> = emptyList()
    ): List<TrendBucket> {
        val out = ArrayList<TrendBucket>()
        var m = from
        while (!m.isAfter(to)) {
            out += month(m, deadlines, incomes, spends, jarMoves, invest)
            m = m.plusMonths(1)
        }
        return out
    }

    /** Several buckets as one. */
    fun sum(buckets: List<TrendBucket>): TrendBucket? {
        if (buckets.isEmpty()) return null
        return TrendBucket(
            buckets.first().first, buckets.last().last,
            buckets.sumOf { it.incomeCents }, buckets.sumOf { it.outCents }, buckets.sumOf { it.spendCents },
            buckets.sumOf { it.savedCents }, buckets.sumOf { it.months }
        )
    }

    /** Months added up by calendar year, oldest first. */
    fun byYear(months: List<TrendBucket>): List<TrendBucket> =
        months.groupBy { it.first.year }.toSortedMap().values.mapNotNull { sum(it) }

    /** Everyday spending of the period by category, biggest first. */
    fun categories(spends: List<Spend>, from: YearMonth, to: YearMonth): List<Pair<SpendCat, Long>> =
        Budget.byCategory(spends.filter { val m = YearMonth.from(it.day); !m.isBefore(from) && !m.isAfter(to) })

    /** The regular net pay of each month (extra months such as the tredicesima left out); 0 = no payslip. */
    fun pay(incomes: List<Income>, from: YearMonth, to: YearMonth): List<Pair<YearMonth, Long>> {
        val out = ArrayList<Pair<YearMonth, Long>>()
        var m = from
        while (!m.isAfter(to)) {
            val mm = m
            out += mm to incomes.filter { it.month == mm && !it.extra }.sumOf { it.amountCents }
            m = m.plusMonths(1)
        }
        return out
    }

    /** Extra payslips of the period (tredicesima, quattordicesima, bonus). */
    fun extras(incomes: List<Income>, from: YearMonth, to: YearMonth): List<Income> =
        incomes.filter { it.extra && !it.month.isBefore(from) && !it.month.isAfter(to) }.sortedBy { it.month }

    /** Change of the regular pay from the first to the last month that has one, in percent; null with fewer than two. */
    fun payChange(pay: List<Pair<YearMonth, Long>>): Double? {
        val got = pay.filter { it.second > 0 }
        if (got.size < 2) return null
        val a = got.first().second
        val b = got.last().second
        return (b - a) * 100.0 / a
    }

    /** Days elapsed in the current month, to tell a partial month apart. */
    fun isPartial(m: YearMonth, today: LocalDate): Boolean = m == YearMonth.from(today) && today.dayOfMonth < m.lengthOfMonth()
}
