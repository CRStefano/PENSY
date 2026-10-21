package app.pensy.core

import java.time.LocalDate
import java.time.YearMonth

/*
 * The dates a car brings every year, from the rules in Italy:
 *
 *  - revisione: the first 4 years after the first registration, then
 *    every 2 years, within the month (of registration, then of the last
 *    revision);
 *  - insurance (RC auto) and road tax (bollo): once a year, on their own
 *    dates (insurance stays valid 15 days after it expires);
 *  - service (tagliando): usually once a year;
 *  - winter tyres, where an ordinance requires them: from 15 November to
 *    15 April, with a month to fit them (from 15 October) and a month to
 *    take them off (by 15 May).
 *
 * The user can change every date: these are good first guesses.
 */
object CarRules {

    /** Next revision due date (the last day of its month), not before this month. */
    fun nextRevision(registered: LocalDate, lastRevision: LocalDate?, today: LocalDate): LocalDate {
        val thisMonth = YearMonth.from(today)
        var ym = if (lastRevision != null) YearMonth.from(lastRevision).plusYears(2) else YearMonth.from(registered).plusYears(4)
        while (ym.isBefore(thisMonth)) ym = ym.plusYears(2)
        return ym.atEndOfMonth()
    }

    /** The next date from `last` every `months` months, not before today. */
    fun next(last: LocalDate, today: LocalDate, months: Int = 12): LocalDate {
        var k = 1L
        var d = last.plusMonths(months.toLong())
        while (d.isBefore(today) && k < 600) { k++; d = last.plusMonths(k * months) }
        return d
    }

    /** When winter tyres must be on: 15 November, this year or next. */
    fun winterTyres(today: LocalDate): LocalDate {
        val d = LocalDate.of(today.year, 11, 15)
        return if (today.isAfter(d)) d.plusYears(1) else d
    }

    /** When winter tyres must be off: 15 May, this year or next. */
    fun summerTyres(today: LocalDate): LocalDate {
        val d = LocalDate.of(today.year, 5, 15)
        return if (today.isAfter(d)) d.plusYears(1) else d
    }
}
