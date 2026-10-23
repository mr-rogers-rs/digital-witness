package com.fieldnotes.recorder.integrity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Continuously records the device's inertial + environmental sensor streams (and, fed by
 * the camera pipeline via [addCameraFrame], per-frame Camera2 capture metadata) for the
 * lifetime of a recording session, then snapshots them per segment into a sidecar file
 * whose hash is bound into the segment's signed proof chain.
 *
 * ## Why this exists (anti-"it's AI")
 * A generative/deepfaked video cannot also fabricate an independently time-stamped sensor
 * trace that is *physically consistent* with the pixels:
 *  - gyroscope angular velocity must match the frame-to-frame global motion (optical flow),
 *  - rolling-shutter skew must match the yaw rate,
 *  - the auto-exposure trajectory must match the ambient light sensor,
 *  - accelerometer jolts must line up with visible bumps/footfalls.
 * These cross-modal relationships are physics, not style; reproducing all of them
 * consistently — under a hash chain and external time anchor — is beyond current synthetic
 * video. The verifier reports the correlations as expert-presentable evidence.
 *
 * ## Anonymity
 * The device *fingerprint* in IMU data is the near-constant calibration bias (the DC
 * offset), not the dynamics. In [anonymous] mode each motion stream is bias-subtracted
 * using a **session-level running median** (robust to motion outliers, consistent
 * across segments) so the fingerprint is removed without distorting the dynamics —
 * which carry the anti-forgery signal. Absolute-orientation/heading streams (rotation
 * vector, magnetometer) are omitted entirely, and barometer is stored as deltas from
 * the segment mean rather than absolute pressure.
 *
 * Threading: sensor callbacks arrive on a dedicated [HandlerThread]; camera metadata is
 * pushed from the camera thread. Both append to lock-free queues. [writeSegment] drains
 * them and is expected to be called from the segment-finalize coroutine.
 */
