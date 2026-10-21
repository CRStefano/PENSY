package app.pensy

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.widget.LinearLayout
import app.pensy.core.Csv
import app.pensy.core.DoseStatus
import app.pensy.core.MedRule
import app.pensy.core.Medicine
import app.pensy.core.Meds
import app.pensy.core.StockMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A medicine: what, how much, when, until when, and how PENSY knows when
 * to order it. For an existing one the top card says the next dose and
 * how many are left, with "I bought a box".
 */
class MedicineScreen(act: MainActivity, private val id: Long? = null, folderId: Long? = null) : Screen(act) {

    private val original: Medicine? = id?.let { act.db.medicine(it) }
    private var name = original?.name ?: ""
    private var purpose = original?.purpose ?: ""
    private var amountText = HFmt.number(original?.amount ?: 1.0)
    private var unit = original?.unit ?: "compressa"
    private var everyDays = original?.everyDays ?: 1
    private val times = ArrayList(original?.times ?: listOf(LocalTime.of(8, 0)))

    /** How often, as picked on screen: day, ndays, nweeks, weekdays, month, once, need. */
    private var freq: String = original?.let { m ->
        when (val r = m.schedule) {
            is MedRule.Weekdays -> "weekdays"
            is MedRule.MonthDay, is MedRule.MonthWeekday -> "month"
            MedRule.Once -> "once"
            MedRule.Days -> when {
                m.everyDays <= 0 -> "need"
                m.everyDays == 1 -> "day"
                m.everyDays % 7 == 0 -> "nweeks"
                else -> "ndays"
            }
        }
    } ?: "day"
    private var nText = original?.let { m -> if (m.everyDays % 7 == 0 && m.everyDays >= 7) (m.everyDays / 7).toString() else m.everyDays.takeIf { it > 1 }?.toString() } ?: ""
    private val weekdays: MutableSet<Int> = (original?.schedule as? MedRule.Weekdays)?.days?.toMutableSet()
        ?: mutableSetOf((original?.start ?: LocalDate.now()).dayOfWeek.value)
    private var byWeekday = original?.schedule is MedRule.MonthWeekday
    private var monthDayText = ((original?.schedule as? MedRule.MonthDay)?.day ?: (original?.start ?: LocalDate.now()).dayOfMonth).toString()
    private var nth = (original?.schedule as? MedRule.MonthWeekday)?.nth ?: 1
    private var dow = (original?.schedule as? MedRule.MonthWeekday)?.dow ?: (original?.start ?: LocalDate.now()).dayOfWeek.value
    private var monthEvery = when (val r = original?.schedule) {
        is MedRule.MonthDay -> r.every
        is MedRule.MonthWeekday -> r.every
        else -> 1
    }
    private var reserve = original?.reserve ?: 1
    private var boxesText = original?.takeIf { it.stockMode == StockMode.BOXES }?.let { m ->
        Meds.boxesLeft(m, LocalDateTime.now())?.let { HFmt.number(Math.round(it * 10) / 10.0) }
    } ?: ""
    private var start: LocalDate = original?.start ?: LocalDate.now()
    private var until: LocalDate? = original?.until
    private var remind = original?.remind ?: true
    private var stockMode = original?.stockMode ?: StockMode.NONE
    private var haveText = original?.let { m -> Meds.leftNow(m, LocalDateTime.now())?.let { HFmt.number(Math.floor(it)) } } ?: ""
    private var haveTouched = false
    private var packText = original?.pack?.takeIf { it > 0 }?.toString() ?: ""
    private var lead = original?.leadDays ?: 7
    private var orderEveryText = original?.orderEvery?.takeIf { it > 0 }?.toString() ?: ""
    private var lastOrder: LocalDate? = original?.lastOrder
    private var note = original?.note ?: ""
    private var folder: Long? = original?.folderId ?: folderId ?: act.healthRoot()?.id

