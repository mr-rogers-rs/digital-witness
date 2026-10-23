package com.fieldnotes.recorder.upload

import android.app.Notification
import com.fieldnotes.recorder.R
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fieldnotes.recorder.cloud.CloudUploadManager
import com.fieldnotes.recorder.util.Prefs
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Drains the encrypted upload queue, decrypting each file to a temp path
 * and uploading via the configured cloud provider or SAF.
 *
 * Resilient to process death: the queue persists on disk, so the next
 * WorkManager invocation picks up where it left off.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UploadScheduler.triggerTimestamps(applicationContext)
        return when (UploadDrain.run(applicationContext)) {
            UploadDrain.Outcome.COMPLETE -> Result.success()
            UploadDrain.Outcome.MORE -> {
                UploadScheduler.triggerDrain(applicationContext)
                Result.success()
            }
            UploadDrain.Outcome.RETRY -> Result.retry()
            UploadDrain.Outcome.STOPPED -> Result.failure()
        }
    }
}

/** Shared by the recording service and WorkManager; one owner of plaintext temps and receipts. */
object UploadDrain {
    enum class Outcome { COMPLETE, MORE, RETRY, STOPPED }
    private const val TAG = "DW-UploadWorker"
    private val drainLock = Mutex()

    suspend fun run(ctx: Context): Outcome = withContext(Dispatchers.IO) {
        drainLock.withLock {
            try { drain(ctx) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                UploadStatus.failure = R.string.upload_failed_retry
                showUploadAlert(ctx)
                Outcome.RETRY
            } finally { UploadStatus.active = false }
        }
    }

