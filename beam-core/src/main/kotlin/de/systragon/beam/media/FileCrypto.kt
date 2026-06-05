package de.systragon.beam.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optionale Ende-zu-Ende-Verschlüsselung per Passphrase.
 *
 * **Gechunktes Format (BEAMENC2)** — pflicht bei großen Dateien, weil Androids AES/GCM den ganzen
 * Datenstrom im RAM puffert (ein einziger GCM-Block über 600 MB → OutOfMemory). Lösung: die Datei
 * in 1-MB-Klartext-Chunks zerlegen, jeden Chunk als eigenes AES-256-GCM verschlüsseln.
 *
 *   MAGIC2(8) | salt(16) | noncePrefix(8) | [ chunk... ]
 *   chunk = ctLen(4, big-endian) | ciphertext(=Klartext + 16 Byte GCM-Tag)
 *   Nonce je Chunk = noncePrefix(8) || chunkIndex(4, big-endian)  (eindeutig pro Schlüssel)
 *   Klartext-Strom (über alle Chunks) = nameLen(2) | originalName(UTF-8) | originalBytes
 *
 * Schlüssel = PBKDF2(passphrase, salt). Falsche Passphrase → AEADBadTagException im 1. Chunk.
 * Alte Container (BEAMENC1, ein einziger GCM-Block) werden beim Entschlüsseln weiter gelesen.
 */
object FileCrypto {

    private const val MAGIC = "BEAMENC1"            // altes Format (Lesen)
    private const val MAGIC2 = "BEAMENC2"           // neues, gechunktes Format (Schreiben)
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val NONCE_PREFIX_LEN = 8
    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val CHUNK = 1024 * 1024           // 1 MB Klartext pro Chunk
    private const val EXT = ".beamenc"

    fun isEncryptedName(name: String): Boolean = name.endsWith(EXT, ignoreCase = true)

