package app.pensy.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.system.exitProcess

/*
 * Core tests. No framework: a check() that counts failures and a main
 * that runs everything. Run with tools/test-core.sh.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) passed++ else { failed++; println("FAIL  $name  ${detail()}") }
}

private fun <T> eq(name: String, got: T, want: T) = check(name, got == want) { "got=$got want=$want" }

private val TODAY: LocalDate = LocalDate.of(2026, 10, 19)
private fun d(s: String): LocalDate = LocalDate.parse(s)

fun testMoney() {
    eq("format 1", Money.format(123456), "1.234,56 €")
    eq("format 2", Money.format(5), "0,05 €")
    eq("format 3", Money.format(100000000), "1.000.000,00 €")
    eq("format neg", Money.format(-8990), "-89,90 €")
    eq("format short", Money.formatShort(123456), "1.235 €")
    eq("input", Money.toInput(8990), "89,90")
    eq("parse it", Money.parse("1.234,56"), 123456L)
    eq("parse euro", Money.parse("€ 89,90"), 8990L)
    eq("parse suffix", Money.parse("89,90 €"), 8990L)
    eq("parse dot dec", Money.parse("89.90"), 8990L)
    eq("parse thousands dot", Money.parse("1.234"), 123400L)
    eq("parse plain", Money.parse("12"), 1200L)
    eq("parse one dec", Money.parse("12,5"), 1250L)
    eq("parse EUR", Money.parse("EUR 7,00"), 700L)
    eq("parse en", Money.parse("1,234.56"), 123456L)
    eq("parse junk", Money.parse("abc"), null)
    eq("parse empty", Money.parse(""), null)
    eq("parse 3 dec", Money.parse("1,2345"), null)
}

fun testDates() {
    eq("num date", Parser.datesIn("Scadenza 05/11/2026"), listOf(d("2026-11-05")))
    eq("dot date", Parser.datesIn("entro il 30.10.2026"), listOf(d("2026-10-30")))
    eq("short year", Parser.datesIn("il 1-2-27"), listOf(d("2027-02-01")))
    eq("text date", Parser.datesIn("Scadenza: 31 gennaio 2027"), listOf(d("2027-01-31")))
    eq("abbr date", Parser.datesIn("16 nov. 2026"), listOf(d("2026-11-16")))
    eq("bad date", Parser.datesIn("31/02/2026"), emptyList())
    eq("two dates", Parser.datesIn("dal 01/07/2026 al 30/09/2026"), listOf(d("2026-07-01"), d("2026-09-30")))
}

fun testAmounts() {
    eq("amount it", Parser.amountsIn("TOTALE DA PAGARE € 86,24"), listOf(8624L))
    eq("amount big", Parser.amountsIn("Netto 1.733,37"), listOf(173337L))
    eq("no date as amount", Parser.amountsIn("entro il 16.11.2026"), emptyList())
    eq("no percent", Parser.amountsIn("IVA 22,00%"), emptyList())
    eq("two amounts", Parser.amountsIn("78,40 e 7,84"), listOf(7840L, 784L))
}

fun testRecurrence() {
    val dl = Deadline(due = d("2026-01-31"), everyMonths = 1, amountCents = 1000)
    eq("next clamps", dl.next()!!.due, d("2026-02-28"))
    check("next estimated", dl.next()!!.estimated)
    eq("one-off has no next", dl.copy(everyMonths = 0).next(), null)
    // Projection always counts from the original date: no 31 -> 28 -> 28 drift.
    val lines = Budget.lines(listOf(dl), d("2026-01-01"), d("2026-04-30"))
    eq("projection dates", lines.map { it.due }, listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30")))
    eq("projection flags", lines.map { it.projected }, listOf(false, true, true, true))
    // A paid occurrence does not project (its successor is stored).
    eq("paid no projection", Budget.lines(listOf(dl.copy(paid = true)), d("2026-01-01"), d("2026-04-30")).size, 1)
}

fun testReminders() {
    val base = Deadline(id = 1, due = d("2026-10-29"), remindDays = 10)
    eq("early at 10 days", Reminders.stage(TODAY, base), Reminders.Stage.EARLY)
    eq("none at 11 days", Reminders.stage(TODAY, base.copy(due = d("2026-10-30"))), Reminders.Stage.NONE)
    eq("tomorrow", Reminders.stage(TODAY, base.copy(due = d("2026-10-20"))), Reminders.Stage.TOMORROW)
    eq("today", Reminders.stage(TODAY, base.copy(due = TODAY)), Reminders.Stage.TODAY)
    eq("overdue", Reminders.stage(TODAY, base.copy(due = d("2026-10-01"))), Reminders.Stage.OVERDUE)
    eq("paid silent", Reminders.stage(TODAY, base.copy(paid = true)), Reminders.Stage.NONE)
    val list = listOf(
        base,                                              // early, never notified -> notify
        base.copy(id = 2, notified = 1),                   // early already notified -> skip
        base.copy(id = 3, due = d("2026-10-20"), notified = 1), // now tomorrow -> notify again
        base.copy(id = 4, due = d("2027-01-01"))           // too far
    )
    eq("due ids", Reminders.due(TODAY, list).map { it.first.id }, listOf(3L, 1L))
}

fun testStages() {
    // An identity card: first reminder 90 days before, then at 30 and 7 days.
    val id = Deadline(id = 1, due = TODAY.plusDays(90), remindDays = 90)
    eq("90: early", Reminders.stage(TODAY, id), Reminders.Stage.EARLY)
    eq("31: still early", Reminders.stage(TODAY, id.copy(due = TODAY.plusDays(31))), Reminders.Stage.EARLY)
    eq("30: month", Reminders.stage(TODAY, id.copy(due = TODAY.plusDays(30))), Reminders.Stage.MONTH)
    eq("8: month", Reminders.stage(TODAY, id.copy(due = TODAY.plusDays(8))), Reminders.Stage.MONTH)
    eq("7: week", Reminders.stage(TODAY, id.copy(due = TODAY.plusDays(7))), Reminders.Stage.WEEK)
    eq("1: tomorrow", Reminders.stage(TODAY, id.copy(due = TODAY.plusDays(1))), Reminders.Stage.TOMORROW)
    // 30 days: early, then the week; never "month".
    val m = Deadline(id = 2, due = TODAY.plusDays(30), remindDays = 30)
    eq("30/30: early", Reminders.stage(TODAY, m), Reminders.Stage.EARLY)
    eq("30/7: week", Reminders.stage(TODAY, m.copy(due = TODAY.plusDays(7))), Reminders.Stage.WEEK)
    // 10 days (the default): no extra stages.
    val b = Deadline(id = 3, due = TODAY.plusDays(7), remindDays = 10)
    eq("10/7: early", Reminders.stage(TODAY, b), Reminders.Stage.EARLY)
    // Each stage is said once, in order.
    eq("week after month", Reminders.due(TODAY, listOf(id.copy(due = TODAY.plusDays(7), notified = 2))).size, 1)
    eq("month not again", Reminders.due(TODAY, listOf(id.copy(due = TODAY.plusDays(20), notified = 2))).size, 0)
    check("levels grow", Reminders.Stage.entries.zipWithNext().all { (a, z) -> a.level < z.level })
}

fun testSpendsJars() {
    val oct = YearMonth.of(2026, 10)
    val incomes = listOf(Income(month = oct, amountCents = 150000))
    val spends = listOf(
        Spend(id = 1, day = d("2026-10-02"), amountCents = 4500, cat = SpendCat.SPESA),
        Spend(id = 2, day = d("2026-10-12"), amountCents = 2500, cat = SpendCat.SPESA),
        Spend(id = 3, day = d("2026-10-15"), amountCents = 6000, cat = SpendCat.AUTO),
        Spend(id = 4, day = d("2026-09-30"), amountCents = 9999, cat = SpendCat.SVAGO)
    )
    val s = Budget.month(oct, emptyList(), incomes, spends, today = TODAY)
    eq("spends of the month", s.spends.map { it.id }, listOf(3L, 2L, 1L))
    eq("spend total", s.spendCents, 13000L)
    eq("out includes spends", s.outCents, 13000L)
    eq("by category", Budget.byCategory(s.spends), listOf(SpendCat.SPESA to 7000L, SpendCat.AUTO to 6000L))

    // Holiday: 1.200 € by June 2027, nothing saved yet. Oct..Jun = 9 months.
    val trip = Jar(id = 1, name = "Vacanza", goalCents = 120000, byDate = d("2027-06-30"))
    eq("months left", Jars.monthsLeft(oct, d("2027-06-30")), 9L)
    eq("quota", Jars.quota(trip, emptyList(), oct), 13334L)  // rounded up
    // October: nothing moved yet -> the quota counts.
    eq("set aside now", Budget.month(oct, emptyList(), incomes, emptyList(), listOf(trip), emptyList(), TODAY).savedCents, 13334L)
    // 200 € put in this month: more than the quota -> 200 €.
    val moves = listOf(JarMove(id = 1, jarId = 1, day = d("2026-10-05"), amountCents = 20000))
    val now = Budget.month(oct, emptyList(), incomes, emptyList(), listOf(trip), moves, TODAY)
    eq("set aside moved", now.savedCents, 20000L)
    eq("remaining", now.remainingCents, 150000L - 20000)
    // The quota is computed at the start of the month: the 200 € don't lower October's.
    eq("quota stable in month", Jars.quota(trip, moves, oct), 13334L)
    // November: (1.200 - 200) over 8 months.
    eq("next quota", Jars.quota(trip, moves, oct.plusMonths(1)), 12500L)
    // Later months use the quota suggested now.
    eq("future set aside", Jars.setAside(oct.plusMonths(3), listOf(trip), moves, TODAY), 13334L)
    eq("after the date", Jars.setAside(YearMonth.of(2027, 7), listOf(trip), moves, TODAY), 0L)
    // Past months: only what was moved.
    eq("past", Jars.setAside(oct.minusMonths(1), listOf(trip), moves, TODAY), 0L)
    // A withdrawal this month still leaves the quota to put aside.
    val out = moves + JarMove(id = 2, jarId = 1, day = d("2026-10-18"), amountCents = -30000)
    eq("withdrawal", Jars.setAside(oct, listOf(trip), out, TODAY), -10000L + 13334L)
    // Goal reached, no date, closed: no quota.
    eq("reached", Jars.quota(trip.copy(goalCents = 10000), moves, oct.plusMonths(1)), 0L)
    eq("no date", Jars.quota(trip.copy(byDate = null), moves, oct), 0L)
    eq("closed", Jars.quota(trip.copy(closed = true), moves, oct), 0L)
    eq("progress", Jars.progress(trip, moves), 20000.0 / 120000)
    eq("saved", Jars.saved(trip, out), -10000L)
}

fun testPack() {
    val db = "SQLite format 3\u0000 and the rest".toByteArray()
    val pages = mapOf("p-1a2b.jpg" to byteArrayOf(1, 2, 3), "p-ffff.jpg" to ByteArray(5000) { it.toByte() })
    val z = Pack.pack(db, pages)
    check("pack is zip", Pack.isZip(z))
    check("db is not zip", !Pack.isZip(db))
    val back = Pack.unpack(z)
    check("db back", back.db.contentEquals(db))
    eq("pages back", back.pages.keys, pages.keys)
    check("page bytes", back.pages.getValue("p-ffff.jpg").contentEquals(pages.getValue("p-ffff.jpg")))
    check("unsafe names", !Pack.isSafeName("../x.jpg") && !Pack.isSafeName("a/b.jpg") && !Pack.isSafeName("x.png"))
    // A crafted archive with another path is refused.
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { zz ->
        zz.putNextEntry(java.util.zip.ZipEntry("pensy.db")); zz.write(db); zz.closeEntry()
        zz.putNextEntry(java.util.zip.ZipEntry("pagine/../../evil.jpg")); zz.write(db); zz.closeEntry()
    }
    check("crafted refused", runCatching { Pack.unpack(bos.toByteArray()) }.isFailure)
    // Sealed and opened like a database file.
    val key = Vault.random(32); val salt = Vault.random(16)
    val opened = Vault.open(Vault.seal(z, key, salt, 1000), key)
    check("sealed pack", Pack.isZip(opened) && Pack.unpack(opened).pages.size == 2)
}

fun testBudget() {
    val incomes = listOf(
        Income(month = YearMonth.of(2026, 9), amountCents = 173337),
        Income(month = YearMonth.of(2026, 12), amountCents = 173337),
        Income(month = YearMonth.of(2026, 12), amountCents = 150000, extra = true) // tredicesima
    )
    val dls = listOf(
        Deadline(id = 1, title = "Bollo", type = DocType.BOLLO_AUTO, amountCents = 28946, due = d("2026-11-30")),
        Deadline(id = 2, title = "Luce", type = DocType.BOLLETTA, amountCents = 6200, due = d("2026-11-10")),
        Deadline(id = 3, title = "Acqua", type = DocType.BOLLETTA, amountCents = 8624, due = d("2026-11-05")),
        Deadline(id = 4, title = "Mutuo", type = DocType.RATA, amountCents = 45000, due = d("2026-10-15"), everyMonths = 1, paid = true),
        Deadline(id = 5, title = "Mutuo", type = DocType.RATA, amountCents = 45000, due = d("2026-11-15"), everyMonths = 1, estimated = true)
    )
    val nov = Budget.month(YearMonth.of(2026, 11), dls, incomes)
    check("nov income estimated", nov.hasIncome && nov.incomeEstimated)
    eq("nov income", nov.incomeCents, 173337L)
    eq("nov out", nov.outCents, 28946L + 6200 + 8624 + 45000)
    eq("nov remaining", nov.remainingCents, 173337L - (28946 + 6200 + 8624 + 45000))
    eq("nov big", nov.bigLines.map { it.deadlineId }, listOf(5L))    // 450 € >= 20% of 1.733 €
    check("nov heavy", nov.heavy)
    // Reminders with no amount never make a month heavy.
    val reminders = (1..5).map { Deadline(id = 100L + it, amountCents = 0, due = d("2026-10-2$it")) }
    check("reminders not heavy", !Budget.month(YearMonth.of(2026, 10), reminders + Deadline(id = 99, amountCents = 40000, due = d("2026-10-03")), incomes).heavy)
    val oct = Budget.month(YearMonth.of(2026, 10), dls, incomes)
    eq("oct lines", oct.lines.size, 1)
    check("oct not heavy", !oct.heavy)
    val dec = Budget.month(YearMonth.of(2026, 12), dls, incomes)
    eq("dec income with bonus", dec.incomeCents, 323337L)
    eq("dec projected mortgage", dec.lines.map { it.projected }, listOf(true))
    val jan = Budget.month(YearMonth.of(2027, 1), dls, incomes)
    eq("jan income ignores tredicesima", jan.incomeCents, 173337L)
    val none = Budget.month(YearMonth.of(2026, 1), dls, emptyList())
    check("no income", !none.hasIncome && none.incomeCents == 0L)
    eq("range size", Budget.range(YearMonth.of(2026, 10), 6, dls, incomes).size, 6)
    val m = Budget.matchEstimated(dls, DocType.RATA, "", d("2026-11-20"))
    eq("match estimated by issuer", m?.id, 5L)
    eq("no match other type", Budget.matchEstimated(dls, DocType.BOLLETTA, "", d("2026-11-20")), null)
}

fun testPagoPa() {
    val qr = Parser.parsePagoPaQr("PAGOPA|002|301000000012345678|00514490010|12345")
    eq("qr notice", qr?.noticeCode, "301000000012345678")
    eq("qr cf", qr?.creditorCf, "00514490010")
    eq("qr amount", qr?.amountCents, 12345L)
    eq("qr junk", Parser.parsePagoPaQr("https://example.com"), null)
    val dm = "codfase=NBPA;18301000000012345678120000123456781000000086243896P1100514490010RSSMRA80A01L219X"
    val p = Parser.parsePosteDataMatrix(dm)
    eq("dm notice", p?.noticeCode, "301000000012345678")
    eq("dm amount", p?.amountCents, 8624L)
}

fun testParseBill() {
    val text = """
        SMAT S.p.A.
        Società Metropolitana Acque Torino
        Bolletta n. 2026/123456 del 02/10/2026
        Periodo di fatturazione: dal 01/07/2026 al 30/09/2026
        Servizio idrico integrato - fatturazione bimestrale
        Totale imponibile 78,40
        IVA 10% 7,84
        TOTALE DA PAGARE € 86,24
        Da pagare entro il 05/11/2026
        Codice avviso pagoPA 3018 0000 1234 5678 90
    """.trimIndent()
    val c = Parser.parse(text, emptyList(), TODAY)
    eq("bill type", c.type, DocType.BOLLETTA)
    eq("bill issuer", c.issuer, "SMAT")
    eq("bill amount", c.amountCents, 8624L)
    eq("bill due", c.due, d("2026-11-05"))
    eq("bill notice", c.noticeCode, "301800001234567890")
    eq("bill every", c.everyMonths, 2)
    // The QR code wins over OCR for the amount.
    val withQr = Parser.parse(text, listOf(Code("QR_CODE", "PAGOPA|002|301800001234567890|00512345678|8700")), TODAY)
    eq("qr overrides amount", withQr.amountCents, 8700L)
    eq("qr cf", withQr.creditorCf, "00512345678")
    check("fields", withQr.fields().contains("codice_avviso=301800001234567890"))
    eq("fields roundtrip", parseFields(withQr.fields())["cf_ente"], "00512345678")
}

fun testParseBollo() {
    val text = "Regione Piemonte\nTassa automobilistica\nTarga AB123CD\nImporto dovuto: 289,46 €\nScadenza: 31 gennaio 2027"
    val c = Parser.parse(text, emptyList(), TODAY)
    eq("bollo type", c.type, DocType.BOLLO_AUTO)
    eq("bollo issuer", c.issuer, "Regione Piemonte")
    eq("bollo amount", c.amountCents, 28946L)
    eq("bollo due", c.due, d("2027-01-31"))
    eq("bollo yearly", c.everyMonths, 12)
}

fun testParsePayslip() {
    val text = "CEDOLINO PAGA\nPeriodo di retribuzione: Settembre 2026\nTotale competenze 2.345,67\nTotale trattenute 612,30\nNETTO IN BUSTA 1.733,37"
    val c = Parser.parse(text, emptyList(), TODAY)
    eq("pay type", c.type, DocType.BUSTA_PAGA)
    eq("pay amount", c.amountCents, 173337L)
    eq("pay month", c.payMonth, YearMonth.of(2026, 9))
    eq("pay no due", c.due, null)
    eq("pay numeric month", Parser.payMonth(listOf("Mese 09/2026")), YearMonth.of(2026, 9))
}

fun testParseTicket() {
    val text = "ASL Città di Torino\nRicevuta prestazione ambulatoriale\nTicket da pagare € 36,15\nEntro il 30.10.2026"
    val c = Parser.parse(text, emptyList(), TODAY)
    eq("ticket type", c.type, DocType.TICKET)
    eq("ticket issuer", c.issuer, "ASL Città di Torino")
    eq("ticket amount", c.amountCents, 3615L)
    eq("ticket due", c.due, d("2026-10-30"))
}

fun testParseUniversity() {
    val text = "Università degli Studi di Torino\nSeconda rata contribuzione studentesca a.a. 2026/2027\nImporto € 512,00\nScadenza 16/12/2026"
    val c = Parser.parse(text, emptyList(), TODAY)
    eq("uni type", c.type, DocType.TASSE_UNIVERSITA)
    eq("uni issuer", c.issuer, "Università degli Studi di Torino")
    eq("uni amount", c.amountCents, 51200L)
    eq("uni due", c.due, d("2026-12-16"))
    eq("uni not recurring", c.everyMonths, 0)
}

fun testParseLabelBelow() {
    // OCR often splits label and value on two lines.
    val text = "Fattura Enel Energia\nTotale da pagare\n€ 62,00\nScadenza\n10/11/2026"
    val c = Parser.parse(text, emptyList(), TODAY)
    eq("split amount", c.amountCents, 6200L)
    eq("split due", c.due, d("2026-11-10"))
    eq("split issuer", c.issuer, "Enel Energia")
}

fun testClassifyWords() {
    // "sanitaria" must not count as TARI, "tariffa" neither.
    eq("tari word", Parser.classify("Azienda sanitaria, tariffa ticket"), DocType.TICKET)
    eq("nothing", Parser.classify("lorem ipsum"), DocType.ALTRO)
}

fun testVault() {
    val salt = Vault.random(16)
    val key = Vault.deriveKey("segreta-123".toCharArray(), salt, 2000)
    val plain = "SQLite format 3\u0000 dati di prova".toByteArray()
    val blob = Vault.seal(plain, key, salt, 2000)
    check("vault header", blob.copyOfRange(0, 8).contentEquals(Vault.MAGIC))
    val (back, _) = Vault.openWithPassword(blob, "segreta-123".toCharArray())
    check("vault roundtrip", back.contentEquals(plain))
    check("vault is sqlite", Vault.isSqlite(back))
    var wrong = false
    try { Vault.openWithPassword(blob, "sbagliata".toCharArray()) } catch (e: Vault.WrongPassword) { wrong = true }
    check("vault wrong password", wrong)
    val tampered = blob.copyOf().also { it[12] = (it[12] + 1).toByte() } // iterations changed
    var bad = false
    try { Vault.open(tampered, key) } catch (e: Exception) { bad = true }
    check("vault header authenticated", bad)
    var fmt = false
    try { Vault.header("hello world, not a vault at all......................".toByteArray()) } catch (e: Vault.BadFormat) { fmt = true }
    check("vault bad format", fmt)
    check("two seals differ", !Vault.seal(plain, key, salt, 2000).contentEquals(blob))
}

fun testLayout() {
    // Label and value in two OCR blocks on the same row; another row below.
    val boxes = listOf(
        Layout.Box(600, 402, 760, 430, "€ 86,24"),
        Layout.Box(40, 400, 300, 428, "TOTALE DA PAGARE"),
        Layout.Box(40, 460, 420, 488, "Da pagare entro il 05/11/2026")
    )
    eq("rows", Layout.rows(boxes), "TOTALE DA PAGARE  € 86,24\nDa pagare entro il 05/11/2026")
    val c = Parser.parse(Layout.rows(boxes), emptyList(), TODAY)
    eq("rows amount", c.amountCents, 8624L)
    eq("rows due", c.due, d("2026-11-05"))
    eq("rows empty", Layout.rows(emptyList()), "")
}

private fun fixture(name: String): String = java.io.File("core/test/fixtures/$name").readText()

private val FOLDERS = listOf(
    Folder(1, null, "Casa", "casa"), Folder(2, null, "Auto", "auto"), Folder(3, null, "Salute", "salute"),
    Folder(4, null, "Animali", "animali"), Folder(5, null, "Scuola e università", "scuola"),
    Folder(6, null, "Lavoro", "lavoro"), Folder(7, null, "Tasse e documenti", "tasse"), Folder(8, null, "Altro", "altro"),
    Folder(31, 3, "Cuore"), Folder(32, 3, "Gamba"), Folder(33, 3, "Esami del sangue"),
    Folder(11, 1, "Acqua"), Folder(12, 1, "Luce"), Folder(21, 2, "Panda")
)

/** Real output of the OCR engine built for the phone (run under qemu) on spoiled photos made by tools/ocr/sample.py. */
fun testOcrPipeline() {
    val bill = Parser.parse(Layout.fromTesseract(fixture("ocr-bolletta.tsv")), emptyList(), TODAY)
    check("ocr junk dropped", !Layout.fromTesseract(fixture("ocr-bolletta.tsv")).contains("°"))
    eq("ocr bill type", bill.type, DocType.BOLLETTA)
    eq("ocr bill amount", bill.amountCents, 8624L)
    eq("ocr bill due", bill.due, d("2026-11-05"))
    eq("ocr bill notice", bill.noticeCode, "301800001234567890")
    eq("ocr bill date", bill.docDate, d("2026-10-02"))
    eq("ocr bill folder", Sorter.suggest(bill.text, bill.type, bill.issuer, FOLDERS, emptyMap()), 11L) // Casa > Acqua

    val ref = Parser.parse(Layout.fromTesseract(fixture("ocr-referto.tsv")), emptyList(), TODAY)
    eq("ocr referto type", ref.type, DocType.REFERTO)
    eq("ocr referto date", ref.docDate, d("2026-10-14"))
    eq("ocr referto follow-up", ref.followUp, d("2027-10-14"))
    eq("ocr referto no due", ref.due, null)
    eq("ocr referto folder", Sorter.suggest(ref.text, ref.type, ref.issuer, FOLDERS, emptyMap()), 31L) // Salute > Cuore

    val bollo = Parser.parse(Layout.fromTesseract(fixture("ocr-bollo.tsv")), emptyList(), TODAY)
    eq("ocr bollo type", bollo.type, DocType.BOLLO_AUTO)
    eq("ocr bollo amount", bollo.amountCents, 28946L)
    eq("ocr bollo due", bollo.due, d("2027-01-31"))
    eq("ocr bollo folder", Sorter.suggest(bollo.text, bollo.type, bollo.issuer, FOLDERS, emptyMap()), 2L)
}

