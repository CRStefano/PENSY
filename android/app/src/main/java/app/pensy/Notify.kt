package app.pensy

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import app.pensy.core.Boosters
import app.pensy.core.Deadline
import app.pensy.core.DoseStatus
import app.pensy.core.HealthItem
import app.pensy.core.Medicine
import app.pensy.core.Meds
import app.pensy.core.Money
import app.pensy.core.Reminders
import app.pensy.core.StockMode
import app.pensy.core.Visit
import app.pensy.core.VisitStatus
import app.pensy.core.Visits
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * Reminders, the simple way: one alarm a day (09:00 by default).
 * When it rings, the core decides what to say (deadlines, visits,
 * medicines to order) and we post one notification each, then set the
 * alarm for tomorrow. Medicine doses have their own alarm (see Doses).
 * No background services, no server.
 */
object Notify {

    const val CHANNEL = "scadenze"
    const val CHANNEL_MEDS = "farmaci"
    const val CHANNEL_HEALTH = "salute"
    /** Doses with an alarm sound: they ring in silent mode too (Settings › Reminders). */
    const val CHANNEL_MEDS_ALARM = "farmaci_sveglia"

    /** Notification ids: deadlines use their own id; the others live in separate ranges. */
    const val VISIT_BASE = 1_000_000
    const val ORDER_BASE = 2_000_000
    const val DOSE_BASE = 3_000_000
    const val ITEM_BASE = 5_000_000

    fun createChannel(ctx: Context) {
        val c = Lang.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        fun ch(id: String, name: Int, desc: Int, importance: Int) {
            val x = NotificationChannel(id, c.getString(name), importance)
            x.description = c.getString(desc)
            x.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            nm.createNotificationChannel(x)
        }
        ch(CHANNEL, R.string.channel_name, R.string.channel_desc, NotificationManager.IMPORTANCE_DEFAULT)
        ch(CHANNEL_MEDS, R.string.channel_meds, R.string.channel_meds_desc, NotificationManager.IMPORTANCE_HIGH)
        ch(CHANNEL_HEALTH, R.string.channel_health, R.string.channel_health_desc, NotificationManager.IMPORTANCE_DEFAULT)
        val alarm = NotificationChannel(CHANNEL_MEDS_ALARM, c.getString(R.string.channel_meds_alarm), NotificationManager.IMPORTANCE_HIGH)
        alarm.description = c.getString(R.string.channel_meds_alarm_desc)
        alarm.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        alarm.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        alarm.enableVibration(true)
        alarm.vibrationPattern = longArrayOf(0, 400, 250, 400, 250, 400)
        alarm.setBypassDnd(true)
        nm.createNotificationChannel(alarm)
        Emergency.createChannel(ctx)
    }

    /** Hour and minute of the daily check, "HH:MM" in the settings table. */
    fun time(ctx: Context): Pair<Int, Int> {
        val s = ctx.db.setting("remind_time", "09:00").split(':')
        val h = s.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
        val m = s.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return h to m
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val (h, m) = time(ctx)
        var t = LocalDate.now().atTime(h, m)
        if (!t.isAfter(LocalDateTime.now())) t = t.plusDays(1)
        val at = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
    }

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, DailyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The daily check, over every profile. */
    fun run(ctx: Context) {
        val c = Lang.wrap(ctx)
        val db = ctx.db
        val today = LocalDate.now()
        val allowed = canNotify(ctx)
        val who = whoPrefix(ctx)
        for ((d, stage) in Reminders.due(today, db.allDeadlines())) {
            if (allowed) post(c, d, stage, today, who(d.profileId))
            db.setNotified(d.id, stage.level)
        }
        for ((v, stage) in Visits.due(today, db.allVisits())) {
            if (allowed) postVisit(c, v, stage, today, who(v.profileId))
            db.setVisitNotified(v.id, stage.level)
        }
        for ((m, day) in Meds.toOrder(db.allMedicines(), today)) {
            if (allowed) postOrder(c, m, today, who(m.profileId))
            db.setOrderNotified(m.id, day)
        }
        for ((h, stage) in Boosters.due(today, db.allHealthItems())) {
            if (allowed) postBooster(c, h, stage, today, who(h.profileId))
            db.setItemNotified(h.id, stage.level)
        }
        // Medicines to take today with no time set: one reminder now, per person, with the same buttons.
        if (allowed && db.setting("anytime_day", "") != today.toString()) {
            val untimed = db.allMedicines().filter { it.active && it.remind && it.times.isEmpty() && Meds.takesOn(it, today) &&
                Meds.statusOf(it, today, Meds.ANYTIME) == null }
            for ((pid, meds) in untimed.groupBy { it.profileId }) Doses.post(c, today, Meds.ANYTIME, meds, who(pid), pid)
        }
        db.setSetting("anytime_day", today.toString())
        // The investment plans whose day has come (100 € on the 5th): recorded, then saved to PENSY_DATA.
        try { if (db.catchUpPlans(today) > 0) DataFolder.saveSoon(ctx) } catch (t: Throwable) { }
        db.setSetting("last_check", today.toString())
        Widget.update(ctx)
    }

