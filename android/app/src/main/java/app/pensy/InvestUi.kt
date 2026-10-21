package app.pensy

import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.pensy.core.Invest
import app.pensy.core.InvestKind
import app.pensy.core.InvestMove
import app.pensy.core.InvestValue
import app.pensy.core.Investment
import app.pensy.core.Jars
import app.pensy.core.Money
import app.pensy.core.Position
import app.pensy.core.ProjPoint
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.YearMonth

/*
 * "Risparmi e investimenti": the jars and the investments together.
 * An investment has a name, the money put in (with the units, when
 * known), a plan that records itself, an expected yearly return, and the
 * values the user copies from the bank or broker app: PENSY never goes
 * online for prices. Charts: how it went, and where it could be in 5 to
 * 30 years. A calculator tries a monthly amount before starting.
 */

fun investColor(k: InvestKind): Int = when (k) {
    InvestKind.ETF -> 0xFF4F46E5.toInt()
    InvestKind.AZIONI -> 0xFF7C3AED.toInt()
    InvestKind.OBBLIGAZIONI -> 0xFF0369A1.toInt()
    InvestKind.FONDO -> 0xFF0E8FA8.toInt()
    InvestKind.PENSIONE -> 0xFF16A34A.toInt()
    InvestKind.CONTO -> 0xFF0F766E.toInt()
    InvestKind.BUONI -> 0xFFB7791F.toInt()
    InvestKind.CRYPTO -> 0xFFEA580C.toInt()
    InvestKind.ALTRO -> 0xFF78716C.toInt()
}

fun investIcon(k: InvestKind): Int = when (k) {
    InvestKind.ETF, InvestKind.AZIONI, InvestKind.FONDO -> R.drawable.ic_trending
    InvestKind.OBBLIGAZIONI, InvestKind.CONTO, InvestKind.BUONI -> R.drawable.ic_bank
    InvestKind.PENSIONE -> R.drawable.ic_jar
    InvestKind.CRYPTO -> R.drawable.ic_star
    InvestKind.ALTRO -> R.drawable.ic_chart
}

fun MainActivity.investKindLabel(k: InvestKind): String = getString(
    when (k) {
        InvestKind.ETF -> R.string.inv_kind_etf
        InvestKind.AZIONI -> R.string.inv_kind_azioni
        InvestKind.OBBLIGAZIONI -> R.string.inv_kind_obbligazioni
        InvestKind.FONDO -> R.string.inv_kind_fondo
        InvestKind.PENSIONE -> R.string.inv_kind_pensione
        InvestKind.CONTO -> R.string.inv_kind_conto
        InvestKind.BUONI -> R.string.inv_kind_buoni
        InvestKind.CRYPTO -> R.string.inv_kind_crypto
        InvestKind.ALTRO -> R.string.inv_kind_altro
    }
)

/** Numbers typed by the user and shown back: units, prices, percentages. */
object Num {
    /** "1.234,56", "1234.56", "0,5" → a number; null when it is not one. */
    fun parse(s: String): Double? {
        var t = s.trim().replace("€", "").replace("%", "").replace(" ", "")
        if (t.isEmpty()) return null
        if (t.contains(',') && t.contains('.')) t = t.replace(".", "").replace(',', '.')
        else t = t.replace(',', '.')
        return t.toDoubleOrNull()
    }

    private fun fmt(pattern: String, x: Double): String = DecimalFormat(pattern, DecimalFormatSymbols.getInstance()).format(x)

    fun units(x: Double): String = fmt("#,##0.####", x)
    fun price(x: Double): String = fmt("#,##0.00##", x) + " €"
    fun input(x: Double): String = fmt("0.######", x)
    fun rate(x: Double): String = fmt("0.##", x) + "%"

    /** "+5,2%" or "−1,0%". */
    fun pct(x: Double): String = (if (x >= 0) "+" else "−") + fmt("0.0", Math.abs(x)) + "%"

    /** "+1.234,00 €" or "−56,00 €". */
    fun signed(cents: Long): String = (if (cents >= 0) "+" else "−") + Money.format(Math.abs(cents))
}

private fun MainActivity.gainColor(cents: Long): Int = col(if (cents >= 0) R.color.good else R.color.bad)

/** "Mar 26": short enough to sit under a bar. */
fun monthTick(m: YearMonth): String = Fmt.monthLetter(m) + " " + (m.year % 100).toString().padStart(2, '0')

/** A text field for money, units or percentages. */
fun MainActivity.numberField(hint: String, value: String, onChange: (String) -> Unit): EditText =
    field(hint, value, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, onChange = onChange).apply {
        keyListener = DigitsKeyListener.getInstance("0123456789,.")
    }

