package app.pensy

import android.view.Gravity
import android.view.View
import app.pensy.core.Budget
import app.pensy.core.Csv
import app.pensy.core.Money
import app.pensy.core.Tax
import app.pensy.core.TaxCat
import java.time.LocalDate

/*
 * "Spese per il 730": the expenses of a year that may lower the income
 * tax, by kind, with a rough estimate, for the person in use and the
 * people who count in their balance (children can be deducted by the
 * parent). A CSV for the CAF. Also: the CSV exports of the balance.
 */

fun MainActivity.taxLabel(t: TaxCat): String = getString(
    when (t) {
        TaxCat.SANITARIE -> R.string.tax_sanitarie
        TaxCat.VETERINARIE -> R.string.tax_veterinarie
        TaxCat.ISTRUZIONE -> R.string.tax_istruzione
        TaxCat.UNIVERSITA -> R.string.tax_universita
        TaxCat.TRASPORTI -> R.string.tax_trasporti
        TaxCat.SPORT -> R.string.tax_sport
        TaxCat.ALTRO -> R.string.tax_altro
    }
)

private fun MainActivity.taxRule(t: TaxCat): String = getString(
    when (t) {
        TaxCat.SANITARIE -> R.string.tax_rule_sanitarie
        TaxCat.VETERINARIE -> R.string.tax_rule_veterinarie
        TaxCat.ISTRUZIONE -> R.string.tax_rule_istruzione
        TaxCat.UNIVERSITA -> R.string.tax_rule_universita
        TaxCat.TRASPORTI -> R.string.tax_rule_trasporti
        TaxCat.SPORT -> R.string.tax_rule_sport
        TaxCat.ALTRO -> R.string.tax_rule_altro
    }
)

class TaxScreen(act: MainActivity) : Screen(act) {

    /** Expenses are paid in a year and declared the next: the default is last year until spring is over. */
    private var year: Int = LocalDate.now().let { if (it.monthValue <= 9) it.year - 1 else it.year }

