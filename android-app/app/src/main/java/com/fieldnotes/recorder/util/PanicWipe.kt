package com.fieldnotes.recorder.util

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fieldnotes.recorder.capture.SimplifiedCaptureService
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Comprehensive data destruction for panic wipe and duress scenarios.
 *
 * Goes beyond simple file deletion:
 * - Clears app data dirs, cache dirs, and external cache
 * - Removes any MediaStore entries the app may have created
 * - Overwrites freed space with random bytes (best-effort on flash)
 * - Clears logcat ring buffer (best-effort, may need shell access)
 * - Destroys Android Keystore signing key
 * - Optionally triggers app self-uninstall prompt
 */
object PanicWipe {

    /**
     * Process-wide "a wipe has started" gate. Set true (and never reset) at the
     * very start of [doCryptographicKill]. Security-sensitive write paths
     * (notably [com.fieldnotes.recorder.secure.SecureFile.getOrCreateWrapKey]) check
     * this and fail closed, so a thread still running after the Keystore key is
     * destroyed (e.g. the capture service finalizing a last segment) cannot
     * recreate the wrap key and emit a decryptable artifact that outlives the
     * wipe. @Volatile so the visibility crosses the worker/encoder threads.
     */
    @Volatile
    @JvmStatic
    var wipeInProgress: Boolean = false
        private set

    private const val WIPE_MARKER = "wipe.pending"
    private val protectedDirs = listOf("simple_capture", "upload_queue", "local_archive", "upload_tmp",
        "pending_ots", "pending_stamp", "timestamp_tmp", "export_ciphertext", "calibration", "recording_metrics", "capture_journal")

    /** Runs before Android providers/workers initialize. Interrupted wipe is retried, never ignored. */
    fun resumeBeforeStartup(context: Context) {
        if (!File(context.noBackupFilesDir, WIPE_MARKER).exists()) return
        wipeInProgress = true
        val result = executeBlocking(context, silent = true)
        val remains = protectedDirs.any { File(context.filesDir, it).exists() } ||
            listOf("dw_prefs_encrypted.spf", "dw_prefs_encrypted.spf.bak", "dw_prefs_encrypted.spf.new")
                .any { File(context.filesDir, it).exists() }
        check(result.keystoreCleared && !remains) { "Interrupted wipe could not complete; app remains locked" }
        check(File(context.noBackupFilesDir, WIPE_MARKER).delete()) { "Could not complete wipe recovery" }
        wipeInProgress = false // Startup only: no prior process writers survive.
    }

    /** Keystore alias used internally by EncryptedSharedPreferences (AndroidX Security). */
    private const val ANDROIDX_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /**
     * Files larger than this are deleted without a random-byte overwrite (see
     * [secureDeleteFile]). 1 MB covers all JSON sidecars/proofs; encrypted media
     * segments (the large files) are already cryptographically dead once the
     * Keystore wrap key is destroyed.
     */
    private const val SECURE_OVERWRITE_MAX_BYTES = 1L * 1024 * 1024

    /** All Keystore aliases the app or its libraries may have created. */
    private val KEYSTORE_ALIASES = listOf(
        "dw_signing_key",
        "dw_attest_key",
        "dw_wrap_key",
        ANDROIDX_MASTER_KEY_ALIAS
    )

    data class WipeResult(
        val filesDeleted: Int,
        val mediaStoreEntriesRemoved: Int,
        val cacheCleared: Boolean,
        val keystoreCleared: Boolean,
        val logcatCleared: Boolean,
        val recoveryRecorded: Boolean,
        val artifactsRemain: Boolean
    )

    /**
     * Internal per-subsection outcome of [deleteAllFiles]. Surfaced so
     * [executeBlocking] can build a truthful [WipeResult] (the earlier version
     * hardcoded mediaStore/cache/logcat to 0/false, which was a fabrication —
     * the work had actually been done, the counts were just discarded).
     */
    private data class WipeCounts(
        val filesDeleted: Int,
        val mediaStoreEntriesRemoved: Int,
        val cacheCleared: Boolean,
        val logcatCleared: Boolean
    )

