package app.pensy

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import app.pensy.core.Code

/** Small dialogs built with the framework: sheets, confirmations, passwords. */
object Dialogs {

    /** A sheet sliding from the bottom with a list of actions. */
    fun sheet(act: MainActivity, title: String?, items: List<Triple<Int, String, () -> Unit>>) =
        sections(act, title, listOf(null to items))

    /** The same sheet with the actions in groups, each under a small heading ("Da un documento"). */
    fun sections(act: MainActivity, title: String?, groups: List<Pair<String?, List<Triple<Int, String, () -> Unit>>>>) {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = act.vbox().apply {
            background = GradientDrawable().apply {
                setColor(act.col(R.color.surface))
                val r = act.dpf(26f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(20))
        }
        box.add(android.view.View(act).apply { background = act.rounded(act.col(R.color.line), 3) },
            w = act.dp(40), h = act.dp(5), gravity = Gravity.CENTER_HORIZONTAL, bottom = 10)
        if (title != null) box.add(act.text(title, 14f, act.col(R.color.ink2), MEDIUM), start = 12, top = 4, bottom = 8)
        val list = act.vbox()
        groups.forEachIndexed { g, (heading, items) ->
            if (heading != null) {
                if (g > 0) list.add(android.view.View(act).apply { setBackgroundColor(act.col(R.color.line)) }, h = 1, start = 12, end = 12, top = 6)
                list.add(act.text(heading, 13f, act.col(R.color.accent_ink), MEDIUM), start = 12, top = if (g > 0) 12 else 2, bottom = 2)
            }
            for ((icon, label, fn) in items) {
                val row = act.hbox().apply {
                    setPadding(act.dp(12), act.dp(14), act.dp(12), act.dp(14))
                    background = act.ripple(null, 16)
                    setOnClickListener { d.dismiss(); fn() }
                }
                val ic = act.icon(icon, act.col(R.color.accent_ink))
                row.add(ic, w = act.dp(24), h = act.dp(24))
                row.add(act.text(label, 16f), w = 0, weight = 1f, start = 16)
                list.add(row)
            }
        }
        // A long sheet on a small phone scrolls instead of running off the screen.
        val scroll = android.widget.ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(list)
        box.add(scroll)
        // Keep the last row above the gesture/navigation bar.
        val basePad = box.paddingBottom
        box.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + insets.systemWindowInsetBottom)
            insets
        }
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    fun confirm(act: MainActivity, title: Int, message: String, yes: Int, onYes: () -> Unit) {
        AlertDialog.Builder(act, act.dialogTheme())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun info(act: MainActivity, title: Int, message: String) {
        AlertDialog.Builder(act, act.dialogTheme())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun passwordField(act: MainActivity, hint: Int): EditText =
        act.field(act.getString(hint), type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

    /** Ask for a new password twice. done() gets it as a CharArray. */
    fun newPassword(act: MainActivity, title: Int, done: (CharArray) -> Unit) {
        val box = act.vbox().apply { setPadding(act.dp(24), act.dp(8), act.dp(24), 0) }
        box.add(act.text(act.getString(R.string.password_explain), 14f, act.col(R.color.ink2)))
        val a = box.add(passwordField(act, R.string.password_hint), top = 14)
        val b = box.add(passwordField(act, R.string.password_repeat), top = 10)
        val dlg = AlertDialog.Builder(act, act.dialogTheme())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = a.text.toString()
                val p2 = b.text.toString()
                when {
                    p1.length < 6 -> act.toast(R.string.password_short)
                    p1 != p2 -> act.toast(R.string.password_mismatch)
                    else -> { dlg.dismiss(); done(p1.toCharArray()) }
                }
            }
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    /** Ask for an existing password. */
    fun password(act: MainActivity, title: Int, message: String?, done: (CharArray) -> Unit) {
        val box = act.vbox().apply { setPadding(act.dp(24), act.dp(8), act.dp(24), 0) }
        if (message != null) box.add(act.text(message, 14f, act.col(R.color.ink2)))
        val a = box.add(passwordField(act, R.string.password_hint), top = 14)
        val dlg = AlertDialog.Builder(act, act.dialogTheme())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> done(a.text.toString().toCharArray()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    /** A code shown big, on white, with the screen at full brightness: ready to be scanned. */
    fun bigCode(act: MainActivity, code: Code) {
        val d = Dialog(act, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val box = act.vbox().apply {
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(act.dp(24), act.dp(24), act.dp(24), act.dp(24))
            setOnClickListener { d.dismiss() }
        }
        val width = act.resources.displayMetrics.widthPixels - act.dp(48)
        val bmp = Codes.render(code, width)
        if (bmp != null) {
            val iv = ImageView(act).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            box.add(iv, w = LinearLayout.LayoutParams.MATCH_PARENT)
        }
        box.add(act.text(code.content, 13f, Color.DKGRAY).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, top = 20)
        box.add(act.text(act.getString(R.string.tap_to_close), 13f, Color.GRAY).apply { gravity = Gravity.CENTER }, top = 24)
        d.setContentView(box)
        d.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = 1f
            w.attributes = lp
        }
        d.show()
    }

    /** A short text shown very big, on white, at full brightness (a prescription number at the pharmacy). */
    fun bigText(act: MainActivity, title: String, text: String) {
        val d = Dialog(act, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val box = act.vbox().apply {
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(act.dp(24), act.dp(24), act.dp(24), act.dp(24))
            setOnClickListener { d.dismiss() }
        }
        box.add(act.text(title, 18f, Color.DKGRAY, MEDIUM).apply { gravity = Gravity.CENTER })
        box.add(act.text(text, 34f, Color.BLACK, android.graphics.Typeface.MONOSPACE).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, top = 18)
        box.add(act.text(act.getString(R.string.tap_to_close), 13f, Color.GRAY).apply { gravity = Gravity.CENTER }, top = 28)
        d.setContentView(box)
        d.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = 1f
            w.attributes = lp
        }
        d.show()
    }

    /** One line of text, e.g. a folder name. */
    fun input(act: MainActivity, title: String, value: String, hint: Int, done: (String) -> Unit) {
        val box = act.vbox().apply { setPadding(act.dp(24), act.dp(8), act.dp(24), 0) }
        val f = box.add(act.field(act.getString(hint), value))
        val dlg = AlertDialog.Builder(act, act.dialogTheme())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        f.requestFocus()
        f.setSelection(f.text.length)
    }

    /** Several lines of free text: the summary of a folder. */
    fun note(act: MainActivity, title: String, value: String, done: (String) -> Unit) {
        val box = act.vbox().apply { setPadding(act.dp(24), act.dp(8), act.dp(24), 0) }
        val f = box.add(act.field(act.getString(R.string.folder_summary_hint), value, lines = 8))
        val dlg = AlertDialog.Builder(act, act.dialogTheme())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> done(f.text.toString().trimEnd()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    /**
     * Pick a folder from the whole tree. The last row creates a new
     * sub-folder inside the highlighted one (or at the top level).
     */
    fun pickFolder(act: MainActivity, current: Long?, done: (Long) -> Unit) {
        val folders = act.db.folders()
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val list = act.vbox()
        fun addTree(parent: Long?, depth: Int) {
            for (f in folders.filter { it.parentId == parent }) {
                val on = f.id == current
                val row = act.hbox().apply {
                    setPadding(act.dp(16 + depth * 24), act.dp(11), act.dp(16), act.dp(11))
                    background = act.ripple(if (on) act.rounded(act.col(R.color.accent_soft), 14) else null, 14)
                    setOnClickListener { d.dismiss(); done(f.id) }
                }
                row.add(act.folderBadge(f, folders, 34), w = act.dp(34), h = act.dp(34))
                row.add(act.text(f.name, 16f, if (on) act.col(R.color.accent_ink) else act.col(R.color.ink), if (on) MEDIUM else null),
                    w = 0, weight = 1f, start = 12)
                list.add(row)
                addTree(f.id, depth + 1)
            }
        }
        addTree(null, 0)
        val newRow = act.hbox().apply {
            setPadding(act.dp(16), act.dp(13), act.dp(16), act.dp(13))
            background = act.ripple(null, 14)
            setOnClickListener {
                d.dismiss()
                val parent = act.db.folder(current)
                val title = if (parent != null) act.getString(R.string.subfolder_new_in, parent.name) else act.getString(R.string.folder_new)
                input(act, title, "", R.string.folder_name_hint) { name ->
                    val id = act.db.addFolder(parent?.id, name)
                    act.changed()
                    done(id)
                }
            }
        }
        newRow.add(act.icon(R.drawable.ic_new_folder, act.col(R.color.accent_ink)), w = act.dp(24), h = act.dp(24))
        newRow.add(act.text(act.getString(R.string.subfolder_here), 16f, act.col(R.color.accent_ink), MEDIUM), w = 0, weight = 1f, start = 16)
        val box = act.vbox().apply {
            background = act.rounded(act.col(R.color.surface), 24)
            setPadding(act.dp(8), act.dp(16), act.dp(8), act.dp(8))
        }
        box.add(act.text(act.getString(R.string.folder_choose_title), 18f, face = BOLD), start = 16, bottom = 8)
        val sv = android.widget.ScrollView(act)
        sv.addView(list)
        box.add(sv, h = minOf(act.dp(420), act.resources.displayMetrics.heightPixels / 2, folders.size * act.dp(57) + act.dp(8)))
        box.add(newRow, top = 4)
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((act.resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }
}
