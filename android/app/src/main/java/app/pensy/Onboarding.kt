package app.pensy

import android.app.NotificationManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.pensy.core.Profile

/*
 * Making the first days easy:
 *
 *  - SetupScreen: three short steps at the first launch that set PENSY up
 *    (language and name, the people you follow, where PENSY_DATA goes),
 *    instead of a tour that only describes it;
 *  - "Primi passi" on Oggi: what is still to do, ticked by itself;
 *  - a one-line hint the first time each tab is opened.
 */

/** Device settings for the hints and the "Primi passi" card. */
object Hints {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pensy_device", Context.MODE_PRIVATE)
    fun hidden(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean("hint_$key", false)
    fun hide(ctx: Context, key: String) { prefs(ctx).edit().putBoolean("hint_$key", true).apply() }
    /** Bring every hint back ("Rivedi i suggerimenti" in Settings). */
    fun reset(ctx: Context) {
        val p = prefs(ctx)
        val e = p.edit()
        for (k in p.all.keys) if (k.startsWith("hint_")) e.remove(k)
        e.apply()
    }
}

/** A small card with one sentence about the screen, shown until it is closed. */
fun MainActivity.hintCard(key: String, textRes: Int): View? {
    if (Hints.hidden(this, key)) return null
    val box = hbox().apply {
        gravity = Gravity.TOP
        background = rounded(col(R.color.accent_soft), 18)
        setPadding(dp(14), dp(12), dp(4), dp(12))
    }
    box.add(icon(R.drawable.ic_help, col(R.color.accent_ink), 20), w = dp(20), h = dp(20), top = 1)
    box.add(text(getString(textRes), 14f, col(R.color.ink)), w = 0, weight = 1f, start = 12)
    box.add(iconButton(R.drawable.ic_close, R.string.hint_close, col(R.color.ink2)) {
        Hints.hide(this, key)
        refresh()
    }.apply { minimumWidth = dp(40); minimumHeight = dp(32) }, w = WRAP, h = WRAP)
    return box
}

/**
 * "Primi passi": the few things that make PENSY useful, each with its
 * button, ticked as soon as they are done. It goes away when everything
 * is done or when it is hidden. Only for the main profile.
 */
fun MainActivity.firstStepsCard(): View? {
    if (db.profile != 1L || Hints.hidden(this, "steps")) return null

    class Step(val key: String, val title: Int, val sub: Int, val done: Boolean, val skippable: Boolean, val actions: List<Pair<String, () -> Unit>>)

    val notifOn = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    val hasDoc = db.docs().isNotEmpty() || db.deadlines().isNotEmpty()
    val hasHealth = db.medicines().isNotEmpty() || db.visits().isNotEmpty() || db.healthItems().isNotEmpty()
    val steps = listOf(
        Step("doc", R.string.steps_doc, R.string.steps_doc_sub, hasDoc, false, listOf(
            getString(R.string.steps_photo) to { Capture.camera(this) },
            getString(R.string.steps_file) to { Capture.pickFile(this) }
        )),
        Step("pay", R.string.steps_pay, R.string.steps_pay_sub, db.incomes().isNotEmpty() || Hints.hidden(this, "step_pay"), true, listOf(
            getString(R.string.steps_add) to { addPayslip() }
        )),
        Step("folder", R.string.steps_folder, R.string.steps_folder_sub, DataFolder.isConfigured(this), false, listOf(
            getString(R.string.steps_choose) to { DataFolder.chooseFolder(this) }
        )),
        Step("notif", R.string.steps_notif, R.string.steps_notif_sub, notifOn, false, listOf(
            getString(R.string.steps_enable) to { enableNotifications() }
        )),
        Step("health", R.string.steps_health, R.string.steps_health_sub, hasHealth || Hints.hidden(this, "step_health"), true, listOf(
            getString(R.string.steps_open) to {
                showTab(1)
                healthRoot()?.let { push(FolderScreen(this, it.id)) }
            }
        ))
    )
    val done = steps.count { it.done }
    if (done == steps.size) return null

    val box = card(18)
    val head = hbox()
    head.add(text(getString(R.string.steps_title), 17f, face = MEDIUM), w = 0, weight = 1f)
    head.add(text(getString(R.string.steps_count, done, steps.size), 13f, col(R.color.ink2), MEDIUM), w = WRAP)
    box.add(head)
    box.add(progressBar(done.toDouble() / steps.size, col(R.color.good)), top = 10)

    for (s in steps) {
        val row = hbox().apply { gravity = Gravity.TOP }
        val mark = text(if (s.done) "✓" else "", 13f, col(R.color.on_accent), BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(col(if (s.done) R.color.good else R.color.surface), 12, if (s.done) null else col(R.color.line))
        }
        row.add(mark, w = dp(24), h = dp(24), top = 1)
        val mid = vbox()
        mid.add(text(getString(s.title), 15f, col(if (s.done) R.color.ink2 else R.color.ink), MEDIUM))
        if (!s.done) {
            mid.add(text(getString(s.sub), 13f, col(R.color.ink2)), top = 2)
            val buttons = hbox()
            for ((label, fn) in s.actions) {
                buttons.add(button(label, Btn.SECONDARY) { fn() }.apply {
                    minHeight = dp(38)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    textSize = 14f
                }, w = WRAP, end = 8)
            }
            if (s.skippable) {
                buttons.add(text(getString(R.string.steps_skip), 13.5f, col(R.color.ink2), MEDIUM).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = ripple(null, 12)
                    setOnClickListener { Hints.hide(this@firstStepsCard, "step_" + s.key); refresh() }
                }, w = WRAP)
            }
            mid.add(buttons, top = 8)
        }
        row.add(mid, w = 0, weight = 1f, start = 12)
        box.add(row, top = 16)
    }

