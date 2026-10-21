package app.pensy

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * A screen: builds its view from the current data every time it is shown.
 * Data is small, so rebuilding is instant and the UI is never stale.
 */
abstract class Screen(val act: MainActivity) {
    /** Bottom tab this screen belongs to; -1 hides the tab bar (detail and edit screens). */
    open val tab: Int = -1
    internal var scrollY = 0
    abstract fun build(): View
    fun refresh() = act.refresh()
    /** Called once when the screen leaves the stack (back, or a jump to a tab). */
    open fun onClose() {}
}

/**
 * The only activity. It holds a small stack of screens, the bottom bar,
 * and routes the results coming back from the camera and file pickers.
 */
class MainActivity : Activity() {

    companion object {
        const val REQ_CAMERA = 11
        const val REQ_FILE = 12
        const val REQ_TREE = 21
        const val REQ_IMPORT_TREE = 22
        const val REQ_IMPORT_FILE = 23
        const val REQ_EXPORT = 24
        const val REQ_NOTIF = 31
        const val REQ_SETTINGS = 32
        const val REQ_UNLOCK = 41
        const val EXTRA_DEADLINE = "deadline_id"
        const val EXTRA_VISIT = "visit_id"
        /** A medicine id, or -1 for "the health record" (several medicines at once). */
        const val EXTRA_MEDICINE = "medicine_id"
        /** The profile a notification is about. */
        const val EXTRA_PROFILE = "profile_id"
        /** A health item (a vaccine whose booster is due). */
        const val EXTRA_ITEM = "item_id"
        /** The emergency card on the lock screen was tapped: the profile. */
        const val EXTRA_ICE = "ice_profile"
        /** The widget's camera button. */
        const val EXTRA_CAPTURE = "capture"

        /** Tab to show after the activity is recreated for a language change. */
        var reopenTab = -1
        /** Screen to show again after the language was changed from it: "setup" or "tour". */
        var reopen: String? = null
    }

    private lateinit var frame: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val stack = ArrayList<Screen>()
    private var busy: View? = null
    private var imeVisible = false

    /** The lock cover, while PENSY is locked. */
    private var lockView: View? = null
    private var stoppedAt = 0L
    /** The camera, a picker or the share sheet is open: coming back from it is not "coming back later". */
    private var external = false
    private var authenticating = false

    override fun attachBaseContext(newBase: Context) {
        val ctx = try {
            Lang.wrap(newBase)
        } catch (t: Throwable) {
            Crash.save(newBase, t, "Lang")
            newBase
        }
        super.attachBaseContext(ctx)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PENSY closed (or could not start its reminders) last time: show why, before anything else.
        if (Crash.pending(this) != null) { Crash.show(this); finish(); return }
        try {
            start()
        } catch (t: Throwable) {
            Crash.save(this, t, "MainActivity")
            Crash.show(this)
            finish()
        }
    }