/** One investment in a list: icon, name, value and gain. */
fun MainActivity.investRow(inv: Investment, pos: Position): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        background = ripple(null, 0)
        setOnClickListener { push(InvestmentScreen(this@investRow, inv.id)) }
    }
    row.add(roundIcon(investIcon(inv.kind), investColor(inv.kind), 42), w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(inv.name, 16f, face = MEDIUM, lines = 1))
    var sub = investKindLabel(inv.kind)
    if (inv.closed) sub += " · " + getString(R.string.inv_closed)
    else if (inv.hasPlan) sub += " · " + getString(R.string.inv_plan_short, Money.formatShort(inv.planCents))
    mid.add(text(sub, 13f, col(R.color.ink2), lines = 1), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    val right = vbox().apply { gravity = Gravity.END }
    right.add(text(Money.format(pos.valueCents), 15f, col(R.color.ink), MEDIUM).apply { gravity = Gravity.END })
    if (pos.investedCents > 0) right.add(text(Num.pct(pos.gainPct), 12.5f, gainColor(pos.gainCents), MEDIUM).apply { gravity = Gravity.END }, top = 1)
    row.add(right, w = WRAP, start = 8)
    return row
}

/** Invested and value over time, as two lines. */
fun MainActivity.historyChart(points: List<app.pensy.core.InvestPoint>): View {
    val box = card(16)
    val chart = ChartView(this).apply { heightDp = 180 }
    chart.set(points.map { monthTick(it.month) }, listOf(
        ChartView.Series(points.map { it.investedCents }, col(R.color.ink3), dashed = true),
        ChartView.Series(points.map { it.valueCents }, col(R.color.accent), fill = true)
    ))
    val info = text("", 13f, col(R.color.ink2))
    chart.onSelect = { i ->
        val pt = points[i]
        info.text = getString(R.string.inv_point, Fmt.month(pt.month), Money.format(pt.investedCents), Money.format(pt.valueCents))
    }
    box.add(chart)
    box.add(legend(listOf(col(R.color.ink3) to getString(R.string.inv_invested), col(R.color.accent) to getString(R.string.inv_value))), top = 8)
    box.add(info, top = 6)
    return box
}

/** A projection: invested and estimated value, year by year, with the totals of the last year. */
fun MainActivity.projectionCard(points: List<ProjPoint>, note: String): View {
    val box = card(16)
    val last = points.last()
    val chart = ChartView(this).apply { heightDp = 180 }
    chart.set(points.map { if (it.year == 0) getString(R.string.inv_today_short) else "+" + it.year }, listOf(
        ChartView.Series(points.map { it.investedCents }, col(R.color.ink3), dashed = true),
        ChartView.Series(points.map { it.valueCents }, col(R.color.good), fill = true)
    ))
    val info = text("", 13f, col(R.color.ink2))
    chart.onSelect = { i ->
        val pt = points[i]
        info.text = getString(R.string.inv_proj_point, pt.year, Money.format(pt.investedCents), Money.format(pt.valueCents))
    }
    val row = hbox()
    row.add(stat(getString(R.string.inv_invested), Money.format(last.investedCents), col(R.color.ink2), col(R.color.ink)), w = 0, weight = 1f)
    row.add(stat(getString(R.string.inv_estimated), Money.format(last.valueCents), col(R.color.ink2), col(R.color.good)), w = 0, weight = 1f)
    row.add(stat(getString(R.string.inv_interest), Num.signed(last.gainCents), col(R.color.ink2), gainColor(last.gainCents)), w = 0, weight = 1f)
    box.add(row)
    box.add(chart, top = 12)
    box.add(legend(listOf(col(R.color.ink3) to getString(R.string.inv_invested), col(R.color.good) to getString(R.string.inv_estimated))), top = 8)
    box.add(info, top = 6)
    box.add(text(note, 12.5f, col(R.color.ink3)), top = 6)
    return box
}

private val YEAR_OPTIONS = listOf(5, 10, 20, 30)

/** "Risparmi e investimenti": everything put aside, the investments, the jars. */
class InvestScreen(act: MainActivity) : Screen(act) {

    private var years = 10

