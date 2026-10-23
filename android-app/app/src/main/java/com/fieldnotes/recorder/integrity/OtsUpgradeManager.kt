package com.fieldnotes.recorder.integrity

import android.content.Context
import android.util.Log
import com.fieldnotes.recorder.upload.UploadQueue
import java.io.File

/**
 * Tracks OpenTimestamps state across two off-the-capture-path stages: an initial stamp
 * (chain hash → calendar server → pending .ots) and a later upgrade (pending → Bitcoin
 * anchored). Both run from TimestampWorker so calendar calls never hold up the
 * recording pipeline or evidence upload drain.
 *
 * Flow:
 *   1. ProofManager calls [trackNeedsStamp] on the capture path (cheap local marker only)
 *   2. TimestampWorker calls [tryStampAll]: stamps each marker, enqueues the
 *      pending .ots for upload, and registers it via [trackPending]
 *   3. TimestampWorker calls [tryUpgradeAll]: upgrades pending proofs once the
 *      Bitcoin tx confirms, enqueues the upgraded .ots, and removes the tracking entry
 *
 * Pending proofs are stored as small files (~200-500 bytes) in
 * [PENDING_DIR] inside the app's internal storage.  Bitcoin confirmation
 * typically takes 1-2 hours, so proofs may stay pending across multiple
 * drain cycles.
 */
object OtsUpgradeManager {

    private const val TAG = "DW-OtsUpgrade"
    private const val PENDING_DIR = "pending_ots"
    private const val NEEDS_STAMP_DIR = "pending_stamp"