class SensorTelemetry(
    private val context: Context,
    private val anonymous: Boolean
) : SensorEventListener {

    companion object {
        private const val TAG = "DW-Telemetry"
        const val VERSION = 1
        private const val QUANTIZE = 1e-3   // anonymous-mode rounding step
    }

    private data class Sample(val tNanos: Long, val values: FloatArray)

    private val accel = ArrayBlockingQueue<Sample>(12_000)
    private val gyro = ArrayBlockingQueue<Sample>(12_000)
    private val rotation = ArrayBlockingQueue<Sample>(12_000)
    private val magnetic = ArrayBlockingQueue<Sample>(12_000)
    private val baro = ArrayBlockingQueue<Sample>(12_000)
    private val light = ArrayBlockingQueue<Sample>(12_000)
    private val cameraFrames = ArrayBlockingQueue<JSONObject>(6_000)
    private val droppedSamples = AtomicLong()
    private fun <T> offer(queue: ArrayBlockingQueue<T>, value: T) {
        if (!queue.offer(value)) droppedSamples.incrementAndGet()
    }

    /**
     * Session-level running median per IMU axis, used as the DC-bias estimate
     * for anonymous-mode mean-subtraction (audit H16).
     *
     * Why session-level and median (not per-segment mean): the device
     * fingerprint in IMU data is the near-constant calibration bias (DC
     * offset), which persists across the whole session. The previous code
     * subtracted each segment's *mean*, which (a) only equals the true bias
     * for a perfectly static segment and (b) distorts the dynamics under
     * motion — the very signal the anti-forgery design relies on. A
     * session-wide median is robust to motion outliers and removes the
     * fingerprint consistently without distorting per-segment dynamics.
     *
     * Maintained on the sensor HandlerThread (single-threaded for inserts).
     */
    private val accelMedian = RunningMedian(3)
    private val gyroMedian = RunningMedian(3)

    private var sm: SensorManager? = null
    private var thread: HandlerThread? = null

    /** Camera2 SENSOR_INFO_TIMESTAMP_SOURCE ("REALTIME" | "UNKNOWN"); set by the service. */
    @Volatile private var cameraTimestampSource: String = "unset"
    /** Measured offset between the camera clock and SensorEvent.timestamp, if known. */
    @Volatile private var clockOffsetNanos: Long? = null

    fun setCameraClock(source: String, offsetNanos: Long?) {
        cameraTimestampSource = source
        clockOffsetNanos = offsetNanos
    }

    fun start() {
        val mgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
            Log.w(TAG, "No SensorManager available")
            return
        }
        sm = mgr
        val ht = HandlerThread("DW-Telemetry").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)

        // ~100 Hz for motion, slower for environmental. SENSOR_DELAY_GAME ≈ 50 Hz; use a
        // fixed microsecond period for motion sensors where supported.
        fun reg(type: Int, periodUs: Int) {
            mgr.getDefaultSensor(type)?.let { mgr.registerListener(this, it, periodUs, handler) }
        }
        reg(Sensor.TYPE_ACCELEROMETER, 10_000)        // 100 Hz
        reg(Sensor.TYPE_GYROSCOPE, 10_000)            // 100 Hz
        reg(Sensor.TYPE_PRESSURE, 200_000)            // 5 Hz
        reg(Sensor.TYPE_LIGHT, 200_000)               // 5 Hz
        if (!anonymous) {
            reg(Sensor.TYPE_ROTATION_VECTOR, 20_000)  // 50 Hz, absolute orientation
            reg(Sensor.TYPE_MAGNETIC_FIELD, 50_000)   // 20 Hz, absolute heading
        }
        Log.i(TAG, "Sensor telemetry started (anonymous=$anonymous)")
    }

    fun stop() {
        try { sm?.unregisterListener(this) } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        sm = null
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = Sample(event.timestamp, event.values.clone())
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                offer(accel, s)
                if (anonymous) accelMedian.add(event.values)
            }
            Sensor.TYPE_GYROSCOPE -> {
                offer(gyro, s)
                if (anonymous) gyroMedian.add(event.values)
            }
            Sensor.TYPE_ROTATION_VECTOR -> offer(rotation, s)
            Sensor.TYPE_MAGNETIC_FIELD -> offer(magnetic, s)
            Sensor.TYPE_PRESSURE -> offer(baro, s)
            Sensor.TYPE_LIGHT -> offer(light, s)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Push one Camera2 frame's capture metadata. Called from the camera thread. */
    fun addCameraFrame(
        sensorTimestampNanos: Long,
        exposureTimeNanos: Long?,
        iso: Int?,
        focalLengthMm: Float?,
        focusDistance: Float?,
        aeState: Int?,
        afState: Int?,
        awbState: Int?,
        rollingShutterSkewNanos: Long?,
        frameNumber: Long
    ) {
        val o = JSONObject()
        o.put("t", sensorTimestampNanos)
        o.put("frame", frameNumber)
        exposureTimeNanos?.let { o.put("exposureNs", it) }
        iso?.let { o.put("iso", it) }
        aeState?.let { o.put("ae", it) }
        afState?.let { o.put("af", it) }
        awbState?.let { o.put("awb", it) }
        rollingShutterSkewNanos?.let { o.put("skewNs", it) }
        // Lens fields are coarse but slightly device-class revealing; keep in evidence mode.
        if (!anonymous) {
            focalLengthMm?.let { o.put("focalMm", it.toDouble()) }
            focusDistance?.let { o.put("focusDist", it.toDouble()) }
        }
        offer(cameraFrames, o)
    }

    /**
     * Drain everything buffered since the last call into [file] as a JSON sidecar and
     * return it, or null if nothing was captured / on failure. Intended to be called once
     * per segment, on the segment-finalize path.
     *
     * @param segmentStartPtsUs / [segmentEndPtsUs] the segment's video PTS window, recorded
     *   so a verifier can align the sensor clock to the video timeline.
     */
    fun writeSegment(
        file: File,
        segmentStartPtsUs: Long,
        segmentEndPtsUs: Long,
        segmentStartElapsedNanos: Long
    ): File? {
        return try {
            val root = JSONObject()
            root.put("v", VERSION)
            root.put("anonymized", anonymous)
            root.put("clock", JSONObject().apply {
                put("sensorTimeBase", "elapsedRealtimeNanos")
                put("cameraTimestampSource", cameraTimestampSource)
                put("cameraOffsetNanos", clockOffsetNanos ?: JSONObject.NULL)
                put("drainedAtElapsedNanos", SystemClock.elapsedRealtimeNanos())
            })
            // Video frame PTS is segment-relative; this anchors PTS=0 to the absolute
            // sensor clock so a verifier can align frames with sensor samples:
            //   absoluteElapsedNanos(ptsUs) = segmentStartElapsedNanos + ptsUs * 1000
            root.put("segmentStartElapsedNanos", segmentStartElapsedNanos)
            root.put("segmentStartPtsUs", segmentStartPtsUs)
            root.put("segmentEndPtsUs", segmentEndPtsUs)

            val streams = JSONObject()
            // Session-level DC-bias estimate (median) for anonymous mode; zero
            // in evidence mode (no subtraction). See drainVec3 / audit H16.
            val accelBias = if (anonymous) accelMedian.median() else DoubleArray(3)
            val gyroBias = if (anonymous) gyroMedian.median() else DoubleArray(3)
            streams.put("accel", drainVec3(accel, accelBias))
            streams.put("gyro", drainVec3(gyro, gyroBias))
            streams.put("baro", drainScalar(baro, relative = anonymous))
            streams.put("light", drainScalar(light, relative = false))
            if (!anonymous) {
                streams.put("rotation", drainRaw(rotation))
                streams.put("magnetic", drainRaw(magnetic))
            } else {
                // Ensure absolute streams left over from a mode change aren't leaked.
                rotation.clear(); magnetic.clear()
            }
            root.put("streams", streams)

            val cam = JSONArray()
            while (true) { val o = cameraFrames.poll() ?: break; cam.put(o) }
            root.put("cameraFrames", cam)
            root.put("droppedSamples", droppedSamples.getAndSet(0))

            val totalSamples = (streams.optJSONArray("accel")?.length() ?: 0) +
                (streams.optJSONArray("gyro")?.length() ?: 0) + cam.length()
            if (totalSamples == 0) {
                Log.w(TAG, "No telemetry samples this segment")
                return null
            }

            file.writeText(root.toString())
            Log.i(TAG, "Telemetry segment written: ${file.name} (${file.length()} B)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write telemetry segment", e)
            null
        }
    }

    /**
     * 3-axis stream → [[t,x,y,z],…]; in anonymous mode the DC bias is removed
     * using the **session-level running median** ([bias]), not the per-segment
     * mean. The median is robust to motion (a segment with a jolt doesn't
     * shift it) and persistent across segments, so it tracks the true
     * hardware calibration offset — removing the device fingerprint
     * consistently without distorting the dynamics (audit H16). Values are
     * then quantized to defeat sub-quantization fingerprinting.
     */
    private fun drainVec3(q: ArrayBlockingQueue<Sample>, bias: DoubleArray): JSONArray {
        val samples = ArrayList<Sample>()
        while (true) { val s = q.poll() ?: break; samples.add(s) }
        val arr = JSONArray()
        if (samples.isEmpty()) return arr
        for (s in samples) {
            val row = JSONArray()
            row.put(s.tNanos)
            for (i in 0..2) {
                var v = (s.values.getOrNull(i)?.toDouble() ?: 0.0) - bias[i]
                if (anonymous) v = Math.round(v / QUANTIZE) * QUANTIZE
                row.put(v)
            }
            arr.put(row)
        }
        return arr
    }

    /** Scalar stream → [[t,v],…]; [relative] subtracts the segment mean (anonymous baro). */
    private fun drainScalar(q: ArrayBlockingQueue<Sample>, relative: Boolean): JSONArray {
        val samples = ArrayList<Sample>()
        while (true) { val s = q.poll() ?: break; samples.add(s) }
        val arr = JSONArray()
        if (samples.isEmpty()) return arr
        var mean = 0f
        if (relative) { for (s in samples) mean += s.values.getOrElse(0) { 0f }; mean /= samples.size }
        for (s in samples) {
            val row = JSONArray()
            row.put(s.tNanos)
            row.put((s.values.getOrElse(0) { 0f } - mean).toDouble())
            arr.put(row)
        }
        return arr
    }

    /** Raw multi-component stream (evidence mode only). */
    private fun drainRaw(q: ArrayBlockingQueue<Sample>): JSONArray {
        val arr = JSONArray()
        while (true) {
            val s = q.poll() ?: break
            val row = JSONArray()
            row.put(s.tNanos)
            for (v in s.values) row.put(v.toDouble())
            arr.put(row)
        }
        return arr
    }

    /**
     * Bounded-memory running-median estimator over [axes] axes, used to track
     * the session-level IMU DC bias (audit H16). Keeps the most recent
     * [CAPACITY] samples per axis in a ring buffer; [median] returns the
     * per-axis median of that window. CAPACITY is large enough that the window
     * spans many seconds of motion, so the median converges to the true
     * calibration bias and is robust to transient jolts.
     *
     * Thread-safety: inserts happen on the sensor HandlerThread (single
     * producer); [median] is read on the finalize coroutine. Both use
     * synchronized to be safe.
     */
    private class RunningMedian(val axes: Int) {
        private val CAPACITY = 4096
        private val rings = Array(axes) { FloatArray(CAPACITY) }
        private val counts = IntArray(axes)
        private val heads = IntArray(axes)

        @Synchronized
        fun add(values: FloatArray) {
            for (i in 0 until minOf(axes, values.size)) {
                rings[i][heads[i]] = values[i]
                heads[i] = (heads[i] + 1) % CAPACITY
                if (counts[i] < CAPACITY) counts[i]++
            }
        }

        @Synchronized
        fun median(): DoubleArray {
            val out = DoubleArray(axes)
            for (i in 0 until axes) {
                if (counts[i] == 0) { out[i] = 0.0; continue }
                val sorted = FloatArray(counts[i]) { j -> rings[i][j] }
                sorted.sort()
                out[i] = sorted[sorted.size / 2].toDouble()
            }
            return out
        }
    }
}
