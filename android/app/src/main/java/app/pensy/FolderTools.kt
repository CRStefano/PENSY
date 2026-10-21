package app.pensy

import android.text.InputType
import android.view.View
import app.pensy.core.CarRules
import app.pensy.core.Deadline
import app.pensy.core.DocType
import app.pensy.core.Folder
import app.pensy.core.FolderKind
import app.pensy.core.HealthItem
import app.pensy.core.HealthKind
import app.pensy.core.Measure
import app.pensy.core.MeasureKind
import app.pensy.core.Medicine
import app.pensy.core.Money
import app.pensy.core.Sorter
import app.pensy.core.Visit
import app.pensy.core.VisitStatus
import app.pensy.core.parseFields
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * What a folder can do besides holding documents:
 *  - become one PDF (cover, what is to be done, index, then every
 *    document with its photos or its text), to print or hand over;
 *  - for a car: the yearly dates (revisione, insurance, road tax,
 *    service, tyres) in one go;
 *  - for a pet: vaccine and booster, antiparasitic every month, the
 *    yearly check-up, its weight.
 */

/** The whole folder (and its sub-folders) as one PDF, shared through the share sheet. */
fun MainActivity.exportFolderPdf(folderId: Long) {
    val folders = db.folders()
    val f = folders.firstOrNull { it.id == folderId } ?: return
    val subtree = Sorter.subtree(folders, folderId)
    val path = Sorter.path(folders, folderId).joinToString(" › ") { it.name }
    val docs = db.docs().filter { it.folderId in subtree }.sortedByDescending { it.shownDate }
    if (docs.isEmpty()) { toast(R.string.folder_pdf_empty); return }
    val todo = db.deadlines().filter { !it.paid && it.folderId in subtree }.sortedBy { it.due }
    val pages = docs.associate { it.id to db.pages(it.id).map { p -> p.name } }
    val who = profileLabel(db.current())
    val today = LocalDate.now()
    showBusy(R.string.making_pdf)
    Thread {
        val out = try {
            val w = PdfWriter(this, "PENSY · $path · " + Fmt.date(today))
            w.title(path)
            w.subtitle(who + " · " + Fmt.date(today) + " · " + resources.getQuantityString(R.plurals.docs_count, docs.size, docs.size))
            if (f.note.isNotBlank()) { w.heading(getString(R.string.folder_summary)); w.text(f.note) }
            if (todo.isNotEmpty()) {
                w.heading(getString(R.string.folder_todo))
                for (d in todo) w.bullet(
                    (if (d.noDue) getString(R.string.status_no_due) else Fmt.date(d.due)) + " — " + Fmt.title(this, d) +
                        (if (d.amountCents > 0) " · " + Money.format(d.amountCents) else "")
                )
            }
            w.heading(getString(R.string.folder_pdf_index))
            docs.forEachIndexed { i, d ->
                w.bullet("${i + 1}. " + Fmt.date(d.shownDate) + " — " + Fmt.title(this, d.title, d.type, d.issuer) + " (" + Fmt.type(this, d.type) + ")")
            }
            docs.forEachIndexed { i, d ->
                w.heading("${i + 1}. " + Fmt.title(this, d.title, d.type, d.issuer))
                val sub = listOf(Fmt.type(this, d.type), d.issuer, Fmt.date(d.shownDate),
                    Sorter.path(folders, d.folderId).lastOrNull()?.name ?: "").filter { it.isNotBlank() }.distinct()
                w.text(sub.joinToString(" · "), 10f, color = 0xFF66646C.toInt())
                val fields = parseFields(d.fields)
                fields["nre"]?.let { w.text(getString(R.string.nre_label) + ": " + Fmt.nre(it), 11f, before = 4f) }
                fields["codice_avviso"]?.let { w.text(getString(R.string.notice_code) + ": " + it.chunked(4).joinToString(" "), 11f, before = 4f) }
                fields["prezzo"]?.toLongOrNull()?.let { w.text(getString(R.string.field_price) + ": " + Money.format(it), 11f, before = 4f) }
                if (d.note.isNotBlank()) w.text(d.note, 11f, before = 4f)
                val names = pages[d.id].orEmpty()
                if (names.isNotEmpty()) {
                    for (n in names) {
                        val bmp = Pages.load(this, n, 1700) ?: continue
                        w.image(bmp)
                        bmp.recycle()
                    }
                } else if (d.text.isNotBlank()) {
                    w.text(d.text.take(6000), 9f, color = 0xFF3A3940.toInt(), before = 6f)
                }
            }
            val file = Share.newFile(this, "PENSY $path $today.pdf".replace(" › ", " - "))
            w.finish(file)
            file
        } catch (t: Throwable) { null }
        runOnUiThread {
            hideBusy()
            if (out == null) toast(R.string.pdf_error) else Share.send(this, out, "application/pdf", path)
        }
    }.start()
}

