package app.pensy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.pensy.core.Code
import app.pensy.core.Deadline
import app.pensy.core.DocType
import app.pensy.core.DoseLog
import app.pensy.core.DoseStatus
import app.pensy.core.Folder
import app.pensy.core.FolderKind
import app.pensy.core.HealthItem
import app.pensy.core.HealthKind
import app.pensy.core.Income
import app.pensy.core.Inflow
import app.pensy.core.InflowCat
import app.pensy.core.Invest
import app.pensy.core.InvestKind
import app.pensy.core.InvestMove
import app.pensy.core.InvestValue
import app.pensy.core.Investment
import app.pensy.core.Jar
import app.pensy.core.JarMove
import app.pensy.core.Measure
import app.pensy.core.MeasureKind
import app.pensy.core.Medicine
import app.pensy.core.Profile
import app.pensy.core.Sorter
import app.pensy.core.Spend
import app.pensy.core.SpendCat
import app.pensy.core.StockMode
import app.pensy.core.Visit
import app.pensy.core.VisitStatus
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/*
 * The database: one SQLite file, a handful of tables, dates as ISO text,
 * money as integer cents. Readable with any sqlite3 tool once decrypted.
 *
 *   folder    drawers and sub-drawers ("fascicoli")
 *   doc       every document, as text, in a folder
 *   code      QR codes and barcodes of a document, exact content
 *   deadline  what must be paid or done by a date
 *   income    payslips
 *   inflow    other money that came in: a gift, a refund, something sold
 *   rule     what PENSY learned: "documents from X go in folder Y"
 *   medicine  therapies: doses, times, stock
 *   visit     visits and tests: to book, booked, done
 *   health_item  conditions, allergies, operations, vaccines
 *   profile   the people whose papers PENSY keeps (1 = the owner)
 *   spend     everyday expenses
 *   jar, jar_move  savings jars and the money moved in and out
 *   page      photos of documents kept by choice (the files live apart)
 *   dose_log, measure, profile_info  taken/skipped doses, measurements,
 *             the emergency card
 *   investment, invest_move, invest_value  investments, money put in and
 *             taken out, the values typed in from the bank or broker
 *
 * Profiles: every list shows the profile in use (setting "profile");
 * notifications look at all of them.
 *
 * WAL is disabled on purpose: after every commit the single file holds
 * everything, so a copy of it is always a complete snapshot for PENSY_DATA.
 */
class Db(private val ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, VERSION) {