    override fun build(): View {
        val c = act
        val body = c.vbox()
        val now = LocalDateTime.now()

        original?.let { m -> body.add(summary(c.db.medicine(m.id) ?: m, now), top = 4, bottom = 6) }

        // What
        body.add(c.labeled(c.getString(R.string.med_name), c.field(c.getString(R.string.med_name_hint), name) { name = it }), top = 12)
        body.add(c.labeled(c.getString(R.string.med_purpose), c.field(c.getString(R.string.med_purpose_hint), purpose) { purpose = it }), top = 14)

        // How much
        val amount = c.field("1", amountText, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { amountText = it }.apply {
            keyListener = DigitsKeyListener.getInstance("0123456789,.")
        }
        val amountRow = c.hbox()
        amountRow.add(amount, w = c.dp(84))
        amountRow.add(c.chips(MED_UNITS.map { it to HFmt.unit(c, it, 2.0) }, unit) { unit = it }, w = 0, weight = 1f, start = 10)
        body.add(c.labeled(c.getString(R.string.med_amount), amountRow), top = 14)

        // When
        body.add(c.labeled(c.getString(R.string.med_every), c.chips(listOf(
            "day" to c.getString(R.string.freq_day),
            "ndays" to c.getString(R.string.freq_ndays),
            "nweeks" to c.getString(R.string.freq_nweeks),
            "weekdays" to c.getString(R.string.freq_weekdays),
            "month" to c.getString(R.string.freq_month),
            "once" to c.getString(R.string.freq_once),
            "need" to c.getString(R.string.freq_need)
        ), freq) { freq = it; refresh() }), top = 16)
        when (freq) {
            "ndays" -> body.add(c.labeled(c.getString(R.string.freq_ndays_n), numberField(nText.ifBlank { "2" }, decimal = false) { nText = it }), top = 10)
            "nweeks" -> {
                body.add(c.labeled(c.getString(R.string.freq_nweeks_n), numberField(nText.ifBlank { "4" }, decimal = false) { nText = it }), top = 10)
                body.add(c.text(c.getString(R.string.freq_nweeks_hint, start.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())),
                    13f, c.col(R.color.ink2)), top = 6, start = 4)
            }
            "weekdays" -> body.add(c.labeled(c.getString(R.string.freq_which_days), weekdayRow()), top = 10)
            "month" -> {
                val box = c.formCard()
                box.add(c.chips(listOf(false to c.getString(R.string.freq_month_day), true to c.getString(R.string.freq_month_weekday)), byWeekday) {
                    byWeekday = it; refresh()
                })
                if (!byWeekday) {
                    box.add(c.labeled(c.getString(R.string.freq_day_of_month), numberField(monthDayText, decimal = false) { monthDayText = it }), top = 12)
                } else {
                    val words = c.getString(R.string.med_nth_m).split('|')
                    box.add(c.chips(listOf(1, 2, 3, 4, -1).map { it to Fmt.cap(words.getOrElse(if (it < 0) 4 else it - 1) { "" }.substringAfter(' ').substringAfter('\'')) }, nth) {
                        nth = it
                    }, top = 12)
                    box.add(c.chips((1..7).map { it to Fmt.cap(java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())) }, dow) {
                        dow = it
                    }, top = 8)
                }
                box.add(c.labeled(c.getString(R.string.freq_month_every), c.chips(listOf(1, 2, 3, 6, 12).map {
                    it to (if (it == 1) c.getString(R.string.freq_every_month) else c.getString(R.string.freq_every_n_months, it))
                }, monthEvery) { monthEvery = it }), top = 12)
                body.add(box, top = 10)
            }
        }
        if (freq != "need") {
            val tbox = c.vbox()
            val trow = c.hbox()
            for (t in times.sorted()) {
                val chip = c.text(HFmt.time(t) + "  ✕", 15f, c.col(R.color.accent_ink), MEDIUM).apply {
                    background = c.ripple(c.rounded(c.col(R.color.accent_soft), 18), 18)
                    setPadding(c.dp(14), c.dp(9), c.dp(12), c.dp(9))
                    contentDescription = HFmt.time(t)
                    setOnClickListener { times.remove(t); refresh() }
                }
                trow.add(chip, w = WRAP, end = 8)
            }
            val scroller = android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(trow) }
            tbox.add(scroller)
            tbox.add(c.button(c.getString(R.string.med_add_time), Btn.GHOST, R.drawable.ic_time) {
                val base = times.maxOrNull()?.plusHours(4) ?: LocalTime.of(8, 0)
                TimePickerDialog(c, c.dialogTheme(), { _, h, m ->
                    val t = LocalTime.of(h, m)
                    if (t !in times) times += t
                    refresh()
                }, base.hour, base.minute, true).show()
            }, w = WRAP, top = 8)
            if (times.isEmpty()) tbox.add(c.text(c.getString(R.string.med_no_time_hint), 13f, c.col(R.color.ink2)), top = 8, start = 4)
            body.add(c.labeled(c.getString(R.string.med_times_optional), tbox), top = 14)
        }
        val dates = c.hbox()
        dates.add(c.labeled(c.getString(if (freq == "once") R.string.med_on_day else R.string.med_start), c.dateButton(start, "") { start = it; refresh() }), w = 0, weight = 1f)
        if (freq != "once") dates.add(c.labeled(c.getString(R.string.med_until), c.dateRow(until, c.getString(R.string.med_no_end), { until = it; refresh() },
            if (until != null) ({ until = null; refresh() }) else null)), w = 0, weight = 1f, start = 10)
        body.add(dates, top = 14)
        if (freq != "need") {
            body.add(c.toggleRow(c.getString(if (times.isEmpty()) R.string.med_remind_anytime else R.string.med_remind), remind) { remind = it; refresh() }, top = 12)
            if (remind && times.isNotEmpty() && !Doses.canExact(c)) body.add(exactHint(), top = 6)
        }

        // Stock
        body.add(c.labeled(c.getString(R.string.med_stock), c.chips(listOf(
            StockMode.NONE to c.getString(R.string.stock_none),
            StockMode.BOXES to c.getString(R.string.stock_boxes),
            StockMode.COUNT to c.getString(R.string.stock_count),
            StockMode.INTERVAL to c.getString(R.string.stock_interval)
        ), stockMode) { stockMode = it; refresh() }), top = 18)
        when (stockMode) {
            StockMode.BOXES -> {
                val box = c.formCard()
                val row = c.hbox()
                row.add(c.labeled(c.getString(R.string.med_boxes_now), numberField(boxesText, decimal = true) { boxesText = it; haveTouched = true }),
                    w = 0, weight = 1f)
                row.add(c.labeled(c.getString(R.string.med_per_box, Fmt.cap(HFmt.unit(c, unit, 2.0))), numberField(packText, decimal = false) { packText = it }),
                    w = 0, weight = 1f, start = 10)
                box.add(row)
                box.add(c.labeled(c.getString(R.string.med_reserve), c.chips(listOf(0, 1, 2, 3, 4, 5).map {
                    it to c.resources.getQuantityString(R.plurals.boxes_n, it, it)
                }, reserve) { reserve = it; refresh() }), top = 14)
                box.add(c.text(if (reserve > 0) c.resources.getQuantityString(R.plurals.med_reserve_hint, reserve, reserve)
                    else c.getString(R.string.med_reserve_none), 13f, c.col(R.color.ink2)), top = 6, start = 4)
                body.add(box, top = 8)
            }
            StockMode.COUNT -> {
                val box = c.formCard()
                val row = c.hbox()
                row.add(c.labeled(c.getString(R.string.med_have_now), numberField(haveText, decimal = true) { haveText = it; haveTouched = true }),
                    w = 0, weight = 1f)
                row.add(c.labeled(c.getString(R.string.med_pack), numberField(packText, decimal = false) { packText = it }), w = 0, weight = 1f, start = 10)
                box.add(row)
                box.add(c.labeled(c.getString(R.string.med_lead), c.chips(listOf(3, 5, 7, 10, 14).map {
                    it to c.resources.getQuantityString(R.plurals.days_before, it, it)
                }, lead) { lead = it }), top = 14)
                body.add(box, top = 8)
            }
            StockMode.INTERVAL -> {
                val box = c.formCard()
                box.add(c.labeled(c.getString(R.string.med_order_every), numberField(orderEveryText, decimal = false) { orderEveryText = it }))
                box.add(c.labeled(c.getString(R.string.med_last_order), c.dateRow(lastOrder, c.getString(R.string.field_date_none), { lastOrder = it; refresh() }, null)), top = 14)
                body.add(box, top = 8)
            }
            StockMode.NONE -> {}
        }

        body.add(c.labeled(c.getString(R.string.field_folder), folderButton()), top = 18)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.med_note_hint), note, lines = 2) { note = it }), top = 14)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)

        val actions = ArrayList<Triple<Int, Int, () -> Unit>>()
        original?.let { m ->
            body.add(c.button(c.getString(if (m.active) R.string.med_stop else R.string.med_resume), Btn.SECONDARY) {
                c.db.saveMedicine(m.copy(active = !m.active, until = if (m.active) LocalDate.now() else null))
                Doses.schedule(c); Emergency.post(c); c.changed(); c.pop()
            }, top = 12)
            actions += Triple(R.drawable.ic_share, R.string.dose_log_csv) { exportLog(m) }
            actions += Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.med_delete_title, c.getString(R.string.med_delete_msg, m.name), R.string.delete) {
                    c.db.deleteMedicine(m.id); Doses.schedule(c); Emergency.post(c); c.changed(); c.pop()
                }
            }
        }
        return page(c.getString(if (original == null) R.string.med_new else R.string.med_edit), back = true, actions = actions, body = body)
    }

    private fun summary(m: Medicine, now: LocalDateTime): View {
        val c = act
        val box = c.card(18)
        box.add(c.text(m.name, 20f, face = BOLD))
        box.add(c.text(HFmt.schedule(c, m), 14f, c.col(R.color.ink2)), top = 2)
        val next = if (m.active) Meds.nextFor(m, now) else null
        if (next != null) {
            val day = Fmt.relative(c, now.toLocalDate(), next.toLocalDate())
            box.add(c.text(c.getString(R.string.med_next_dose, day + " " + HFmt.time(next.toLocalTime())), 15f, face = MEDIUM), top = 10)
        } else if (m.active && !m.asNeeded) Meds.nextDay(m, now.toLocalDate())?.let { d ->
            box.add(c.text(c.getString(R.string.med_next_day, Fmt.relative(c, now.toLocalDate(), d) + " · " + Fmt.dayLong(d)), 15f, face = MEDIUM), top = 10)
        }
        HFmt.stockLine(c, m, now)?.let { (line, color) -> box.add(c.text(line, 14f, color, MEDIUM), top = 6) }
        // How it went in the last 30 days
        if (!m.asNeeded) {
            val a = Meds.adherence(m, now.toLocalDate().minusDays(29), now)
            if (a.due > 0 && a.taken + a.skipped > 0) {
                val pct = a.percent?.let { " · $it%" } ?: ""
                box.add(c.text(c.getString(R.string.adherence_line, a.taken, a.skipped, a.unmarked) + pct, 14f, c.col(R.color.ink2)), top = 6)
            } else if (a.due > 0) {
                box.add(c.text(c.getString(R.string.adherence_none), 13f, c.col(R.color.ink3)), top = 6)
            }
        }
        // Taken when needed: "Presa ora", and today's
        if (m.active && m.asNeeded) {
            val today = Meds.extraOn(m, now.toLocalDate())
            if (today.isNotEmpty()) box.add(c.text(c.getString(R.string.dose_extra_today, today.joinToString(", ") { HFmt.time(it.at.toLocalTime()) }),
                14f, c.col(R.color.ink2)), top = 6)
            box.add(c.button(c.getString(R.string.dose_take_now), Btn.PRIMARY, R.drawable.ic_check) {
                c.db.logDose(m.id, now.toLocalDate(), null, DoseStatus.TAKEN, java.time.LocalDateTime.now())
                c.changed()
                c.toast(c.getString(R.string.dose_taken_at, HFmt.time(java.time.LocalTime.now())))
                refresh()
            }, top = 12)
        }
        m.until?.let { box.add(c.text(Fmt.cap(c.getString(R.string.med_last_day, Fmt.date(it))), 14f, c.col(R.color.ink2)), top = 6) }
        if (m.active && m.counted && m.pack > 0) {
            box.add(c.button(c.getString(R.string.med_bought) + " (+" + m.pack + ")", Btn.SECONDARY, R.drawable.ic_cart) {
                val today = LocalDate.now()
                val base = Meds.stockOn(m, today) ?: 0.0
                c.db.saveMedicine(m.copy(stock = base + m.pack, stockDate = today, orderNotified = null))
                c.changed()
                val left = c.db.medicine(m.id)?.let { Meds.leftNow(it, LocalDateTime.now()) } ?: 0.0
                c.toast(c.getString(R.string.med_bought_done, HFmt.number(Math.floor(left))))
                haveText = HFmt.number(Math.floor(left)); haveTouched = false
                boxesText = HFmt.number(Math.round(left / m.pack * 10) / 10.0)
                refresh()
            }, top = 12)
        }
        if (m.active && m.stockMode == StockMode.INTERVAL) {
            box.add(c.button(c.getString(R.string.med_ordered), Btn.SECONDARY, R.drawable.ic_cart) {
                c.db.saveMedicine(m.copy(lastOrder = LocalDate.now(), orderNotified = null))
                lastOrder = LocalDate.now()
                c.changed(); c.toast(R.string.saved); refresh()
            }, top = 12)
        }
        return box
    }

    /** Every marked dose, for a spreadsheet. */
    private fun exportLog(m: Medicine) {
        val c = act
        val log = c.db.allDoseLogs(m.id)
        if (log.isEmpty()) { c.toast(R.string.dose_log_empty); return }
        val rows = log.reversed().map { l ->
            listOf(l.day.toString(), l.slot?.let { HFmt.time(it) } ?: c.getString(R.string.dose_extra),
                c.getString(if (l.status == DoseStatus.TAKEN) R.string.dose_taken else R.string.dose_skipped),
                l.at.toLocalDate().toString() + " " + HFmt.time(l.at.toLocalTime()))
        }
        val csv = Csv.table(listOf(c.getString(R.string.csv_date), c.getString(R.string.csv_dose_time), c.getString(R.string.csv_status),
            c.getString(R.string.csv_marked_at)), rows)
        val f = Share.newFile(c, "PENSY " + m.name + " " + LocalDate.now() + ".csv")
        f.writeText(csv)
        Share.send(c, f, "text/csv", m.name)
    }

    /** Seven round chips, Monday first: tap to pick the days. */
    private fun weekdayRow(): View {
        val c = act
        val row = c.hbox()
        for (d in 1..7) {
            val on = d in weekdays
            val chip = c.text(java.time.DayOfWeek.of(d).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()).take(3), 13.5f,
                c.col(if (on) R.color.on_accent else R.color.ink), MEDIUM).apply {
                gravity = android.view.Gravity.CENTER
                background = c.ripple(c.rounded(c.col(if (on) R.color.accent else R.color.surface2), 20), 20)
                setOnClickListener { if (!weekdays.remove(d)) weekdays += d; refresh() }
            }
            row.add(chip, w = 0, h = c.dp(40), weight = 1f, end = if (d < 7) 6 else 0)
        }
        return row
    }

    private fun numberField(value: String, decimal: Boolean, onChange: (String) -> Unit): View =
        act.field("", value, InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0), onChange = onChange).apply {
            keyListener = DigitsKeyListener.getInstance(if (decimal) "0123456789,." else "0123456789")
        }

    private fun exactHint(): View {
        val c = act
        val box = c.card(14, c.col(R.color.warn_soft))
        box.add(c.text(c.getString(R.string.exact_needed), 14f, c.col(R.color.ink)))
        box.add(c.button(c.getString(R.string.exact_allow), Btn.SECONDARY) {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.packageName)))
                } catch (e: Exception) {
                    c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
                }
            }
        }, w = LinearLayout.LayoutParams.WRAP_CONTENT, top = 10)
        return box
    }

    private fun folderButton(): View = act.folderPicker(folder) { folder = it; refresh() }

    private fun save() {
        val c = act
        val n = name.trim()
        if (n.isEmpty()) { c.toast(R.string.med_need_name); return }
        val amount = amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        // How often → the stored schedule
        var rule = ""
        var every = 1
        var end = until?.takeIf { !it.isBefore(start) }
        when (freq) {
            "ndays" -> { every = nText.trim().ifBlank { "2" }.toIntOrNull()?.takeIf { it in 1..365 } ?: run { c.toast(R.string.freq_bad_number); return } }
            "nweeks" -> { every = 7 * (nText.trim().ifBlank { "4" }.toIntOrNull()?.takeIf { it in 1..52 } ?: run { c.toast(R.string.freq_bad_number); return }) }
            "weekdays" -> { if (weekdays.isEmpty()) { c.toast(R.string.freq_need_day); return }; rule = MedRule.Weekdays(weekdays.toSet()).code() }
            "month" -> rule = if (byWeekday) MedRule.MonthWeekday(nth, dow, monthEvery).code() else {
                val d = monthDayText.trim().toIntOrNull()?.takeIf { it in 1..31 } ?: run { c.toast(R.string.freq_bad_day); return }
                MedRule.MonthDay(d, monthEvery).code()
            }
            "once" -> { rule = "once"; end = start }
            "need" -> every = 0
        }
        val now = LocalDateTime.now()
        val pack = packText.toIntOrNull() ?: 0
        if (stockMode == StockMode.BOXES && pack <= 0) { c.toast(R.string.med_need_pack); return }
        var m = (original ?: Medicine(name = n, start = start)).copy(
            name = n, purpose = purpose.trim(), amount = amount, unit = unit, times = times.sorted().distinct(),
            everyDays = every, rule = rule, start = start, until = end,
            remind = remind && freq != "need", stockMode = stockMode,
            pack = pack, leadDays = lead, reserve = reserve,
            orderEvery = orderEveryText.toIntOrNull() ?: 0, lastOrder = lastOrder,
            note = note.trim(), folderId = folder
        )
        val counted = original != null && original.stock != null && original.stockMode == stockMode
        if (stockMode == StockMode.COUNT && (haveTouched || !counted)) {
            val have = haveText.replace(',', '.').toDoubleOrNull()
            m = if (have == null) m.copy(stock = null, stockDate = null)
            else m.copy(stock = Meds.stockAtStartOfDay(m, have, now), stockDate = now.toLocalDate(), orderNotified = null)
        }
        if (stockMode == StockMode.BOXES && (haveTouched || !counted)) {
            val boxes = boxesText.replace(',', '.').toDoubleOrNull()
            m = if (boxes == null) m.copy(stock = null, stockDate = null)
            else m.copy(stock = Meds.stockAtStartOfDay(m, boxes * pack, now), stockDate = now.toLocalDate(), orderNotified = null)
        }
        if (stockMode == StockMode.INTERVAL && m.lastOrder == null) m = m.copy(lastOrder = LocalDate.now())
        if (original != null && Meds.reorderOn(original, now.toLocalDate()) != Meds.reorderOn(m, now.toLocalDate())) m = m.copy(orderNotified = null)
        c.db.saveMedicine(m)
        Doses.schedule(c); Emergency.post(c)
        c.changed()
        c.toast(R.string.saved)
        c.pop()
    }
}
