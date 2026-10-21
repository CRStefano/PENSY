package app.pensy

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.pensy.core.Folder

/** "Fascicoli": every drawer at a glance, plus the latest documents. */
class FoldersScreen(act: MainActivity) : Screen(act) {

    override val tab = 1

    override fun build(): View {
        val c = act
        val folders = c.db.folders()
        val docs = c.db.docs()
        val deadlines = c.db.deadlines()
        val body = c.vbox()

        body.add(c.searchPill(), top = 4)
        c.hintCard("folders", R.string.hint_folders)?.let { body.add(it, top = 12) }

        // Two tiles per row.
        val tops = folders.filter { it.parentId == null }
        val tiles: List<View> = tops.map { tile(it, folders, docs, deadlines) } + newTile()
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = c.hbox().apply { gravity = Gravity.TOP }
                body.add(row!!, top = if (i == 0) 16 else 12)
            }
            row!!.add(t, w = 0, weight = 1f, start = if (i % 2 == 1) 12 else 0)
        }
        if (tiles.size % 2 == 1) row!!.add(View(c), w = 0, h = 1, weight = 1f, start = 12)

        val recent = docs.take(6)
        if (recent.isNotEmpty()) {
            body.add(c.section(R.string.recent_docs), top = 26)
            val list = c.listCard()
            for (d in recent) list.add(c.docRow(d, folders, showFolder = true))
            body.add(list, top = 10)
        }
        body.add(View(c), h = c.dp(80))
        return withFab(page(c.getString(R.string.tab_folders), body = body)) { c.addSheet() }
    }

    private fun tile(f: Folder, folders: List<Folder>, docs: List<Db.DocRow>, deadlines: List<app.pensy.core.Deadline>): View {
        val c = act
        val box = c.card(16).apply {
            background = c.ripple(c.rounded(c.col(R.color.surface), 22), 22)
            minimumHeight = c.dp(128)
            setOnClickListener { c.push(FolderScreen(c, f.id)) }
        }
        box.add(c.folderBadge(f, folders, 44), w = c.dp(44), h = c.dp(44))
        box.add(c.text(f.name, 16f, face = MEDIUM, lines = 2), top = 12)
        box.add(c.text(c.folderCounts(f, folders, docs, deadlines), 12.5f, c.col(R.color.ink2), lines = 2), top = 2)
        return box
    }

    private fun newTile(): View {
        val c = act
        val box = c.card(16).apply {
            background = c.ripple(c.rounded(c.col(R.color.bg), 22, c.col(R.color.line)), 22)
            minimumHeight = c.dp(128)
            gravity = Gravity.CENTER
            setOnClickListener {
                Dialogs.input(c, c.getString(R.string.folder_new), "", R.string.folder_name_hint) { name ->
                    val id = c.db.addFolder(null, name)
                    c.changed()
                    c.push(FolderScreen(c, id))
                }
            }
        }
        box.add(c.icon(R.drawable.ic_new_folder, c.col(R.color.accent_ink), 28), w = c.dp(28), h = c.dp(28), gravity = Gravity.CENTER_HORIZONTAL)
        box.add(c.text(c.getString(R.string.folder_new), 14f, c.col(R.color.accent_ink), MEDIUM).apply { gravity = Gravity.CENTER }, top = 8)
        return box
    }
}