fun testClassifyMore() {
    eq("ricetta", Parser.classify("Ricetta elettronica - promemoria per l'assistito\nNRE 0101A1234567890\nPrescrizione: amoxicillina"), DocType.RICETTA)
    eq("multa", Parser.classify("Polizia Locale di Torino\nVerbale di contestazione n. 123\nViolazione art. 7 del Codice della Strada"), DocType.MULTA)
    eq("polizza", Parser.classify("Polizza RCA n. 998877\nPremio annuo 420,00\nMassimale 6.450.000"), DocType.POLIZZA)
    eq("documento", Parser.classify("Repubblica Italiana\nCarta d'identità elettronica\nScadenza 12/03/2031"), DocType.DOCUMENTO)
    eq("garanzia", Parser.classify("Documento commerciale di vendita\nLavatrice X1\nGaranzia 24 mesi"), DocType.GARANZIA)
    eq("ticket stays ticket", Parser.classify("ASL TO3\nTicket da pagare per prestazione specialistica\nVisita oculistica"), DocType.TICKET)
}

fun testFollowUp() {
    val from = d("2026-10-14")
    eq("fu months", Parser.followUp("Conclusioni: controllo tra 6 mesi.", from), d("2027-04-14"))
    eq("fu words", Parser.followUp("Si consiglia rivalutazione a sei mesi", from), d("2027-04-14"))
    eq("fu year", Parser.followUp("follow-up tra un anno", from), d("2027-10-14"))
    eq("fu weeks", Parser.followUp("Controllo fra 2 settimane", from), d("2026-10-28"))
    eq("fu none", Parser.followUp("Nessuna indicazione particolare", from), null)
}