    /** "Luca · " before what concerns someone else than the owner, when there are several profiles. */
    fun whoPrefix(ctx: Context): (Long) -> String {
        val profiles = ctx.db.profiles()
        if (profiles.size < 2) return { "" }
        val names = profiles.associateBy({ it.id }, { it.name })
        return { id -> if (id == 1L) "" else names[id]?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "" }
    }

    /**
     * If today's check was missed (phone off at 9:00, or an aggressive
     * battery saver), run it now. Called at boot and when the app opens.
     */
    fun catchUp(ctx: Context) {
        if (ctx.db.setting("last_check", "") == LocalDate.now().toString()) return
        val (h, m) = time(ctx)
        if (LocalTime.now().isBefore(LocalTime.of(h, m))) return
        run(ctx)
    }

    /** Tapping a notification opens PENSY on this screen. */
    private fun open(ctx: Context, code: Int, key: String, id: Long): PendingIntent =
        PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java)
                .putExtra(key, id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** What the lock screen shows: no amounts, no names, no medicines. */
    private fun publicVersion(ctx: Context, channel: String, titleRes: Int): Notification =
        Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(ctx.getString(titleRes))
            .build()

    fun show(ctx: Context, id: Int, channel: String, title: String, body: String, tap: PendingIntent, publicTitle: Int) {
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(ctx.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, channel, publicTitle))
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }

    private fun post(ctx: Context, d: Deadline, stage: Reminders.Stage, today: LocalDate, who: String) {
        val title = who + if (d.amountCents > 0) Fmt.title(ctx, d) + " · " + Money.format(d.amountCents) else Fmt.title(ctx, d)
        val body = when (stage) {
            Reminders.Stage.EARLY, Reminders.Stage.MONTH, Reminders.Stage.WEEK ->
                ctx.getString(R.string.notif_early, Fmt.relative(ctx, today, d.due), Fmt.date(d.due))
            Reminders.Stage.TOMORROW -> ctx.getString(R.string.notif_tomorrow)
            Reminders.Stage.TODAY -> ctx.getString(R.string.notif_today)
            Reminders.Stage.OVERDUE -> ctx.getString(R.string.notif_overdue, Fmt.date(d.due))
            Reminders.Stage.NONE -> return
        }
        show(ctx, d.id.toInt(), CHANNEL, title, body, open(ctx, d.id.toInt(), MainActivity.EXTRA_DEADLINE, d.id), R.string.notif_public)
    }

    private fun postVisit(ctx: Context, v: Visit, stage: Reminders.Stage, today: LocalDate, who: String) {
        val body = if (v.status == VisitStatus.BOOKED) {
            val d = v.date ?: return
            val whenText = when (stage) {
                Reminders.Stage.TODAY -> ctx.getString(R.string.notif_visit_today)
                Reminders.Stage.TOMORROW -> ctx.getString(R.string.notif_visit_tomorrow)
                else -> ctx.getString(R.string.notif_visit_on, Fmt.date(d), Fmt.relative(ctx, today, d))
            }
            whenText + (v.time?.let { ctx.getString(R.string.notif_visit_at, HFmt.time(it)) } ?: "") +
                (if (v.place.isNotBlank()) " · " + v.place else "")
        } else {
            val d = v.dueBy ?: return
            if (stage == Reminders.Stage.OVERDUE) ctx.getString(R.string.notif_book_overdue, Fmt.date(d))
            else ctx.getString(R.string.notif_book_by, Fmt.date(d), Fmt.relative(ctx, today, d))
        }
        val id = VISIT_BASE + v.id.toInt()
        show(ctx, id, CHANNEL_HEALTH, who + v.title, body, open(ctx, id, MainActivity.EXTRA_VISIT, v.id), R.string.notif_health_public)
    }

    private fun postBooster(ctx: Context, h: HealthItem, stage: Reminders.Stage, today: LocalDate, who: String) {
        if (h.kind == app.pensy.core.HealthKind.SURGERY) {
            val d = h.date ?: return
            val whenText = when (stage) {
                Reminders.Stage.TODAY -> ctx.getString(R.string.notif_visit_today)
                Reminders.Stage.TOMORROW -> ctx.getString(R.string.notif_visit_tomorrow)
                else -> ctx.getString(R.string.notif_visit_on, Fmt.date(d), Fmt.relative(ctx, today, d))
            }
            val sid = ITEM_BASE + h.id.toInt()
            show(ctx, sid, CHANNEL_HEALTH, who + ctx.getString(R.string.notif_surgery_title, h.name), whenText,
                open(ctx, sid, MainActivity.EXTRA_ITEM, h.id), R.string.notif_health_public)
            return
        }
        val d = h.nextDue ?: return
        val body = if (stage == Reminders.Stage.OVERDUE) ctx.getString(R.string.notif_booster_overdue, Fmt.date(d))
        else ctx.getString(R.string.notif_booster, Fmt.date(d), Fmt.relative(ctx, today, d))
        val id = ITEM_BASE + h.id.toInt()
        show(ctx, id, CHANNEL_HEALTH, who + ctx.getString(R.string.notif_booster_title, h.name), body,
            open(ctx, id, MainActivity.EXTRA_ITEM, h.id), R.string.notif_health_public)
    }

    private fun postOrder(ctx: Context, m: Medicine, today: LocalDate, who: String) {
        val body = when (m.stockMode) {
            StockMode.COUNT -> Meds.runOut(m, today)?.let { ctx.getString(R.string.notif_order_body, Fmt.date(it)) }
                ?: ctx.getString(R.string.notif_order_soon)
            StockMode.BOXES -> Meds.boxesLeft(m, java.time.LocalDateTime.now())?.let {
                ctx.getString(R.string.notif_order_boxes, HFmt.number(Math.floor(it * 10) / 10))
            } ?: ctx.getString(R.string.notif_order_soon)
            else -> ctx.getString(R.string.notif_order_interval, m.orderEvery)
        }
        val id = ORDER_BASE + m.id.toInt()
        show(ctx, id, CHANNEL_HEALTH, who + ctx.getString(R.string.notif_order, m.name), body,
            open(ctx, id, MainActivity.EXTRA_MEDICINE, m.id), R.string.notif_health_public)
    }
}

