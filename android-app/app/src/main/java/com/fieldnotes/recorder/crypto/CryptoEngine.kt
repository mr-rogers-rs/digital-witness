package com.fieldnotes.recorder.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private const val TAG = "DW-CryptoEngine"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val SIGNING_KEY_ALIAS = "dw_signing_key"
    private const val ATTEST_KEY_ALIAS = "dw_attest_key"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    fun generateMediaKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    fun encryptChunk(mediaKey: SecretKey, nonce12: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce12)
        cipher.init(Cipher.ENCRYPT_MODE, mediaKey, spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decryptChunk(mediaKey: SecretKey, nonce12: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce12)
        cipher.init(Cipher.DECRYPT_MODE, mediaKey, spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun deriveNonce(sessionId: ByteArray, seqNum: Long): ByteArray {
        val info = ByteBuffer.allocate(8).putLong(seqNum).array()
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(sessionId, "HmacSHA256"))
            doFinal(ByteArray(0))
        }
        val okm = ByteArray(32)
        var t = ByteArray(0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var i = 0
        while (i < okm.size) {
            mac.update(t)
            mac.update(info)
            mac.update((i / 32 + 1).toByte())
            t = mac.doFinal()
            val len = minOf(t.size, okm.size - i)
            System.arraycopy(t, 0, okm, i, len)
            i += len
        }
        return okm.copyOfRange(0, 12)
    }

    fun importAesKey(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    /**
     * Ensure the ECDSA P-256 signing key exists in the Android Keystore.
     * Creates one on first call; subsequent calls are no-ops.
     *
     * StrongBox-backed when the device has a dedicated secure element (API 28+),
     * falling back to the TEE otherwise. We deliberately do NOT set
     * `setUserAuthenticationRequired(true)`: recording must run unattended and
     * while the screen is locked, and an auth-per-use prompt would break capture
     * mid-incident. The "seized unlocked device fabricates evidence" threat is
     * instead mitigated by the time bracket (a forged later recording cannot
     * obtain an earlier OTS anchor or a past beacon) and attestation-challenge
     * freshness — see the design doc §4.2.
     */
    fun ensureSigningKey(): Unit = synchronized(com.fieldnotes.recorder.secure.KeyLifecycle.lock) {
        check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) { "Wipe in progress" }
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(SIGNING_KEY_ALIAS)) return

        fun buildSpec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            SIGNING_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

        val wantStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        try {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            kpg.initialize(buildSpec(wantStrongBox))
            kpg.generateKeyPair()
            Log.i(TAG, "Created ECDSA P-256 signing key (strongBox=$wantStrongBox)")
        } catch (e: Exception) {
            // StrongBoxUnavailableException (and some OEM quirks) → retry on TEE.
            if (!wantStrongBox) throw e
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            kpg.initialize(buildSpec(false))
            kpg.generateKeyPair()
            Log.i(TAG, "Created ECDSA P-256 signing key (TEE fallback)")
        }
    }

    /**
     * Generate a fresh, per-session **attestation** key whose hardware key
     * attestation certificate chain proves a genuine Android device (StrongBox
     * or TEE) produced a key bound to [challenge] — not an emulator, desktop
     * generator, or software forgery. The challenge is `beaconRandomness ‖
     * sessionId`, tying the attestation to the time bracket so it cannot be
     * replayed from another session (design doc §2.4).
     *
     * Returns the certificate chain, leaf-first, or null if the platform does
     * not produce an attestation (no Google-rooted attestation support). The
     * key itself is disposable — only its attestation chain matters.
     */
    fun generateAttestationChain(challenge: ByteArray): Array<Certificate>? = synchronized(com.fieldnotes.recorder.secure.KeyLifecycle.lock) {
        check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) { "Wipe in progress" }
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        try { if (ks.containsAlias(ATTEST_KEY_ALIAS)) ks.deleteEntry(ATTEST_KEY_ALIAS) } catch (_: Exception) {}

        fun buildSpec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ATTEST_KEY_ALIAS, KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

        val wantStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return try {
            val chain = try {
                generateAndGetChain(ks, buildSpec(wantStrongBox))
            } catch (e: Exception) {
                if (!wantStrongBox) throw e
                generateAndGetChain(ks, buildSpec(false))
            }
            chain
        } catch (e: Exception) {
            Log.w(TAG, "Attestation chain unavailable", e)
            null
        }
    }

    private fun generateAndGetChain(ks: KeyStore, spec: KeyGenParameterSpec): Array<Certificate>? {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        kpg.initialize(spec)
        kpg.generateKeyPair()
        return ks.getCertificateChain(ATTEST_KEY_ALIAS)
    }

    /**
     * Returns the per-session attestation chain as a JSON-friendly list of
     * base64 (X.509 DER) certificates, leaf-first, or null if unavailable.
     */
    fun getAttestationChainBase64(challenge: ByteArray): List<String>? {
        val chain = generateAttestationChain(challenge) ?: return null
        return chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
    }

    /**
     * Sign arbitrary data with the device signing key (ECDSA P-256 / SHA-256).
     */
    fun signData(data: ByteArray): ByteArray {
        ensureSigningKey()
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = ks.getKey(SIGNING_KEY_ALIAS, null) as java.security.PrivateKey
        val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Returns the public key bytes (X.509 encoded) for sharing with verifiers.
     */
    fun getPublicKeyBytes(): ByteArray {
        ensureSigningKey()
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val cert = ks.getCertificate(SIGNING_KEY_ALIAS)
        return cert.publicKey.encoded
    }

    /**
     * Returns the public key as a Base64 string for embedding in proof JSON or sharing.
     */
    fun getPublicKeyBase64(): String {
        val bytes = getPublicKeyBytes()
        return if (bytes.isNotEmpty()) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
    }
}
