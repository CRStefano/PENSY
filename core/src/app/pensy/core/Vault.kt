package app.pensy.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * The PENSY_DATA container: the whole database sealed with a password.
 *
 * Layout of pensy.dat (all integers big endian):
 *
 *   offset size  field
 *   0      8     magic "PENSYDAT"
 *   8      1     format version (1)
 *   9      1     key derivation (1 = PBKDF2-HMAC-SHA256)
 *   10     4     iterations
 *   14     16    salt
 *   30     12    nonce
 *   42     ...   AES-256-GCM ciphertext, 16 byte tag at the end
 *
 * The 42 header bytes are authenticated too (GCM additional data), so
 * nobody can lower the iterations or swap the salt without notice.
 * A wrong password and a corrupted file look the same: the tag fails.
 *
 * Only the standard Java crypto API is used, present on every Android
 * since 8.0: nothing to download, nothing to trust beyond the platform.
 */
object Vault {

    val MAGIC: ByteArray = "PENSYDAT".toByteArray(Charsets.US_ASCII)
    const val FORMAT: Byte = 1
    const val KDF_PBKDF2_SHA256: Byte = 1
    const val DEFAULT_ITERATIONS = 200_000
    const val HEADER_SIZE = 42
    private const val TAG_BITS = 128

    class WrongPassword : Exception("wrong password or damaged file")
    class BadFormat(msg: String) : Exception(msg)

    class Header(val iterations: Int, val salt: ByteArray, val nonce: ByteArray)

    private val rnd = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(plain: ByteArray, key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(key.size == 32 && salt.size == 16)
        val nonce = random(12)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(FORMAT).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(nonce)
            .array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    fun header(blob: ByteArray): Header {
        if (blob.size < HEADER_SIZE + 16) throw BadFormat("file too short")
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) throw BadFormat("not a PENSY_DATA file")
        if (blob[8] != FORMAT) throw BadFormat("unknown format version ${blob[8]}")
        if (blob[9] != KDF_PBKDF2_SHA256) throw BadFormat("unknown key derivation ${blob[9]}")
        val bb = ByteBuffer.wrap(blob, 10, 4)
        val iterations = bb.int
        if (iterations < 1000 || iterations > 10_000_000) throw BadFormat("bad iterations")
        return Header(iterations, blob.copyOfRange(14, 30), blob.copyOfRange(30, 42))
    }

    fun open(blob: ByteArray, key: ByteArray): ByteArray {
        val h = header(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, h.nonce))
        c.updateAAD(blob, 0, HEADER_SIZE)
        try {
            return c.doFinal(blob, HEADER_SIZE, blob.size - HEADER_SIZE)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }
    }

    /** Convenience for imports: derive the key from the file's own salt and open it. */
    fun openWithPassword(blob: ByteArray, password: CharArray): Pair<ByteArray, ByteArray> {
        val h = header(blob)
        val key = deriveKey(password, h.salt, h.iterations)
        return open(blob, key) to key
    }

    /** True if the bytes look like an SQLite database. */
    fun isSqlite(b: ByteArray): Boolean {
        val sig = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        if (b.size < sig.size) return false
        for (i in sig.indices) if (b[i] != sig[i]) return false
        return true
    }
}
