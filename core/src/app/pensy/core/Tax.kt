package app.pensy.core

import java.time.LocalDate

/*
 * Expenses that may lower the income tax (730), year by year.
 *
 * A rough, honest estimate: the main 19% deductions with their usual
 * limits, per person where the law counts per person (each profile is a
 * person). It is a help to collect receipts and talk with the CAF, not a
 * tax return: rules change, incomes over 75.000 € have further limits,
 * and medical expenses are usually already in the pre-filled 730.
 *
 *   medical        above a 129,11 € franchise, no ceiling
 *   veterinary     up to 550 €, above the same franchise
 *   school         up to 800 € per student
 *   university     no ceiling counted (private universities have one)
 *   public transport pass   up to 250 € per person
 *   sport (children 5-18)   up to 210 € per child
 *
 * Most 19% deductions need a traceable payment (card, transfer): cash
 * expenses are listed but left out of the estimate, except medical ones
 * (medicines and public health services can be paid in cash).
 */
enum class TaxCat(val code: String, val capCents: Long?, val franchiseCents: Long, val perPerson: Boolean, val cashOk: Boolean) {
    SANITARIE("sanitarie", null, 12911, false, true),
    VETERINARIE("veterinarie", 55000, 12911, false, false),
    ISTRUZIONE("istruzione", 80000, 0, true, false),
    UNIVERSITA("universita", null, 0, true, false),
    TRASPORTI("trasporti", 25000, 0, true, false),
    SPORT("sport", 21000, 0, true, false),
    ALTRO("altro", null, 0, false, false);

    companion object {
        fun of(code: String?): TaxCat? = if (code.isNullOrEmpty()) null else entries.firstOrNull { it.code == code }
    }
}

/** One expense that counts for the 730. `source`: "spend" or "deadline". */
data class TaxItem(
    val profileId: Long,
    val cat: TaxCat,
    val day: LocalDate,
    val amountCents: Long,
    val label: String,
    val cash: Boolean,
    val source: String,
    val id: Long
)

data class TaxLine(
    val cat: TaxCat,
    val items: List<TaxItem>,
    val totalCents: Long,
    /** What the 19% applies to, after ceilings and franchise (0 for ALTRO). */
    val baseCents: Long,
    val estimateCents: Long,
    /** Items paid in cash left out of the estimate. */
    val cashLeftOut: Int
)

object Tax {

    const val RATE_PERCENT = 19

    /** Deductible kind suggested for an everyday expense category. */
    fun suggest(cat: SpendCat): TaxCat? = when (cat) {
        SpendCat.SALUTE -> TaxCat.SANITARIE
        else -> null
    }

    /** Deductible kind of a paid deadline, from its document kind. */
    fun ofDeadline(d: Deadline): TaxCat? = when (d.type) {
        DocType.TICKET -> TaxCat.SANITARIE
        DocType.TASSE_UNIVERSITA -> TaxCat.UNIVERSITA
        else -> null
    }

    /** Everything of `year` that counts: spends marked for the 730, paid tickets and university fees. */
    fun items(year: Int, deadlines: List<Deadline>, spends: List<Spend>): List<TaxItem> {
        val out = ArrayList<TaxItem>()
        for (s in spends) {
            val cat = TaxCat.of(s.tax) ?: continue
            if (s.day.year != year) continue
            out += TaxItem(s.profileId, cat, s.day, s.amountCents, s.note, s.cash, "spend", s.id)
        }
        for (d in deadlines) {
            if (!d.paid || d.amountCents <= 0) continue
            val cat = ofDeadline(d) ?: continue
            val day = d.paidOn ?: d.due
            if (day.year != year) continue
            out += TaxItem(d.profileId, cat, day, d.amountCents, d.title.ifBlank { d.issuer }, false, "deadline", d.id)
        }
        out.sortByDescending { it.day }
        return out
    }

    /** Per kind: total, base after limits, 19% estimate. Kinds with nothing are left out. */
    fun summary(items: List<TaxItem>): List<TaxLine> {
        val out = ArrayList<TaxLine>()
        for (cat in TaxCat.entries) {
            val mine = items.filter { it.cat == cat }
            if (mine.isEmpty()) continue
            val total = mine.sumOf { it.amountCents }
            val counted = mine.filter { cat.cashOk || !it.cash }
            val base = when {
                cat == TaxCat.ALTRO -> 0L
                cat.perPerson -> counted.groupBy { it.profileId }.values.sumOf { list ->
                    val t = list.sumOf { it.amountCents }
                    cat.capCents?.let { minOf(t, it) } ?: t
                }
                else -> {
                    val t = counted.sumOf { it.amountCents }
                    val capped = cat.capCents?.let { minOf(t, it) } ?: t
                    maxOf(0L, capped - cat.franchiseCents)
                }
            }
            out += TaxLine(cat, mine, total, base, base * RATE_PERCENT / 100, mine.size - counted.size)
        }
        return out
    }
}