    override fun build(): View {
        val c = act
        val today = LocalDate.now()
        val invs = c.db.investments()
        val moves = c.db.investMoves()
        val values = c.db.investValues()
        val jars = c.db.jars()
        val jarMoves = c.db.jarMoves()
        val open = invs.filter { !it.closed }
        val tot = Invest.total(open, moves, values, today)
        val jarTotal = jars.filter { !it.closed }.sumOf { Jars.saved(it, jarMoves) }
        val body = c.vbox()

        c.hintCard("invest", R.string.hint_invest)?.let { body.add(it, top = 4, bottom = 12) }

        // Everything put aside
        val onAccent = c.col(R.color.on_accent)
        val soft = c.col(R.color.on_accent_soft)
        val box = c.card(22, c.col(R.color.accent))
        box.add(c.text(c.getString(R.string.savings_total), 15f, soft))
        box.add(c.text(Money.format(tot.valueCents + jarTotal), 36f, onAccent, BOLD), top = 2)
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.jars_title), Money.format(jarTotal), soft, onAccent), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.inv_title), Money.format(tot.valueCents), soft, onAccent), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.inv_gain), if (tot.investedCents > 0) Num.pct(tot.gainPct) else "—", soft, onAccent), w = 0, weight = 1f)
        box.add(row, top = 16)
        if (tot.investedCents > 0) box.add(c.text(c.getString(R.string.inv_total_line, Money.format(tot.investedCents), Num.signed(tot.gainCents)), 13f, soft), top = 10)
        body.add(box)

        // The investments
        body.add(c.section(R.string.inv_title), top = 24)
        if (invs.isEmpty()) {
            val empty = c.card(18)
            empty.add(c.text(c.getString(R.string.inv_empty_title), 16f, face = MEDIUM))
            empty.add(c.text(c.getString(R.string.inv_empty), 14f, c.col(R.color.ink2)), top = 4)
            empty.add(c.button(c.getString(R.string.inv_first), Btn.PRIMARY, R.drawable.ic_add) { c.push(InvestEditScreen(c)) }, w = WRAP, top = 12)
            body.add(empty, top = 10)
        } else {
            if (open.size > 1 && tot.valueCents > 0) body.add(allocation(open, moves, values, today, tot.valueCents), top = 10)
            val list = c.listCard()
            for (i in invs) list.add(c.investRow(i, Invest.position(i, moves, values, today)))
            body.add(list, top = 10)
            body.add(c.button(c.getString(R.string.inv_new), Btn.GHOST, R.drawable.ic_add) { c.push(InvestEditScreen(c)) }, w = WRAP, top = 10)

            val hist = Invest.history(open, moves, values, today)
            if (hist.size >= 2) {
                body.add(c.section(R.string.inv_history), top = 24)
                body.add(c.historyChart(hist), top = 10)
            }
            if (open.any { it.ratePct != 0.0 || it.hasPlan }) {
                body.add(c.section(R.string.inv_future), top = 24)
                body.add(c.chips(YEAR_OPTIONS.map { it to c.getString(R.string.inv_years, it) }, years) { years = it; refresh() }, top = 10)
                body.add(c.projectionCard(Invest.projectionAll(open, moves, values, today, years), c.getString(R.string.inv_proj_note_all)), top = 10)
            }
        }

        // The calculator
        val calc = c.card(16).apply {
            background = c.ripple(c.rounded(c.col(R.color.surface), 22), 22)
            setOnClickListener { c.push(CalculatorScreen(c)) }
        }
        val ch = c.hbox()
        ch.add(c.roundIcon(R.drawable.ic_chart, c.col(R.color.accent), 40), w = c.dp(40), h = c.dp(40))
        val cm = c.vbox()
        cm.add(c.text(c.getString(R.string.calc_title), 16f, face = MEDIUM))
        cm.add(c.text(c.getString(R.string.calc_sub), 13f, c.col(R.color.ink2)), top = 2)
        ch.add(cm, w = 0, weight = 1f, start = 12)
        ch.add(c.icon(R.drawable.ic_chevron_right, c.col(R.color.ink3), 20), w = c.dp(20), h = c.dp(20))
        calc.add(ch)
        body.add(calc, top = 16)

        // The jars
        body.add(c.section(R.string.jars_title), top = 24)
        if (jars.isEmpty()) body.add(c.text(c.getString(R.string.jars_empty), 14f, c.col(R.color.ink2)), top = 8, start = 4)
        else for (j in jars) body.add(c.jarCard(j, jarMoves, today), top = 10)
        body.add(c.button(c.getString(R.string.jar_new), Btn.GHOST, R.drawable.ic_jar) { c.push(JarEditScreen(c)) }, w = WRAP, top = 10)

        body.add(c.text(c.getString(R.string.inv_disclaimer), 12.5f, c.col(R.color.ink3)), top = 20, start = 4)
        val actions = listOf(Triple(R.drawable.ic_add, R.string.inv_new) { c.push(InvestEditScreen(c)) })
        return page(c.getString(R.string.savings_title), back = true, actions = actions, body = body)
    }

    /** How the value is split: one coloured stretch per investment, then a legend. */
    private fun allocation(open: List<Investment>, moves: List<InvestMove>, values: List<InvestValue>, today: LocalDate, total: Long): View {
        val c = act
        val box = c.card(16)
        val bar = c.hbox().apply { background = c.rounded(c.col(R.color.surface2), 6); clipToOutline = true }
        val parts = open.map { it to Invest.position(it, moves, values, today).valueCents }.filter { it.second > 0 }.sortedByDescending { it.second }
        val palette = intArrayOf(0xFF4F46E5.toInt(), 0xFF16A34A.toInt(), 0xFF0E8FA8.toInt(), 0xFFEA580C.toInt(), 0xFF7C3AED.toInt(), 0xFFB7791F.toInt(), 0xFFDB2777.toInt(), 0xFF78716C.toInt())
        parts.forEachIndexed { i, (_, v) ->
            bar.add(View(c).apply { setBackgroundColor(palette[i % palette.size]) }, w = 0, h = c.dp(12), weight = v.toFloat())
        }
        box.add(bar)
        parts.forEachIndexed { i, (inv, v) ->
            val r = c.hbox()
            r.add(View(c).apply { background = c.rounded(palette[i % palette.size], 5) }, w = c.dp(10), h = c.dp(10))
            r.add(c.text(inv.name, 13.5f, lines = 1), w = 0, weight = 1f, start = 8)
            r.add(c.text(Num.pct(v * 100.0 / total).removePrefix("+"), 13.5f, c.col(R.color.ink2), MEDIUM), w = WRAP)
            box.add(r, top = if (i == 0) 12 else 6)
        }
        return box
    }
}

