package app.pensy.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * The single-file export (.pensy) when there are photos of documents.
 *
 * Without photos the sealed content is the database itself, as since the
 * first version. With photos it is a plain ZIP, sealed the same way:
 *
 *   pensy.db           the database
 *   pagine/<name>.jpg  one file per kept page
 *
 * Opening checks every name: only "pensy.db" and "pagine/<safe name>",
 * so a crafted file cannot write anywhere else.
 */
object Pack {

    const val DB = "pensy.db"
    const val PAGES = "pagine/"
    private val SAFE = Regex("""[a-z0-9][a-z0-9\-]{0,63}\.jpg""")

    class Content(val db: ByteArray, val pages: Map<String, ByteArray>)

    fun isSafeName(name: String): Boolean = SAFE.matches(name)

    fun isZip(b: ByteArray): Boolean = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
        b[2] == 3.toByte() && b[3] == 4.toByte()

    fun pack(db: ByteArray, pages: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream(db.size + pages.values.sumOf { it.size } + 4096)
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry(DB))
            z.write(db)
            z.closeEntry()
            for ((name, bytes) in pages) {
                require(isSafeName(name)) { "bad page name" }
                // JPEG is already compressed: store it as it is.
                val e = ZipEntry(PAGES + name)
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong()
                e.compressedSize = bytes.size.toLong()
                e.crc = CRC32().apply { update(bytes) }.value
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun unpack(b: ByteArray): Content {
        var db: ByteArray? = null
        val pages = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                when {
                    e.isDirectory -> {}
                    name == DB -> db = z.readBytes()
                    name.startsWith(PAGES) && isSafeName(name.removePrefix(PAGES)) -> pages[name.removePrefix(PAGES)] = z.readBytes()
                    else -> throw Vault.BadFormat("unexpected entry")
                }
            }
        }
        return Content(db ?: throw Vault.BadFormat("no database"), pages)
    }
}
