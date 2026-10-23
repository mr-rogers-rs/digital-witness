package com.fieldnotes.recorder.capture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.PanicWipe
import com.fieldnotes.recorder.util.Prefs
import org.json.JSONObject
import java.io.File

class FrameMeasurements {
    var count = 0L; private set
    var firstUs = 0L; private set
    var lastUs = 0L; private set
    var maxGapUs = 0L; private set
    var discontinuities = 0L; private set
    fun add(timeUs: Long) {
        if (count == 0L) firstUs = timeUs else {
            val delta = timeUs - lastUs
            if (delta <= 0) discontinuities++ else maxGapUs = maxOf(maxGapUs, delta)
        }
        lastUs = timeUs; count++
    }
    fun fps(): Double = if (count > 1 && lastUs > firstUs) (count - 1) * 1_000_000.0 / (lastUs - firstUs) else 0.0
}

/** Measurements, not battery-life forecasts. No footage or camera identifiers in these records. */
object RecordingMeasurements {
    private const val PIPELINE = "native-direct-v1"
    private var frames = FrameMeasurements()
    private var startMs = 0L
    private var backend = "camera2"
    private var preset = "balanced"
    private var requestedFps = 0
    private var width = 0
    private var height = 0
    private var mediaBytes = 0L
    private var videoBytes = 0L
    private var targetBitrateKbps = 0
    private var currentTargetKbps = 0
    private var minimumTargetKbps = 0
    private var adaptiveRequested = false
    private var adaptiveSupported = false
    private var txStart = -1L
    private var rxStart = -1L
    private var batteryStart = JSONObject()
    private val recentFrames = java.util.ArrayDeque<Long>()

    private fun battery(ctx: Context): JSONObject {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return JSONObject().put("percent", if (level >= 0 && scale > 0) level * 100.0 / scale else JSONObject.NULL)
            .put("temperatureC", if (temp != Int.MIN_VALUE) temp / 10.0 else JSONObject.NULL)
            .put("plugged", (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) != 0)
    }

    @Synchronized fun begin(ctx: Context) {
        frames = FrameMeasurements(); startMs = SystemClock.elapsedRealtime()
        recentFrames.clear()
        backend = Prefs.cameraBackend(ctx); preset = Prefs.videoPreset(ctx); requestedFps = Prefs.videoFps(ctx)
        width = 0; height = 0; mediaBytes = 0; videoBytes = 0; targetBitrateKbps = 0
        currentTargetKbps = 0; minimumTargetKbps = 0; adaptiveRequested = false; adaptiveSupported = false
        txStart = TrafficStats.getUidTxBytes(Process.myUid()); rxStart = TrafficStats.getUidRxBytes(Process.myUid())
        batteryStart = battery(ctx)
    }

    @Synchronized fun videoFormat(w: Int, h: Int, targetBps: Int = 0) { width = w; height = h; targetBitrateKbps = targetBps / 1000 }
    @Synchronized fun video(timeUs: Long, bytes: Int) {
        frames.add(timeUs); mediaBytes += bytes; videoBytes += bytes
        recentFrames.addLast(timeUs)
        while (recentFrames.size > 301 || (recentFrames.size > 2 && timeUs - recentFrames.first > 5_000_000L)) recentFrames.removeFirst()
    }
    @Synchronized fun belowMinimumFps(): Boolean {
        if (recentFrames.size < 2) return false
        val span = recentFrames.last - recentFrames.first
        return span >= 4_000_000 && (recentFrames.size - 1) * 1_000_000.0 / span < 9.95
    }
    @Synchronized fun audio(bytes: Int) { mediaBytes += bytes }
    @Synchronized fun producedBytes(): Long = mediaBytes
    @Synchronized fun bitrateTarget(bps: Int, requested: Boolean, supported: Boolean) {
        currentTargetKbps = bps / 1000
        minimumTargetKbps = if (minimumTargetKbps == 0) currentTargetKbps else minOf(minimumTargetKbps, currentTargetKbps)
        adaptiveRequested = requested; adaptiveSupported = supported
    }
    @Synchronized fun liveText(ctx: Context): String = ctx.getString(R.string.capture_measured_fps,
        if (backend == "camerax") "CameraX" else "Camera2", requestedFps, frames.fps(), width, height) +
        if (adaptiveRequested) "\n" + (if (adaptiveSupported) ctx.getString(R.string.adaptive_bitrate_live, currentTargetKbps)
            else ctx.getString(R.string.adaptive_bitrate_unavailable)) else ""