/** One investment: value, gain, plan, charts, what was put in, the values typed in. */
class InvestmentScreen(act: MainActivity, private val invId: Long) : Screen(act) {

    private var years = 10

    override fun build(): View {
        val c = act
        val inv = c.db.investment(invId) ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val today = LocalDate.now()
        val moves = c.db.investMoves().filter { it.investmentId == invId }
        val values = c.db.investValues().filter { it.investmentId == invId }
        val pos = Invest.position(inv, moves, values, today)
        val body = c.vbox()

        val box = c.card(20)
        val head = c.hbox()
        head.add(c.roundIcon(investIcon(inv.kind), investColor(inv.kind), 48), w = c.dp(48), h = c.dp(48))
        val names = c.vbox()
        names.add(c.text(inv.name, 20f, face = BOLD, lines = 2))
        names.add(c.text(listOf(c.investKindLabel(inv.kind), inv.code).filter { it.isNotBlank() }.joinToString(" · "), 13f, c.col(R.color.ink2)), top = 1)
        head.add(names, w = 0, weight = 1f, start = 14)
        box.add(head)
        box.add(c.text(Money.format(pos.valueCents), 36f, face = BOLD), top = 14)
        val upd = pos.updated
        box.add(c.text(if (upd != null) c.getString(R.string.inv_updated, Fmt.date(upd)) else c.getString(R.string.inv_not_updated), 13f,
            c.col(if (upd == null || upd.isBefore(today.minusDays(45))) R.color.warn else R.color.ink2)), top = 2)
        val row = c.hbox()
        row.add(c.stat(c.getString(R.string.inv_invested), Money.format(pos.investedCents), c.col(R.color.ink2), c.col(R.color.ink)), w = 0, weight = 1f)
        row.add(c.stat(c.getString(R.string.inv_gain), Num.signed(pos.gainCents), c.col(R.color.ink2), c.gainColorOf(pos.gainCents)), w = 0, weight = 1f)
        row.add(c.stat("%", if (pos.investedCents > 0) Num.pct(pos.gainPct) else "—", c.col(R.color.ink2), c.gainColorOf(pos.gainCents)), w = 0, weight = 1f)
        box.add(row, top = 14)
        pos.units?.let { u -> if (u > 0) box.add(c.text(c.getString(R.string.inv_units_owned, Num.units(u)) +
            (Invest.lastPrice(inv, values, today)?.let { " · " + c.getString(R.string.inv_last_price, Num.price(it)) } ?: ""), 13f, c.col(R.color.ink2)), top = 10) }
        if (inv.note.isNotBlank()) box.add(c.text(inv.note, 14f), top = 10)
        body.add(box, top = 4)

        if (!inv.closed) {
            body.add(c.button(c.getString(R.string.inv_update), Btn.PRIMARY, R.drawable.ic_edit) { c.push(InvestValueScreen(c, invId)) }, top = 14)
            val b = c.hbox()
            b.add(c.button(c.getString(R.string.inv_buy), Btn.SECONDARY, R.drawable.ic_add) { c.push(InvestMoveScreen(c, invId)) }, w = 0, weight = 1f)
            b.add(c.button(c.getString(R.string.inv_sell), Btn.GHOST) { c.push(InvestMoveScreen(c, invId, sell = true)) }, w = 0, weight = 1f, start = 10)
            body.add(b, top = 10)
        }

        // The plan
        body.add(c.section(R.string.inv_plan), top = 24)
        val plan = c.card(16)
        if (inv.hasPlan) {
            plan.add(c.text(c.getString(R.string.inv_plan_line, Money.format(inv.planCents), Fmt.every(c, inv.planEvery).lowercase(), inv.planDay), 15f, face = MEDIUM))
            val next = Invest.planDates(inv, today.plusDays(1), today.plusYears(2)).firstOrNull()
            val bits = ArrayList<String>()
            inv.planFrom?.let { bits += c.getString(R.string.inv_plan_from, Fmt.date(it)) }
            inv.planUntil?.let { bits += c.getString(R.string.inv_plan_until, Fmt.date(it)) }
            if (next != null) bits += c.getString(R.string.inv_plan_next, Fmt.date(next))
            plan.add(c.text(bits.joinToString(" · "), 13f, c.col(R.color.ink2)), top = 4)
            plan.add(c.text(c.getString(R.string.inv_plan_auto), 13f, c.col(R.color.ink2)), top = 6)
        } else {
            plan.add(c.text(c.getString(R.string.inv_plan_none), 14f, c.col(R.color.ink2)))
            if (!inv.closed) plan.add(c.button(c.getString(R.string.inv_plan_set), Btn.SECONDARY) { c.push(InvestEditScreen(c, invId)) }, w = WRAP, top = 10)
        }
        body.add(plan, top = 10)

        // How it went, where it could go
        val hist = Invest.history(listOf(inv), moves, values, today)
        if (hist.size >= 2) {
            body.add(c.section(R.string.inv_history), top = 24)
            body.add(c.historyChart(hist), top = 10)
        }
        if (!inv.closed) {
            body.add(c.section(R.string.inv_future), top = 24)
            body.add(c.chips(YEAR_OPTIONS.map { it to c.getString(R.string.inv_years, it) }, years) { years = it; refresh() }, top = 10)
            body.add(c.projectionCard(Invest.projection(inv, moves, values, today, years),
                c.getString(R.string.inv_proj_note, Num.rate(inv.ratePct))), top = 10)
        }

        // Money in and out
        body.add(c.section(R.string.inv_moves), top = 24)
        if (moves.isEmpty()) {
            body.add(c.text(c.getString(R.string.inv_moves_empty), 14f, c.col(R.color.ink2)), top = 8, start = 4)
        } else {
            val list = c.listCard()
            for (m in moves.take(60)) {
                val r = c.hbox().apply {
                    setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
                    background = c.ripple(null, 0)
                    setOnClickListener { c.push(InvestMoveScreen(c, invId, moveId = m.id)) }
                }
                val mid = c.vbox()
                val what = when {
                    m.amountCents < 0 -> c.getString(R.string.inv_sold)
                    m.plan -> c.getString(R.string.inv_by_plan)
                    else -> c.getString(R.string.inv_bought)
                }
                mid.add(c.text(m.note.ifBlank { what }, 15f, face = MEDIUM, lines = 1))
                var sub = Fmt.date(m.day)
                m.units?.let { sub += " · " + c.getString(R.string.inv_units_n, Num.units(Math.abs(it))) }
                mid.add(c.text(sub, 13f, c.col(R.color.ink2)), top = 2)
                r.add(mid, w = 0, weight = 1f)
                r.add(c.text(Num.signed(m.amountCents), 15f, c.col(if (m.amountCents >= 0) R.color.ink else R.color.good), MEDIUM), w = WRAP)
                list.add(r)
            }
            body.add(list, top = 10)
        }

        // Values typed in
        if (values.isNotEmpty()) {
            body.add(c.section(R.string.inv_values), top = 24)
            val list = c.listCard()
            for (v in values.take(24)) {
                val r = c.hbox().apply {
                    setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
                    background = c.ripple(null, 0)
                    setOnClickListener {
                        Dialogs.confirm(c, R.string.inv_value_delete_title, c.getString(R.string.inv_value_delete_msg), R.string.delete) {
                            c.db.deleteInvestValue(v.id); c.changed(); refresh()
                        }
                    }
                }
                r.add(c.text(Fmt.date(v.day), 15f), w = 0, weight = 1f)
                val price = v.price
                val total = v.valueCents
                r.add(c.text(when {
                    total != null -> Money.format(total)
                    price != null -> c.getString(R.string.inv_price_each, Num.price(price))
                    else -> "—"
                }, 15f, face = MEDIUM), w = WRAP)
                list.add(r)
            }
            body.add(list, top = 10)
        }

        val actions = listOf(
            Triple(R.drawable.ic_edit, R.string.edit) { c.push(InvestEditScreen(c, invId)) },
            Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.inv_delete_title, c.getString(R.string.inv_delete_msg, inv.name), R.string.delete) {
                    c.db.deleteInvestment(invId); c.changed(); c.pop()
                }
            }
        )
        return page("", back = true, actions = actions, body = body)
    }
}

