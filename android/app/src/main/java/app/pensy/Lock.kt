package app.pensy

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/*
 * "Blocca PENSY": fingerprint, face or the phone's own PIN, at start and
 * when coming back after a minute away. PENSY keeps no PIN of its own:
 * it asks Android, so the lock is exactly as strong as the phone's.
 *
 *  - Android 11+: BiometricPrompt, biometrics or the device credential
 *  - Android 10:  BiometricPrompt with the device credential allowed
 *  - Android 8-9: the system "confirm your PIN" screen
 *
 * If the phone has no screen lock any more, PENSY does not lock (it
 * could never be opened again); Settings says so.
 */
object Lock {

    private const val PREFS = "pensy_device"
    const val TIMEOUT_MS = 60_000L
    /** Away in the camera or a picker: lock only after a long time. */
    const val EXTERNAL_MS = 10 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Android 8-9: what to do when the system PIN screen answers. */
    private var waiting: Pair<() -> Unit, () -> Unit>? = null

    /** The system PIN screen answered (MainActivity.onActivityResult). */
    fun onResult(ok: Boolean) {
        val w = waiting
        waiting = null
        if (ok) w?.first?.invoke() else w?.second?.invoke()
    }

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("lock", false)

    fun setEnabled(act: MainActivity, on: Boolean) {
        prefs(act).edit().putBoolean("lock", on).apply()
        applyRecents(act)
    }

    /** The phone has a PIN, pattern or password. */
    fun deviceSecure(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: false

    /** Lock now? Only when enabled and possible. */
    fun active(ctx: Context): Boolean = enabled(ctx) && deviceSecure(ctx)

    /** Hide PENSY's content in the recent apps while the lock is on (Android 13+; screenshots stay allowed). */
    fun applyRecents(act: MainActivity) {
        if (Build.VERSION.SDK_INT >= 33) act.setRecentsScreenshotEnabled(!enabled(act))
    }

    @Suppress("DEPRECATION")
    fun authenticate(act: MainActivity, onOk: () -> Unit, onFail: () -> Unit) {
        if (!deviceSecure(act)) { onOk(); return }
        val title = act.getString(R.string.lock_prompt)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val b = BiometricPrompt.Builder(act).setTitle(title).setSubtitle(act.getString(R.string.lock_prompt_sub))
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    b.setDeviceCredentialAllowed(true)
                }
                b.build().authenticate(CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFail()
                })
            } else {
                val km = act.getSystemService(KeyguardManager::class.java)
                val i = km?.createConfirmDeviceCredentialIntent(title, act.getString(R.string.lock_prompt_sub))
                if (i == null) onOk() else { waiting = onOk to onFail; act.startExternal(i, MainActivity.REQ_UNLOCK) }
            }
        } catch (t: Throwable) {
            onFail()
        }
    }

    /** What covers PENSY while it is locked. */
    fun cover(act: MainActivity, onUnlock: () -> Unit): View {
        val box = act.vbox().apply {
            setBackgroundColor(act.col(R.color.bg))
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(act.dp(32), act.dp(32), act.dp(32), act.dp(32))
        }
        box.add(ImageView(act).apply { setImageResource(R.mipmap.ic_launcher) }, w = act.dp(96), h = act.dp(96), gravity = Gravity.CENTER_HORIZONTAL)
        box.add(act.text(act.getString(R.string.lock_locked), 22f, face = BOLD).apply { gravity = Gravity.CENTER }, top = 20)
        box.add(act.text(act.getString(R.string.lock_locked_sub), 15f, act.col(R.color.ink2)).apply { gravity = Gravity.CENTER }, top = 8)
        box.add(act.button(act.getString(R.string.lock_unlock), Btn.PRIMARY, R.drawable.ic_lock) { onUnlock() },
            w = LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 28)
        return box
    }
}
