package com.fieldnotes.recorder.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fieldnotes.recorder.integrity.OtsUpgradeManager
import com.fieldnotes.recorder.network.TorManager
import com.fieldnotes.recorder.util.PanicWipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement

/** Slow calendar servers must not stall the independently verified video-upload queue. */
class TimestampWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val lock = Mutex() }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val ctx = applicationContext
            if (PanicWipe.wipeInProgress) return@withLock Result.failure()
            if (TorManager.shouldBlockUpload(ctx)) return@withLock Result.retry()
            UploadStatus.timestampActive = true
            try {
                val target = DeliveryPolicy.target(ctx)
                withContext(DeliveryPolicy.activeDestination.asContextElement(target)) {
                    // This worker owns a separate scratch directory; upload cleanup cannot remove it.
                    java.io.File(ctx.filesDir, "timestamp_tmp").listFiles()?.forEach { it.delete() }
                    val stamped = OtsUpgradeManager.tryStampAll(ctx)
                    val upgraded = OtsUpgradeManager.tryUpgradeAll(ctx)
                    if (stamped + upgraded > 0) UploadScheduler.triggerDrain(ctx)
                    if (java.io.File(ctx.filesDir, "pending_stamp").listFiles()?.any { it.extension == "stamp" } == true)
                        Result.retry() else Result.success()
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { Result.retry() }
            finally { UploadStatus.timestampActive = false }
        }
    }
}