private fun MainActivity.gainColorOf(cents: Long): Int = col(if (cents >= 0) R.color.good else R.color.bad)

/** New investment (id 0) or edit: name, kind, code, expected return, plan. */
class InvestEditScreen(act: MainActivity, private val id: Long = 0) : Screen(act) {

    private val original: Investment? = if (id != 0L) act.db.investment(id) else null
    private var name = original?.name ?: ""
    private var kind = original?.kind ?: InvestKind.ETF
    private var code = original?.code ?: ""
    private var rateText = original?.let { Num.input(it.ratePct) } ?: Num.input(Invest.suggestedRate(InvestKind.ETF))
    private var rateTouched = original != null
    private var plan = original?.hasPlan ?: false
    private var planText = original?.planCents?.takeIf { it > 0 }?.let { Money.toInput(it) } ?: ""
    private var every = original?.planEvery ?: 1
    private var dayText = (original?.planDay ?: 1).toString()
    private var from: LocalDate = original?.planFrom ?: LocalDate.now()
    private var until: LocalDate? = original?.planUntil
    private var note = original?.note ?: ""
    private var closed = original?.closed ?: false
    /** A new investment: what is already in it. */
    private var startText = ""
    private var startDay: LocalDate = LocalDate.now()

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.labeled(c.getString(R.string.inv_name), c.field(c.getString(R.string.inv_name_hint), name) { name = it }), top = 8)
        body.add(c.labeled(c.getString(R.string.inv_kind), c.chips(InvestKind.values().map { it to c.investKindLabel(it) }, kind) { k ->
            kind = k
            if (!rateTouched) { rateText = Num.input(Invest.suggestedRate(k)); refresh() }
        }), top = 16)
        body.add(c.labeled(c.getString(R.string.inv_code), c.field(c.getString(R.string.inv_code_hint), code,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) { code = it }), top = 16)
        body.add(c.labeled(c.getString(R.string.inv_rate), c.numberField(c.getString(R.string.inv_rate_hint), rateText) { rateText = it; rateTouched = true }), top = 16)
        body.add(c.text(c.getString(R.string.inv_rate_help), 13f, c.col(R.color.ink2)), top = 6, start = 4)

        if (original == null) {
            body.add(c.labeled(c.getString(R.string.inv_start), c.numberField(c.getString(R.string.inv_start_hint), startText) { startText = it }), top = 16)
            body.add(c.labeled(c.getString(R.string.inv_start_day), c.dateButton(startDay, "") { startDay = it }), top = 10)
        }

        // The plan
        val box = c.card(14)
        box.add(c.toggleRow(c.getString(R.string.inv_plan_toggle), plan) { plan = it; refresh() })
        if (plan) {
            box.add(c.labeled(c.getString(R.string.inv_plan_amount), c.numberField(c.getString(R.string.field_amount_hint), planText) { planText = it }), top = 10)
            box.add(c.labeled(c.getString(R.string.inv_plan_every), c.chips(listOf(1, 2, 3, 6, 12).map { it to Fmt.every(c, it) }, every) { every = it }), top = 12)
            box.add(c.labeled(c.getString(R.string.inv_plan_day), c.field("1", dayText, InputType.TYPE_CLASS_NUMBER) { dayText = it }), top = 12)
            box.add(c.labeled(c.getString(R.string.inv_plan_start), c.dateButton(from, "") { from = it }), top = 12)
            box.add(c.labeled(c.getString(R.string.inv_plan_end), c.dateRow(until, c.getString(R.string.inv_plan_no_end),
                { until = it; refresh() }, if (until != null) ({ until = null; refresh() }) else null)), top = 12)
            box.add(c.text(c.getString(R.string.inv_plan_help), 13f, c.col(R.color.ink2)), top = 8, start = 4)
        }
        body.add(box, top = 18)

        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.field_note_hint), note, lines = 2) { note = it }), top = 16)
        if (original != null) body.add(c.toggleRow(c.getString(R.string.inv_close), closed) { closed = it }, top = 12)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        return page(c.getString(if (original == null) R.string.inv_new else R.string.inv_edit), back = true, body = body)
    }

    private fun save() {
        val c = act
        if (name.isBlank()) { c.toast(R.string.inv_need_name); return }
        val rate = if (rateText.isBlank()) 0.0 else Num.parse(rateText) ?: run { c.toast(R.string.inv_bad_rate); return }
        if (rate < -50 || rate > 50) { c.toast(R.string.inv_bad_rate); return }
        var planCents = 0L
        var day = 1
        if (plan) {
            planCents = Money.parse(planText) ?: 0L
            if (planCents <= 0) { c.toast(R.string.bad_amount); return }
            day = dayText.trim().toIntOrNull() ?: 0
            if (day !in 1..31) { c.toast(R.string.inv_bad_day); return }
        }
        val start = if (original == null && startText.isNotBlank()) Money.parse(startText) ?: run { c.toast(R.string.bad_amount); return } else 0L
        val wasPlan = original?.hasPlan ?: false
        val base = original ?: Investment(name = name)
        val inv = base.copy(
            name = name.trim(), kind = kind, code = code.trim(), ratePct = rate,
            planCents = planCents, planEvery = every, planDay = day,
            planFrom = if (plan) from else null, planUntil = if (plan) until else null,
            // A plan just switched on fills in its past dates; an edited one carries on from where it was.
            planLast = if (plan && wasPlan) base.planLast else null,
            note = note.trim(), closed = closed
        )
        val saved = c.db.saveInvestment(inv)
        if (start > 0) c.db.saveInvestMove(InvestMove(investmentId = saved, day = startDay, amountCents = start, note = c.getString(R.string.inv_start_note)))
        val n = c.db.catchUpPlans(LocalDate.now())
        c.changed()
        if (n > 0) c.toast(c.resources.getQuantityString(R.plurals.inv_plan_recorded, n, n))
        c.pop()
        if (original == null) c.push(InvestmentScreen(c, saved))
    }
}

