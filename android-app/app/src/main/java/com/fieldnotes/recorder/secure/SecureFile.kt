package com.fieldnotes.recorder.secure

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.fieldnotes.recorder.crypto.CryptoEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Self-managed at-rest encryption, replacing the unmaintained
 * `androidx.security:security-crypto` alpha. All at-rest protection in the app
 * (queued media + preferences) rests on this layer, so it is deliberately small
 * and auditable.
 *
 * Design (design doc §4.3/§4.4):
 *  - One non-exportable AES-256-GCM **wrapping key** in the Android Keystore
 *    (`dw_wrap_key`, StrongBox when available). It never leaves secure hardware.
 *  - Each file gets a fresh random **data key**, wrapped by the Keystore key and
 *    stored in the file header. Per-file keys are stronger than one global key
 *    and bound the file to this device's Keystore.
 *  - Content is encrypted in fixed-size chunks with the repurposed
 *    [CryptoEngine.encryptChunk]/[CryptoEngine.decryptChunk]/[CryptoEngine.deriveNonce]
 *    primitives — streaming GCM so multi-GB media never lands in heap, and each
 *    chunk's nonce is derived from a per-file random salt + chunk index. The
 *    chunk index is also bound as AAD, so reordering/truncation is detected.
 *
 * Nonce uniqueness: the data key is random per file AND the nonce salt is random
 * per file, so `deriveNonce(fileNonceSalt, chunkIndex)` is unique for every
 * (key, nonce) pair across all files and chunks.
 *
 * File layout (all integers big-endian):
 *   magic "DWSF" (4) | version (1) | wrapNonce (12) | wrappedKeyLen (2) |
 *   wrappedDataKey (wrappedKeyLen) | fileNonceSalt (16) | chunkSize (4) |
 *   repeated { cipherLen (4) | chunk ciphertext (cipherLen) }
 */
object SecureFile {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val WRAP_KEY_ALIAS = "dw_wrap_key"
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
    private const val VERSION = 2
    private const val DATA_KEY_BYTES = 32
    private const val FILE_NONCE_SALT_BYTES = 16
    private const val DEFAULT_CHUNK = 64 * 1024
    // Hard cap on chunks per file to stop a crafted header from looping
    // near-forever (audit H12). 64K * 16M ≈ 1 TiB — far above any real file.
    private const val MAX_DECRYPT_CHUNKS = 1 shl 20

    // ── Keystore wrapping key ────────────────────────────────────────────────

    private val wrapKeyLock = KeyLifecycle.lock

    private fun getOrCreateWrapKey(create: Boolean = true): javax.crypto.SecretKey = synchronized(wrapKeyLock) {
        // Fail closed if a panic/duress wipe is underway. Without this, a thread
        // still alive after PanicWipe destroyed dw_wrap_key (e.g. the capture
        // service finalizing one last segment) would hit the branch below and
        // RECREATE the key, producing ciphertext that is decryptable on a seized
        // device — defeating the wipe (security review HIGH).
        if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) {
            throw IllegalStateException("wipe in progress — refusing to (re)create the wrap key")
        }
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getEntry(WRAP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        check(create) { "Wrapping key missing; existing evidence cannot be decrypted" }

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

        val wantStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        return try {
            kg.init(spec(wantStrongBox)); kg.generateKey()
        } catch (e: Exception) {
            if (!wantStrongBox) throw e
            kg.init(spec(false)); kg.generateKey()
        }
    }

    private fun wrapDataKey(raw: ByteArray, headerAad: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        cipher.updateAAD(headerAad)
        val wrapped = cipher.doFinal(raw)
        return cipher.iv to wrapped // (wrapNonce, wrappedKey+tag)
    }