/** A folder in Auto (or Auto itself): ask the dates of a car and create its yearly reminders. */
class CarSetupScreen(act: MainActivity, private val folderId: Long) : Screen(act) {

    private val folder: Folder? = act.db.folder(folderId)
    private val isTop = folder?.parentId == null
    private var name = if (isTop) "" else folder?.name ?: ""
    private var registered: LocalDate? = null
    private var lastRevision: LocalDate? = null
    private var insurance: LocalDate? = null
    private var insuranceText = ""
    private var roadTax: LocalDate? = null
    private var roadTaxText = ""
    private var service: LocalDate? = null
    private var tyres = false

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.text(c.getString(R.string.car_intro), 14f, c.col(R.color.ink2)), top = 4, start = 4)
        body.add(c.labeled(c.getString(R.string.car_name), c.field(c.getString(R.string.car_name_hint), name) { name = it }), top = 14)
        if (isTop) body.add(c.text(c.getString(R.string.car_subfolder), 13f, c.col(R.color.ink2)), top = 4, start = 4)
        fun date(label: Int, v: LocalDate?, set: (LocalDate?) -> Unit) =
            body.add(c.labeled(c.getString(label), c.dateRow(v, c.getString(R.string.field_date_none), { set(it); refresh() },
                if (v != null) ({ set(null); refresh() }) else null)), top = 14)
        date(R.string.car_registered, registered) { registered = it }
        date(R.string.car_last_revision, lastRevision) { lastRevision = it }
        body.add(c.text(c.getString(R.string.car_revision_hint), 13f, c.col(R.color.ink2)), top = 4, start = 4)
        date(R.string.car_insurance, insurance) { insurance = it }
        if (insurance != null) body.add(amount(insuranceText) { insuranceText = it }, top = 6)
        date(R.string.car_road_tax, roadTax) { roadTax = it }
        if (roadTax != null) body.add(amount(roadTaxText) { roadTaxText = it }, top = 6)
        date(R.string.car_service, service) { service = it }
        body.add(c.toggleRow(c.getString(R.string.car_tyres), tyres) { tyres = it }, top = 12)
        body.add(c.text(c.getString(R.string.car_tyres_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.car_create), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        return page(c.getString(R.string.car_title), back = true, body = body)
    }

    private fun amount(v: String, onChange: (String) -> Unit): View =
        act.field(act.getString(R.string.car_amount_hint), v, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, onChange = onChange)

    private fun save() {
        val c = act
        val db = c.db
        val today = LocalDate.now()
        val n = name.trim()
        if (registered == null && lastRevision == null && insurance == null && roadTax == null && service == null && !tyres) {
            c.toast(R.string.car_need_something); return
        }
        // At the top of Auto: a sub-folder for this car.
        val target = if (isTop && n.isNotEmpty()) db.addFolder(folderId, n) else folderId
        val label = n.ifEmpty { folder?.name ?: "" }
        var count = 0
        fun add(type: DocType, titleRes: Int, due: LocalDate, every: Int, remind: Int, amount: Long = 0) {
            db.saveDeadline(Deadline(type = type, title = c.getString(titleRes, label).trim(), amountCents = amount, due = due,
                everyMonths = every, remindDays = remind, folderId = target))
            count++
        }
        val reg = registered
        if (reg != null || lastRevision != null) {
            add(DocType.ALTRO, R.string.car_t_revision, CarRules.nextRevision(reg ?: lastRevision!!.minusYears(4), lastRevision, today), 24, 30)
        }
        insurance?.let { add(DocType.POLIZZA, R.string.car_t_insurance, CarRules.next(it.minusYears(1), today), 12, 30, Money.parse(insuranceText) ?: 0) }
        roadTax?.let { add(DocType.BOLLO_AUTO, R.string.car_t_road_tax, CarRules.next(it.minusYears(1), today), 12, 10, Money.parse(roadTaxText) ?: 0) }
        service?.let { add(DocType.ALTRO, R.string.car_t_service, CarRules.next(it, today), 12, 15) }
        if (tyres) {
            add(DocType.ALTRO, R.string.car_t_winter, CarRules.winterTyres(today), 12, 30)
            add(DocType.ALTRO, R.string.car_t_summer, CarRules.summerTyres(today), 12, 30)
        }
        c.changed()
        c.toast(c.resources.getQuantityString(R.plurals.car_created, count, count))
        c.pop()
        if (target != folderId) c.push(FolderScreen(c, target))
    }
}