fun testSorter() {
    eq("kind auto", Sorter.bestKind("Officina Rossi - tagliando e cambio gomme", DocType.ALTRO), FolderKind.AUTO)
    eq("kind animali", Sorter.bestKind("Clinica veterinaria San Francesco - vaccino cane Fido", DocType.ALTRO), FolderKind.ANIMALI)
    eq("kind by type", Sorter.bestKind("nessuna parola utile", DocType.BUSTA_PAGA), FolderKind.LAVORO)
    eq("leg", Sorter.suggest("Visita ortopedica: dolore al ginocchio destro", DocType.REFERTO, "", FOLDERS, emptyMap()), 32L)
    eq("blood", Sorter.suggest("Esame emocromo e glicemia", DocType.REFERTO, "", FOLDERS, emptyMap()), 33L)
    eq("car by name", Sorter.suggest("Revisione Fiat Panda targa AB123CD", DocType.ALTRO, "", FOLDERS, emptyMap()), 21L)
    eq("top when no sub", Sorter.suggest("Visita dermatologica", DocType.REFERTO, "", FOLDERS, emptyMap()), 3L)
    val rules = mapOf(Sorter.issuerKey("Studio Bianchi")!! to 7L)
    eq("learned rule wins", Sorter.suggest("Visita ortopedica ginocchio", DocType.REFERTO, " studio  BIANCHI ", FOLDERS, rules), 7L)
    eq("rule to missing folder ignored", Sorter.suggest("bolletta acqua", DocType.BOLLETTA, "X", FOLDERS, mapOf("issuer:x" to 999L)), 11L)
    check("related cardiologia", Sorter.relatedWords("Cardiologia").contains("ecg"))
    eq("path", Sorter.path(FOLDERS, 31L).map { it.name }, listOf("Salute", "Cuore"))
    eq("subtree", Sorter.subtree(FOLDERS, 3L), setOf(3L, 31L, 32L, 33L))
    eq("no folders", Sorter.suggest("x", DocType.ALTRO, "", emptyList(), emptyMap()), null)
}

