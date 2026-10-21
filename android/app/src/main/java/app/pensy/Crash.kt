package app.pensy

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.LocalDateTime

/*
 * When PENSY closes because of an error, it should say why, in words the
 * user can copy and send. The report is kept in a file on the phone (it
 * never leaves it unless the user shares it) and shown by CrashActivity,
 * which runs in its own process and uses nothing but the framework: it
 * works even when the error is in the rest of the app.
 */
object Crash {

    private const val FILE = "crash.txt"
    private const val REPORT_PROCESS = ":report"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Catch every uncaught error: write the report, show it, end the process. */
    fun install(app: Application) {
        if (isReportProcess(app)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                save(app, error, thread.name)
                show(app)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (t: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Write the report (overwriting the previous one). */
    fun save(ctx: Context, error: Throwable, where: String = "main") {
        try {
            val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
            val text = buildString {
                append("PENSY ").append(version)
                append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
                append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append(LocalDateTime.now().withNano(0)).append(" · ").append(where).append("\n\n")
                append(Log.getStackTraceString(error).ifBlank { error.toString() })
            }
            file(ctx).writeText(text)
        } catch (t: Throwable) {
            // nothing more we can do
        }
    }

    fun read(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /** The report to show at start, if any. One left by an older version is about code that changed: dropped. */
    fun pending(ctx: Context): String? {
        val r = read(ctx) ?: return null
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        if (!r.startsWith("PENSY $version ")) { clear(ctx); return null }
        return r
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (t: Throwable) {}
    }

    /** Open the report screen in its own process. */
    fun show(ctx: Context) {
        ctx.startActivity(Intent(ctx, CrashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** True in the process that only shows the report (nothing else may start there). */
    fun isReportProcess(ctx: Context): Boolean {
        val name: String? = try {
            if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
        } catch (t: Throwable) {
            null
        }
        return name?.endsWith(REPORT_PROCESS) ?: false
    }
}

/** "PENSY si è chiusa per un errore": the report, Copy, Share, Open PENSY again. Framework views only. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = Crash.read(this) ?: getString(R.string.crash_none)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) Color.rgb(17, 17, 20) else Color.rgb(244, 243, 239)
        val ink = if (night) Color.rgb(241, 240, 244) else Color.rgb(24, 23, 28)
        val ink2 = if (night) Color.rgb(169, 167, 177) else Color.rgb(102, 100, 108)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(px(20), px(40), px(20), px(20))
            fitsSystemWindows = true
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(text(getString(R.string.crash_title), 22f, ink, true))
        col.addView(text(getString(R.string.crash_explain), 15f, ink2).apply { setPadding(0, px(8), 0, px(12)) })
        val trace = text(report, 12f, ink).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(trace) }
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
            text = getString(label)
            setOnClickListener { onClick() }
        }
        row.addView(button(R.string.copy) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PENSY", report))
            android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(R.string.crash_share) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
            try { startActivity(Intent.createChooser(send, getString(R.string.crash_share))) } catch (e: Exception) {}
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(12)
        })
        col.addView(button(R.string.crash_reopen) {
            Crash.clear(this)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            Process.killProcess(Process.myPid())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(col)
        col.visibility = View.VISIBLE
    }
}
