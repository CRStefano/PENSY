package app.pensy

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.pensy.core.DocType

/*
 * A tiny view toolkit. PENSY builds its screens in code with the plain
 * Android framework: no XML layouts, no AndroidX, no Compose. These
 * helpers keep that code short and the look consistent.
 */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.col(id: Int): Int = getColor(id)
fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.dialogTheme(): Int =
    if (isNight()) android.R.style.Theme_DeviceDefault_Dialog_Alert
    else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

fun Context.rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun Context.ripple(content: Drawable?, radiusDp: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(col(R.color.ripple)), content, rounded(Color.WHITE, radiusDp))

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Add a child with its layout params in one call (sizes in px, margins in dp). */
fun <T : View> LinearLayout.add(
    v: T, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, gravity: Int = -1
): T {
    val lp = LinearLayout.LayoutParams(w, h, weight)
    lp.setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
    if (gravity != -1) lp.gravity = gravity
    addView(v, lp)
    return v
}

fun View.pad(h: Int, v: Int): View { setPadding(context.dp(h), context.dp(v), context.dp(h), context.dp(v)); return this }

fun Context.text(
    s: CharSequence, size: Float = 15f, color: Int = col(R.color.ink),
    face: Typeface? = null, lines: Int = 0
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (face != null) typeface = face
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    setLineSpacing(0f, 1.08f)
}

fun Context.section(res: Int): TextView = sectionText(getString(res))

fun Context.sectionText(s: String): TextView = text(s.uppercase(), 12f, col(R.color.ink2), MEDIUM).apply {
    letterSpacing = 0.08f
    setPadding(dp(4), 0, 0, 0)
}

/** A rounded surface with padding. */
fun Context.card(padDp: Int = 18, color: Int = col(R.color.surface)): LinearLayout = vbox().apply {
    background = rounded(color, 22)
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
}

/** A card holding list rows separated by thin lines. */
fun Context.listCard(): LinearLayout = vbox().apply {
    background = rounded(col(R.color.surface), 22)
    clipToOutline = true
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setColor(col(R.color.line)); setSize(1, dp(1)) }
    dividerPadding = dp(16)
}

enum class Btn { PRIMARY, SECONDARY, GHOST, DANGER, ON_ACCENT }

fun Context.button(label: String, kind: Btn = Btn.PRIMARY, icon: Int = 0, onClick: () -> Unit): TextView {
    val bg: Int
    val fg: Int
    var stroke: Int? = null
    when (kind) {
        Btn.PRIMARY -> { bg = col(R.color.accent); fg = col(R.color.on_accent) }
        Btn.SECONDARY -> { bg = col(R.color.accent_soft); fg = col(R.color.accent_ink) }
        Btn.GHOST -> { bg = Color.TRANSPARENT; fg = col(R.color.ink); stroke = col(R.color.line) }
        Btn.DANGER -> { bg = col(R.color.bad_soft); fg = col(R.color.bad) }
        Btn.ON_ACCENT -> { bg = col(R.color.on_accent); fg = col(R.color.accent) }
    }
    return TextView(this).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = MEDIUM
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = ripple(rounded(bg, 16, stroke), 16)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        if (icon != 0) {
            val d = getDrawable(icon)!!.mutate()
            d.setTint(fg)
            d.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
    }
}

fun Context.icon(res: Int, tint: Int = col(R.color.ink), sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.iconButton(res: Int, desc: Int, tint: Int = col(R.color.ink), onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = getString(desc)
        background = ripple(null, 24)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
    }

fun Context.field(
    hint: String, value: String = "",
    type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
    lines: Int = 1, onChange: ((String) -> Unit)? = null
): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(col(R.color.ink))
    setHintTextColor(col(R.color.ink3))
    background = rounded(col(R.color.surface2), 14)
    setPadding(dp(14), dp(13), dp(14), dp(13))
    if (lines > 1) {
        inputType = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setSingleLine(false)
        minLines = lines
        gravity = Gravity.TOP or Gravity.START
    } else {
        inputType = type
        setSingleLine(true)
    }
    if (onChange != null) addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    })
}