fun testDocDate() {
    val c = Parser.parse("Referto\nData esame: 03/09/2026\nScadenza ritiro 30/11/2026", emptyList(), TODAY)
    eq("doc date", c.docDate, d("2026-09-03"))
    val f = Parser.parse("Bolletta del 02/10/2026\nDa pagare entro il 05/11/2026", emptyList(), TODAY)
    eq("doc date not due", f.docDate, d("2026-10-02"))
}


fun testNoDue() {
    // Paid with no due date: counted in the month it was paid, never reminded, never repeated.
    val paid = Deadline(id = 1, due = d("2026-10-05"), amountCents = 4500, paid = true, paidOn = d("2026-10-05"), noDue = true, everyMonths = 1)
    eq("nodue no recurrence", paid.recurring, false)
    eq("nodue no next", paid.next(), null)
    eq("nodue paid in month", Budget.lines(listOf(paid), d("2026-10-01"), d("2026-10-31")).map { it.due }, listOf(d("2026-10-05")))
    eq("nodue paid not next month", Budget.lines(listOf(paid), d("2026-11-01"), d("2026-11-30")).size, 0)
    // Paid later than the day it was added: the payment day counts.
    val later = paid.copy(due = d("2026-09-20"), paidOn = d("2026-10-02"))
    eq("nodue uses paid day", Budget.lines(listOf(later), d("2026-10-01"), d("2026-10-31")).map { it.due }, listOf(d("2026-10-02")))
    // Unpaid with no due date: outside every month and silent.
    val open = Deadline(id = 2, due = d("2026-01-01"), amountCents = 9000, noDue = true)
    eq("nodue unpaid not in budget", Budget.lines(listOf(open), d("2025-01-01"), d("2027-12-31")).size, 0)
    eq("nodue unpaid silent", Reminders.stage(TODAY, open), Reminders.Stage.NONE)
    eq("nodue not in due list", Reminders.due(TODAY, listOf(open)).size, 0)
    val est = Deadline(id = 3, due = d("2026-11-05"), type = DocType.BOLLETTA, issuer = "SMAT", estimated = true, noDue = true)
    eq("nodue not matched as estimate", Budget.matchEstimated(listOf(est), DocType.BOLLETTA, "SMAT", d("2026-11-03")), null)
}

private fun t(s: String): java.time.LocalTime = java.time.LocalTime.parse(s)
private fun at(s: String): java.time.LocalDateTime = java.time.LocalDateTime.parse(s)

fun testMeds() {
    val m = Medicine(id = 1, name = "Cardioaspirina", times = listOf(t("20:00"), t("08:00")), start = d("2026-10-01"))
    eq("takes daily", Meds.takesOn(m, TODAY), true)
    eq("not before start", Meds.takesOn(m, d("2026-09-30")), false)
    eq("doses sorted", Meds.dosesOn(m, TODAY), listOf(t("08:00"), t("20:00")))
    val alt = m.copy(everyDays = 2)
    eq("alternate on", Meds.takesOn(alt, d("2026-10-03")), true)
    eq("alternate off", Meds.takesOn(alt, d("2026-10-04")), false)
    eq("as needed never", Meds.takesOn(m.copy(everyDays = 0), TODAY), false)
    eq("until", Meds.takesOn(m.copy(until = d("2026-10-18")), TODAY), false)
    eq("stopped", Meds.takesOn(m.copy(active = false), TODAY), false)
    eq("per day", Meds.perDay(m.copy(amount = 0.5)), 1.0)

    // Stock: 10 tablets at the start of the 19th, two a day -> last full day the 23rd, out on the 24th.
    val s = m.copy(stockMode = StockMode.COUNT, stock = 10.0, stockDate = TODAY, leadDays = 2)
    eq("stock today", Meds.stockOn(s, TODAY), 10.0)
    eq("stock in 2 days", Meds.stockOn(s, d("2026-10-21")), 6.0)
    eq("left now after morning", Meds.leftNow(s, at("2026-10-19T09:00")), 9.0)
    eq("left now early", Meds.leftNow(s, at("2026-10-19T07:00")), 10.0)
    eq("run out", Meds.runOut(s, TODAY), d("2026-10-24"))
    eq("run out from later", Meds.runOut(s, d("2026-10-22")), d("2026-10-24"))
    eq("reorder", Meds.reorderOn(s, TODAY), d("2026-10-22"))
    eq("never runs out before the end", Meds.runOut(s.copy(until = d("2026-10-22")), TODAY), null)
    eq("odd stock", Meds.runOut(s.copy(stock = 3.0), TODAY), d("2026-10-20"))
    eq("start of day", Meds.stockAtStartOfDay(s, 9.0, at("2026-10-19T12:00")), 10.0)
    eq("not counted", Meds.stockOn(m, TODAY), null)
    eq("no reorder without mode", Meds.reorderOn(m, TODAY), null)

    // Fixed interval
    val iv = m.copy(stockMode = StockMode.INTERVAL, orderEvery = 30, lastOrder = d("2026-09-25"))
    eq("interval reorder", Meds.reorderOn(iv, TODAY), d("2026-10-25"))
    eq("interval from start", Meds.reorderOn(iv.copy(lastOrder = null), TODAY), d("2026-10-31"))

    // To order: once per date
    val late = s.copy(stock = 4.0)
    eq("to order", Meds.toOrder(listOf(late), TODAY).map { it.second }, listOf(d("2026-10-19")))
    eq("to order once", Meds.toOrder(listOf(late.copy(orderNotified = d("2026-10-19"))), TODAY).size, 0)

    // Next dose
    val other = Medicine(id = 2, name = "Eutirox", times = listOf(t("08:00")), start = d("2026-01-01"))
    val nd = Meds.nextDose(listOf(m, other), at("2026-10-19T07:30"))!!
    eq("next dose time", nd.first, at("2026-10-19T08:00"))
    eq("next dose both", nd.second.map { it.id }.sorted(), listOf(1L, 2L))
    eq("next dose evening", Meds.nextDose(listOf(m, other), at("2026-10-19T08:00"))!!.first, at("2026-10-19T20:00"))
    eq("next dose tomorrow", Meds.nextDose(listOf(m, other), at("2026-10-19T21:00"))!!.first, at("2026-10-20T08:00"))
    eq("next dose skips silent", Meds.nextDose(listOf(m.copy(remind = false)), at("2026-10-19T07:00")), null)
    eq("next dose future start", Meds.nextFor(m.copy(start = d("2026-11-02")), at("2026-10-19T07:00")), at("2026-11-02T08:00"))
    eq("next dose weekly", Meds.nextFor(m.copy(everyDays = 7, times = listOf(t("09:00"))), at("2026-10-19T10:00")), at("2026-10-22T09:00"))
    eq("between", Meds.dosesBetween(listOf(m, other), at("2026-10-19T07:00"), at("2026-10-19T08:00")).size, 2)
    eq("between excludes start", Meds.dosesBetween(listOf(m), at("2026-10-19T08:00"), at("2026-10-19T19:59")).size, 0)
    eq("schedule", Meds.schedule(listOf(m, other), TODAY).map { it.first to it.second.id },
        listOf(t("08:00") to 1L, t("08:00") to 2L, t("20:00") to 1L))
}

fun testVisits() {
    val booked = Visit(id = 1, title = "Visita cardiologica", status = VisitStatus.BOOKED, date = d("2026-10-21"), time = t("10:30"), remindDays = 3)
    eq("booked early", Visits.stage(TODAY, booked), Reminders.Stage.EARLY)
    eq("booked tomorrow", Visits.stage(d("2026-10-20"), booked), Reminders.Stage.TOMORROW)
    eq("booked today", Visits.stage(d("2026-10-21"), booked), Reminders.Stage.TODAY)
    eq("booked past silent", Visits.stage(d("2026-10-22"), booked), Reminders.Stage.NONE)
    eq("booked past is done", booked.isDone(d("2026-10-22")), true)
    val todo = Visit(id = 2, title = "Controllo", status = VisitStatus.TODO, dueBy = d("2026-11-10"), remindDays = 30)
    eq("todo early", Visits.stage(TODAY, todo), Reminders.Stage.EARLY)
    eq("todo overdue", Visits.stage(d("2026-11-11"), todo), Reminders.Stage.OVERDUE)
    eq("todo no date silent", Visits.stage(TODAY, todo.copy(dueBy = null)), Reminders.Stage.NONE)
    eq("done silent", Visits.stage(TODAY, booked.copy(status = VisitStatus.DONE)), Reminders.Stage.NONE)
    eq("due once", Visits.due(TODAY, listOf(booked.copy(notified = 1))).size, 0)
    eq("priority B", Visits.priorityDays("b"), 10)
    eq("priority none", Visits.priorityDays(""), null)
    val done = Visit(id = 3, title = "Esami", status = VisitStatus.DONE, date = d("2026-05-02"))
    val all = listOf(todo, booked, done, todo.copy(id = 4, dueBy = null), todo.copy(id = 5, dueBy = d("2026-10-30")))
    eq("upcoming", Visits.upcoming(all, TODAY).map { it.id }, listOf(1L))
    eq("to book order", Visits.toBook(all).map { it.id }, listOf(5L, 2L, 4L))
    eq("done list", Visits.done(all, d("2026-10-25")).map { it.id }, listOf(1L, 3L))

    val m = Medicine(id = 9, name = "Eutirox", start = d("2026-10-01"), until = d("2026-10-28"),
        stockMode = StockMode.INTERVAL, orderEvery = 20, lastOrder = d("2026-10-01"))
    val ev = HealthCalendar.events(listOf(m), all, d("2026-10-01"), d("2026-10-31"), TODAY)
    eq("events", ev.map { it.kind to it.id }, listOf(
        HealthEvent.Kind.REORDER to 9L,            // was due the 21st: shown from today, the 21st is after today
        HealthEvent.Kind.VISIT to 1L,
        HealthEvent.Kind.LAST_DOSE to 9L,
        HealthEvent.Kind.TO_BOOK to 5L
    ).sortedBy { p -> ev.indexOfFirst { it.kind == p.first && it.id == p.second } })
    eq("events dates", ev.map { it.date }, listOf(d("2026-10-21"), d("2026-10-21"), d("2026-10-28"), d("2026-10-30")))
    val lateOrder = m.copy(lastOrder = d("2026-09-20"))
    eq("overdue order shown today", HealthCalendar.events(listOf(lateOrder), emptyList(), d("2026-10-01"), d("2026-10-31"), TODAY).first().date, TODAY)
}

