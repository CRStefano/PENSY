package app.pensy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import app.pensy.core.HealthKind
import java.time.LocalDate
import java.time.Period

/*
 * The emergency card ("scheda di emergenza"): what a rescuer or a
 * doctor in the emergency room needs in ten seconds. Blood group,
 * allergies, conditions, medicines in use, who to call.
 *
 * Allergies, conditions and medicines come from the health record; the
 * rest is written here (stored per profile in profile_info). By the
 * user's explicit choice the card can stay on the lock screen as a
 * silent notification, readable without unlocking the phone.
 */
object Emergency {

    const val CHANNEL = "emergenza"
    const val NOTIF_ID = 4_000_000
    private const val PREFS = "pensy_device"

    const val BIRTH = "nascita"
    const val BLOOD = "sangue"
    const val C1_NAME = "contatto1_nome"
    const val C1_TEL = "contatto1_tel"
    const val C2_NAME = "contatto2_nome"
    const val C2_TEL = "contatto2_tel"
    const val DOCTOR = "medico"
    const val DOCTOR_TEL = "medico_tel"
    const val NOTES = "note_emergenza"
    const val DONOR = "donatore"

    val BLOOD_GROUPS = listOf("0+", "0−", "A+", "A−", "B+", "B−", "AB+", "AB−")

    fun createChannel(ctx: Context) {
        val c = Lang.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, c.getString(R.string.channel_ice), NotificationManager.IMPORTANCE_LOW)
        ch.description = c.getString(R.string.channel_ice_desc)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The profile whose card is on the lock screen; 0 = none. */
    fun lockProfile(ctx: Context): Long = prefs(ctx).getLong("ice_profile", 0L)

    fun setLockProfile(ctx: Context, id: Long) {
        prefs(ctx).edit().putLong("ice_profile", id).apply()
        post(ctx)
    }

    /** The card as (label, text) lines, for one profile. */
    fun lines(ctx: Context, profileId: Long): List<Pair<String, String>> {
        val db = ctx.db
        val info = db.info(profileId)
        val items = db.allHealthItems().filter { it.profileId == profileId }
        val meds = db.allMedicines().filter { it.profileId == profileId && it.active }
        val out = ArrayList<Pair<String, String>>()
        info[BIRTH]?.let { b ->
            runCatching { LocalDate.parse(b) }.getOrNull()?.let { d ->
                out += ctx.getString(R.string.ice_birth) to ctx.getString(R.string.ice_birth_value, Fmt.date(d), Period.between(d, LocalDate.now()).years)
            }
        }
        info[BLOOD]?.let { out += ctx.getString(R.string.ice_blood) to it }
        val allergies = items.filter { it.kind == HealthKind.ALLERGY }.map { it.name + (if (it.note.isNotBlank()) " (" + it.note + ")" else "") }
        out += ctx.getString(R.string.ice_allergies) to (if (allergies.isEmpty()) ctx.getString(R.string.ice_none_known) else allergies.joinToString(", "))
        val conds = items.filter { it.kind == HealthKind.CONDITION && it.active }.map { it.name }
        if (conds.isNotEmpty()) out += ctx.getString(R.string.ice_conditions) to conds.joinToString(", ")
        if (meds.isNotEmpty()) out += ctx.getString(R.string.ice_meds) to meds.joinToString(", ") { m ->
            m.name + if (m.asNeeded || m.times.isEmpty()) "" else " " + m.times.joinToString("/") { HFmt.time(it) }
        }
        info[NOTES]?.let { out += ctx.getString(R.string.ice_notes) to it }
        for ((n, t) in listOf(C1_NAME to C1_TEL, C2_NAME to C2_TEL)) {
            val name = info[n]
            val tel = info[t]
            if (name != null || tel != null) out += ctx.getString(R.string.ice_contact) to listOfNotNull(name, tel).joinToString(" · ")
        }
        if (info[DOCTOR] != null || info[DOCTOR_TEL] != null) {
            out += ctx.getString(R.string.ice_doctor) to listOfNotNull(info[DOCTOR], info[DOCTOR_TEL]).joinToString(" · ")
        }
        when (info[DONOR]) {
            "si" -> out += ctx.getString(R.string.ice_donor) to ctx.getString(R.string.yes)
            "no" -> out += ctx.getString(R.string.ice_donor) to ctx.getString(R.string.no)
        }
        return out
    }

    /** Put (or refresh, or remove) the card on the lock screen. */
    fun post(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val pid = lockProfile(ctx)
        val profile = if (pid != 0L) runCatching { ctx.db.profileOf(pid) }.getOrNull() else null
        if (profile == null || !Notify.canNotify(ctx)) { nm.cancel(NOTIF_ID); return }
        val c = Lang.wrap(ctx)
        val text = lines(c, pid).joinToString("\n") { (k, v) -> "$k: $v" }
        val name = profile.name.ifBlank { c.getString(R.string.profile_me) }
        val tap = PendingIntent.getActivity(
            ctx, NOTIF_ID,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ICE, pid)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_health)
            .setColor(0xFFE11D48.toInt())
            .setContentTitle(c.getString(R.string.ice_lock_title, name))
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(tap)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

/** The emergency card of the profile in use: how it looks, and the fields to fill. */
class EmergencyScreen(act: MainActivity) : Screen(act) {

    private val pid = act.db.profile
    private val info = act.db.info(pid).toMutableMap()
    private var editing = info.isEmpty()

