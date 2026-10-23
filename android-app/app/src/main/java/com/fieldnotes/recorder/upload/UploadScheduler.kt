package com.fieldnotes.recorder.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.TimeUnit

/**
 * Schedules [UploadWorker] jobs to drain the encrypted upload queue.
 *
 * Two scheduling strategies:
 *  1. **On-demand** ([triggerDrain]) – called right after a new segment is
 *     encrypted into the queue.  Requests execution without artificial delay; Android still controls scheduling.
 *  2. **Periodic** ([ensurePeriodicDrain]) – a 15-minute repeating job that
 *     catches any segments missed due to process death or OEM background kills.
 */
object UploadScheduler {

    private const val TAG = "DW-UploadSched"
    private const val ONE_SHOT_NAME = "dw_upload_drain"
    private const val PERIODIC_NAME = "dw_upload_periodic"
    private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val schedulingLock = Mutex()
    @Volatile private var liveDrain: Channel<Unit>? = null

    /** Active recording already owns a foreground service; use it to avoid worker-start latency. */
    fun startLiveDrain(ctx: Context, scope: CoroutineScope): Job? {
        val app = ctx.applicationContext
        val signal = Channel<Unit>(Channel.CONFLATED)
        liveDrain = signal
        return scope.launch {
            try {
                for (ignored in signal) {
                    var backoff = 5_000L
                    while (isActive && UploadQueue.listPending(app).isNotEmpty()) {
                        when (UploadDrain.run(app)) {
                            UploadDrain.Outcome.STOPPED -> return@launch
                            UploadDrain.Outcome.RETRY -> {
                                delay(backoff + kotlin.random.Random.nextLong(0, 1_001))
                                backoff = minOf(60_000L, backoff * 2)
                            }
                            else -> backoff = 5_000L
                        }
                    }
                }
            } finally {
                if (liveDrain === signal) liveDrain = null
                signal.close()
            }
        }.also { signal.trySend(Unit) }
    }

    private fun constraints(ctx: Context): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (com.fieldnotes.recorder.util.Prefs.uploadMethod(ctx) == "saf" &&
            com.fieldnotes.recorder.util.Prefs.isLocalSaf(ctx)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
        .build()

    /**
     * Request prompt delivery without timing jitter. APPEND_OR_REPLACE preserves
     * an in-flight upload and schedules a successor even when a segment arrives
     * just after that worker's last queue scan. Android may still defer execution.
     */
    fun triggerDrain(ctx: Context) {
        liveDrain?.trySend(Unit)
        triggerTimestamps(ctx)
        val app = ctx.applicationContext
        schedulingScope.launch {
            schedulingLock.withLock {
                if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return@withLock
                try {
                    val work = WorkManager.getInstance(app)
                    val existing = work.getWorkInfosForUniqueWork(ONE_SHOT_NAME).get()
                    // Bound the chain to the current worker plus one successor.
                    // An enqueued/backoff/blocked job has not done its next scan;
                    // it will see files added now. Only RUNNING needs a successor
                    // to cover the race after that worker's final queue scan.
                    if (existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@withLock
                    val req = OneTimeWorkRequestBuilder<UploadWorker>()
                        .setConstraints(constraints(app))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build()
                    work.enqueueUniqueWork(ONE_SHOT_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req).result.get()
                } catch (e: Exception) {
                    Log.w(TAG, "Drain scheduling failed; persistent queue retained", e)
                }
            }
        }
    }

    fun triggerTimestamps(ctx: Context) {
        if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return
        val pending = listOf("pending_stamp", "pending_ots").any { name ->
            java.io.File(ctx.filesDir, name).listFiles()?.any { it.isFile } == true
        }
        if (!pending) return
        val request = OneTimeWorkRequestBuilder<TimestampWorker>().setConstraints(constraints(ctx))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("dw_upload_timestamps", ExistingWorkPolicy.KEEP, request)
    }

    /** A fresh tail job must not inherit an old offline worker's backoff after recording stops. */
    fun finishRecordingUploads(ctx: Context) {
        triggerDrain(ctx)
        if (UploadQueue.listPending(ctx).isEmpty() ||
            com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return
        try {
            val request = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints(ctx))
                .addTag("dw_upload_tail").setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Older Android versions require a distinct foreground-worker UI for expedited work.
            if (android.os.Build.VERSION.SDK_INT >= 31)
                request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(ctx).enqueue(request.build())
        } catch (error: Exception) { Log.w(TAG, "Tail scheduling deferred to durable fallback", error) }
    }

    /** An explicit retry must also replace a previous manual job waiting in backoff.
     * Cancellation keeps the encrypted source and bound delivery state; the shared drain lock serializes owners.
     */
    fun retryNow(ctx: Context) {
        if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(ctx))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork("dw_upload_manual", ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Register a periodic drain job with flex window for timing randomization.
     * WorkManager may fire anywhere within the 5-minute flex window at the end
     * of the 15-minute interval, making exact timing unpredictable.
     */
    fun ensurePeriodicDrain(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES  // flex window — fires randomly within last 5 min
            )
            .setConstraints(constraints(ctx))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)

        Log.i(TAG, "Periodic queue drain registered (15 min, 5 min flex)")
    }

    /**
     * Cancel the periodic drain (e.g. when user disables cloud upload),
     * and also cancel any in-flight one-shot drain. The latter is needed by
     * PanicWipe: an upload worker mid-flight would otherwise re-create
     * files we're trying to destroy (audit H10).
     */
    fun cancelPeriodicDrain(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC_NAME)
        WorkManager.getInstance(ctx).cancelUniqueWork(ONE_SHOT_NAME)
        WorkManager.getInstance(ctx).cancelUniqueWork("dw_upload_manual")
        WorkManager.getInstance(ctx).cancelUniqueWork("dw_upload_timestamps")
        WorkManager.getInstance(ctx).cancelUniqueWork("dw_capture_recovery")
        WorkManager.getInstance(ctx).cancelAllWorkByTag("dw_upload_tail")
        Log.i(TAG, "Periodic + one-shot queue drains cancelled")
    }
}
