package app.pensy.core

/*
 * OCR engines return words and lines with their position on the page,
 * often grouped in columns: the label "Totale da pagare" ends up in one
 * block and its value "86,24 €" in another, far away in the text.
 *
 * rows() puts things back the way a person reads a bill: pieces whose
 * vertical centres are close form one row, sorted left to right.
 * Then "Totale da pagare 86,24 €" is a single line for the Parser.
 */
object Layout {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String) {
        val cy: Int get() = (top + bottom) / 2
        val h: Int get() = bottom - top
    }

    fun rows(boxes: List<Box>): String {
        val items = boxes.filter { it.text.isNotBlank() && it.h > 0 }.sortedBy { it.cy }
        if (items.isEmpty()) return ""
        val rows = ArrayList<MutableList<Box>>()
        for (b in items) {
            val last = rows.lastOrNull()
            if (last != null) {
                val cy = last.sumOf { it.cy } / last.size
                val h = last.sumOf { it.h } / last.size
                if (Math.abs(b.cy - cy) <= maxOf(h, b.h) * 6 / 10) { last += b; continue }
            }
            rows += mutableListOf(b)
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }

    /**
     * Tesseract output from the PENSY bridge, one line per text line:
     * "left top right bottom confidence text" separated by tabs.
     * Lines Tesseract itself doubts (stains, borders, shadows) are dropped,
     * the rest is put back in reading order.
     */
    fun fromTesseract(tsv: String, minConfidence: Int = 30): String {
        val boxes = ArrayList<Box>()
        for (line in tsv.lineSequence()) {
            val p = line.split('\t', limit = 6)
            if (p.size < 6) continue
            val l = p[0].toIntOrNull() ?: continue
            val t = p[1].toIntOrNull() ?: continue
            val r = p[2].toIntOrNull() ?: continue
            val b = p[3].toIntOrNull() ?: continue
            val conf = p[4].toIntOrNull() ?: 0
            val text = p[5].trim()
            if (conf < minConfidence || !looksLikeText(text)) continue
            boxes += Box(l, t, r, b, text)
        }
        return rows(boxes)
    }

    /** At least half of the visible characters are letters or digits. */
    fun looksLikeText(s: String): Boolean {
        val visible = s.count { !it.isWhitespace() }
        if (visible == 0) return false
        return s.count { it.isLetterOrDigit() } * 2 >= visible
    }
}
