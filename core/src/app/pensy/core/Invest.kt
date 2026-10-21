package app.pensy.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/*
 * Investments, in the spirit of an ETF portfolio tracker, but offline:
 *
 *   invested  = money put in minus money taken out
 *   value     = from the latest update the user typed in:
 *                 a unit price  -> units owned x price (when all units are known)
 *                 a total value -> that value + what was put in after it
 *               without any update: the money invested
 *   gain      = value - invested
 *
 * PENSY never downloads prices: the user copies them from the bank or
 * broker app when they like. Projections compound the expected yearly
 * return month by month and add the plan's contributions.
 */

/** Where an investment stands on a day. */
data class Position(
    val investedCents: Long,
    val valueCents: Long,
    /** Units owned, when every movement has them. */
    val units: Double?,
    /** Day of the update the value comes from; null = no update, value = invested. */
    val updated: LocalDate?
) {
    val gainCents: Long get() = valueCents - investedCents
    /** Gain in percent of what was invested; 0 when nothing is invested. */
    val gainPct: Double get() = if (investedCents <= 0) 0.0 else gainCents * 100.0 / investedCents
}

/** One point of a chart over time. */
data class InvestPoint(val month: YearMonth, val investedCents: Long, val valueCents: Long)

/** One year of a projection: year 0 is today. */
data class ProjPoint(val year: Int, val investedCents: Long, val valueCents: Long) {
    val gainCents: Long get() = valueCents - investedCents
}

/** Money going into (or out of) investments on a day, for the monthly balance. */
data class InvestFlow(val day: LocalDate, val amountCents: Long, val planned: Boolean)

object Invest {

    /** Suggested yearly return for a kind, only to fill the field (the user decides). */
    fun suggestedRate(kind: InvestKind): Double = when (kind) {
        InvestKind.ETF, InvestKind.AZIONI -> 6.0
        InvestKind.OBBLIGAZIONI -> 3.0
        InvestKind.FONDO, InvestKind.PENSIONE -> 4.0
        InvestKind.CONTO -> 3.0
        InvestKind.BUONI -> 2.5
        InvestKind.CRYPTO, InvestKind.ALTRO -> 0.0
    }

    private fun mine(inv: Investment, moves: List<InvestMove>) = moves.filter { it.investmentId == inv.id }

    /** Money in minus money out, up to `day` included (null = all). */
    fun invested(moves: List<InvestMove>, day: LocalDate? = null): Long =
        moves.filter { day == null || !it.day.isAfter(day) }.sumOf { it.amountCents }

    /** Units owned up to `day`; null when any movement has no units. */
    fun units(moves: List<InvestMove>, day: LocalDate? = null): Double? {
        var u = 0.0
        for (m in moves) {
            if (day != null && m.day.isAfter(day)) continue
            u += m.units ?: return null
        }
        return u
    }

    /** The latest update on or before `day`. */
    fun lastValue(inv: Investment, values: List<InvestValue>, day: LocalDate): InvestValue? =
        latest(values.filter { it.investmentId == inv.id && !it.day.isAfter(day) && (it.price != null || it.valueCents != null) })

    /** The newest update (the later one typed in, on the same day). */
    private fun latest(vs: List<InvestValue>): InvestValue? {
        var best: InvestValue? = null
        for (v in vs) {
            val b = best
            if (b == null || v.day.isAfter(b.day) || (v.day == b.day && v.id > b.id)) best = v
        }
        return best
    }

    /** Where the investment stands on `day`. */
    fun position(inv: Investment, moves: List<InvestMove>, values: List<InvestValue>, day: LocalDate): Position {
        val ms = mine(inv, moves)
        val invested = invested(ms, day)
        val units = units(ms, day)
        // The newest update that can be used: a unit price needs every unit known.
        var snap: InvestValue? = null
        for (v in values) {
            if (v.investmentId != inv.id || v.day.isAfter(day)) continue
            val usable = v.valueCents != null || (v.price != null && units != null)
            if (!usable) continue
            val b = snap
            if (b == null || v.day.isAfter(b.day) || (v.day == b.day && v.id > b.id)) snap = v
        }
        val s = snap ?: return Position(invested, invested, units, null)
        val total = s.valueCents
        return if (total != null) Position(invested, total + (invested - invested(ms, s.day)), units, s.day)
        else Position(invested, Math.round(units!! * s.price!! * 100), units, s.day)
    }

    /** All open investments together. */
    fun total(invs: List<Investment>, moves: List<InvestMove>, values: List<InvestValue>, day: LocalDate): Position {
        var inv = 0L
        var value = 0L
        var oldest: LocalDate? = null
        for (i in invs) {
            if (i.closed) continue
            val p = position(i, moves, values, day)
            inv += p.investedCents
            value += p.valueCents
            val u = p.updated
            if (u != null && (oldest == null || u.isBefore(oldest))) oldest = u
        }
        return Position(inv, value, null, oldest)
    }

    /** Month by month, from the first movement to `today`: invested and value at the end of each month. */
    fun history(invs: List<Investment>, moves: List<InvestMove>, values: List<InvestValue>, today: LocalDate): List<InvestPoint> {
        val ids = invs.map { it.id }.toSet()
        val first = moves.filter { it.investmentId in ids }.minOfOrNull { it.day } ?: return emptyList()
        val out = ArrayList<InvestPoint>()
        var m = YearMonth.from(first)
        val now = YearMonth.from(today)
        while (!m.isAfter(now)) {
            val day = if (m == now) today else m.atEndOfMonth()
            var inv = 0L
            var value = 0L
            for (i in invs) {
                val p = position(i, moves, values, day)
                inv += p.investedCents
                value += p.valueCents
            }
            out += InvestPoint(m, inv, value)
            m = m.plusMonths(1)
        }
        return out
    }

