package com.fieldnotes.recorder.upload

import android.content.Context
import android.util.Log
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.Prefs
import java.io.File

/**
 * Encrypted upload queue backed by AES-256-GCM via the self-managed [SecureFile].
 *
 * Files are encrypted at rest in [QUEUE_DIR] until UploadWorker confirms a
 * successful upload.  After upload the file is either moved to [ARCHIVE_DIR]
 * (if keep_local_copy is enabled) or deleted outright.
 */
object UploadQueue {
    private val deliveredBytes = java.util.concurrent.atomic.AtomicLong()
    fun deliveredBytes(): Long = deliveredBytes.get()

    private const val TAG = "DW-UploadQueue"
    private const val QUEUE_DIR = "upload_queue"
    private const val ARCHIVE_DIR = "local_archive"
    private const val ENC_SUFFIX = ".enc"
    private const val TEMP_DIR = "upload_tmp"

    /** Pre-encryption capture scratch dir, owned by SimplifiedCaptureService. */
    private const val CAPTURE_DIR = "simple_capture"

    /** Segment output worth re-encrypting; see [recoverOrphans]. */
    private val RECOVERABLE_SUFFIXES = listOf(".mp4", ".proof.json", ".telemetry.json")


    /**
     * A file must be untouched for this long before the sweep considers it
     * orphaned, so an in-flight muxer write is never mistaken for a leftover.
     */
    private const val QUIET_PERIOD_MS = 5 * 60 * 1000L

    private fun queueDir(ctx: Context): File =
        File(ctx.filesDir, QUEUE_DIR).also { it.mkdirs() }

    private fun archiveDir(ctx: Context): File =
        File(ctx.filesDir, ARCHIVE_DIR).also { it.mkdirs() }

    private fun tempDir(ctx: Context): File =
        File(ctx.filesDir, TEMP_DIR).also { it.mkdirs() }

    /**
     * Encrypt [src] into the upload queue.
     * The cleartext [src] is **not** deleted by this method – caller decides.
     *
     * @return the encrypted file inside the queue directory, or null on failure.
     */
    fun enqueue(ctx: Context, src: File): File? {
        if (!src.exists()) {
            Log.w(TAG, "Source file does not exist: ${src.name}")
            return null
        }
        return try {
            val dest = File(queueDir(ctx), src.name + ENC_SUFFIX)
            SecureFile.encryptFile(src, dest)
            if (Prefs.exportMode(ctx) != "unconfigured") DeliveryPolicy.bind(ctx, dest)
            Log.i(TAG, "Enqueued ${src.name} (${src.length()} B) -> ${dest.name}")
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue ${src.name}", e)
            null
        }
    }

