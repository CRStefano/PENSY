package app.pensy.core

/*
 * Which drawer does a document go in?
 *
 *   1. a rule learned from the user: "documents from SMAT go in Casa > Acqua";
 *   2. otherwise the drawer whose subject the text talks about (auto,
 *      salute, animali...), helped by the kind of document;
 *   3. then, inside that drawer, the sub-drawer whose name (or a word
 *      related to it) appears in the text: a "Cuore" sub-drawer catches
 *      "visita cardiologica", a "Gamba" one catches "ginocchio".
 *
 * Plain keyword counting, no magic: the confirmation screen always shows
 * the choice and the user can move the document with one tap.
 */
object Sorter {

    private val KIND_KEYS: Map<FolderKind, List<String>> = mapOf(
        FolderKind.AUTO to listOf(
            "targa", "veicolo", "autovettura", "automobil", "bollo auto", "revisione", "rca", "tagliando",
            "carrozzeria", "officina", "pneumatic", "gomme", "patente", "multa", "verbale", "codice della strada",
            "motorizzazione", "aci", "carburante", "parcheggio", "autostrad", "telepass"
        ),
        FolderKind.SALUTE to listOf(
            "asl", "ospedal", "referto", "visita", "ambulatori", "esame", "analisi", "diagnosi", "terapia",
            "farmac", "ricetta", "medico", "dott", "specialist", "ticket", "cardiolog", "ortoped", "radiolog",
            "ecografia", "risonanza", "tac", "ecg", "sangue", "s.s.n", "sanitari", "paziente", "anamnesi",
            "dentist", "oculist", "dermatolog", "fisioterap", "laboratorio"
        ),
        FolderKind.ANIMALI to listOf(
            "veterinari", "microchip", "cane", "gatto", "antiparassitari", "clinica veterinaria", "anagrafe canina",
            "toelettatura"
        ),
        FolderKind.CASA to listOf(
            "bolletta", "energia elettrica", "gas naturale", "servizio idrico", "fornitura", "condomini", "affitto",
            "locazione", "mutuo", "tari", "rifiuti", "fibra", "internet", "telefon", "kwh", "smc", "elettrodomestic",
            "arredament", "caldaia", "assicurazione casa"
        ),
        FolderKind.SCUOLA to listOf(
            "scuola", "scolastic", "istituto comprensivo", "universit", "ateneo", "politecnico", "tasse universitarie",
            "contribuzione studentesca", "mensa", "gita", "iscrizione", "matricola", "libri di testo", "docente"
        ),
        FolderKind.LAVORO to listOf(
            "busta paga", "cedolino", "retribuzione", "contratto di lavoro", "certificazione unica", "datore di lavoro",
            "ferie", "trattenute", "tfr"
        ),
        FolderKind.TASSE to listOf(
            "f24", "agenzia delle entrate", "730", "imu", "irpef", "modello redditi", "codice tributo",
            "carta d'identità", "carta di identità", "passaporto", "anagrafe", "codice fiscale", "dichiarazione dei redditi"
        )
    )

    /**
     * Words related to a sub-drawer name. A sub-drawer whose name starts like
     * a key (or like one of its words) borrows the whole group.
     */
    private val RELATED: Map<String, List<String>> = mapOf(
        "cuore" to listOf("cuore", "cardio", "cardiac", "ecg", "elettrocardiogramma", "ecocardio", "holter", "aritmi", "coronar"),
        "gamba" to listOf("gamba", "gambe", "ginocchi", "menisco", "caviglia", "tibia", "femore", "polpaccio", "piede", "anca", "ortoped"),
        "ginocchio" to listOf("ginocchi", "menisco", "rotula", "crociato"),
        "braccio" to listOf("braccio", "braccia", "spalla", "gomito", "polso", "mano", "omero"),
        "schiena" to listOf("schiena", "colonna", "lombar", "cervical", "vertebr", "rachide", "ernia", "dorsal"),
        "occhi" to listOf("occhi", "occhio", "oculist", "oftalm", "vista", "retina", "miopia"),
        "denti" to listOf("denti", "dente", "dentist", "odontoiatr", "dentale", "ortodon"),
        "pelle" to listOf("pelle", "dermatolog", "nevo", "cute"),
        "sangue" to listOf("sangue", "emocromo", "glicemia", "colesterol", "emoglobina", "ematochimic"),
        "polmoni" to listOf("polmon", "pneumolog", "spirometr", "torace", "respirator"),
        "stomaco" to listOf("stomaco", "gastro", "endoscop", "intestin", "addome"),
        "testa" to listOf("testa", "cefalea", "emicrania", "neurolog", "encefal"),
        "orecchie" to listOf("orecchi", "otorino", "udito", "audiometr"),
        "tiroide" to listOf("tiroide", "tsh", "endocrinolog"),
        "vaccini" to listOf("vaccin"),
        "esami" to listOf("esame", "esami", "analisi", "laboratorio"),
        "assicurazione" to listOf("assicurazion", "polizza", "rca"),
        "revisione" to listOf("revisione", "collaudo"),
        "bollo" to listOf("bollo", "tassa automobilistica"),
        "multe" to listOf("multa", "multe", "verbale", "contravvenzione", "violazione"),
        "manutenzione" to listOf("tagliando", "officina", "manutenzione", "gomme", "pneumatic", "carrozzeria"),
        "luce" to listOf("luce", "energia elettrica", "kwh", "pod"),
        "gas" to listOf("gas", "smc", "pdr"),
        "acqua" to listOf("acqua", "idrico", "smat"),
        "internet" to listOf("internet", "fibra", "adsl", "telefon"),
        "condominio" to listOf("condomini", "amministratore"),
        "mutuo" to listOf("mutuo", "ammortamento")
    )