    override fun build(): View {
        val c = act
        val names = c.db.profiles().associateBy({ it.id }, { c.profileLabel(it) })
        val items = Tax.items(year, c.db.budgetDeadlines(), c.db.spends())
        val lines = Tax.summary(items)
        val body = c.vbox()

        val head = c.hbox()
        head.add(c.iconButton(R.drawable.ic_chevron_left, R.string.prev_year) { year--; refresh() }, w = WRAP)
        head.add(c.text(c.getString(R.string.tax_year, year), 18f, face = BOLD).apply { gravity = Gravity.CENTER }, w = 0, weight = 1f)
        head.add(c.iconButton(R.drawable.ic_chevron_right, R.string.next_year) { year++; refresh() }, w = WRAP)
        body.add(head, top = 4)
        body.add(c.text(c.getString(R.string.tax_declared_in, year + 1), 13f, c.col(R.color.ink2)).apply { gravity = Gravity.CENTER }, top = 2)

        val total = c.card(18)
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.tax_total), Money.format(lines.sumOf { it.totalCents }), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.tax_estimate), Money.format(lines.sumOf { it.estimateCents }), c.col(R.color.ink2), c.col(R.color.good)), w = 0, weight = 1f)
        total.add(row)
        total.add(c.text(c.getString(R.string.tax_estimate_hint), 13f, c.col(R.color.ink2)), top = 10)
        body.add(total, top = 14)

        if (lines.isEmpty()) {
            body.add(c.text(c.getString(R.string.tax_empty), 14f, c.col(R.color.ink2)), top = 16, start = 4)
        }
        for (l in lines) {
            body.add(c.sectionText(c.taxLabel(l.cat)), top = 24)
            val card = c.card(16)
            val r = c.hbox()
            r.add(c.stat(c.getString(R.string.tax_spent), Money.format(l.totalCents), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
            if (l.cat != TaxCat.ALTRO) r.add(c.stat(c.getString(R.string.tax_back), "≈ " + Money.format(l.estimateCents), c.col(R.color.ink2), c.col(R.color.good)), w = 0, weight = 1f)
            card.add(r)
            card.add(c.text(c.taxRule(l.cat), 13f, c.col(R.color.ink2)), top = 8)
            if (l.cashLeftOut > 0) card.add(c.text(c.resources.getQuantityString(R.plurals.tax_cash_left, l.cashLeftOut, l.cashLeftOut), 13f, c.col(R.color.warn)), top = 6)
            body.add(card, top = 8)
            val list = c.listCard()
            for (x in l.items) {
                val rr = c.hbox().apply {
                    setPadding(c.dp(16), c.dp(11), c.dp(16), c.dp(11))
                    background = c.ripple(null, 0)
                    setOnClickListener { if (x.source == "spend") c.push(SpendScreen(c, x.id)) else c.push(DeadlineScreen(c, x.id)) }
                }
                val mid = c.vbox()
                mid.add(c.text(x.label.ifBlank { c.taxLabel(x.cat) }, 15f, face = MEDIUM, lines = 1))
                var sub = Fmt.date(x.day)
                if (names.size > 1) names[x.profileId]?.let { n -> sub += " · $n" }
                if (x.cash) sub += " · " + c.getString(R.string.tax_cash_short)
                mid.add(c.text(sub, 13f, c.col(R.color.ink2), lines = 1), top = 1)
                rr.add(mid, w = 0, weight = 1f)
                rr.add(c.text(Money.format(x.amountCents), 15f, face = MEDIUM), w = WRAP)
                list.add(rr)
            }
            body.add(list, top = 8)
        }
        body.add(c.text(c.getString(R.string.tax_disclaimer), 12.5f, c.col(R.color.ink3)), top = 20, start = 4)

        val actions = listOf(Triple(R.drawable.ic_share, R.string.export_csv) { exportTax(items, names) })
        return page(c.getString(R.string.tax_title), back = true, actions = actions, body = body)
    }

    private fun exportTax(items: List<app.pensy.core.TaxItem>, names: Map<Long, String>) {
        val c = act
        if (items.isEmpty()) { c.toast(R.string.tax_empty_short); return }
        val rows = items.sortedBy { it.day }.map {
            listOf(it.day.toString(), names[it.profileId] ?: "", c.taxLabel(it.cat), it.label, Csv.money(it.amountCents),
                c.getString(if (it.cash) R.string.tax_cash_short else R.string.tax_traceable))
        }
        val csv = Csv.table(listOf(c.getString(R.string.csv_date), c.getString(R.string.csv_person), c.getString(R.string.csv_kind),
            c.getString(R.string.csv_what), c.getString(R.string.csv_amount), c.getString(R.string.csv_payment)), rows)
        val f = Share.newFile(c, "PENSY 730 $year.csv")
        f.writeText(csv)
        Share.send(c, f, "text/csv", c.getString(R.string.tax_title))
    }
}

/** "Esporta": deadlines and payments, everyday spending, 730 — as CSV for a spreadsheet. */
fun MainActivity.exportSheet() {
    Dialogs.sheet(this, getString(R.string.export_title), listOf(
        Triple(R.drawable.ic_calendar, getString(R.string.export_deadlines)) { exportDeadlines() },
        Triple(R.drawable.ic_receipt, getString(R.string.export_spends)) { exportSpends() },
        Triple(R.drawable.ic_wallet, getString(R.string.export_incomes)) { exportIncomes() },
        Triple(R.drawable.ic_bank, getString(R.string.tax_title)) { push(TaxScreen(this)) }
    ))
}

private fun MainActivity.exportDeadlines() {
    val names = db.profiles().associateBy({ it.id }, { profileLabel(it) })
    val folders = db.folders()
    val rows = db.budgetDeadlines().sortedBy { it.due }.map { d ->
        listOf(
            d.due.toString(), names[d.profileId] ?: "", Fmt.type(this, d.type), Fmt.title(this, d), d.issuer,
            Csv.money(d.amountCents), getString(if (d.paid) R.string.status_paid else R.string.csv_to_pay),
            d.paidOn?.toString() ?: "", Fmt.every(this, d.everyMonths),
            folders.firstOrNull { it.id == d.folderId }?.name ?: ""
        )
    }
    if (rows.isEmpty()) { toast(R.string.export_nothing); return }
    val csv = Csv.table(listOf(getString(R.string.csv_due), getString(R.string.csv_person), getString(R.string.csv_kind),
        getString(R.string.csv_what), getString(R.string.field_issuer), getString(R.string.csv_amount), getString(R.string.csv_status),
        getString(R.string.csv_paid_on), getString(R.string.field_every), getString(R.string.field_folder)), rows)
    val f = Share.newFile(this, "PENSY " + getString(R.string.export_deadlines) + " " + LocalDate.now() + ".csv")
    f.writeText(csv)
    Share.send(this, f, "text/csv", getString(R.string.export_deadlines))
}