    private suspend fun drain(ctx: Context): Outcome {
        if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return Outcome.STOPPED
        UploadQueue.cleanTempDir(ctx)
        UploadQueue.recoverOrphans(ctx)
        if (UploadQueue.listPending(ctx).isEmpty()) {
            UploadStatus.failure = null
            return Outcome.COMPLETE
        }
        UploadReadiness.blocker(ctx)?.let {
            UploadStatus.failure = it
            showUploadAlert(ctx)
            return Outcome.RETRY
        }
        UploadStatus.failure = null
        var failed = false
        // Reuse provider authentication/connections throughout one bounded pass.
        val cloud = if (Prefs.uploadMethod(ctx) == "cloud") CloudUploadManager(ctx) else null
        if (cloud != null && !cloud.initialize()) {
            UploadStatus.failure = R.string.upload_failed_retry
            return Outcome.RETRY
        }
        val attempted = HashSet<String>()
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
        var more = false
        drainLoop@ do {
            val batch = UploadQueue.listPending(ctx).filter { it.absolutePath !in attempted }
            if (batch.isEmpty()) break
            for (encFile in batch) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) { more = true; break@drainLoop }
                attempted.add(encFile.absolutePath)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return Outcome.STOPPED
                var exported: File? = null
                var plaintext = false
                var delivered = false
                try {
                    UploadStatus.active = true
                    val policy = DeliveryPolicy.bind(ctx, encFile)
                    plaintext = policy.getString("mode") == "plaintext"
                    exported = DeliveryPolicy.export(ctx, encFile, policy)
                    // Old bound retries retain their original root path; new items use the grouped layout.
                    val subfolder = EvidenceLayout.validate(policy.optString("subfolder", ""))
                    delivered = withContext(DeliveryPolicy.activeDestination.asContextElement(policy.getString("target"))) {
                        DeliveryPolicy.checkActiveDestination(ctx)
                        when (Prefs.uploadMethod(ctx)) {
                            "saf" -> uploadViaSAF(ctx, exported, subfolder)
                            "cloud" -> uploadViaCloud(exported, subfolder, cloud)
                            else -> false
                        }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (delivered && !com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) {
                        UploadQueue.markUploaded(ctx, encFile)
                        val target = policy.getString("target")
                        if (UploadStatus.lastTarget != target) UploadStatus.verifiedFiles = 0
                        UploadStatus.lastTarget = target
                        UploadStatus.verifiedFiles++
                        UploadStatus.lastDeliveryWasSaf = Prefs.uploadMethod(ctx) == "saf"
                    } else { failed = true; if (UploadStatus.failure == null) UploadStatus.failure = R.string.upload_failed_retry }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    UploadStatus.failure = if (e is DestinationChangedException) R.string.upload_block_changed else R.string.upload_failed_retry
                    Log.w(TAG, "Delivery retained for retry", e)
                    if (e is UploadRetryLaterException) {
                        showUploadAlert(ctx)
                        return Outcome.RETRY
                    }
                } finally {
                    if (plaintext || delivered) exported?.delete()
                }
            }
            // Pick up parts published during the previous upload, without a WorkManager backoff.
        } while (true)
        UploadStatus.active = false
        // Calendar requests run in their own worker and never hold the evidence-drain lock.
        if (failed) showUploadAlert(ctx)
        return when {
            failed -> Outcome.RETRY
            more || UploadQueue.listPending(ctx).isNotEmpty() -> Outcome.MORE
            else -> Outcome.COMPLETE
        }
    }

    private fun showUploadAlert(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Ensure alert channel exists (service may not have created it yet)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel("dw_alerts") == null) {
                val channel = android.app.NotificationChannel(
                    "dw_alerts",
                    com.fieldnotes.recorder.util.Disguise.channelName(ctx),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = com.fieldnotes.recorder.util.Disguise.channelDescription(ctx)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    setShowBadge(false)
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, "dw_alerts")
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
            }
            // Text follows the presentation stance (honest by default, covert
            // when the calculator disguise is opted in). See util/Disguise.
            val notification = builder
                .setContentTitle(com.fieldnotes.recorder.util.Disguise.alertTitle(ctx))
                .setContentText(if (Prefs.isCalculatorDisguiseEnabled(ctx)) com.fieldnotes.recorder.util.Disguise.alertText(ctx)
                    else ctx.getString(UploadStatus.failure ?: R.string.upload_failed_retry))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(ctx))
                .build()
            nm.notify(100, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show upload alert", e)
        }
    }

    /**
     * Tap target for the alert notification. The body reads "Tap to open" but
     * previously had no `contentIntent`, so tapping it did nothing — leaving the
     * user with a warning they could not act on. Routes to the same entry point
     * the launcher uses, honouring the disguise setting, so the tap never
     * bypasses the PIN.
     */
    private fun openAppIntent(ctx: Context): android.app.PendingIntent? = try {
        val target = if (Prefs.isCalculatorDisguiseEnabled(ctx)) {
            com.fieldnotes.recorder.ui.CalculatorActivity::class.java
        } else {
            com.fieldnotes.recorder.ui.LockScreenActivity::class.java
        }
        android.app.PendingIntent.getActivity(
            ctx,
            0,
            android.content.Intent(ctx, target)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun uploadViaCloud(file: File, subfolder: String,
        cloud: CloudUploadManager?): Boolean = cloud?.uploadFile(file, subfolder) ?: false

    private fun uploadViaSAF(ctx: Context, file: File, subfolder: String): Boolean {
        val uri = Prefs.cloudFolderUri(ctx)?.let(Uri::parse) ?: return false
        val grant = ctx.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (grant?.isWritePermission != true || !grant.isReadPermission) return false
        val root = DocumentFile.fromTreeUri(ctx, uri) ?: return false
        val tree = SafEvidenceFolder.resolve(root, subfolder) ?: return false
        val expected = com.fieldnotes.recorder.integrity.IntegrityManager.sha256File(file)
        fun matches(doc: DocumentFile): Boolean { return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val stream = ctx.contentResolver.openInputStream(doc.uri) ?: return false
            stream.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    if (bytes > file.length()) return false
                    digest.update(buffer, 0, n)
                }
            }
            bytes == file.length() && java.security.MessageDigest.isEqual(digest.digest(), expected)
        } catch (_: Exception) { false } }

        var target = tree.findFile(file.name)
        if (target != null && !target.isFile) return false
        if (target != null && matches(target)) return true
        if (target != null && !target.delete()) return false
        val mime = when (file.extension) {
            "mp4" -> "video/mp4"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        target = tree.createFile(mime, file.name) ?: return false
        return try {
            val stream = ctx.contentResolver.openOutputStream(target.uri, "w") ?: return false
            val copied = stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            if (copied != file.length() || !matches(target)) {
                target.delete()
                false
            } else true // Provider-visible bytes verified; not a remote cloud receipt.
        } catch (e: Exception) {
            target.delete()
            false
        }
    }
}