/** Money put in (or taken out): amount, day, units or unit price, note. */
class InvestMoveScreen(act: MainActivity, private val invId: Long, private val sell: Boolean = false, private val moveId: Long = 0) : Screen(act) {

    private val original: InvestMove? = if (moveId != 0L) act.db.investMoves().firstOrNull { it.id == moveId } else null
    private val selling = original?.let { it.amountCents < 0 } ?: sell
    private var amountText = original?.let { Money.toInput(Math.abs(it.amountCents)) } ?: ""
    private var unitsText = original?.units?.let { Num.input(Math.abs(it)) } ?: ""
    private var priceText = ""
    private var day: LocalDate = original?.day ?: LocalDate.now()
    private var note = original?.note ?: ""

    override fun build(): View {
        val c = act
        val inv = c.db.investment(invId)
        val body = c.vbox()
        if (inv != null) body.add(c.text(inv.name, 15f, c.col(R.color.ink2), MEDIUM), start = 4)
        val amount = c.numberField(c.getString(R.string.field_amount_hint), amountText) { amountText = it }.apply { textSize = 26f; typeface = MEDIUM }
        body.add(c.labeled(c.getString(if (selling) R.string.inv_sell_amount else R.string.inv_buy_amount), amount), top = 10)
        body.add(c.labeled(c.getString(R.string.inv_day), c.dateButton(day, "") { day = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.inv_units), c.numberField(c.getString(R.string.inv_units_hint), unitsText) { unitsText = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.inv_or_price), c.numberField(c.getString(R.string.inv_price_hint), priceText) { priceText = it }), top = 10)
        body.add(c.text(c.getString(R.string.inv_units_help), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.field_note_hint), note) { note = it }), top = 14)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        if (original != null) {
            body.add(c.button(c.getString(R.string.delete), Btn.DANGER, R.drawable.ic_delete) {
                c.db.deleteInvestMove(moveId); c.changed(); c.pop()
            }, top = 12)
        }
        return page(c.getString(if (selling) R.string.inv_sell else R.string.inv_buy), back = true, body = body)
    }

    private fun save() {
        val c = act
        val v = Money.parse(amountText)
        if (v == null || v <= 0) { c.toast(R.string.bad_amount); return }
        var units = if (unitsText.isBlank()) null else Num.parse(unitsText) ?: run { c.toast(R.string.inv_bad_number); return }
        if (units == null && priceText.isNotBlank()) {
            val price = Num.parse(priceText) ?: run { c.toast(R.string.inv_bad_number); return }
            units = Invest.unitsFor(v, price)
        }
        val sign = if (selling) -1 else 1
        val m = (original ?: InvestMove(investmentId = invId, day = day, amountCents = v)).copy(
            day = day, amountCents = sign * v, units = units?.let { sign * Math.abs(it) }, note = note.trim()
        )
        c.db.saveInvestMove(m)
        // A unit price typed here is also the latest value.
        if (priceText.isNotBlank()) Num.parse(priceText)?.let { p -> c.db.saveInvestValue(InvestValue(investmentId = invId, day = day, price = p)) }
        c.changed()
        c.pop()
    }
}

