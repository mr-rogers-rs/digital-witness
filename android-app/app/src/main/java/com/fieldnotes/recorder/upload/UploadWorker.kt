package com.fieldnotes.recorder.upload

import android.app.Notification
import com.fieldnotes.recorder.R
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel

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
    // Bounded process-local tuning: a worker restart must not immediately undo fallback.
    private val tuning = LinkedHashMap<String, TransferTuning>()

    suspend fun run(ctx: Context): Outcome = withContext(Dispatchers.IO) {
        drainLock.withLock {
            try { drain(ctx) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                UploadStatus.failure = if (e is DestinationChangedException) R.string.upload_block_changed else R.string.upload_failed_retry
                showUploadAlert(ctx)
                Outcome.RETRY
            } finally {
                UploadStatus.active = false
                runCatching { UploadMeasurements.finish(ctx) }
            }
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
        UploadMeasurements.begin(Prefs.uploadMethod(ctx))
        var failed = false
        // Reuse provider authentication/connections throughout one bounded pass.
        val target = DeliveryPolicy.target(ctx)
        val cloud = if (Prefs.uploadMethod(ctx) == "cloud") CloudUploadManager(ctx) else null
        val initialized = withContext(DeliveryPolicy.activeDestination.asContextElement(target)) {
            DeliveryPolicy.checkActiveDestination(ctx)
            cloud?.initialize() ?: true
        }
        if (!initialized) {
            UploadStatus.failure = R.string.upload_failed_retry
            return Outcome.RETRY
        }
        val attempted = HashSet<String>()
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
        var more = false
        val tuner = tuning.getOrPut(target) { TransferTuning() }
        while (tuning.size > 8) tuning.remove(tuning.keys.first())
        tuner.begin(android.os.SystemClock.elapsedRealtime())
        val preparationLock = Mutex()
        val safDirectoryLock = Mutex()
        val safDirectories = HashMap<String, DocumentFile>()
        var stopNew = false
        var wiped = false
        coroutineScope {
            // Only the coordinator claims files and commits receipts. Children own distinct temp paths.
            val completions = Channel<TransferResult>(2)
            var running = 0
            val activeNames = HashSet<String>()
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                while (!stopNew && running < tuner.limit) {
                    if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) { wiped = true; stopNew = true; break }
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) { more = true; stopNew = true; break }
                    val next = UploadQueue.listPending(ctx).firstOrNull {
                        it.absolutePath !in attempted && it.name !in activeNames
                    } ?: break
                    attempted.add(next.absolutePath)
                    activeNames.add(next.name)
                    running++
                    UploadMeasurements.concurrency(running)
                    UploadStatus.active = true
                    launch(Dispatchers.IO) {
                        try {
                            completions.send(transfer(ctx, next, target, cloud, preparationLock, safDirectoryLock, safDirectories))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            this@coroutineScope.cancel(e)
                            throw e
                        }
                    }
                }
                if (running == 0) break
                val result = completions.receive()
                running--
                activeNames.remove(result.queued.name)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) { wiped = true; stopNew = true }
                try {
                    if (result.delivered && !wiped) {
                        val commitStarted = android.os.SystemClock.elapsedRealtime()
                        withContext(DeliveryPolicy.activeDestination.asContextElement(target)) {
                            DeliveryPolicy.checkActiveDestination(ctx)
                            UploadQueue.markUploaded(ctx, result.queued)
                        }
                        if (UploadStatus.lastTarget != target) UploadStatus.verifiedFiles = 0
                        UploadStatus.lastTarget = target
                        UploadStatus.verifiedFiles++
                        UploadStatus.lastDeliveryWasSaf = Prefs.uploadMethod(ctx) == "saf"
                        tuner.completed(result.size, android.os.SystemClock.elapsedRealtime())
                        UploadMeasurements.add("commit", android.os.SystemClock.elapsedRealtime() - commitStarted)
                        UploadMeasurements.confirmed(result.size)
                    } else if (!wiped) {
                        failed = true
                        UploadMeasurements.failed()
                        tuner.failed(android.os.SystemClock.elapsedRealtime())
                        val changed = result.error is DestinationChangedException
                        if (changed || UploadStatus.failure == null) UploadStatus.failure =
                            if (changed) R.string.upload_block_changed else R.string.upload_failed_retry
                        if (changed || result.error is UploadRetryLaterException) stopNew = true
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) {
                    failed = true
                    UploadMeasurements.failed()
                    tuner.failed(android.os.SystemClock.elapsedRealtime())
                    UploadStatus.failure = if (e is DestinationChangedException) R.string.upload_block_changed else R.string.upload_failed_retry
                    if (e is DestinationChangedException) stopNew = true
                }
            }
            completions.close()
        }
        UploadStatus.active = false
        // Calendar requests run in their own worker and never hold the evidence-drain lock.
        if (failed) showUploadAlert(ctx)
        return when {
            wiped -> Outcome.STOPPED
            failed -> Outcome.RETRY
            more || UploadQueue.listPending(ctx).isNotEmpty() -> Outcome.MORE
            else -> Outcome.COMPLETE
        }
    }

    private data class TransferResult(val queued: File, val size: Long, val delivered: Boolean, val error: Exception? = null)

    private suspend fun transfer(ctx: Context, queued: File, target: String, cloud: CloudUploadManager?,
        preparationLock: Mutex, directoryLock: Mutex, directories: MutableMap<String, DocumentFile>): TransferResult {
        var exported: File? = null
        var plaintext = false
        var delivered = false
        val size = queued.length()
        val serviceBackoff = java.util.concurrent.atomic.AtomicBoolean()
        try {
            return withContext(DeliveryPolicy.activeDestination.asContextElement(target) +
                TransferTuning.serviceBackoff.asContextElement(serviceBackoff)) {
                // Serial preparation bounds disk/crypto pressure; overlaps the other slot's network IO.
                val policy = preparationLock.withLock {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress)
                    DeliveryPolicy.checkActiveDestination(ctx)
                    val prepareStarted = android.os.SystemClock.elapsedRealtime()
                    val bound = DeliveryPolicy.bind(ctx, queued)
                    plaintext = bound.getString("mode") == "plaintext"
                    exported = DeliveryPolicy.export(ctx, queued, bound)
                    UploadMeasurements.add("prepare", android.os.SystemClock.elapsedRealtime() - prepareStarted)
                    bound
                }
                val file = checkNotNull(exported)
                val subfolder = EvidenceLayout.validate(policy.optString("subfolder", ""))
                DeliveryPolicy.checkActiveDestination(ctx)
                delivered = when (Prefs.uploadMethod(ctx)) {
                    "saf" -> uploadViaSAF(ctx, file, subfolder, directoryLock, directories)
                    "cloud" -> uploadViaCloud(file, subfolder, cloud)
                    else -> false
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                DeliveryPolicy.checkActiveDestination(ctx)
                if (!delivered && serviceBackoff.get()) throw UploadRetryLaterException()
                TransferResult(queued, size, delivered)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.w(TAG, "Delivery retained for retry", e)
            return TransferResult(queued, size, false, e)
        } finally {
            if (plaintext || delivered) exported?.delete()
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
        cloud: CloudUploadManager?): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        return try { cloud?.uploadFile(file, subfolder) ?: false }
        finally { UploadMeasurements.add("cloud", android.os.SystemClock.elapsedRealtime() - start) }
    }

    private suspend fun uploadViaSAF(ctx: Context, file: File, subfolder: String,
        directoryLock: Mutex, directories: MutableMap<String, DocumentFile>): Boolean {
        val uri = Prefs.cloudFolderUri(ctx)?.let(Uri::parse) ?: return false
        val grant = ctx.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (grant?.isWritePermission != true || !grant.isReadPermission) return false
        val directoryStarted = android.os.SystemClock.elapsedRealtime()
        val tree = try { directoryLock.withLock {
            DeliveryPolicy.checkActiveDestination(ctx)
            directories[subfolder] ?: run {
                val root = DocumentFile.fromTreeUri(ctx, uri) ?: return@withLock null
                SafEvidenceFolder.resolve(ctx, root, subfolder)?.also { directories[subfolder] = it }
            }
        } } finally { UploadMeasurements.add("lookup", android.os.SystemClock.elapsedRealtime() - directoryStarted) } ?: return false
        val hashStarted = android.os.SystemClock.elapsedRealtime()
        val expected = com.fieldnotes.recorder.integrity.IntegrityManager.sha256File(file)
        UploadMeasurements.add("prepare", android.os.SystemClock.elapsedRealtime() - hashStarted)
        fun matches(uri: Uri): Boolean {
            val start = android.os.SystemClock.elapsedRealtime()
            return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val stream = ctx.contentResolver.openInputStream(uri) ?: return false
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
        } catch (_: Exception) { false }
            finally { UploadMeasurements.add("verify", android.os.SystemClock.elapsedRealtime() - start) }
        }

        DeliveryPolicy.checkActiveDestination(ctx)
        val lookupStarted = android.os.SystemClock.elapsedRealtime()
        val found = try { SafDocumentLookup.find(ctx, tree.uri, file.name) }
            finally { UploadMeasurements.add("lookup", android.os.SystemClock.elapsedRealtime() - lookupStarted) }
        if (found != null && !found.isFile) return false
        var target = found?.uri
        if (target != null && matches(target)) return true
        if (target != null && !DocumentsContract.deleteDocument(ctx.contentResolver, target)) return false
        val mime = when (file.extension) {
            "mp4" -> "video/mp4"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        target = DocumentsContract.createDocument(ctx.contentResolver, tree.uri, mime, file.name) ?: return false
        return try {
            DeliveryPolicy.checkActiveDestination(ctx)
            val writeStarted = android.os.SystemClock.elapsedRealtime()
            val copied = try {
                val stream = ctx.contentResolver.openOutputStream(target, "w") ?: return false
                stream.use { out -> file.inputStream().use { it.copyTo(out, 65536) } }
            } finally { UploadMeasurements.add("write", android.os.SystemClock.elapsedRealtime() - writeStarted) }
            if (copied != file.length() || !matches(target)) {
                DocumentsContract.deleteDocument(ctx.contentResolver, target)
                false
            } else true // Provider-visible bytes verified; not a remote cloud receipt.
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, target) }
            false
        }
    }
}
