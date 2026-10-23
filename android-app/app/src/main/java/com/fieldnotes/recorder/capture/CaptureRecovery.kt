package com.fieldnotes.recorder.capture

import android.content.Context
import androidx.work.*
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.integrity.IntegrityManager
import com.fieldnotes.recorder.integrity.ProofManager
import com.fieldnotes.recorder.upload.DeliveryPolicy
import com.fieldnotes.recorder.upload.UploadQueue
import com.fieldnotes.recorder.upload.UploadScheduler
import com.fieldnotes.recorder.util.PanicWipe
import com.fieldnotes.recorder.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Recovery is local work. Never silently restart a camera or claim an interrupted session was continuous. */
object CaptureRecovery {
    private val lock = Mutex()
    @Volatile var pending = false; private set
    @Volatile var busy = false; private set
    @Volatile var interrupted = false; private set
    fun message(): Int? = when {
        pending && busy -> R.string.recovery_running
        pending -> R.string.recovery_failed
        interrupted -> R.string.recovery_interrupted
        else -> null
    }
    private fun journals(ctx: Context) = File(ctx.filesDir, CaptureJournal.ROOT).listFiles()
        ?.filter { it.isDirectory } ?: emptyList()

    /** Application startup has no surviving service from the old process. */
    fun initialize(ctx: Context) {
        if (PanicWipe.wipeInProgress) return
        interrupted = Prefs.isRecordingActive(ctx) || journals(ctx).isNotEmpty()
        if (Prefs.isRecordingActive(ctx)) Prefs.endRecordingSession(ctx)
        request(ctx)
    }
    fun request(ctx: Context) {
        if (PanicWipe.wipeInProgress) return
        pending = journals(ctx).isNotEmpty()
        if (!pending) return
        interrupted = true
        val job = OneTimeWorkRequestBuilder<CaptureRecoveryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("dw_capture_recovery", ExistingWorkPolicy.KEEP, job)
    }
    fun clearNotice() { interrupted = false }
    fun waitForWriters() { pending = true }

    suspend fun run(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (PanicWipe.wipeInProgress || CaptureStatus.running) return@withLock false
            busy = true
            try {
                for (dir in journals(ctx)) {
                    check(!PanicWipe.wipeInProgress)
                    // A kill before the first header commit contains no captured data.
                    if (dir.listFiles()?.isEmpty() == true) { dir.delete(); continue }
                    val journal = CaptureJournal.open(dir)
                    check(journal.state.getString("target") == DeliveryPolicy.target(ctx)) { "Recovery destination changed" }
                    journal.state.optString("lastPublication").takeIf { it.isNotEmpty() }?.let {
                        UploadQueue.releasePublication(ctx, it)
                        removeSources(ctx, it)
                    }
                    withContext(DeliveryPolicy.activeDestination.asContextElement(journal.state.getString("target"))) {
                    while (true) {
                        check(!PanicWipe.wipeInProgress)
                        val seal = journal.state.optJSONObject("seal")
                        if (seal != null && UploadQueue.publicationExists(ctx, seal.getString("stem"))) {
                            finish(ctx, journal, seal.getString("stem")); continue
                        }
                        val remaining = journal.remaining()
                        if (remaining.isEmpty()) { check(journal.discardIfEmpty()); break }
                        val audio = remaining.filter { !it.video && (seal == null || it.pts <= seal.getLong("audio")) }
                        val video = remaining.filter { it.video && (seal == null || it.pts <= seal.getLong("video")) }
                        val stem = seal?.getString("stem") ?: UUID.randomUUID().toString().also {
                            journal.beginSeal(it, audio.lastOrNull()?.pts ?: -1L, video.lastOrNull()?.pts ?: -1L)
                        }
                        require(UUID.fromString(stem).toString() == stem)
                        val work = File(ctx.filesDir, "simple_capture").also { check(it.isDirectory || it.mkdirs()) }
                        val mp4 = File(work, "$stem.mp4")
                        val proof = File(work, "$stem.proof.json")
                        // A pre-existing proof fixes the exact MP4 bytes. Never remux it under that proof.
                        if (proof.isFile) {
                            val json = JSONObject(proof.readText())
                            check(mp4.isFile && json.getString("sha256") == IntegrityManager.toHex(IntegrityManager.sha256File(mp4)))
                        } else {
                            SegmentMuxer.write(mp4, CaptureJournal.restoreFormat(journal.state.optJSONObject("audioFormat")),
                                CaptureJournal.restoreFormat(journal.state.optJSONObject("videoFormat")),
                                audio.map { MuxSample(it.bytes, it.pts, false) },
                                video.map { MuxSample(it.bytes, it.pts, it.key) }, 900_000_000L,
                                journal.state.getInt("orientation"))
                            if (journal.state.optBoolean("anonymous", false))
                                check(com.fieldnotes.recorder.util.Mp4MetadataStripper.stripInPlace(mp4))
                            checkNotNull(ProofManager(ctx, "recovery-$stem", journal.state.getString("session"), journal.state.optBoolean("anonymous", false))
                                .generateProof(mp4, "recovered codec format", 0, 0, 0, 0))
                        }
                        val telemetry = File(work, "$stem.telemetry.json").takeIf { it.isFile }
                        // Existing proofs may bind telemetry produced just before interruption.
                        val proofJson = JSONObject(proof.readText())
                        if (proofJson.has("telemetrySha256")) check(telemetry != null &&
                            proofJson.getString("telemetrySha256") == IntegrityManager.toHex(IntegrityManager.sha256File(telemetry)))
                        checkNotNull(UploadQueue.enqueueBundle(ctx, listOfNotNull(mp4, proof,
                            telemetry?.takeIf { proofJson.has("telemetrySha256") }), stem))
                        finish(ctx, journal, stem)
                    }
                    }
                }
                pending = journals(ctx).isNotEmpty()
                UploadScheduler.triggerDrain(ctx)
                !pending
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { pending = true; false }
            finally { busy = false }
        }
    }
    private fun finish(ctx: Context, journal: CaptureJournal, stem: String) {
        journal.completeSeal()
        UploadQueue.releasePublication(ctx, stem)
        removeSources(ctx, stem)
    }
    private fun removeSources(ctx: Context, stem: String) {
        require(UUID.fromString(stem).toString() == stem)
        val work = File(ctx.filesDir, "simple_capture")
        for (suffix in listOf(".mp4", ".proof.json", ".telemetry.json", ".mp4.muxing")) File(work, stem + suffix).delete()
    }
}

class CaptureRecoveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = when {
        PanicWipe.wipeInProgress -> Result.failure()
        CaptureRecovery.run(applicationContext) -> Result.success()
        else -> Result.retry()
    }
}