fun testDoseLog() {
    // Two a day, 10 tablets at the start of the 19th.
    val m = Medicine(id = 1, name = "Cardioaspirina", times = listOf(t("08:00"), t("20:00")), start = d("2026-10-01"),
        stockMode = StockMode.COUNT, stock = 10.0, stockDate = TODAY, leadDays = 2)
    fun log(day: String, slot: String?, s: DoseStatus, atS: String) =
        DoseLog(medId = 1, day = d(day), slot = slot?.let { t(it) }, status = s, at = at(atS))
    // The evening dose of the 19th skipped: one tablet more left.
    val skipped = m.copy(log = listOf(log("2026-10-19", "20:00", DoseStatus.SKIPPED, "2026-10-19T21:00")))
    eq("skip used", Meds.usedOn(skipped, TODAY), 1.0)
    eq("skip stock next day", Meds.stockOn(skipped, d("2026-10-20")), 9.0)
    eq("skip runs out later", Meds.runOut(skipped, TODAY), d("2026-10-24"))
    eq("skip left tonight", Meds.leftNow(skipped, at("2026-10-19T21:00")), 9.0)
    // A morning dose taken early (07:30, before 08:00): counted now.
    val early = m.copy(log = listOf(log("2026-10-19", "08:00", DoseStatus.TAKEN, "2026-10-19T07:30")))
    eq("early taken", Meds.leftNow(early, at("2026-10-19T07:45")), 9.0)
    eq("early not twice", Meds.leftNow(early, at("2026-10-19T09:00")), 9.0)
    eq("start of day with early", Meds.stockAtStartOfDay(early, 9.0, at("2026-10-19T07:45")), 10.0)
    // An extra dose of a medicine taken when needed.
    val prn = m.copy(id = 2, everyDays = 0, times = emptyList(),
        log = listOf(DoseLog(medId = 2, day = TODAY, slot = null, status = DoseStatus.TAKEN, at = at("2026-10-19T15:00"))))
    eq("extra used", Meds.usedOn(prn, TODAY), 1.0)
    eq("extra left", Meds.leftNow(prn, at("2026-10-19T16:00")), 9.0)
    eq("extra not yet", Meds.leftNow(prn, at("2026-10-19T14:00")), 10.0)
    eq("extra list", Meds.extraOn(prn, TODAY).size, 1)
    // Status and notifications: a marked dose is not notified.
    eq("status", Meds.statusOf(skipped, TODAY, t("20:00")), DoseStatus.SKIPPED)
    eq("status none", Meds.statusOf(skipped, TODAY, t("08:00")), null)
    eq("marked not notified", Meds.dosesBetween(listOf(skipped), at("2026-10-19T19:00"), at("2026-10-19T20:00")).size, 0)
    // Adherence over three days (17, 18, 19 until 21:00): 6 due.
    val week = m.copy(start = d("2026-10-17"), log = listOf(
        log("2026-10-17", "08:00", DoseStatus.TAKEN, "2026-10-17T08:05"),
        log("2026-10-17", "20:00", DoseStatus.TAKEN, "2026-10-17T20:10"),
        log("2026-10-18", "08:00", DoseStatus.SKIPPED, "2026-10-18T09:00"),
        log("2026-10-19", "08:00", DoseStatus.TAKEN, "2026-10-19T08:00")
    ))
    val a = Meds.adherence(week, d("2026-10-10"), at("2026-10-19T21:00"))
    eq("adherence due", a.due, 6)
    eq("adherence taken", a.taken, 3)
    eq("adherence skipped", a.skipped, 1)
    eq("adherence unmarked", a.unmarked, 2)
    eq("adherence percent", a.percent, 75)
    eq("adherence before evening", Meds.adherence(week, d("2026-10-19"), at("2026-10-19T12:00")).due, 1)
    eq("adherence nothing marked", Adherence(4, 0, 0).percent, null)
}

fun testBoostersMeasures() {
    val v = HealthItem(id = 1, kind = HealthKind.VACCINE, name = "Tetano", date = d("2016-11-15"), nextDue = d("2026-11-15"))
    eq("booster early", Boosters.stage(TODAY, v), Reminders.Stage.EARLY)        // 27 days
    eq("booster week", Boosters.stage(d("2026-11-10"), v), Reminders.Stage.WEEK)
    eq("booster overdue", Boosters.stage(d("2026-11-16"), v), Reminders.Stage.OVERDUE)
    eq("booster none far", Boosters.stage(d("2026-09-01"), v), Reminders.Stage.NONE)
    eq("not a vaccine", Boosters.stage(TODAY, v.copy(kind = HealthKind.CONDITION)), Reminders.Stage.NONE)
    eq("booster once", Boosters.due(TODAY, listOf(v, v.copy(id = 2, notified = 1))).map { it.first.id }, listOf(1L))
    val ev = HealthCalendar.events(emptyList(), emptyList(), d("2026-11-01"), d("2026-11-30"), TODAY, listOf(v))
    eq("booster in calendar", ev.map { it.kind }, listOf(HealthEvent.Kind.BOOSTER))

    eq("parse pressure", Measures.parse(MeasureKind.PRESSURE, "135/85"), Triple(135.0, 85.0, null))
    eq("parse pressure pulse", Measures.parse(MeasureKind.PRESSURE, "120 80 72"), Triple(120.0, 80.0, 72.0))
    eq("pressure swapped refused", Measures.parse(MeasureKind.PRESSURE, "80/120"), null)
    eq("pressure one number", Measures.parse(MeasureKind.PRESSURE, "120"), null)
    eq("parse weight comma", Measures.parse(MeasureKind.WEIGHT, "72,5 kg"), Triple(72.5, null, null))
    eq("parse temperature", Measures.parse(MeasureKind.TEMPERATURE, "36.8"), Triple(36.8, null, null))
    eq("temperature absurd", Measures.parse(MeasureKind.TEMPERATURE, "368"), null)
    eq("parse glucose", Measures.parse(MeasureKind.GLUCOSE, "98"), Triple(98.0, null, null))
    val list = listOf(
        Measure(id = 1, kind = MeasureKind.PRESSURE, at = at("2026-10-10T08:00"), v1 = 130.0, v2 = 85.0),
        Measure(id = 2, kind = MeasureKind.PRESSURE, at = at("2026-10-18T08:00"), v1 = 120.0, v2 = 75.0),
        Measure(id = 3, kind = MeasureKind.WEIGHT, at = at("2026-10-12T08:00"), v1 = 72.0),
        Measure(id = 4, kind = MeasureKind.PRESSURE, at = at("2026-09-01T08:00"), v1 = 140.0, v2 = 90.0)
    )
    eq("of kind", Measures.of(list, MeasureKind.PRESSURE).map { it.id }, listOf(4L, 1L, 2L))
    eq("of range", Measures.of(list, MeasureKind.PRESSURE, at("2026-10-01T00:00")).map { it.id }, listOf(1L, 2L))
    eq("latest", Measures.latest(list, MeasureKind.PRESSURE)?.id, 2L)
    val st = Measures.stats(Measures.of(list, MeasureKind.PRESSURE))!!
    eq("stats", listOf(st.count.toDouble(), st.min, st.max, st.avg, st.min2!!, st.max2!!), listOf(3.0, 120.0, 140.0, 130.0, 75.0, 90.0))
    eq("stats empty", Measures.stats(emptyList()), null)
}

fun testPrescription() {
    val text = """
        SERVIZIO SANITARIO NAZIONALE
        RICETTA ELETTRONICA - PROMEMORIA PER L'ASSISTITO
        REGIONE PIEMONTE
        NRE 0100A 40056 73219
        Cognome e Nome: ROSSI MARIO
        Data: 14/10/2026
        AMOXICILLINA 1 g 12 cpr - 1 confezione
    """.trimIndent()
    val c = Parser.parse(text, today = TODAY)
    eq("rx type", c.type, DocType.RICETTA)
    eq("rx nre", c.nre, "0100A4005673219")
    eq("rx date", c.docDate, d("2026-10-14"))
    eq("rx valid until", c.due, d("2026-11-13"))
    check("rx fields", c.fields().contains("nre=0100A4005673219"))
    // A prescription for a visit has no 30-day limit (it is booked by priority).
    val visit = text.replace("AMOXICILLINA 1 g 12 cpr - 1 confezione", "Prestazione: VISITA CARDIOLOGICA  Priorità: D")
    eq("rx visit no due", Parser.parse(visit, today = TODAY).due, null)
    // From the barcode
    eq("nre from code", Parser.nre("", listOf(Code("CODE_39", "0100A4005673219"))), "0100A4005673219")
    // An 18-digit pagoPA code or a fiscal code is not an NRE.
    eq("no nre in numbers", Parser.nre("Codice avviso 301000000012345678 CF 80087670016"), null)
}

