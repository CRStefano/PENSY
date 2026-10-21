package app.pensy.core

/*
 * Euro amounts. Always Long cents inside PENSY: no floating point,
 * no rounding surprises. 1234,56 € is 123456.
 */
object Money {

    /** 123456 -> "1.234,56 €" (Italian style). */
    fun format(cents: Long, symbol: Boolean = true): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val euros = (abs / 100).toString()
        val c = (abs % 100).toInt()
        val sb = StringBuilder()
        if (neg) sb.append('-')
        for (i in euros.indices) {
            if (i > 0 && (euros.length - i) % 3 == 0) sb.append('.')
            sb.append(euros[i])
        }
        sb.append(',')
        if (c < 10) sb.append('0')
        sb.append(c)
        if (symbol) sb.append(" €")
        return sb.toString()
    }

    /** Short form for charts and tight spaces: "1.235 €", no cents. */
    fun formatShort(cents: Long): String {
        val rounded = if (cents >= 0) (cents + 50) / 100 else (cents - 50) / 100
        return format(rounded * 100, symbol = false).substringBefore(',') + " €"
    }

    /** Value suitable to put back into an input field: "1234,56". */
    fun toInput(cents: Long): String {
        val abs = if (cents < 0) -cents else cents
        val c = (abs % 100).toInt()
        return (if (cents < 0) "-" else "") + (abs / 100) + "," + (if (c < 10) "0$c" else "$c")
    }

    /**
     * Parse an amount as people and documents write it: "1.234,56",
     * "89,90 €", "EUR 12", "€ 7", and also the dot-decimal "89.90".
     * Returns cents, or null when the string is not an amount.
     *
     * Rules for the separators, Italian first:
     *  - when both '.' and ',' appear, the last one is the decimal separator;
     *  - a lone ',' followed by 1-2 digits is decimal, by 3 digits thousands;
     *  - a lone '.' followed by 1-2 digits is decimal, by 3 digits thousands.
     */
    fun parse(input: String): Long? {
        var t = input.trim()
            .replace("€", "")
            .replace(" ", "")
            .replace(" ", "")
        if (t.startsWith("EUR", ignoreCase = true)) t = t.substring(3)
        if (t.endsWith("EUR", ignoreCase = true)) t = t.dropLast(3)
        if (t.isEmpty()) return null
        var neg = false
        if (t[0] == '-') { neg = true; t = t.substring(1) }
        if (t.isEmpty() || !t.all { it.isDigit() || it == '.' || it == ',' }) return null
        if (!t[0].isDigit() && !(t[0] == ',' || t[0] == '.')) return null

        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        val dec: Char? = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) ',' else '.'
            lastComma >= 0 -> if (t.count { it == ',' } == 1 && t.length - lastComma - 1 in 0..2) ',' else null
            lastDot >= 0 -> if (t.count { it == '.' } == 1 && t.length - lastDot - 1 in 0..2) '.' else null
            else -> null
        }
        val rawInt: String
        val fracPart: String
        if (dec == null) {
            rawInt = t
            fracPart = ""
        } else {
            val i = t.lastIndexOf(dec)
            rawInt = t.substring(0, i)
            fracPart = t.substring(i + 1)
        }
        // Thousands groups must be well formed: "1.234.567", not "12.34".
        val groups = rawInt.split('.', ',')
        if (groups.size > 1 && (groups[0].length !in 1..3 || groups.drop(1).any { it.length != 3 })) return null
        val intPart = groups.joinToString("")
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        if (fracPart.length > 2 || intPart.length > 12) return null
        if (!fracPart.all { it.isDigit() }) return null
        val euros = if (intPart.isEmpty()) 0L else intPart.toLongOrNull() ?: return null
        val c = when (fracPart.length) {
            0 -> 0
            1 -> fracPart.toInt() * 10
            else -> fracPart.toInt()
        }
        val v = euros * 100 + c
        return if (neg) -v else v
    }
}
