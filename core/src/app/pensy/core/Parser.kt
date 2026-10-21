package app.pensy.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/*
 * The document reader: from OCR text (and decoded codes) to a Card.
 *
 * No magic here, only rules written by hand for Italian documents:
 * keywords near amounts and dates, the pagoPA QR format, the Poste
 * Data Matrix. It will be wrong sometimes; that's why every Card goes
 * through a confirmation screen before anything is saved.
 *
 * Codes win over text: a pagoPA QR carries the exact amount and
 * notice code, so when present it overrides what OCR guessed.
 */
object Parser {

    // ---------------------------------------------------------------- codes

    data class PagoPa(val noticeCode: String, val creditorCf: String?, val amountCents: Long?)

    /** pagoPA QR: "PAGOPA|002|<codice avviso 18>|<cf ente 11>|<importo in centesimi>". */
    fun parsePagoPaQr(content: String): PagoPa? {
        val p = content.trim().split('|')
        if (p.size < 5 || !p[0].equals("PAGOPA", ignoreCase = true)) return null
        val notice = p[2].filter { it.isDigit() }
        if (notice.length != 18) return null
        val cf = p[3].trim().takeIf { it.length == 11 && it.all { c -> c.isDigit() } }
        val amount = p[4].trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }?.toLongOrNull()
        return PagoPa(notice, cf, amount)
    }

    private val POSTE_DM = Regex("""codfase=NBPA;18(\d{18})12(\d{12})10(\d{10})""")
    private val POSTE_CF = Regex("""1P1(\d{11})""")

    /** Data Matrix printed on pagoPA notices payable at the post office ("codfase=NBPA;..."). */
    fun parsePosteDataMatrix(content: String): PagoPa? {
        val m = POSTE_DM.find(content) ?: return null
        val cf = POSTE_CF.find(content, m.range.last)?.groupValues?.get(1)
        return PagoPa(m.groupValues[1], cf, m.groupValues[3].toLongOrNull())
    }

    // ---------------------------------------------------------------- dates

    private val MONTHS = linkedMapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
        "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6,
        "lug" to 7, "ago" to 8, "sett" to 9, "set" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
        // English, for documents written in English
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "jan" to 1, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "dec" to 12
    )
    private val MONTH_ALT = MONTHS.keys.joinToString("|")
    private val NUM_DATE = Regex("""(?<!\d)(\d{1,2})\s?[/\-.]\s?(\d{1,2})\s?[/\-.]\s?(\d{4}|\d{2})(?!\d)""")
    private val TXT_DATE = Regex("""(?<!\d)(\d{1,2})\s+($MONTH_ALT)\.?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    /** "November 15, 2026" (English order). */
    private val TXT_DATE_EN = Regex("""(?<![a-z])($MONTH_ALT)\.?\s+(\d{1,2}),?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val TXT_MONTH = Regex("""(?<![a-z])(${MONTHS.keys.take(12).joinToString("|")})\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val NUM_MONTH = Regex("""(?<![\d/.\-])(\d{1,2})\s?[/\-]\s?(\d{4})(?!\d)""")

    private fun mkDate(d: Int, m: Int, y: Int): LocalDate? = try {
        val year = if (y < 100) 2000 + y else y
        if (year !in 1990..2100) null else LocalDate.of(year, m, d)
    } catch (e: DateTimeException) { null }

    /** All dates found in a line, in order. */
    fun datesIn(line: String): List<LocalDate> {
        val found = ArrayList<Pair<Int, LocalDate>>()
        for (m in NUM_DATE.findAll(line)) {
            val (d, mo, y) = m.destructured
            mkDate(d.toInt(), mo.toInt(), y.toInt())?.let { found += m.range.first to it }
        }
        for (m in TXT_DATE.findAll(line)) {
            val (d, mo, y) = m.destructured
            val month = MONTHS[mo.lowercase()] ?: continue
            mkDate(d.toInt(), month, y.toInt())?.let { found += m.range.first to it }
        }
        for (m in TXT_DATE_EN.findAll(line)) {
            val (mo, d, y) = m.destructured
            val month = MONTHS[mo.lowercase()] ?: continue
            mkDate(d.toInt(), month, y.toInt())?.let { found += m.range.first to it }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    // -------------------------------------------------------------- amounts

    private val AMOUNT = Regex("""(?<![\d,.])(?:€\s?)?(\d{1,3}(?:\.\d{3})+|\d{1,9})([,.]\d{2})(?!\d|[.,/]\d|\s?%)(?:\s?€)?""")

    /** All amounts (cents) found in a line, in order. Dates and percentages are skipped. */
    fun amountsIn(line: String): List<Long> =
        AMOUNT.findAll(line).mapNotNull { Money.parse(it.groupValues[1] + it.groupValues[2]) }.toList()

    // ------------------------------------------------------------- keywords

    private val AMOUNT_KEYS = listOf(
        "totale da pagare" to 10, "importo da pagare" to 10, "totale da versare" to 10,
        "netto in busta" to 10, "netto a pagare" to 10, "netto da pagare" to 10, "netto del mese" to 10,
        "ticket da pagare" to 10, "importo dovuto" to 9, "totale bolletta" to 9, "totale fattura" to 9,
        "importo rata" to 9, "da pagare" to 7, "importo" to 6, "totale" to 5, "netto" to 5, "euro" to 2,
        "amount due" to 10, "total due" to 10, "balance due" to 10, "amount" to 5, "total" to 5
    )
    private val AMOUNT_NOISE = listOf("imponibile", "iva", "aliquota", "kwh", "smc", "consum", "trattenute", "competenze", "lordo")

    private val DUE_KEYS = listOf(
        "scadenza" to 10, "da pagare entro" to 10, "pagare entro" to 10, "scade il" to 10, "scade" to 8,
        "entro il" to 8, "data limite" to 8, "termine di pagamento" to 8, "valida fino" to 9, "valido fino" to 9, "entro" to 5,
        "due date" to 10, "payment due" to 10, "date of expiry" to 10, "expiry" to 9, "expires" to 8, "pay by" to 9,
        "due by" to 9, "valid until" to 8
    )
    private val DUE_NOISE = listOf(
        "emission", "emessa", "data documento", "periodo", "lettura", "nato", "nata", "nascita", "dal", "fatturazione",
        "rilascio", "rilasciat", "invoice date", "issue", "birth"
    )

    /*
     * Keyword match on a lowercased line. A key must start a word; keys of
     * four letters or less must also end it ("tari" is not "tariffa",
     * "iva" is not "arriva"), longer ones may be a prefix ("prestazion").
     */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun has(lowLine: String, key: String): Boolean {
        val r = keyCache.getOrPut(key) {
            val k = key.trim()
            val end = if (k.length <= 4) "(?![\\p{L}\\d])" else ""
            Regex("(?<![\\p{L}\\d])" + Regex.escape(k) + end)
        }
        return r.containsMatchIn(lowLine)
    }

    private fun score(line: String, keys: List<Pair<String, Int>>): Int =
        keys.filter { has(line, it.first) }.maxOfOrNull { it.second } ?: 0

    private val TYPE_KEYS: List<Triple<DocType, String, Int>> = listOf(
        Triple(DocType.BUSTA_PAGA, "busta paga", 4), Triple(DocType.BUSTA_PAGA, "cedolino", 4),
        Triple(DocType.BUSTA_PAGA, "netto in busta", 4), Triple(DocType.BUSTA_PAGA, "retribuzione", 2),
        Triple(DocType.BUSTA_PAGA, "trattenute", 2), Triple(DocType.BUSTA_PAGA, "competenze", 1),
        Triple(DocType.BUSTA_PAGA, "imponibile previdenziale", 3), Triple(DocType.BUSTA_PAGA, "t.f.r", 1),
        Triple(DocType.F24, "f24", 4), Triple(DocType.F24, "codice tributo", 3), Triple(DocType.F24, "delega di pagamento", 2),
        Triple(DocType.MULTA, "contravvenzione", 5), Triple(DocType.MULTA, "codice della strada", 5),
        Triple(DocType.MULTA, "verbale", 3), Triple(DocType.MULTA, "violazione", 3),
        Triple(DocType.MULTA, "sanzione amministrativa", 4), Triple(DocType.MULTA, "polizia locale", 3),
        Triple(DocType.MULTA, "polizia municipale", 3),
        Triple(DocType.BOLLO_AUTO, "tassa automobilistica", 5), Triple(DocType.BOLLO_AUTO, "bollo auto", 5),
        Triple(DocType.BOLLO_AUTO, "targa", 2), Triple(DocType.BOLLO_AUTO, "veicolo", 1),
        Triple(DocType.TICKET, "ticket", 4), Triple(DocType.TICKET, "prestazion", 2),
        Triple(DocType.TICKET, "azienda sanitaria", 2), Triple(DocType.TICKET, "azienda ospedaliera", 2),
        Triple(DocType.TICKET, "s.s.n", 2), Triple(DocType.TICKET, "esenzione", 1),
        Triple(DocType.REFERTO, "referto", 5), Triple(DocType.REFERTO, "anamnesi", 3), Triple(DocType.REFERTO, "diagnosi", 3),
        Triple(DocType.REFERTO, "esame obiettivo", 3), Triple(DocType.REFERTO, "conclusioni", 2),
        Triple(DocType.REFERTO, "visita", 2), Triple(DocType.REFERTO, "lettera di dimissione", 5),
        Triple(DocType.REFERTO, "ecografia", 2), Triple(DocType.REFERTO, "radiografia", 2), Triple(DocType.REFERTO, "risonanza", 2),
        Triple(DocType.REFERTO, "elettrocardiogramma", 2), Triple(DocType.REFERTO, "ecocardiogramma", 2),
        Triple(DocType.REFERTO, "valori di riferimento", 3),
        Triple(DocType.RICETTA, "ricetta elettronica", 5), Triple(DocType.RICETTA, "promemoria per l'assistito", 5),
        Triple(DocType.RICETTA, "ricetta", 3), Triple(DocType.RICETTA, "prescrizione", 3), Triple(DocType.RICETTA, "nre", 3),
        Triple(DocType.TASSE_UNIVERSITA, "universit", 3), Triple(DocType.TASSE_UNIVERSITA, "ateneo", 3),
        Triple(DocType.TASSE_UNIVERSITA, "contribuzione studentesca", 5), Triple(DocType.TASSE_UNIVERSITA, "tasse universitarie", 5),
        Triple(DocType.TASSE_UNIVERSITA, "matricola", 2), Triple(DocType.TASSE_UNIVERSITA, "diritto allo studio", 2),
        Triple(DocType.POLIZZA, "polizza", 5), Triple(DocType.POLIZZA, "assicurazion", 3), Triple(DocType.POLIZZA, "rca", 3),
        Triple(DocType.POLIZZA, "massimale", 2), Triple(DocType.POLIZZA, "quietanza", 2), Triple(DocType.POLIZZA, "premio", 1),
        Triple(DocType.RATA, "mutuo", 4), Triple(DocType.RATA, "finanziamento", 3), Triple(DocType.RATA, "prestito", 3),
        Triple(DocType.RATA, "piano di ammortamento", 3), Triple(DocType.RATA, "rata", 2),
        Triple(DocType.BOLLETTA, "bolletta", 4), Triple(DocType.BOLLETTA, "fornitura", 2), Triple(DocType.BOLLETTA, "kwh", 2),
        Triple(DocType.BOLLETTA, "smc", 2), Triple(DocType.BOLLETTA, "energia elettrica", 3), Triple(DocType.BOLLETTA, "gas naturale", 3),
        Triple(DocType.BOLLETTA, "servizio idrico", 3), Triple(DocType.BOLLETTA, "tari", 2), Triple(DocType.BOLLETTA, "rifiuti", 2),
        Triple(DocType.BOLLETTA, "fibra", 1), Triple(DocType.BOLLETTA, "pod", 1), Triple(DocType.BOLLETTA, "pdr", 1),
        Triple(DocType.DOCUMENTO, "carta d'identità", 5), Triple(DocType.DOCUMENTO, "carta di identità", 5),
        Triple(DocType.DOCUMENTO, "passaporto", 5), Triple(DocType.DOCUMENTO, "patente di guida", 5),
        Triple(DocType.DOCUMENTO, "tessera sanitaria", 4), Triple(DocType.DOCUMENTO, "documento di riconoscimento", 3),
        Triple(DocType.GARANZIA, "certificato di garanzia", 5), Triple(DocType.GARANZIA, "garanzia", 3),
        Triple(DocType.GARANZIA, "scontrino", 3), Triple(DocType.GARANZIA, "documento commerciale", 3),
        Triple(DocType.PAGOPA, "pagopa", 2), Triple(DocType.PAGOPA, "avviso di pagamento", 2), Triple(DocType.PAGOPA, "codice avviso", 1),
        Triple(DocType.ABBONAMENTO, "abbonamento", 4), Triple(DocType.ABBONAMENTO, "rinnovo automatico", 4),
        Triple(DocType.ABBONAMENTO, "tacito rinnovo", 4), Triple(DocType.ABBONAMENTO, "canone mensile", 3),
        Triple(DocType.ABBONAMENTO, "disdetta", 3), Triple(DocType.ABBONAMENTO, "recesso", 2), Triple(DocType.ABBONAMENTO, "subscription", 4),
        Triple(DocType.RICEVUTA, "ricevuta di pagamento", 6), Triple(DocType.RICEVUTA, "attestazione di pagamento", 6),
        Triple(DocType.RICEVUTA, "pagamento eseguito", 5), Triple(DocType.RICEVUTA, "ricevuta", 4), Triple(DocType.RICEVUTA, "receipt", 4),
        Triple(DocType.RICEVUTA, "esito", 1),
        Triple(DocType.DOCUMENTO, "passport", 5), Triple(DocType.DOCUMENTO, "identity card", 5), Triple(DocType.DOCUMENTO, "driving licence", 5),
        Triple(DocType.DOCUMENTO, "patente", 3), Triple(DocType.DOCUMENTO, "carta di soggiorno", 4),
        Triple(DocType.GARANZIA, "warranty", 3), Triple(DocType.BOLLETTA, "invoice", 2)
    )

    /** Order used to break ties: the more specific kinds first. */
    private val TYPE_PRIORITY = listOf(
        DocType.BUSTA_PAGA, DocType.F24, DocType.MULTA, DocType.BOLLO_AUTO, DocType.TICKET, DocType.REFERTO,
        DocType.RICETTA, DocType.TASSE_UNIVERSITA, DocType.POLIZZA, DocType.RATA, DocType.BOLLETTA,
        DocType.DOCUMENTO, DocType.GARANZIA, DocType.ABBONAMENTO, DocType.RICEVUTA, DocType.PAGOPA, DocType.ALTRO
    )

    fun classify(text: String): DocType {
        val t = text.lowercase()
        val scores = HashMap<DocType, Int>()
        for ((type, key, w) in TYPE_KEYS) if (has(t, key)) scores[type] = (scores[type] ?: 0) + w
        val best = scores.maxOfOrNull { it.value } ?: return DocType.ALTRO
        return TYPE_PRIORITY.first { (scores[it] ?: 0) == best }
    }

    // --------------------------------------------------------------- issuers

    private val ISSUERS = listOf(
        "Enel Energia", "Enel", "A2A", "Iren", "Hera", "Edison", "Eni Plenitude", "Plenitude", "Sorgenia",
        "Acea", "SMAT", "Acquedotto Pugliese", "Italgas", "TIM", "Vodafone", "WindTre", "Fastweb", "Iliad",
        "Sky", "Poste Italiane", "Agenzia delle Entrate-Riscossione", "Agenzia delle Entrate", "INPS", "ACI"
    )
    private val ISSUER_PATTERNS = listOf(
        Regex("""Universit[àa][ \t]+(?:degli[ \t]+Studi[ \t]+)?di[ \t]+\p{L}[\p{L}']+""", RegexOption.IGNORE_CASE),
        Regex("""Politecnico[ \t]+di[ \t]+\p{L}[\p{L}']+""", RegexOption.IGNORE_CASE),
        Regex("""\bASL[ \t]+[\p{L}0-9' \t]{2,30}?(?=\n|$|,)""", RegexOption.IGNORE_CASE),
        Regex("""Azienda[ \t]+(?:Sanitaria|Ospedaliera)[\p{L}' \t]{0,40}?(?=\n|$|,)""", RegexOption.IGNORE_CASE),
        Regex("""\bComune[ \t]+di[ \t]+\p{L}[\p{L}']+""", RegexOption.IGNORE_CASE),
        Regex("""\bRegione[ \t]+\p{L}[\p{L}']+""", RegexOption.IGNORE_CASE)
    )

    fun issuer(text: String): String {
        for (name in ISSUERS) {
            val r = Regex("""(?<![\p{L}])${Regex.escape(name)}(?![\p{L}])""", RegexOption.IGNORE_CASE)
            if (r.containsMatchIn(text)) return name
        }
        for (p in ISSUER_PATTERNS) {
            p.find(text)?.let { return it.value.trim().replace(Regex("""\s+"""), " ") }
        }
        return ""
    }

    // ------------------------------------------------- documents, warranties

    /** Kinds of identity documents, by the words on them. */
    enum class IdKind { CARTA_IDENTITA, PATENTE, PASSAPORTO, TESSERA_SANITARIA }

    fun idKind(text: String): IdKind? {
        val t = text.lowercase()
        return when {
            has(t, "passaporto") || has(t, "passport") -> IdKind.PASSAPORTO
            has(t, "patente") || has(t, "driving licence") || has(t, "permis de conduire") -> IdKind.PATENTE
            has(t, "carta d'identità") || has(t, "carta di identità") || has(t, "carta d'identita") || has(t, "identity card") -> IdKind.CARTA_IDENTITA
            has(t, "tessera sanitaria") || has(t, "tessera europea") -> IdKind.TESSERA_SANITARIA
            else -> null
        }
    }

    /** Years of validity when the expiry is not printed (adults; a child's documents last less). */
    fun idYears(k: IdKind): Long = when (k) {
        IdKind.CARTA_IDENTITA, IdKind.PASSAPORTO, IdKind.PATENTE -> 10
        IdKind.TESSERA_SANITARIA -> 6
    }

    /** Italian driving licence: field 4a is the issue date, 4b the expiry. */
    private val LICENCE_4B = Regex("""(?<![\w])4\s?b[.)]?\s*[:\-]?\s*(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4})""", RegexOption.IGNORE_CASE)
    private val LICENCE_4A = Regex("""(?<![\w])4\s?a[.)]?\s*[:\-]?\s*(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4})""", RegexOption.IGNORE_CASE)
    private val ISSUE_KEYS = listOf("rilascio", "rilasciat", "emission", "emessa", "data di emissione", "date of issue", "issued")

    /** Expiry and issue date of an identity document (either may be null). */
    fun idDates(lines: List<String>, low: List<String>): Pair<LocalDate?, LocalDate?> {
        val all = lines.joinToString("\n")
        val exp4b = LICENCE_4B.find(all)?.let { datesIn(it.groupValues[1]).firstOrNull() }
        val iss4a = LICENCE_4A.find(all)?.let { datesIn(it.groupValues[1]).firstOrNull() }
        var issue: LocalDate? = iss4a
        if (issue == null) {
            for (i in lines.indices) {
                val keyed = ISSUE_KEYS.any { has(low[i], it) }
                val above = i > 0 && ISSUE_KEYS.any { has(low[i - 1], it) } && datesIn(lines[i - 1]).isEmpty()
                if (keyed || above) datesIn(lines[i]).firstOrNull()?.let { issue = it }
                if (issue != null) break
            }
        }
        return exp4b to issue
    }

    /** "garanzia di 3 anni", "estensione di garanzia 36 mesi", "2 years warranty": months, or null. */
    private val WARRANTY = Regex(
        """(?:garanzia[^\n\d]{0,25}?(\d{1,2})\s*(ann[oi]|mes[ei]))|(?:(\d{1,2})\s*(ann[oi]|mes[ei]|years?|months?)\s*(?:di\s+)?(?:garanzia|warranty))""",
        RegexOption.IGNORE_CASE
    )

    fun warrantyMonths(text: String): Int? {
        val m = WARRANTY.find(text) ?: return null
        val n = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return null
        val unit = m.groupValues[2].ifEmpty { m.groupValues[4] }.lowercase()
        val months = if (unit.startsWith("ann") || unit.startsWith("year")) n * 12 else n
        return months.takeIf { it in 6..120 }
    }

    /** Consumer goods: two years of legal warranty from delivery. */
    const val LEGAL_WARRANTY_MONTHS = 24

    // -------------------------------------------------------- prescriptions

    /** A prescription for medicines can be used for 30 days after the day it was written. */
    const val RX_VALID_DAYS = 30L

    /** NRE (electronic prescription number): 15 characters, a batch of 5 (region code and letters) and 10 digits. */
    private val NRE = Regex("""(?<![A-Z0-9])(\d{3}[A-Z0-9]{2})\s?(\d{5})\s?(\d{5})(?![A-Z0-9])""")
    private val NRE_EXACT = Regex("""\d{3}[A-Z0-9]{2}\d{10}""")
    private val NRE_KEY = Regex("""\bN\.?\s?R\.?\s?E\b|numero\s+(?:di\s+)?ricetta""", RegexOption.IGNORE_CASE)
    private val SPECIALIST = listOf("priorità", "priorita", "prestazion", "visita", "esame", "ecografia", "radiografia",
        "risonanza", "tac", "analisi", "prelievo")

    private fun nreOk(v: String) = v.length == 15 && v.substring(3, 5).any { it.isLetter() }

    /** The NRE of a prescription: from a barcode, near "NRE", or anywhere. */
    fun nre(text: String, codes: List<Code> = emptyList()): String? {
        for (c in codes) {
            val t = c.content.trim().uppercase()
            if (NRE_EXACT.matches(t) && nreOk(t)) return t
        }
        val up = text.uppercase()
        NRE_KEY.find(text)?.let { k ->
            NRE.find(up, k.range.first)?.takeIf { it.range.first - k.range.last < 60 }?.let { m ->
                val v = m.groupValues.drop(1).joinToString("")
                if (nreOk(v)) return v
            }
        }
        for (m in NRE.findAll(up)) {
            val v = m.groupValues.drop(1).joinToString("")
            if (nreOk(v)) return v
        }
        return null
    }

    // ----------------------------------------------------------------- main

    private val NOTICE_TEXT = Regex("""codice\s*(?:di\s*)?avviso[^0-9\n]{0,30}((?:\d\s?){18})""", RegexOption.IGNORE_CASE)
    private val CF_ENTE_TEXT = Regex("""(?:cod(?:ice)?\.?\s*fiscale\s*(?:dell'?)?\s*ente(?:\s*creditore)?|cf\s*ente)[^0-9\n]{0,20}(\d{11})""", RegexOption.IGNORE_CASE)

    fun parse(text: String, codes: List<Code> = emptyList(), today: LocalDate = LocalDate.now()): Card {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val low = lines.map { it.lowercase() }
        var type = classify(text)

        // Amount: best keyword score on the same line (or label on the line above).
        var bestAmount: Long? = null
        var bestScore = 0
        for (i in lines.indices) {
            val amounts = amountsIn(lines[i])
            if (amounts.isEmpty()) continue
            var s = score(low[i], AMOUNT_KEYS)
            if (s == 0 && i > 0 && amountsIn(lines[i - 1]).isEmpty()) s = score(low[i - 1], AMOUNT_KEYS) - 1
            if (AMOUNT_NOISE.any { has(low[i], it) }) s -= 4
            val a = amounts.maxOrNull()!!
            if (s > bestScore || (s == bestScore && s > 0 && bestAmount != null && a > bestAmount)) {
                bestScore = s; bestAmount = a
            }
        }
        if (bestAmount == null) {
            bestAmount = lines.filter { it.contains("€") || it.lowercase().contains("euro") }
                .flatMap { amountsIn(it) }.maxOrNull()
        }

        // Due date: dates on a "scadenza/entro" line or right below it.
        var bestDue: LocalDate? = null
        var bestDueScore = 0
        for (i in lines.indices) {
            val dates = datesIn(lines[i])
            if (dates.isEmpty() || DUE_NOISE.any { has(low[i], it) }) continue
            var s = score(low[i], DUE_KEYS)
            if (s == 0 && i > 0 && datesIn(lines[i - 1]).isEmpty()) s = score(low[i - 1], DUE_KEYS) - 1
            if (s <= 0) continue
            val d = dates.first()
            if (s > bestDueScore || (s == bestDueScore && bestDue != null && d.isBefore(bestDue))) {
                bestDueScore = s; bestDue = d
            }
        }
        if (bestDue == null && type.payable) {
            bestDue = lines.indices
                .filter { i -> DUE_NOISE.none { has(low[i], it) } }
                .flatMap { datesIn(lines[it]) }
                .filter { !it.isBefore(today) }
                .minOrNull()
        }

        // pagoPA data: codes first, text as fallback.
        var notice: String? = null
        var cf: String? = null
        for (c in codes) {
            val p = parsePagoPaQr(c.content) ?: parsePosteDataMatrix(c.content) ?: continue
            notice = p.noticeCode
            cf = p.creditorCf ?: cf
            if (p.amountCents != null && p.amountCents > 0) bestAmount = p.amountCents
            break
        }
        if (notice == null) NOTICE_TEXT.find(text)?.let { notice = it.groupValues[1].filter { c -> c.isDigit() } }
        if (cf == null) CF_ENTE_TEXT.find(text)?.let { cf = it.groupValues[1] }
        if (notice != null && type == DocType.ALTRO) type = DocType.PAGOPA

        val every = when {
            type == DocType.BUSTA_PAGA -> 0
            text.contains("bimestr", true) -> 2
            text.contains("trimestr", true) -> 3
            text.contains("semestr", true) -> 6
            text.contains("mensil", true) -> 1
            type == DocType.BOLLO_AUTO -> 12
            type == DocType.RATA -> 1
            else -> 0
        }

        var date = docDate(lines, low, bestDue, today)

        // An identity document: the expiry printed on it (patente: field 4b), or the issue date plus its years.
        if (type == DocType.DOCUMENTO) {
            val (exp, issue) = idDates(lines, low)
            if (issue != null) date = issue
            if (exp != null) bestDue = exp
            else if (bestDue == null && issue != null) idKind(text)?.let { bestDue = issue.plusYears(idYears(it)) }
        }
        // A receipt or a warranty certificate: two years of warranty (or what it says) from the purchase.
        if (type == DocType.GARANZIA && bestDue == null) {
            bestDue = (date ?: today).plusMonths((warrantyMonths(text) ?: LEGAL_WARRANTY_MONTHS).toLong())
        }

        // A prescription: its number, and (for medicines) the last day it can be used.
        val rxNumber = nre(text, codes)
        if (rxNumber != null && type == DocType.ALTRO) type = DocType.RICETTA
        if (type == DocType.RICETTA && bestDue == null && date != null) {
            val lowAll = text.lowercase()
            if (SPECIALIST.none { has(lowAll, it) }) bestDue = date.plusDays(RX_VALID_DAYS)
        }

        return Card(
            type = type,
            issuer = issuer(text),
            docDate = date,
            followUp = followUp(text, date ?: today),
            amountCents = bestAmount,
            due = if (type == DocType.BUSTA_PAGA) null else bestDue,
            everyMonths = every,
            noticeCode = notice,
            creditorCf = cf,
            payMonth = if (type == DocType.BUSTA_PAGA) payMonth(lines) else null,
            nre = if (type == DocType.RICETTA) rxNumber else null,
            codes = codes,
            text = text
        )
    }

    private val DATE_KEYS = listOf(
        "data", "del", "visita", "emission", "emess", "eseguit", "rilasciat", "redatt",
        "accettazione", "prelievo", "refertazione", "dimission"
    )

    /** Date of the document itself: on a "data / del / visita / emissione" line, else the first date that is not the due date. */
    private val BIRTH = listOf("nascita", "nato", "nata", "birth")

    fun docDate(lines: List<String>, low: List<String>, due: LocalDate?, today: LocalDate): LocalDate? {
        val plausible = { d: LocalDate -> d != due && !d.isAfter(today.plusDays(1)) }
        val skip = { i: Int -> score(low[i], DUE_KEYS) > 0 || BIRTH.any { has(low[i], it) } }
        for (i in lines.indices) {
            if (skip(i)) continue
            if (DATE_KEYS.none { has(low[i], it) }) continue
            datesIn(lines[i]).firstOrNull(plausible)?.let { return it }
        }
        for (i in lines.indices) {
            if (skip(i)) continue
            datesIn(lines[i]).firstOrNull(plausible)?.let { return it }
        }
        return null
    }

    private val NUMBER_WORDS = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "nove" to 9, "dodici" to 12, "diciotto" to 18, "ventiquattro" to 24
    )
    private val FOLLOW_UP = Regex(
        """(?:controll[oi]|ricontroll[oi]|rivalutazione|follow[- ]?up|rivedere|visita di controllo)[^.\n]{0,25}?""" +
            """(?:tra|fra|a|dopo|entro)\s+(\d{1,2}|${NUMBER_WORDS.keys.joinToString("|")})\s+(mes[ei]|ann[oi]|settiman[ae])""",
        RegexOption.IGNORE_CASE
    )

    /** "controllo tra 12 mesi", "rivalutazione a sei mesi", "follow-up tra un anno" -> the date it points to. */
    fun followUp(text: String, from: LocalDate): LocalDate? {
        val m = FOLLOW_UP.find(text) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1].lowercase()] ?: return null
        if (n <= 0) return null
        val unit = m.groupValues[2].lowercase()
        return when {
            unit.startsWith("mes") -> from.plusMonths(n.toLong())
            unit.startsWith("ann") -> from.plusYears(n.toLong())
            else -> from.plusWeeks(n.toLong())
        }
    }

    /** Month of a payslip: "Settembre 2026", "periodo 09/2026"... first match near a period keyword wins. */
    fun payMonth(lines: List<String>): YearMonth? {
        val keyed = lines.filter { l ->
            val x = l.lowercase()
            x.contains("periodo") || x.contains("mese") || x.contains("retribuzione") || x.contains("competenza")
        }
        for (group in listOf(keyed, lines)) {
            for (l in group) {
                TXT_MONTH.find(l)?.let { m ->
                    val mo = MONTHS[m.groupValues[1].lowercase()] ?: return@let
                    return YearMonth.of(m.groupValues[2].toInt(), mo)
                }
                NUM_MONTH.find(l)?.let { m ->
                    val mo = m.groupValues[1].toInt()
                    val y = m.groupValues[2].toInt()
                    if (mo in 1..12 && y in 1990..2100) return YearMonth.of(y, mo)
                }
            }
        }
        return null
    }
}