    fun encrypt(
        source: File,
        originalName: String,
        passphrase: String,
        outFile: File,
        onProgress: (Float) -> Unit
    ) {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val nameBytes = originalName.toByteArray(Charsets.UTF_8)
        val total = source.length() + 2 + nameBytes.size   // Klartext-Gesamtgröße (für Fortschritt)

        FileOutputStream(outFile).use { fos ->
            fos.write(MAGIC2.toByteArray(Charsets.US_ASCII))
            fos.write(salt)
            fos.write(noncePrefix)

            val plain = ByteArray(CHUNK)
            var filled = 0
            var chunkIndex = 0
            var processed = 0L
            var lastReported = 0L

            fun flushChunk() {
                cipher.init(
                    Cipher.ENCRYPT_MODE, key,
                    GCMParameterSpec(TAG_BITS, chunkNonce(noncePrefix, chunkIndex))
                )
                val ct = cipher.doFinal(plain, 0, filled)
                writeInt(fos, ct.size)
                fos.write(ct)
                chunkIndex++
                filled = 0
            }

            // Header (nameLen + name) an den Anfang des Klartext-Stroms.
            plain[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
            plain[1] = (nameBytes.size and 0xFF).toByte()
            System.arraycopy(nameBytes, 0, plain, 2, nameBytes.size)
            filled = 2 + nameBytes.size

            FileInputStream(source).use { fis ->
                while (true) {
                    val n = fis.read(plain, filled, CHUNK - filled)
                    if (n < 0) break
                    filled += n
                    processed += n
                    if (processed - lastReported >= 2L * 1024 * 1024) {
                        lastReported = processed
                        onProgress((processed.toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (filled == CHUNK) flushChunk()
                }
            }
            if (filled > 0) flushChunk()   // letzter (Teil-)Chunk
        }
        onProgress(1f)
    }

    /**
     * Entschlüsselt [container] in [outDir] und gibt (echter Name, Klartextdatei) zurück.
     * Wirft AEADBadTagException bei falscher Passphrase / Manipulation.
     */
    fun decrypt(
        container: File,
        passphrase: String,
        outDir: File,
        onProgress: (Float) -> Unit
    ): Pair<String, File> {
        FileInputStream(container).use { fis ->
            val magic = ByteArray(MAGIC.length).also { readFully(fis, it) }
            val salt = ByteArray(SALT_LEN).also { readFully(fis, it) }
            return when (String(magic, Charsets.US_ASCII)) {
                MAGIC2 -> {
                    val prefix = ByteArray(NONCE_PREFIX_LEN).also { readFully(fis, it) }
                    decryptChunked(fis, deriveKey(passphrase, salt), prefix, outDir, container.length(), onProgress)
                }
                MAGIC -> {
                    val nonce = ByteArray(NONCE_LEN).also { readFully(fis, it) }
                    decryptLegacy(fis, deriveKey(passphrase, salt), nonce, outDir, container.length(), onProgress)
                }
                else -> throw IOException("Not a Beam encrypted file")
            }
        }
    }

    /** Liest gechunkte Container (BEAMENC2). Speicher bleibt bei ~1 Chunk. */
    private fun decryptChunked(
        fis: InputStream,
        key: SecretKeySpec,
        prefix: ByteArray,
        outDir: File,
        total: Long,
        onProgress: (Float) -> Unit
    ): Pair<String, File> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val header = ByteArrayOutputStream()
        var nameLen = -1
        var name: String? = null
        var out: FileOutputStream? = null
        var outFile: File? = null

        fun consume(plain: ByteArray) {
            if (plain.isEmpty()) return
            if (name == null) {
                header.write(plain)
                val hb = header.toByteArray()
                if (nameLen < 0 && hb.size >= 2) {
                    nameLen = ((hb[0].toInt() and 0xFF) shl 8) or (hb[1].toInt() and 0xFF)
                }
                if (nameLen >= 0 && hb.size >= 2 + nameLen) {
                    name = String(hb, 2, nameLen, Charsets.UTF_8)
                    outFile = File(outDir, name!!)
                    out = FileOutputStream(outFile)
                    val contentStart = 2 + nameLen
                    if (hb.size > contentStart) out!!.write(hb, contentStart, hb.size - contentStart)
                }
            } else {
                out!!.write(plain)
            }
        }

        try {
            var chunkIndex = 0
            var processed = 0L
            var lastReported = 0L
            while (true) {
                val ctLen = readIntOrEof(fis)
                if (ctLen < 0) break
                if (ctLen <= 0 || ctLen > CHUNK + 64) throw IOException("corrupt chunk length")
                val ct = ByteArray(ctLen).also { readFully(fis, it) }
                cipher.init(
                    Cipher.DECRYPT_MODE, key,
                    GCMParameterSpec(TAG_BITS, chunkNonce(prefix, chunkIndex))
                )
                consume(cipher.doFinal(ct))   // wirft bei falscher Passphrase (1. Chunk)
                chunkIndex++
                processed += 4 + ctLen
                if (processed - lastReported >= 2L * 1024 * 1024) {
                    lastReported = processed
                    onProgress((processed.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } catch (e: Exception) {
            out?.close()
            outFile?.delete()
            throw e
        }

        out?.close()
        onProgress(1f)
        val finalName = name ?: throw IOException("corrupt encrypted file")
        return finalName to outFile!!
    }

    /** Altes Einzel-GCM-Format (BEAMENC1) — nur Lesen, für noch kursierende Alt-Links. */
    private fun decryptLegacy(
        fis: InputStream,
        key: SecretKeySpec,
        nonce: ByteArray,
        outDir: File,
        total: Long,
        onProgress: (Float) -> Unit
    ): Pair<String, File> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        val header = ByteArrayOutputStream()
        var nameLen = -1
        var name: String? = null
        var out: FileOutputStream? = null
        var outFile: File? = null

        fun consume(plain: ByteArray?) {
            if (plain == null || plain.isEmpty()) return
            if (name == null) {
                header.write(plain)
                val hb = header.toByteArray()
                if (nameLen < 0 && hb.size >= 2) {
                    nameLen = ((hb[0].toInt() and 0xFF) shl 8) or (hb[1].toInt() and 0xFF)
                }
                if (nameLen >= 0 && hb.size >= 2 + nameLen) {
                    name = String(hb, 2, nameLen, Charsets.UTF_8)
                    outFile = File(outDir, name!!)
                    out = FileOutputStream(outFile)
                    val contentStart = 2 + nameLen
                    if (hb.size > contentStart) out!!.write(hb, contentStart, hb.size - contentStart)
                }
            } else {
                out!!.write(plain)
            }
        }

        try {
            val buf = ByteArray(64 * 1024)
            var processed = 0L
            var lastReported = 0L
            while (true) {
                val n = fis.read(buf)
                if (n < 0) break
                consume(cipher.update(buf, 0, n))
                processed += n
                if (processed - lastReported >= 2L * 1024 * 1024) {
                    lastReported = processed
                    onProgress((processed.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            consume(cipher.doFinal())
        } catch (e: Exception) {
            out?.close()
            outFile?.delete()
            throw e
        }

        out?.close()
        onProgress(1f)
        val finalName = name ?: throw IOException("corrupt encrypted file")
        return finalName to outFile!!
    }

    /** Nonce für Chunk: noncePrefix(8) || chunkIndex(4, big-endian). */
    private fun chunkNonce(prefix: ByteArray, index: Int): ByteArray {
        val nonce = ByteArray(NONCE_LEN)
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_LEN)
        nonce[8] = ((index ushr 24) and 0xFF).toByte()
        nonce[9] = ((index ushr 16) and 0xFF).toByte()
        nonce[10] = ((index ushr 8) and 0xFF).toByte()
        nonce[11] = (index and 0xFF).toByte()
        return nonce
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** Liest 4 Byte big-endian; gibt -1 bei sauberem EOF (vor dem ersten Byte) zurück. */
    private fun readIntOrEof(input: InputStream): Int {
        val b = ByteArray(4)
        var off = 0
        while (off < 4) {
            val n = input.read(b, off, 4 - off)
            if (n < 0) {
                if (off == 0) return -1
                throw IOException("truncated chunk length")
            }
            off += n
        }
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw IOException("unexpected end of file")
            off += n
        }
    }
}