    private fun writeProtected(file: File, bytes: ByteArray) {
        val encrypted = com.fieldnotes.recorder.secure.SecureFile.encryptBytes(bytes)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(encrypted); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun readProtected(file: File): ByteArray {
        val bytes = android.util.AtomicFile(file).readFully()
        return if (bytes.take(4).toByteArray().contentEquals("DWSF".toByteArray()))
            com.fieldnotes.recorder.secure.SecureFile.decryptBytes(bytes) else bytes
    }

    /** Remove pending entries older than 7 days — if not confirmed by then,
     *  the calendar server is unlikely to ever anchor this proof. */
    private const val STALE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private fun pendingDir(ctx: Context): File =
        File(ctx.filesDir, PENDING_DIR).also { it.mkdirs() }

    private fun stampDir(ctx: Context): File =
        File(ctx.filesDir, NEEDS_STAMP_DIR).also { it.mkdirs() }

    /**
     * Record that a segment's chain hash needs an initial OpenTimestamps stamp. This is a
     * cheap local 32-byte write with no network, safe to call on the capture path; the
     * actual calendar request is performed later by [tryStampAll] from the UploadWorker.
     */
    fun trackNeedsStamp(ctx: Context, chainHash: ByteArray, baseName: String) {
        try {
            writeProtected(File(stampDir(ctx), "$baseName.stamp"), chainHash)
            Log.d(TAG, "Recorded needs-stamp: $baseName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record needs-stamp for $baseName", e)
        }
    }

    /**
     * Perform the deferred initial OTS stamp for any segments awaiting one. For each,
     * submits the chain hash to the calendar servers, writes the resulting .ots, enqueues
     * it for upload, and hands it to [trackPending] for later Bitcoin-confirmation upgrade.
     * The marker is kept (and retried next drain) if the calendars are unreachable.
     *
     * Must be called only when the secure-network gate allows uploads. Returns count stamped.
     */
    fun tryStampAll(ctx: Context): Int {
        val dir = stampDir(ctx)
        val markers = dir.listFiles()?.filter { it.extension == "stamp" } ?: emptyList()
        if (markers.isEmpty()) return 0

        val active = markers.sortedBy { it.lastModified() }.take(2)

        var stamped = 0
        for (marker in active) {
            marker.setLastModified(System.currentTimeMillis())
            try {
                val chainHash = readProtected(marker)
                if (chainHash.size != 32) continue

                val otsBytes = OpenTimestampsClient.stamp(chainHash, ctx) ?: continue // keep marker, retry
                val baseName = marker.nameWithoutExtension

                // Enqueue the pending .ots for upload via a temp file.
                val tmpDir = File(ctx.filesDir, "timestamp_tmp").also { it.mkdirs() }
                val otsFile = File(tmpDir, "$baseName.ots")
                otsFile.writeBytes(otsBytes)
                com.fieldnotes.recorder.upload.DeliveryPolicy.checkActiveDestination(ctx)
                val queued = UploadQueue.enqueue(ctx, otsFile)
                otsFile.delete()
                if (queued == null) continue

                // Track for later Bitcoin-confirmation upgrade.
                trackPending(ctx, otsBytes, baseName)

                marker.delete()
                stamped++
                Log.i(TAG, "Initial OTS stamp: $baseName")
            } catch (e: Exception) {
                Log.w(TAG, "Initial stamp failed for ${marker.name}: ${e.message}")
            }
        }
        if (stamped > 0) Log.i(TAG, "Stamped $stamped segment(s) to OpenTimestamps")
        return stamped
    }

    /**
     * Save a copy of a pending OTS proof for later upgrade attempts.
     *
     * @param ctx       application context
     * @param otsBytes  raw OTS proof bytes from [OpenTimestampsClient.stamp]
     * @param baseName  segment base name (e.g., "session_20250415_120000")
     *                  used as the tracking filename
     */
    fun trackPending(ctx: Context, otsBytes: ByteArray, baseName: String) {
        try {
            val file = File(pendingDir(ctx), "$baseName.ots")
            writeProtected(file, otsBytes)
            Log.d(TAG, "Tracking pending OTS: ${file.name} (${otsBytes.size} bytes)")
        } catch (e: Exception) {
            throw java.io.IOException("Could not retain pending timestamp", e)
        }
    }

    /**
     * Attempt to upgrade all tracked pending proofs.
     * Returns the number of successfully upgraded proofs.
     *
     * @param ctx  application context (used for HTTP client + upload queue)
     */
    fun tryUpgradeAll(ctx: Context): Int {
        val dir = pendingDir(ctx)

        val pendingFiles = dir.listFiles()?.filter { it.extension == "ots" } ?: emptyList()

        if (pendingFiles.isEmpty()) return 0

        val activeFiles = pendingFiles.sortedBy { it.lastModified() }.take(2)

        Log.d(TAG, "Attempting upgrade for ${activeFiles.size} pending OTS proof(s)")
        var upgraded = 0

        for (file in activeFiles) {
            file.setLastModified(System.currentTimeMillis())
            try {
                val pendingBytes = readProtected(file)
                val upgradedBytes = OpenTimestampsClient.upgrade(pendingBytes, ctx)

                if (upgradedBytes != null) {
                    // Write to timestamp_tmp (not pending_ots) so:
                    // 1) it keeps the .ots extension for correct MIME type in upload pipeline
                    // 2) it won't be re-processed by this manager's .ots filter
                    // 3) TimestampWorker will clean it up if we crash
                    val tmpDir = File(ctx.filesDir, "timestamp_tmp").also { it.mkdirs() }
                    val upgradedFile = File(tmpDir, file.nameWithoutExtension + "_upgraded.ots")
                    upgradedFile.writeBytes(upgradedBytes)

                    com.fieldnotes.recorder.upload.DeliveryPolicy.checkActiveDestination(ctx)
                    val enqueued = UploadQueue.enqueue(ctx, upgradedFile)
                    upgradedFile.delete()

                    if (enqueued != null) {
                        Log.i(TAG, "Upgraded + enqueued: ${file.nameWithoutExtension}")
                        // Only remove tracking file after successful enqueue —
                        // if enqueue fails, we retry on the next drain cycle
                        file.delete()
                        upgraded++
                    } else {
                        Log.w(TAG, "Upgraded but enqueue failed, will retry: ${file.nameWithoutExtension}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upgrade attempt failed for ${file.name}: ${e.message}")
            }
        }

        if (upgraded > 0) {
            Log.i(TAG, "Upgraded $upgraded of ${activeFiles.size} pending OTS proofs")
        }
        return upgraded
    }

    /**
     * Number of proofs still awaiting Bitcoin confirmation.
     */
    fun pendingCount(ctx: Context): Int {
        val dir = pendingDir(ctx)
        return dir.listFiles()?.count { it.extension == "ots" } ?: 0
    }

    /**
     * Remove all tracked pending proofs (used by panic wipe).
     */
    fun clearAll(ctx: Context) {
        pendingDir(ctx).listFiles()?.forEach { it.delete() }
        stampDir(ctx).listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cleared all pending OTS tracking")
    }
}