/** A folder in Animali (or Animali itself): vaccine, antiparasitic, yearly visit and weight of a pet. */
class PetSetupScreen(act: MainActivity, private val folderId: Long) : Screen(act) {

    private val folder: Folder? = act.db.folder(folderId)
    private val isTop = folder?.parentId == null
    private var name = if (isTop) "" else folder?.name ?: ""
    private var species = "cane"
    private var vaccine: LocalDate? = null
    private var boosterYears = 1
    private var antiparasitic = true
    private var antiEvery = 30
    private var lastVisit: LocalDate? = null
    private var weightText = ""

    override fun build(): View {
        val c = act
        val body = c.vbox()
        body.add(c.text(c.getString(R.string.pet_intro), 14f, c.col(R.color.ink2)), top = 4, start = 4)
        body.add(c.labeled(c.getString(R.string.pet_name), c.field(c.getString(R.string.pet_name_hint), name) { name = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.pet_species), c.chips(listOf(
            "cane" to c.getString(R.string.pet_dog), "gatto" to c.getString(R.string.pet_cat), "altro" to c.getString(R.string.pet_other)
        ), species) { species = it }), top = 14)
        body.add(c.labeled(c.getString(R.string.pet_vaccine), c.dateRow(vaccine, c.getString(R.string.field_date_none),
            { vaccine = it; refresh() }, if (vaccine != null) ({ vaccine = null; refresh() }) else null)), top = 14)
        if (vaccine != null) body.add(c.chips(listOf(1, 2, 3).map { it to c.resources.getQuantityString(R.plurals.booster_in_years, it, it) },
            boosterYears) { boosterYears = it }, top = 8)
        body.add(c.toggleRow(c.getString(R.string.pet_antiparasitic), antiparasitic) { antiparasitic = it; refresh() }, top = 12)
        if (antiparasitic) body.add(c.chips(listOf(30, 60, 90).map { it to HFmt.every(c, it) }, antiEvery) { antiEvery = it }, top = 6)
        body.add(c.labeled(c.getString(R.string.pet_last_visit), c.dateRow(lastVisit, c.getString(R.string.field_date_none),
            { lastVisit = it; refresh() }, if (lastVisit != null) ({ lastVisit = null; refresh() }) else null)), top = 14)
        body.add(c.labeled(c.getString(R.string.pet_weight), c.field(c.getString(R.string.m_weight_hint), weightText,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { weightText = it }), top = 14)
        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.car_create), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)
        return page(c.getString(R.string.pet_title), back = true, body = body)
    }

    private fun save() {
        val c = act
        val db = c.db
        val today = LocalDate.now()
        val n = name.trim()
        if (isTop && n.isEmpty()) { c.toast(R.string.pet_need_name); return }
        val target = if (isTop) db.addFolder(folderId, n) else folderId
        vaccine?.let { v ->
            db.saveHealthItem(HealthItem(folderId = target, kind = HealthKind.VACCINE, name = c.getString(R.string.pet_vaccine_name),
                date = v, nextDue = v.plusYears(boosterYears.toLong())))
        }
        if (antiparasitic) {
            db.saveMedicine(Medicine(folderId = target, name = c.getString(R.string.pet_antiparasitic_name), unit = "applicazione",
                times = listOf(LocalTime.of(9, 0)), everyDays = antiEvery, start = today, remind = true))
            Doses.schedule(c)
        }
        db.saveVisit(Visit(folderId = target, title = c.getString(R.string.pet_visit_title), status = VisitStatus.TODO,
            dueBy = (lastVisit ?: today).plusYears(1), remindDays = 30))
        app.pensy.core.Measures.parse(MeasureKind.WEIGHT, weightText)?.let { (kg, _, _) ->
            db.saveMeasure(Measure(folderId = target, kind = MeasureKind.WEIGHT, at = LocalDateTime.now(), v1 = kg))
        }
        c.changed()
        c.toast(R.string.pet_created)
        c.pop()
        if (target != folderId) c.push(FolderScreen(c, target))
    }
}

/** The card at the top of an Auto or Animali folder that opens the setup. */
fun MainActivity.setupCard(f: Folder, folders: List<Folder>): View? {
    val kind = folderKind(f, folders) ?: return null
    if (kind != FolderKind.AUTO && kind != FolderKind.ANIMALI) return null
    val car = kind == FolderKind.AUTO
    val list = listCard()
    list.add(healthRow(if (car) R.drawable.ic_car else R.drawable.ic_pets, folderColor(kind),
        getString(if (car) R.string.car_title else R.string.pet_title),
        getString(if (car) R.string.car_card_sub else R.string.pet_card_sub)) {
        push(if (car) CarSetupScreen(this, f.id) else PetSetupScreen(this, f.id))
    })
    return list
}
