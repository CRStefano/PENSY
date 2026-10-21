package app.pensy

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/*
 * The app's language: the phone's (default), Italian or English.
 *
 * The choice is a device setting (like the encryption key, it is not part
 * of the data that moves to another phone). It is applied by wrapping the
 * activity's context with a configuration that only changes the locale,
 * so dark mode and font size keep following the system.
 */
object Lang {

    val CODES = listOf("", "it", "en")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pensy_device", Context.MODE_PRIVATE)

    fun code(ctx: Context): String = prefs(ctx).getString("lang", "") ?: ""

    fun set(ctx: Context, code: String) {
        prefs(ctx).edit().putString("lang", code).apply()
    }

    /** The phone's own language, whatever the app is showing. */
    private fun systemLocale(): Locale {
        val l: Locale? = try { Resources.getSystem().configuration.locales.get(0) } catch (t: Throwable) { null }
        return l ?: Locale.getDefault()
    }

    /** A context speaking the chosen language. Also sets the default locale used for dates. */
    fun wrap(base: Context): Context {
        val code = code(base)
        if (code.isEmpty()) {
            Locale.setDefault(systemLocale())
            return base
        }
        val loc = Locale.forLanguageTag(code)
        Locale.setDefault(loc)
        val cfg = Configuration()  // a delta: only the locale changes
        cfg.setLocales(LocaleList(loc))
        return base.createConfigurationContext(cfg)
    }
}
