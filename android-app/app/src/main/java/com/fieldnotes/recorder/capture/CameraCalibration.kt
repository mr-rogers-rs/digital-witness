package com.fieldnotes.recorder.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.CameraInfo
import com.fieldnotes.recorder.util.PanicWipe
import com.fieldnotes.recorder.util.Prefs
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Calibration images are private, encrypted, excluded from delivery, and covered by panic wipe. */
object CalibrationStore {
    data class Preview(val jpeg: ByteArray, val frames: Int, val fps: Double, val kbps: Long, val signature: String) {
        fun bitmap(): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
    }

    fun signature(ctx: Context): String = listOf(
        "v4-native-direct", Build.FINGERPRINT, Prefs.cameraBackend(ctx),
        runCatching { com.fieldnotes.recorder.util.CameraUtil.normalVideoResolution(ctx) }.getOrNull(),
        Prefs.videoFps(ctx), Prefs.videoProfile(ctx), Prefs.videoGopSeconds(ctx),
        Prefs.videoQuality(ctx), Prefs.videoBitrateMode(ctx), Prefs.videoTargetKbps(ctx), Prefs.videoBppMillis(ctx),
        Prefs.isFullCameraView(ctx), Prefs.isVideoStabilizationEnabled(ctx), Prefs.isVideoDynamicFpsEnabled(ctx)
    ).joinToString("|")

    private fun file(ctx: Context, cameraId: String, size: Pair<Int, Int>): File {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(cameraId.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "calibration"), "$id-${size.first}x${size.second}.enc")
    }

    fun save(ctx: Context, cameraId: String, size: Pair<Int, Int>, preview: Preview) {
        check(!PanicWipe.wipeInProgress)
        val target = file(ctx, cameraId, size)
        target.parentFile!!.mkdirs()
        val json = JSONObject().put("jpeg", Base64.encodeToString(preview.jpeg, Base64.NO_WRAP))
            .put("frames", preview.frames).put("fps", preview.fps).put("kbps", preview.kbps).put("signature", preview.signature)
        val encrypted = SecureFile.encryptBytes(json.toString().toByteArray())
        check(!PanicWipe.wipeInProgress)
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try { out.write(encrypted); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }

    fun load(ctx: Context, cameraId: String, size: Pair<Int, Int>): Preview? = runCatching {
        val json = JSONObject(String(SecureFile.decryptBytes(AtomicFile(file(ctx, cameraId, size)).readFully())))
        Preview(Base64.decode(json.getString("jpeg"), Base64.NO_WRAP), json.getInt("frames"),
            json.getDouble("fps"), json.getLong("kbps"), json.getString("signature"))
    }.getOrNull()

    fun clear(ctx: Context) { File(ctx.filesDir, "calibration").deleteRecursively() }
}

object CameraCalibration {
    /** Uses the same camera requests, encoder settings and MP4 writer as recording. No microphone/upload. */
    suspend fun test(ctx: Context, camera: CameraInfo, size: Pair<Int, Int>): CalibrationStore.Preview {
        check(!CaptureStatus.running && !PanicWipe.wipeInProgress)
        val signature = CalibrationStore.signature(ctx)
        val frames = mutableListOf<MuxSample>()
        val format = AtomicReference<MediaFormat?>()
        val error = AtomicReference<String?>()
        var bytes = 0L
        val capture = VideoCapture(ctx, onEncoded = { frame ->
            if (!frame.isConfig) synchronized(frames) {
                bytes += frame.data.size
                if (bytes > 32 * 1024 * 1024) error.set("Calibration buffer limit")
                else frames.add(MuxSample(frame.data, frame.presentationTimeUs, frame.isKeyFrame))
            }
        }, onError = { error.set(it) }, onFormat = { format.set(it) }, resolutionOverride = size)
        try {
            withContext(Dispatchers.Main) { capture.start() }
            val started = withTimeoutOrNull(12_000) {
                while (synchronized(frames) { frames.isEmpty() }) {
                    error.get()?.let { throw IllegalStateException(it) }
                    delay(50)
                }
                true
            }
            check(started == true) { "Camera produced no frames" }
            val until = SystemClock.elapsedRealtime() + 3_000
            while (SystemClock.elapsedRealtime() < until) {
                error.get()?.let { throw IllegalStateException(it) }
                delay(50)
            }
        } finally {
            withContext(NonCancellable) { capture.stopAndDrain() }
        }
        error.get()?.let { throw IllegalStateException(it) }
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) {
            val samples = synchronized(frames) { frames.toList() }
            require(samples.size >= 2 && samples.first().keyFrame)
            val file = File(ctx.cacheDir, "calibration-${UUID.randomUUID()}.mp4")
            try {
                SegmentMuxer.write(file, null, checkNotNull(format.get()), emptyList(), samples, 8_000_000, camera.orientation)
                val retriever = MediaMetadataRetriever()
                val bitmap = try {
                    retriever.setDataSource(file.absolutePath)
                    checkNotNull(if (Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST, 960, 960)
                    else retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
                } finally { retriever.release() }
                val jpeg = try {
                    ByteArrayOutputStream().use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out))
                        out.toByteArray()
                    }
                } finally { bitmap.recycle() }
                val durationMs = (samples.last().timeUs - samples.first().timeUs) / 1000.0
                val preview = CalibrationStore.Preview(jpeg, samples.size, (samples.size - 1) * 1000.0 / durationMs,
                    (samples.sumOf { it.data.size.toLong() } * 8 / durationMs).toLong(), signature)
                currentCoroutineContext().ensureActive()
                check(!CaptureStatus.running && !PanicWipe.wipeInProgress)
                CalibrationStore.save(ctx, camera.cameraId, size, preview)
                preview
            } finally {
                file.delete()
                File(file.path + ".muxing").delete()
            }
        }
    }
}
