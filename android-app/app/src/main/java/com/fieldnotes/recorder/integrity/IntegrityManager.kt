package com.fieldnotes.recorder.integrity

import android.util.Base64
import java.io.File
import java.security.MessageDigest

object IntegrityManager {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    fun sha256File(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    fun sha256FileHex(file: File): String = sha256File(file).joinToString("") { "%02x".format(it) }

    /**
     * Compute the next link in the hash chain.
     * H_i = SHA-256(currentSegmentHash || previousChainHash)
     */
    fun nextHash(prev: ByteArray, segmentHash: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(segmentHash)
        md.update(prev)
        return md.digest()
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