    private fun unwrapDataKey(wrapNonce: ByteArray, wrapped: ByteArray, headerAad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(false), GCMParameterSpec(128, wrapNonce))
        if (headerAad != null) cipher.updateAAD(headerAad)
        return cipher.doFinal(wrapped)
    }

    private fun aadFor(chunkIndex: Long): ByteArray =
        ByteBuffer.allocate(8).putLong(chunkIndex).array()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Encrypt [src] to [dest], streaming. Atomic: writes to a sibling `.tmp`
     * file, fsyncs, then renames over [dest] so a crash mid-encrypt never
     * leaves a partially-written destination (audit H12). The previous code
     * wrote directly to [dest], so a process kill could leave a truncated
     * ciphertext that decryptStream would then fail on — and if [dest] was a
     * queued segment, the original (decrypted) source had often already been
     * deleted by the time the corruption was discovered.
     */
    fun encryptFile(src: File, dest: File, chunkSize: Int = DEFAULT_CHUNK) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            src.inputStream().use { input ->
                tmp.outputStream().use { output ->
                    encryptStream(input, output, chunkSize)
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(dest)) {
                throw java.io.IOException("Atomic rename ${tmp.name} -> ${dest.name} failed")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Decrypt [src] to [dest], streaming. */
    fun decryptFile(src: File, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".partial")
        try {
            tmp.outputStream().use { output ->
                decryptTo(src, output)
                output.fd.sync()
            }
            check(tmp.renameTo(dest)) { "Could not commit decrypted file" }
        } finally {
            tmp.delete()
        }
    }

    fun decryptTo(src: File, output: OutputStream) {
        src.inputStream().use { decryptStream(it, output) }
    }

    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        plaintext.inputStream().use { input -> encryptStream(input, out, DEFAULT_CHUNK) }
        return out.toByteArray()
    }

    fun decryptBytes(ciphertext: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ciphertext.inputStream().use { input -> decryptStream(input, out) }
        return out.toByteArray()
    }

    private fun encryptStream(input: InputStream, rawOut: OutputStream, chunkSize: Int) {
        require(chunkSize in 1..DEFAULT_CHUNK)
        val rng = SecureRandom()
        val dataKeyRaw = ByteArray(DATA_KEY_BYTES).also { rng.nextBytes(it) }
        val fileNonceSalt = ByteArray(FILE_NONCE_SALT_BYTES).also { rng.nextBytes(it) }
        val headerAad = MAGIC + byteArrayOf(VERSION.toByte()) + fileNonceSalt + ByteBuffer.allocate(4).putInt(chunkSize).array()
        val (wrapNonce, wrappedKey) = wrapDataKey(dataKeyRaw, headerAad)
        val dataKey = CryptoEngine.importAesKey(dataKeyRaw)
        // The raw key now lives inside the SecretKeySpec (its own copy); scrub
        // our transient copy so the 32 plaintext key bytes don't linger in the
        // heap any longer than necessary (audit B6). Best-effort: the JVM may
        // already have copied the array during GC, and SecretKeySpec itself
        // can't be reliably zeroed on Android, but this removes the obvious
        // long-lived reference.
        zero(dataKeyRaw)

        val out = DataOutputStream(rawOut)
        out.write(MAGIC)
        out.writeByte(VERSION)
        out.write(wrapNonce)
        out.writeShort(wrappedKey.size)
        out.write(wrappedKey)
        out.write(fileNonceSalt)
        out.writeInt(chunkSize)

        val buf = ByteArray(chunkSize)
        var index = 0L
        var total = 0L
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val plain = if (n == buf.size) buf else buf.copyOf(n)
            val nonce = CryptoEngine.deriveNonce(fileNonceSalt, index)
            val cipher = CryptoEngine.encryptChunk(dataKey, nonce, aadFor(index), plain)
            out.writeInt(cipher.size)
            out.write(cipher)
            total += n
            index++
            if (n < buf.size) break
        }
        // Authenticated terminal record distinguishes complete streams from valid prefixes.
        val terminal = ByteBuffer.allocate(16).putLong(index).putLong(total).array()
        val terminalCipher = CryptoEngine.encryptChunk(dataKey,
            CryptoEngine.deriveNonce(fileNonceSalt, index), aadFor(-1), terminal)
        out.writeInt(-1)
        out.write(terminalCipher)
        out.flush()
    }

    private fun decryptStream(rawIn: InputStream, out: OutputStream) {
        val input = DataInputStream(rawIn)
        val magic = ByteArray(4); input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Bad SecureFile magic" }
        val version = input.readUnsignedByte()
        require(version in 1..VERSION) { "Unsupported SecureFile version $version" }
        val wrapNonce = ByteArray(12); input.readFully(wrapNonce)
        val wrappedLen = input.readUnsignedShort()
        require(wrappedLen == 48) { "Invalid wrapped key length" }
        val wrappedKey = ByteArray(wrappedLen); input.readFully(wrappedKey)
        val fileNonceSalt = ByteArray(FILE_NONCE_SALT_BYTES); input.readFully(fileNonceSalt)
        val chunkSize = input.readInt()
        // Reject absurd chunk sizes from a forged/corrupted header. The header
        // is attacker-controlled (anyone who can write to filesDir), so an
        // unbounded cipherLen allocation below would OOM the process (audit H12).
        // Real chunks are chunkSize + 16 (GCM tag); allow a generous multiple
        // to cover legitimate variation but cap well below heap limits.
        val saneChunkCeiling = (DEFAULT_CHUNK.coerceAtLeast(chunkSize.coerceAtLeast(1))) * 4 + 64
        require(chunkSize in 1..(64 * 1024 * 1024)) {
            "Implausible chunkSize $chunkSize in SecureFile header"
        }

        val headerAad = if (version >= 2) MAGIC + byteArrayOf(version.toByte()) + fileNonceSalt + ByteBuffer.allocate(4).putInt(chunkSize).array() else null
        val rawKey = unwrapDataKey(wrapNonce, wrappedKey, headerAad)
        val dataKey = CryptoEngine.importAesKey(rawKey)
        zero(rawKey) // scrub the transient plaintext key copy (audit B6)
        var index = 0L
        var opsProcessed = 0
        var total = 0L
        while (true) {
            if (opsProcessed >= MAX_DECRYPT_CHUNKS) {
                throw java.io.IOException("SecureFile exceeded $MAX_DECRYPT_CHUNKS chunks — possible decompression bomb")
            }
            val first = input.read()
            if (first == -1) {
                require(version == 1) { "Missing authenticated terminal record" }
                break // Legacy read compatibility; v1 cannot establish completeness.
            }
            val cipherLen = (first shl 24) or (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            if (version == 2 && cipherLen == -1) {
                val terminal = ByteArray(32); input.readFully(terminal)
                val decoded = CryptoEngine.decryptChunk(dataKey,
                    CryptoEngine.deriveNonce(fileNonceSalt, index), aadFor(-1), terminal)
                val record = ByteBuffer.wrap(decoded)
                require(record.long == index && record.long == total) { "Invalid terminal record" }
                require(input.read() == -1) { "Trailing encrypted data" }
                break
            }
            // Bound each allocation: plaintext can't exceed chunkSize, so
            // ciphertext (chunkSize + 16 tag bytes) is bounded. A header
            // claiming a multi-GB chunk is a corruption/forgery, not real data.
            if (cipherLen < 16 || cipherLen > saneChunkCeiling) {
                throw java.io.IOException("Implausible cipherLen $cipherLen at chunk $index (ceiling $saneChunkCeiling)")
            }
            val cipher = ByteArray(cipherLen); input.readFully(cipher)
            val nonce = CryptoEngine.deriveNonce(fileNonceSalt, index)
            val plain = CryptoEngine.decryptChunk(dataKey, nonce, aadFor(index), cipher)
            out.write(plain)
            total += plain.size
            index++
            opsProcessed++
        }
        out.flush()
    }

    /** Read until [buf] is full or EOF; returns bytes read. */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /**
     * Overwrite a transient key/byte buffer with zeros (audit B6). Best-effort
     * heap hygiene only: on the JVM the GC may have already relocated the
     * array, and we cannot scrub the copy held inside a [javax.crypto.SecretKey].
     * It still removes the most obvious long-lived plaintext-key reference.
     */
    private fun zero(b: ByteArray) {
        java.util.Arrays.fill(b, 0.toByte())
    }

    /** Delete the Keystore wrapping key (used by PanicWipe via its alias list). */
    fun wrapKeyAlias(): String = WRAP_KEY_ALIAS
}