/** "Aggiorna il valore": the whole value, or the price of one unit, copied from the bank or broker. */
class InvestValueScreen(act: MainActivity, private val invId: Long) : Screen(act) {

    private var byPrice = false
    private var text = ""
    private var day: LocalDate = LocalDate.now()

    override fun build(): View {
        val c = act
        val inv = c.db.investment(invId) ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val moves = c.db.investMoves().filter { it.investmentId == invId }
        val units = Invest.units(moves)
        val body = c.vbox()
        body.add(c.text(inv.name, 15f, c.col(R.color.ink2), MEDIUM), start = 4)
        body.add(c.text(c.getString(R.string.inv_update_help), 14f, c.col(R.color.ink2)), top = 6, start = 4)
        if (units != null && units > 0) {
            body.add(c.chips(listOf(false to c.getString(R.string.inv_by_value), true to c.getString(R.string.inv_by_price)), byPrice) {
                byPrice = it; refresh()
            }, top = 14)
        } else byPrice = false
        val f = c.numberField(c.getString(if (byPrice) R.string.inv_price_hint else R.string.field_amount_hint), text) { text = it }.apply { textSize = 26f; typeface = MEDIUM }
        body.add(c.labeled(c.getString(if (byPrice) R.string.inv_price_now else R.string.inv_value_now), f), top = 14)
        if (byPrice && units != null) body.add(c.text(c.getString(R.string.inv_units_owned, Num.units(units)), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        body.add(c.labeled(c.getString(R.string.inv_day), c.dateButton(day, "") { day = it }), top = 14)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        return page(c.getString(R.string.inv_update), back = true, body = body)
    }

    private fun save() {
        val c = act
        if (byPrice) {
            val p = Num.parse(text)
            if (p == null || p <= 0) { c.toast(R.string.inv_bad_number); return }
            c.db.saveInvestValue(InvestValue(investmentId = invId, day = day, price = p))
        } else {
            val v = Money.parse(text)
            if (v == null || v < 0) { c.toast(R.string.bad_amount); return }
            c.db.saveInvestValue(InvestValue(investmentId = invId, day = day, valueCents = v))
        }
        c.changed()
        c.toast(R.string.inv_value_saved)
        c.pop()
    }
}

/** The accumulation calculator: a starting amount, so much a month, a yearly return, some years. */
class CalculatorScreen(act: MainActivity) : Screen(act) {

    private var startText = ""
    private var monthlyText = "100"
    private var rateText = "6"
    private var years = 20
    private lateinit var result: LinearLayout

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.text(c.getString(R.string.calc_help), 14f, c.col(R.color.ink2)), top = 4, start = 4)
        body.add(c.labeled(c.getString(R.string.calc_start), c.numberField(c.getString(R.string.calc_start_hint), startText) { startText = it; update() }), top = 16)
        body.add(c.labeled(c.getString(R.string.calc_monthly), c.numberField(c.getString(R.string.field_amount_hint), monthlyText) { monthlyText = it; update() }), top = 14)
        body.add(c.labeled(c.getString(R.string.inv_rate), c.numberField(c.getString(R.string.inv_rate_hint), rateText) { rateText = it; update() }), top = 14)
        body.add(c.labeled(c.getString(R.string.calc_years), c.chips(listOf(5, 10, 15, 20, 30, 40).map { it to c.getString(R.string.inv_years, it) }, years) {
            years = it; update()
        }), top = 14)
        result = c.vbox()
        body.add(result, top = 18)
        body.add(c.text(c.getString(R.string.inv_disclaimer), 12.5f, c.col(R.color.ink3)), top = 16, start = 4)
        update()
        return page(c.getString(R.string.calc_title), back = true, body = body)
    }

    /** Only the result is redrawn, so the keyboard stays on the field being typed in. */
    private fun update() {
        if (!this::result.isInitialized) return
        val c = act
        val start = if (startText.isBlank()) 0L else Money.parse(startText) ?: 0L
        val monthly = if (monthlyText.isBlank()) 0L else Money.parse(monthlyText) ?: 0L
        val rate = Num.parse(rateText) ?: 0.0
        result.removeAllViews()
        if (start <= 0 && monthly <= 0) {
            result.add(c.text(c.getString(R.string.calc_empty), 14f, c.col(R.color.ink2)), start = 4)
            return
        }
        val pts = Invest.calculator(start, monthly, rate.coerceIn(-50.0, 50.0), years)
        result.add(c.projectionCard(pts, c.getString(R.string.calc_note, Num.rate(rate))))
    }
}
