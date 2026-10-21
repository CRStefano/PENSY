package app.pensy

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import app.pensy.core.Profile

/*
 * Family profiles: the owner (always there, id 1) and the people whose
 * papers they look after, a child or a parent. Each profile has its own
 * folders, documents, deadlines and health record; switching shows
 * everything of that person. A child's expenses can count in the
 * parent's monthly balance.
 */

private val PROFILE_COLORS = intArrayOf(
    0xFF4F46E5.toInt(), 0xFF0E8FA8.toInt(), 0xFFDB2777.toInt(), 0xFF16A34A.toInt(),
    0xFFEA580C.toInt(), 0xFF7C3AED.toInt(), 0xFFB7791F.toInt(), 0xFF0369A1.toInt()
)

fun profileColor(p: Profile): Int = PROFILE_COLORS[((p.id - 1).coerceAtLeast(0) % PROFILE_COLORS.size).toInt()]

/** The name to show: the profile's, or "Io" for an owner without a name. */
fun MainActivity.profileLabel(p: Profile): String =
    p.name.ifBlank { if (p.id == 1L) getString(R.string.profile_me) else getString(R.string.profile_unnamed) }

fun MainActivity.kindLabel(kind: String): String = getString(
    when (kind) {
        "bambino" -> R.string.kind_child
        "assistito" -> R.string.kind_cared
        else -> R.string.kind_adult
    }
)

/** A round badge with the initial on the profile's colour. */
fun MainActivity.avatar(p: Profile, sizeDp: Int): TextView = TextView(this).apply {
    val c = profileColor(p)
    text = profileLabel(p).take(1).uppercase()
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
    textSize = sizeDp * 0.42f
    typeface = BOLD
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
}

