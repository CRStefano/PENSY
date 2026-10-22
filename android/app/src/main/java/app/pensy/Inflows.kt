package app.pensy

import android.content.Context
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.pensy.core.Inflow
import app.pensy.core.InflowCat
import app.pensy.core.Money
import java.time.LocalDate

/*
 * Other income: money that came in outside the payslip (a friend paid you
 * back, a gift, something sold, a small job). Like an everyday expense, it
 * is three taps: amount, where it comes from, save. It counts in the
 * month's income on top of the payslip, or of its estimate.
 *
 * Cash or card: every expense and every income says how the money moved.
 * PENSY does not keep two wallets with two balances (the bank app already
 * knows the real one, and a forgotten expense would make both wrong): it
 * just tells, month by month, how much went by card and how much in cash.
 */

fun inflowColor(c: InflowCat): Int = when (c) {
    InflowCat.REGALO -> 0xFFDB2777.toInt()
    InflowCat.RIMBORSO -> 0xFF0E8FA8.toInt()
    InflowCat.VENDITA -> 0xFFEA580C.toInt()
    InflowCat.LAVORO -> 0xFF7C3AED.toInt()
    InflowCat.ALTRO -> 0xFF16A34A.toInt()
}

fun inflowIcon(c: InflowCat): Int = when (c) {
    InflowCat.REGALO -> R.drawable.ic_gift
    InflowCat.RIMBORSO -> R.drawable.ic_undo
    InflowCat.VENDITA -> R.drawable.ic_sell
    InflowCat.LAVORO -> R.drawable.ic_work
    InflowCat.ALTRO -> R.drawable.ic_wallet
}

fun MainActivity.inflowLabel(c: InflowCat): String = getString(
    when (c) {
        InflowCat.REGALO -> R.string.icat_regalo
        InflowCat.RIMBORSO -> R.string.icat_rimborso
        InflowCat.VENDITA -> R.string.icat_vendita
        InflowCat.LAVORO -> R.string.icat_lavoro
        InflowCat.ALTRO -> R.string.icat_altro
    }
)

/** How the last expense or income moved, so the next one starts the same way (a device setting). */
object PayPref {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pensy_device", Context.MODE_PRIVATE)
    fun lastCash(ctx: Context, what: String): Boolean = prefs(ctx).getBoolean("cash_$what", false)
    fun setLastCash(ctx: Context, what: String, cash: Boolean) { prefs(ctx).edit().putBoolean("cash_$what", cash).apply() }
}

/** "Carta o conto" / "Contanti". */
fun MainActivity.payChips(cash: Boolean, onChange: (Boolean) -> Unit): View =
    chips(listOf(false to getString(R.string.pay_card), true to getString(R.string.pay_cash)), cash, onChange)

/** One income in a list, with a "+" amount. */
fun MainActivity.inflowRow(x: Inflow): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        background = ripple(null, 0)
        setOnClickListener { push(InflowScreen(this@inflowRow, x.id)) }
    }
    row.add(roundIcon(inflowIcon(x.cat), inflowColor(x.cat), 42), w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(x.note.ifBlank { inflowLabel(x.cat) }, 16f, face = MEDIUM, lines = 1))
    var sub = Fmt.dateShort(x.day) + " · " + inflowLabel(x.cat)
    if (x.cash) sub += " · " + getString(R.string.tax_cash_short)
    mid.add(text(sub, 13f, col(R.color.ink2), lines = 1), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    row.add(text("+" + Money.format(x.amountCents), 15f, col(R.color.good), MEDIUM), w = WRAP, start = 8)
    return row
}

/** New income (id 0) or edit one. */
class InflowScreen(act: MainActivity, private val id: Long = 0, private val day0: LocalDate? = null) : Screen(act) {

    private val original: Inflow? = if (id != 0L) act.db.inflow(id) else null
    private var amountText = original?.let { Money.toInput(it.amountCents) } ?: ""
    private var cat = original?.cat ?: InflowCat.REGALO
    private var day: LocalDate = original?.day ?: day0 ?: LocalDate.now()
    private var note = original?.note ?: ""
    private var cash = original?.cash ?: PayPref.lastCash(act, "inflow")
    private var focused = original != null

    override fun build(): View {
        val c = act
        val body = c.vbox()
        val amount = c.field(c.getString(R.string.field_amount_hint), amountText,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { amountText = it }.apply {
            keyListener = DigitsKeyListener.getInstance("0123456789,.")
            textSize = 28f
            typeface = MEDIUM
        }
        body.add(c.labeled(c.getString(R.string.field_amount), amount), top = 8)
        if (!focused) amount.post {
            focused = true
            amount.requestFocus()
            c.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(amount, 0)
        }

        // Where it comes from: one row of tiles.
        body.add(c.text(c.getString(R.string.inflow_cat), 13f, c.col(R.color.ink2), MEDIUM), top = 18, start = 4)
        val row: LinearLayout = c.hbox().apply { gravity = Gravity.TOP }
        for (k in InflowCat.entries) {
            val on = k == cat
            val tile = c.vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, c.dp(8), 0, c.dp(8))
                background = c.ripple(if (on) c.rounded(c.col(R.color.accent_soft), 14) else null, 14)
                setOnClickListener { cat = k; refresh() }
                contentDescription = c.inflowLabel(k)
            }
            tile.add(c.roundIcon(inflowIcon(k), inflowColor(k), 40), w = c.dp(40), h = c.dp(40), gravity = Gravity.CENTER_HORIZONTAL)
            tile.add(c.text(c.inflowLabel(k), 11.5f, if (on) c.col(R.color.accent_ink) else c.col(R.color.ink2), MEDIUM, lines = 1).apply {
                gravity = Gravity.CENTER
            }, top = 4)
            row.add(tile, w = 0, weight = 1f)
        }
        body.add(row, top = 8)

        body.add(c.labeled(c.getString(R.string.pay_received), c.payChips(cash) { cash = it }), top = 18)
        body.add(c.labeled(c.getString(R.string.spend_day), c.dateButton(day, "") { day = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.inflow_note_hint), note) { note = it }), top = 14)
        body.add(c.text(c.getString(R.string.inflow_explain), 13f, c.col(R.color.ink2)), top = 10, start = 4)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        if (original != null) {
            body.add(c.button(c.getString(R.string.delete), Btn.DANGER, R.drawable.ic_delete) {
                c.db.deleteInflow(id); c.changed(); c.toast(R.string.inflow_deleted); c.pop()
            }, top = 12)
        }
        return page(c.getString(if (original == null) R.string.inflow_new else R.string.inflow_edit), back = true, body = body)
    }

    private fun save() {
        val c = act
        val v = Money.parse(amountText)
        if (v == null || v <= 0) { c.toast(R.string.bad_amount); return }
        val x = (original ?: Inflow(day = day, amountCents = v)).copy(day = day, amountCents = v, cat = cat, note = note.trim(), cash = cash)
        c.db.saveInflow(x)
        if (original == null) PayPref.setLastCash(c, "inflow", cash)
        c.changed()
        c.toast(R.string.inflow_saved)
        c.pop()
    }
}