    override fun build(): View {
        val c = act
        val body = c.vbox()
        val name = c.profileLabel(c.db.current())

        // The card, big and plain
        val card = c.card(18, 0xFFFFF1F2.toInt())
        card.add(c.text(c.getString(R.string.ice_title_card), 13f, 0xFFB91C1C.toInt(), MEDIUM))
        card.add(c.text(name, 26f, 0xFF18171C.toInt(), BOLD), top = 4)
        for ((k, v) in Emergency.lines(c, pid)) {
            card.add(c.text(k.uppercase(), 11.5f, 0xFF66646C.toInt(), MEDIUM).apply { letterSpacing = 0.06f }, top = 12)
            card.add(c.text(v, 18f, 0xFF18171C.toInt()).apply { setTextIsSelectable(true) }, top = 2)
        }
        body.add(card, top = 4)

        // Call buttons
        val calls = listOf(Emergency.C1_NAME to Emergency.C1_TEL, Emergency.C2_NAME to Emergency.C2_TEL, Emergency.DOCTOR to Emergency.DOCTOR_TEL)
            .mapNotNull { (n, t) -> info[t]?.let { tel -> (info[n] ?: tel) to tel } }
        for ((label, tel) in calls) {
            body.add(c.button(c.getString(R.string.ice_call, label), Btn.SECONDARY) {
                try { c.startExternal(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.filter { it.isDigit() || it == '+' })), 0) }
                catch (e: Exception) { c.toast(R.string.no_picker) }
            }, top = 10)
        }

        // On the lock screen
        val onLock = Emergency.lockProfile(c) == pid
        val lockBox = c.card(16)
        lockBox.add(c.toggleRow(c.getString(R.string.ice_lock), onLock) { on ->
            Emergency.setLockProfile(c, if (on) pid else 0L)
            if (on && !Notify.canNotify(c)) c.toast(R.string.notifications_off)
        })
        lockBox.add(c.text(c.getString(R.string.ice_lock_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        body.add(lockBox, top = 16)

        // The fields
        if (!editing) {
            body.add(c.button(c.getString(R.string.ice_edit), Btn.GHOST, R.drawable.ic_edit) { editing = true; refresh() }, top = 14)
        } else {
            body.add(c.section(R.string.ice_edit), top = 24)
            body.add(c.text(c.getString(R.string.ice_from_record), 13f, c.col(R.color.ink2)), top = 6, start = 4)
            val birth = info[Emergency.BIRTH]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            body.add(c.labeled(c.getString(R.string.ice_birth), c.dateRow(birth, c.getString(R.string.field_date_none),
                { info[Emergency.BIRTH] = it.toString(); refresh() }, if (birth != null) ({ info.remove(Emergency.BIRTH); refresh() }) else null)), top = 14)
            body.add(c.labeled(c.getString(R.string.ice_blood), c.chips(
                (listOf("" to c.getString(R.string.ice_blood_unknown)) + Emergency.BLOOD_GROUPS.map { it to it }), info[Emergency.BLOOD] ?: ""
            ) { if (it.isEmpty()) info.remove(Emergency.BLOOD) else info[Emergency.BLOOD] = it }), top = 14)
            fun text(key: String, label: Int, hint: Int, phone: Boolean = false) {
                val type = if (phone) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                body.add(c.labeled(c.getString(label), c.field(c.getString(hint), info[key] ?: "", type) {
                    if (it.isBlank()) info.remove(key) else info[key] = it
                }), top = 14)
            }
            text(Emergency.C1_NAME, R.string.ice_contact1, R.string.ice_contact_hint)
            text(Emergency.C1_TEL, R.string.ice_phone, R.string.ice_phone_hint, phone = true)
            text(Emergency.C2_NAME, R.string.ice_contact2, R.string.ice_contact_hint)
            text(Emergency.C2_TEL, R.string.ice_phone, R.string.ice_phone_hint, phone = true)
            text(Emergency.DOCTOR, R.string.ice_doctor, R.string.ice_doctor_hint)
            text(Emergency.DOCTOR_TEL, R.string.ice_phone, R.string.ice_phone_hint, phone = true)
            body.add(c.labeled(c.getString(R.string.ice_notes), c.field(c.getString(R.string.ice_notes_hint), info[Emergency.NOTES] ?: "", lines = 3) {
                if (it.isBlank()) info.remove(Emergency.NOTES) else info[Emergency.NOTES] = it
            }), top = 14)
            body.add(c.labeled(c.getString(R.string.ice_donor), c.chips(listOf(
                "" to c.getString(R.string.ice_donor_unset), "si" to c.getString(R.string.yes), "no" to c.getString(R.string.no)
            ), info[Emergency.DONOR] ?: "") { if (it.isEmpty()) info.remove(Emergency.DONOR) else info[Emergency.DONOR] = it }), top = 14)
            body.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, top = 22)
        }
        return page(c.getString(R.string.ice_title), back = true, body = body)
    }

    private fun save() {
        val c = act
        val keys = listOf(Emergency.BIRTH, Emergency.BLOOD, Emergency.C1_NAME, Emergency.C1_TEL, Emergency.C2_NAME, Emergency.C2_TEL,
            Emergency.DOCTOR, Emergency.DOCTOR_TEL, Emergency.NOTES, Emergency.DONOR)
        for (k in keys) c.db.setInfo(k, info[k] ?: "", pid)
        c.changed()
        Emergency.post(c)
        editing = false
        c.toast(R.string.saved)
        refresh()
    }
}

/** A small "Scheda di emergenza" row for the health block. */
fun MainActivity.emergencyRow(): View =
    healthRow(R.drawable.ic_warning, 0xFFE11D48.toInt(), getString(R.string.ice_title), getString(R.string.ice_row_sub)) {
        push(EmergencyScreen(this))
    }
