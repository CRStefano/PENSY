package app.pensy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import app.pensy.core.Money

/*
 * A general chart drawn on a Canvas: bars side by side and lines (with an
 * optional soft area under them) over the same labels, money on the left
 * axis, a tap selects a column. Used by "Andamento" and the investments.
 */
class ChartView(ctx: Context) : View(ctx) {

    /** One series: a value per label (null = nothing there). */
    class Series(
        val values: List<Long?>,
        val color: Int,
        val bars: Boolean = false,
        val fill: Boolean = false,
        val dashed: Boolean = false
    )

    var labels: List<String> = emptyList()
    var series: List<Series> = emptyList()
    var selected = -1
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null
    var heightDp = 190
        set(v) { field = v; requestLayout() }

    fun set(labels: List<String>, series: List<Series>) {
        this.labels = labels
        this.series = series
        if (selected >= labels.size) selected = -1
        invalidate()
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(heightDp))
    }

    /** "12k", "1,2M", "850 €": short enough for the axis. */
    private fun compact(cents: Long): String {
        val e = cents / 100.0
        val a = Math.abs(e)
        return when {
            a >= 1_000_000 -> String.format("%.1fM", e / 1_000_000).replace('.', ',')
            a >= 10_000 -> "${Math.round(e / 1000)}k"
            a >= 1_000 -> String.format("%.1fk", e / 1000).replace('.', ',')
            else -> Money.formatShort(cents)
        }
    }

    /** A round step for the guides: 1, 2 or 5 times a power of ten. */
    private fun niceStep(range: Double): Double {
        if (range <= 0) return 1.0
        val raw = range / 3
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val f = raw / mag
        return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
    }

    override fun onDraw(canvas: Canvas) {
        val n = labels.size
        if (n == 0 || series.isEmpty()) return
        val all = series.flatMap { s -> s.values.filterNotNull() }
        if (all.isEmpty()) return
        var lo = minOf(0L, all.minOrNull()!!).toDouble()
        var hi = maxOf(0L, all.maxOrNull()!!).toDouble()
        if (hi - lo < 1) hi = lo + 100
        val step = niceStep(hi - lo)
        lo = Math.floor(lo / step) * step
        hi = Math.ceil(hi / step) * step

        val left = context.dpf(46f)
        val right = width - context.dpf(6f)
        val top = context.dpf(10f)
        val bottom = height - context.dpf(24f)
        fun y(v: Double): Float = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
        val slot = (right - left) / n
        fun cx(i: Int): Float = left + slot * i + slot / 2

        // Selected column
        if (selected in 0 until n) {
            p.style = Paint.Style.FILL
            p.color = context.col(R.color.surface2)
            canvas.drawRoundRect(RectF(cx(selected) - slot / 2, top, cx(selected) + slot / 2, bottom), context.dpf(6f), context.dpf(6f), p)
        }

        // Guides and their values
        p.style = Paint.Style.FILL
        p.textSize = context.dpf(10.5f)
        p.typeface = Typeface.DEFAULT
        p.textAlign = Paint.Align.RIGHT
        var g = lo
        var guard = 0
        while (g <= hi + 0.5 && guard < 12) {
            val yy = y(g)
            p.color = context.col(if (g == 0.0) R.color.ink3 else R.color.line)
            p.strokeWidth = context.dpf(1f)
            canvas.drawLine(left, yy, right, yy, p)
            p.color = context.col(R.color.ink2)
            canvas.drawText(compact(Math.round(g)), left - context.dpf(6f), yy + context.dpf(3.5f), p)
            g += step
            guard++
        }

        // Labels, as many as fit
        p.textAlign = Paint.Align.CENTER
        val every = maxOf(1, Math.ceil(n * context.dpf(38f) / (right - left).toDouble()).toInt())
        for (i in 0 until n) {
            val show = if (i == selected) true
            else i % every == 0 && (selected < 0 || every == 1 || Math.abs(i - selected) >= every)
            if (!show) continue
            p.color = context.col(if (i == selected) R.color.ink else R.color.ink2)
            p.typeface = if (i == selected) MEDIUM else Typeface.DEFAULT
            canvas.drawText(labels[i], cx(i), height - context.dpf(7f), p)
        }

        // Bars, side by side
        val bars = series.filter { it.bars }
        if (bars.isNotEmpty()) {
            val group = minOf(slot * 0.72f, context.dpf(13f) * bars.size)
            val bw = group / bars.size
            val zero = y(0.0)
            bars.forEachIndexed { k, s ->
                p.style = Paint.Style.FILL
                p.color = s.color
                for (i in 0 until minOf(n, s.values.size)) {
                    val v = s.values[i] ?: continue
                    val x0 = cx(i) - group / 2 + bw * k + bw * 0.08f
                    val x1 = x0 + bw * 0.84f
                    val yv = y(v.toDouble())
                    val r = context.dpf(3f)
                    if (Math.abs(yv - zero) < 0.5f) continue
                    canvas.drawRoundRect(RectF(x0, minOf(yv, zero), x1, maxOf(yv, zero)), r, r, p)
                }
            }
        }

        // Lines, with their area
        for (s in series.filter { !it.bars }) {
            val pts = ArrayList<Pair<Float, Float>>()
            for (i in 0 until minOf(n, s.values.size)) {
                val v = s.values[i] ?: continue
                pts += cx(i) to y(v.toDouble())
            }
            if (pts.isEmpty()) continue
            if (s.fill && pts.size > 1) {
                path.reset()
                val base = y(maxOf(lo, 0.0))
                path.moveTo(pts.first().first, base)
                for ((px, py) in pts) path.lineTo(px, py)
                path.lineTo(pts.last().first, base)
                path.close()
                p.style = Paint.Style.FILL
                p.color = (s.color and 0x00FFFFFF) or 0x30000000
                canvas.drawPath(path, p)
            }
            p.style = Paint.Style.STROKE
            p.color = s.color
            p.strokeWidth = context.dpf(2.4f)
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.pathEffect = if (s.dashed) DashPathEffect(floatArrayOf(context.dpf(6f), context.dpf(5f)), 0f) else null
            path.reset()
            path.moveTo(pts.first().first, pts.first().second)
            for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
            canvas.drawPath(path, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
            if (pts.size <= 24) for ((px, py) in pts) canvas.drawCircle(px, py, context.dpf(3f), p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (labels.isEmpty()) return super.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_DOWN) { parent?.requestDisallowInterceptTouchEvent(false); return true }
        if (e.action == MotionEvent.ACTION_UP) {
            val left = context.dpf(46f)
            val right = width - context.dpf(6f)
            val i = ((e.x - left) / ((right - left) / labels.size)).toInt().coerceIn(0, labels.size - 1)
            selected = i
            onSelect?.invoke(i)
            return true
        }
        return super.onTouchEvent(e)
    }
}

/** A small legend: a coloured dot and its words, several in a row. */
fun MainActivity.legend(items: List<Pair<Int, String>>): View {
    val row = hbox()
    items.forEachIndexed { i, (color, label) ->
        row.add(View(this).apply { background = rounded(color, 5) }, w = dp(10), h = dp(10), start = if (i == 0) 0 else 14)
        row.add(text(label, 12.5f, col(R.color.ink2)), w = WRAP, start = 6)
    }
    return row
}