fun testCsv() {
    eq("csv field plain", Csv.field("Enel"), "Enel")
    eq("csv field quoted", Csv.field("a;b"), "\"a;b\"")
    eq("csv field quotes", Csv.field("il \"bollo\""), "\"il \"\"bollo\"\"\"")
    eq("csv number", Csv.number(72.5), "72,5")
    eq("csv number round", Csv.number(36.849, 1), "36,8")
    eq("csv integer", Csv.number(120.0), "120")
    eq("csv money", Csv.money(123456), "1234,56")
    val t = Csv.table(listOf("Data", "Valore"), listOf(listOf("2026-10-19", "120/80")))
    check("csv bom", t.startsWith("﻿"))
    eq("csv lines", t.removePrefix("﻿"), "Data;Valore\r\n2026-10-19;120/80\r\n")
}

fun testDocuments() {
    val cie = """
        REPUBBLICA ITALIANA
        CARTA DI IDENTITÀ / IDENTITY CARD
        COGNOME / SURNAME ROSSI
        LUOGO E DATA DI NASCITA / PLACE AND DATE OF BIRTH TORINO 03.05.1990
        EMISSIONE / ISSUING 12.06.2019
        SCADENZA / EXPIRY
        03.05.2030
    """.trimIndent()
    val c = Parser.parse(cie, today = TODAY)
    eq("cie type", c.type, DocType.DOCUMENTO)
    eq("cie expiry", c.due, d("2030-05-03"))
    eq("cie issue date (not birth)", c.docDate, d("2019-06-12"))
    // Italian driving licence: 4a issue, 4b expiry.
    val pat = "PATENTE DI GUIDA\n1. ROSSI\n3. 03/05/90 TORINO\n4a. 14.02.2018  4b. 03.05.2028\n4c. MIT-UCO"
    val p = Parser.parse(pat, today = TODAY)
    eq("licence type", p.type, DocType.DOCUMENTO)
    eq("licence 4b", p.due, d("2028-05-03"))
    eq("licence 4a", p.docDate, d("2018-02-14"))
    // A passport with only the issue date: ten years.
    val pass = "PASSAPORTO / PASSPORT\nData di rilascio / Date of issue 20 OTT 2020\nAutorità: QUESTURA DI TORINO"
    eq("passport computed", Parser.parse(pass, today = TODAY).due, d("2030-10-20"))
    // English expiry
    val en = "PASSPORT\nDate of issue 02 JAN 2018\nDate of expiry 02 JAN 2028"
    eq("passport expiry en", Parser.parse(en, today = TODAY).due, d("2028-01-02"))
    eq("id kind", Parser.idKind("Tessera Sanitaria - TEAM"), Parser.IdKind.TESSERA_SANITARIA)
}

fun testWarranty() {
    val receipt = """
        MEDIAWORLD
        DOCUMENTO COMMERCIALE di vendita o prestazione
        LAVATRICE SAMSUNG WW90   499,00
        TOTALE COMPLESSIVO 499,00
        Data 05/10/2026 ore 17:32
    """.trimIndent()
    val c = Parser.parse(receipt, today = TODAY)
    eq("warranty type", c.type, DocType.GARANZIA)
    eq("warranty date", c.docDate, d("2026-10-05"))
    eq("warranty 2 years", c.due, d("2028-10-05"))
    eq("warranty price", c.amountCents, 49900L)
    eq("warranty months", Parser.warrantyMonths("Estensione di garanzia 3 anni"), 36)
    eq("warranty years before", Parser.warrantyMonths("5 anni di garanzia sul motore"), 60)
    eq("warranty none", Parser.warrantyMonths("Garanzia legale"), null)
}

fun testEnglishBill() {
    val bill = """
        Thames Water
        INVOICE 2026-1043
        Invoice date: 01 October 2026
        Amount due: £ 129.90
        Due date: November 15, 2026
    """.trimIndent()
    val c = Parser.parse(bill, today = TODAY)
    eq("en type", c.type, DocType.BOLLETTA)
    eq("en due", c.due, d("2026-11-15"))
    eq("en amount", c.amountCents, 12990L)
    eq("en dates", Parser.datesIn("Due date: November 15, 2026"), listOf(d("2026-11-15")))
}

fun testReceiptSubscription() {
    eq("receipt", Parser.classify("Ricevuta di pagamento pagoPA\nEsito: eseguito\nImporto 45,00"), DocType.RICEVUTA)
    eq("subscription", Parser.classify("Abbonamento palestra annuale con tacito rinnovo. Disdetta entro 30 giorni"), DocType.ABBONAMENTO)
}

fun testTax() {
    val spends = listOf(
        Spend(id = 1, profileId = 1, day = d("2026-03-02"), amountCents = 10000, cat = SpendCat.SALUTE, tax = "sanitarie", cash = true),
        Spend(id = 2, profileId = 1, day = d("2026-05-10"), amountCents = 25000, cat = SpendCat.SALUTE, tax = "sanitarie"),
        Spend(id = 3, profileId = 1, day = d("2026-06-01"), amountCents = 70000, cat = SpendCat.ANIMALI, tax = "veterinarie"),
        Spend(id = 4, profileId = 2, day = d("2026-09-01"), amountCents = 30000, cat = SpendCat.ISTRUZIONE, tax = "sport"),
        Spend(id = 5, profileId = 3, day = d("2026-09-01"), amountCents = 10000, cat = SpendCat.ISTRUZIONE, tax = "sport"),
        Spend(id = 6, profileId = 1, day = d("2026-02-01"), amountCents = 30000, cat = SpendCat.TRASPORTI, tax = "trasporti", cash = true),
        Spend(id = 7, profileId = 1, day = d("2025-12-31"), amountCents = 99999, cat = SpendCat.SALUTE, tax = "sanitarie"),
        Spend(id = 8, profileId = 1, day = d("2026-02-01"), amountCents = 5000, cat = SpendCat.SPESA)
    )
    val dls = listOf(
        Deadline(id = 10, profileId = 1, type = DocType.TICKET, amountCents = 3615, due = d("2026-04-01"), paid = true, paidOn = d("2026-04-02")),
        Deadline(id = 11, profileId = 1, type = DocType.TICKET, amountCents = 3615, due = d("2026-04-01")),          // not paid
        Deadline(id = 12, profileId = 1, type = DocType.TASSE_UNIVERSITA, amountCents = 80000, due = d("2026-11-05"), paid = true, paidOn = d("2026-10-30"))
    )
    val items = Tax.items(2026, dls, spends)
    eq("tax items", items.map { it.id }.sorted(), listOf(1L, 2L, 3L, 4L, 5L, 6L, 10L, 12L))
    val sum = Tax.summary(items).associateBy { it.cat }
    // Medical: 100 + 250 + 36,15 = 386,15; cash allowed; minus 129,11 -> 257,04; 19% -> 48,83
    eq("medical total", sum.getValue(TaxCat.SANITARIE).totalCents, 38615L)
    eq("medical base", sum.getValue(TaxCat.SANITARIE).baseCents, 25704L)
    eq("medical estimate", sum.getValue(TaxCat.SANITARIE).estimateCents, 4883L)
    // Vet: capped at 550, minus 129,11 -> 420,89
    eq("vet base", sum.getValue(TaxCat.VETERINARIE).baseCents, 42089L)
    // Sport: per child, 210 + 100
    eq("sport base", sum.getValue(TaxCat.SPORT).baseCents, 31000L)
    // Transport paid in cash: listed, not counted
    eq("transport base", sum.getValue(TaxCat.TRASPORTI).baseCents, 0L)
    eq("transport cash", sum.getValue(TaxCat.TRASPORTI).cashLeftOut, 1)
    eq("university", sum.getValue(TaxCat.UNIVERSITA).baseCents, 80000L)
    eq("suggest health", Tax.suggest(SpendCat.SALUTE), TaxCat.SANITARIE)
    eq("cat of", TaxCat.of(""), null)
}

fun testFixedAndCar() {
    val dls = listOf(
        Deadline(id = 1, type = DocType.ABBONAMENTO, issuer = "Palestra", title = "", amountCents = 4500, due = d("2026-11-01"), everyMonths = 1),
        Deadline(id = 2, type = DocType.ABBONAMENTO, issuer = "Palestra", title = "", amountCents = 4500, due = d("2026-12-01"), everyMonths = 1, estimated = true),
        Deadline(id = 3, type = DocType.BOLLETTA, issuer = "Iren", amountCents = 12000, due = d("2026-11-20"), everyMonths = 2),
        Deadline(id = 4, type = DocType.POLIZZA, issuer = "RC", amountCents = 60000, due = d("2027-03-01"), everyMonths = 12),
        Deadline(id = 5, type = DocType.BOLLETTA, issuer = "Iren", amountCents = 12000, due = d("2026-09-20"), everyMonths = 2, paid = true),
        Deadline(id = 6, type = DocType.MULTA, amountCents = 4200, due = d("2026-11-02"))
    )
    val f = Budget.fixedCosts(dls)
    eq("fixed ids", f.map { it.first.id }, listOf(3L, 4L, 1L))
    eq("fixed yearly", f.map { it.second }, listOf(72000L, 60000L, 54000L))

    eq("revision first", CarRules.nextRevision(d("2024-03-10"), null, TODAY), d("2028-03-31"))
    eq("revision cycle", CarRules.nextRevision(d("2015-06-01"), null, TODAY), d("2027-06-30"))  // 2019, 21, 23, 25, 27
    eq("revision after last", CarRules.nextRevision(d("2015-06-01"), d("2025-11-12"), TODAY), d("2027-11-30"))
    eq("revision this month", CarRules.nextRevision(d("2016-10-05"), null, TODAY), d("2026-10-31"))
    eq("next yearly", CarRules.next(d("2025-04-10"), TODAY), d("2027-04-10"))
    eq("next yearly soon", CarRules.next(d("2025-11-10"), TODAY), d("2026-11-10"))
    eq("winter", CarRules.winterTyres(TODAY), d("2026-11-15"))
    eq("winter next year", CarRules.winterTyres(d("2026-11-20")), d("2027-11-15"))
    eq("summer", CarRules.summerTyres(TODAY), d("2027-05-15"))
}

