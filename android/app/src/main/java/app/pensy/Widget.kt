package app.pensy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.pensy.core.Meds
import app.pensy.core.Money
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * The home screen widget: the next three things to pay or do, today's
 * medicines still to take, a button that opens the camera. With the lock
 * on, it shows only counts: no names or amounts on the home screen.
 * It is redrawn after every change and by the daily check.
 */
object Widget {

    fun update(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = build(Lang.wrap(ctx))
            for (id in ids) mgr.updateAppWidget(id, views)
        } catch (t: Throwable) {
            // a widget must never break the app
        }
    }

    private fun build(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        val db = ctx.db
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val hidden = Lock.active(ctx)
        val me = db.current()
        val due = db.deadlines().filter { !it.paid && !it.noDue && !it.due.isAfter(today.plusDays(30)) }.sortedBy { it.due }
        val meds = db.medicines().filter { it.active }
        val pending = Meds.schedule(meds, today).count { (t, m) -> Meds.statusOf(m, today, t) == null && (Meds.isAnytime(t) || !t.isBefore(now.toLocalTime().minusHours(3))) }
        val lines = intArrayOf(R.id.w_line0, R.id.w_line1, R.id.w_line2)

        v.setTextViewText(R.id.w_title, if (me.id == 1L || me.name.isBlank()) ctx.getString(R.string.app_name) else "PENSY · " + me.name)
        if (hidden) {
            val late = due.count { it.due.isBefore(today) }
            val week = due.count { !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(7)) }
            v.setTextViewText(R.id.w_line0, ctx.resources.getQuantityString(R.plurals.widget_week, week, week))
            v.setTextViewText(R.id.w_line1, if (late > 0) ctx.resources.getQuantityString(R.plurals.widget_late, late, late) else "")
            v.setTextViewText(R.id.w_line2, "")
        } else {
            for (i in 0..2) {
                val d = due.getOrNull(i)
                val text = if (d == null) (if (i == 0) ctx.getString(R.string.widget_nothing) else "")
                else Fmt.relative(ctx, today, d.due).replaceFirstChar { it.uppercase() } + " · " + Fmt.title(ctx, d) +
                    (if (d.amountCents > 0) " · " + Money.format(d.amountCents) else "")
                v.setTextViewText(lines[i], text)
            }
        }
        v.setTextViewText(R.id.w_footer, if (pending > 0) ctx.resources.getQuantityString(R.plurals.widget_doses, pending, pending)
            else ctx.getString(R.string.widget_updated, Fmt.dateShort(today)))

        val open = PendingIntent.getActivity(ctx, 7001, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val capture = PendingIntent.getActivity(ctx, 7002, Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CAPTURE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.w_root, open)
        v.setOnClickPendingIntent(R.id.w_add, capture)
        return v
    }
}

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Widget.update(ctx)
    }
}
