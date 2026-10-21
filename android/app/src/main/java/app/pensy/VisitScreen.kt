package app.pensy

import android.view.View
import app.pensy.core.Visit
import app.pensy.core.VisitStatus
import app.pensy.core.Visits
import java.time.LocalDate
import java.time.LocalTime

/**
 * A visit or a test: to book (maybe by a date, from the prescription's
 * priority), booked (day, time, place), or done (with its report).
 */
class VisitScreen(
    act: MainActivity,
    private val id: Long? = null,
    folderId: Long? = null,
    private val docId: Long? = null,
    title: String = "",
    dueBy: LocalDate? = null
) : Screen(act) {

    private val original: Visit? = id?.let { act.db.visit(it) }
    private var title = original?.title ?: title
    private var status = original?.status ?: VisitStatus.TODO
    private var date: LocalDate? = original?.date
    private var time: LocalTime? = original?.time
    private var dueBy: LocalDate? = original?.dueBy ?: dueBy
    private var priority = original?.priority ?: ""
    private var doctor = original?.doctor ?: ""
    private var place = original?.place ?: ""
    private var remindBooked = original?.takeIf { it.status != VisitStatus.TODO }?.remindDays ?: 1
    private var remindTodo = original?.takeIf { it.status == VisitStatus.TODO }?.remindDays ?: 30
    private var note = original?.note ?: ""
    private var folder: Long? = original?.folderId ?: folderId ?: act.healthRoot()?.id

    override fun build(): View {
        val c = act
        val today = LocalDate.now()
        val body = c.vbox()

        body.add(c.labeled(c.getString(R.string.visit_title), c.field(c.getString(R.string.visit_title_hint), title) { title = it }), top = 12)
        body.add(c.labeled(c.getString(R.string.visit_status), c.chips(VisitStatus.entries.map { it to HFmt.statusLabel(c, it) }, status) {
            status = it
            if (it == VisitStatus.DONE && date == null) date = today
            refresh()
        }), top = 16)

        val box = c.formCard()
        when (status) {
            VisitStatus.TODO -> {
                box.add(c.labeled(c.getString(R.string.visit_priority), c.chips(listOf("", "U", "B", "D", "P").map {
                    it to (if (it.isEmpty()) c.getString(R.string.priority_none) else it)
                }, priority) { p ->
                    priority = p
                    Visits.priorityDays(p)?.let { dueBy = today.plusDays(it.toLong()) }
                    refresh()
                }))
                box.add(c.text(c.getString(R.string.priority_hint), 13f, c.col(R.color.ink2)), top = 6, start = 4)
                box.add(c.labeled(c.getString(R.string.visit_due_by), c.dateRow(dueBy, c.getString(R.string.field_date_none),
                    { dueBy = it; refresh() }, if (dueBy != null) ({ dueBy = null; refresh() }) else null)), top = 14)
                if (dueBy != null) {
                    box.add(c.labeled(c.getString(R.string.visit_remind), c.chips(listOf(7, 15, 30, 60).map {
                        it to c.resources.getQuantityString(R.plurals.days_before, it, it)
                    }, remindTodo) { remindTodo = it }), top = 14)
                }
            }
            VisitStatus.BOOKED, VisitStatus.DONE -> {
                val row = c.hbox()
                row.add(c.labeled(c.getString(R.string.visit_date), c.dateButton(date, c.getString(R.string.field_due_pick)) { date = it }), w = 0, weight = 1.3f)
                row.add(c.labeled(c.getString(R.string.visit_time), c.timeButton(time, c.getString(R.string.visit_time_none)) { time = it }), w = 0, weight = 1f, start = 10)
                box.add(row)
                if (status == VisitStatus.BOOKED) {
                    box.add(c.labeled(c.getString(R.string.visit_remind), c.chips(listOf(1, 2, 3, 7).map {
                        it to c.resources.getQuantityString(R.plurals.days_before, it, it)
                    }, remindBooked) { remindBooked = it }), top = 14)
                }
            }
        }
        body.add(box, top = 8)

        body.add(c.labeled(c.getString(R.string.visit_doctor), c.field(c.getString(R.string.visit_doctor_hint), doctor) { doctor = it }), top = 16)
        body.add(c.labeled(c.getString(R.string.visit_place), c.field(c.getString(R.string.visit_place_hint), place) { place = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.field_folder), c.folderPicker(folder) { folder = it; refresh() }), top = 14)
        body.add(c.labeled(c.getString(R.string.field_note), c.field(c.getString(R.string.field_note_hint), note, lines = 2) { note = it }), top = 14)

        // The report
        val linkedDoc = (original?.docId ?: docId)?.let { c.db.doc(it) }
        if (linkedDoc != null) {
            body.add(c.button(c.getString(R.string.visit_open_doc), Btn.GHOST, R.drawable.ic_doc) { c.push(DocScreen(c, linkedDoc.id)) }, top = 16)
        } else if (original != null && original.isDone(today)) {
            body.add(c.button(c.getString(R.string.visit_add_doc), Btn.SECONDARY, R.drawable.ic_doc) {
                Capture.choose(c, title = c.getString(R.string.visit_add_doc), folder = folder, visit = original.id)
            }, top = 16)
        }

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)

        val actions = ArrayList<Triple<Int, Int, () -> Unit>>()
        original?.let { v ->
            actions += Triple(R.drawable.ic_delete, R.string.delete) {
                Dialogs.confirm(c, R.string.visit_delete_title, v.title, R.string.delete) {
                    c.db.deleteVisit(v.id); c.changed(); c.pop()
                }
            }
        }
        return page(c.getString(if (original == null) R.string.visit_new else R.string.visit_edit), back = true, actions = actions, body = body)
    }

    private fun save() {
        val c = act
        val t = title.trim()
        if (t.isEmpty()) { c.toast(R.string.visit_need_title); return }
        if (status != VisitStatus.TODO && date == null) { c.toast(R.string.visit_need_date); return }
        val base = original ?: Visit(title = t, docId = docId)
        val remind = if (status == VisitStatus.TODO) remindTodo else remindBooked
        val changedWhen = base.status != status || base.date != date || base.dueBy != dueBy || base.remindDays != remind
        val v = base.copy(
            title = t, status = status,
            date = if (status == VisitStatus.TODO) null else date,
            time = if (status == VisitStatus.TODO) null else time,
            dueBy = if (status == VisitStatus.TODO) dueBy else base.dueBy,
            priority = if (status == VisitStatus.TODO) priority else base.priority,
            doctor = doctor.trim(), place = place.trim(), remindDays = remind,
            notified = if (changedWhen) 0 else base.notified,
            note = note.trim(), folderId = folder
        )
        c.db.saveVisit(v)
        c.changed()
        c.toast(R.string.saved)
        c.pop()
    }
}