fun testInvest() {
    val a = Investment(id = 1, name = "All-World", ratePct = 6.0, planCents = 10000, planEvery = 1, planDay = 5, planFrom = d("2026-01-05"))
    eq("plan dates", Invest.planDates(a, d("2026-01-01"), d("2026-04-30")), listOf(d("2026-01-05"), d("2026-02-05"), d("2026-03-05"), d("2026-04-05")))
    eq("plan due all", Invest.planDue(a, TODAY).size, 10)
    eq("plan due after last", Invest.planDue(a.copy(planLast = d("2026-08-05")), TODAY), listOf(d("2026-09-05"), d("2026-10-05")))
    eq("plan day 31", Invest.planDates(a.copy(planDay = 31, planFrom = d("2026-01-31")), d("2026-01-01"), d("2026-03-31")),
        listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31")))
    eq("plan until", Invest.planDates(a.copy(planUntil = d("2026-03-01")), d("2026-01-01"), d("2026-12-31")).size, 2)
    eq("plan quarterly", Invest.planDates(a.copy(planEvery = 3), d("2026-01-01"), d("2026-12-31")).map { it.monthValue }, listOf(1, 4, 7, 10))
    eq("plan closed", Invest.planDates(a.copy(closed = true), d("2026-01-01"), d("2026-12-31")).size, 0)

    val moves = listOf(
        InvestMove(id = 1, investmentId = 1, day = d("2026-01-05"), amountCents = 10000, units = 1.0),
        InvestMove(id = 2, investmentId = 1, day = d("2026-02-05"), amountCents = 10000, units = 0.8)
    )
    val price = InvestValue(id = 1, investmentId = 1, day = d("2026-03-01"), price = 110.0)
    val before = Invest.position(a, moves, listOf(price), d("2026-02-28"))
    eq("no update yet", before.valueCents, 20000L)
    eq("no update day", before.updated, null)
    val p = Invest.position(a, moves, listOf(price), d("2026-03-02"))
    eq("units", p.units, 1.8)
    eq("value from price", p.valueCents, 19800L)
    eq("gain", p.gainCents, -200L)
    check("gain pct", Math.abs(p.gainPct + 1.0) < 1e-9) { "${p.gainPct}" }

    val more = moves + InvestMove(id = 3, investmentId = 1, day = d("2026-05-05"), amountCents = 10000)
    val total = InvestValue(id = 2, investmentId = 1, day = d("2026-04-01"), valueCents = 25000)
    val q = Invest.position(a, more, listOf(price, total), d("2026-06-01"))
    eq("value from total + later money", q.valueCents, 35000L)
    eq("units unknown", q.units, null)
    // A later unit price cannot be used without the units: the total still counts.
    val q2 = Invest.position(a, more, listOf(price, total, InvestValue(id = 3, investmentId = 1, day = d("2026-07-01"), price = 120.0)), d("2026-07-02"))
    eq("price unusable", q2.valueCents, 35000L)
    eq("price unusable day", q2.updated, d("2026-04-01"))
    eq("units for", Invest.unitsFor(10000, 110.0), 0.909091)
    eq("units no price", Invest.unitsFor(10000, null), null)

    val hist = Invest.history(listOf(a), moves, listOf(price), d("2026-03-15"))
    eq("history months", hist.map { it.month }, listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)))
    eq("history values", hist.map { it.valueCents }, listOf(10000L, 20000L, 19800L))

    val calc = Invest.calculator(0, 10000, 0.0, 1)
    eq("calc flat", calc.last(), ProjPoint(1, 120000, 120000))
    eq("calc 12%", Invest.calculator(100000, 0, 12.0, 1).last().valueCents, 112000L)
    val ten = Invest.calculator(0, 10000, 6.0, 10)
    eq("calc 10y invested", ten.last().investedCents, 1200000L)
    eq("calc 10y value", ten.last().valueCents, 1624734L)
    eq("calc points", ten.size, 11)

    val tenMoves = Invest.planDue(a, TODAY).mapIndexed { i, day -> InvestMove(id = 10L + i, investmentId = 1, day = day, amountCents = 10000, plan = true) }
    val flat = a.copy(ratePct = 0.0)
    val proj = Invest.projection(flat, tenMoves, emptyList(), TODAY, 1)
    eq("projection start", proj.first(), ProjPoint(0, 100000, 100000))
    eq("projection 1y", proj.last(), ProjPoint(1, 220000, 220000))
    val both = Invest.projectionAll(listOf(flat, Investment(id = 2, name = "Conto", ratePct = 0.0)), tenMoves, emptyList(), TODAY, 1)
    eq("projection all", both.last().investedCents, 220000L)

    val flows = Invest.flows(listOf(a), tenMoves, d("2026-10-01"), d("2026-12-31"), TODAY)
    eq("flows", flows.map { it.day to it.planned }, listOf(d("2026-10-05") to false, d("2026-11-05") to true, d("2026-12-05") to true))
    val nov = Budget.month(YearMonth.of(2026, 11), emptyList(), listOf(Income(month = YearMonth.of(2026, 11), amountCents = 200000)), invest = flows, today = TODAY)
    eq("budget invested", nov.investedCents, 10000L)
    eq("budget remaining", nov.remainingCents, 190000L)
    eq("rate etf", Invest.suggestedRate(InvestKind.ETF), 6.0)
    eq("kind of", InvestKind.of("pensione"), InvestKind.PENSIONE)
}

fun testTrend() {
    val incomes = listOf(
        Income(month = YearMonth.of(2025, 12), amountCents = 150000),
        Income(month = YearMonth.of(2025, 12), amountCents = 140000, extra = true),
        Income(month = YearMonth.of(2026, 8), amountCents = 150000),
        Income(month = YearMonth.of(2026, 9), amountCents = 150000),
        Income(month = YearMonth.of(2026, 10), amountCents = 155000)
    )
    val spends = listOf(
        Spend(day = d("2026-09-10"), amountCents = 5000, cat = SpendCat.SPESA),
        Spend(day = d("2026-10-02"), amountCents = 3000, cat = SpendCat.TRASPORTI),
        Spend(day = d("2026-10-05"), amountCents = 2000, cat = SpendCat.SPESA)
    )
    val dls = listOf(
        Deadline(id = 1, type = DocType.BOLLETTA, amountCents = 8000, due = d("2026-09-20"), paid = true),
        Deadline(id = 2, type = DocType.BOLLETTA, amountCents = 4000, due = d("2026-10-25"), everyMonths = 1)
    )
    val jarMoves = listOf(JarMove(jarId = 1, day = d("2026-10-10"), amountCents = 5000))
    val flows = listOf(InvestFlow(d("2026-10-05"), 10000, false), InvestFlow(d("2026-11-05"), 10000, true))
    val oct = Trend.month(YearMonth.of(2026, 10), dls, incomes, spends, jarMoves, flows)
    eq("oct income", oct.incomeCents, 155000L)
    eq("oct out", oct.outCents, 9000L)
    eq("oct saved", oct.savedCents, 15000L)
    eq("oct left", oct.leftCents, 131000L)
    val nov = Trend.month(YearMonth.of(2026, 11), dls, incomes, spends, jarMoves, flows)
    eq("planned not counted", nov.savedCents, 0L)
    eq("projected not counted", nov.outCents, 0L)
    val sep = Trend.month(YearMonth.of(2026, 9), dls, incomes, spends, jarMoves, flows)
    eq("sep out", sep.outCents, 13000L)

    val first = Trend.firstMonth(YearMonth.of(2026, 10), dls, incomes, spends, jarMoves)
    eq("first month", first, YearMonth.of(2025, 12))
    eq("range six", Trend.range(TrendPeriod.SIX, YearMonth.of(2026, 10), first), YearMonth.of(2026, 5) to YearMonth.of(2026, 10))
    eq("range year", Trend.range(TrendPeriod.YEAR, YearMonth.of(2026, 10), first).first, YearMonth.of(2026, 1))
    eq("range all", Trend.range(TrendPeriod.ALL, YearMonth.of(2026, 10), first).first, YearMonth.of(2025, 12))

    val months = Trend.months(YearMonth.of(2025, 12), YearMonth.of(2026, 10), dls, incomes, spends, jarMoves, flows)
    eq("months", months.size, 11)
    val years = Trend.byYear(months)
    eq("years", years.map { it.first.year }, listOf(2025, 2026))
    eq("year 2025 income", years[0].incomeCents, 290000L)
    eq("year 2026 income", years[1].incomeCents, 455000L)
    eq("year 2026 months", years[1].months, 10)
    eq("sum", Trend.sum(months)?.incomeCents, 745000L)
    eq("sum empty", Trend.sum(emptyList()), null)

    eq("categories", Trend.categories(spends, YearMonth.of(2026, 10), YearMonth.of(2026, 10)),
        listOf(SpendCat.TRASPORTI to 3000L, SpendCat.SPESA to 2000L))
    val pay = Trend.pay(incomes, YearMonth.of(2026, 8), YearMonth.of(2026, 10))
    eq("pay", pay.map { it.second }, listOf(150000L, 150000L, 155000L))
    val ch = Trend.payChange(pay)
    check("pay change", ch != null && Math.abs(ch - 3.3333) < 0.01) { "$ch" }
    eq("pay change one", Trend.payChange(pay.take(1)), null)
    eq("extras", Trend.extras(incomes, YearMonth.of(2025, 1), YearMonth.of(2026, 12)).size, 1)
    eq("partial", Trend.isPartial(YearMonth.of(2026, 10), TODAY), true)
}