    private val wordCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** A key must start a word; keys of four letters or less must also end it. */
    private fun has(low: String, key: String): Boolean {
        val r = wordCache.getOrPut(key) {
            val end = if (key.length <= 4) "(?![\\p{L}\\d])" else ""
            Regex("(?<![\\p{L}\\d])" + Regex.escape(key) + end)
        }
        return r.containsMatchIn(low)
    }

    fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Key under which a learned rule is stored. */
    fun issuerKey(issuer: String): String? = normalize(issuer).takeIf { it.length >= 2 }?.let { "issuer:$it" }

    /** Scores of each drawer kind for a text; the document kind adds a nudge. */
    fun kindScores(text: String, type: DocType): Map<FolderKind, Int> {
        val low = text.lowercase()
        val out = HashMap<FolderKind, Int>()
        for ((kind, keys) in KIND_KEYS) {
            val n = keys.count { has(low, it) }
            if (n > 0) out[kind] = n
        }
        if (type != DocType.ALTRO && type != DocType.PAGOPA && type != DocType.POLIZZA) {
            out[type.kind] = (out[type.kind] ?: 0) + 3
        }
        return out
    }

    fun bestKind(text: String, type: DocType): FolderKind {
        val scores = kindScores(text, type)
        val best = scores.maxOfOrNull { it.value } ?: return type.kind
        // Ties: the document kind wins, then the declaration order.
        if ((scores[type.kind] ?: 0) == best) return type.kind
        return FolderKind.entries.first { (scores[it] ?: 0) == best }
    }

    /** Words that point to a sub-drawer with this name ("Cuore" -> cuore, cardio, ecg...). */
    fun relatedWords(name: String): List<String> {
        val n = normalize(name)
        if (n.length < 3) return if (n.isEmpty()) emptyList() else listOf(n)
        val words = LinkedHashSet<String>()
        words += n
        for (w in n.split(' ').filter { it.length >= 4 }) {
            words += w
            val stem = w.take(5)
            for ((key, group) in RELATED) {
                if (key.startsWith(stem) || stem.startsWith(key.take(5)) || group.any { it.startsWith(stem) }) words += group
            }
        }
        return words.toList()
    }

    private fun descendants(folders: List<Folder>, id: Long): List<Folder> {
        val out = ArrayList<Folder>()
        val queue = ArrayDeque<Long>()
        queue.addLast(id)
        while (queue.isNotEmpty()) {
            val cur = queue.removeAt(0)
            for (f in folders) if (f.parentId == cur) { out += f; queue.addLast(f.id) }
        }
        return out
    }

    /** The best sub-drawer of `top` for this text, or `top` itself. */
    fun bestInside(text: String, top: Folder, folders: List<Folder>): Folder {
        val low = text.lowercase()
        var best = top
        var bestScore = 0
        for (f in descendants(folders, top.id)) {
            val s = relatedWords(f.name).count { has(low, it) }
            if (s > bestScore) { best = f; bestScore = s }
        }
        return best
    }

    /**
     * The drawer for a new document. `rules` maps learned keys (see
     * issuerKey) to folder ids. Returns null only if there are no folders.
     */
    fun suggest(text: String, type: DocType, issuer: String, folders: List<Folder>, rules: Map<String, Long>): Long? {
        if (folders.isEmpty()) return null
        issuerKey(issuer)?.let { k -> rules[k]?.let { id -> if (folders.any { it.id == id }) return id } }
        val kind = bestKind(text + "\n" + issuer, type)
        val tops = folders.filter { it.parentId == null }
        val top = tops.firstOrNull { it.kind == kind.code }
            ?: tops.firstOrNull { it.kind == FolderKind.ALTRO.code }
            ?: tops.firstOrNull()
            ?: return folders.first().id
        return bestInside(text, top, folders).id
    }

    /** "Salute › Cuore": the path of a folder, from the top. */
    fun path(folders: List<Folder>, id: Long?): List<Folder> {
        val out = ArrayList<Folder>()
        var cur = folders.firstOrNull { it.id == id }
        var guard = 0
        while (cur != null && guard++ < 32) {
            out.add(0, cur)
            val p = cur.parentId
            cur = if (p == null) null else folders.firstOrNull { it.id == p }
        }
        return out
    }

    /** The folder and everything below it. */
    fun subtree(folders: List<Folder>, id: Long): Set<Long> = (listOf(id) + descendants(folders, id).map { it.id }).toSet()
}
