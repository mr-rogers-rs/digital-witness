package com.fieldnotes.recorder.upload

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.PanicWipe
import org.json.JSONObject
import java.io.File

/** Last nonempty drain pass: numeric stage totals only, encrypted locally; no destination or file identifiers. */
object UploadMeasurements {
    private var started = -1L
    private var method = ""
    private var confirmed = 0L
    private var failed = 0L
    private var bytes = 0L
    private var slots = 0
    private val stages = HashMap<String, Long>()
    private val knownStages = listOf("prepare", "lookup", "write", "verify", "cloud", "commit")

    @Synchronized fun begin(selectedMethod: String) {
        started = SystemClock.elapsedRealtime()
        method = if (selectedMethod == "saf") "SAF" else "API"
        confirmed = 0; failed = 0; bytes = 0; slots = 0; stages.clear()
    }
    @Synchronized fun add(stage: String, ms: Long) {
        require(stage in knownStages)
        stages[stage] = (stages[stage] ?: 0L) + ms.coerceAtLeast(0)
    }
    @Synchronized fun confirmed(size: Long) { confirmed++; bytes += size }
    @Synchronized fun failed() { failed++ }
    @Synchronized fun concurrency(count: Int) { slots = maxOf(slots, count) }

    @Synchronized fun finish(ctx: Context) {
        if (started < 0) return
        val start = started
        started = -1
        if (PanicWipe.wipeInProgress) return
        val record = JSONObject().put("method", method).put("wallMs", SystemClock.elapsedRealtime() - start)
            .put("confirmed", confirmed).put("failed", failed).put("bytes", bytes).put("slots", slots)
        knownStages.forEach { record.put(it, stages[it] ?: 0L) }
        val encrypted = SecureFile.encryptBytes(record.toString().toByteArray(Charsets.UTF_8))
        check(!PanicWipe.wipeInProgress)
        val folder = File(ctx.filesDir, "recording_metrics").apply { mkdirs() }
        val atomic = AtomicFile(File(folder, "upload.enc"))
        val out = atomic.startWrite()
        try { out.write(encrypted); out.fd.sync(); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }

    fun report(ctx: Context): String = runCatching {
        val file = File(ctx.filesDir, "recording_metrics/upload.enc")
        val obj = JSONObject(String(SecureFile.decryptBytes(AtomicFile(file).readFully()), Charsets.UTF_8))
        ctx.getString(R.string.upload_measurements_report, obj.getString("method"), obj.getLong("slots"),
            obj.getLong("confirmed"), obj.getLong("failed"), obj.getLong("bytes") / 1_000_000.0,
            obj.getLong("wallMs") / 1000.0, obj.getLong("prepare") / 1000.0, obj.getLong("lookup") / 1000.0,
            obj.getLong("write") / 1000.0, obj.getLong("verify") / 1000.0, obj.getLong("cloud") / 1000.0,
            obj.getLong("commit") / 1000.0)
    }.getOrElse { ctx.getString(R.string.upload_measurements_none) }
}
