package app.pensy

import android.app.AlertDialog
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.pensy.core.Jar
import app.pensy.core.JarMove
import app.pensy.core.Jars
import app.pensy.core.Money
import app.pensy.core.Spend
import app.pensy.core.SpendCat
import app.pensy.core.Tax
import app.pensy.core.TaxCat
import java.time.LocalDate
import java.time.YearMonth

/*
 * Everyday spending and savings jars, as seen in "Bilancio".
 *
 * A spend is three taps: amount, category, save (the day is today unless
 * changed). A jar holds money put aside for something; with a goal and a
 * date it suggests how much to put in each month, and that quota counts
 * in "how much is left" so the money is not spent twice.
 */

fun spendColor(c: SpendCat): Int = when (c) {
    SpendCat.SPESA -> 0xFF16A34A.toInt()
    SpendCat.CASA -> 0xFF2563EB.toInt()
    SpendCat.TRASPORTI -> 0xFF0E8FA8.toInt()
    SpendCat.AUTO -> 0xFF7C3AED.toInt()
    SpendCat.SALUTE -> 0xFFE11D48.toInt()
    SpendCat.RISTORANTI -> 0xFFEA580C.toInt()
    SpendCat.SVAGO -> 0xFFDB2777.toInt()
    SpendCat.ISTRUZIONE -> 0xFFB7791F.toInt()
    SpendCat.ANIMALI -> 0xFFC2410C.toInt()
    SpendCat.ALTRO -> 0xFF78716C.toInt()
}

fun spendIcon(c: SpendCat): Int = when (c) {
    SpendCat.SPESA -> R.drawable.ic_cart
    SpendCat.CASA -> R.drawable.ic_home
    SpendCat.TRASPORTI -> R.drawable.ic_bus
    SpendCat.AUTO -> R.drawable.ic_car
    SpendCat.SALUTE -> R.drawable.ic_health
    SpendCat.RISTORANTI -> R.drawable.ic_restaurant
    SpendCat.SVAGO -> R.drawable.ic_star
    SpendCat.ISTRUZIONE -> R.drawable.ic_school
    SpendCat.ANIMALI -> R.drawable.ic_pets
    SpendCat.ALTRO -> R.drawable.ic_receipt
}

fun MainActivity.spendLabel(c: SpendCat): String = getString(
    when (c) {
        SpendCat.SPESA -> R.string.cat_spesa
        SpendCat.CASA -> R.string.cat_casa
        SpendCat.TRASPORTI -> R.string.cat_trasporti
        SpendCat.AUTO -> R.string.cat_auto
        SpendCat.SALUTE -> R.string.cat_salute
        SpendCat.RISTORANTI -> R.string.cat_ristoranti
        SpendCat.SVAGO -> R.string.cat_svago
        SpendCat.ISTRUZIONE -> R.string.cat_istruzione
        SpendCat.ANIMALI -> R.string.cat_animali
        SpendCat.ALTRO -> R.string.cat_altro
    }
)

/** A round icon on a soft tint: used for categories and jars. */
fun MainActivity.roundIcon(res: Int, color: Int, sizeDp: Int): View {
    val box = FrameLayout(this)
    box.background = rounded((color and 0x00FFFFFF) or 0x24000000, sizeDp / 3)
    box.addView(icon(res, color, sizeDp / 2), FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
    return box
}

/** One everyday expense in a list. `who`: the profile's name when it is not the one in use. */
fun MainActivity.spendRow(s: Spend, who: String?): View {
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(16), dp(12))
        background = ripple(null, 0)
        setOnClickListener { push(SpendScreen(this@spendRow, s.id)) }
    }
    row.add(roundIcon(spendIcon(s.cat), spendColor(s.cat), 42), w = dp(42), h = dp(42))
    val mid = vbox()
    mid.add(text(s.note.ifBlank { spendLabel(s.cat) }, 16f, face = MEDIUM, lines = 1))
    var sub = Fmt.dateShort(s.day) + " · " + spendLabel(s.cat)
    if (who != null) sub += " · $who"
    mid.add(text(sub, 13f, col(R.color.ink2), lines = 1), top = 2)
    row.add(mid, w = 0, weight = 1f, start = 14)
    row.add(text(Money.format(s.amountCents), 15f, col(R.color.ink), MEDIUM), w = WRAP, start = 8)
    return row
}

