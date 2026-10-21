package app.pensy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import app.pensy.core.Measure
import java.io.File
import java.time.ZoneId

/*
 * PDF pages drawn by hand with the framework's PdfDocument: A4, text that
 * wraps and flows to the next page, headings, small charts, photos of
 * documents. No library. Used for the doctor's summary and for a whole
 * folder. The file is made on the phone and goes nowhere by itself.
 */
class PdfWriter(private val ctx: Context, private val footer: String) {

    companion object {
        const val W = 595
        const val H = 842
        private const val M = 48f
        private const val INK = 0xFF18171C.toInt()
        private const val INK2 = 0xFF66646C.toInt()
        private const val ACCENT = 0xFF4F46E5.toInt()
        private const val LINE = 0xFFDDDBE3.toInt()
    }

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = M
    private var number = 0
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bottom get() = H - M - 14f

    private fun newPage() {
        finishPage()
        number++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
        page = p
        canvas = p.canvas
        y = M
    }

    private fun finishPage() {
        val p = page ?: return
        val c = p.canvas
        paint.textSize = 8f
        paint.typeface = Typeface.DEFAULT
        paint.color = INK2
        paint.textAlign = Paint.Align.LEFT
        c.drawText(footer, M, H - 26f, paint)
        paint.textAlign = Paint.Align.RIGHT
        c.drawText(number.toString(), W - M, H - 26f, paint)
        paint.textAlign = Paint.Align.LEFT
        doc.finishPage(p)
        page = null
        canvas = null
    }

    /** Make room for `h` points, on a new page if needed. */
    private fun room(h: Float) {
        if (page == null || y + h > bottom) newPage()
    }

    /** A paragraph that wraps, continuing on the next pages if long. */
    fun text(s: String, size: Float = 11f, bold: Boolean = false, color: Int = INK, before: Float = 0f, indent: Float = 0f) {
        if (s.isEmpty()) return
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.color = color
        val width = (W - 2 * M - indent).toInt()
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width).setLineSpacing(0f, 1.15f).build()
        room(layout.getLineBottom(0).toFloat() + before)
        if (y > M) y += before
        var line = 0
        while (line < layout.lineCount) {
            room((layout.getLineBottom(line) - layout.getLineTop(line)).toFloat())
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && y + (layout.getLineBottom(last + 1) - top) <= bottom) last++
            val c = canvas!!
            c.save()
            c.translate(M + indent, y - top)
            c.clipRect(0, top, width, layout.getLineBottom(last))
            layout.draw(c)
            c.restore()
            y += (layout.getLineBottom(last) - top).toFloat()
            line = last + 1
            if (line < layout.lineCount) newPage()
        }
    }

    fun title(s: String) = text(s, 20f, bold = true)
    fun subtitle(s: String) = text(s, 10f, color = INK2, before = 2f)

    fun heading(s: String) {
        room(44f)
        y += 16f
        text(s.uppercase(), 9.5f, bold = true, color = ACCENT)
        y += 3f
        paint.color = LINE
        paint.strokeWidth = 0.8f
        canvas!!.drawLine(M, y, W - M, y, paint)
        y += 6f
    }

    fun bullet(s: String) = text("–  $s", 11f, before = 3f, indent = 0f)

    fun gap(h: Float) { if (page != null) y += h }

    /** Measurements over time, as in the app (two lines for blood pressure). */
    fun chart(points: List<Measure>, h: Float = 120f, decimals: Int = 0) {
        if (points.isEmpty()) return
        room(h + 10f)
        val c = canvas!!
        val left = M + 34f
        val right = W - M
        val top = y + 6f
        val bot = y + h - 16f
        val sorted = points.sortedBy { it.at }
        val values = sorted.flatMap { listOfNotNull(it.v1, it.v2) }
        var lo = values.minOrNull()!!
        var hi = values.maxOrNull()!!
        if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.12
        lo -= pad; hi += pad
        val zone = ZoneId.systemDefault()
        val t0 = sorted.first().at.atZone(zone).toEpochSecond().toDouble()
        val t1 = sorted.last().at.atZone(zone).toEpochSecond().toDouble()
        fun px(m: Measure): Float = if (t1 - t0 < 1) (left + right) / 2 else
            (left + (right - left) * ((m.at.atZone(zone).toEpochSecond() - t0) / (t1 - t0))).toFloat()
        fun py(v: Double): Float = (bot - (bot - top) * ((v - lo) / (hi - lo))).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = 7.5f
        p.textAlign = Paint.Align.RIGHT
        for (k in 0..2) {
            val v = lo + pad + (hi - lo - 2 * pad) * k / 2.0
            p.color = LINE
            p.strokeWidth = 0.6f
            c.drawLine(left, py(v), right, py(v), p)
            p.color = INK2
            c.drawText(if (decimals == 0) Math.round(v).toString() else HFmt.number(Math.round(v * 10) / 10.0), left - 4f, py(v) + 2.5f, p)
        }
        p.textAlign = Paint.Align.LEFT
        c.drawText(Fmt.dateShort(sorted.first().at.toLocalDate()), left, y + h - 4f, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText(Fmt.dateShort(sorted.last().at.toLocalDate()), right, y + h - 4f, p)
        fun series(get: (Measure) -> Double?, color: Int) {
            val pts = sorted.mapNotNull { m -> get(m)?.let { px(m) to py(it) } }
            p.color = color
            p.strokeWidth = 1.4f
            for (i in 1 until pts.size) c.drawLine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, p)
            for ((x, yy) in pts) c.drawCircle(x, yy, 1.8f, p)
        }
        series({ it.v1 }, ACCENT)
        if (sorted.any { it.v2 != null }) series({ it.v2 }, COLOR_MED)
        y += h
    }

    /** A photo, as large as the page allows (a new page if less than half is left). */
    fun image(bmp: Bitmap) {
        val maxW = W - 2 * M
        val maxH = H - 2 * M - 20f
        var w = maxW
        var h = w * bmp.height / bmp.width
        if (h > maxH) { h = maxH; w = h * bmp.width / bmp.height }
        if (page == null || y + h > bottom) {
            if (page == null || bottom - y < maxH / 2 || y + h > bottom) newPage()
        }
        val x = M + (maxW - w) / 2
        canvas!!.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        y += h + 8f
    }

    /** A thin frame, white inside: used for the cover of a folder. */
    fun rule() {
        room(10f)
        paint.color = LINE
        paint.strokeWidth = 0.8f
        canvas!!.drawLine(M, y + 4f, W - M, y + 4f, paint)
        y += 10f
    }

    fun finish(out: File) {
        if (page == null && number == 0) newPage()
        finishPage()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