    /** Publish companions together by a single directory rename. Sources stay until success. */
    @Synchronized
    fun enqueueBundle(ctx: Context, sources: List<File>, publicationId: String? = null): List<File>? {
        val id = publicationId ?: java.util.UUID.randomUUID().toString()
        require(java.util.UUID.fromString(id).toString() == id)
        val stage = File(queueDir(ctx), ".stage-" + id)
        val committed = File(queueDir(ctx), "bundle-" + id)
        // This marker remains even if workers have already delivered every member.
        // A capture journal can therefore replay a killed queue/cursor transaction without duplicates.
        if (publicationId != null && File(committed, ".published").isFile)
            return sources.map { File(committed, it.name + ENC_SUFFIX) }
        return try {
            if (stage.exists()) check(stage.deleteRecursively())
            check(stage.mkdir())
            for (source in sources) {
                require(source.isFile && source.name == File(source.name).name)
                val encrypted = File(stage, source.name + ENC_SUFFIX)
                SecureFile.encryptFile(source, encrypted)
                if (Prefs.exportMode(ctx) != "unconfigured") DeliveryPolicy.bind(ctx, encrypted)
            }
            if (publicationId != null) File(stage, ".published").outputStream().use { it.write(1); it.fd.sync() }
            check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress)
            com.fieldnotes.recorder.secure.FileDurability.syncDirectory(stage)
            check(stage.renameTo(committed)) { "Could not publish evidence bundle" }
            com.fieldnotes.recorder.secure.FileDurability.syncDirectory(queueDir(ctx))
            sources.map { File(committed, it.name + ENC_SUFFIX) }
        } catch (e: Exception) {
            Log.e(TAG, "Bundle not committed; originals retained", e)
            null
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    fun publicationExists(ctx: Context, id: String): Boolean {
        require(java.util.UUID.fromString(id).toString() == id)
        return File(File(queueDir(ctx), "bundle-$id"), ".published").isFile
    }

    /** Only after the capture journal has durably advanced its cursor. */
    @Synchronized fun releasePublication(ctx: Context, id: String) {
        require(java.util.UUID.fromString(id).toString() == id)
        val dir = File(queueDir(ctx), "bundle-$id")
        File(dir, ".published").delete()
        if (dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    /**
     * List all encrypted files in the queue, oldest first.
     */
    fun listPending(ctx: Context): List<File> {
        val dir = queueDir(ctx)
        return (dir.listFiles() ?: emptyArray()).flatMap {
            if (it.isDirectory && it.name.startsWith("bundle-")) it.listFiles()?.toList() ?: emptyList()
            else listOf(it)
        }
            .filter { it.isFile && it.name.endsWith(ENC_SUFFIX) }
            .sortedBy { it.lastModified() }
    }

    /**
     * Decrypt an encrypted queue file into a temporary cleartext file
     * suitable for uploading.  Caller must delete the temp file when done.
     */
    fun decryptToTemp(ctx: Context, encFile: File): File? {
        if (!encFile.exists()) return null
        return try {
            val plainName = encFile.name.removeSuffix(ENC_SUFFIX)
            val tmp = File(tempDir(ctx), plainName)
            if (tmp.exists()) tmp.delete()

            SecureFile.decryptFile(encFile, tmp)
            tmp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt ${encFile.name}", e)
            null
        }
    }

    /**
     * Called after a file has been successfully uploaded.
     * Moves to local archive or deletes, depending on [Prefs.keepLocalCopy].
     */
    fun markUploaded(ctx: Context, encFile: File) {
        val deliveredSize = encFile.length()
        if (Prefs.keepLocalCopy(ctx)) {
            val dest = File(archiveDir(ctx), encFile.name)
            if (dest.exists()) dest.delete()
            if (encFile.renameTo(dest)) {
                Log.i(TAG, "Archived ${encFile.name}")
            } else {
                val atomic = android.util.AtomicFile(dest)
                val out = atomic.startWrite()
                try {
                    encFile.inputStream().use { it.copyTo(out) }
                    out.fd.sync()
                    atomic.finishWrite(out)
                } catch (error: Exception) { atomic.failWrite(out); throw error }
                check(encFile.delete())
                Log.i(TAG, "Archived ${encFile.name} (copy+delete)")
            }
            enforceArchiveSizeLimit(ctx)
        } else {
            encFile.delete()
            Log.i(TAG, "Deleted ${encFile.name} after upload")
        }
        check(!encFile.exists()) { "Could not finalize delivered queue item" }
        deliveredBytes.addAndGet(deliveredSize)
        DeliveryPolicy.removeState(encFile)
        val parent = encFile.parentFile
        if (parent?.name?.startsWith("bundle-") == true && parent.listFiles()?.isEmpty() == true) parent.delete()
    }

    /**
     * Total bytes sitting in the upload queue.
     */
    fun queueSizeBytes(ctx: Context): Long =
        listPending(ctx).sumOf { it.length() }

    /**
     * Total bytes in the local archive.
     */
    fun archiveSizeBytes(ctx: Context): Long {
        val dir = archiveDir(ctx)
        return (dir.listFiles() ?: emptyArray()).sumOf { it.length() }
    }

    /**
     * Evicts oldest archived files until the archive is within the configured limit.
     */
    private fun enforceArchiveSizeLimit(ctx: Context) {
        val limitBytes = Prefs.localArchiveMaxMb(ctx).toLong() * 1024 * 1024
        val dir = archiveDir(ctx)
        val files = (dir.listFiles() ?: emptyArray()).sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > limitBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
            Log.i(TAG, "Evicted ${oldest.name} to stay within archive limit")
        }
    }

    /**
     * Delete stale temp files left behind by interrupted uploads.
     */
    fun cleanTempDir(ctx: Context) {
        val dir = tempDir(ctx)
        (dir.listFiles() ?: emptyArray()).forEach { it.delete() }
    }

    /**
     * Sweep the pre-encryption capture scratch directory (design doc §5.5).
     *
     * `SimplifiedCaptureService` deliberately keeps a segment's CLEARTEXT
     * artifacts when encrypting them into the queue fails, so a transient
     * Keystore error can't destroy the only copy of a recording. Nothing ever
     * looked at them again, so the retained cleartext just accumulated — on a
     * real device, 97 MB of it, months old, readable by anyone who images the
     * phone. Trading total data loss for indefinite plaintext-at-rest is a bad
     * trade; this closes it by finishing the job on the next worker pass:
     *
     *  - `.mp4` / `.proof.json` / `.telemetry.json` are real segment output —
     *    retry the encryption, and delete the cleartext only once the encrypted
     *    counterpart exists (same ordering the capture path uses).
     *  - Raw .aac/.h264 intermediates are retained for explicit recovery.
     *
     * Two guards keep this away from live capture: it does nothing while a
     * recording session is active, and it ignores anything touched within
     * [QUIET_PERIOD_MS] so it can never grab a file the muxer is still writing.
     *
     * `@Synchronized` because WorkManager can have two [UploadWorker] instances
     * in flight at once (the periodic one and an on-demand one), and both would
     * sweep the same directory. Observed on-device: each picked up the same
     * orphan, both encrypted to the same `<name>.enc.tmp`, and the loser died on
     * `Atomic rename ... failed`. Nothing was lost that time — the winner had
     * already committed and the loser failed closed without deleting its source
     * — but two writers on one temp path can equally produce a torn file that
     * *does* get renamed into the queue, which for evidence is far worse than a
     * noisy log. Serializing the sweep removes the shared temp path entirely;
     * the second caller then finds the directory already drained and no-ops.
     *
     * @return the number of files re-encrypted into the queue.
     */
    @Synchronized
    fun recoverOrphans(ctx: Context): Int {
        // Never race the muxer: during a session the capture service owns this dir.
        if (Prefs.isRecordingActive(ctx) || com.fieldnotes.recorder.capture.CaptureRecovery.pending) return 0

        val dir = File(ctx.filesDir, CAPTURE_DIR)
        if (!dir.isDirectory) return 0

        val cutoff = System.currentTimeMillis() - QUIET_PERIOD_MS
        var recovered = 0
        var discarded = 0

        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.isFile || f.lastModified() > cutoff) continue

            val recoverable = RECOVERABLE_SUFFIXES.any { f.name.endsWith(it) }

            when {
                recoverable && f.length() > 0L -> {
                    // enqueue() fails closed while a panic wipe is in progress
                    // (SecureFile.getOrCreateWrapKey refuses to recreate the wrap
                    // key), so a wipe can never be undone by this sweep.
                    if (enqueue(ctx, f) != null) {
                        f.delete()
                        recovered++
                    }
                }
                f.length() == 0L -> {
                    f.delete()
                    discarded++
                }
            }
        }

        if (recovered > 0 || discarded > 0) {
            Log.i(TAG, "Orphan sweep: recovered $recovered, discarded $discarded")
        }
        return recovered
    }
}