fun testMedRules() {
    val base = Medicine(id = 1, name = "X", start = d("2026-10-01"), times = listOf(java.time.LocalTime.of(9, 0)))
    val wk = base.copy(rule = "w:1,4")     // Monday and Thursday
    eq("weekdays mon", Meds.takesOn(wk, d("2026-10-19")), true)
    eq("weekdays tue", Meds.takesOn(wk, d("2026-10-20")), false)
    eq("weekdays thu", Meds.takesOn(wk, d("2026-10-22")), true)
    val md = base.copy(rule = "m:31:1")
    eq("month day 31 in nov", Meds.takesOn(md, d("2026-11-30")), true)
    eq("month day 31 oct", Meds.takesOn(md, d("2026-10-31")), true)
    eq("month day other", Meds.takesOn(md, d("2026-11-29")), false)
    val q = base.copy(rule = "m:15:3")
    eq("every 3 months oct", Meds.takesOn(q, d("2026-10-15")), true)
    eq("every 3 months nov", Meds.takesOn(q, d("2026-11-15")), false)
    eq("every 3 months jan", Meds.takesOn(q, d("2027-01-15")), true)
    eq("first monday", Meds.nextDay(base.copy(rule = "mw:1:1:1"), d("2026-10-20")), d("2026-11-02"))
    eq("last friday", Meds.nextDay(base.copy(rule = "mw:-1:5:1"), d("2026-10-01")), d("2026-10-30"))
    val once = base.copy(rule = "once", start = d("2026-10-25"))
    eq("once", Meds.nextDay(once, TODAY), d("2026-10-25"))
    eq("once after", Meds.nextDay(once, d("2026-10-26")), null)
    eq("every 4 weeks", Meds.nextDay(base.copy(everyDays = 28), d("2026-10-02")), d("2026-10-29"))
    eq("rule code", MedRule.parse("w:4,1").code(), "w:1,4")
    eq("rule bad", MedRule.parse("zz") == MedRule.Days, true)
    eq("rule month", MedRule.parse("m:15:3").code(), "m:15:3")
    eq("next alarm monthly", Meds.nextFor(md, TODAY.atTime(10, 0)), d("2026-10-31").atTime(9, 0))
    eq("per day weekdays", Meds.perDay(wk), 2.0 / 7)

    // No time: one dose during the day, never an alarm.
    val any = base.copy(times = emptyList())
    eq("anytime dose", Meds.dosesOn(any, TODAY), listOf(Meds.ANYTIME))
    eq("anytime no alarm", Meds.nextFor(any, TODAY.atTime(0, 0)), null)
    eq("anytime not rung", Meds.dosesBetween(listOf(any), TODAY.atStartOfDay().minusHours(1), TODAY.atTime(23, 0)).size, 0)
    eq("anytime not as needed", any.asNeeded, false)
    eq("anytime slot kept", java.time.LocalTime.parse(Meds.ANYTIME.toString()), Meds.ANYTIME)

    // Boxes: 3 boxes of 10, one a day, keep 1 aside.
    val bx = base.copy(stockMode = StockMode.BOXES, pack = 10, reserve = 1, stock = 30.0, stockDate = TODAY)
    eq("reserve reached", Meds.reorderOn(bx, TODAY), d("2026-11-08"))
    eq("boxes left", Meds.boxesLeft(bx, TODAY.atTime(0, 0)), 3.0)
    eq("boxes no reserve", Meds.reorderOn(bx.copy(reserve = 0, leadDays = 7), TODAY), d("2026-11-11"))
    eq("boxes counted", bx.counted, true)

    // Operations booked ahead: reminded, and in the calendar.
    val op = HealthItem(id = 7, kind = HealthKind.SURGERY, name = "Menisco", date = d("2026-10-24"))
    eq("surgery soon", Boosters.stage(TODAY, op), Reminders.Stage.EARLY)
    eq("surgery past", Boosters.stage(d("2026-10-25"), op), Reminders.Stage.NONE)
    val ev = HealthCalendar.events(listOf(md), emptyList(), d("2026-10-01"), d("2026-10-31"), TODAY, listOf(op))
    eq("calendar surgery and dose", ev.map { it.kind }, listOf(HealthEvent.Kind.SURGERY, HealthEvent.Kind.DOSE))

    eq("height m", Measures.parse(MeasureKind.HEIGHT, "1,78"), Triple(178.0, null, null))
    eq("height cm", Measures.parse(MeasureKind.HEIGHT, "178 cm"), Triple(178.0, null, null))
    eq("bmi", Measures.bmi(72.0, 178.0), 22.7)
}

fun testInflows() {
    val sep = YearMonth.of(2026, 9)
    val oct = YearMonth.of(2026, 10)
    val nov = YearMonth.of(2026, 11)
    val pay = listOf(Income(month = sep, amountCents = 150000))
    val gift = Inflow(day = d("2026-10-05"), amountCents = 5000, cat = InflowCat.REGALO)
    val sale = Inflow(day = d("2026-10-20"), amountCents = 2000, cat = InflowCat.VENDITA, cash = true)

    // October, no payslip yet: September's estimate plus the other money
    val o = Budget.month(oct, emptyList(), pay, inflows = listOf(gift, sale), today = TODAY)
    eq("inflow adds to estimate", o.incomeCents, 157000L)
    eq("inflow keeps estimate flag", o.incomeEstimated, true)
    eq("inflow other", o.otherIncomeCents, 7000L)
    eq("inflow salary known", o.salaryKnown, true)
    eq("inflow newest first", o.inflows.map { it.amountCents }, listOf(2000L, 5000L))

    // The payslip arrives: the estimate goes, the other money stays
    val o2 = Budget.month(oct, emptyList(), pay + Income(month = oct, amountCents = 152000), inflows = listOf(gift, sale), today = TODAY)
    eq("inflow with payslip", o2.incomeCents, 159000L)
    eq("inflow with payslip exact", o2.incomeEstimated, false)

    // Only other money, never a payslip
    val only = Budget.month(oct, emptyList(), emptyList(), inflows = listOf(gift), today = TODAY)
    eq("inflow only has income", only.hasIncome, true)
    eq("inflow only no salary", only.salaryKnown, false)
    eq("inflow only remaining", only.remainingCents, 5000L)
    eq("no income at all", Budget.month(oct, emptyList(), emptyList(), today = TODAY).hasIncome, false)

    // Other months
    eq("inflow other month", Budget.month(nov, emptyList(), pay, inflows = listOf(gift), today = TODAY).otherIncomeCents, 0L)
    eq("inflow range", Budget.range(sep, 3, emptyList(), pay, inflows = listOf(gift), today = TODAY).map { it.otherIncomeCents },
        listOf(0L, 5000L, 0L))

    // Cash, or card and account
    val spends = listOf(Spend(day = d("2026-10-03"), amountCents = 1200, cash = true), Spend(day = d("2026-10-04"), amountCents = 3000))
    val split = Budget.cashSplit(spends, listOf(gift, sale))
    eq("cash out", split.cashOut, 1200L)
    eq("card out", split.cardOut, 3000L)
    eq("cash in", split.cashIn, 2000L)
    eq("card in", split.cardIn, 5000L)
    eq("cash any", split.any, true)
    eq("no cash", Budget.cashSplit(listOf(spends[1])).any, false)

    // Andamento: other money is income, told apart
    val t = Trend.month(oct, emptyList(), pay, emptyList(), inflows = listOf(gift, sale))
    eq("trend inflow income", t.incomeCents, 7000L)
    eq("trend inflow other", t.otherCents, 7000L)
    eq("trend sum other", Trend.sum(listOf(t, t))?.otherCents, 14000L)
    eq("trend first month inflow", Trend.firstMonth(nov, emptyList(), emptyList(), emptyList(), inflows = listOf(gift)), oct)
    eq("inflow cat code", InflowCat.of("vendita"), InflowCat.VENDITA)
    eq("inflow cat unknown", InflowCat.of("x"), InflowCat.ALTRO)
}

fun main() {
    testMoney(); testDates(); testAmounts(); testRecurrence(); testReminders(); testBudget()
    testPagoPa(); testParseBill(); testParseBollo(); testParsePayslip(); testParseTicket()
    testParseUniversity(); testParseLabelBelow(); testClassifyWords(); testVault(); testLayout()
    testOcrPipeline(); testClassifyMore(); testFollowUp(); testSorter(); testDocDate()
    testNoDue(); testMeds(); testVisits(); testStages(); testSpendsJars(); testPack()
    testDoseLog(); testBoostersMeasures(); testPrescription(); testCsv()
    testDocuments(); testWarranty(); testEnglishBill(); testReceiptSubscription(); testTax(); testFixedAndCar()
    testInvest(); testTrend(); testMedRules(); testInflows()
    println("$passed passed, $failed failed")
    if (failed > 0) exitProcess(1)
}