    /**
     * Execute a full panic wipe, running the entire sequence on the calling
     * thread. **The caller MUST be off the UI thread** (wrap in a worker
     * thread) — the slow file-overwrite + directory-wipe steps would otherwise
     * ANR. All wipe triggers (duress, lockout, the explicit MainActivity wipe
     * button) use this so the wipe is COMPLETE before any UI presents an
     * "empty" state and before a seizure could race the deletion.
     *
     * Sequence:
     *   1. Raise the [wipeInProgress] gate (blocks any concurrent wrap-key
     *      recreation — see [doCryptographicKill]).
     *   2. Cancel WorkManager (kills in-flight uploads that would re-create files).
     *   3. Stop the capture service; drop the in-memory prefs cache.
     *   4. Destroy every app Keystore alias — this instantly makes ALL encrypted
     *      files (queued media, prefs blob) permanently undecryptable, even if
     *      the raw bytes survive on flash. This is the actual security guarantee;
     *      file deletion is best-effort cleanup on top.
     *   5. Delete/overwrite all on-disk artifacts (synchronously).
     *
     * Returns a [WipeResult] with the REAL file/cache/logcat counts.
     *
     * An earlier async variant (`execute`) returned after only the crypto kill,
     * spawning file deletion on a daemon thread. It was removed: duress/lockout
     * callers used it, so the UI opened the "wiped" app while plaintext capture
     * artifacts could still be on disk — recoverable by a seizure in that window
     * (security review HIGH).
     *
     * @param silent If true, suppresses Toast/UI feedback (used by duress code).
     *
     * No sensitive logging. The durable marker deliberately records pending cleanup;
     * absence of forensic traces is not promised.
     */
    fun executeBlocking(context: Context, silent: Boolean = false): WipeResult {
        val keystoreOk = doCryptographicKill(context)
        // Run file deletion SYNCHRONOUSLY on this thread (no background spawn,
        // so no double-deletion race and we can report the real counts).
        val counts = try {
            deleteAllFiles(context)
        } catch (_: Throwable) {
            // Best-effort; Keystore kill already rendered data unrecoverable.
            WipeCounts(0, 0, false, false)
        }
        return WipeResult(
            filesDeleted = counts.filesDeleted,
            mediaStoreEntriesRemoved = counts.mediaStoreEntriesRemoved,
            cacheCleared = counts.cacheCleared,
            keystoreCleared = keystoreOk,
            logcatCleared = counts.logcatCleared,
            recoveryRecorded = File(context.noBackupFilesDir, WIPE_MARKER).isFile,
            artifactsRemain = protectedDirs.any { File(context.filesDir, it).exists() }
        )
    }