/** A thin bar filled to `fraction`. */
fun MainActivity.progressBar(fraction: Double, color: Int): View {
    val bar = hbox().apply { background = rounded(col(R.color.surface2), 4); clipToOutline = true }
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    if (f > 0f) bar.add(View(this).apply { background = rounded(color, 4) }, w = 0, h = dp(8), weight = f)
    if (f < 1f) bar.add(View(this), w = 0, h = dp(8), weight = 1f - f)
    return bar
}

/** One jar: name, saved / goal, bar, the monthly quota. */
fun MainActivity.jarCard(j: Jar, moves: List<JarMove>, today: LocalDate): View {
    val saved = Jars.saved(j, moves)
    val box = card(16).apply {
        background = ripple(rounded(col(R.color.surface), 22), 22)
        setOnClickListener { push(JarScreen(this@jarCard, j.id)) }
    }
    val head = hbox()
    head.add(roundIcon(R.drawable.ic_jar, JAR_COLOR, 40), w = dp(40), h = dp(40))
    val names = vbox()
    names.add(text(j.name, 16f, face = MEDIUM, lines = 1))
    names.add(text(jarLine(j, moves, today), 13f, col(R.color.ink2), lines = 2), top = 1)
    head.add(names, w = 0, weight = 1f, start = 12)
    head.add(text(Money.format(saved), 16f, col(R.color.ink), MEDIUM), w = WRAP, start = 8)
    box.add(head)
    if (j.goalCents > 0) box.add(progressBar(Jars.progress(j, moves), JAR_COLOR), top = 12)
    return box
}

const val JAR_COLOR = 0xFF0F766E.toInt()

/** "di 1.200 € · 133 € al mese fino a giugno", or "Obiettivo raggiunto". */
fun MainActivity.jarLine(j: Jar, moves: List<JarMove>, today: LocalDate): String {
    val saved = Jars.saved(j, moves)
    val parts = ArrayList<String>()
    if (j.closed) parts += getString(R.string.jar_closed)
    if (j.goalCents > 0) parts += if (saved >= j.goalCents) getString(R.string.jar_reached) else getString(R.string.jar_of, Money.format(j.goalCents))
    val q = Jars.quota(j, moves, YearMonth.from(today))
    if (q > 0 && j.byDate != null) parts += getString(R.string.jar_quota, Money.formatShort(q), Fmt.monthName(YearMonth.from(j.byDate)))
    else if (j.byDate != null && saved < j.goalCents && !j.byDate.isAfter(today)) parts += getString(R.string.jar_late)
    return parts.joinToString(" · ").ifEmpty { getString(R.string.jar_no_goal) }
}

/** Ask an amount (and a note). */
fun MainActivity.askAmount(title: String, done: (Long, String) -> Unit) {
    val box = vbox().apply { setPadding(dp(24), dp(8), dp(24), 0) }
    val amount = box.add(field(getString(R.string.field_amount_hint), "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
        keyListener = DigitsKeyListener.getInstance("0123456789,.")
        textSize = 22f
        typeface = MEDIUM
    })
    val note = box.add(field(getString(R.string.field_note_hint), ""), top = 10)
    val dlg = AlertDialog.Builder(this, dialogTheme())
        .setTitle(title)
        .setView(box)
        .setPositiveButton(R.string.ok, null)
        .setNegativeButton(R.string.cancel, null)
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = Money.parse(amount.text.toString())
            if (v == null || v <= 0) toast(R.string.bad_amount)
            else { dlg.dismiss(); done(v, note.text.toString().trim()) }
        }
    }
    dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dlg.show()
    amount.requestFocus()
}

/** New everyday expense (id 0) or edit one. */
class SpendScreen(act: MainActivity, private val id: Long = 0, private val day0: LocalDate? = null) : Screen(act) {

    private val original: Spend? = if (id != 0L) act.db.spend(id) else null
    private var amountText = original?.let { Money.toInput(it.amountCents) } ?: ""
    private var cat = original?.cat ?: SpendCat.SPESA
    private var day: LocalDate = original?.day ?: day0 ?: LocalDate.now()
    private var note = original?.note ?: ""
    private var focused = original != null
    /** 730: the kind of deduction ("" none); suggested from the category until the user picks. */
    private var tax: String = original?.tax ?: (Tax.suggest(cat)?.code ?: "")
    private var taxTouched = original != null
    private var cash = original?.cash ?: false

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

