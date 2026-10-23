package com.fieldnotes.recorder.util

import com.fieldnotes.recorder.R
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.fieldnotes.recorder.secure.SecurePrefs
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Prefs {
    private const val TAG = "DW-Prefs"
    private const val FILE = "dw_prefs_encrypted"
    private const val KEY_CRYPTO_MIGRATED = "crypto_migrated_v2"
    private const val ANDROIDX_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    private const val KEY_VIDEO = "video_enabled"
    private const val KEY_SEQ = "global_seq"
    private const val KEY_CLOUD = "cloud_uri"
    private const val KEY_WAKELOCK = "wakelock_enabled"
    private const val KEY_SEGMENT_SECS = "segment_secs"
    private const val KEY_VIDEO_QUALITY = "video_quality"
    private const val KEY_VIDEO_PRESET = "video_preset"
    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_VIDEO_MAX_HEIGHT = "video_max_height"
    private const val KEY_VIDEO_FPS = "video_fps"
    private const val KEY_VIDEO_BITRATE_MODE = "video_bitrate_mode"
    private const val KEY_VIDEO_TARGET_KBPS = "video_target_kbps"
    private const val KEY_VIDEO_BPP_MILLIS = "video_bpp_millis"
    private const val KEY_VIDEO_GOP_SECONDS = "video_gop_seconds"
    private const val KEY_VIDEO_PROFILE = "video_profile"
    private const val KEY_VIDEO_STABILIZATION = "video_stabilization"
    private const val KEY_VIDEO_DYNAMIC_FPS = "video_dynamic_fps"
    private const val KEY_AUDIO_KBPS = "audio_kbps"
    private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
    private const val KEY_AUDIO_PROFILE = "audio_profile"

    private const val KEY_UPLOAD_METHOD = "upload_method"
    private const val KEY_CLOUD_PROVIDER = "cloud_provider"
    private const val KEY_CLOUD_ACCESS_TOKEN = "cloud_access_token"
    private const val KEY_CLOUD_REFRESH_TOKEN = "cloud_refresh_token"
    private const val KEY_CLOUD_APP_KEY = "cloud_app_key"
    private const val KEY_CLOUD_USERNAME = "cloud_username"
    private const val KEY_CLOUD_PASSWORD = "cloud_password"
    private const val KEY_CLOUD_FOLDER_PATH = "cloud_folder_path"
    private const val KEY_CLOUD_WEBDAV_URL = "cloud_webdav_url"
    // Nextcloud-specific config (kept separate from the generic WebDAV slots so
    // a user can configure both providers independently and so NC-only
    // semantics — app password, cert-pin, chunk size — stay unambiguous).
    private const val KEY_NC_BASE_URL = "nc_base_url"
    private const val KEY_NC_USERNAME = "nc_username"
    private const val KEY_NC_APP_PASSWORD = "nc_app_password"
    private const val KEY_NC_FOLDER = "nc_folder"
    private const val KEY_NC_CERT_PIN = "nc_cert_pin"
    private const val KEY_NC_CHUNK_MB = "nc_chunk_mb"
    private const val KEY_KEEP_LOCAL_COPY = "keep_local_copy"
    private const val KEY_LOCAL_ARCHIVE_MAX_MB = "local_archive_max_mb"
    private const val KEY_GPS_ENABLED = "gps_enabled"
    private const val KEY_UNLOCK_CODE = "unlock_code"
    private const val KEY_UNLOCK_CODE_LEN = "unlock_code_len"
    private const val KEY_TOR_ENABLED = "tor_enabled"
    private const val KEY_SECURE_NETWORK = "secure_network_required"
    private const val KEY_RECORDING_MODE = "recording_mode"
    private const val KEY_DURESS_CODE = "duress_code"
    private const val KEY_DURESS_CODE_LEN = "duress_code_len"
    private const val KEY_CALCULATOR_DISGUISE = "calculator_disguise"
    private const val KEY_ONBOARDING_DONE = "onboarding_complete"
    private const val KEY_UNLOCK_FAIL_COUNT = "unlock_fail_count"
    private const val KEY_UNLOCK_LOCKOUT_UNTIL = "unlock_lockout_until"
    private const val KEY_DURESS_FAIL_COUNT = "duress_fail_count"
    private const val KEY_DURESS_LOCKOUT_UNTIL = "duress_lockout_until"

    // Hash-chain / recording-session lifecycle state (A2/A3). A recording session = one
    // chain. State persists so a service recreated after a mid-recording crash resumes the
    // same chain instead of forking a new one.
    private const val KEY_CHAIN_ACTIVE = "chain_active"
    private const val KEY_CHAIN_SESSION = "chain_session"
    private const val KEY_CHAIN_INDEX = "chain_index"
    private const val KEY_CHAIN_PREV = "chain_prev"
    private const val KEY_CHAIN_SALT = "chain_salt"
    private const val KEY_CHAIN_BEACON = "chain_beacon"
    // Evidence-mode hardware attestation, generated off the capture path at session
    // start and consumed into the first proof that finds it (see ProofManager).
    private const val KEY_CHAIN_ATTESTATION = "chain_attestation"

    // PBKDF2 cost parameters. OWASP 2023 recommends ≥600,000 iterations for
    // PBKDF2-HMAC-SHA256; the previous 100k was brute-forceable on a consumer
    // GPU against a 4-digit PIN in under a second. The iteration count is
    // versioned in the stored hash (`v1:` = 100k, `v2:` = 600k) so existing
    // hashes can be transparently upgraded on the next successful unlock.
    private const val PBKDF2_ITERATIONS_V1 = 100_000
    private const val PBKDF2_ITERATIONS_V2 = 600_000
    private const val PBKDF2_ITERATIONS = PBKDF2_ITERATIONS_V2
    private const val PBKDF2_SALT_BYTES = 16
    private const val PBKDF2_HASH_BYTES = 32
    private const val HASH_PREFIX_V1 = "v1:"
    private const val HASH_PREFIX_V2 = "v2:"
    private const val HASH_PREFIX = HASH_PREFIX_V2
    private const val MAX_FAILED_ATTEMPTS = 15

    @Volatile private var cachedPrefs: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs ?: createEncryptedPrefs(ctx).also { cachedPrefs = it }
        }
    }

    /**
     * Drops the cached decrypted [SharedPreferences] reference. Called by
     * [PanicWipe] so the in-memory secrets (PIN/duress hashes, cloud tokens,
     * passwords) don't outlive a wipe and keep satisfying verify*Code() from the
     * heap. Best-effort: the underlying SecurePrefs map cannot be force-zeroed,
     * but dropping the strong reference makes it GC-eligible and forces the next
     * access to re-decrypt from disk — which fails closed once the Keystore key
     * and the on-disk blob are gone.
     */
    fun invalidateCache() {
        synchronized(this) { cachedPrefs = null }
    }

    /** Never discard evidence or keys when preferences cannot be read. */
    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        try {
            val blob = File(ctx.filesDir, "$FILE.spf")
            if (!blob.exists() && !File(ctx.filesDir, "$FILE.spf.bak").exists()) {
                val hasEvidence = listOf("upload_queue", "local_archive", "simple_capture").any {
                    File(ctx.filesDir, it).listFiles()?.isNotEmpty() == true
                }
                val hasLegacy = File(ctx.filesDir.parent, "shared_prefs/$FILE.xml").exists()
                check(!hasEvidence && !hasLegacy) {
                    "Preferences missing while evidence exists; recovery is required"
                }
            }
            return SecurePrefs.create(ctx, FILE)
        } catch (e: Exception) {
            throw SecurityException(
                "Encrypted settings unavailable. Evidence has been preserved. Retry after unlocking the device; do not clear app data.",
                e
            )
        }
    }

    /** Export confidentiality is independent of encrypted local storage. */
    fun exportMode(ctx: Context): String =
        prefs(ctx).getString("export_mode", "plaintext") ?: "unconfigured"

    fun recipientPublicKey(ctx: Context): String =
        prefs(ctx).getString("recipient_public_key", "") ?: ""

    fun setExportPolicy(ctx: Context, mode: String, publicKey: String) {
        require(mode in listOf("encrypted", "plaintext", "unconfigured"))
        prefs(ctx).edit().putString("export_mode", mode)
            .putString("recipient_public_key", publicKey).commit()
    }

    fun isLocalSaf(ctx: Context): Boolean =
        android.net.Uri.parse(cloudFolderUri(ctx) ?: "").authority == "com.android.externalstorage.documents"

    fun exportStatus(ctx: Context): String = when (exportMode(ctx)) {
        "encrypted" -> ctx.getString(R.string.export_status_encrypted)
        "plaintext" -> ctx.getString(R.string.export_status_plaintext)
        else -> ctx.getString(R.string.export_status_unconfigured)
    }

    /** True if a recording session is currently in progress (set at begin, cleared on stop). */
    fun isRecordingActive(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CHAIN_ACTIVE, false)

    /**
     * Begin a fresh recording session: new random sessionId + salt, cleared chain progress
     * and beacon. Returns the new sessionId. The non-deterministic salt (plus a later
     * drand beacon) make H_0 unguessable (audit B12).
     *
     * Uses [commit] (synchronous) rather than [apply] so a hard process kill mid-session
     * cannot lose the chain rotation — the next segment's prevChainHash must match this
     * session's seed, or the chain forks silently on resume.
     */
    fun beginRecordingSession(ctx: Context): String {
        val id = UUID.randomUUID().toString()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        check(prefs(ctx).edit()
            .putBoolean(KEY_CHAIN_ACTIVE, true)
            .putString(KEY_CHAIN_SESSION, id)
            .putInt(KEY_CHAIN_INDEX, 0)
            .remove(KEY_CHAIN_PREV)
            .putString(KEY_CHAIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .remove(KEY_CHAIN_BEACON)
            .remove(KEY_CHAIN_ATTESTATION)
            .commit()) { "Could not persist recording session" }
        return id
    }

    /** Mark the current recording session as cleanly stopped (does not erase chain state). */
    fun endRecordingSession(ctx: Context) {
        check(prefs(ctx).edit().putBoolean(KEY_CHAIN_ACTIVE, false).commit()) { "Could not persist recording stop" }
    }

    fun activeSessionId(ctx: Context): String? = prefs(ctx).getString(KEY_CHAIN_SESSION, null)
    fun chainSegmentIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_CHAIN_INDEX, 0)
    fun chainPrevHash(ctx: Context): String? = prefs(ctx).getString(KEY_CHAIN_PREV, null)
    fun chainSaltB64(ctx: Context): String? = prefs(ctx).getString(KEY_CHAIN_SALT, null)
    fun chainBeacon(ctx: Context): String? = prefs(ctx).getString(KEY_CHAIN_BEACON, null)
    fun setChainBeacon(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_CHAIN_BEACON, json).commit()
    }
    fun chainAttestation(ctx: Context): String? = prefs(ctx).getString(KEY_CHAIN_ATTESTATION, null)
    fun setChainAttestation(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_CHAIN_ATTESTATION, json).commit()
    }
    fun clearChainAttestation(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CHAIN_ATTESTATION).commit()
    }
    /**
     * Persist the advanced chain head so a post-crash resume continues from here.
     * Synchronous [commit] — losing this write to async flush would fork the chain
     * on resume (the next segment's prevChainHash wouldn't match the on-disk segment).
     */
    fun saveChainProgress(ctx: Context, nextIndex: Int, prevHashHex: String) {
        check(prefs(ctx).edit().putInt(KEY_CHAIN_INDEX, nextIndex).putString(KEY_CHAIN_PREV, prevHashHex).commit()) {
            "Could not persist evidence chain progress"
        }
    }

    fun isVideoEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIDEO, true)

    fun isAudioEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("audio_enabled", true)

    fun setAudioEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("audio_enabled", enabled).apply()
    }

    fun setVideoEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VIDEO, enabled).apply()
    }

    fun nextSeq(ctx: Context): Long {
        return synchronized(this) {
            val sp = prefs(ctx)
            val curr = sp.getLong(KEY_SEQ, 0L)
            val next = curr + 1
            sp.edit().putLong(KEY_SEQ, next).apply()
            next
        }
    }

    fun cloudFolderUri(ctx: Context): String? =
        prefs(ctx).getString(KEY_CLOUD, null)

    fun setCloudFolderUri(ctx: Context, uri: String?) {
        val ed = prefs(ctx).edit()
        if (uri == null) ed.remove(KEY_CLOUD) else ed.putString(KEY_CLOUD, uri)
        ed.apply()
    }

    fun isWakelockEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WAKELOCK, true)

    fun setWakelockEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WAKELOCK, enabled).apply()
    }

    fun segmentDurationSeconds(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_SEGMENT_SECS, 10)
        return if (v < 5) 10 else v
    }

    fun setSegmentDurationSeconds(ctx: Context, secs: Int) {
        val safe = secs.coerceIn(5, 600)
        prefs(ctx).edit().putInt(KEY_SEGMENT_SECS, safe).apply()
    }

    fun videoQuality(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_VIDEO_QUALITY, "high") ?: "high"
        return if (v.lowercase() in listOf("standard", "high", "maximum")) v.lowercase() else "high"
    }

    fun setVideoQuality(ctx: Context, quality: String) {
        val v = if (quality.lowercase() in listOf("standard", "high", "maximum")) quality.lowercase() else "high"
        prefs(ctx).edit().putString(KEY_VIDEO_QUALITY, v).apply()
    }

    @JvmOverloads
    fun applyVideoPreset(ctx: Context, normalFps: Int = 30) {
        val preset = videoPreset(ctx)
        if (preset == "custom") return
        setVideoFps(ctx, when (preset) { "battery" -> 15; "minimum" -> 10; else -> normalFps })
        // Bitrate is an independent user choice, preserved across FPS presets.
        // A video power preset must never change normal audio or camera resolution.
    }

    fun videoPreset(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_VIDEO_PRESET, "balanced") ?: "balanced"
        return if (v.lowercase() in listOf("battery", "balanced", "minimum", "custom")) v.lowercase() else "balanced"
    }

    fun setVideoPreset(ctx: Context, preset: String) {
        val v = if (preset.lowercase() in listOf("battery", "balanced", "minimum", "custom")) preset.lowercase() else "balanced"
        prefs(ctx).edit().putString(KEY_VIDEO_PRESET, v).apply()
    }

    fun videoCodec(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_VIDEO_CODEC, "auto") ?: "auto"
        return if (v.lowercase() in listOf("auto", "h264", "hevc")) v.lowercase() else "auto"
    }

    fun setVideoCodec(ctx: Context, codec: String) {
        val v = if (codec.lowercase() in listOf("auto", "h264", "hevc")) codec.lowercase() else "auto"
        prefs(ctx).edit().putString(KEY_VIDEO_CODEC, v).apply()
    }

    fun cameraBackend(ctx: Context): String =
        if (prefs(ctx).getString("camera_backend", "camera2") == "camerax") "camerax" else "camera2"

    fun setCameraBackend(ctx: Context, backend: String) {
        require(backend in listOf("camera2", "camerax"))
        check(prefs(ctx).edit().putString("camera_backend", backend).commit())
    }

    fun cameraSourceResolution(ctx: Context, cameraId: String): Pair<Int, Int>? {
        val value = prefs(ctx).getString("camera_source_${cameraBackend(ctx)}_$cameraId", null) ?: return null
        val parts = value.split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    fun setCameraSourceResolution(ctx: Context, cameraId: String, size: Pair<Int, Int>) {
        require(size.first > 0 && size.second > 0)
        check(prefs(ctx).edit().putString("camera_source_${cameraBackend(ctx)}_$cameraId", "${size.first}x${size.second}").commit())
    }

    // Full scene preservation is mandatory; legacy stabilization preferences cannot crop it.
    fun isFullCameraView(ctx: Context): Boolean = true

    fun setFullCameraView(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("video_full_view", enabled).apply()
    }

    fun calibratedResolution(ctx: Context, cameraId: String): Pair<Int, Int>? {
        val parts = prefs(ctx).getString("calibrated_resolution", "")?.split(":") ?: return null
        if (parts.size != 3 || parts[0] != cameraId) return null
        val w = parts[1].toIntOrNull() ?: return null
        val h = parts[2].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    fun setCalibratedResolution(ctx: Context, cameraId: String, width: Int, height: Int) {
        require(width > 0 && height > 0)
        check(prefs(ctx).edit().putString("calibrated_resolution", "$cameraId:$width:$height").commit())
    }

    fun clearCalibratedResolution(ctx: Context) {
        check(prefs(ctx).edit().remove("calibrated_resolution").commit())
    }

    fun verifiedNativeResolution(ctx: Context, cameraId: String, signature: String): Pair<Int, Int>? {
        val key = "native_view_v1_${cameraBackend(ctx)}_$cameraId"
        if (prefs(ctx).getString(key + "_signature", null) != signature) return null
        val parts = prefs(ctx).getString(key, "")!!.split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    fun setVerifiedNativeResolution(ctx: Context, cameraId: String, size: Pair<Int, Int>, signature: String) {
        require(size.first > 0 && size.second > 0)
        val key = "native_view_v1_${cameraBackend(ctx)}_$cameraId"
        check(prefs(ctx).edit().putString(key, "${size.first}x${size.second}").putString(key + "_signature", signature).commit())
    }

    fun clearVerifiedNativeResolution(ctx: Context, cameraId: String) {
        val key = "native_view_v1_${cameraBackend(ctx)}_$cameraId"
        check(prefs(ctx).edit().remove(key).remove(key + "_signature").commit())
    }

    fun videoMaxHeight(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_VIDEO_MAX_HEIGHT, 720)
        if (v == 0) return 0 // 0 = full sensor, no cap
        return v.coerceIn(240, 2160)
    }

    fun setVideoMaxHeight(ctx: Context, height: Int) {
        if (height == 0) {
            prefs(ctx).edit().putInt(KEY_VIDEO_MAX_HEIGHT, 0).apply() // full sensor
        } else {
            prefs(ctx).edit().putInt(KEY_VIDEO_MAX_HEIGHT, height.coerceIn(240, 2160)).apply()
        }
    }

    fun videoFps(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_VIDEO_FPS, 24)
        return v.coerceIn(10, 30)
    }

    fun setVideoFps(ctx: Context, fps: Int) {
        prefs(ctx).edit().putInt(KEY_VIDEO_FPS, fps.coerceIn(10, 30)).apply()
    }

    fun videoBitrateMode(ctx: Context): String {
        val fallback = "target"
        val v = prefs(ctx).getString(KEY_VIDEO_BITRATE_MODE, fallback) ?: fallback
        return if (v.lowercase() in listOf("auto", "target", "bpp")) v.lowercase() else "auto"
    }

    fun setVideoBitrateMode(ctx: Context, mode: String) {
        val v = if (mode.lowercase() in listOf("auto", "target", "bpp")) mode.lowercase() else "auto"
        prefs(ctx).edit().putString(KEY_VIDEO_BITRATE_MODE, v).apply()
    }

    fun videoTargetKbps(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_VIDEO_TARGET_KBPS, 2000)
        return v.coerceIn(200, 50000)
    }

    fun adaptiveVideoBitrate(ctx: Context): Boolean =
        prefs(ctx).getBoolean("adaptive_video_bitrate", true)

    fun setAdaptiveVideoBitrate(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("adaptive_video_bitrate", enabled).apply()
    }

    fun setVideoTargetKbps(ctx: Context, kbps: Int) {
        prefs(ctx).edit().putInt(KEY_VIDEO_TARGET_KBPS, kbps.coerceIn(200, 50000)).apply()
    }

    fun videoBppMillis(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_VIDEO_BPP_MILLIS, 60)
        return v.coerceIn(10, 300)
    }

    fun setVideoBppMillis(ctx: Context, millis: Int) {
        prefs(ctx).edit().putInt(KEY_VIDEO_BPP_MILLIS, millis.coerceIn(10, 300)).apply()
    }

    fun videoGopSeconds(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_VIDEO_GOP_SECONDS, 2)
        return v.coerceIn(1, 15)
    }

    fun setVideoGopSeconds(ctx: Context, seconds: Int) {
        prefs(ctx).edit().putInt(KEY_VIDEO_GOP_SECONDS, seconds.coerceIn(1, 15)).apply()
    }

    fun videoProfile(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_VIDEO_PROFILE, "main") ?: "main"
        return if (v.lowercase() in listOf("baseline", "main", "high")) v.lowercase() else "main"
    }

    fun setVideoProfile(ctx: Context, profile: String) {
        val v = if (profile.lowercase() in listOf("baseline", "main", "high")) profile.lowercase() else "main"
        prefs(ctx).edit().putString(KEY_VIDEO_PROFILE, v).apply()
    }

    fun isVideoStabilizationEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIDEO_STABILIZATION, true)

    fun setVideoStabilizationEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VIDEO_STABILIZATION, enabled).apply()
    }

    fun isVideoDynamicFpsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIDEO_DYNAMIC_FPS, false)

    fun setVideoDynamicFpsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VIDEO_DYNAMIC_FPS, enabled).apply()
    }

    fun audioKbps(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_AUDIO_KBPS, 32)
        return v.coerceIn(12, 128)
    }

    fun setAudioKbps(ctx: Context, kbps: Int) {
        prefs(ctx).edit().putInt(KEY_AUDIO_KBPS, kbps.coerceIn(12, 128)).apply()
    }

    fun audioSampleRate(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_AUDIO_SAMPLE_RATE, 16000)
        return if (v in listOf(8000, 12000, 16000, 22050, 32000)) v else 16000
    }

    fun setAudioSampleRate(ctx: Context, rate: Int) {
        val safe = if (rate in listOf(8000, 12000, 16000, 22050, 32000)) rate else 16000
        prefs(ctx).edit().putInt(KEY_AUDIO_SAMPLE_RATE, safe).apply()
    }

    fun audioProfile(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_AUDIO_PROFILE, "aac_lc") ?: "aac_lc"
        return if (v.lowercase() in listOf("aac_lc", "he_aac")) v.lowercase() else "aac_lc"
    }

    fun setAudioProfile(ctx: Context, profile: String) {
        val v = if (profile.lowercase() in listOf("aac_lc", "he_aac")) profile.lowercase() else "aac_lc"
        prefs(ctx).edit().putString(KEY_AUDIO_PROFILE, v).apply()
    }

    fun uploadMethod(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_UPLOAD_METHOD, "saf") ?: "saf"
        return if (v.lowercase() in listOf("saf", "cloud")) v.lowercase() else "saf"
    }

    fun setUploadMethod(ctx: Context, method: String) {
        val v = if (method.lowercase() in listOf("saf", "cloud")) method.lowercase() else "saf"
        prefs(ctx).edit().putString(KEY_UPLOAD_METHOD, v).apply()
    }

    fun cloudProvider(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_CLOUD_PROVIDER, "dropbox") ?: "dropbox"
        return if (v.lowercase() in listOf("dropbox", "google_drive", "mega", "webdav", "nextcloud")) v.lowercase() else "dropbox"
    }

    fun setCloudProvider(ctx: Context, provider: String) {
        val v = if (provider.lowercase() in listOf("dropbox", "google_drive", "mega", "webdav", "nextcloud")) provider.lowercase() else "dropbox"
        val storage = prefs(ctx)
        val old = cloudProvider(ctx)
        val edit = storage.edit()
        for (key in listOf(KEY_CLOUD_ACCESS_TOKEN, KEY_CLOUD_REFRESH_TOKEN, KEY_CLOUD_APP_KEY)) {
            val legacy = storage.getString(key, null)
            if (legacy != null && !storage.contains(old + "_" + key)) edit.putString(old + "_" + key, legacy)
            edit.remove(key)
        }
        edit.putString(KEY_CLOUD_PROVIDER, v).commit()
    }

    private fun oauthKey(ctx: Context, key: String) = cloudProvider(ctx) + "_" + key
    fun cloudCredentialFor(ctx: Context, provider: String, kind: String): String? {
        val key = when(kind) {
            "access" -> KEY_CLOUD_ACCESS_TOKEN
            "refresh" -> KEY_CLOUD_REFRESH_TOKEN
            "app" -> KEY_CLOUD_APP_KEY
            else -> error("Unknown credential")
        }
        return prefs(ctx).getString(provider + "_" + key, null)
            ?: if (provider == cloudProvider(ctx)) prefs(ctx).getString(key, null) else null
    }
    fun oauthPending(ctx: Context): String? = prefs(ctx).getString("dropbox_pkce_pending", null)
    fun setOauthPending(ctx: Context, value: String?) {
        prefs(ctx).edit().putString("dropbox_pkce_pending", value).commit()
    }

    fun cloudAccessToken(ctx: Context): String? =
        prefs(ctx).getString(oauthKey(ctx, KEY_CLOUD_ACCESS_TOKEN), null) ?: prefs(ctx).getString(KEY_CLOUD_ACCESS_TOKEN, null)

    fun setCloudAccessTokenFor(ctx: Context, provider: String, token: String) {
        require(provider in listOf("dropbox", "google_drive", "mega"))
        prefs(ctx).edit().putString(provider + "_" + KEY_CLOUD_ACCESS_TOKEN, token).commit()
    }

    fun setCloudAccessToken(ctx: Context, token: String?) {
        val ed = prefs(ctx).edit()
        ed.remove(KEY_CLOUD_ACCESS_TOKEN)
        if (token == null) ed.remove(oauthKey(ctx, KEY_CLOUD_ACCESS_TOKEN)) else ed.putString(oauthKey(ctx, KEY_CLOUD_ACCESS_TOKEN), token)
        ed.apply()
    }

    fun cloudRefreshToken(ctx: Context): String? =
        prefs(ctx).getString(oauthKey(ctx, KEY_CLOUD_REFRESH_TOKEN), null) ?: prefs(ctx).getString(KEY_CLOUD_REFRESH_TOKEN, null)

    fun setCloudRefreshToken(ctx: Context, token: String?) {
        val ed = prefs(ctx).edit()
        ed.remove(KEY_CLOUD_REFRESH_TOKEN)
        if (token == null) ed.remove(oauthKey(ctx, KEY_CLOUD_REFRESH_TOKEN)) else ed.putString(oauthKey(ctx, KEY_CLOUD_REFRESH_TOKEN), token)
        ed.apply()
    }

    fun cloudAppKey(ctx: Context): String? =
        prefs(ctx).getString(oauthKey(ctx, KEY_CLOUD_APP_KEY), null) ?: prefs(ctx).getString(KEY_CLOUD_APP_KEY, null)

    fun setCloudAppKey(ctx: Context, key: String?) {
        val ed = prefs(ctx).edit()
        ed.remove(KEY_CLOUD_APP_KEY)
        if (key == null) ed.remove(oauthKey(ctx, KEY_CLOUD_APP_KEY)) else ed.putString(oauthKey(ctx, KEY_CLOUD_APP_KEY), key)
        ed.apply()
    }

    fun cloudUsername(ctx: Context): String? =
        prefs(ctx).getString(KEY_CLOUD_USERNAME, null)

    fun setCloudUsername(ctx: Context, username: String?) {
        val ed = prefs(ctx).edit()
        if (username == null) ed.remove(KEY_CLOUD_USERNAME) else ed.putString(KEY_CLOUD_USERNAME, username)
        ed.apply()
    }

    fun cloudPassword(ctx: Context): String? =
        prefs(ctx).getString(KEY_CLOUD_PASSWORD, null)

    fun setCloudPassword(ctx: Context, password: String?) {
        val ed = prefs(ctx).edit()
        if (password == null) ed.remove(KEY_CLOUD_PASSWORD) else ed.putString(KEY_CLOUD_PASSWORD, password)
        ed.apply()
    }

    /**
     * Default cloud folder is intentionally innocuous. The previous default
     * "/DigitalWitness" branded the cloud account with the app's identity —
     * an adversary with cloud-storage access would immediately know what
     * the user was doing. "/Backups" is the kind of folder anyone might
     * have for any phone-backup tool.
     */
    fun cloudFolderPath(ctx: Context): String =
        prefs(ctx).getString(KEY_CLOUD_FOLDER_PATH, "/Backups") ?: "/Backups"

    fun setCloudFolderPath(ctx: Context, path: String) {
        prefs(ctx).edit().putString(KEY_CLOUD_FOLDER_PATH, path).apply()
    }

    fun cloudWebdavUrl(ctx: Context): String? =
        prefs(ctx).getString(KEY_CLOUD_WEBDAV_URL, null)

    fun setCloudWebdavUrl(ctx: Context, url: String?) {
        val ed = prefs(ctx).edit()
        if (url == null) ed.remove(KEY_CLOUD_WEBDAV_URL) else ed.putString(KEY_CLOUD_WEBDAV_URL, url)
        ed.apply()
    }

    // ── Nextcloud ───────────────────────────────────────────────────────────

    fun ncBaseUrl(ctx: Context): String? =
        prefs(ctx).getString(KEY_NC_BASE_URL, null)

    fun setNcBaseUrl(ctx: Context, url: String?) {
        val ed = prefs(ctx).edit()
        if (url.isNullOrBlank()) ed.remove(KEY_NC_BASE_URL) else ed.putString(KEY_NC_BASE_URL, url.trim())
        ed.apply()
    }

    fun ncUsername(ctx: Context): String? =
        prefs(ctx).getString(KEY_NC_USERNAME, null)

    fun setNcUsername(ctx: Context, username: String?) {
        val ed = prefs(ctx).edit()
        if (username.isNullOrBlank()) ed.remove(KEY_NC_USERNAME) else ed.putString(KEY_NC_USERNAME, username.trim())
        ed.apply()
    }

    fun ncAppPassword(ctx: Context): String? =
        prefs(ctx).getString(KEY_NC_APP_PASSWORD, null)

    fun setNcAppPassword(ctx: Context, password: String?) {
        val ed = prefs(ctx).edit()
        if (password.isNullOrBlank()) ed.remove(KEY_NC_APP_PASSWORD) else ed.putString(KEY_NC_APP_PASSWORD, password)
        ed.apply()
    }

    /**
     * Default Nextcloud folder is "/Documents" (per audit B13) — an innocuous
     * folder anyone might have, with no app branding.
     */
    fun ncFolder(ctx: Context): String =
        prefs(ctx).getString(KEY_NC_FOLDER, "/Documents") ?: "/Documents"

    fun setNcFolder(ctx: Context, path: String?) {
        val v = path?.trim().takeUnless { it.isNullOrEmpty() } ?: "/Documents"
        prefs(ctx).edit().putString(KEY_NC_FOLDER, v).apply()
    }

    /**
     * Optional SHA-256 SPKI fingerprint (base64, OkHttp "sha256/…" form
     * accepted with or without the prefix) for pinning a self-hosted server's
     * certificate — defeats state-CA MITM on a host unknown at compile time.
     */
    fun ncCertPin(ctx: Context): String? =
        prefs(ctx).getString(KEY_NC_CERT_PIN, null)

    fun setNcCertPin(ctx: Context, pin: String?) {
        val ed = prefs(ctx).edit()
        if (pin.isNullOrBlank()) ed.remove(KEY_NC_CERT_PIN) else ed.putString(KEY_NC_CERT_PIN, pin.trim())
        ed.apply()
    }

    /** Chunk size in MB for Nextcloud resumable uploads (clamped 1..50, default 10). */
    fun ncChunkMb(ctx: Context): Int =
        prefs(ctx).getInt(KEY_NC_CHUNK_MB, 10).coerceIn(5, 50)

    fun setNcChunkMb(ctx: Context, mb: Int) {
        prefs(ctx).edit().putInt(KEY_NC_CHUNK_MB, mb.coerceIn(5, 50)).apply()
    }

    fun keepLocalCopy(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_KEEP_LOCAL_COPY, false)

    fun setKeepLocalCopy(ctx: Context, keep: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_KEEP_LOCAL_COPY, keep).apply()
    }

    fun localArchiveMaxMb(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_LOCAL_ARCHIVE_MAX_MB, 500)
        return v.coerceIn(50, 10000)
    }

    fun setLocalArchiveMaxMb(ctx: Context, mb: Int) {
        prefs(ctx).edit().putInt(KEY_LOCAL_ARCHIVE_MAX_MB, mb.coerceIn(50, 10000)).apply()
    }

    /** Returns true if the user has set an unlock code (hashed or legacy). */
    fun isUnlockCodeSet(ctx: Context): Boolean = prefs(ctx).getString(KEY_UNLOCK_CODE, null) != null

    /**
     * The configured length of the unlock code, or 0 if unset. The calculator
     * disguise only treats a digit sequence as an unlock attempt when its length
     * matches this value, so ordinary calculator use (e.g. `5000=`) cannot trip
     * the brute-force counter or trigger a panic wipe (audit C4).
     */
    fun unlockCodeLength(ctx: Context): Int = prefs(ctx).getInt(KEY_UNLOCK_CODE_LEN, 0)

    /** Store a hashed unlock code using PBKDF2 (v2 cost) with random salt. */
    fun setUnlockCode(ctx: Context, code: String) {
        val salt = ByteArray(PBKDF2_SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2Hash(code, salt, PBKDF2_ITERATIONS)
        val encoded = HASH_PREFIX + Base64.encodeToString(salt + hash, Base64.NO_WRAP)
        prefs(ctx).edit()
            .putString(KEY_UNLOCK_CODE, encoded)
            .putInt(KEY_UNLOCK_CODE_LEN, code.length)
            .commit()
    }

    /**
     * Verify a PIN against the stored unlock code hash.
     * Uses constant-time comparison and handles migration of legacy plaintext codes.
     * Returns true if the code matches, false otherwise. On a successful verify of
     * a v1 (100k-iteration) hash, transparently re-hashes with v2 (600k) cost.
     */
    fun verifyUnlockCode(ctx: Context, entered: String): Boolean {
        // Android's PBKDF2 provider rejects empty passwords. Reject before
        // every storage format, including legacy plaintext migration.
        if (entered.isEmpty()) return false
        val stored = prefs(ctx).getString(KEY_UNLOCK_CODE, null) ?: return false
        // v2 hash
        if (stored.startsWith(HASH_PREFIX_V2)) {
            return verifyHash(entered, stored.substring(HASH_PREFIX_V2.length), PBKDF2_ITERATIONS_V2)
        }
        // v1 hash — verify with old cost, then transparently upgrade to v2
        if (stored.startsWith(HASH_PREFIX_V1)) {
            val ok = verifyHash(entered, stored.substring(HASH_PREFIX_V1.length), PBKDF2_ITERATIONS_V1)
            if (ok) setUnlockCode(ctx, entered) // re-hash at v2 cost
            return ok
        }
        // Legacy plaintext code — migrate to hashed v2 on successful entry.
        // Use constant-time compare for the legacy branch too (audit M12).
        if (MessageDigest.isEqual(entered.toByteArray(Charsets.UTF_8), stored.toByteArray(Charsets.UTF_8))) {
            setUnlockCode(ctx, entered)
            return true
        }
        return false
    }

    /** True if the user has set a duress code. */
    fun isDuressCodeSet(ctx: Context): Boolean = prefs(ctx).getString(KEY_DURESS_CODE, null) != null

    /** Configured length of the duress code, or 0 if unset. See [unlockCodeLength]. */
    fun duressCodeLength(ctx: Context): Int = prefs(ctx).getInt(KEY_DURESS_CODE_LEN, 0)

    /** Store a hashed duress code using PBKDF2 (v2 cost) with random salt. */
    fun setDuressCode(ctx: Context, code: String?) {
        val ed = prefs(ctx).edit()
        if (code == null) {
            ed.remove(KEY_DURESS_CODE).remove(KEY_DURESS_CODE_LEN)
        } else {
            val salt = ByteArray(PBKDF2_SALT_BYTES)
            SecureRandom().nextBytes(salt)
            val hash = pbkdf2Hash(code, salt, PBKDF2_ITERATIONS)
            val encoded = HASH_PREFIX + Base64.encodeToString(salt + hash, Base64.NO_WRAP)
            ed.putString(KEY_DURESS_CODE, encoded).putInt(KEY_DURESS_CODE_LEN, code.length)
        }
        ed.commit()
    }

    /**
     * Verify a PIN against the stored duress code hash.
     * Uses constant-time comparison and handles migration of legacy plaintext codes.
     * On successful verify of a v1 hash, transparently re-hashes at v2 cost.
     */
    fun verifyDuressCode(ctx: Context, entered: String): Boolean {
        if (entered.isEmpty()) return false
        val stored = prefs(ctx).getString(KEY_DURESS_CODE, null) ?: return false
        if (stored.startsWith(HASH_PREFIX_V2)) {
            return verifyHash(entered, stored.substring(HASH_PREFIX_V2.length), PBKDF2_ITERATIONS_V2)
        }
        if (stored.startsWith(HASH_PREFIX_V1)) {
            val ok = verifyHash(entered, stored.substring(HASH_PREFIX_V1.length), PBKDF2_ITERATIONS_V1)
            if (ok) setDuressCode(ctx, entered)
            return ok
        }
        // Legacy plaintext duress code — migrate it
        if (MessageDigest.isEqual(entered.toByteArray(Charsets.UTF_8), stored.toByteArray(Charsets.UTF_8))) {
            setDuressCode(ctx, entered)
            return true
        }
        return false
    }

    /**
     * Record a failed unlock attempt. Returns the number of seconds the user must wait
     * before trying again (0 = can retry immediately, -1 = max attempts exceeded).
     */
    fun recordFailedUnlockAttempt(ctx: Context): Long {
        val sp = prefs(ctx)
        val count = sp.getInt(KEY_UNLOCK_FAIL_COUNT, 0) + 1
        sp.edit().putInt(KEY_UNLOCK_FAIL_COUNT, count).apply()

        // Apply the exponential lockout from the 5th failure onward and KEEP it
        // applied past MAX. The calculator-disguise path rate-limits instead of
        // wiping (a bare N-digit calculation is indistinguishable from a wrong
        // code), so the counter can climb well beyond MAX and must stay
        // rate-limited. Cap the shift exponent: `Long shl n` uses only n mod 64,
        // so an uncapped exponent would silently wrap around to a tiny delay.
        if (count >= 5) {
            val shift = (count - 5).coerceAtMost(20)
            val delayMs = minOf(60_000L shl shift, 3_600_000L)
            val until = System.currentTimeMillis() + delayMs
            sp.edit().putLong(KEY_UNLOCK_LOCKOUT_UNTIL, until).apply()
            // -1 = "threshold exceeded": the honest LockScreenActivity treats it
            // as a wipe trigger; the calculator ignores it and just enforces the
            // lockout set above.
            return if (count >= MAX_FAILED_ATTEMPTS) -1L else delayMs / 1000
        }
        return 0
    }

    /** Reset failed unlock attempts after a successful unlock. */
    fun resetFailedUnlockAttempts(ctx: Context) {
        prefs(ctx).edit()
            .putInt(KEY_UNLOCK_FAIL_COUNT, 0)
            .remove(KEY_UNLOCK_LOCKOUT_UNTIL)
            .apply()
    }

    /** Returns remaining lockout seconds (>0 = locked out, <=0 = can retry). */
    fun unlockLockoutRemaining(ctx: Context): Long {
        val until = prefs(ctx).getLong(KEY_UNLOCK_LOCKOUT_UNTIL, 0L)
        if (until == 0L) return 0
        val remaining = (until - System.currentTimeMillis()) / 1000
        return if (remaining > 0) remaining else 0
    }

    /** Returns the number of failed unlock attempts (for UI feedback). */
    fun unlockFailCount(ctx: Context): Int = prefs(ctx).getInt(KEY_UNLOCK_FAIL_COUNT, 0)

    /**
     * Record a failed duress attempt (same mechanism but separate counter).
     * Returns seconds to wait (0 = ok, -1 = max exceeded).
     */
    fun recordFailedDuressAttempt(ctx: Context): Long {
        val sp = prefs(ctx)
        val count = sp.getInt(KEY_DURESS_FAIL_COUNT, 0) + 1
        sp.edit().putInt(KEY_DURESS_FAIL_COUNT, count).apply()

        // Same exponential lockout + shift-exponent cap as the unlock counter.
        if (count >= 5) {
            val shift = (count - 5).coerceAtMost(20)
            val delayMs = minOf(60_000L shl shift, 3_600_000L)
            val until = System.currentTimeMillis() + delayMs
            sp.edit().putLong(KEY_DURESS_LOCKOUT_UNTIL, until).apply()
            return if (count >= MAX_FAILED_ATTEMPTS) -1L else delayMs / 1000
        }
        return 0
    }

    fun resetFailedDuressAttempts(ctx: Context) {
        prefs(ctx).edit()
            .putInt(KEY_DURESS_FAIL_COUNT, 0)
            .remove(KEY_DURESS_LOCKOUT_UNTIL)
            .apply()
    }

    fun duressLockoutRemaining(ctx: Context): Long {
        val until = prefs(ctx).getLong(KEY_DURESS_LOCKOUT_UNTIL, 0L)
        if (until == 0L) return 0
        return maxOf(0, (until - System.currentTimeMillis()) / 1000)
    }

    private fun pbkdf2Hash(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_HASH_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun verifyHash(entered: String, encodedSaltHash: String, iterations: Int = PBKDF2_ITERATIONS): Boolean {
        if (entered.isEmpty()) return false
        val raw = try {
            Base64.decode(encodedSaltHash, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        if (raw.size != PBKDF2_SALT_BYTES + PBKDF2_HASH_BYTES) return false
        val salt = raw.copyOfRange(0, PBKDF2_SALT_BYTES)
        val storedHash = raw.copyOfRange(PBKDF2_SALT_BYTES, raw.size)
        val enteredHash = pbkdf2Hash(entered, salt, iterations)
        return MessageDigest.isEqual(enteredHash, storedHash)
    }

    /** @deprecated Use verifyUnlockCode() instead. Returns null for hashed codes. */
    @Deprecated("Use verifyUnlockCode() for constant-time comparison", ReplaceWith("verifyUnlockCode(ctx, entered)"))
    fun unlockCode(ctx: Context): String? {
        val stored = prefs(ctx).getString(KEY_UNLOCK_CODE, null) ?: return null
        if (stored.startsWith(HASH_PREFIX)) return null
        return stored
    }

    /** @deprecated Use verifyDuressCode() instead. Returns null for hashed codes. */
    @Deprecated("Use verifyDuressCode() for constant-time comparison", ReplaceWith("verifyDuressCode(ctx, entered)"))
    fun duressCode(ctx: Context): String? {
        val stored = prefs(ctx).getString(KEY_DURESS_CODE, null) ?: return null
        if (stored.startsWith(HASH_PREFIX)) return null
        return stored
    }

    fun isGpsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_GPS_ENABLED, false)

    fun setGpsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_GPS_ENABLED, enabled).apply()
    }

    /** True if network traffic should be routed through Tor (Orbot SOCKS5). */
    fun isTorEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TOR_ENABLED, false)

    fun setTorEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TOR_ENABLED, enabled).apply()
    }

    /**
     * Require VPN or Tor to be active before allowing uploads.
     *
     * New installations allow ordinary connections. A saved VPN/Tor requirement
     * remains in effect until the user changes it in setup or Settings.
     */
    fun isSecureNetworkRequired(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SECURE_NETWORK, false)

    fun setSecureNetworkRequired(ctx: Context, required: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SECURE_NETWORK, required).apply()
    }

    /**
     * Recording mode: "evidence" preserves device/environment metadata and signed proofs.
     * "anonymous" strips device fingerprint, network operator, locale — keeps only
     * sensor readings, battery, and hash chain for integrity.
     */
    fun recordingMode(ctx: Context): String {
        // Evidence mode is the fresh-install default. Keep explicit choices;
        // malformed stored values still fall back to the more private mode.
        val initialMode = "evidence"
        val v = prefs(ctx).getString(KEY_RECORDING_MODE, initialMode) ?: "anonymous"
        return if (v in listOf("evidence", "anonymous")) v else "anonymous"
    }

    fun setRecordingMode(ctx: Context, mode: String) {
        // Same fail-closed default: reject an unrecognized mode toward anonymity.
        val v = if (mode in listOf("evidence", "anonymous")) mode else "anonymous"
        prefs(ctx).edit().putString(KEY_RECORDING_MODE, v).apply()
    }

    fun isAnonymousMode(ctx: Context): Boolean = recordingMode(ctx) == "anonymous"

    /** True once the first-run onboarding flow has been completed. */
    fun isOnboardingComplete(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingComplete(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARDING_DONE, done).commit()
    }

    /** Whether the calculator disguise launcher is enabled (default: off). */
    fun isCalculatorDisguiseEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CALCULATOR_DISGUISE, false)

    fun setCalculatorDisguiseEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CALCULATOR_DISGUISE, enabled).apply()
    }
}