    /** The plan's dates between two days (both included). */
    fun planDates(inv: Investment, from: LocalDate, to: LocalDate): List<LocalDate> {
        val start = inv.planFrom ?: return emptyList()
        if (!inv.hasPlan || to.isBefore(from)) return emptyList()
        val every = maxOf(1, inv.planEvery).toLong()
        val out = ArrayList<LocalDate>()
        var k = 0L
        while (k < 2400) {
            val ym = YearMonth.from(start).plusMonths(k * every)
            val d = ym.atDay(minOf(maxOf(1, inv.planDay), ym.lengthOfMonth()))
            k++
            if (d.isBefore(start)) continue
            val until = inv.planUntil
            if (until != null && d.isAfter(until)) break
            if (d.isAfter(to)) break
            if (!d.isBefore(from)) out += d
        }
        return out
    }

    /** Plan dates up to `today` not recorded yet. */
    fun planDue(inv: Investment, today: LocalDate): List<LocalDate> {
        val start = inv.planFrom ?: return emptyList()
        val from = inv.planLast?.plusDays(1) ?: start
        return planDates(inv, from, today)
    }

    /**
     * Money into investments day by day between two dates, for the balance:
     * what was recorded, and the plan's dates still to come after `today`.
     */
    fun flows(invs: List<Investment>, moves: List<InvestMove>, from: LocalDate, to: LocalDate, today: LocalDate): List<InvestFlow> {
        val ids = invs.map { it.id }.toSet()
        val out = ArrayList<InvestFlow>()
        for (m in moves) if (m.investmentId in ids && !m.day.isBefore(from) && !m.day.isAfter(to)) out += InvestFlow(m.day, m.amountCents, false)
        for (i in invs) {
            if (!i.hasPlan) continue
            val start = maxOf(from, today.plusDays(1))
            for (d in planDates(i, start, to)) out += InvestFlow(d, i.planCents, true)
        }
        return out
    }

    private fun monthlyRate(ratePct: Double): Double = Math.pow(1 + ratePct / 100.0, 1.0 / 12) - 1

    /**
     * Year by year for `years` years: the value grows by the yearly rate,
     * compounded monthly, and each month adds `contribution(k)` (k = 1 is
     * next month) at its end.
     */
    fun project(value0: Long, invested0: Long, ratePct: Double, years: Int, contribution: (Int) -> Long): List<ProjPoint> {
        val m = monthlyRate(ratePct)
        var v = value0.toDouble()
        var inv = invested0
        val out = ArrayList<ProjPoint>()
        out += ProjPoint(0, inv, value0)
        for (k in 1..years * 12) {
            v *= (1 + m)
            val c = contribution(k)
            v += c
            inv += c
            if (k % 12 == 0) out += ProjPoint(k / 12, inv, Math.round(v))
        }
        return out
    }

    /** One investment from today, with its plan and its expected return. */
    fun projection(inv: Investment, moves: List<InvestMove>, values: List<InvestValue>, today: LocalDate, years: Int): List<ProjPoint> {
        val p = position(inv, moves, values, today)
        val contrib = LongArray(years * 12 + 1)
        if (inv.hasPlan) {
            val now = YearMonth.from(today)
            for (d in planDates(inv, today.plusDays(1), today.plusYears(years.toLong()))) {
                val k = ChronoUnit.MONTHS.between(now, YearMonth.from(d)).toInt().coerceAtLeast(1)
                if (k <= years * 12) contrib[k] += inv.planCents
            }
        }
        return project(p.valueCents, p.investedCents, inv.ratePct, years) { k -> contrib[k] }
    }

    /** All open investments, each with its own rate and plan, added year by year. */
    fun projectionAll(invs: List<Investment>, moves: List<InvestMove>, values: List<InvestValue>, today: LocalDate, years: Int): List<ProjPoint> {
        val inv = LongArray(years + 1)
        val value = LongArray(years + 1)
        for (i in invs) {
            if (i.closed) continue
            for (pt in projection(i, moves, values, today, years)) {
                inv[pt.year] += pt.investedCents
                value[pt.year] += pt.valueCents
            }
        }
        return (0..years).map { ProjPoint(it, inv[it], value[it]) }
    }

    /** The accumulation calculator: a starting amount, so much every month, a yearly return. */
    fun calculator(startCents: Long, monthlyCents: Long, ratePct: Double, years: Int): List<ProjPoint> =
        project(startCents, startCents, ratePct, years) { monthlyCents }

    /** Units for an amount at a price, rounded to 6 decimals; null without a price. */
    fun unitsFor(amountCents: Long, price: Double?): Double? {
        if (price == null || price <= 0) return null
        return Math.round(amountCents / 100.0 / price * 1_000_000) / 1_000_000.0
    }

    /** The unit price of the latest update on or before `day`, if it was a price. */
    fun lastPrice(inv: Investment, values: List<InvestValue>, day: LocalDate): Double? =
        latest(values.filter { it.investmentId == inv.id && !it.day.isAfter(day) && it.price != null })?.price
}