/** A label above an input, as a small vertical group. */
fun Context.labeled(label: String, v: View): LinearLayout = vbox().apply {
    add(text(label, 13f, col(R.color.ink2), MEDIUM), start = 4)
    add(v, top = 6)
}

/**
 * Horizontal row of selectable chips. Returns the scroller; the chosen
 * value is reported through onChange and styled in place.
 */
fun <T> Context.chips(options: List<Pair<T, String>>, selected: T, onChange: (T) -> Unit): HorizontalScrollView {
    val row = hbox()
    val views = ArrayList<TextView>()
    fun style(tv: TextView, on: Boolean) {
        tv.setTextColor(col(if (on) R.color.on_accent else R.color.ink))
        tv.background = ripple(rounded(col(if (on) R.color.accent else R.color.surface2), 20), 20)
    }
    options.forEachIndexed { i, (value, label) ->
        val tv = text(label, 14f, face = MEDIUM).apply {
            setPadding(dp(14), dp(9), dp(14), dp(9))
            isClickable = true
        }
        style(tv, value == selected)
        tv.setOnClickListener {
            views.forEachIndexed { j, v -> style(v, j == i) }
            onChange(value)
        }
        views += tv
        row.add(tv, w = WRAP, end = 8)
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
}

/** A switch-like row built from a TextView and a check mark. */
fun Context.toggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    var on = value
    val box = hbox().apply { setPadding(dp(4), dp(8), dp(4), dp(8)); background = ripple(null, 12) }
    val t = text(label, 15f)
    val mark = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = BOLD
    }
    fun paint() {
        mark.text = if (on) "✓" else ""
        mark.setTextColor(col(R.color.on_accent))
        mark.background = rounded(col(if (on) R.color.accent else R.color.surface2), 8, if (on) null else col(R.color.line))
    }
    paint()
    box.add(t, w = 0, weight = 1f)
    box.add(mark, w = dp(26), h = dp(26))
    box.setOnClickListener { on = !on; paint(); onChange(on) }
    return box
}

/** Colour of a document kind, used for the small badges. */
fun typeColor(t: DocType): Int = when (t) {
    DocType.BOLLETTA -> 0xFF2563EB.toInt()
    DocType.PAGOPA -> 0xFF0E8FA8.toInt()
    DocType.BOLLO_AUTO -> 0xFF7C3AED.toInt()
    DocType.MULTA -> 0xFF9333EA.toInt()
    DocType.TICKET -> 0xFFDB2777.toInt()
    DocType.REFERTO -> 0xFFE11D48.toInt()
    DocType.RICETTA -> 0xFFC2410C.toInt()
    DocType.POLIZZA -> 0xFF0369A1.toInt()
    DocType.DOCUMENTO -> 0xFF475569.toInt()
    DocType.GARANZIA -> 0xFF0F766E.toInt()
    DocType.TASSE_UNIVERSITA -> 0xFFB7791F.toInt()
    DocType.RATA -> 0xFFEA580C.toInt()
    DocType.F24 -> 0xFF64748B.toInt()
    DocType.BUSTA_PAGA -> 0xFF16A34A.toInt()
    DocType.ABBONAMENTO -> 0xFF4F46E5.toInt()
    DocType.RICEVUTA -> 0xFF65A30D.toInt()
    DocType.ALTRO -> 0xFF78716C.toInt()
}

fun Context.typeBadge(t: DocType): TextView = TextView(this).apply {
    val c = typeColor(t)
    text = Fmt.typeShort(context, t)
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (text.length > 1) 13f else 15f)
    typeface = BOLD
    setTextColor(c)
    background = rounded((c and 0x00FFFFFF) or 0x24000000, 14)
}

/** A centred overlay layer used for the "working..." indicator. */
fun Context.scrim(): FrameLayout = FrameLayout(this).apply {
    setBackgroundColor(0x66000000)
    isClickable = true
}