    @Synchronized fun finish(ctx: Context) {
        if (startMs == 0L || PanicWipe.wipeInProgress) return
        fun delta(current: Long, previous: Long): Any = if (current >= previous && previous >= 0) current - previous else JSONObject.NULL
        val record = JSONObject().put("pipeline", PIPELINE).put("backend", backend).put("preset", preset)
            .put("seconds", (SystemClock.elapsedRealtime() - startMs) / 1000.0)
            .put("requestedFps", requestedFps).put("measuredFps", frames.fps())
            .put("width", width).put("height", height).put("videoFrames", frames.count)
            .put("maxFrameGapMs", frames.maxGapUs / 1000.0).put("timestampDiscontinuities", frames.discontinuities)
            .put("targetVideoKbps", if (targetBitrateKbps > 0) targetBitrateKbps else JSONObject.NULL)
            .put("adaptiveBitrateRequested", adaptiveRequested).put("adaptiveBitrateSupported", adaptiveSupported)
            .put("minimumTargetVideoKbps", minimumTargetKbps)
            .put("measuredVideoKbps", if (frames.fps() > 0) videoBytes * 8.0 * frames.fps() / (1000.0 * frames.count) else JSONObject.NULL)
            .put("mediaBytes", mediaBytes).put("txBytes", delta(TrafficStats.getUidTxBytes(Process.myUid()), txStart))
            .put("rxBytes", delta(TrafficStats.getUidRxBytes(Process.myUid()), rxStart))
            .put("batteryStart", batteryStart).put("batteryEnd", battery(ctx))
        val encrypted = SecureFile.encryptBytes(record.toString().toByteArray())
        check(!PanicWipe.wipeInProgress)
        val folder = File(ctx.filesDir, "recording_metrics").apply { mkdirs() }
        val file = AtomicFile(File(folder, "$backend-$preset.enc"))
        val out = file.startWrite()
        try { out.write(encrypted); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        startMs = 0
    }

    fun report(ctx: Context): String {
        val files = File(ctx.filesDir, "recording_metrics").listFiles()?.filter { it.extension == "enc" }.orEmpty()
        val rows = files.sortedBy { it.name }.mapNotNull { file ->
            runCatching {
                val json = JSONObject(String(SecureFile.decryptBytes(AtomicFile(file).readFully())))
                // Power results from the removed GPU path are not comparable to this recorder.
                require(json.optString("pipeline") == PIPELINE)
                fun number(obj: JSONObject, key: String, scale: Double = 1.0): String =
                    if (obj.isNull(key)) ctx.getString(R.string.measurement_unknown) else String.format(ctx.resources.configuration.locales[0], "%.1f", obj.getDouble(key) / scale)
                val start = json.getJSONObject("batteryStart"); val end = json.getJSONObject("batteryEnd")
                val mode = when (json.getString("preset")) {
                    "battery" -> R.string.set_preset_battery
                    "minimum" -> R.string.set_preset_minimum
                    "quality" -> R.string.set_preset_quality
                    "full_sensor" -> R.string.set_preset_full
                    "custom" -> R.string.set_preset_custom
                    else -> R.string.set_preset_balanced
                }
                ctx.getString(R.string.measurement_record,
                    if (json.getString("backend") == "camerax") "CameraX" else "Camera2", ctx.getString(mode),
                    number(json, "seconds"), json.getInt("width"), json.getInt("height"), json.getInt("requestedFps"),
                    number(json, "measuredFps"), number(json, "maxFrameGapMs"), json.getLong("timestampDiscontinuities"),
                    number(json, "mediaBytes", 1_000_000.0), number(json, "txBytes", 1_000_000.0), number(json, "rxBytes", 1_000_000.0),
                    number(start, "percent"), number(end, "percent"), number(start, "temperatureC"), number(end, "temperatureC"),
                    ctx.getString(if (start.getBoolean("plugged") || end.getBoolean("plugged")) R.string.measurement_plugged else R.string.measurement_unplugged)) + "\n" +
                    ctx.getString(R.string.measurement_bitrate, number(json, "targetVideoKbps"), number(json, "measuredVideoKbps"))
            }.getOrNull()
        }
        return rows.joinToString("\n\n").ifBlank { ctx.getString(R.string.measurement_none) }
    }
}
