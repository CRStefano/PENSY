package app.pensy

import android.view.View
import app.pensy.core.Sorter
import java.time.LocalDate

/**
 * One drawer: its summary (what you always want at hand, like
 * conditions or a plate number), its sub-drawers, what is to be done
 * and its documents.
 */
class FolderScreen(act: MainActivity, private val id: Long) : Screen(act) {

    /** Show every past visit instead of the last few. */
    private var expanded = false

    override fun build(): View {
        val c = act
        val folders = c.db.folders()
        val f = folders.firstOrNull { it.id == id } ?: return page(c.getString(R.string.not_found), back = true, body = c.vbox())
        val today = LocalDate.now()
        val docs = c.db.docs()
        val deadlines = c.db.deadlines()
        val subtree = Sorter.subtree(folders, id)
        val body = c.vbox()

        // Where we are
        val path = Sorter.path(folders, id)
        if (path.size > 1) {
            body.add(c.text(path.dropLast(1).joinToString(" › ") { it.name }, 13f, c.col(R.color.ink2)), start = 4)
        }

        // Health record: conditions, medicines, visits (Salute, and pets in Animali)
        val health = hasHealth(f, folders)
        if (health && f.kind == app.pensy.core.FolderKind.SALUTE.code) c.hintCard("health", R.string.hint_health)?.let { body.add(it, top = 4, bottom = 12) }
        else if (f.parentId == null && !health) c.hintCard("folder", R.string.hint_folder)?.let { body.add(it, top = 4, bottom = 12) }
        if (health) body.add(healthBlock(f, folders, expanded) { expanded = true; refresh() })

        // A car or a pet: their yearly dates in one go
        c.setupCard(f, folders)?.let { body.add(it, top = if (health) 16 else 8) }

        // Summary note
        if (health) body.add(c.section(R.string.folder_summary), top = 24)
        val note = c.card(16)
        val head = c.hbox()
        head.add(c.text(c.getString(R.string.folder_summary), 13f, c.col(R.color.ink2), MEDIUM), w = 0, weight = 1f)
        head.add(c.icon(R.drawable.ic_edit, c.col(R.color.ink3), 18), w = c.dp(18), h = c.dp(18))
        note.add(head)
        if (f.note.isBlank()) note.add(c.text(c.getString(R.string.folder_summary_hint), 14f, c.col(R.color.ink3)), top = 6)
        else note.add(c.text(f.note, 15f), top = 6)
        note.background = c.ripple(c.rounded(c.col(R.color.surface), 22), 22)
        note.setOnClickListener {
            Dialogs.note(c, c.getString(R.string.note_edit_title, f.name), f.note) { txt ->
                c.db.setFolderNote(id, txt); c.changed(); refresh()
            }
        }
        body.add(note, top = 8)

        // Sub-folders
        val subs = folders.filter { it.parentId == id }
        body.add(c.section(R.string.folder_subfolders), top = 24)
        val subList = c.listCard()
        for (s in subs) subList.add(c.folderRow(s, folders, c.folderCounts(s, folders, docs, deadlines)) { c.push(FolderScreen(c, s.id)) })
        val newSub = c.hbox().apply {
            setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
            background = c.ripple(null, 0)
            setOnClickListener { newSubfolder(f.name) }
        }
        newSub.add(c.icon(R.drawable.ic_new_folder, c.col(R.color.accent_ink), 22), w = c.dp(22), h = c.dp(22))
        newSub.add(c.text(c.getString(R.string.subfolder_new), 15f, c.col(R.color.accent_ink), MEDIUM), w = 0, weight = 1f, start = 14)
        subList.add(newSub)
        body.add(subList, top = 10)

        // To do: unpaid deadlines here and below
        val todo = deadlines.filter { !it.paid && it.folderId in subtree }.sortedBy { it.noDue }
        if (todo.isNotEmpty()) {
            body.add(c.section(R.string.folder_todo), top = 24)
            val list = c.listCard()
            for (d in todo) list.add(c.deadlineRow(d, today))
            body.add(list, top = 10)
        }

        // Documents directly in this folder
        val mine = docs.filter { it.folderId == id }
        body.add(c.section(R.string.folder_docs), top = 24)
        if (mine.isEmpty()) {
            body.add(c.text(c.getString(R.string.folder_empty), 14f, c.col(R.color.ink2)), top = 8, start = 4)
        } else {
            val list = c.listCard()
            for (d in mine) list.add(c.docRow(d, folders, showFolder = false))
            body.add(list, top = 10)
        }
        body.add(View(c), h = c.dp(80))

        val actions = listOf(
            Triple(R.drawable.ic_doc, R.string.folder_pdf) { c.exportFolderPdf(id) },
            Triple(R.drawable.ic_new_folder, R.string.subfolder_new) { newSubfolder(f.name) },
            Triple(R.drawable.ic_edit, R.string.folder_rename) {
                Dialogs.input(c, c.getString(R.string.folder_rename), f.name, R.string.folder_name_hint) { name ->
                    c.db.renameFolder(id, name); c.changed(); refresh()
                }
            },
            Triple(R.drawable.ic_delete, R.string.delete) {
                val dest = f.parentId?.let { p -> folders.firstOrNull { it.id == p }?.name }
                    ?: folders.firstOrNull { it.parentId == null && it.kind == "altro" && it.id != id }?.name ?: "—"
                Dialogs.confirm(c, R.string.folder_delete_title, c.getString(R.string.folder_delete_msg, f.name, dest), R.string.delete) {
                    c.db.deleteFolder(id); c.changed(); c.pop()
                }
            }
        )
        return withFab(page(f.name, back = true, actions = actions, body = body)) {
            if (health) healthAddSheet(c, id) else c.addSheet(id)
        }
    }

    private fun newSubfolder(parentName: String) {
        val c = act
        Dialogs.input(c, c.getString(R.string.subfolder_new_in, parentName), "", R.string.folder_name_hint) { name ->
            c.db.addFolder(id, name); c.changed(); refresh()
        }
    }
}