/** "Whose papers?": switch profile, add one, manage them. */
fun MainActivity.profileSheet() {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = vbox().apply {
        background = GradientDrawable().apply {
            setColor(col(R.color.surface))
            val r = dpf(26f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(dp(12), dp(10), dp(12), dp(20))
    }
    box.add(View(this).apply { background = rounded(col(R.color.line), 3) }, w = dp(40), h = dp(5), gravity = Gravity.CENTER_HORIZONTAL, bottom = 10)
    box.add(text(getString(R.string.profiles_title), 14f, col(R.color.ink2), MEDIUM), start = 12, top = 4, bottom = 8)
    val current = db.profile
    for (p in db.profiles()) {
        val on = p.id == current
        val row = hbox().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ripple(if (on) rounded(col(R.color.accent_soft), 16) else null, 16)
            setOnClickListener { d.dismiss(); switchProfile(p.id) }
        }
        row.add(avatar(p, 40), w = dp(40), h = dp(40))
        val col2 = vbox()
        col2.add(text(profileLabel(p), 16f, face = MEDIUM, lines = 1))
        col2.add(text(kindLabel(p.kind), 13f, col(R.color.ink2), lines = 1), top = 1)
        row.add(col2, w = 0, weight = 1f, start = 14)
        if (on) row.add(icon(R.drawable.ic_check, col(R.color.accent_ink), 22), w = dp(22), h = dp(22))
        box.add(row)
    }
    val add = hbox().apply {
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = ripple(null, 16)
        setOnClickListener { d.dismiss(); push(ProfileScreen(this@profileSheet, 0)) }
    }
    add.add(icon(R.drawable.ic_add, col(R.color.accent_ink)), w = dp(24), h = dp(24))
    add.add(text(getString(R.string.profile_add), 16f, col(R.color.accent_ink), MEDIUM), w = 0, weight = 1f, start = 16)
    box.add(add, top = 4)
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

/** New profile (id 0) or edit one: name, who it is, whose balance its expenses count in. */
class ProfileScreen(act: MainActivity, private val id: Long) : Screen(act) {

    private val original: Profile? = if (id != 0L) act.db.profileOf(id) else null
    private var name = original?.name ?: ""
    private var kind = original?.kind ?: "bambino"
    private var budgetOf: Long? = if (original != null) original.budgetOf else defaultBudget("bambino")

    private fun defaultBudget(k: String): Long? {
        if (k != "bambino") return null
        val me = act.db.current()
        return if (me.budgetOf == null) me.id else me.budgetOf
    }

    override fun build(): View {
        val c = act
        val body = c.vbox()
        val isOwner = id == 1L

        body.add(c.labeled(c.getString(R.string.profile_name), c.field(c.getString(R.string.profile_name_hint), name) { name = it }), top = 8)

        if (!isOwner) {
            body.add(c.labeled(c.getString(R.string.profile_kind), c.chips(
                listOf("bambino", "assistito", "adulto").map { it to c.kindLabel(it) }, kind
            ) { k ->
                val wasDefault = budgetOf == defaultBudget(kind)
                kind = k
                if (original == null && wasDefault) { budgetOf = defaultBudget(k); refresh() }
            }), top = 18)
            if (original == null) body.add(c.text(c.getString(R.string.profile_folders_hint), 13f, c.col(R.color.ink2)), top = 6, start = 4)

            // Whose balance: its own, or someone who keeps their own balance.
            val others = c.db.profiles().filter { it.id != id && it.budgetOf == null }
            val options = listOf<Pair<Long?, String>>(null to c.getString(R.string.profile_budget_own)) +
                others.map { it.id to c.profileLabel(it) }
            body.add(c.labeled(c.getString(R.string.profile_budget), c.chips(options, budgetOf) { budgetOf = it }), top = 18)
            body.add(c.text(c.getString(R.string.profile_budget_hint), 13f, c.col(R.color.ink2)), top = 6, start = 4)
        } else {
            body.add(c.text(c.getString(R.string.profile_owner_hint), 13f, c.col(R.color.ink2)), top = 8, start = 4)
        }

        val buttons = c.hbox()
        buttons.add(c.button(c.getString(R.string.cancel), Btn.GHOST) { c.pop() }, w = 0, weight = 1f)
        buttons.add(c.button(c.getString(R.string.save), Btn.PRIMARY, R.drawable.ic_check) { save() }, w = 0, weight = 1.4f, start = 12)
        body.add(buttons, top = 26)

        if (original != null && !isOwner) {
            body.add(c.button(c.getString(R.string.profile_delete), Btn.DANGER, R.drawable.ic_delete) {
                Dialogs.confirm(c, R.string.profile_delete_title, c.getString(R.string.profile_delete_msg, c.profileLabel(original)), R.string.delete) {
                    c.db.deleteProfile(id)
                    c.changed()
                    c.switchProfile(c.db.profile)
                }
            }, top = 14)
        }
        return page(c.getString(if (original == null) R.string.profile_new else R.string.profile_edit), back = true, body = body)
    }

    private fun save() {
        val c = act
        if (name.isBlank() && id != 1L) { c.toast(R.string.profile_need_name); return }
        val p = Profile(id = id, name = name, kind = if (id == 1L) "adulto" else kind, budgetOf = if (id == 1L) null else budgetOf)
        val saved = c.db.saveProfile(p)
        // Profiles that counted in this one's balance can't any more if this one now counts in another's.
        if (p.budgetOf != null) for (o in c.db.profiles()) if (o.budgetOf == saved) c.db.saveProfile(o.copy(budgetOf = null))
        c.changed()
        if (original == null) { c.toast(c.getString(R.string.profile_created, name.trim())); c.switchProfile(saved) }
        else c.pop()
    }
}

/** The list of people in Settings. */
fun MainActivity.profilesCard(): LinearLayout {
    val list = listCard()
    val all = db.profiles()
    for (p in all) {
        val row = hbox().apply {
            setPadding(dp(14), dp(12), dp(16), dp(12))
            background = ripple(null, 0)
            setOnClickListener { push(ProfileScreen(this@profilesCard, p.id)) }
        }
        row.add(avatar(p, 40), w = dp(40), h = dp(40))
        val mid = vbox()
        mid.add(text(profileLabel(p), 16f, face = MEDIUM, lines = 1))
        val sub = ArrayList<String>()
        sub += kindLabel(p.kind)
        p.budgetOf?.let { b -> all.firstOrNull { it.id == b }?.let { sub += getString(R.string.profile_in_budget_of, profileLabel(it)) } }
        if (p.id == db.profile) sub += getString(R.string.profile_in_use)
        mid.add(text(sub.joinToString(" · "), 13f, col(R.color.ink2), lines = 2), top = 1)
        row.add(mid, w = 0, weight = 1f, start = 14)
        row.add(icon(R.drawable.ic_chevron_right, col(R.color.ink3), 20), w = dp(20), h = dp(20), start = 8)
        list.add(row)
    }
    val add = hbox().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = ripple(null, 0)
        setOnClickListener { push(ProfileScreen(this@profilesCard, 0)) }
    }
    add.add(icon(R.drawable.ic_add, col(R.color.accent_ink), 22), w = dp(22), h = dp(22))
    add.add(text(getString(R.string.profile_add), 15f, col(R.color.accent_ink), MEDIUM), w = 0, weight = 1f, start = 14)
    list.add(add)
    return list
}
