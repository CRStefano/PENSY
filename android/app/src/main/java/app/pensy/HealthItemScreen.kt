package app.pensy

import android.view.View
import app.pensy.core.HealthItem
import app.pensy.core.HealthKind
import java.time.LocalDate

/** A condition, an allergy, an operation or a vaccine. */
class HealthItemScreen(act: MainActivity, private val id: Long? = null, folderId: Long? = null) : Screen(act) {

    private val original: HealthItem? = id?.let { act.db.healthItem(it) }
    private var kind = original?.kind ?: HealthKind.CONDITION
    private var name = original?.name ?: ""
    private var date: LocalDate? = original?.date
    private var fromBirth = original?.fromBirth ?: false
    private var active = original?.active ?: true
    private var note = original?.note ?: ""
    private var nextDue: LocalDate? = original?.nextDue
    private var folder: Long? = original?.folderId ?: folderId ?: act.healthRoot()?.id

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.labeled(c.getString(R.string.field_type), c.chips(HealthKind.entries.map { it to HFmt.kindLabel(c, it) }, kind) {
            kind = it; refresh()
        }), top = 12)
        val hint = when (kind) {
            HealthKind.CONDITION -> R.string.item_name_hint_condition
            HealthKind.ALLERGY -> R.string.item_name_hint_allergy
            HealthKind.SURGERY -> R.string.item_name_hint_surgery
            HealthKind.VACCINE -> R.string.item_name_hint_vaccine
        }
        body.add(c.labeled(c.getString(R.string.item_name), c.field(c.getString(hint), name) { name = it }), top = 16)
        val dateLabel = when (kind) {
            HealthKind.CONDITION, HealthKind.ALLERGY -> R.string.item_date_condition
            HealthKind.SURGERY -> R.string.item_date_surgery
            HealthKind.VACCINE -> R.string.item_date_other
        }
        val birthPossible = kind == HealthKind.CONDITION || kind == HealthKind.ALLERGY
        if (!(birthPossible && fromBirth)) body.add(c.labeled(c.getString(dateLabel), c.dateRow(date, c.getString(R.string.field_date_none),
            { date = it; refresh() }, if (date != null) ({ date = null; refresh() }) else null)), top = 14)
        if (birthPossible) body.add(c.toggleRow(c.getString(R.string.from_birth), fromBirth) { fromBirth = it; refresh() }, top = 8)
        if (kind == HealthKind.SURGERY) body.add(c.text(c.getString(R.string.item_surgery_hint), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        if (kind == HealthKind.CONDITION) body.add(c.toggleRow(c.getString(R.string.item_active), active) { active = it }, top = 12)
        if (kind == HealthKind.VACCINE) {
            body.add(c.labeled(c.getString(R.string.booster), c.dateRow(nextDue, c.getString(R.string.booster_none),
                { nextDue = it; refresh() }, if (nextDue != null) ({ nextDue = null; refresh() }) else null)), top = 14)
            val base = date ?: LocalDate.now()
            body.add(c.chips(listOf(1, 3, 5, 10).map { it to c.resources.getQuantityString(R.plurals.booster_in_years, it, it) }, -1) { y ->
                nextDue = base.plusYears(y.toLong()); refresh()
            }, top = 8)
            body.add(c.text(c.getString(R.string.booster_hint), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        }
        body.add(c.labeled(c.getString(R.string.field_folder), c.folderPicker(folder) { folder = it; refresh() }), top = 14)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.field_note_hint), note, lines = 2) { note = it }), top = 14)

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)

        val actions = ArrayList<Triple<Int, Int, () -> Unit>>()
        original?.let { h ->
            actions += Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.item_delete_title, h.name, R.string.delete) {
                    c.db.deleteHealthItem(h.id); c.changed(); Emergency.post(c); c.pop()
                }
            }
        }
        return page(c.getString(if (original == null) R.string.item_new else R.string.item_edit), back = true, actions = actions, body = body)
    }

    private fun save() {
        val c = act
        val n = name.trim()
        if (n.isEmpty()) { c.toast(R.string.item_need_name); return }
        val due = if (kind == HealthKind.VACCINE) nextDue else null
        val birth = fromBirth && (kind == HealthKind.CONDITION || kind == HealthKind.ALLERGY)
        val h = (original ?: HealthItem(name = n)).copy(
            kind = kind, name = n, date = if (birth) null else date, fromBirth = birth,
            active = if (kind == HealthKind.CONDITION) active else true,
            note = note.trim(), folderId = folder, nextDue = due,
            notified = if (original?.nextDue != due || original?.date != date) 0 else original?.notified ?: 0
        )
        c.db.saveHealthItem(h)
        Emergency.post(c)
        c.changed()
        c.toast(R.string.saved)
        c.pop()
    }
}
