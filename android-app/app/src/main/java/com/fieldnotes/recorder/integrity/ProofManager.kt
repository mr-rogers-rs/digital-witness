package com.fieldnotes.recorder.integrity

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fieldnotes.recorder.crypto.CryptoEngine
import com.fieldnotes.recorder.util.Prefs
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates sidecar JSON proof files for each MP4 segment.
 * Maintains a rolling SHA-256 hash chain across segments within a session.
 */
class ProofManager(private val context: Context, private val sessionId: String,
    private val recoveredFrom: String? = null, private val recoveredAnonymous: Boolean? = null) {
    companion object {
        private const val TAG = "DW-ProofManager"
        private const val PROOF_VERSION = 4
    }

    // Chain state is loaded from persisted session state so a service recreated after a
    // mid-recording crash resumes the same chain rather than forking a new one.
    private var segmentIndex: Int = if (recoveredFrom == null) Prefs.chainSegmentIndex(context) else 0
    private var previousChainHash: ByteArray? =
        if (recoveredFrom == null) Prefs.chainPrevHash(context)?.let { IntegrityManager.fromHex(it) } else null
    private var saltB64: String? = if (recoveredFrom == null) Prefs.chainSaltB64(context) else
        Base64.encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
    private var beaconJson: String? = if (recoveredFrom == null) Prefs.chainBeacon(context) else null

    init {
        if (previousChainHash != null) {
            Log.i(TAG, "Resuming chain session=$sessionId at segmentIndex=$segmentIndex")
        } else {
            Log.i(TAG, "New chain session=$sessionId (H_0 seeded at first segment)")
        }
    }

    /**
     * Lazily seed H_0 = SHA-256(sessionId ‖ drandRandomness ‖ salt) on the first segment,
     * reading the salt (always present) and the drand beacon (if fetched in time) from the
     * persisted session state, then persist the head. Subsequent calls and post-crash
     * resumes use the persisted chain head. Returns the current chain head.
     */
    private fun ensureSeeded(): ByteArray {
        previousChainHash?.let { return it }
        if (recoveredFrom == null) {
            saltB64 = Prefs.chainSaltB64(context)
            beaconJson = Prefs.chainBeacon(context)
        }
        val salt = saltB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0)
        val randomnessHex = beaconJson?.let {
            runCatching { JSONObject(it).optString("randomness", "") }.getOrNull()
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sessionId.toByteArray(Charsets.UTF_8))
        if (!randomnessHex.isNullOrEmpty()) md.update(randomnessHex.toByteArray(Charsets.UTF_8))
        md.update(salt)
        val h0 = md.digest()
        previousChainHash = h0
        if (recoveredFrom == null) Prefs.saveChainProgress(context, segmentIndex, IntegrityManager.toHex(h0))
        Log.i(TAG, "Seeded H_0 (beacon=${!randomnessHex.isNullOrEmpty()}) session=$sessionId")
        return h0
    }

    /**
     * Generate a proof file for a completed MP4 segment.
     * Returns the proof File, or null on failure.
     */
    @Volatile var videoBitratePolicy: String? = null

    fun generateProof(
        mp4File: File,
        resolution: String,
        fps: Int,
        videoBitrateKbps: Int,
        audioSampleRate: Int,
        audioBitrateKbps: Int,
        telemetryFile: File? = null
    ): File? {
        return try {
            val prev = ensureSeeded()
            val anonymous = recoveredAnonymous ?: Prefs.isAnonymousMode(context)
            val mp4Hash = IntegrityManager.sha256File(mp4File)
            val telHash = telemetryFile?.let { IntegrityManager.sha256File(it) }
            val now = System.currentTimeMillis()
            val timestamp = if (anonymous) now / 3_600_000L * 3_600_000L else now
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(timestamp))
            // Exact serialized bytes are committed. Verifiers do not reserialize floating-point metadata.
            val metadata = JSONObject().apply {
                put("version", PROOF_VERSION)
                put("sessionId", sessionId)
                put("segmentIndex", segmentIndex)
                put("mode", if (anonymous) "anonymous" else "evidence")
                recoveredFrom?.let { put("recovery", JSONObject().put("interruptedSession", it)
                    .put("partial", true).put("captureTimeKnown", false)
                    .put("note", "Recovered durable packets after interruption; gaps and an unflushed tail may be missing")) }
                put("timestampUnix", timestamp / 1000)
                put("timestampIso", iso)
                put("chainSeed", JSONObject().apply {
                    saltB64?.let { put("salt", it) }
                    beaconJson?.let { put("beacon", JSONObject(it)) }
                })
                if (!anonymous) {
                    put("publicKey", CryptoEngine.getPublicKeyBase64())
                    put("signatureAlgorithm", "SHA256withECDSA")
                    put("captureParams", JSONObject().apply {
                        put("resolution", resolution)
                        put("fps", fps)
                        put("videoBitrateKbps", videoBitrateKbps)
                        put("audioSampleRate", audioSampleRate)
                        put("audioBitrateKbps", audioBitrateKbps)
                        put("source", if (recoveredFrom == null) videoBitratePolicy ?: "configured settings; actual codec format may differ"
                            else "recovered packets; original capture settings and capture time unavailable")
                    })
                    (if (recoveredFrom == null) Prefs.chainAttestation(context) else null)?.let {
                        put("deviceAttestation", JSONObject(it))
                    }
                }
                put("environment", EnvironmentCapture.capture(context, anonymous))
            }
            val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
            val segmentHash = MessageDigest.getInstance("SHA-256").apply {
                update(mp4Hash)
                telHash?.let { update(it) }
                update(IntegrityManager.sha256(metadataBytes))
            }.digest()
            val chainHash = IntegrityManager.nextHash(prev, segmentHash)
            val proof = JSONObject(metadata.toString()).apply {
                put("metadata", Base64.encodeToString(metadataBytes, Base64.NO_WRAP))
                put("segmentFilename", mp4File.name)
                put("sha256", IntegrityManager.toHex(mp4Hash))
                telHash?.let {
                    put("telemetrySha256", IntegrityManager.toHex(it))
                    put("telemetryFilename", telemetryFile!!.name)
                }
                put("segmentHash", IntegrityManager.toHex(segmentHash))
                put("prevChainHash", IntegrityManager.toHex(prev))
                put("chainHash", IntegrityManager.toHex(chainHash))
                if (!anonymous) put("deviceSignature", IntegrityManager.toBase64(CryptoEngine.signData(chainHash)))
            }
            val proofFile = File(mp4File.parent, mp4File.nameWithoutExtension + ".proof.json")
            val atomic = android.util.AtomicFile(proofFile)
            val output = atomic.startWrite()
            try {
                output.write(proof.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
                atomic.finishWrite(output)
                com.fieldnotes.recorder.secure.FileDurability.syncDirectory(proofFile.parentFile!!)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
            // Never advance the chain before a durable proof exists.
            if (recoveredFrom == null) Prefs.saveChainProgress(context, segmentIndex + 1, IntegrityManager.toHex(chainHash))
            previousChainHash = chainHash
            segmentIndex++
            OtsUpgradeManager.trackNeedsStamp(context, chainHash, mp4File.nameWithoutExtension)
            proofFile
        } catch (e: Exception) {
            Log.e(TAG, "Could not seal proof; preserve segment for recovery", e)
            null
        }
    }
}
