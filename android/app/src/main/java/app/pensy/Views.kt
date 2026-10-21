package app.pensy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import app.pensy.core.Money
import app.pensy.core.MonthSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/*
 * Two custom views drawn by hand on a Canvas: a month grid and a bar
 * chart. A few dozen lines each, no chart library.
 */

/** Month grid, Monday first. Each day can carry a dot: 1 = to pay, 2 = paid, 4 = overdue, 8 = projected; 16 adds a health dot. */
class CalendarGridView(ctx: Context) : View(ctx) {

    var month: YearMonth = YearMonth.now()
        set(v) { field = v; requestLayout(); invalidate() }
    var marks: Map<LocalDate, Int> = emptyMap()
        set(v) { field = v; invalidate() }
    var selected: LocalDate? = null
        set(v) { field = v; invalidate() }
    var heavy = false
        set(v) { field = v; invalidate() }
    var onDay: ((LocalDate) -> Unit)? = null

    private val today = LocalDate.now()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerH = context.dp(28)
    private val cellH = context.dp(48)

    private fun firstOffset(): Int = month.atDay(1).dayOfWeek.value - 1 // Monday = 0
    private fun rows(): Int = (firstOffset() + month.lengthOfMonth() + 6) / 7

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, headerH + rows() * cellH)
    }

    override fun onDraw(canvas: Canvas) {
        val cw = width / 7f
        val ink = context.col(R.color.ink)
        val ink2 = context.col(R.color.ink2)
        p.textAlign = Paint.Align.CENTER

        // Weekday initials in the phone's language.
        p.textSize = context.dpf(12f)
        p.typeface = MEDIUM
        p.color = ink2
        for (i in 0 until 7) {
            val name = DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault())
            canvas.drawText(name, cw * i + cw / 2, headerH - context.dpf(10f), p)
        }

        val off = firstOffset()
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val idx = off + day - 1
            val cx = cw * (idx % 7) + cw / 2
            val cy = headerH + cellH * (idx / 7) + cellH / 2f - context.dpf(4f)
            val r = context.dpf(17f)
            val isSel = date == selected
            val isToday = date == today
            if (isSel) {
                p.style = Paint.Style.FILL
                p.color = context.col(R.color.accent)
                canvas.drawCircle(cx, cy, r, p)
            } else if (isToday) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = context.dpf(1.5f)
                p.color = context.col(R.color.accent)
                canvas.drawCircle(cx, cy, r, p)
            }
            p.style = Paint.Style.FILL
            p.textSize = context.dpf(15f)
            p.typeface = if (isToday || isSel) MEDIUM else Typeface.DEFAULT
            p.color = if (isSel) context.col(R.color.on_accent) else ink
            canvas.drawText(day.toString(), cx, cy + context.dpf(5f), p)

            val m = marks[date] ?: 0
            val money = m and 15
            val health = m and 16 != 0
            // One dot for money, one for health; side by side when both.
            val dy = cy + r + context.dpf(5f)
            val gap = if (money != 0 && health) context.dpf(4f) else 0f
            if (money != 0) {
                val dotColor = when {
                    money and 4 != 0 -> R.color.bad
                    money and 1 != 0 -> R.color.accent
                    money and 8 != 0 -> R.color.ink3
                    else -> R.color.good
                }
                p.color = context.col(dotColor)
                canvas.drawCircle(cx - gap, dy, context.dpf(3f), p)
            }
            if (health) {
                p.color = COLOR_VISIT
                canvas.drawCircle(cx + gap, dy, context.dpf(3f), p)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            if (e.y < headerH) return true
            val col = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
            val row = ((e.y - headerH) / cellH).toInt()
            val day = row * 7 + col - firstOffset() + 1
            if (day in 1..month.lengthOfMonth()) {
                val d = month.atDay(day)
                selected = d
                onDay?.invoke(d)
            }
            return true
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Expenses per month as bars, income as a short line above each bar.
 * Future months (projections) are drawn lighter; the selected one is bold.
 */
class BarChartView(ctx: Context) : View(ctx) {

    var data: List<MonthSummary> = emptyList()
        set(v) { field = v; invalidate() }
    var selected = 0
        set(v) { field = v; invalidate() }
    var current = 0
    var onSelect: ((Int) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartH = context.dp(150)
    private val labelH = context.dp(24)
    private val topPad = context.dp(22)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), topPad + chartH + labelH)
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val n = data.size
        val slot = width / n.toFloat()
        val barW = minOf(slot * 0.56f, context.dpf(26f))
        val maxV = maxOf(1L, data.maxOf { maxOf(it.outCents, if (it.hasIncome) it.incomeCents else 0L) })
        val base = (topPad + chartH).toFloat()
        val accent = context.col(R.color.accent)
        val ink2 = context.col(R.color.ink2)

        // baseline
        p.color = context.col(R.color.line)
        p.strokeWidth = context.dpf(1f)
        canvas.drawLine(0f, base, width.toFloat(), base, p)

        for ((i, s) in data.withIndex()) {
            val cx = slot * i + slot / 2
            val h = chartH * (s.outCents.toFloat() / maxV)
            val future = i > current
            p.style = Paint.Style.FILL
            p.color = when {
                i == selected -> accent
                future -> context.col(R.color.accent_soft_ink)
                s.heavy -> context.col(R.color.warn)
                else -> context.col(R.color.accent_mid)
            }
            if (h > 0f) {
                val rr = context.dpf(6f)
                canvas.drawRoundRect(RectF(cx - barW / 2, base - h, cx + barW / 2, base), rr, rr, p)
                canvas.drawRect(cx - barW / 2, base - minOf(h, rr), cx + barW / 2, base, p)
            }
            if (s.hasIncome && s.incomeCents > 0) {
                val y = base - chartH * (s.incomeCents.toFloat() / maxV)
                p.color = context.col(R.color.good)
                p.strokeWidth = context.dpf(2.5f)
                p.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx - barW / 2 - context.dpf(3f), y, cx + barW / 2 + context.dpf(3f), y, p)
            }
            // month label
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.textSize = context.dpf(11f)
            p.typeface = if (i == selected) MEDIUM else Typeface.DEFAULT
            p.color = if (i == selected) context.col(R.color.ink) else ink2
            canvas.drawText(Fmt.monthLetter(s.month), cx, base + labelH - context.dpf(6f), p)
            if (i == selected && s.outCents > 0) {
                p.textSize = context.dpf(11f)
                p.typeface = MEDIUM
                p.color = context.col(R.color.ink)
                val ty = maxOf(context.dpf(12f), base - h - context.dpf(6f))
                canvas.drawText(Money.formatShort(s.outCents), cx, ty, p)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP && data.isNotEmpty()) {
            val i = (e.x / (width / data.size.toFloat())).toInt().coerceIn(0, data.size - 1)
            selected = i
            onSelect?.invoke(i)
            return true
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Measurements over time: one line (two for blood pressure), a dot for
 * each value, three guides with their values, the first and last day.
 */
class LineChartView(ctx: Context) : View(ctx) {

    var points: List<app.pensy.core.Measure> = emptyList()
        set(v) { field = v.sortedBy { it.at }; invalidate() }
    var decimals = 0

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartH = context.dp(180)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), chartH)
    }

    private fun label(v: Double): String =
        if (decimals == 0) Math.round(v).toString() else HFmt.number(Math.round(v * 10) / 10.0)

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val left = context.dpf(40f)
        val right = width - context.dpf(10f)
        val top = context.dpf(10f)
        val bottom = chartH - context.dpf(24f)
        val values = points.flatMap { listOfNotNull(it.v1, it.v2) }
        var lo = values.minOrNull()!!
        var hi = values.maxOrNull()!!
        if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.12
        lo -= pad; hi += pad
        val t0 = points.first().at.atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
        val t1 = points.last().at.atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
        fun x(m: app.pensy.core.Measure): Float {
            if (t1 - t0 < 1) return (left + right) / 2
            val t = m.at.atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
            return (left + (right - left) * ((t - t0) / (t1 - t0))).toFloat()
        }
        fun y(v: Double): Float = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()

        // Guides
        p.textSize = context.dpf(11f)
        p.typeface = Typeface.DEFAULT
        p.textAlign = Paint.Align.RIGHT
        for (k in 0..2) {
            val v = lo + pad + (hi - lo - 2 * pad) * k / 2.0
            val yy = y(v)
            p.color = context.col(R.color.line)
            p.strokeWidth = context.dpf(1f)
            canvas.drawLine(left, yy, right, yy, p)
            p.color = context.col(R.color.ink2)
            canvas.drawText(label(v), left - context.dpf(6f), yy + context.dpf(4f), p)
        }
        // Days
        p.textAlign = Paint.Align.LEFT
        canvas.drawText(Fmt.dateShort(points.first().at.toLocalDate()), left, chartH - context.dpf(6f), p)
        if (points.size > 1) {
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText(Fmt.dateShort(points.last().at.toLocalDate()), right, chartH - context.dpf(6f), p)
        }
        // Lines and dots
        fun series(get: (app.pensy.core.Measure) -> Double?, color: Int) {
            val pts = points.mapNotNull { m -> get(m)?.let { x(m) to y(it) } }
            p.color = color
            p.strokeWidth = context.dpf(2.2f)
            p.strokeCap = Paint.Cap.ROUND
            for (i in 1 until pts.size) canvas.drawLine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, p)
            val r = context.dpf(if (pts.size > 40) 2f else 3.5f)
            for ((px, py) in pts) canvas.drawCircle(px, py, r, p)
        }
        series({ it.v1 }, context.col(R.color.accent))
        if (points.any { it.v2 != null }) series({ it.v2 }, COLOR_MED)
    }
}