private fun MainActivity.exportSpends() {
    val names = db.profiles().associateBy({ it.id }, { profileLabel(it) })
    val rows = db.spends().sortedBy { it.day }.map { s ->
        listOf(s.day.toString(), names[s.profileId] ?: "", spendLabel(s.cat), s.note, Csv.money(s.amountCents),
            getString(if (s.cash) R.string.pay_cash else R.string.pay_card), TaxCat.of(s.tax)?.let { taxLabel(it) } ?: "")
    }
    if (rows.isEmpty()) { toast(R.string.export_nothing); return }
    val csv = Csv.table(listOf(getString(R.string.csv_date), getString(R.string.csv_person), getString(R.string.spend_cat),
        getString(R.string.field_note), getString(R.string.csv_amount), getString(R.string.csv_how), getString(R.string.tax_field)), rows)
    val f = Share.newFile(this, "PENSY " + getString(R.string.export_spends) + " " + LocalDate.now() + ".csv")
    f.writeText(csv)
    Share.send(this, f, "text/csv", getString(R.string.export_spends))
}

/** Payslips (by month) and the other money that came in (by day), oldest first. */
private fun MainActivity.exportIncomes() {
    val rows = ArrayList<Pair<String, List<String>>>()
    for (i in db.incomes()) rows += i.month.toString() to listOf(i.month.toString(),
        getString(if (i.extra) R.string.payslip_extra else R.string.payslip_regular), i.note, Csv.money(i.amountCents), "")
    for (x in db.inflows()) rows += x.day.toString() to listOf(x.day.toString(), inflowLabel(x.cat), x.note, Csv.money(x.amountCents),
        getString(if (x.cash) R.string.pay_cash else R.string.pay_card))
    if (rows.isEmpty()) { toast(R.string.export_nothing); return }
    rows.sortBy { it.first }
    val csv = Csv.table(listOf(getString(R.string.csv_date), getString(R.string.csv_kind), getString(R.string.field_note),
        getString(R.string.csv_amount), getString(R.string.csv_how)), rows.map { it.second })
    val f = Share.newFile(this, "PENSY " + getString(R.string.export_incomes) + " " + LocalDate.now() + ".csv")
    f.writeText(csv)
    Share.send(this, f, "text/csv", getString(R.string.export_incomes))
}

/** "Spese fisse": every recurring cost once, what it costs in a year. */
fun MainActivity.fixedCostsCard(): View? {
    val fixed = Budget.fixedCosts(db.budgetDeadlines())
    if (fixed.isEmpty()) return null
    val box = card(16)
    val head = hbox()
    head.add(text(getString(R.string.fixed_title), 16f, face = MEDIUM), w = 0, weight = 1f)
    head.add(text(getString(R.string.fixed_year, Money.format(fixed.sumOf { it.second })), 14f, col(R.color.ink2), MEDIUM), w = WRAP)
    box.add(head)
    box.add(text(getString(R.string.fixed_month, Money.format(fixed.sumOf { it.second } / 12)), 13f, col(R.color.ink2)), top = 2)
    for ((d, yearly) in fixed.take(8)) {
        val r = hbox().apply {
            setPadding(0, dp(8), 0, dp(4))
            background = ripple(null, 8)
            setOnClickListener { push(DeadlineScreen(this@fixedCostsCard, d.id)) }
        }
        r.add(typeBadge(d.type), w = dp(34), h = dp(34))
        val mid = vbox()
        mid.add(text(Fmt.title(this, d), 14.5f, face = MEDIUM, lines = 1))
        mid.add(text(Money.format(d.amountCents) + " · " + Fmt.every(this, d.everyMonths).lowercase(), 12.5f, col(R.color.ink2), lines = 1))
        r.add(mid, w = 0, weight = 1f, start = 12)
        r.add(text(getString(R.string.fixed_per_year, Money.formatShort(yearly)), 13.5f, col(R.color.ink), MEDIUM), w = WRAP)
        box.add(r, top = 4)
    }
    if (fixed.size > 8) box.add(text(getString(R.string.fixed_more, fixed.size - 8), 13f, col(R.color.ink2)), top = 6)
    return box
}
