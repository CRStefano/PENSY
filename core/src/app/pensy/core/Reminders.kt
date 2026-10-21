package app.pensy.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Reminders.
 *
 * The app wakes up once a day and asks: which deadlines deserve a
 * notification today? Each unpaid deadline goes through increasing
 * stages; a notification fires only when the stage grows past what
 * was already notified. If the phone was off for a few days, only the
 * current stage is notified, never a burst of old ones.
 */
object Reminders {

    /*
     * The levels are stored in the database ("notified"): version 4 of
     * the schema added MONTH and WEEK and moved the last three up by two.
     */
    enum class Stage(val level: Int) {
        NONE(0),
        EARLY(1),     // within remindDays (default 10 days before)
        MONTH(2),     // 30 days left, when the first reminder came earlier (an identity card: 90 days)
        WEEK(3),      // 7 days left, when the first reminder came at least two weeks before
        TOMORROW(4),  // due tomorrow
        TODAY(5),     // due today
        OVERDUE(6)    // past due and still unpaid
    }

    fun daysLeft(today: LocalDate, due: LocalDate): Long = ChronoUnit.DAYS.between(today, due)

    fun stage(today: LocalDate, d: Deadline): Stage {
        if (d.paid || d.noDue) return Stage.NONE
        return stageFor(today, d.due, d.remindDays)
    }

    /** The stage of anything due on `due` whose first reminder comes `remindDays` before. */
    fun stageFor(today: LocalDate, due: LocalDate, remindDays: Int): Stage {
        val left = daysLeft(today, due)
        return when {
            left < 0 -> Stage.OVERDUE
            left == 0L -> Stage.TODAY
            left == 1L -> Stage.TOMORROW
            left <= 7 && remindDays > 14 -> Stage.WEEK
            left <= 30 && remindDays > 30 -> Stage.MONTH
            left <= remindDays -> Stage.EARLY
            else -> Stage.NONE
        }
    }

    /** Deadlines to notify today, with the stage reached. The caller saves `stage.level` into `notified`. */
    fun due(today: LocalDate, deadlines: List<Deadline>): List<Pair<Deadline, Stage>> =
        deadlines.mapNotNull { d ->
            val s = stage(today, d)
            if (s != Stage.NONE && s.level > d.notified) d to s else null
        }.sortedBy { it.first.due }
}