        // Categories as a grid of tiles, five per row.
        body.add(c.text(c.getString(R.string.spend_cat), 13f, c.col(R.color.ink2), MEDIUM), top = 18, start = 4)
        val cats = SpendCat.entries
        var row: LinearLayout? = null
        cats.forEachIndexed { i, k ->
            if (i % 5 == 0) { row = c.hbox().apply { gravity = Gravity.TOP }; body.add(row!!, top = if (i == 0) 8 else 6) }
            val on = k == cat
            val tile = c.vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, c.dp(8), 0, c.dp(8))
                background = c.ripple(if (on) c.rounded(c.col(R.color.accent_soft), 14) else null, 14)
                setOnClickListener {
                    cat = k
                    if (!taxTouched) tax = Tax.suggest(k)?.code ?: ""
                    refresh()
                }
                contentDescription = c.spendLabel(k)
            }
            tile.add(c.roundIcon(spendIcon(k), spendColor(k), 40), w = c.dp(40), h = c.dp(40), gravity = Gravity.CENTER_HORIZONTAL)
            tile.add(c.text(c.spendLabel(k), 11.5f, if (on) c.col(R.color.accent_ink) else c.col(R.color.ink2), MEDIUM, lines = 1).apply {
                gravity = Gravity.CENTER
            }, top = 4)
            row!!.add(tile, w = 0, weight = 1f)
        }

        body.add(c.labeled(c.getString(R.string.spend_day), c.dateButton(day, "") { day = it }), top = 18)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.spend_note_hint), note) { note = it }), top = 14)

        // For the 730
        val taxOptions = listOf("" to c.getString(R.string.tax_none)) + TaxCat.entries.map { it.code to c.taxLabel(it) }
        body.add(c.labeled(c.getString(R.string.tax_field), c.chips(taxOptions, tax) { tax = it; taxTouched = true; refresh() }), top = 14)
        if (tax.isNotEmpty()) {
            body.add(c.toggleRow(c.getString(R.string.tax_cash), cash) { cash = it }, top = 6)
            body.add(c.text(c.getString(R.string.tax_cash_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        }

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        if (original != null) {
            body.add(c.button(c.getString(R.string.delete), Btn.DANGER, R.drawable.ic_delete) {
                c.db.deleteSpend(id); c.changed(); c.toast(R.string.spend_deleted); c.pop()
            }, top = 12)
        }
        return page(c.getString(if (original == null) R.string.spend_new else R.string.spend_edit), back = true, body = body)
    }

    private fun save() {
        val c = act
        val v = Money.parse(amountText)
        if (v == null || v <= 0) { c.toast(R.string.bad_amount); return }
        val s = (original ?: Spend(day = day, amountCents = v)).copy(
            day = day, amountCents = v, cat = cat, note = note.trim(), tax = tax, cash = tax.isNotEmpty() && cash
        )
        c.db.saveSpend(s)
        c.changed()
        c.toast(R.string.spend_saved)
        c.pop()
    }
}

/** One jar: what is in it, the quota, put in or take out, its history. */
class JarScreen(act: MainActivity, private val id: Long) : Screen(act) {

    override fun build(): View {
        val c = act
        val j = c.db.jar(id) ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val today = LocalDate.now()
        val moves = c.db.jarMoves().filter { it.jarId == id }
        val saved = Jars.saved(j, moves)
        val body = c.vbox()

        val box = c.card(20)
        val head = c.hbox()
        head.add(c.roundIcon(R.drawable.ic_jar, JAR_COLOR, 48), w = c.dp(48), h = c.dp(48))
        head.add(c.text(j.name, 20f, face = BOLD, lines = 2), w = 0, weight = 1f, start = 14)
        box.add(head)
        box.add(c.text(Money.format(saved), 36f, face = BOLD), top = 14)
        box.add(c.text(c.jarLine(j, moves, today), 14f, c.col(R.color.ink2)), top = 2)
        if (j.goalCents > 0) {
            box.add(c.progressBar(Jars.progress(j, moves), JAR_COLOR), top = 14)
            val missing = j.goalCents - saved
            if (missing > 0) box.add(c.text(c.getString(R.string.jar_missing, Money.format(missing)) +
                (j.byDate?.let { " · " + c.getString(R.string.jar_by, Fmt.date(it)) } ?: ""), 13f, c.col(R.color.ink2)), top = 8)
        }
        if (j.note.isNotBlank()) box.add(c.text(j.note, 14f), top = 12)
        body.add(box, top = 4)

        if (!j.closed) {
            val row = c.hbox()
            row.add(c.button(c.getString(R.string.jar_put), Btn.PRIMARY, R.drawable.ic_add) {
                c.askAmount(c.getString(R.string.jar_put_title, j.name)) { v, note ->
                    c.db.saveJarMove(JarMove(jarId = id, day = LocalDate.now(), amountCents = v, note = note)); c.changed(); refresh()
                }
            }, w = 0, weight = 1f)
            row.add(c.button(c.getString(R.string.jar_take), Btn.SECONDARY) {
                c.askAmount(c.getString(R.string.jar_take_title, j.name)) { v, note ->
                    c.db.saveJarMove(JarMove(jarId = id, day = LocalDate.now(), amountCents = -v, note = note)); c.changed(); refresh()
                }
            }, w = 0, weight = 1f, start = 10)
            body.add(row, top = 14)
            body.add(c.text(c.getString(R.string.jar_explain), 13f, c.col(R.color.ink2)), top = 10, start = 4)
        }

        body.add(c.section(R.string.jar_history), top = 24)
        if (moves.isEmpty()) {
            body.add(c.text(c.getString(R.string.jar_history_empty), 14f, c.col(R.color.ink2)), top = 8, start = 4)
        } else {
            val list = c.listCard()
            for (m in moves) {
                val r = c.hbox().apply {
                    setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
                    background = c.ripple(null, 0)
                    setOnClickListener {
                        Dialogs.confirm(c, R.string.jar_move_delete_title, c.getString(R.string.jar_move_delete_msg), R.string.delete) {
                            c.db.deleteJarMove(m.id); c.changed(); refresh()
                        }
                    }
                }
                val mid = c.vbox()
                mid.add(c.text(m.note.ifBlank { c.getString(if (m.amountCents >= 0) R.string.jar_put else R.string.jar_take) }, 15f, face = MEDIUM, lines = 1))
                mid.add(c.text(Fmt.date(m.day), 13f, c.col(R.color.ink2)), top = 2)
                r.add(mid, w = 0, weight = 1f)
                val sign = if (m.amountCents >= 0) "+" else "−"
                r.add(c.text(sign + Money.format(Math.abs(m.amountCents)), 15f,
                    c.col(if (m.amountCents >= 0) R.color.good else R.color.ink), MEDIUM), w = WRAP)
                list.add(r)
            }
            body.add(list, top = 10)
        }

        val actions = listOf(
            Triple(R.drawable.ic_edit, R.string.edit) { c.push(JarEditScreen(c, id)) },
            Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.jar_delete_title, c.getString(R.string.jar_delete_msg, j.name), R.string.delete) {
                    c.db.deleteJar(id); c.changed(); c.pop()
                }
            }
        )
        return page("", back = true, actions = actions, body = body)
    }
}