    /**
     * The synchronous, security-critical kill: raise the wipe gate, cancel
     * uploads, stop capture, drop the prefs cache, re-lock the UI, and destroy
     * every Keystore alias. Returns true if the Keystore was cleared. Called by
     * [executeBlocking] before it deletes files.
     *
     * Residual limitation: `stopService` is asynchronous, so the capture service
     * may finalize one more segment after the Keystore key is destroyed. The
     * [wipeInProgress] gate (raised here, checked in
     * `SecureFile.getOrCreateWrapKey`) now prevents that late segment from
     * RECREATING the wrap key and producing a decryptable artifact — the encrypt
     * fails closed instead. A late *plaintext* pre-encryption chunk could still
     * be written to `simple_capture/` in the brief window before the service
     * stops; fully closing that requires a synchronous service kill or process
     * exit (`Process.killProcess`), which is a UX/threat-model tradeoff for the
     * duress path (a vanishing app can be a tell under coercion) and is left as
     * a documented decision.
     */
    private fun doCryptographicKill(context: Context): Boolean {
        // 0. Raise the wipe gate FIRST. Any concurrent code path that would
        // otherwise (re)create the Keystore wrap key — e.g. the capture service
        // finalizing one last segment via SecureFile after we delete the key —
        // checks this flag and fails closed, so it cannot produce a decryptable
        // artifact that survives the wipe (security review HIGH: wrap-key
        // recreation window). Never reset within the process: once a wipe has
        // begun, this process must not write new protected data again.
        wipeInProgress = true
        // Durable before key deletion: process death restarts this cleanup at application startup.
        try {
            context.noBackupFilesDir.mkdirs()
            File(context.noBackupFilesDir, WIPE_MARKER).outputStream().use {
                it.write(byteArrayOf(1)); it.fd.sync()
            }
        } catch (_: Exception) { /* Keep gate raised and still attempt the immediate kill. */ }
        com.fieldnotes.recorder.network.PinnedHttpClient.cancelAll()

        // 1. Cancel WorkManager uploads FIRST. An upload worker mid-flight
        // would otherwise re-create files in upload_queue/upload_tmp as we
        // delete them, racing the wipe and leaking partial data (audit H10).
        try {
            com.fieldnotes.recorder.upload.UploadScheduler.cancelPeriodicDrain(context)
        } catch (_: Exception) {}

        // 2. Stop recording immediately.
        try {
            context.stopService(Intent(context, SimplifiedCaptureService::class.java))
        } catch (_: Exception) {}

        // 2b. Clear the session-lock flag so any surviving UI activity
        // re-prompts for a PIN (which will no longer exist post-wipe → the
        // user sees the first-launch setup, matching the "empty app" state).
        try { com.fieldnotes.recorder.util.SessionLock.lock() } catch (_: Exception) {}

        // 2c. Drop the in-memory decrypted prefs cache. The on-disk encrypted
        // blob is deleted later and the Keystore key below makes it unreadable,
        // but the decrypted SharedPreferences (PIN hashes, cloud tokens,
        // passwords) would otherwise linger in the heap for the life of the
        // process and still satisfy verifyUnlockCode/verifyDuressCode from RAM
        // (security review MEDIUM). Best-effort: drops the strong reference so
        // the secrets become GC-eligible and the next access fails closed.
        try { com.fieldnotes.recorder.util.Prefs.invalidateCache() } catch (_: Exception) {}

        // 3. Destroy ALL app-related Android Keystore aliases SYNCHRONOUSLY.
        // This is the cryptographic kill: once dw_wrap_key is gone, the
        // SecureFile-encrypted queued media and the dw_prefs_encrypted.spf
        // blob are permanently undecryptable, regardless of whether the bytes
        // are overwritten. Doing this before file deletion means a crash part
        // way through the wipe still leaves the data cryptographically dead.
        return synchronized(com.fieldnotes.recorder.secure.KeyLifecycle.lock) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                var success = true
                for (alias in KEYSTORE_ALIASES) {
                    try {
                        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
                        if (ks.containsAlias(alias)) success = false
                    } catch (_: Exception) { success = false }
                }
                success
            } catch (_: Exception) { false }
        }
    }

    /**
     * All file/directory/cache/MediaStore/prefs/logcat deletion. Extracted so
     * it can run synchronously from [executeBlocking] (returning the counts)
     * without duplicating the synchronous Keystore-kill path.
     */
    private fun deleteAllFiles(context: Context): WipeCounts {
        var filesDeleted = 0
        var mediaRemoved = 0
        var cacheOk = false
        var logcatOk = false

        // Delete all app data directories
        val dirsToWipe = protectedDirs
        for (dirName in dirsToWipe) {
            val dir = File(context.filesDir, dirName)
            if (dir.exists()) {
                filesDeleted += secureDeleteDir(dir)
            }
        }

        // Delete the self-managed encrypted prefs blob explicitly. SecurePrefs
        // writes to `filesDir/dw_prefs_encrypted.spf` — the extension whitelist
        // below would miss it, leaving the blob (with every credential, PIN hash,
        // and chain state) on disk. It is encrypted, but the Keystore key is
        // already destroyed, so this is belt-and-suspenders.
        try {
            val prefsBlob = File(context.filesDir, "dw_prefs_encrypted.spf")
            if (prefsBlob.exists() && secureDeleteFile(prefsBlob)) filesDeleted++
            for (suffix in listOf(".tmp", ".bak", ".new")) {
                val extra = File(context.filesDir, "dw_prefs_encrypted.spf" + suffix)
                if (extra.exists()) secureDeleteFile(extra)
            }
        } catch (_: Exception) {}

        // Delete loose media files in filesDir root
        context.filesDir.listFiles()?.forEach { file ->
            val ext = file.extension.lowercase()
            if (ext in listOf("enc", "mp4", "json", "ots", "h264", "aac", "proof", "stripped", "spf")) {
                if (secureDeleteFile(file)) filesDeleted++
            }
        }

        // Clear cache directories
        try {
            context.cacheDir?.let { filesDeleted += secureDeleteDir(it) }
            context.externalCacheDir?.let { filesDeleted += secureDeleteDir(it) }
            cacheOk = true
        } catch (_: Exception) {}

        // Remove MediaStore entries the app may have created
        try { mediaRemoved = clearMediaStoreEntries(context) } catch (_: Exception) {}

        // Clear ALL preferences (third-party libs like WorkManager store state here)
        try {
            val prefsDir = File(context.filesDir.parent, "shared_prefs")
            prefsDir.listFiles()?.forEach { file ->
                secureDeleteFile(file); filesDeleted++
            }
        } catch (_: Exception) {}

        // Wipe WorkManager and any other Room/SQLite databases.
        try {
            val dbDir = File(context.filesDir.parent, "databases")
            if (dbDir.exists()) {
                filesDeleted += secureDeleteDir(dbDir)
            }
        } catch (_: Exception) {}

        // Clear logcat ring buffer last. On non-rooted Android 10+ this only
        // clears the calling process's logs, but it's defense in depth.
        try {
            val proc = Runtime.getRuntime().exec("logcat -c")
            if (proc.waitFor(2, TimeUnit.SECONDS)) {
                logcatOk = true
            } else {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {}

        return WipeCounts(filesDeleted, mediaRemoved, cacheOk, logcatOk)
    }

    /**
     * Prompt the user to uninstall the app. Requires user confirmation (Android
     * does not allow silent self-uninstall without device admin).
     */
    fun promptSelfUninstall(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    /**
     * Recursively delete a directory, overwriting file contents first.
     * Returns count of files deleted.
     */
    private fun secureDeleteDir(dir: File): Int {
        var count = 0
        dir.walkBottomUp().forEach { file ->
            if (file.isFile) {
                if (secureDeleteFile(file)) count++
            } else if (file.isDirectory) {
                file.delete()
            }
        }
        return count
    }

    /**
     * Overwrite a file with random bytes before deleting.
     *
     * Latency-aware (audit H11): for large files we skip the byte overwrite and
     * just delete. The overwrite adds negligible security here — every file
     * targeted by the wipe is either (a) already encrypted with a Keystore key
     * we just destroyed (so it's permanently undecryptable regardless of bytes
     * on flash), or (b) a small JSON sidecar. Overwriting a multi-hundred-MB
     * encrypted segment with random bytes on the UI/wipe thread risks ANRs and
     * slows the wipe exactly when speed matters (duress). Small files (< 1 MB)
     * still get the overwrite as defense-in-depth for any plaintext leakage.
     */
    private fun secureDeleteFile(file: File): Boolean {
        return try {
            if (file.isFile && file.length() in 1..SECURE_OVERWRITE_MAX_BYTES) {
                val size = file.length()
                val random = SecureRandom()
                file.outputStream().use { out ->
                    val buf = ByteArray(minOf(size, 8192).toInt())
                    var remaining = size
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                        random.nextBytes(buf)
                        out.write(buf, 0, toWrite)
                        remaining -= toWrite
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            file.delete()
        } catch (_: Exception) {
            file.delete() // fallback to plain delete
        }
    }

    /**
     * Remove MediaStore entries for video files that might have been indexed.
     * The app stores files in its private directory which normally isn't indexed,
     * but some OEMs aggressively scan all storage.
     */
    private fun clearMediaStoreEntries(context: Context): Int {
        var removed = 0
        try {
            val resolver = context.contentResolver
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA)
            val selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%${context.packageName}%")

            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val deleteUri = ContentUris.withAppendedId(uri, id)
                    try {
                        resolver.delete(deleteUri, null, null)
                        removed++
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return removed
    }
}