    companion object {
        const val NAME = "pensy.db"
        const val VERSION = 7

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE profile(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL DEFAULT 'adulto',
                created TEXT NOT NULL)""",
            """CREATE TABLE doc(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                issuer TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL DEFAULT '',
                fields TEXT NOT NULL DEFAULT '',
                created TEXT NOT NULL)""",
            """CREATE TABLE code(
                id INTEGER PRIMARY KEY,
                doc_id INTEGER NOT NULL,
                format TEXT NOT NULL,
                content TEXT NOT NULL)""",
            """CREATE TABLE deadline(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                doc_id INTEGER,
                type TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                issuer TEXT NOT NULL DEFAULT '',
                amount INTEGER NOT NULL DEFAULT 0,
                due TEXT NOT NULL,
                every_months INTEGER NOT NULL DEFAULT 0,
                remind_days INTEGER NOT NULL DEFAULT 10,
                paid INTEGER NOT NULL DEFAULT 0,
                paid_on TEXT,
                estimated INTEGER NOT NULL DEFAULT 0,
                notified INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE income(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                month TEXT NOT NULL,
                amount INTEGER NOT NULL,
                doc_id INTEGER,
                extra INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE setting(key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
            """CREATE INDEX deadline_due ON deadline(due)""",
            """CREATE INDEX code_doc ON code(doc_id)"""
        )

        /** Version 2: folders, document date and note, learned rules. */
        private val UPGRADE_V2 = listOf(
            """CREATE TABLE folder(
                id INTEGER PRIMARY KEY,
                parent_id INTEGER,
                name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                sort INTEGER NOT NULL DEFAULT 0,
                created TEXT NOT NULL)""",
            """CREATE TABLE rule(key TEXT PRIMARY KEY, folder_id INTEGER NOT NULL)""",
            """ALTER TABLE doc ADD COLUMN folder_id INTEGER""",
            """ALTER TABLE doc ADD COLUMN date TEXT""",
            """ALTER TABLE doc ADD COLUMN note TEXT NOT NULL DEFAULT ''""",
            """ALTER TABLE deadline ADD COLUMN folder_id INTEGER""",
            """CREATE INDEX doc_folder ON doc(folder_id)""",
            """CREATE INDEX deadline_doc ON deadline(doc_id)"""
        )

        /** Version 3: the health record, deadlines without a due date. */
        private val UPGRADE_V3 = listOf(
            """ALTER TABLE deadline ADD COLUMN no_due INTEGER NOT NULL DEFAULT 0""",
            """CREATE TABLE medicine(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL DEFAULT 1,
                folder_id INTEGER,
                name TEXT NOT NULL,
                purpose TEXT NOT NULL DEFAULT '',
                amount REAL NOT NULL DEFAULT 1,
                unit TEXT NOT NULL DEFAULT 'compressa',
                times TEXT NOT NULL DEFAULT '',
                every_days INTEGER NOT NULL DEFAULT 1,
                start_date TEXT NOT NULL,
                until_date TEXT,
                remind INTEGER NOT NULL DEFAULT 1,
                stock_mode INTEGER NOT NULL DEFAULT 0,
                stock REAL,
                stock_date TEXT,
                pack INTEGER NOT NULL DEFAULT 0,
                lead_days INTEGER NOT NULL DEFAULT 7,
                order_every INTEGER NOT NULL DEFAULT 0,
                last_order TEXT,
                order_notified TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '',
                created TEXT NOT NULL)""",
            """CREATE TABLE visit(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL DEFAULT 1,
                folder_id INTEGER,
                doc_id INTEGER,
                title TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'todo',
                day TEXT,
                at TEXT,
                due_by TEXT,
                priority TEXT NOT NULL DEFAULT '',
                doctor TEXT NOT NULL DEFAULT '',
                place TEXT NOT NULL DEFAULT '',
                remind_days INTEGER NOT NULL DEFAULT 1,
                notified INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                created TEXT NOT NULL)""",
            """CREATE TABLE health_item(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL DEFAULT 1,
                folder_id INTEGER,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                day TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '',
                created TEXT NOT NULL)""",
            """CREATE INDEX visit_doc ON visit(doc_id)"""
        )

        /**
         * Version 4: profiles everywhere (folders too), everyday spending,
         * savings jars, kept photos, dose log, measurements, emergency card,
         * vaccine boosters, receipts of payments. Reminder stages gain
         * MONTH and WEEK (see Reminders.Stage): stored levels move up.
         */
        private val UPGRADE_V4 = listOf(
            """ALTER TABLE folder ADD COLUMN profile_id INTEGER NOT NULL DEFAULT 1""",
            """ALTER TABLE profile ADD COLUMN budget_of INTEGER""",
            """ALTER TABLE deadline ADD COLUMN receipt_doc_id INTEGER""",
            """ALTER TABLE health_item ADD COLUMN next_due TEXT""",
            """ALTER TABLE health_item ADD COLUMN notified INTEGER NOT NULL DEFAULT 0""",
            """CREATE TABLE spend(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                day TEXT NOT NULL,
                amount INTEGER NOT NULL,
                cat TEXT NOT NULL DEFAULT 'altro',
                note TEXT NOT NULL DEFAULT '',
                tax TEXT NOT NULL DEFAULT '',
                cash INTEGER NOT NULL DEFAULT 0,
                doc_id INTEGER,
                created TEXT NOT NULL)""",
            """CREATE TABLE jar(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                goal INTEGER NOT NULL DEFAULT 0,
                by_date TEXT,
                note TEXT NOT NULL DEFAULT '',
                closed INTEGER NOT NULL DEFAULT 0,
                created TEXT NOT NULL)""",
            """CREATE TABLE jar_move(
                id INTEGER PRIMARY KEY,
                jar_id INTEGER NOT NULL,
                day TEXT NOT NULL,
                amount INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE page(
                id INTEGER PRIMARY KEY,
                doc_id INTEGER NOT NULL,
                n INTEGER NOT NULL,
                name TEXT NOT NULL UNIQUE,
                created TEXT NOT NULL)""",
            """CREATE TABLE page_gone(name TEXT PRIMARY KEY, day TEXT NOT NULL)""",
            """CREATE TABLE dose_log(
                id INTEGER PRIMARY KEY,
                medicine_id INTEGER NOT NULL,
                day TEXT NOT NULL,
                slot TEXT,
                status TEXT NOT NULL,
                at TEXT NOT NULL)""",
            """CREATE TABLE measure(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                folder_id INTEGER,
                kind TEXT NOT NULL,
                at TEXT NOT NULL,
                v1 REAL NOT NULL,
                v2 REAL,
                v3 REAL,
                note TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE profile_info(
                profile_id INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY(profile_id, key))""",
            """CREATE INDEX folder_profile ON folder(profile_id)""",
            """CREATE INDEX deadline_profile ON deadline(profile_id)""",
            """CREATE INDEX doc_profile ON doc(profile_id)""",
            """CREATE INDEX spend_day ON spend(profile_id, day)""",
            """CREATE INDEX jar_move_jar ON jar_move(jar_id)""",
            """CREATE INDEX page_doc ON page(doc_id, n)""",
            """CREATE UNIQUE INDEX dose_log_key ON dose_log(medicine_id, day, slot)""",
            """CREATE INDEX measure_kind ON measure(profile_id, kind, at)""",
            """UPDATE deadline SET notified = notified + 2 WHERE notified >= 2""",
            """UPDATE visit SET notified = notified + 2 WHERE notified >= 2"""
        )

        /** Version 5 (PENSY 0.7): investments. */
        private val UPGRADE_V5 = listOf(
            """CREATE TABLE investment(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'etf',
                code TEXT NOT NULL DEFAULT '',
                rate REAL NOT NULL DEFAULT 0,
                plan_amount INTEGER NOT NULL DEFAULT 0,
                plan_every INTEGER NOT NULL DEFAULT 1,
                plan_day INTEGER NOT NULL DEFAULT 1,
                plan_from TEXT,
                plan_until TEXT,
                plan_last TEXT,
                note TEXT NOT NULL DEFAULT '',
                closed INTEGER NOT NULL DEFAULT 0,
                created TEXT NOT NULL)""",
            """CREATE TABLE invest_move(
                id INTEGER PRIMARY KEY,
                investment_id INTEGER NOT NULL,
                day TEXT NOT NULL,
                amount INTEGER NOT NULL,
                units REAL,
                plan INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE invest_value(
                id INTEGER PRIMARY KEY,
                investment_id INTEGER NOT NULL,
                day TEXT NOT NULL,
                price REAL,
                value INTEGER)""",
            """CREATE INDEX investment_profile ON investment(profile_id)""",
            """CREATE INDEX invest_move_inv ON invest_move(investment_id, day)""",
            """CREATE INDEX invest_value_inv ON invest_value(investment_id, day)"""
        )

        /** Version 6 (PENSY 0.8): medicine schedules and boxes, conditions since birth. */
        private val UPGRADE_V6 = listOf(
            """ALTER TABLE medicine ADD COLUMN rule TEXT NOT NULL DEFAULT ''""",
            """ALTER TABLE medicine ADD COLUMN reserve INTEGER NOT NULL DEFAULT 0""",
            """ALTER TABLE health_item ADD COLUMN from_birth INTEGER NOT NULL DEFAULT 0"""
        )

        /** Version 7 (PENSY 0.9): money that came in outside the payslip. */
        private val UPGRADE_V7 = listOf(
            """CREATE TABLE inflow(
                id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                day TEXT NOT NULL,
                amount INTEGER NOT NULL,
                cat TEXT NOT NULL DEFAULT 'altro',
                note TEXT NOT NULL DEFAULT '',
                cash INTEGER NOT NULL DEFAULT 0,
                created TEXT NOT NULL)""",
            """CREATE INDEX inflow_day ON inflow(profile_id, day)"""
        )

        /** The Salute summary template of version 2, replaced when untouched. */
        private const val OLD_NOTE_SALUTE = "Patologie:\nAllergie:\nFarmaci in uso:\nGruppo sanguigno:\nMedico di base:"

        private const val DL_COLS =
            "id, profile_id, doc_id, type, title, issuer, amount, due, every_months, remind_days, paid, paid_on, estimated, notified, note, folder_id, no_due, receipt_doc_id"
        private const val MED_COLS =
            "id, profile_id, folder_id, name, purpose, amount, unit, times, every_days, start_date, until_date, remind, " +
                "stock_mode, stock, stock_date, pack, lead_days, order_every, last_order, order_notified, active, note, rule, reserve"
        private const val VISIT_COLS =
            "id, profile_id, folder_id, doc_id, title, status, day, at, due_by, priority, doctor, place, remind_days, notified, note"
        private const val ITEM_COLS = "id, profile_id, folder_id, kind, name, day, active, note, next_due, notified, from_birth"
        private const val MEASURE_COLS = "id, profile_id, folder_id, kind, at, v1, v2, v3, note"
        private const val DOC_COLS = "id, type, issuer, title, text, fields, created, folder_id, date, note"
        private const val SPEND_COLS = "id, profile_id, day, amount, cat, note, tax, cash, doc_id"
        private const val INFLOW_COLS = "id, profile_id, day, amount, cat, note, cash"
        private const val JAR_COLS = "id, profile_id, name, goal, by_date, note, closed"
        private const val INV_COLS =
            "id, profile_id, name, kind, code, rate, plan_amount, plan_every, plan_day, plan_from, plan_until, plan_last, note, closed"

        /** Default folders for a new profile, by kind of person. */
        private val KIND_FOLDERS = mapOf(
            "adulto" to FolderKind.entries.toList(),
            "bambino" to listOf(FolderKind.SALUTE, FolderKind.SCUOLA, FolderKind.ALTRO),
            "assistito" to listOf(FolderKind.CASA, FolderKind.SALUTE, FolderKind.TASSE, FolderKind.ALTRO)
        )
    }

    init {
        setWriteAheadLoggingEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (s in SCHEMA_V1) db.execSQL(s)
        db.execSQL("INSERT INTO profile(id, name, kind, created) VALUES (1, '', 'adulto', ?)", arrayOf(LocalDate.now().toString()))
        upgradeTo2(db)
        upgradeTo3(db)
        upgradeTo4(db)
        upgradeTo5(db)
        upgradeTo6(db)
        upgradeTo7(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeTo2(db)
        if (oldVersion < 3) upgradeTo3(db)
        if (oldVersion < 4) upgradeTo4(db)
        if (oldVersion < 5) upgradeTo5(db)
        if (oldVersion < 6) upgradeTo6(db)
        if (oldVersion < 7) upgradeTo7(db)
    }

    private fun upgradeTo7(db: SQLiteDatabase) {
        for (s in UPGRADE_V7) db.execSQL(s)
    }

    private fun upgradeTo6(db: SQLiteDatabase) {
        for (s in UPGRADE_V6) db.execSQL(s)
    }

    private fun upgradeTo5(db: SQLiteDatabase) {
        for (s in UPGRADE_V5) db.execSQL(s)
    }

    private fun upgradeTo4(db: SQLiteDatabase) {
        for (s in UPGRADE_V4) db.execSQL(s)
    }

    /**
     * The health record. The follow-ups PENSY found in medical reports
     * were reminders until now: they become visits to book.
     */
    private fun upgradeTo3(db: SQLiteDatabase) {
        for (s in UPGRADE_V3) db.execSQL(s)
        val now = LocalDate.now().toString()
        db.rawQuery(
            "SELECT id, doc_id, title, issuer, due, remind_days, paid, paid_on, note, folder_id FROM deadline WHERE type = 'referto' AND amount = 0",
            null
        ).use { c ->
            val moved = ArrayList<Long>()
            while (c.moveToNext()) {
                val paid = c.getInt(6) != 0
                val v = ContentValues().apply {
                    put("profile_id", 1)
                    if (c.isNull(9)) putNull("folder_id") else put("folder_id", c.getLong(9))
                    if (c.isNull(1)) putNull("doc_id") else put("doc_id", c.getLong(1))
                    put("title", c.getString(2).ifBlank { c.getString(3) }.ifBlank { "Controllo" })
                    put("status", if (paid) VisitStatus.DONE.code else VisitStatus.TODO.code)
                    if (paid) put("day", if (c.isNull(7)) c.getString(4) else c.getString(7)) else put("due_by", c.getString(4))
                    put("remind_days", maxOf(c.getInt(5), 30))
                    put("note", c.getString(8))
                    put("created", now)
                }
                db.insertOrThrow("visit", null, v)
                moved += c.getLong(0)
            }
            for (id in moved) db.execSQL("DELETE FROM deadline WHERE id = ?", arrayOf<Any>(id))
        }
        db.execSQL("UPDATE folder SET note = ? WHERE kind = ? AND note = ?",
            arrayOf<Any>(ctx.getString(R.string.note_salute), FolderKind.SALUTE.code, OLD_NOTE_SALUTE))
    }

    private val defaultFolders = listOf(
        Triple(FolderKind.CASA, R.string.folder_casa, 0),
        Triple(FolderKind.AUTO, R.string.folder_auto, R.string.note_auto),
        Triple(FolderKind.SALUTE, R.string.folder_salute, R.string.note_salute),
        Triple(FolderKind.ANIMALI, R.string.folder_animali, R.string.note_animali),
        Triple(FolderKind.SCUOLA, R.string.folder_scuola, 0),
        Triple(FolderKind.LAVORO, R.string.folder_lavoro, 0),
        Triple(FolderKind.TASSE, R.string.folder_tasse, 0),
        Triple(FolderKind.ALTRO, R.string.folder_altro, 0)
    )

    /** Create the default drawers and put every existing document in one. */
    private fun upgradeTo2(db: SQLiteDatabase) {
        for (s in UPGRADE_V2) db.execSQL(s)
        defaultFolders.forEachIndexed { i, (kind, name, note) ->
            db.execSQL(
                "INSERT INTO folder(parent_id, name, kind, note, sort, created) VALUES (NULL, ?, ?, ?, ?, ?)",
                arrayOf<Any>(ctx.getString(name), kind.code, if (note != 0) ctx.getString(note) else "", i, LocalDate.now().toString())
            )
        }
        val folders = folders(db)
        // Existing documents: sort them like new ones.
        val docs = db.rawQuery("SELECT id, type, issuer, title, text FROM doc", null).use { c ->
            val out = ArrayList<Pair<Long, Long?>>()
            while (c.moveToNext()) {
                val type = DocType.of(c.getString(1))
                val text = c.getString(3) + "\n" + c.getString(4)
                out += c.getLong(0) to Sorter.suggest(text, type, c.getString(2), folders, emptyMap())
            }
            out
        }
        for ((id, f) in docs) db.execSQL("UPDATE doc SET folder_id = ? WHERE id = ?", arrayOf<Any?>(f, id))
        db.execSQL("UPDATE deadline SET folder_id = (SELECT folder_id FROM doc WHERE doc.id = deadline.doc_id) WHERE doc_id IS NOT NULL")
        val loose = db.rawQuery("SELECT id, type, issuer, title FROM deadline WHERE folder_id IS NULL", null).use { c ->
            val out = ArrayList<Pair<Long, Long?>>()
            while (c.moveToNext()) {
                out += c.getLong(0) to Sorter.suggest(c.getString(3), DocType.of(c.getString(1)), c.getString(2), folders, emptyMap())
            }
            out
        }
        for ((id, f) in loose) db.execSQL("UPDATE deadline SET folder_id = ? WHERE id = ?", arrayOf<Any?>(f, id))
    }

    private val w: SQLiteDatabase get() = writableDatabase

    // -------------------------------------------------------------- settings

    fun setting(key: String, default: String): String =
        w.rawQuery("SELECT value FROM setting WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else default }

    fun setSetting(key: String, value: String) {
        w.execSQL("INSERT OR REPLACE INTO setting(key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    fun remindDays(): Int = setting("remind_days", "10").toIntOrNull() ?: 10

    // -------------------------------------------------------------- profiles

    @Volatile private var cur = 0L

    /** The profile in use. */
    val profile: Long
        get() {
            if (cur == 0L) {
                val id = setting("profile", "1").toLongOrNull() ?: 1L
                cur = if (profileRow(id) != null) id else 1L
            }
            return cur
        }

    fun setProfile(id: Long) {
        setSetting("profile", id.toString())
        cur = id
    }

    /** 0 means "the profile in use". */
    private fun pid(id: Long): Long = if (id == 0L) profile else id

    private fun profileRow(id: Long): Profile? =
        w.rawQuery("SELECT id, name, kind, budget_of FROM profile WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) Profile(c.getLong(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3)) else null
        }

    fun profiles(): List<Profile> =
        w.rawQuery("SELECT id, name, kind, budget_of FROM profile ORDER BY id", null).use { c ->
            val out = ArrayList<Profile>()
            while (c.moveToNext()) out += Profile(c.getLong(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3))
            out
        }

    fun profileOf(id: Long): Profile? = profileRow(id)

    fun current(): Profile = profileRow(profile) ?: Profile(1, "")

    fun profileName(): String = current().name

    fun setProfileName(name: String) {
        w.execSQL("UPDATE profile SET name = ? WHERE id = ?", arrayOf<Any>(name.trim(), profile))
    }

    /** Insert (id == 0, with its default folders) or update. Returns the id. */
    fun saveProfile(p: Profile): Long {
        val v = ContentValues().apply {
            put("name", p.name.trim())
            put("kind", p.kind)
            if (p.budgetOf != null && p.budgetOf != p.id) put("budget_of", p.budgetOf) else putNull("budget_of")
        }
        if (p.id != 0L) {
            w.update("profile", v, "id = ?", arrayOf(p.id.toString()))
            return p.id
        }
        w.beginTransaction()
        try {
            v.put("created", LocalDate.now().toString())
            val id = w.insertOrThrow("profile", null, v)
            val kinds = KIND_FOLDERS[p.kind] ?: KIND_FOLDERS.getValue("adulto")
            defaultFolders.filter { it.first in kinds }.forEachIndexed { i, (kind, name, note) ->
                w.execSQL(
                    "INSERT INTO folder(parent_id, name, kind, note, sort, created, profile_id) VALUES (NULL, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(ctx.getString(name), kind.code, if (note != 0) ctx.getString(note) else "", i, LocalDate.now().toString(), id)
                )
            }
            w.setTransactionSuccessful()
            return id
        } finally {
            w.endTransaction()
        }
    }

    /** Delete a profile and everything it holds. The owner (1) cannot be deleted. */
    fun deleteProfile(id: Long) {
        if (id == 1L) return
        val gone = ArrayList<String>()
        w.beginTransaction()
        try {
            val p = arrayOf(id.toString())
            w.rawQuery("SELECT page.name FROM page JOIN doc ON doc.id = page.doc_id WHERE doc.profile_id = ?", p).use { c ->
                while (c.moveToNext()) gone += c.getString(0)
            }
            w.execSQL("DELETE FROM page WHERE doc_id IN (SELECT id FROM doc WHERE profile_id = ?)", p)
            w.execSQL("DELETE FROM code WHERE doc_id IN (SELECT id FROM doc WHERE profile_id = ?)", p)
            w.execSQL("DELETE FROM dose_log WHERE medicine_id IN (SELECT id FROM medicine WHERE profile_id = ?)", p)
            w.execSQL("DELETE FROM jar_move WHERE jar_id IN (SELECT id FROM jar WHERE profile_id = ?)", p)
            w.execSQL("DELETE FROM invest_move WHERE investment_id IN (SELECT id FROM investment WHERE profile_id = ?)", p)
            w.execSQL("DELETE FROM invest_value WHERE investment_id IN (SELECT id FROM investment WHERE profile_id = ?)", p)
            w.execSQL("DELETE FROM rule WHERE folder_id IN (SELECT id FROM folder WHERE profile_id = ?)", p)
            for (t in listOf("doc", "deadline", "income", "inflow", "folder", "medicine", "visit", "health_item", "spend", "jar", "investment", "measure", "profile_info")) {
                w.execSQL("DELETE FROM $t WHERE profile_id = ?", p)
            }
            w.execSQL("UPDATE profile SET budget_of = NULL WHERE budget_of = ?", p)
            w.execSQL("DELETE FROM profile WHERE id = ?", p)
            for (n in gone) w.execSQL("INSERT OR REPLACE INTO page_gone(name, day) VALUES (?, ?)", arrayOf(n, LocalDate.now().toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        for (n in gone) Pages.file(ctx, n).delete()
        if (cur == id) setProfile(1)
    }

    /**
     * Whose expenses count in the balance shown now: the profile in use
     * and the ones that count in its balance (a child's in the parent's).
     * A profile that counts in someone else's balance shows only its own.
     */
    fun budgetGroup(): List<Long> {
        val me = current()
        if (me.budgetOf != null) return listOf(me.id)
        return listOf(me.id) + profiles().filter { it.budgetOf == me.id }.map { it.id }
    }

    private fun inList(ids: List<Long>): String = ids.joinToString(",", "(", ")")

    /** Per-profile facts (the emergency card): key -> value. */
    fun info(profileId: Long = 0): Map<String, String> =
        w.rawQuery("SELECT key, value FROM profile_info WHERE profile_id = ?", arrayOf(pid(profileId).toString())).use { c ->
            val out = LinkedHashMap<String, String>()
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            out
        }

    fun setInfo(key: String, value: String, profileId: Long = 0) {
        if (value.isBlank()) w.execSQL("DELETE FROM profile_info WHERE profile_id = ? AND key = ?", arrayOf<Any>(pid(profileId), key))
        else w.execSQL("INSERT OR REPLACE INTO profile_info(profile_id, key, value) VALUES (?, ?, ?)", arrayOf<Any>(pid(profileId), key, value.trim()))
    }

    // --------------------------------------------------------------- folders

    private fun folders(db: SQLiteDatabase, where: String = "", args: Array<String>? = null): List<Folder> =
        db.rawQuery("SELECT id, parent_id, name, kind, note, sort FROM folder $where ORDER BY sort, name COLLATE NOCASE", args).use { c ->
            val out = ArrayList<Folder>()
            while (c.moveToNext()) out += Folder(
                c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5)
            )
            out
        }

    /** The folders of the profile in use. */
    fun folders(): List<Folder> = folders(w, "WHERE profile_id = ?", arrayOf(profile.toString()))

    fun folder(id: Long?): Folder? = if (id == null) null else folders(w, "WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    /** The profile a folder belongs to. */
    fun folderProfile(id: Long): Long? =
        w.rawQuery("SELECT profile_id FROM folder WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun addFolder(parentId: Long?, name: String): Long {
        val v = ContentValues().apply {
            if (parentId != null) put("parent_id", parentId) else putNull("parent_id")
            put("name", name.trim())
            put("kind", "")
            put("sort", 100)
            put("created", LocalDate.now().toString())
            put("profile_id", profile)
        }
        return w.insertOrThrow("folder", null, v)
    }

    fun renameFolder(id: Long, name: String) {
        w.execSQL("UPDATE folder SET name = ? WHERE id = ?", arrayOf<Any>(name.trim(), id))
    }

    fun setFolderNote(id: Long, note: String) {
        w.execSQL("UPDATE folder SET note = ? WHERE id = ?", arrayOf<Any>(note, id))
    }

    /** Delete a folder: its documents, deadlines and sub-folders move up one level (or to "Altro"). */
    fun deleteFolder(id: Long) {
        val all = folders()
        val f = all.firstOrNull { it.id == id } ?: return
        val target = f.parentId ?: all.firstOrNull { it.parentId == null && it.kind == FolderKind.ALTRO.code && it.id != id }?.id
        w.beginTransaction()
        try {
            w.execSQL("UPDATE doc SET folder_id = ? WHERE folder_id = ?", arrayOf<Any?>(target, id))
            w.execSQL("UPDATE deadline SET folder_id = ? WHERE folder_id = ?", arrayOf<Any?>(target, id))
            for (t in listOf("medicine", "visit", "health_item", "measure")) {
                w.execSQL("UPDATE $t SET folder_id = ? WHERE folder_id = ?", arrayOf<Any?>(target, id))
            }
            w.execSQL("UPDATE folder SET parent_id = ? WHERE parent_id = ?", arrayOf<Any?>(f.parentId, id))
            w.execSQL("DELETE FROM rule WHERE folder_id = ?", arrayOf<Any>(id))
            w.execSQL("DELETE FROM folder WHERE id = ?", arrayOf<Any>(id))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Rules of the profile in use. The owner's keys are bare (as before profiles), the others' carry "p<id>|". */
    fun rules(): Map<String, Long> {
        val prefix = if (profile == 1L) "" else "p$profile|"
        return w.rawQuery("SELECT key, folder_id FROM rule", null).use { c ->
            val out = HashMap<String, Long>()
            while (c.moveToNext()) {
                val k = c.getString(0)
                if (prefix.isEmpty()) { if ('|' !in k) out[k] = c.getLong(1) }
                else if (k.startsWith(prefix)) out[k.removePrefix(prefix)] = c.getLong(1)
            }
            out
        }
    }

    /** Remember where the user put a document from this issuer. */
    fun learn(issuer: String, folderId: Long?) {
        val key = Sorter.issuerKey(issuer) ?: return
        if (folderId == null) return
        val k = if (profile == 1L) key else "p$profile|$key"
        w.execSQL("INSERT OR REPLACE INTO rule(key, folder_id) VALUES (?, ?)", arrayOf<Any>(k, folderId))
    }

    // ------------------------------------------------------------- deadlines

    private fun deadline(c: Cursor) = Deadline(
        id = c.getLong(0),
        profileId = c.getLong(1),
        docId = if (c.isNull(2)) null else c.getLong(2),
        type = DocType.of(c.getString(3)),
        title = c.getString(4),
        issuer = c.getString(5),
        amountCents = c.getLong(6),
        due = LocalDate.parse(c.getString(7)),
        everyMonths = c.getInt(8),
        remindDays = c.getInt(9),
        paid = c.getInt(10) != 0,
        paidOn = if (c.isNull(11)) null else LocalDate.parse(c.getString(11)),
        estimated = c.getInt(12) != 0,
        notified = c.getInt(13),
        note = c.getString(14),
        folderId = if (c.isNull(15)) null else c.getLong(15),
        noDue = c.getInt(16) != 0,
        receiptDocId = if (c.isNull(17)) null else c.getLong(17)
    )

    private fun deadlinesWhere(where: String, args: Array<String>?): List<Deadline> =
        w.rawQuery("SELECT $DL_COLS FROM deadline $where ORDER BY due, id", args).use { c ->
            val out = ArrayList<Deadline>()
            while (c.moveToNext()) out += deadline(c)
            out
        }

    /** Deadlines of the profile in use. */
    fun deadlines(): List<Deadline> = deadlinesWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    /** Deadlines of every profile (for the reminders). */
    fun allDeadlines(): List<Deadline> = deadlinesWhere("", null)

    /** Deadlines that count in the balance shown now (see budgetGroup). */
    fun budgetDeadlines(): List<Deadline> = deadlinesWhere("WHERE profile_id IN " + inList(budgetGroup()), null)

    fun deadlinesOfDoc(docId: Long): List<Deadline> = deadlinesWhere("WHERE doc_id = ?", arrayOf(docId.toString()))

    fun deadline(id: Long): Deadline? = deadlinesWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    /** Insert (id == 0) or update. Returns the id. */
    fun saveDeadline(d: Deadline): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(d.profileId))
            if (d.docId != null) put("doc_id", d.docId) else putNull("doc_id")
            put("type", d.type.code)
            put("title", d.title)
            put("issuer", d.issuer)
            put("amount", d.amountCents)
            put("due", d.due.toString())
            put("every_months", d.everyMonths)
            put("remind_days", d.remindDays)
            put("paid", if (d.paid) 1 else 0)
            if (d.paidOn != null) put("paid_on", d.paidOn.toString()) else putNull("paid_on")
            put("estimated", if (d.estimated) 1 else 0)
            put("notified", d.notified)
            put("note", d.note)
            if (d.folderId != null) put("folder_id", d.folderId) else putNull("folder_id")
            put("no_due", if (d.noDue) 1 else 0)
            if (d.receiptDocId != null) put("receipt_doc_id", d.receiptDocId) else putNull("receipt_doc_id")
        }
        return if (d.id == 0L) w.insertOrThrow("deadline", null, v)
        else { w.update("deadline", v, "id = ?", arrayOf(d.id.toString())); d.id }
    }

    /** Mark as paid; for a recurring deadline also create the next occurrence. Returns it, if any. */
    fun markPaid(id: Long, on: LocalDate): Deadline? {
        val d = deadline(id) ?: return null
        var next: Deadline? = null
        w.beginTransaction()
        try {
            saveDeadline(d.copy(paid = true, paidOn = on))
            d.next()?.let { n ->
                // Don't create it twice if the user toggles paid/unpaid.
                val exists = w.rawQuery(
                    "SELECT COUNT(*) FROM deadline WHERE type = ? AND issuer = ? AND due = ? AND title = ? AND profile_id = ?",
                    arrayOf(n.type.code, n.issuer, n.due.toString(), n.title, d.profileId.toString())
                ).use { it.moveToFirst() && it.getInt(0) > 0 }
                if (!exists) next = n.copy(id = saveDeadline(n))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return next
    }

    fun markUnpaid(id: Long) {
        w.execSQL("UPDATE deadline SET paid = 0, paid_on = NULL, notified = 0 WHERE id = ?", arrayOf<Any>(id))
    }

    fun setNotified(id: Long, level: Int) {
        w.execSQL("UPDATE deadline SET notified = ? WHERE id = ?", arrayOf<Any>(level, id))
    }

    /** Delete a deadline. Its document stays in its folder (it is part of the archive). */
    fun deleteDeadline(id: Long) {
        w.delete("deadline", "id = ?", arrayOf(id.toString()))
    }

    // ---------------------------------------------------------- docs, codes

    class DocRow(
        val id: Long, val type: DocType, val issuer: String, val title: String, val text: String,
        val fields: String, val created: String, val folderId: Long?, val date: LocalDate?, val note: String
    ) {
        /** The date to show and sort by: the document's own, or when it was added. */
        val shownDate: LocalDate get() = date ?: runCatching { LocalDate.parse(created) }.getOrDefault(LocalDate.now())
    }

    private fun doc(c: Cursor) = DocRow(
        c.getLong(0), DocType.of(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
        c.getString(6), if (c.isNull(7)) null else c.getLong(7),
        if (c.isNull(8)) null else runCatching { LocalDate.parse(c.getString(8)) }.getOrNull(), c.getString(9)
    )

    private fun docsWhere(where: String, args: Array<String>?): List<DocRow> =
        w.rawQuery("SELECT $DOC_COLS FROM doc $where", args).use { c ->
            val out = ArrayList<DocRow>()
            while (c.moveToNext()) out += doc(c)
            out
        }

    /** Documents of the profile in use, newest first. */
    fun docs(): List<DocRow> = docsWhere("WHERE profile_id = ?", arrayOf(profile.toString())).sortedByDescending { it.shownDate }

    fun doc(id: Long): DocRow? = docsWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun saveDoc(
        type: DocType, issuer: String, title: String, text: String, fields: String,
        folderId: Long?, date: LocalDate?, note: String = ""
    ): Long {
        val v = ContentValues().apply {
            put("profile_id", profile)
            put("type", type.code)
            put("issuer", issuer)
            put("title", title)
            put("text", text)
            put("fields", fields)
            put("created", LocalDate.now().toString())
            if (folderId != null) put("folder_id", folderId) else putNull("folder_id")
            if (date != null) put("date", date.toString()) else putNull("date")
            put("note", note)
        }
        return w.insertOrThrow("doc", null, v)
    }

    fun updateDoc(id: Long, type: DocType, issuer: String, title: String, folderId: Long?, date: LocalDate?, note: String) {
        val v = ContentValues().apply {
            put("type", type.code)
            put("issuer", issuer)
            put("title", title)
            if (folderId != null) put("folder_id", folderId) else putNull("folder_id")
            if (date != null) put("date", date.toString()) else putNull("date")
            put("note", note)
        }
        w.update("doc", v, "id = ?", arrayOf(id.toString()))
        w.execSQL("UPDATE deadline SET folder_id = ? WHERE doc_id = ?", arrayOf<Any?>(folderId, id))
    }

    /** Move a document, and every deadline attached to it, to another folder. */
    fun moveDoc(id: Long, folderId: Long) {
        w.execSQL("UPDATE doc SET folder_id = ? WHERE id = ?", arrayOf<Any>(folderId, id))
        w.execSQL("UPDATE deadline SET folder_id = ? WHERE doc_id = ?", arrayOf<Any>(folderId, id))
    }

    fun moveDeadline(d: Deadline, folderId: Long) {
        if (d.docId != null) moveDoc(d.docId, folderId)
        else w.execSQL("UPDATE deadline SET folder_id = ? WHERE id = ?", arrayOf<Any>(folderId, d.id))
    }

    /** Set (or remove, with null) one key=value fact of a document. */
    fun setDocField(id: Long, key: String, value: String?) {
        val d = doc(id) ?: return
        val map = LinkedHashMap(app.pensy.core.parseFields(d.fields))
        if (value == null) map.remove(key) else map[key] = value
        val text = map.entries.joinToString("") { "${it.key}=${it.value}\n" }
        w.execSQL("UPDATE doc SET fields = ? WHERE id = ?", arrayOf<Any>(text, id))
    }

    /** Append text read from new pages to a document. */
    fun appendDocText(id: Long, text: String) {
        if (text.isBlank()) return
        w.execSQL("UPDATE doc SET text = CASE WHEN text = '' THEN ? ELSE text || ? END WHERE id = ?",
            arrayOf<Any>(text, "\n\n" + text, id))
    }

    /** Delete a document with its codes, pages and deadlines. */
    fun deleteDoc(id: Long) {
        val names = pages(id).map { it.name }
        w.beginTransaction()
        try {
            w.delete("deadline", "doc_id = ?", arrayOf(id.toString()))
            w.execSQL("UPDATE income SET doc_id = NULL WHERE doc_id = ?", arrayOf<Any>(id))
            w.execSQL("UPDATE visit SET doc_id = NULL WHERE doc_id = ?", arrayOf<Any>(id))
            w.execSQL("UPDATE deadline SET receipt_doc_id = NULL WHERE receipt_doc_id = ?", arrayOf<Any>(id))
            w.execSQL("UPDATE spend SET doc_id = NULL WHERE doc_id = ?", arrayOf<Any>(id))
            w.delete("code", "doc_id = ?", arrayOf(id.toString()))
            w.delete("page", "doc_id = ?", arrayOf(id.toString()))
            for (n in names) w.execSQL("INSERT OR REPLACE INTO page_gone(name, day) VALUES (?, ?)", arrayOf(n, LocalDate.now().toString()))
            w.delete("doc", "id = ?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        for (n in names) Pages.file(ctx, n).delete()
    }

    // ----------------------------------------------------------------- pages

    /** A kept photo of one page of a document. The JPEG lives in files/pages/<name>. */
    class Page(val id: Long, val docId: Long, val n: Int, val name: String)

    fun pages(docId: Long): List<Page> =
        w.rawQuery("SELECT id, doc_id, n, name FROM page WHERE doc_id = ? ORDER BY n, id", arrayOf(docId.toString())).use { c ->
            val out = ArrayList<Page>()
            while (c.moveToNext()) out += Page(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3))
            out
        }

    /** Every kept page of every profile (for PENSY_DATA). */
    fun allPageNames(): Set<String> =
        w.rawQuery("SELECT name FROM page", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    /** Documents of the profile in use that have kept pages, with how many. */
    fun pageCounts(): Map<Long, Int> =
        w.rawQuery("SELECT page.doc_id, COUNT(*) FROM page JOIN doc ON doc.id = page.doc_id WHERE doc.profile_id = ? GROUP BY page.doc_id",
            arrayOf(profile.toString())).use { c ->
            val out = HashMap<Long, Int>()
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
            out
        }

    fun addPage(docId: Long, name: String): Long {
        val n = w.rawQuery("SELECT COALESCE(MAX(n), 0) FROM page WHERE doc_id = ?", arrayOf(docId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val v = ContentValues().apply {
            put("doc_id", docId)
            put("n", n + 1)
            put("name", name)
            put("created", LocalDate.now().toString())
        }
        return w.insertOrThrow("page", null, v)
    }

    fun deletePage(p: Page) {
        w.delete("page", "id = ?", arrayOf(p.id.toString()))
        w.execSQL("INSERT OR REPLACE INTO page_gone(name, day) VALUES (?, ?)", arrayOf(p.name, LocalDate.now().toString()))
        Pages.file(ctx, p.name).delete()
    }

    /**
     * Pages deleted before `day`: their copy in PENSY_DATA can go. They are
     * kept a week after deletion, so the rotating copies stay complete.
     */
    fun pagesGoneBefore(day: LocalDate): List<String> =
        w.rawQuery("SELECT name FROM page_gone WHERE day < ?", arrayOf(day.toString())).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    fun forgetGone(names: Collection<String>) {
        for (n in names) w.execSQL("DELETE FROM page_gone WHERE name = ?", arrayOf(n))
    }

    // ---------------------------------------------------------------- spends

    private fun spend(c: Cursor) = Spend(
        id = c.getLong(0), profileId = c.getLong(1), day = LocalDate.parse(c.getString(2)), amountCents = c.getLong(3),
        cat = SpendCat.of(c.getString(4)), note = c.getString(5), tax = c.getString(6), cash = c.getInt(7) != 0,
        docId = if (c.isNull(8)) null else c.getLong(8)
    )

    private fun spendsWhere(where: String, args: Array<String>?): List<Spend> =
        w.rawQuery("SELECT $SPEND_COLS FROM spend $where ORDER BY day DESC, id DESC", args).use { c ->
            val out = ArrayList<Spend>()
            while (c.moveToNext()) out += spend(c)
            out
        }

    /** Everyday expenses that count in the balance shown now. */
    fun spends(): List<Spend> = spendsWhere("WHERE profile_id IN " + inList(budgetGroup()), null)

    /** Everyday expenses of the profile in use only. */
    fun ownSpends(): List<Spend> = spendsWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    fun spend(id: Long): Spend? = spendsWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun saveSpend(x: Spend): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(x.profileId))
            put("day", x.day.toString())
            put("amount", x.amountCents)
            put("cat", x.cat.code)
            put("note", x.note)
            put("tax", x.tax)
            put("cash", if (x.cash) 1 else 0)
            putId("doc_id", x.docId)
        }
        return if (x.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("spend", null, v) }
        else { w.update("spend", v, "id = ?", arrayOf(x.id.toString())); x.id }
    }

    fun deleteSpend(id: Long) {
        w.delete("spend", "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------ jars

    private fun jar(c: Cursor) = Jar(
        id = c.getLong(0), profileId = c.getLong(1), name = c.getString(2), goalCents = c.getLong(3),
        byDate = day(c, 4), note = c.getString(5), closed = c.getInt(6) != 0
    )

    /** Jars of the profile in use: open ones first, soonest date first. */
    fun jars(): List<Jar> =
        w.rawQuery("SELECT $JAR_COLS FROM jar WHERE profile_id = ? ORDER BY closed, by_date IS NULL, by_date, name COLLATE NOCASE",
            arrayOf(profile.toString())).use { c ->
            val out = ArrayList<Jar>()
            while (c.moveToNext()) out += jar(c)
            out
        }

    fun jar(id: Long): Jar? =
        w.rawQuery("SELECT $JAR_COLS FROM jar WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) jar(it) else null }

    fun saveJar(j: Jar): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(j.profileId))
            put("name", j.name.trim())
            put("goal", j.goalCents)
            putDay("by_date", j.byDate)
            put("note", j.note)
            put("closed", if (j.closed) 1 else 0)
        }
        return if (j.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("jar", null, v) }
        else { w.update("jar", v, "id = ?", arrayOf(j.id.toString())); j.id }
    }

    fun deleteJar(id: Long) {
        w.beginTransaction()
        try {
            w.delete("jar_move", "jar_id = ?", arrayOf(id.toString()))
            w.delete("jar", "id = ?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** Money moved in and out of the jars of the profile in use, newest first. */
    fun jarMoves(): List<JarMove> =
        w.rawQuery(
            "SELECT jar_move.id, jar_id, day, amount, jar_move.note FROM jar_move JOIN jar ON jar.id = jar_move.jar_id " +
                "WHERE jar.profile_id = ? ORDER BY day DESC, jar_move.id DESC", arrayOf(profile.toString())
        ).use { c ->
            val out = ArrayList<JarMove>()
            while (c.moveToNext()) out += JarMove(c.getLong(0), c.getLong(1), LocalDate.parse(c.getString(2)), c.getLong(3), c.getString(4))
            out
        }

    fun saveJarMove(m: JarMove): Long {
        val v = ContentValues().apply {
            put("jar_id", m.jarId)
            put("day", m.day.toString())
            put("amount", m.amountCents)
            put("note", m.note)
        }
        return if (m.id == 0L) w.insertOrThrow("jar_move", null, v)
        else { w.update("jar_move", v, "id = ?", arrayOf(m.id.toString())); m.id }
    }

    fun deleteJarMove(id: Long) {
        w.delete("jar_move", "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------ investments

    private fun investment(c: Cursor) = Investment(
        id = c.getLong(0), profileId = c.getLong(1), name = c.getString(2), kind = InvestKind.of(c.getString(3)),
        code = c.getString(4), ratePct = c.getDouble(5), planCents = c.getLong(6), planEvery = c.getInt(7), planDay = c.getInt(8),
        planFrom = day(c, 9), planUntil = day(c, 10), planLast = day(c, 11), note = c.getString(12), closed = c.getInt(13) != 0
    )

    /** Investments of the profile in use: open ones first, by name. */
    fun investments(): List<Investment> =
        w.rawQuery("SELECT $INV_COLS FROM investment WHERE profile_id = ? ORDER BY closed, name COLLATE NOCASE",
            arrayOf(profile.toString())).use { c ->
            val out = ArrayList<Investment>()
            while (c.moveToNext()) out += investment(c)
            out
        }

    /** Every profile's investments (the plans are recorded for all of them). */
    private fun allInvestments(): List<Investment> =
        w.rawQuery("SELECT $INV_COLS FROM investment", null).use { c ->
            val out = ArrayList<Investment>()
            while (c.moveToNext()) out += investment(c)
            out
        }

    fun investment(id: Long): Investment? =
        w.rawQuery("SELECT $INV_COLS FROM investment WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) investment(it) else null }

    fun saveInvestment(i: Investment): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(i.profileId))
            put("name", i.name.trim())
            put("kind", i.kind.code)
            put("code", i.code.trim())
            put("rate", i.ratePct)
            put("plan_amount", i.planCents)
            put("plan_every", maxOf(1, i.planEvery))
            put("plan_day", i.planDay.coerceIn(1, 31))
            putDay("plan_from", i.planFrom)
            putDay("plan_until", i.planUntil)
            putDay("plan_last", i.planLast)
            put("note", i.note)
            put("closed", if (i.closed) 1 else 0)
        }
        return if (i.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("investment", null, v) }
        else { w.update("investment", v, "id = ?", arrayOf(i.id.toString())); i.id }
    }

    fun deleteInvestment(id: Long) {
        w.beginTransaction()
        try {
            w.delete("invest_move", "investment_id = ?", arrayOf(id.toString()))
            w.delete("invest_value", "investment_id = ?", arrayOf(id.toString()))
            w.delete("investment", "id = ?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun investMoves(where: String, args: Array<String>): List<InvestMove> =
        w.rawQuery("SELECT invest_move.id, investment_id, day, amount, units, plan, invest_move.note FROM invest_move " +
            "JOIN investment ON investment.id = invest_move.investment_id WHERE $where ORDER BY day DESC, invest_move.id DESC", args).use { c ->
            val out = ArrayList<InvestMove>()
            while (c.moveToNext()) out += InvestMove(c.getLong(0), c.getLong(1), LocalDate.parse(c.getString(2)), c.getLong(3),
                if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) != 0, c.getString(6))
            out
        }

    /** Money in and out of the investments of the profile in use, newest first. */
    fun investMoves(): List<InvestMove> = investMoves("investment.profile_id = ?", arrayOf(profile.toString()))

    fun saveInvestMove(m: InvestMove): Long {
        val v = ContentValues().apply {
            put("investment_id", m.investmentId)
            put("day", m.day.toString())
            put("amount", m.amountCents)
            if (m.units != null) put("units", m.units) else putNull("units")
            put("plan", if (m.plan) 1 else 0)
            put("note", m.note)
        }
        return if (m.id == 0L) w.insertOrThrow("invest_move", null, v)
        else { w.update("invest_move", v, "id = ?", arrayOf(m.id.toString())); m.id }
    }

    fun deleteInvestMove(id: Long) {
        w.delete("invest_move", "id = ?", arrayOf(id.toString()))
    }

    /** The values typed in for the investments of the profile in use, newest first. */
    fun investValues(): List<InvestValue> =
        w.rawQuery("SELECT invest_value.id, investment_id, day, price, value FROM invest_value " +
            "JOIN investment ON investment.id = invest_value.investment_id WHERE investment.profile_id = ? " +
            "ORDER BY day DESC, invest_value.id DESC", arrayOf(profile.toString())).use { c ->
            val out = ArrayList<InvestValue>()
            while (c.moveToNext()) out += InvestValue(c.getLong(0), c.getLong(1), LocalDate.parse(c.getString(2)),
                if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getLong(4))
            out
        }

    fun saveInvestValue(x: InvestValue): Long {
        val v = ContentValues().apply {
            put("investment_id", x.investmentId)
            put("day", x.day.toString())
            if (x.price != null) put("price", x.price) else putNull("price")
            if (x.valueCents != null) put("value", x.valueCents) else putNull("value")
        }
        return if (x.id == 0L) w.insertOrThrow("invest_value", null, v)
        else { w.update("invest_value", v, "id = ?", arrayOf(x.id.toString())); x.id }
    }

    fun deleteInvestValue(id: Long) {
        w.delete("invest_value", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Record the plans' contributions whose day has come, for every
     * profile. The units are estimated from the latest unit price typed
     * in, when there is one. Returns how many were recorded.
     */
    fun catchUpPlans(today: LocalDate): Int {
        var n = 0
        val invs = allInvestments().filter { it.hasPlan }
        if (invs.isEmpty()) return 0
        w.beginTransaction()
        try {
            for (inv in invs) {
                val due = Invest.planDue(inv, today)
                if (due.isEmpty()) continue
                val have = investMoves("investment.id = ?", arrayOf(inv.id.toString()))
                val values = w.rawQuery("SELECT id, investment_id, day, price, value FROM invest_value WHERE investment_id = ?",
                    arrayOf(inv.id.toString())).use { c ->
                    val out = ArrayList<InvestValue>()
                    while (c.moveToNext()) out += InvestValue(c.getLong(0), c.getLong(1), LocalDate.parse(c.getString(2)),
                        if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getLong(4))
                    out
                }
                for (day in due) {
                    // Already typed in by hand on that day for the same amount: don't count it twice.
                    if (have.any { it.day == day && it.amountCents == inv.planCents }) continue
                    // Units only if every earlier movement has them (otherwise they could not add up anyway).
                    val units = if (Invest.units(have, day) != null) Invest.unitsFor(inv.planCents, Invest.lastPrice(inv, values, day)) else null
                    saveInvestMove(InvestMove(investmentId = inv.id, day = day, amountCents = inv.planCents, units = units, plan = true))
                    n++
                }
                saveInvestment(inv.copy(planLast = due.last()))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return n
    }

    fun saveCodes(docId: Long, codes: List<Code>) {
        for (c in codes) {
            val v = ContentValues().apply {
                put("doc_id", docId)
                put("format", c.format)
                put("content", c.content)
            }
            w.insertOrThrow("code", null, v)
        }
    }

    fun codes(docId: Long?): List<Code> {
        if (docId == null) return emptyList()
        return w.rawQuery("SELECT format, content FROM code WHERE doc_id = ? ORDER BY id", arrayOf(docId.toString())).use { c ->
            val out = ArrayList<Code>()
            while (c.moveToNext()) out += Code(c.getString(0), c.getString(1))
            out
        }
    }

    // ---------------------------------------------------------------- search

    class Found(
        val folders: List<Folder>, val docs: List<DocRow>, val deadlines: List<Deadline>,
        val medicines: List<Medicine> = emptyList(), val visits: List<Visit> = emptyList()
    )

    /** Everything whose text contains q (case-insensitive). Small data: a LIKE is plenty. */
    fun search(q: String): Found {
        val t = q.trim()
        if (t.length < 2) return Found(emptyList(), emptyList(), emptyList())
        val like = "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val esc = " ESCAPE '\\'"
        val folders = folders().filter { it.name.contains(t, true) || it.note.contains(t, true) }
        val me = profile.toString()
        val docs = docsWhere(
            "WHERE profile_id = ? AND (text LIKE ?$esc OR title LIKE ?$esc OR issuer LIKE ?$esc OR note LIKE ?$esc)",
            arrayOf(me, like, like, like, like)
        ).sortedByDescending { it.shownDate }
        val docIds = docs.map { it.id }.toSet()
        val deadlines = deadlinesWhere(
            "WHERE profile_id = ? AND (title LIKE ?$esc OR issuer LIKE ?$esc OR note LIKE ?$esc)", arrayOf(me, like, like, like)
        ).filter { it.docId == null || it.docId !in docIds }
        val meds = medicines().filter { it.name.contains(t, true) || it.purpose.contains(t, true) || it.note.contains(t, true) }
        val visits = visits().filter {
            it.title.contains(t, true) || it.doctor.contains(t, true) || it.place.contains(t, true) || it.note.contains(t, true)
        }
        return Found(folders, docs, deadlines, meds, visits)
    }

    // ---------------------------------------------------------------- health

    private fun day(c: Cursor, i: Int): LocalDate? = if (c.isNull(i)) null else runCatching { LocalDate.parse(c.getString(i)) }.getOrNull()
    private fun ContentValues.putDay(k: String, d: LocalDate?) { if (d != null) put(k, d.toString()) else putNull(k) }
    private fun ContentValues.putId(k: String, id: Long?) { if (id != null) put(k, id) else putNull(k) }

    private fun medicine(c: Cursor) = Medicine(
        id = c.getLong(0), profileId = c.getLong(1), folderId = if (c.isNull(2)) null else c.getLong(2),
        name = c.getString(3), purpose = c.getString(4), amount = c.getDouble(5), unit = c.getString(6),
        times = c.getString(7).split(',').mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }.sorted(),
        everyDays = c.getInt(8), start = day(c, 9) ?: LocalDate.now(), until = day(c, 10), remind = c.getInt(11) != 0,
        stockMode = StockMode.of(c.getInt(12)), stock = if (c.isNull(13)) null else c.getDouble(13), stockDate = day(c, 14),
        pack = c.getInt(15), leadDays = c.getInt(16), orderEvery = c.getInt(17), lastOrder = day(c, 18),
        orderNotified = day(c, 19), active = c.getInt(20) != 0, note = c.getString(21),
        rule = c.getString(22) ?: "", reserve = c.getInt(23)
    )

    /** Medicines with their marked doses of the last months attached (for stock and adherence). */
    private fun medicinesWhere(where: String, args: Array<String>?): List<Medicine> {
        val meds = w.rawQuery("SELECT $MED_COLS FROM medicine $where ORDER BY active DESC, name COLLATE NOCASE", args).use { c ->
            val out = ArrayList<Medicine>()
            while (c.moveToNext()) out += medicine(c)
            out
        }
        if (meds.isEmpty()) return meds
        val logs = doseLogs(meds.map { it.id }, LocalDate.now().minusDays(400))
        return meds.map { m -> logs[m.id]?.let { m.copy(log = it) } ?: m }
    }

    // --------------------------------------------------------------- doses

    private fun doseLogs(ids: List<Long>, since: LocalDate): Map<Long, List<DoseLog>> =
        w.rawQuery("SELECT id, medicine_id, day, slot, status, at FROM dose_log WHERE medicine_id IN " + inList(ids) +
            " AND day >= ? ORDER BY day, id", arrayOf(since.toString())).use { c ->
            val out = HashMap<Long, ArrayList<DoseLog>>()
            while (c.moveToNext()) {
                val l = DoseLog(
                    id = c.getLong(0), medId = c.getLong(1), day = LocalDate.parse(c.getString(2)),
                    slot = if (c.isNull(3)) null else runCatching { LocalTime.parse(c.getString(3)) }.getOrNull(),
                    status = DoseStatus.of(c.getString(4)),
                    at = runCatching { LocalDateTime.parse(c.getString(5)) }.getOrDefault(LocalDateTime.now())
                )
                out.getOrPut(l.medId) { ArrayList() } += l
            }
            out
        }

    /** Mark a dose. A dose of the schedule has one mark (the last wins); extra doses (slot null) add up. */
    fun logDose(medId: Long, day: LocalDate, slot: LocalTime?, status: DoseStatus, at: LocalDateTime = LocalDateTime.now()) {
        val v = ContentValues().apply {
            put("medicine_id", medId)
            put("day", day.toString())
            if (slot != null) put("slot", slot.toString()) else putNull("slot")
            put("status", status.code)
            put("at", at.withNano(0).toString())
        }
        if (slot != null) w.insertWithOnConflict("dose_log", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        else w.insertOrThrow("dose_log", null, v)
    }

    /** Remove the mark of a dose of the schedule (back to "not marked"). */
    fun unlogDose(medId: Long, day: LocalDate, slot: LocalTime) {
        w.execSQL("DELETE FROM dose_log WHERE medicine_id = ? AND day = ? AND slot = ?", arrayOf<Any>(medId, day.toString(), slot.toString()))
    }

    fun deleteDoseLog(id: Long) {
        w.delete("dose_log", "id = ?", arrayOf(id.toString()))
    }

    /** Every mark of a medicine, newest first (for the CSV). */
    fun allDoseLogs(medId: Long): List<DoseLog> = doseLogs(listOf(medId), LocalDate.of(1990, 1, 1))[medId]?.reversed() ?: emptyList()

    // ---------------------------------------------------------- measurements

    private fun measure(c: Cursor) = Measure(
        id = c.getLong(0), profileId = c.getLong(1), folderId = if (c.isNull(2)) null else c.getLong(2),
        kind = MeasureKind.of(c.getString(3)),
        at = runCatching { LocalDateTime.parse(c.getString(4)) }.getOrDefault(LocalDateTime.now()),
        v1 = c.getDouble(5), v2 = if (c.isNull(6)) null else c.getDouble(6), v3 = if (c.isNull(7)) null else c.getDouble(7),
        note = c.getString(8)
    )

    /** Measurements of the profile in use, newest first. */
    fun measures(): List<Measure> =
        w.rawQuery("SELECT $MEASURE_COLS FROM measure WHERE profile_id = ? ORDER BY at DESC, id DESC", arrayOf(profile.toString())).use { c ->
            val out = ArrayList<Measure>()
            while (c.moveToNext()) out += measure(c)
            out
        }

    fun measure(id: Long): Measure? =
        w.rawQuery("SELECT $MEASURE_COLS FROM measure WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) measure(it) else null }

    fun saveMeasure(m: Measure): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(m.profileId))
            putId("folder_id", m.folderId)
            put("kind", m.kind.code)
            put("at", m.at.withSecond(0).withNano(0).toString())
            put("v1", m.v1)
            if (m.v2 != null) put("v2", m.v2) else putNull("v2")
            if (m.v3 != null) put("v3", m.v3) else putNull("v3")
            put("note", m.note)
        }
        return if (m.id == 0L) w.insertOrThrow("measure", null, v)
        else { w.update("measure", v, "id = ?", arrayOf(m.id.toString())); m.id }
    }

    fun deleteMeasure(id: Long) {
        w.delete("measure", "id = ?", arrayOf(id.toString()))
    }

    /** Medicines of the profile in use. */
    fun medicines(): List<Medicine> = medicinesWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    /** Medicines of every profile (dose alarms, orders). */
    fun allMedicines(): List<Medicine> = medicinesWhere("", null)

    fun medicine(id: Long): Medicine? = medicinesWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun saveMedicine(m: Medicine): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(m.profileId))
            putId("folder_id", m.folderId)
            put("name", m.name)
            put("purpose", m.purpose)
            put("amount", m.amount)
            put("unit", m.unit)
            put("times", m.times.sorted().joinToString(",") { it.toString() })
            put("every_days", m.everyDays)
            put("start_date", m.start.toString())
            putDay("until_date", m.until)
            put("remind", if (m.remind) 1 else 0)
            put("stock_mode", m.stockMode.code)
            if (m.stock != null) put("stock", m.stock) else putNull("stock")
            putDay("stock_date", m.stockDate)
            put("pack", m.pack)
            put("lead_days", m.leadDays)
            put("order_every", m.orderEvery)
            putDay("last_order", m.lastOrder)
            putDay("order_notified", m.orderNotified)
            put("active", if (m.active) 1 else 0)
            put("note", m.note)
            put("rule", m.rule)
            put("reserve", m.reserve)
        }
        return if (m.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("medicine", null, v) }
        else { w.update("medicine", v, "id = ?", arrayOf(m.id.toString())); m.id }
    }

    fun setOrderNotified(id: Long, d: LocalDate) {
        w.execSQL("UPDATE medicine SET order_notified = ? WHERE id = ?", arrayOf<Any>(d.toString(), id))
    }

    fun deleteMedicine(id: Long) {
        w.delete("dose_log", "medicine_id = ?", arrayOf(id.toString()))
        w.delete("medicine", "id = ?", arrayOf(id.toString()))
    }

    private fun visit(c: Cursor) = Visit(
        id = c.getLong(0), profileId = c.getLong(1), folderId = if (c.isNull(2)) null else c.getLong(2),
        docId = if (c.isNull(3)) null else c.getLong(3), title = c.getString(4), status = VisitStatus.of(c.getString(5)),
        date = day(c, 6), time = if (c.isNull(7)) null else runCatching { LocalTime.parse(c.getString(7)) }.getOrNull(),
        dueBy = day(c, 8), priority = c.getString(9), doctor = c.getString(10), place = c.getString(11),
        remindDays = c.getInt(12), notified = c.getInt(13), note = c.getString(14)
    )

    private fun visitsWhere(where: String, args: Array<String>?): List<Visit> =
        w.rawQuery("SELECT $VISIT_COLS FROM visit $where ORDER BY id", args).use { c ->
            val out = ArrayList<Visit>()
            while (c.moveToNext()) out += visit(c)
            out
        }

    /** Visits of the profile in use. */
    fun visits(): List<Visit> = visitsWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    /** Visits of every profile (for the reminders). */
    fun allVisits(): List<Visit> = visitsWhere("", null)

    fun visit(id: Long): Visit? =
        w.rawQuery("SELECT $VISIT_COLS FROM visit WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) visit(it) else null }

    fun visitsOfDoc(docId: Long): List<Visit> = visitsWhere("WHERE doc_id = ?", arrayOf(docId.toString()))

    fun saveVisit(x: Visit): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(x.profileId))
            putId("folder_id", x.folderId)
            putId("doc_id", x.docId)
            put("title", x.title)
            put("status", x.status.code)
            putDay("day", x.date)
            if (x.time != null) put("at", x.time.toString()) else putNull("at")
            putDay("due_by", x.dueBy)
            put("priority", x.priority)
            put("doctor", x.doctor)
            put("place", x.place)
            put("remind_days", x.remindDays)
            put("notified", x.notified)
            put("note", x.note)
        }
        return if (x.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("visit", null, v) }
        else { w.update("visit", v, "id = ?", arrayOf(x.id.toString())); x.id }
    }

    fun setVisitNotified(id: Long, level: Int) {
        w.execSQL("UPDATE visit SET notified = ? WHERE id = ?", arrayOf<Any>(level, id))
    }

    fun deleteVisit(id: Long) {
        w.delete("visit", "id = ?", arrayOf(id.toString()))
    }

    private fun item(c: Cursor) = HealthItem(
        id = c.getLong(0), profileId = c.getLong(1), folderId = if (c.isNull(2)) null else c.getLong(2),
        kind = HealthKind.of(c.getString(3)), name = c.getString(4), date = day(c, 5), active = c.getInt(6) != 0, note = c.getString(7),
        nextDue = day(c, 8), notified = c.getInt(9), fromBirth = c.getInt(10) != 0
    )

    private fun itemsWhere(where: String, args: Array<String>?): List<HealthItem> =
        w.rawQuery("SELECT $ITEM_COLS FROM health_item $where ORDER BY active DESC, day DESC, name COLLATE NOCASE", args).use { c ->
            val out = ArrayList<HealthItem>()
            while (c.moveToNext()) out += item(c)
            out
        }

    /** Conditions, allergies, operations and vaccines of the profile in use. */
    fun healthItems(): List<HealthItem> = itemsWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    fun healthItem(id: Long): HealthItem? = itemsWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    /** Of every profile (booster reminders). */
    fun allHealthItems(): List<HealthItem> = itemsWhere("", null)

    fun setItemNotified(id: Long, level: Int) {
        w.execSQL("UPDATE health_item SET notified = ? WHERE id = ?", arrayOf<Any>(level, id))
    }

    fun saveHealthItem(x: HealthItem): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(x.profileId))
            putId("folder_id", x.folderId)
            put("kind", x.kind.code)
            put("name", x.name)
            putDay("day", x.date)
            put("active", if (x.active) 1 else 0)
            put("note", x.note)
            putDay("next_due", x.nextDue)
            put("notified", x.notified)
            put("from_birth", if (x.fromBirth) 1 else 0)
        }
        return if (x.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("health_item", null, v) }
        else { w.update("health_item", v, "id = ?", arrayOf(x.id.toString())); x.id }
    }

    fun deleteHealthItem(id: Long) {
        w.delete("health_item", "id = ?", arrayOf(id.toString()))
    }

    /** Default top-level folders still named as in another language: give them this language's names. */
    fun relabelDefaults(names: Map<String, Pair<Set<String>, String>>) {
        for (f in folders(w)) {
            if (f.parentId != null) continue
            val (known, now) = names[f.kind] ?: continue
            if (f.name in known && f.name != now) renameFolder(f.id, now)
        }
    }

    // --------------------------------------------------------------- incomes

    private fun incomesWhere(where: String, args: Array<String>?): List<Income> =
        w.rawQuery("SELECT id, profile_id, month, amount, doc_id, extra, note FROM income $where ORDER BY month, id", args).use { c ->
            val out = ArrayList<Income>()
            while (c.moveToNext()) out += Income(
                id = c.getLong(0), profileId = c.getLong(1), month = YearMonth.parse(c.getString(2)),
                amountCents = c.getLong(3), docId = if (c.isNull(4)) null else c.getLong(4),
                extra = c.getInt(5) != 0, note = c.getString(6)
            )
            out
        }

    /** Income of the profile in use (a dependent's expenses count against the parent's income). */
    fun incomes(): List<Income> = incomesWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    fun income(id: Long): Income? = incomesWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun saveIncome(i: Income): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(i.profileId))
            put("month", i.month.toString())
            put("amount", i.amountCents)
            if (i.docId != null) put("doc_id", i.docId) else putNull("doc_id")
            put("extra", if (i.extra) 1 else 0)
            put("note", i.note)
        }
        return if (i.id == 0L) w.insertOrThrow("income", null, v)
        else { w.update("income", v, "id = ?", arrayOf(i.id.toString())); i.id }
    }

    /** Delete a payslip entry. Its document stays in the archive. */
    fun deleteIncome(id: Long) {
        w.delete("income", "id = ?", arrayOf(id.toString()))
    }

    // --------------------------------------------------------------- inflows

    private fun inflowsWhere(where: String, args: Array<String>?): List<Inflow> =
        w.rawQuery("SELECT $INFLOW_COLS FROM inflow $where ORDER BY day DESC, id DESC", args).use { c ->
            val out = ArrayList<Inflow>()
            while (c.moveToNext()) out += Inflow(
                id = c.getLong(0), profileId = c.getLong(1), day = LocalDate.parse(c.getString(2)), amountCents = c.getLong(3),
                cat = InflowCat.of(c.getString(4)), note = c.getString(5), cash = c.getInt(6) != 0
            )
            out
        }

    /** Other money that came in, for the profile in use (like the payslips). */
    fun inflows(): List<Inflow> = inflowsWhere("WHERE profile_id = ?", arrayOf(profile.toString()))

    fun inflow(id: Long): Inflow? = inflowsWhere("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun saveInflow(x: Inflow): Long {
        val v = ContentValues().apply {
            put("profile_id", pid(x.profileId))
            put("day", x.day.toString())
            put("amount", x.amountCents)
            put("cat", x.cat.code)
            put("note", x.note)
            put("cash", if (x.cash) 1 else 0)
        }
        return if (x.id == 0L) { v.put("created", LocalDate.now().toString()); w.insertOrThrow("inflow", null, v) }
        else { w.update("inflow", v, "id = ?", arrayOf(x.id.toString())); x.id }
    }

    fun deleteInflow(id: Long) {
        w.delete("inflow", "id = ?", arrayOf(id.toString()))
    }

    // -------------------------------------------------------- whole-file ops

    fun file(): File = ctx.getDatabasePath(NAME)

    /** The database file as bytes. Safe between transactions (no WAL). */
    fun snapshot(): ByteArray {
        writableDatabase // make sure it exists and is open
        return file().readBytes()
    }

    /** Counts shown before an import replaces the data. */
    class Summary(
        val deadlines: Int, val incomes: Int, val docs: Int, val version: Int, val health: Int = 0,
        val profiles: Int = 1, val pages: Set<String> = emptySet()
    )

    fun summaryOf(f: File): Summary {
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            fun count(t: String) = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val health = if (db.version >= 3) count("medicine") + count("visit") + count("health_item") +
                (if (db.version >= 4) count("measure") else 0) else 0
            val pages = HashSet<String>()
            if (db.version >= 4) db.rawQuery("SELECT name FROM page", null).use { c -> while (c.moveToNext()) pages += c.getString(0) }
            return Summary(count("deadline"), count("income"), count("doc"), db.version, health, count("profile"), pages)
        } finally {
            db.close()
        }
    }

    /** Replace the whole database with another file (already validated). */
    fun replaceWith(f: File) {
        close()
        val target = file()
        f.copyTo(target, overwrite = true)
        for (suffix in listOf("-journal", "-wal", "-shm")) File(target.path + suffix).delete()
        cur = 0L
    }
}