/** New jar (id 0) or edit: name, goal and date (both optional), note. */
class JarEditScreen(act: MainActivity, private val id: Long = 0) : Screen(act) {

    private val original: Jar? = if (id != 0L) act.db.jar(id) else null
    private var name = original?.name ?: ""
    private var goalText = original?.goalCents?.takeIf { it > 0 }?.let { Money.toInput(it) } ?: ""
    private var byDate: LocalDate? = original?.byDate
    private var note = original?.note ?: ""
    private var closed = original?.closed ?: false

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.labeled(c.getString(R.string.jar_name), c.field(c.getString(R.string.jar_name_hint), name) { name = it }), top = 8)
        body.add(c.labeled(c.getString(R.string.jar_goal), c.field(c.getString(R.string.jar_goal_hint), goalText,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { goalText = it }.apply {
            keyListener = DigitsKeyListener.getInstance("0123456789,.")
        }), top = 14)
        body.add(c.labeled(c.getString(R.string.jar_date), c.dateRow(byDate, c.getString(R.string.jar_date_none),
            { byDate = it; refresh() }, if (byDate != null) ({ byDate = null; refresh() }) else null)), top = 14)
        body.add(c.text(c.getString(R.string.jar_date_hint), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.field_note_hint), note, lines = 2) { note = it }), top = 14)
        if (original != null) body.add(c.toggleRow(c.getString(R.string.jar_close), closed) { closed = it }, top = 12)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        return page(c.getString(if (original == null) R.string.jar_new else R.string.jar_edit), back = true, body = body)
    }

    private fun save() {
        val c = act
        if (name.isBlank()) { c.toast(R.string.jar_need_name); return }
        val goal = if (goalText.isBlank()) 0L else Money.parse(goalText) ?: run { c.toast(R.string.bad_amount); return }
        val j = (original ?: Jar(name = name)).copy(name = name.trim(), goalCents = goal, byDate = byDate, note = note.trim(), closed = closed)
        val saved = c.db.saveJar(j)
        c.changed()
        c.pop()
        if (original == null) c.push(JarScreen(c, saved))
    }
}
