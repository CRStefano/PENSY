package app.pensy.core

import java.time.LocalDate
import java.time.YearMonth

/*
 * PENSY core data model.
 *
 * Everything here is plain data: no Android, no database, no I/O.
 * Money is always stored as a Long number of euro cents, dates as
 * java.time values (ISO strings once they hit the database).
 */

/**
 * The big drawers ("fascicoli") every document lands in. The user can
 * rename them and add more; the kind tells PENSY what a drawer is about.
 */
enum class FolderKind(val code: String) {
    CASA("casa"),
    AUTO("auto"),
    SALUTE("salute"),
    ANIMALI("animali"),
    SCUOLA("scuola"),
    LAVORO("lavoro"),
    TASSE("tasse"),
    ALTRO("altro");

    companion object {
        fun of(code: String?): FolderKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Kind of document. The code is what we store; labels live in the app's
 * string resources. `payable` says whether it usually comes with an
 * amount to pay; `kind` is the drawer it goes to when nothing else is known.
 */
enum class DocType(val code: String, val payable: Boolean, val kind: FolderKind) {
    BOLLETTA("bolletta", true, FolderKind.CASA),
    PAGOPA("pagopa", true, FolderKind.ALTRO),
    BOLLO_AUTO("bollo", true, FolderKind.AUTO),
    MULTA("multa", true, FolderKind.AUTO),
    TICKET("ticket", true, FolderKind.SALUTE),
    REFERTO("referto", false, FolderKind.SALUTE),
    RICETTA("ricetta", false, FolderKind.SALUTE),
    TASSE_UNIVERSITA("universita", true, FolderKind.SCUOLA),
    RATA("rata", true, FolderKind.CASA),
    F24("f24", true, FolderKind.TASSE),
    POLIZZA("polizza", true, FolderKind.ALTRO),
    DOCUMENTO("documento", false, FolderKind.TASSE),
    GARANZIA("garanzia", false, FolderKind.CASA),
    BUSTA_PAGA("busta_paga", false, FolderKind.LAVORO),
    /** Subscriptions and contracts that renew: phone, gym, streaming, energy offers. */
    ABBONAMENTO("abbonamento", true, FolderKind.CASA),
    /** Proof that something was paid. */
    RICEVUTA("ricevuta", false, FolderKind.ALTRO),
    ALTRO("altro", false, FolderKind.ALTRO);

    companion object {
        fun of(code: String?): DocType = entries.firstOrNull { it.code == code } ?: ALTRO
    }
}

/** A drawer or a sub-drawer. `parentId == null` for the top level. */
data class Folder(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val kind: String = "",
    val note: String = "",
    val sort: Int = 0
)

/**
 * A barcode or QR code exactly as decoded. `format` is the ZXing format
 * name (QR_CODE, DATA_MATRIX, CODE_128...), `content` the raw text.
 * Keeping both lets the app redraw an identical code later.
 */
data class Code(val format: String, val content: String)

/*
 * profileId: whose papers these are. 0 means "the profile in use", and the
 * database puts the real id when the row is first saved.
 */

/** Something that must be paid (or done) by a date. */
data class Deadline(
    val id: Long = 0,
    val profileId: Long = 0,
    val docId: Long? = null,
    val type: DocType = DocType.ALTRO,
    val title: String = "",
    val issuer: String = "",
    val amountCents: Long = 0,
    val due: LocalDate,
    /** 0 = one-off, otherwise repeats every N months (1 monthly, 2 bimonthly, 12 yearly...). */
    val everyMonths: Int = 0,
    /** How many days before the due date the first reminder fires. */
    val remindDays: Int = 10,
    val paid: Boolean = false,
    val paidOn: LocalDate? = null,
    /** True for an occurrence PENSY created by itself from a recurrence: amount is a guess. */
    val estimated: Boolean = false,
    /** Highest reminder stage already notified (see Reminders.Stage). */
    val notified: Int = 0,
    val note: String = "",
    /** The drawer it belongs to (the same as its document's). */
    val folderId: Long? = null,
    /**
     * No due date: something already paid (then `due` holds the payment
     * date), or to pay "whenever" (then `due` is just the day it was added).
     * It never reminds and never repeats.
     */
    val noDue: Boolean = false,
    /** The receipt (a document) of the payment, once there is one. */
    val receiptDocId: Long? = null
) {
    val recurring: Boolean get() = everyMonths > 0 && !noDue

    /** The day it counts in the monthly balance: the due date, or the payment date when there is none. */
    val day: LocalDate? get() = if (!noDue) due else if (paid) paidOn ?: due else null

    /** The next occurrence of a recurring deadline, created when this one is paid. */
    fun next(): Deadline? {
        if (!recurring) return null
        return copy(
            id = 0, docId = null,
            due = due.plusMonths(everyMonths.toLong()),
            paid = false, paidOn = null,
            estimated = true, notified = 0, receiptDocId = null
        )
    }
}

/** Net salary (or any income) for a month, usually read from a payslip. */
data class Income(
    val id: Long = 0,
    val profileId: Long = 0,
    val month: YearMonth,
    val amountCents: Long,
    val docId: Long? = null,
    /** An additional month (tredicesima, quattordicesima, bonus): never used as the estimate for later months. */
    val extra: Boolean = false,
    val note: String = ""
)

/**
 * What the reader understood from a photo, before the user confirms it.
 * Null fields mean "not found": the confirmation screen leaves them empty.
 */
data class Card(
    val type: DocType = DocType.ALTRO,
    val issuer: String = "",
    val amountCents: Long? = null,
    val due: LocalDate? = null,
    val everyMonths: Int = 0,
    /** pagoPA "codice avviso", 18 digits. */
    val noticeCode: String? = null,
    /** Fiscal code of the creditor body (ente creditore), 11 digits. */
    val creditorCf: String? = null,
    /** For payslips: the month the salary refers to. */
    val payMonth: YearMonth? = null,
    /** Date of the document itself: visit, issue, payslip... */
    val docDate: LocalDate? = null,
    /** A follow-up the document asks for ("controllo tra 12 mesi"). */
    val followUp: LocalDate? = null,
    /** Electronic prescription number (NRE), 15 characters. */
    val nre: String? = null,
    val codes: List<Code> = emptyList(),
    val text: String = ""
) {
    /** Extra facts worth keeping with the document, as simple key=value lines. */
    fun fields(): String = buildString {
        noticeCode?.let { append("codice_avviso=").append(it).append('\n') }
        creditorCf?.let { append("cf_ente=").append(it).append('\n') }
        payMonth?.let { append("mese=").append(it).append('\n') }
        nre?.let { append("nre=").append(it).append('\n') }
    }
}

/**
 * A person whose papers PENSY keeps: the owner, a child, a parent being
 * looked after. Each profile has its own folders, deadlines and health
 * record. `budgetOf`: whose monthly balance this profile's expenses count
 * in (a child's school fees count in the parent's balance).
 */
data class Profile(
    val id: Long = 0,
    val name: String,
    val kind: String = "adulto",
    val budgetOf: Long? = null,
    val color: Int = 0
)

/** Everyday spending categories. */
enum class SpendCat(val code: String) {
    SPESA("spesa"),
    CASA("casa"),
    TRASPORTI("trasporti"),
    AUTO("auto"),
    SALUTE("salute"),
    RISTORANTI("ristoranti"),
    SVAGO("svago"),
    ISTRUZIONE("istruzione"),
    ANIMALI("animali"),
    ALTRO("altro");

    companion object {
        fun of(code: String?): SpendCat = entries.firstOrNull { it.code == code } ?: ALTRO
    }
}

/** A small everyday expense (groceries, fuel, a pharmacy receipt), counted on its day. */
data class Spend(
    val id: Long = 0,
    val profileId: Long = 0,
    val day: LocalDate,
    val amountCents: Long,
    val cat: SpendCat = SpendCat.ALTRO,
    val note: String = "",
    /** Kind of 730 deduction it may give ("" = none). See Tax. */
    val tax: String = "",
    /** Paid in cash: most deductions need a traceable payment. */
    val cash: Boolean = false,
    val docId: Long? = null
)

/** A savings jar ("salvadanaio"): money put aside for something, maybe by a date. */
data class Jar(
    val id: Long = 0,
    val profileId: Long = 0,
    val name: String,
    /** How much it should reach; 0 = no goal. */
    val goalCents: Long = 0,
    /** When the money is needed; null = no date. */
    val byDate: LocalDate? = null,
    val note: String = "",
    /** Done with: kept with its history, no more quotas. */
    val closed: Boolean = false
)

/** Money put into a jar (positive) or taken out of it (negative). */
data class JarMove(
    val id: Long = 0,
    val jarId: Long,
    val day: LocalDate,
    val amountCents: Long,
    val note: String = ""
)

/** Kind of an investment: it only picks the icon, the colour and the suggested yearly return. */
enum class InvestKind(val code: String) {
    ETF("etf"),
    AZIONI("azioni"),
    OBBLIGAZIONI("obbligazioni"),
    FONDO("fondo"),
    PENSIONE("pensione"),
    CONTO("conto"),
    BUONI("buoni"),
    CRYPTO("crypto"),
    ALTRO("altro");

    companion object {
        fun of(code: String?): InvestKind = values().firstOrNull { it.code == code } ?: ALTRO
    }
}

/**
 * Something money is invested in: an ETF, shares, a pension fund, a
 * deposit account. With an optional accumulation plan ("PAC": 100 €
 * every month on the 5th), recorded by itself as time goes by.
 */
data class Investment(
    val id: Long = 0,
    val profileId: Long = 0,
    val name: String,
    val kind: InvestKind = InvestKind.ETF,
    /** ISIN or ticker, free text. */
    val code: String = "",
    /** The average yearly return the user expects, in percent: used only for projections. */
    val ratePct: Double = 0.0,
    /** The plan: this much every `planEvery` months on `planDay`, from `planFrom` (0 = no plan). */
    val planCents: Long = 0,
    val planEvery: Int = 1,
    val planDay: Int = 1,
    val planFrom: LocalDate? = null,
    val planUntil: LocalDate? = null,
    /** Last plan date already recorded: later dates are recorded when they come. */
    val planLast: LocalDate? = null,
    val note: String = "",
    /** Sold or finished: kept with its history, left out of the totals. */
    val closed: Boolean = false
) {
    val hasPlan: Boolean get() = planCents > 0 && planFrom != null && !closed
}

/** Money put in (positive) or taken out (negative), with the units bought or sold when known. */
data class InvestMove(
    val id: Long = 0,
    val investmentId: Long,
    val day: LocalDate,
    val amountCents: Long,
    /** Units bought (negative when sold); null = not known. */
    val units: Double? = null,
    /** Recorded by the plan. */
    val plan: Boolean = false,
    val note: String = ""
)

/** What an investment was worth on a day: the price of one unit, or the whole value. */
data class InvestValue(
    val id: Long = 0,
    val investmentId: Long,
    val day: LocalDate,
    /** Price of one unit in euro. */
    val price: Double? = null,
    /** Value of the whole position. */
    val valueCents: Long? = null
)

/** Parse the key=value lines produced by Card.fields(). */
fun parseFields(s: String): Map<String, String> =
    s.lineSequence().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()
