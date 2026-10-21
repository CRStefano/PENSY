package app.pensy

import android.app.TimePickerDialog
import android.view.View
import app.pensy.ocr.TextReader
import java.time.format.DateTimeFormatter

/** People, language, lock, reminders, and the PENSY_DATA folder (save, export, import, send). */
class SettingsScreen(act: MainActivity) : Screen(act) {

    override val tab = 4

    override fun build(): View {
        val c = act
        val db = c.db
        val body = c.vbox()

        // People
        body.add(c.section(R.string.profiles_title), top = 8)
        body.add(c.profilesCard(), top = 10)
        body.add(c.text(c.getString(R.string.profiles_hint), 13f, c.col(R.color.ink2)), top = 8, start = 4)

        // Privacy: lock and photos
        body.add(c.section(R.string.privacy), top = 24)
        val sec = c.card(16)
        sec.add(c.toggleRow(c.getString(R.string.lock_setting), Lock.enabled(c)) { on ->
            if (!on) { Lock.setEnabled(c, false); return@toggleRow }
            if (!Lock.deviceSecure(c)) { c.toast(R.string.lock_no_screen_lock); refresh(); return@toggleRow }
            // Make sure it works before turning it on.
            Lock.authenticate(c, onOk = { Lock.setEnabled(c, true); c.toast(R.string.lock_on); refresh() }, onFail = { refresh() })
        })
        sec.add(c.text(c.getString(R.string.lock_setting_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        if (Lock.enabled(c) && !Lock.deviceSecure(c)) sec.add(c.text(c.getString(R.string.lock_disabled_no_lock), 13f, c.col(R.color.warn)), top = 6, start = 4)
        sec.add(c.toggleRow(c.getString(R.string.keep_photos_setting), Pages.keepByDefault(c)) { Pages.setKeepByDefault(c, it) }, top = 12)
        sec.add(c.text(c.getString(R.string.keep_photos_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        body.add(sec, top = 10)

        // Language
        body.add(c.section(R.string.language), top = 24)
        val lang = c.card(16)
        val labels = mapOf("" to R.string.lang_system, "it" to R.string.lang_it, "en" to R.string.lang_en)
        lang.add(c.chips(Lang.CODES.map { it to c.getString(labels.getValue(it)) }, Lang.code(c)) { code ->
            if (code != Lang.code(c)) c.changeLanguage(code)
        })
        lang.add(c.text(c.getString(R.string.language_note), 13f, c.col(R.color.ink2)), top = 10)
        body.add(lang, top = 10)

        // Language of the documents (text reader)
        val ocr = c.card(16)
        ocr.add(c.labeled(c.getString(R.string.ocr_lang), c.chips(listOf(
            app.pensy.ocr.TextReader.ITA to c.getString(R.string.ocr_lang_it),
            app.pensy.ocr.TextReader.ITA_ENG to c.getString(R.string.ocr_lang_it_en)
        ), app.pensy.ocr.TextReader.lang(c)) { app.pensy.ocr.TextReader.setLang(c, it) }))
        ocr.add(c.text(c.getString(R.string.ocr_lang_hint), 13f, c.col(R.color.ink2)), top = 8)
        body.add(ocr, top = 10)

        // Help
        body.add(c.section(R.string.help_and_tour), top = 24)
        val help = c.listCard()
        help.add(c.healthRow(R.drawable.ic_book, c.col(R.color.accent), c.getString(R.string.manual_title), c.getString(R.string.manual_sub)) {
            c.push(ManualScreen(c))
        })
        help.add(c.healthRow(R.drawable.ic_help, c.col(R.color.accent), c.getString(R.string.help), c.getString(R.string.help_sub)) {
            c.push(HelpScreen(c))
        })
        help.add(c.healthRow(R.drawable.ic_chevron_right, c.col(R.color.accent), c.getString(R.string.tour_again), c.getString(R.string.tour_sub)) {
            c.push(TourScreen(c))
        })
        help.add(c.healthRow(R.drawable.ic_check, c.col(R.color.accent), c.getString(R.string.hints_again), c.getString(R.string.hints_again_sub)) {
            Hints.reset(c)
            c.toast(R.string.hints_again_done)
        })
        body.add(help, top = 10)

        // Reminders
        body.add(c.section(R.string.reminders), top = 24)
        val rem = c.card(16)
        rem.add(c.labeled(c.getString(R.string.default_remind),
            c.chips(Fmt.REMIND_OPTIONS.map { it to c.resources.getQuantityString(R.plurals.days_before, it, it) }, db.remindDays()) {
                db.setSetting("remind_days", it.toString()); c.changed()
            }))
        val (h, m) = Notify.time(c)
        val timeBtn = c.button(c.getString(R.string.remind_time, String.format("%02d:%02d", h, m)), Btn.GHOST, R.drawable.ic_notify) {}
        timeBtn.setOnClickListener {
            TimePickerDialog(c, c.dialogTheme(), { _, hh, mm ->
                db.setSetting("remind_time", String.format("%02d:%02d", hh, mm))
                Notify.schedule(c)
                c.changed()
                refresh()
            }, h, m, true).show()
        }
        rem.add(timeBtn, top = 14)
        rem.add(c.toggleRow(c.getString(R.string.dose_alarm_setting), Doses.alarmSound(c)) { Doses.setAlarmSound(c, it) }, top = 12)
        rem.add(c.text(c.getString(R.string.dose_alarm_hint), 13f, c.col(R.color.ink2)), top = 2, start = 4)
        if (!Notify.canNotify(c)) rem.add(c.text(c.getString(R.string.notifications_off), 13f, c.col(R.color.warn)), top = 8)
        body.add(rem, top = 10)

        // PENSY_DATA
        body.add(c.section(R.string.data_folder), top = 24)
        val df = c.card(16)
        df.add(c.text(c.getString(R.string.data_folder_explain), 14f, c.col(R.color.ink2)))
        val label = DataFolder.folderLabel(c)
        df.add(c.stat(c.getString(R.string.folder), label ?: c.getString(R.string.folder_none), c.col(R.color.ink2), c.col(R.color.ink)), top = 14)
        DataFolder.lastSave(c)?.let {
            df.add(c.stat(c.getString(R.string.last_save), it.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), c.col(R.color.ink2), c.col(R.color.ink)), top = 10)
        }
        DataFolder.lastError(c)?.let { df.add(c.text(c.getString(R.string.save_error, it), 13f, c.col(R.color.bad)), top = 8) }
        df.add(c.button(c.getString(if (label == null) R.string.folder_choose else R.string.folder_change), Btn.PRIMARY, R.drawable.ic_folder) {
            DataFolder.chooseFolder(c)
        }, top = 16)
        if (DataFolder.isConfigured(c)) {
            df.add(c.button(c.getString(R.string.save_now), Btn.SECONDARY) {
                DataFolder.saveNow(c); c.toast(R.string.saved_folder)
                c.window.decorView.postDelayed({ refresh() }, 800)
            }, top = 10)
        }
        df.add(c.button(c.getString(R.string.export_file), Btn.GHOST, R.drawable.ic_doc) { DataFolder.exportFile(c) }, top = 10)
        df.add(c.button(c.getString(R.string.send_phone), Btn.GHOST, R.drawable.ic_share) { DataFolder.sendToPhone(c) }, top = 10)
        df.add(c.button(c.getString(R.string.import_data), Btn.GHOST, R.drawable.ic_folder) { DataFolder.chooseImport(c) }, top = 10)
        if (DataFolder.isConfigured(c)) {
            df.add(c.button(c.getString(R.string.copies_restore), Btn.GHOST, R.drawable.ic_time) { DataFolder.chooseCopy(c) }, top = 10)
        }
        if (DataFolder.hasKey(c)) {
            df.add(c.button(c.getString(R.string.change_password), Btn.GHOST) {
                DataFolder.setPassword(c, R.string.password_change_title) { DataFolder.saveNow(c); c.toast(R.string.password_changed) }
            }, top = 10)
        }
        df.add(c.text(c.getString(R.string.password_warning), 13f, c.col(R.color.ink2)), top = 14)
        body.add(df, top = 10)

        // About
        body.add(c.section(R.string.about), top = 24)
        val ab = c.card(16)
        ab.add(c.text(c.getString(R.string.about_offline), 14f, c.col(R.color.ink2)))
        ab.add(c.text(c.getString(if (TextReader.AVAILABLE) R.string.ocr_on else R.string.ocr_off), 14f, c.col(R.color.ink2)), top = 10)
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
        ab.add(c.text(c.getString(R.string.version, version), 13f, c.col(R.color.ink3)), top = 10)
        body.add(ab, top = 10)

        val actions = listOf(Triple(R.drawable.ic_book, R.string.manual_title) { c.push(ManualScreen(c)) })
        return page(c.getString(R.string.tab_settings), actions = actions, body = body)
    }
}