    private fun start() {
        setupWindow()
        frame = FrameLayout(this)
        column = vbox().apply { setBackgroundColor(col(R.color.bg)) }
        content = FrameLayout(this)
        column.add(content, h = 0, weight = 1f)
        nav = buildNav()
        column.add(nav)
        frame.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)
        applyInsets()
        Lock.applyRecents(this)
        Pages.cleanPending(this)
        try { if (db.catchUpPlans(java.time.LocalDate.now()) > 0) changed() } catch (t: Throwable) { }
        val tab = reopenTab
        val again = reopen
        reopenTab = -1
        reopen = null
        if (again == "setup") { showTab(0); push(SetupScreen(this)) }
        else if (again == "tour") { showTab(0); push(TourScreen(this)) }
        else if (tab >= 0) showTab(tab)
        else if (!Tour.done(this)) { showTab(0); push(SetupScreen(this)) }
        else {
            showTab(0)
            askNotificationPermission()
        }
        handleIntent(intent)
        Notify.catchUp(this)
    }

    /** Apply a new language: the whole activity is rebuilt with it, on the same tab. */
    fun changeLanguage(code: String, back: String? = null) {
        val old = Lang.wrap(applicationContext)
        Lang.set(this, code)
        val now = Lang.wrap(applicationContext)
        db.relabelDefaults(Tour.defaultNames(old, now))
        Notify.createChannel(this)
        changed()
        reopenTab = stack.firstOrNull()?.tab?.takeIf { it >= 0 } ?: 4
        reopen = back
        recreate()
    }

    /** After the tour: the notification question comes once the user knows what PENSY is. */
    fun tourFinished() {
        Tour.setDone(this)
        popToRoot()
        showTab(0)
        askNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // "Alarms & reminders" may have just been allowed in the system settings.
        Doses.schedule(this)
        // Coming back after midnight or after a notification: show fresh data.
        if (stack.isNotEmpty() && stack.last().tab >= 0) refresh()
    }

    override fun onStart() {
        super.onStart()
        if (!::frame.isInitialized) return
        if (Lock.active(this) && lockView == null) {
            val away = SystemClock.elapsedRealtime() - stoppedAt
            val limit = if (external) Lock.EXTERNAL_MS else Lock.TIMEOUT_MS
            if (stoppedAt == 0L || away > limit) showLock()
        }
        external = false
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        DataFolder.flush(this)
    }

    // ------------------------------------------------------------ lock

    private fun showLock() {
        val v = Lock.cover(this) { unlock() }
        frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        lockView = v
        // Ask once the window is on screen.
        frame.post { if (lockView != null) unlock() }
    }

    private fun unlock() {
        if (authenticating) return
        authenticating = true
        Lock.authenticate(this, onOk = {
            authenticating = false
            lockView?.let { frame.removeView(it) }
            lockView = null
        }, onFail = { authenticating = false })
    }

    /** Start the camera, a picker or the share sheet (req 0: no result wanted). */
    @Suppress("DEPRECATION")
    fun startExternal(i: Intent, req: Int) {
        external = true
        if (req == 0) startActivity(i) else startActivityForResult(i, req)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    @Deprecated("Back handling for all Android versions")
    override fun onBackPressed() {
        if (lockView != null) { moveTaskToBack(true); return }
        if (busy != null) return
        if (stack.size > 1) pop()
        else if (stack.firstOrNull()?.tab != 0) showTab(0)
        else super.onBackPressed()
    }

    // ------------------------------------------------------------ window

    /** Draw edge to edge (the bars are transparent); insets are applied by hand below. */
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val w = window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // Stefano's fix for Samsung and Motorola: the controller only once the decor view exists.
            val decor = w.decorView
            decor.post {
                val controller = w.insetsController ?: return@post
                controller.setSystemBarsAppearance(if (isNight()) 0 else light, light)
            }
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!isNight()) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            val left: Int
            val right: Int
            val ime: Boolean
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top; left = bars.left; right = bars.right
                bottom = maxOf(bars.bottom, keyboard.bottom)
                ime = insets.isVisible(WindowInsets.Type.ime())
            } else {
                top = insets.systemWindowInsetTop; left = insets.systemWindowInsetLeft; right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
                ime = bottom > dp(120)
            }
            column.setPadding(left, top, right, bottom)
            if (ime != imeVisible) { imeVisible = ime; updateNav() }
            insets
        }
    }

    // ------------------------------------------------------------ screens

    private fun tabScreen(i: Int): Screen = when (i) {
        1 -> FoldersScreen(this)
        2 -> CalendarScreen(this)
        3 -> BudgetScreen(this)
        4 -> SettingsScreen(this)
        else -> HomeScreen(this)
    }

    fun showTab(i: Int) {
        for (s in stack) s.onClose()
        stack.clear()
        stack += tabScreen(i)
        render(keepScroll = false)
    }

    fun push(s: Screen) {
        saveScroll()
        stack += s
        render(keepScroll = false)
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).onClose()
        render()
    }

    /** Back to the tab root, e.g. after saving. */
    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1).onClose()
        render()
    }

    /** Use another profile: everything is shown again from "Oggi". */
    fun switchProfile(id: Long) {
        if (id != db.profile) db.setProfile(id)
        showTab(0)
        Widget.update(this)
    }

    /** A notification about another profile's item: switch to it first. */
    private fun ensureProfile(id: Long) {
        if (id != 0L && id != db.profile && db.profileOf(id) != null) db.setProfile(id)
    }

    /** Rebuild the current screen with fresh data. */
    fun refresh() {
        saveScroll()
        render()
    }

    /** Something changed in the database: rebuild and schedule a PENSY_DATA save. */
    fun changed() {
        DataFolder.saveSoon(this)
        Widget.update(this)
    }

    private fun saveScroll() {
        val s = stack.lastOrNull() ?: return
        (content.findViewWithTag<View>("scroll") as? ScrollView)?.let { s.scrollY = it.scrollY }
    }

    private fun render(keepScroll: Boolean = true) {
        val s = stack.lastOrNull() ?: return
        val v = s.build()
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        if (keepScroll && s.scrollY > 0) {
            (v.findViewWithTag<View>("scroll") as? ScrollView)?.let { sv -> sv.post { sv.scrollTo(0, s.scrollY) } }
        } else s.scrollY = 0
        updateNav()
    }

    // ------------------------------------------------------------ bottom bar

    private val navItems = listOf(
        R.drawable.ic_home to R.string.tab_today,
        R.drawable.ic_folder to R.string.tab_folders,
        R.drawable.ic_calendar to R.string.tab_calendar,
        R.drawable.ic_chart to R.string.tab_budget,
        R.drawable.ic_settings to R.string.tab_settings
    )

    private fun buildNav(): LinearLayout {
        val bar = hbox().apply {
            setBackgroundColor(col(R.color.surface))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        navItems.forEachIndexed { i, (icon, label) ->
            val item = vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                background = ripple(null, 18)
                setOnClickListener { if (stack.size != 1 || stack[0].tab != i) showTab(i) }
                contentDescription = getString(label)
            }
            val pill = FrameLayout(this)
            val ic = ImageView(this).apply { setImageResource(icon) }
            pill.addView(ic, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            item.add(pill, w = dp(52), h = dp(30), gravity = Gravity.CENTER_HORIZONTAL)
            item.add(text(getString(label), 11.5f, face = MEDIUM, lines = 1).apply { gravity = Gravity.CENTER }, top = 4)
            bar.add(item, w = 0, weight = 1f)
        }
        return bar
    }

    private fun updateNav() {
        val s = stack.lastOrNull()
        val tab = s?.tab ?: -1
        nav.visibility = if (tab >= 0 && !imeVisible) View.VISIBLE else View.GONE
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as LinearLayout
            val pill = item.getChildAt(0) as FrameLayout
            val ic = pill.getChildAt(0) as ImageView
            val label = item.getChildAt(1) as TextView
            val on = i == tab
            pill.background = if (on) rounded(col(R.color.accent_soft), 15) else null
            val c = col(if (on) R.color.accent_ink else R.color.ink2)
            ic.imageTintList = ColorStateList.valueOf(c)
            label.setTextColor(c)
        }
    }

    // ------------------------------------------------------------ busy overlay

    fun showBusy(msgRes: Int) = showBusy(getString(msgRes))

    fun showBusy(msg: String) {
        hideBusy()
        val s = scrim()
        val box = card(22).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.add(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(col(R.color.accent)) },
            w = dp(44), h = dp(44), gravity = Gravity.CENTER_HORIZONTAL)
        box.add(text(msg, 15f).apply { gravity = Gravity.CENTER }, top = 14)
        s.addView(box, FrameLayout.LayoutParams(dp(260), WRAP, Gravity.CENTER))
        frame.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        busy = s
    }

    fun hideBusy() {
        busy?.let { frame.removeView(it) }
        busy = null
    }

    // ------------------------------------------------------------ intents & results

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val id = i.getLongExtra(EXTRA_DEADLINE, 0L)
        if (id != 0L) {
            i.removeExtra(EXTRA_DEADLINE)
            db.deadline(id)?.let { d -> ensureProfile(d.profileId); showTab(0); push(DeadlineScreen(this, id)) }
            return
        }
        val visit = i.getLongExtra(EXTRA_VISIT, 0L)
        if (visit != 0L) {
            i.removeExtra(EXTRA_VISIT)
            db.visit(visit)?.let { v -> ensureProfile(v.profileId); showTab(0); push(VisitScreen(this, visit)) }
            return
        }
        if (i.getBooleanExtra(EXTRA_CAPTURE, false)) {
            i.removeExtra(EXTRA_CAPTURE)
            showTab(0)
            Capture.choose(this)
            return
        }
        val item = i.getLongExtra(EXTRA_ITEM, 0L)
        if (item != 0L) {
            i.removeExtra(EXTRA_ITEM)
            db.healthItem(item)?.let { h -> ensureProfile(h.profileId); showTab(1); push(HealthItemScreen(this, id = item)) }
            return
        }
        val ice = i.getLongExtra(EXTRA_ICE, 0L)
        if (ice != 0L) {
            i.removeExtra(EXTRA_ICE)
            ensureProfile(ice)
            showTab(0)
            push(EmergencyScreen(this))
            return
        }
        val med = i.getLongExtra(EXTRA_MEDICINE, 0L)
        if (med != 0L) {
            i.removeExtra(EXTRA_MEDICINE)
            val m = if (med > 0) db.medicine(med) else null
            ensureProfile(m?.profileId ?: i.getLongExtra(EXTRA_PROFILE, 0L))
            showTab(1)
            val folder = m?.folderId ?: healthRoot()?.id
            if (folder != null) push(FolderScreen(this, folder))
            if (m != null) push(MedicineScreen(this, m.id))
            return
        }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                i.action = null
                if (uri != null) Capture.onShared(this, listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                i.action = null
                if (!uris.isNullOrEmpty()) Capture.onShared(this, uris.toList())
            }
            // "Apri con PENSY" on a PDF, e.g. an attachment in the email app
            Intent.ACTION_VIEW -> {
                val uri = i.data
                i.action = null
                if (uri != null) { showTab(0); Capture.onShared(this, listOf(uri)) }
            }
        }
    }

    @Deprecated("Framework activity results, no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK) { Lock.onResult(resultCode == RESULT_OK); return }
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA || requestCode == REQ_FILE) Capture.cancelled()
            return
        }
        val uri = data?.data
        when (requestCode) {
            REQ_CAMERA -> Capture.onCameraResult(this)
            REQ_FILE -> {
                // One file in `data`, several in the clip (images picked together = pages of one document)
                val clip = data?.clipData
                val uris = ArrayList<Uri>()
                if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it }
                if (uris.isEmpty() && uri != null) uris += uri
                if (uris.isEmpty()) Capture.cancelled() else Capture.onFiles(this, uris)
            }
            REQ_TREE -> if (uri != null) DataFolder.onTreePicked(this, uri)
            REQ_IMPORT_TREE -> if (uri != null) DataFolder.onImportSource(this, uri, isTree = true)
            REQ_IMPORT_FILE -> if (uri != null) DataFolder.onImportSource(this, uri, isTree = false)
            REQ_EXPORT -> if (uri != null) DataFolder.onExportTarget(this, uri)
        }
    }

    /** "Attiva le notifiche": the system question if it was never asked, otherwise PENSY's page in the system settings. */
    fun enableNotifications() {
        val p = getSharedPreferences("pensy_device", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !p.getBoolean("asked_notif", false)) {
            askNotificationPermission()
            return
        }
        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        try { startExternal(i, REQ_SETTINGS) } catch (e: android.content.ActivityNotFoundException) { toast(R.string.notifications_off) }
    }

    private var noticeView: View? = null

    /** A short message at the bottom for a few seconds, with an optional action ("Vedi"). */
    fun notice(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        noticeView?.let { frame.removeView(it) }
        val box = hbox().apply {
            background = rounded(col(R.color.accent), 16)
            setPadding(dp(16), dp(12), dp(6), dp(12))
            elevation = dpf(8f)
            isClickable = true
        }
        box.add(text(message, 14f, col(R.color.on_accent)), w = 0, weight = 1f)
        if (action != null && onAction != null) {
            box.add(text(action, 14f, col(R.color.on_accent_soft), MEDIUM).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = ripple(null, 12)
                setOnClickListener { dismissNotice(box); onAction() }
            }, w = WRAP, start = 6)
        }
        box.setOnClickListener { dismissNotice(box) }
        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        lp.setMargins(dp(16), 0, dp(16), column.paddingBottom + dp(16))
        frame.addView(box, lp)
        noticeView = box
        // Above the tab bar, once the screen it belongs to has been laid out
        frame.post {
            val navH = if (nav.visibility == View.VISIBLE) nav.height else 0
            lp.bottomMargin = column.paddingBottom + navH + dp(16)
            box.layoutParams = lp
        }
        box.postDelayed({ dismissNotice(box) }, 7000)
    }

    private fun dismissNotice(v: View) {
        if (noticeView === v) { frame.removeView(v); noticeView = null }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = getSharedPreferences("pensy_device", MODE_PRIVATE)
        if (p.getBoolean("asked_notif", false)) return
        p.edit().putBoolean("asked_notif", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }
}