    val hide = text(getString(R.string.steps_hide), 13.5f, col(R.color.ink2), MEDIUM).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = ripple(null, 12)
        setOnClickListener { Hints.hide(this@firstStepsCard, "steps"); refresh() }
    }
    box.add(hide, w = WRAP, gravity = Gravity.END, top = 8)
    return box
}

/**
 * The first launch: three short steps that set PENSY up. Each can be
 * skipped; what is left waits in "Primi passi" on Oggi.
 */
class SetupScreen(act: MainActivity, private var step: Int = 0) : Screen(act) {

    private var name = act.db.profileOf(1L)?.name ?: ""
    private var child = false
    private var childName = ""
    private var childId = 0L
    private var parent = false
    private var parentName = ""
    private var parentId = 0L

    init {
        // Like the old tour: shown once, even if PENSY is closed halfway.
        Tour.setDone(act)
    }

    override fun build(): View {
        val c = act
        val root = c.vbox().apply { setBackgroundColor(c.col(R.color.bg)) }

        val top = c.hbox().apply {
            setPadding(c.dp(24), c.dp(8), c.dp(8), 0)
            minimumHeight = c.dp(56)
        }
        val dots = c.hbox()
        for (i in 0 until 3) {
            dots.add(View(c).apply { background = c.rounded(c.col(if (i <= step) R.color.accent else R.color.line), 4) },
                w = c.dp(if (i == step) 22 else 8), h = c.dp(8), end = 6)
        }
        top.add(dots, w = WRAP)
        top.add(c.text(c.getString(R.string.setup_step, step + 1, 3), 13f, c.col(R.color.ink2), MEDIUM), w = 0, weight = 1f, start = 8)
        top.add(c.button(c.getString(R.string.tour_skip), Btn.GHOST) { c.tourFinished() }.apply { background = c.ripple(null, 16) }, w = WRAP)
        root.add(top)

        val body = c.vbox().apply { setPadding(c.dp(24), c.dp(12), c.dp(24), c.dp(24)) }
        when (step) {
            0 -> welcome(body)
            1 -> people(body)
            else -> data(body)
        }
        val scroll = ScrollView(c).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.add(scroll, h = 0, weight = 1f)

        val bottom = c.hbox().apply { setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(20)) }
        if (step > 0) bottom.add(c.button(c.getString(R.string.back), Btn.GHOST) { step--; refresh() }, w = WRAP)
        bottom.add(View(c), w = 0, h = 1, weight = 1f)
        bottom.add(c.button(c.getString(if (step == 2) R.string.tour_start else R.string.tour_next), Btn.PRIMARY,
            if (step == 2) R.drawable.ic_check else 0) { next() }, w = WRAP)
        root.add(bottom)
        return root
    }

    private fun picture(body: LinearLayout, iconRes: Int, color: Int) {
        val c = act
        val circle = FrameLayout(c)
        circle.background = c.rounded((color and 0x00FFFFFF) or 0x26000000, 36)
        circle.addView(c.icon(iconRes, color, 36), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
        body.add(circle, w = c.dp(72), h = c.dp(72), top = 8)
    }

    private fun welcome(body: LinearLayout) {
        val c = act
        body.add(ImageView(c).apply { setImageResource(R.mipmap.ic_launcher) }, w = c.dp(80), h = c.dp(80), top = 4)
        body.add(c.text(c.getString(R.string.setup_welcome), 28f, face = BOLD), top = 18)
        body.add(c.text(c.getString(R.string.setup_welcome_text), 16f, c.col(R.color.ink2)).apply { setLineSpacing(0f, 1.2f) }, top = 10)
        body.add(c.manualLink(), top = 16)

        body.add(c.text(c.getString(R.string.tour_lang), 13f, c.col(R.color.ink2), MEDIUM), top = 26, start = 4)
        val cur = Lang.code(c)
        body.add(c.chips(listOf("it" to c.getString(R.string.lang_it), "en" to c.getString(R.string.lang_en)),
            if (cur.isEmpty()) c.getString(R.string.lang_code) else cur) { code ->
            if (code != c.getString(R.string.lang_code)) c.changeLanguage(code, back = "setup")
        }, top = 8)

        body.add(c.labeled(c.getString(R.string.setup_name), c.field(c.getString(R.string.setup_name_hint), name,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS) { name = it }), top = 22)

        val restore = c.card(16)
        restore.add(c.text(c.getString(R.string.setup_restore_title), 15f, face = MEDIUM))
        restore.add(c.text(c.getString(R.string.setup_restore_text), 13.5f, c.col(R.color.ink2)), top = 4)
        restore.add(c.button(c.getString(R.string.setup_restore), Btn.GHOST, R.drawable.ic_folder) { DataFolder.chooseImport(c) },
            w = WRAP, top = 10)
        body.add(restore, top = 26)
    }

    private fun people(body: LinearLayout) {
        val c = act
        picture(body, R.drawable.ic_person, 0xFF0E8FA8.toInt())
        body.add(c.text(c.getString(R.string.setup_people), 26f, face = BOLD), top = 18)
        body.add(c.text(c.getString(R.string.setup_people_text), 16f, c.col(R.color.ink2)).apply { setLineSpacing(0f, 1.2f) }, top = 10)

        val box = c.card(14)
        box.add(c.toggleRow(c.getString(R.string.setup_child), child) { child = it; refresh() })
        if (child) box.add(c.field(c.getString(R.string.setup_child_name), childName,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS) { childName = it }, top = 4, bottom = 8)
        box.add(c.toggleRow(c.getString(R.string.setup_parent), parent) { parent = it; refresh() }, top = 4)
        if (parent) box.add(c.field(c.getString(R.string.setup_parent_name), parentName,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS) { parentName = it }, top = 4)
        body.add(box, top = 22)
        body.add(c.text(c.getString(R.string.setup_people_later), 13.5f, c.col(R.color.ink2)), top = 12, start = 4)
    }

    private fun data(body: LinearLayout) {
        val c = act
        picture(body, R.drawable.ic_folder, 0xFF475569.toInt())
        body.add(c.text(c.getString(R.string.folder_card_title), 26f, face = BOLD), top = 18)
        body.add(c.text(c.getString(R.string.setup_data_text), 16f, c.col(R.color.ink2)).apply { setLineSpacing(0f, 1.2f) }, top = 10)
        if (DataFolder.isConfigured(c)) {
            val ok = c.card(16, c.col(R.color.good_soft))
            val r = c.hbox()
            r.add(c.icon(R.drawable.ic_check, c.col(R.color.good), 22), w = c.dp(22), h = c.dp(22))
            r.add(c.text(c.getString(R.string.setup_data_done, DataFolder.folderLabel(c) ?: "PENSY_DATA"), 15f, face = MEDIUM), w = 0, weight = 1f, start = 10)
            ok.add(r)
            body.add(ok, top = 22)
        } else {
            body.add(c.button(c.getString(R.string.folder_choose), Btn.SECONDARY, R.drawable.ic_folder) { DataFolder.chooseFolder(c) },
                w = WRAP, top = 22)
            body.add(c.text(c.getString(R.string.setup_data_later), 13.5f, c.col(R.color.ink2)), top = 12, start = 4)
        }
    }

    private fun next() {
        val c = act
        when (step) {
            0 -> {
                c.db.profileOf(1L)?.let { p -> if (p.name != name.trim()) { c.db.saveProfile(p.copy(name = name.trim())); c.changed() } }
                step = 1
                refresh()
            }
            1 -> {
                if (child && childName.isBlank() || parent && parentName.isBlank()) { c.toast(R.string.profile_need_name); return }
                childId = person(child, childId, childName, "bambino", 1L)
                parentId = person(parent, parentId, parentName, "assistito", null)
                step = 2
                refresh()
            }
            else -> c.tourFinished()
        }
    }

    /** Create, rename or remove the person of one toggle (going back and forth must not make copies). */
    private fun person(on: Boolean, id: Long, name: String, kind: String, budgetOf: Long?): Long {
        val c = act
        if (!on) {
            if (id != 0L) { c.db.deleteProfile(id); c.changed() }
            return 0L
        }
        val saved = c.db.saveProfile(Profile(id = id, name = name.trim(), kind = kind, budgetOf = budgetOf))
        c.changed()
        return saved
    }
}
