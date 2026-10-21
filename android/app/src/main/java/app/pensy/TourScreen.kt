package app.pensy

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.pensy.core.FolderKind

/** First-launch state of the guided tour (a device setting, like the language). */
object Tour {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pensy_device", Context.MODE_PRIVATE)
    fun done(ctx: Context): Boolean = prefs(ctx).getBoolean("tour_done_v3", false)
    fun setDone(ctx: Context) { prefs(ctx).edit().putBoolean("tour_done_v3", true).apply() }

    /** For each default folder kind: the names it may have now, and its name in the new language. */
    fun defaultNames(old: Context, now: Context): Map<String, Pair<Set<String>, String>> {
        val res = mapOf(
            FolderKind.CASA to R.string.folder_casa, FolderKind.AUTO to R.string.folder_auto,
            FolderKind.SALUTE to R.string.folder_salute, FolderKind.ANIMALI to R.string.folder_animali,
            FolderKind.SCUOLA to R.string.folder_scuola, FolderKind.LAVORO to R.string.folder_lavoro,
            FolderKind.TASSE to R.string.folder_tasse, FolderKind.ALTRO to R.string.folder_altro
        )
        return res.entries.associate { (k, r) -> k.code to (setOf(old.getString(r), now.getString(r)) to now.getString(r)) }
    }
}

/**
 * The guided tour: seven short pages, one idea each. Swipe or tap
 * "Avanti"; the first page also lets you pick the language.
 */
class TourScreen(act: MainActivity) : Screen(act) {

    private var page = 0

    private class Slide(val icon: Int, val color: Int, val title: Int, val text: Int)

    private val slides = listOf(
        Slide(R.drawable.ic_launcher_fg, 0xFF4F46E5.toInt(), R.string.tour_1_title, R.string.tour_1_text),
        Slide(R.drawable.ic_camera, 0xFF0E8FA8.toInt(), R.string.tour_2_title, R.string.tour_2_text),
        Slide(R.drawable.ic_folder, 0xFF2563EB.toInt(), R.string.tour_3_title, R.string.tour_3_text),
        Slide(R.drawable.ic_calendar, 0xFFB45309.toInt(), R.string.tour_4_title, R.string.tour_4_text),
        Slide(R.drawable.ic_health, COLOR_VISIT, R.string.tour_5_title, R.string.tour_5_text),
        Slide(R.drawable.ic_chart, 0xFF16A34A.toInt(), R.string.tour_6_title, R.string.tour_6_text),
        Slide(R.drawable.ic_folder, 0xFF475569.toInt(), R.string.tour_7_title, R.string.tour_7_text)
    )

    init {
        Tour.setDone(act)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun build(): View {
        val c = act
        val s = slides[page]
        val last = page == slides.size - 1
        val root = c.vbox().apply { setBackgroundColor(c.col(R.color.bg)) }

        // Top: skip, on the right. No empty spacer view here: outside a scrolling page an
        // empty View with WRAP_CONTENT height takes the whole screen.
        val top = c.hbox().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(c.dp(8), c.dp(8), c.dp(8), 0)
            minimumHeight = c.dp(56)
        }
        if (!last) top.add(c.button(c.getString(R.string.tour_skip), Btn.GHOST) { c.tourFinished() }.apply { background = c.ripple(null, 16) }, w = WRAP)
        root.add(top)

        // Middle: picture, title, text
        val mid = c.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(c.dp(28), 0, c.dp(28), 0) }
        val circle = FrameLayout(c)
        if (page == 0) {
            // The PENSY mark itself
            circle.addView(android.widget.ImageView(c).apply { setImageResource(R.mipmap.ic_launcher) },
                FrameLayout.LayoutParams(c.dp(120), c.dp(120), Gravity.CENTER))
        } else {
            circle.background = c.rounded((s.color and 0x00FFFFFF) or 0x26000000, 60)
            circle.addView(c.icon(s.icon, s.color, 56), FrameLayout.LayoutParams(c.dp(56), c.dp(56), Gravity.CENTER))
        }
        mid.add(circle, w = c.dp(120), h = c.dp(120), gravity = Gravity.CENTER_HORIZONTAL, top = 24)
        mid.add(c.text(c.getString(s.title), 26f, face = BOLD).apply { gravity = Gravity.CENTER }, top = 28)
        mid.add(c.text(c.getString(s.text), 16.5f, c.col(R.color.ink2)).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }, top = 14)

        if (page == 0 || last) mid.add(c.manualLink(), top = 22)
        if (page == 0) {
            mid.add(c.text(c.getString(R.string.tour_lang), 13f, c.col(R.color.ink2), MEDIUM).apply { gravity = Gravity.CENTER }, top = 26)
            val cur = Lang.code(c)
            mid.add(c.chips(listOf("it" to c.getString(R.string.lang_it), "en" to c.getString(R.string.lang_en)),
                if (cur.isEmpty()) c.getString(R.string.lang_code) else cur) { code ->
                if (code != c.getString(R.string.lang_code)) c.changeLanguage(code, back = "tour")
            }, w = WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 8)
        }
        if (last) {
            if (!DataFolder.isConfigured(c)) {
                mid.add(c.button(c.getString(R.string.tour_choose_folder), Btn.SECONDARY, R.drawable.ic_folder) {
                    c.tourFinished()
                    DataFolder.chooseFolder(c)
                }, w = LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 24)
            }
        }
        val scroll = android.widget.ScrollView(c).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val centerBox = c.vbox().apply { gravity = Gravity.CENTER }
        centerBox.add(mid)
        scroll.addView(centerBox, FrameLayout.LayoutParams(MATCH, MATCH))
        root.add(scroll, h = 0, weight = 1f)

        // Bottom: dots and the button
        val bottom = c.hbox().apply { setPadding(c.dp(24), c.dp(12), c.dp(20), c.dp(20)) }
        val dots = c.hbox()
        for (i in slides.indices) {
            val on = i == page
            dots.add(View(c).apply { background = c.rounded(c.col(if (on) R.color.accent else R.color.line), 4) },
                w = c.dp(if (on) 22 else 8), h = c.dp(8), end = 6)
        }
        bottom.add(dots, w = 0, weight = 1f)
        bottom.add(c.button(c.getString(if (last) R.string.tour_start else R.string.tour_next), Btn.PRIMARY,
            if (last) R.drawable.ic_check else 0) { if (last) c.tourFinished() else { page++; refresh() } }, w = WRAP)
        root.add(bottom)

        // Swipe left / right
        val detector = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                if (Math.abs(dx) < c.dp(60) || Math.abs(dx) < Math.abs(e2.y - start.y)) return false
                if (dx < 0 && page < slides.size - 1) { page++; refresh() }
                else if (dx > 0 && page > 0) { page--; refresh() }
                return true
            }
        })
        scroll.setOnTouchListener { _, e -> detector.onTouchEvent(e); false }
        return root
    }
}
