package com.fieldnotes.recorder.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build

data class CameraInfo(
    val cameraId: String,
    val sensorSize: Pair<Int, Int>,
    val sensorAspectRatio: Double,
    val supportedResolutions: List<Pair<Int, Int>>,
    val orientation: Int
)

object CameraUtil {
    // Characteristics are static for built-in cameras. Cache codec inspection, not session success.
    private val modesCache = mutableMapOf<String, Map<Pair<Int, Int>, List<Int>>>()

    @Synchronized
    private fun nativeModes(context: Context, backend: String): Map<Pair<Int, Int>, List<Int>> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(context)
        val key = "$id:$backend"
        modesCache[key]?.let { return it }
        val chars = cm.getCameraCharacteristics(id)
        val sensor = checkNotNull(chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
        val ratio = sensor.width().toDouble() / sensor.height()
        val map = checkNotNull(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val previewSizes = if (backend == "camerax")
            map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toSet().orEmpty() else null
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val result = try {
            val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            checkNotNull(map.getOutputSizes(MediaCodec::class.java)).filter {
                kotlin.math.abs(it.width.toDouble() / it.height - ratio) / ratio < 0.02 &&
                    (previewSizes == null || it in previewSizes)
            }.associate { size ->
                val duration = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
                val rates = (10..30).filter { fps ->
                    com.fieldnotes.recorder.capture.CameraRequestPolicy.fpsRange(chars, fps) != null &&
                        com.fieldnotes.recorder.capture.NativeCapturePolicy.durationSupports(duration, fps) &&
                        caps.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                }
                (size.width to size.height) to rates
            }.filterValues { it.isNotEmpty() }
        } finally { encoder.release() }
        modesCache[key] = result
        return result
    }

    /** Highest advertised normal rate (up to 30), with no invented 30fps fallback. */
    fun normalFps(context: Context, backend: String = Prefs.cameraBackend(context)): Int =
        nativeModes(context, backend).values.flatten().maxOrNull() ?: error("No compatible native video mode")

    fun nativeFullViewSizes(context: Context, backend: String = Prefs.cameraBackend(context)): List<Pair<Int, Int>> {
        val normalRate = normalFps(context, backend)
        return nativeModes(context, backend).filterValues { normalRate in it }.keys
            .sortedByDescending { it.first.toLong() * it.second }
    }

    fun normalVideoResolution(context: Context, backend: String = Prefs.cameraBackend(context)): Pair<Int, Int> {
        val info = checkNotNull(getCameraInfo(context))
        return com.fieldnotes.recorder.capture.NativeCapturePolicy.normalSize(nativeFullViewSizes(context, backend), info.sensorAspectRatio)
    }

    fun nativeFpsOptions(context: Context, size: Pair<Int, Int> = getOptimalVideoResolution(context, 0),
        backend: String = Prefs.cameraBackend(context)): List<Int> = nativeModes(context, backend)[size].orEmpty()

    fun fpsLabel(context: Context, fps: Int): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val range = checkNotNull(com.fieldnotes.recorder.capture.CameraRequestPolicy.fpsRange(cm.getCameraCharacteristics(backCameraId(context)), fps))
        return if (range.lower == range.upper) context.getString(com.fieldnotes.recorder.R.string.native_rate_fixed, fps)
            else context.getString(com.fieldnotes.recorder.R.string.native_rate_variable, range.lower, range.upper)
    }

    fun applyVideoPreset(context: Context) {
        // A missing mode is reported by the recording/calibration preflight, never substituted.
        val normal = runCatching { normalFps(context) }.getOrDefault(30)
        Prefs.applyVideoPreset(context, normal)
    }
    fun backCameraId(context: Context): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull {
            val chars = cm.getCameraCharacteristics(it)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        } ?: error("No back camera available")
    }

    fun getCameraInfo(context: Context): CameraInfo? = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(context)
        val chars = cm.getCameraCharacteristics(id)
        val map = checkNotNull(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val sizes = checkNotNull(map.getOutputSizes(MediaCodec::class.java))
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val w = sensor?.width() ?: 4
        val h = sensor?.height() ?: 3
        CameraInfo(id, w to h, w.toDouble() / h,
            sizes.map { it.width to it.height }.distinct().sortedByDescending { it.first.toLong() * it.second },
            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
    }.getOrNull()

    fun getOptimalVideoResolution(context: Context, maxHeight: Int): Pair<Int, Int> {
        val info = checkNotNull(getCameraInfo(context)) { "Could not read video output sizes" }
        val compatible = nativeFullViewSizes(context)
        // Legacy GPU/output-size preferences never count as a native-view comparison.
        Prefs.verifiedNativeResolution(context, info.cameraId, com.fieldnotes.recorder.capture.CalibrationStore.signature(context))?.let {
            require(it in compatible) { "Selected camera resolution is no longer supported; recalibrate" }
            return it
        }
        return com.fieldnotes.recorder.capture.NativeCapturePolicy.normalSize(compatible, info.sensorAspectRatio)
    }

    /** Same framing for calibration and recording. Stream aspect ratio can still crop the sensor. */
    fun applyFraming(context: Context, chars: CameraCharacteristics, request: CaptureRequest.Builder) {
        val fullView = Prefs.isFullCameraView(context)
        val stabilization = if (!fullView && Prefs.isVideoStabilizationEnabled(context))
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (modes?.contains(stabilization) == true) request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilization)
        chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            request.set(CaptureRequest.SCALER_CROP_REGION, it)
        }
        if (Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1f) == true) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        }
        if (Build.VERSION.SDK_INT >= 31 && chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
            request.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
    }
}
