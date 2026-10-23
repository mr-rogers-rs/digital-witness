package com.fieldnotes.recorder.capture

import android.content.Context
import android.util.AtomicFile
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.PanicWipe
import org.json.JSONObject
import java.io.File

/** Last capture lifecycle outcome only: no footage, account, token, PIN, location or wall-clock time. */
object CaptureDiagnostics {
    private fun file(ctx: Context) = File(File(ctx.filesDir, "recording_metrics"), "capture_stop.spf")
    @Synchronized fun record(ctx: Context, reason: String, bufferedBytes: Long, savedSegments: Int) {
        if (PanicWipe.wipeInProgress) return
        val bytes = SecureFile.encryptBytes(JSONObject().put("reason", reason).put("bufferedBytes", bufferedBytes)
            .put("savedSegments", savedSegments).toString().toByteArray(Charsets.UTF_8))
        val target = file(ctx)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try {
            out.write(bytes); out.fd.sync()
            check(!PanicWipe.wipeInProgress)
            atomic.finishWrite(out)
        } catch (error: Exception) { atomic.failWrite(out); throw error }
    }
    fun report(ctx: Context): String = runCatching {
        String(SecureFile.decryptBytes(AtomicFile(file(ctx)).readFully()), Charsets.UTF_8)
    }.getOrDefault("{}")
    fun lastProblem(ctx: Context): String? = runCatching {
        val reason = JSONObject(report(ctx)).optString("reason")
        val message = when (reason) {
            "capture_storage_low" -> R.string.capture_storage_low
            "capture_buffer_full" -> R.string.capture_buffer_full
            "capture_journal_failed" -> R.string.capture_journal_failed
            "capture_audio_timing_failed" -> R.string.capture_audio_timing_failed
            "fail_stalled_body" -> R.string.fail_stalled_body
            "keyframe_timeout" -> R.string.fail_mp4_creation
            "fail_segment_processing" -> R.string.fail_segment_processing
            "fail_start_recording" -> R.string.fail_start_recording
            "service_destroyed" -> R.string.recovery_interrupted
            else -> return null
        }
        ctx.getString(message)
    }.getOrNull()
}
