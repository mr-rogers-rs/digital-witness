package com.fieldnotes.recorder.crypto

import java.io.OutputStream
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** RFC 7516 compact JWE; RSA-OAEP-256 and A256GCM (RFC 7518).
 * Only recipient PUBLIC keys belong on the recording phone.
 * Streaming encoder; export is published by the caller only after successful completion.
 */
object RecipientEncryption {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val header = b64.encodeToString(
        "{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\"}".toByteArray(Charsets.US_ASCII)
    )

    fun parsePublicKey(pem: String): RSAPublicKey {
        require(pem.length <= 16_384) { "Public key is too large" }
        require(pem.contains("-----BEGIN PUBLIC KEY-----") && !pem.contains("PRIVATE")) {
            "Paste a PEM PUBLIC KEY, never a private key"
        }
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), "")
        val key = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(body))
        ) as? RSAPublicKey ?: error("RSA public key required")
        require(key.modulus.bitLength() in 2048..8192) { "RSA key must be 2048–8192 bits" }
        return key
    }

    fun encrypt(pem: String, output: OutputStream, writePlaintext: (OutputStream) -> Unit) {
        val recipient = parsePublicKey(pem)
        val random = SecureRandom()
        val cek = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        try {
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(Cipher.ENCRYPT_MODE, recipient,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            val encryptedKey = rsa.doFinal(cek)
            output.write("$header.${b64.encodeToString(encryptedKey)}.${b64.encodeToString(iv)}."
                .toByteArray(Charsets.US_ASCII))
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, iv))
            aes.updateAAD(header.toByteArray(Charsets.US_ASCII))
            val encoded = b64.wrap(object : OutputStream() {
                override fun write(value: Int) { output.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
                override fun flush() { output.flush() }
                override fun close() { flush() }
            })
            var total = 0L
            val plain = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()))
                override fun write(b: ByteArray, off: Int, len: Int) {
                    total += len
                    require(total <= (1L shl 34)) { "Export exceeds the supported 16 GiB limit" }
                    aes.update(b, off, len)?.let { encoded.write(it) }
                }
            }
            writePlaintext(plain)
            val final = aes.doFinal()
            check(final.size >= 16)
            encoded.write(final, 0, final.size - 16)
            encoded.close()
            output.write('.'.code)
            output.write(b64.encode(final.copyOfRange(final.size - 16, final.size)))
            output.flush()
        } finally {
            cek.fill(0)
        }
    }
}
