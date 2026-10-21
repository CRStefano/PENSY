package app.pensy

import android.content.Context
import app.pensy.core.Deadline
import app.pensy.core.DocType
import app.pensy.core.Reminders
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates, labels and other words shown to the user, in the phone's language. */
object Fmt {

    private fun loc(): Locale = Locale.getDefault()

    fun cap(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc()) else it.toString() }

    fun date(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", loc()))
    fun dateShort(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM", loc()))
    fun dayLong(d: LocalDate): String = cap(d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc())))
    fun month(m: YearMonth): String = cap(m.format(DateTimeFormatter.ofPattern("MMMM yyyy", loc())))
    fun monthName(m: YearMonth): String = m.format(DateTimeFormatter.ofPattern("MMMM", loc()))
    fun monthLetter(m: YearMonth): String = cap(m.format(DateTimeFormatter.ofPattern("MMM", loc()))).take(3)

    /** "oggi", "domani", "tra 5 giorni", "ieri", "5 giorni fa". */
    fun relative(ctx: Context, today: LocalDate, d: LocalDate): String {
        val n = Reminders.daysLeft(today, d)
        return when {
            n == 0L -> ctx.getString(R.string.rel_today)
            n == 1L -> ctx.getString(R.string.rel_tomorrow)
            n == -1L -> ctx.getString(R.string.rel_yesterday)
            n > 1 -> ctx.resources.getQuantityString(R.plurals.rel_in_days, n.toInt(), n.toInt())
            else -> ctx.resources.getQuantityString(R.plurals.rel_days_ago, (-n).toInt(), (-n).toInt())
        }
    }

    fun type(ctx: Context, t: DocType): String = ctx.getString(
        when (t) {
            DocType.BOLLETTA -> R.string.type_bolletta
            DocType.PAGOPA -> R.string.type_pagopa
            DocType.BOLLO_AUTO -> R.string.type_bollo
            DocType.MULTA -> R.string.type_multa
            DocType.TICKET -> R.string.type_ticket
            DocType.REFERTO -> R.string.type_referto
            DocType.RICETTA -> R.string.type_ricetta
            DocType.POLIZZA -> R.string.type_polizza
            DocType.DOCUMENTO -> R.string.type_documento
            DocType.GARANZIA -> R.string.type_garanzia
            DocType.TASSE_UNIVERSITA -> R.string.type_universita
            DocType.RATA -> R.string.type_rata
            DocType.F24 -> R.string.type_f24
            DocType.BUSTA_PAGA -> R.string.type_busta_paga
            DocType.ABBONAMENTO -> R.string.type_abbonamento
            DocType.RICEVUTA -> R.string.type_ricevuta
            DocType.ALTRO -> R.string.type_altro
        }
    )

    fun typeShort(ctx: Context, t: DocType): String = ctx.getString(
        when (t) {
            DocType.BOLLETTA -> R.string.short_bolletta
            DocType.PAGOPA -> R.string.short_pagopa
            DocType.BOLLO_AUTO -> R.string.short_bollo
            DocType.MULTA -> R.string.short_multa
            DocType.TICKET -> R.string.short_ticket
            DocType.REFERTO -> R.string.short_referto
            DocType.RICETTA -> R.string.short_ricetta
            DocType.POLIZZA -> R.string.short_polizza
            DocType.DOCUMENTO -> R.string.short_documento
            DocType.GARANZIA -> R.string.short_garanzia
            DocType.TASSE_UNIVERSITA -> R.string.short_universita
            DocType.RATA -> R.string.short_rata
            DocType.F24 -> R.string.short_f24
            DocType.BUSTA_PAGA -> R.string.short_busta_paga
            DocType.ABBONAMENTO -> R.string.short_abbonamento
            DocType.RICEVUTA -> R.string.short_ricevuta
            DocType.ALTRO -> R.string.short_altro
        }
    )

    /** Title to show: the user's own, or "<kind> <issuer>". */
    fun title(ctx: Context, d: Deadline): String = title(ctx, d.title, d.type, d.issuer)

    fun title(ctx: Context, title: String, type: DocType, issuer: String): String =
        title.ifBlank { if (issuer.isBlank()) type(ctx, type) else type(ctx, type) + " " + issuer }

    fun every(ctx: Context, months: Int): String = when (months) {
        0 -> ctx.getString(R.string.every_never)
        1 -> ctx.getString(R.string.every_month)
        12 -> ctx.getString(R.string.every_year)
        else -> ctx.getString(R.string.every_n_months, months)
    }

    val EVERY_OPTIONS = listOf(0, 1, 2, 3, 6, 12)
    val REMIND_OPTIONS = listOf(1, 3, 7, 10, 15, 30, 60, 90)

    /** First reminder by kind: identity documents 90 days before, warranties and contracts 30, a prescription 7. */
    fun defaultRemind(t: DocType, user: Int): Int = when (t) {
        DocType.DOCUMENTO -> 90
        DocType.GARANZIA, DocType.POLIZZA, DocType.ABBONAMENTO -> 30
        DocType.RICETTA -> 7
        else -> user
    }

    /** Title of a reminder with nothing to pay: "Rinnovo: Carta d'identità", "Ricetta da usare: …". */
    fun reminderTitle(ctx: Context, t: DocType, base: String): String = ctx.getString(
        when (t) {
            DocType.RICETTA -> R.string.rem_rx
            DocType.DOCUMENTO -> R.string.rem_renew
            DocType.GARANZIA -> R.string.rem_warranty
            DocType.ABBONAMENTO -> R.string.rem_cancel
            else -> R.string.followup_title
        }, base
    )

    /** NRE in three groups of five: "0100A 40056 73219". */
    fun nre(s: String): String = s.chunked(5).joinToString(" ")
}
