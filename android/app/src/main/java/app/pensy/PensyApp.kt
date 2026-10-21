package app.pensy

import android.app.Application
import android.content.Context

class PensyApp : Application() {
    lateinit var db: Db
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First thing: if anything goes wrong from here on, PENSY says what.
        Crash.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // The process that only shows an error report starts nothing else.
        if (Crash.isReportProcess(this)) return
        try {
            // The database names the default folders when it is created: in the chosen language.
            db = Db(Lang.wrap(this))
            Notify.createChannel(this)
            Notify.schedule(this)
            Doses.schedule(this)
            Emergency.post(this)
        } catch (t: Throwable) {
            // Keep the error: MainActivity shows the report instead of closing.
            Crash.save(this, t, "PensyApp")
        }
    }
}

val Context.app: PensyApp get() = applicationContext as PensyApp
val Context.db: Db get() = app.db
