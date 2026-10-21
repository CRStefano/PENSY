package app.pensy.core

/*
 * CSV for spreadsheets, the way Excel and LibreOffice open it in Italy:
 * semicolon between fields, decimal comma, UTF-8 with a BOM (so accents
 * show right), CRLF line ends. A field with ; " or a line break is
 * quoted, quotes doubled.
 */
object Csv {

    const val SEP = ';'
    private const val BOM = "﻿"

    fun field(s: String): String =
        if (s.any { it == SEP || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(fields: List<String>): String = fields.joinToString(SEP.toString()) { field(it) }

    /** 1234.5 -> "1234,5" (no thousands separator: spreadsheets read it as a number). */
    fun number(x: Double, decimals: Int = 2): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = Math.round(x * factor) / factor
        var s = rounded.toString()
        if (s.endsWith(".0")) s = s.dropLast(2)
        return s.replace('.', ',')
    }

    /** Cents -> "1234,56". */
    fun money(cents: Long): String = Money.toInput(cents)

    fun table(header: List<String>, rows: List<List<String>>): String = buildString {
        append(BOM)
        append(row(header)).append("\r\n")
        for (r in rows) append(row(r)).append("\r\n")
    }
}