/*
 * Medicine doses: one alarm at the next dose time of any medicine. When
 * it rings we notify the doses due now and set the alarm for the next
 * one. Exact when Android allows it ("Alarms & reminders"), otherwise an
 * inexact alarm that Android may deliver a little late.
 */
object Doses {

    private fun intent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, DoseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val next = Meds.nextDose(ctx.db.allMedicines(), LocalDateTime.now())
        if (next == null) { am.cancel(intent(ctx)); return }
        val at = next.first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
        }
    }

    /** The alarm rang: notify what is due (not older than three hours), then the next one. */
    fun ring(ctx: Context) {
        val db = ctx.db
        val now = LocalDateTime.now()
        val last = runCatching { LocalDateTime.parse(db.setting("dose_last", "")) }.getOrNull()
        var from = now.minusHours(3)
        if (last != null && last.isAfter(from)) from = last
        val due = Meds.dosesBetween(db.allMedicines(), from, now.plusSeconds(30))
        db.setSetting("dose_last", now.plusSeconds(30).toString())
        if (Notify.canNotify(ctx)) {
            val c = Lang.wrap(ctx)
            val who = Notify.whoPrefix(ctx)
            for ((at, list) in due.groupBy({ it.first }, { it.second })) {
                // One notification per time and person: "Luca · Tachipirina".
                for ((pid, meds) in list.groupBy { it.profileId }) post(c, at.toLocalDate(), at.toLocalTime(), meds, who(pid), pid)
            }
        }
        schedule(ctx)
    }

    /** "Le medicine suonano anche in silenzioso": a device setting. */
    fun alarmSound(ctx: Context): Boolean = ctx.getSharedPreferences("pensy_device", Context.MODE_PRIVATE).getBoolean("dose_alarm", false)

    fun setAlarmSound(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("pensy_device", Context.MODE_PRIVATE).edit().putBoolean("dose_alarm", on).apply()
    }

    const val SNOOZE_MINUTES = 10L

    fun notifId(profileId: Long, t: LocalTime): Int =
        Notify.DOSE_BASE + ((profileId - 1).coerceIn(0, 99) * 1440).toInt() + t.hour * 60 + t.minute

    /** The doses of one person at one time: "Presa", "Salta", "Tra 10 minuti" right in the notification. */
    fun post(ctx: Context, day: LocalDate, t: LocalTime, meds: List<Medicine>, who: String, profileId: Long, snoozed: Boolean = false) {
        val time = if (Meds.isAnytime(t)) ctx.getString(R.string.notif_today_word) else HFmt.time(t)
        val title: String
        val body: String
        if (meds.size == 1) {
            val m = meds[0]
            title = who + ctx.getString(R.string.notif_dose_one, m.name, time)
            body = HFmt.dose(ctx, m) + (if (m.note.isNotBlank()) " · " + m.note else "")
        } else {
            title = who + if (Meds.isAnytime(t)) ctx.getString(R.string.notif_dose_many_today) else ctx.getString(R.string.notif_dose_many, time)
            body = meds.joinToString("\n") { it.name + " — " + HFmt.dose(ctx, it) }
        }
        val id = notifId(profileId, t)
        val tap = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MEDICINE, if (meds.size == 1) meds[0].id else -1L)
                .putExtra(MainActivity.EXTRA_PROFILE, profileId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ids = meds.map { it.id }.toLongArray()
        fun action(code: Int, act: String, label: Int, icon: Int): Notification.Action {
            val i = Intent(ctx, DoseActionReceiver::class.java).setAction(act)
                .putExtra(DoseActionReceiver.IDS, ids).putExtra(DoseActionReceiver.DAY, day.toString())
                .putExtra(DoseActionReceiver.SLOT, t.toString()).putExtra(DoseActionReceiver.NID, id)
                .putExtra(DoseActionReceiver.PID, profileId)
            val pi = PendingIntent.getBroadcast(ctx, id * 4 + code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), pi).build()
        }
        val alarm = alarmSound(ctx)
        val channel = if (alarm) Notify.CHANNEL_MEDS_ALARM else Notify.CHANNEL_MEDS
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_pill)
            .setColor(ctx.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_pill)
                .setContentTitle(ctx.getString(R.string.notif_dose_public)).build())
            .addAction(action(0, DoseActionReceiver.TAKEN, R.string.dose_taken_action, R.drawable.ic_check))
            .addAction(action(1, DoseActionReceiver.SKIP, R.string.dose_skip_action, R.drawable.ic_delete))
            .addAction(action(2, DoseActionReceiver.SNOOZE, R.string.dose_snooze_action, R.drawable.ic_time))
        if (snoozed) n.setSubText(ctx.getString(R.string.dose_snoozed))
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n.build())
    }

    /** A dose marked in the app: its notification goes once every dose of that person at that time is marked. */
    fun cancelIfDone(ctx: Context, profileId: Long, day: LocalDate, t: LocalTime) {
        val meds = ctx.db.allMedicines().filter { it.profileId == profileId && it.remind && t in Meds.dosesOn(it, day) }
        if (meds.all { Meds.statusOf(it, day, t) != null }) ctx.getSystemService(NotificationManager::class.java)?.cancel(notifId(profileId, t))
    }

    /** "Tra 10 minuti": the same notification again, then. */
    fun snooze(ctx: Context, extras: Intent) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val nid = extras.getIntExtra(DoseActionReceiver.NID, 0)
        val i = Intent(ctx, DoseActionReceiver::class.java).setAction(DoseActionReceiver.REPOST).putExtras(extras)
        val pi = PendingIntent.getBroadcast(ctx, nid * 4 + 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** The buttons of a dose notification, and the snooze coming back. */
class DoseActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAKEN = "app.pensy.DOSE_TAKEN"
        const val SKIP = "app.pensy.DOSE_SKIP"
        const val SNOOZE = "app.pensy.DOSE_SNOOZE"
        const val REPOST = "app.pensy.DOSE_REPOST"
        const val IDS = "ids"
        const val DAY = "day"
        const val SLOT = "slot"
        const val NID = "nid"
        const val PID = "pid"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val ids = intent.getLongArrayExtra(IDS) ?: return
        val day = runCatching { LocalDate.parse(intent.getStringExtra(DAY)) }.getOrNull() ?: return
        val slot = runCatching { LocalTime.parse(intent.getStringExtra(SLOT)) }.getOrNull() ?: return
        val nid = intent.getIntExtra(NID, 0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val db = ctx.db
        when (intent.action) {
            TAKEN, SKIP -> {
                val status = if (intent.action == TAKEN) DoseStatus.TAKEN else DoseStatus.SKIPPED
                for (id in ids) db.logDose(id, day, slot, status)
                nm?.cancel(nid)
                DataFolder.saveSoon(ctx)
                Widget.update(ctx)
            }
            SNOOZE -> {
                nm?.cancel(nid)
                Doses.snooze(ctx, intent)
            }
            REPOST -> {
                // Only what is still not marked (it may have been marked in the app meanwhile).
                val meds = ids.toList().mapNotNull { db.medicine(it) }.filter { it.active && app.pensy.core.Meds.statusOf(it, day, slot) == null }
                if (meds.isNotEmpty() && Notify.canNotify(ctx)) {
                    val pid = intent.getLongExtra(PID, 1L)
                    Doses.post(Lang.wrap(ctx), day, slot, meds, Notify.whoPrefix(ctx)(pid), pid, snoozed = true)
                }
            }
        }
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notify.run(ctx)
        Notify.schedule(ctx)
    }
}

class DoseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Doses.ring(ctx)
    }
}

/** Alarms are forgotten on reboot and on app update: set them again. Also when exact alarms get allowed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notify.catchUp(ctx)
        Notify.schedule(ctx)
        Doses.schedule(ctx)
        Emergency.post(ctx)
    }
}
