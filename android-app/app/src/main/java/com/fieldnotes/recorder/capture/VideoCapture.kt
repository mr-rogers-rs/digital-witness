package com.fieldnotes.recorder.capture

import com.fieldnotes.recorder.R
import android.Manifest
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Range
import androidx.core.content.ContextCompat
import android.util.Log
import com.fieldnotes.recorder.util.Prefs

import androidx.annotation.RequiresPermission

/**
 * Data class to hold encoded video frame and its presentation timestamp
 */
data class EncodedVideoFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val isConfig: Boolean = false
)

/**
 * Per-frame Camera2 capture metadata, emitted alongside each encoded frame. Keyed by
 * [sensorTimestampNanos] (Camera2 SENSOR_TIMESTAMP), which shares a clock with
 * SensorEvent.timestamp when the device reports SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME —
 * enabling sensor↔video alignment for cross-modal authenticity checks.
 */
data class CameraFrameMeta(
    val sensorTimestampNanos: Long,
    val exposureTimeNanos: Long?,
    val iso: Int?,
    val focalLengthMm: Float?,
    val focusDistance: Float?,
    val aeState: Int?,
    val afState: Int?,
    val awbState: Int?,
    val rollingShutterSkewNanos: Long?,
    val frameNumber: Long
)

class VideoCapture(
    private val context: Context,
    private val onEncoded: (EncodedVideoFrame) -> Unit,
    private val onError: ((String) -> Unit)? = null,
    private val onCameraMeta: ((CameraFrameMeta) -> Unit)? = null,
    private val onFormat: (MediaFormat) -> Unit = {},
    private val resolutionOverride: Pair<Int, Int>? = null,
    private val onTorchStatus: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val onBitrateStatus: (Int, Boolean) -> Unit = { _, _ -> }
) {
    private var jobOpen: Job? = null
    private var jobDrain: Job? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraXInput: CameraXInput? = null
    private var cameraRequested = false
    private var cameraClosed = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var cleanup = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
    var configuredBitrateBps: Int = 0; private set
    @Volatile private var requestedBitrateBps = 0
    @Volatile private var adaptiveAvailable = false
    private var minimumBitrateBps = 0
    fun requestBitrate(bps: Int) {
        if (adaptiveAvailable) requestedBitrateBps = bps.coerceIn(minimumBitrateBps, configuredBitrateBps)
    }
    private var configuredSize = android.util.Size(640, 480)
    private val TAG = "DW-VideoCapture"
    private var loggedFirstKeyFrame = false
    private var repeatingRequest: CaptureRequest.Builder? = null
    private var hasFlash = false

    fun setTorch(enabled: Boolean) {
        val input = cameraXInput
        if (input != null) { input.setTorch(enabled); return }
        cameraHandler?.post {
            if (stopped || !hasFlash) return@post
            val request = repeatingRequest ?: return@post
            val activeSession = session ?: return@post
            val previous = request.get(CaptureRequest.FLASH_MODE) ?: CaptureRequest.FLASH_MODE_OFF
            try {
                request.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                activeSession.setRepeatingRequest(request.build(), captureMetaCallback, cameraHandler)
                onTorchStatus(true, enabled, false)
            } catch (_: Exception) {
                request.set(CaptureRequest.FLASH_MODE, previous)
                onTorchStatus(true, previous == CaptureRequest.FLASH_MODE_TORCH, true)
            }
        }
    }

    @Volatile private var stopped = true

    @Synchronized fun start() {
        if (jobOpen != null) return
        stopped = false
        outputEnded = false
        // Prepare a dedicated handler thread for camera callbacks to avoid main-thread contention
        cameraThread = HandlerThread("DW-Camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            onError?.invoke(context.getString(R.string.calib_err_no_permission))
            stop()
            return
        }
        jobOpen = CoroutineScope(Dispatchers.Main).launch {
            try {
                openCameraAndStart()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Could not start camera encoder", e)
                onError?.invoke(if (e is VideoBitrateException) e.message!! else context.getString(R.string.fail_camera_configuration))
                stop()
            }
        }
    }

    @Volatile private var outputEnded = false

    fun requestKeyFrame() {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not request segment keyframe", e)
        }
    }

    suspend fun stopAndDrain() {
        if (kotlinx.coroutines.withTimeoutOrNull(5_000) { jobOpen?.join(); true } != true) {
            jobOpen?.cancel()
            onError?.invoke(context.getString(R.string.fail_camera_configuration))
        }
        cameraXInput?.let {
            if (kotlinx.coroutines.withTimeoutOrNull(3000) { it.close().await(); true } != true)
                onError?.invoke(context.getString(R.string.camera_backend_failed))
        }
        try { session?.stopRepeating(); session?.abortCaptures(); camera?.close() } catch (_: Exception) {}
        if (cameraRequested) kotlinx.coroutines.withTimeoutOrNull(3000) { cameraClosed.await() }
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        val deadline = android.os.SystemClock.elapsedRealtime() + 1500
        while (!outputEnded && jobDrain?.isActive == true && android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(5)
        }
        val drain = jobDrain
        stop()
        if (kotlinx.coroutines.withTimeoutOrNull(5000) { drain?.join(); cleanup.await(); true } != true)
            onError?.invoke(context.getString(R.string.fail_full_view))
    }

    @Synchronized fun stop() {
        if (stopped && codec == null && cameraXInput == null) return
        stopped = true
        repeatingRequest = null
        onTorchStatus(false, false, false)
        Log.d(TAG, "Stopping video capture")
        val oldDrain = jobDrain; jobDrain = null
        val oldOpen = jobOpen
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.abortCaptures() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        try { camera?.close() } catch (_: Throwable) {}
        val oldSurface = surface
        val oldCodec = codec
        val oldCameraX = cameraXInput
        cameraXInput = null
        val oldThread = cameraThread
        val waitForCamera = cameraRequested
        val closedCamera = cameraClosed
        val inputReturned = oldCameraX?.close()
        fun releaseEncoder() {
            try { oldCodec?.stop() } catch (_: Throwable) {}
            try { oldCodec?.release() } catch (_: Throwable) {}
            try { oldSurface?.release() } catch (_: Throwable) {}
        }
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        cleanup = completed
        CoroutineScope(Dispatchers.Default).launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(5_000) { oldOpen?.join() }
                kotlinx.coroutines.withTimeoutOrNull(3_000) { inputReturned?.await() }
                if (waitForCamera) kotlinx.coroutines.withTimeoutOrNull(3000) { closedCamera.await() }
                oldDrain?.cancel(); oldDrain?.join()
                releaseEncoder()
            } finally { oldThread?.quitSafely(); completed.complete(Unit) }
        }
        codec = null
        surface = null
        camera = null
        session = null
        jobOpen?.cancel(); jobOpen = null
        cameraThread = null
        cameraHandler = null
    }

    private fun createVideoEncoder(): MediaCodec {
        val targetFps = Prefs.videoFps(context)
        val gopSeconds = Prefs.videoGopSeconds(context)
        // For stability with current muxing pipeline, always use H.264 (AVC)
        var selectedMime = MediaFormat.MIMETYPE_VIDEO_AVC
        val optimalResolution = resolutionOverride ?: com.fieldnotes.recorder.util.CameraUtil.getOptimalVideoResolution(context, Prefs.videoMaxHeight(context))
        require(targetFps in com.fieldnotes.recorder.util.CameraUtil.nativeFpsOptions(context, optimalResolution)) {
            context.getString(R.string.native_fps_unavailable, targetFps)
        }
        val c: MediaCodec = MediaCodec.createEncoderByType(selectedMime)
        codec = c
        configuredSize = android.util.Size(optimalResolution.first, optimalResolution.second)
        Log.d(TAG, "Selected optimal resolution: ${optimalResolution.first}x${optimalResolution.second}")

        val fmt = MediaFormat.createVideoFormat(selectedMime, optimalResolution.first, optimalResolution.second)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        // Calculate bitrate based on user configuration
        val width = optimalResolution.first
        val height = optimalResolution.second
        val bitrate = VideoBitratePolicy.requestedBps(width, height, targetFps, Prefs.videoBitrateMode(context),
            Prefs.videoTargetKbps(context), Prefs.videoBppMillis(context), Prefs.videoQuality(context))

        val cbr = VideoEncoderBitrate.configure(context, c, fmt, bitrate, Prefs.adaptiveVideoBitrate(context))
        configuredBitrateBps = bitrate
        val range = c.codecInfo.getCapabilitiesForType(selectedMime).videoCapabilities!!.bitrateRange
        minimumBitrateBps = maxOf(AdaptiveBitrate(bitrate, 0).minimumBps, range.lower).coerceAtMost(bitrate)
        adaptiveAvailable = Prefs.adaptiveVideoBitrate(context) && cbr
        requestedBitrateBps = bitrate
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gopSeconds)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)

        // MediaMuxer receives codec configuration via the output MediaFormat.
        // Samples remain untouched MediaCodec access units.
        if (android.os.Build.VERSION.SDK_INT >= 29) fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

        // Encoder profile/level
        try {
            if (selectedMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                val profilePref = Prefs.videoProfile(context)
                val avcProfile = when (profilePref) {
                    "high" -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    "baseline" -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    else -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                }
                fmt.setInteger(MediaFormat.KEY_PROFILE, avcProfile)
                // Let the encoder choose a valid level for the selected dimensions/rate.
            }
        } catch (_: Throwable) { }

        Log.d(TAG, "Configuring encoder mime=${selectedMime} w=${width} h=${height} fps=${targetFps} br=${bitrate} gop=${gopSeconds}")
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        codec = c
        Log.d(TAG, "Video encoder created and started")
        return c
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCameraAndStart() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = com.fieldnotes.recorder.util.CameraUtil.backCameraId(context)

        // Get camera sensor orientation to fix rotation issues
        val characteristics = cm.getCameraCharacteristics(backId)
        hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        Log.d(TAG, "Using camera ID: $backId, sensor orientation: $sensorOrientation degrees")
        val requestedRange = CameraRequestPolicy.fpsRange(characteristics, Prefs.videoFps(context))
        if (requestedRange == null) {
            onError?.invoke(context.getString(R.string.native_fps_unavailable, Prefs.videoFps(context)))
            stop(); return
        }
        val encoder = createVideoEncoder()
        // Native camera output goes straight into the encoder. No GPU resizing,
        // padding, interpolation, frame dropping or timestamp rewriting.
        val inputSurface = surface ?: return

        // Check permission defensively; caller should have requested CAMERA already.
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            return
        }

        if (Prefs.cameraBackend(context) == "camerax") {
            startDrainLoop(encoder)
            cameraXInput = CameraXInput(context, { message -> onError?.invoke(message) }, onTorchStatus,
                CaptureControls.requestedTorch).also {
                it.start(inputSurface, configuredSize, backId, captureMetaCallback)
            }
            return
        }
        cameraRequested = true
        cm.openCamera(backId, object : CameraDevice.StateCallback() {
            override fun onClosed(device: CameraDevice) { cameraClosed.complete(Unit) }
            override fun onOpened(device: CameraDevice) = synchronized(this@VideoCapture) opened@{
                if (stopped) { device.close(); return@opened }
                camera = device
                Log.d(TAG, "Camera opened")
                device.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) = synchronized(this@VideoCapture) configured@{
                        if (stopped) { sess.close(); return@configured }
                        session = sess
                        Log.d(TAG, "Capture session configured")
                        try {
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            val requestedTorch = hasFlash && CaptureControls.requestedTorch
                            req.set(CaptureRequest.FLASH_MODE, if (requestedTorch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                            req.addTarget(inputSurface)
                            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            com.fieldnotes.recorder.util.CameraUtil.applyFraming(context, characteristics, req)
                            req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, requestedRange)

                            // Enhanced motion handling settings
                            try {
                                // Use faster autofocus for better motion tracking
                                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                                // Prioritize motion over noise reduction
                                req.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)

                                // Use fast edge enhancement for better motion detail
                                req.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)

                                // Optimize exposure for motion (faster shutter speeds)
                                req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                req.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)

                                Log.d(TAG, "Applied enhanced motion handling settings")
                            } catch (e: Exception) {
                                Log.w(TAG, "Some motion optimization settings not supported: ${e.message}")
                            }
                            var torchFailed = false
                            try { sess.setRepeatingRequest(req.build(), captureMetaCallback, cameraHandler) }
                            catch (error: Exception) {
                                if (!requestedTorch) throw error
                                // A device may reject torch for this recording configuration.
                                req.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                sess.setRepeatingRequest(req.build(), captureMetaCallback, cameraHandler)
                                torchFailed = true
                            }
                            repeatingRequest = req
                            onTorchStatus(hasFlash, requestedTorch && !torchFailed, torchFailed)
                            Log.d(TAG, "Started repeating camera request")
                            startDrainLoop(encoder)
                        } catch (e: Exception) {
                            onError?.invoke(context.getString(R.string.fail_camera_configuration))
                            stop()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        onError?.invoke(context.getString(R.string.fail_camera_configuration))
                    }
                }, cameraHandler)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (!stopped) onError?.invoke(context.getString(R.string.fail_camera_configuration))
                stop()
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (!stopped) onError?.invoke(context.getString(R.string.fail_camera_code, error))
                stop()
            }
        }, cameraHandler)
    }

    /**
     * Capture callback that forwards per-frame Camera2 metadata to [onCameraMeta].
     * Null when no consumer is registered, so there is zero overhead in that case.
     */
    private val captureMetaCallback: CameraCaptureSession.CaptureCallback? =
        if (onCameraMeta == null) null else object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                try {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    onCameraMeta?.invoke(
                        CameraFrameMeta(
                            sensorTimestampNanos = ts,
                            exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                            focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                            aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                            afState = result.get(CaptureResult.CONTROL_AF_STATE),
                            awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                            rollingShutterSkewNanos = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                            frameNumber = result.frameNumber
                        )
                    )
                } catch (_: Throwable) {
                    // Metadata is best-effort; never disrupt capture.
                }
            }
        }

    private fun startDrainLoop(encoder: MediaCodec) {
        Log.d(TAG, "Starting encoder drain loop")
        jobDrain = CoroutineScope(Dispatchers.Default).launch {
            val info = BufferInfo()
            var activeBitrate = configuredBitrateBps
            onBitrateStatus(activeBitrate, adaptiveAvailable)
            try {
            while (isActive) {
                val requested = requestedBitrateBps
                if (adaptiveAvailable && requested != activeBitrate) {
                    try {
                        encoder.setParameters(android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, requested)
                        })
                        activeBitrate = requested
                    } catch (_: Exception) {
                        // Keep recording with the current codec; never restart or discard footage.
                        adaptiveAvailable = false
                    }
                    onBitrateStatus(activeBitrate, adaptiveAvailable)
                }
                val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                when (outIdx) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // backoff a bit
                        delay(5)
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = encoder.outputFormat
                        onFormat(format)
                        // Keep CSD in raw recovery files, explicitly tagged as metadata.
                        for (key in listOf("csd-0", "csd-1")) {
                            format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                                val data = ByteArray(buffer.remaining())
                                buffer.get(data)
                                onEncoded(EncodedVideoFrame(data, 0L, false, isConfig = true))
                            }
                        }
                    }
                    else -> {
                        if (outIdx >= 0) {
                            val ob = encoder.getOutputBuffer(outIdx)
                            if (ob != null && info.size > 0) {
                                val start = info.offset
                                val end = start + info.size
                                ob.position(start)
                                ob.limit(end)
                                val arr = ByteArray(info.size)
                                ob.get(arr)

                                if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && !loggedFirstKeyFrame) {
                                    Log.d(TAG, "First key frame from encoder: ${arr.size} bytes")
                                    loggedFirstKeyFrame = true
                                }

                                val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                // Forward exact codec bytes; MediaMuxer performs MP4 conversion.
                                val out = arr
                                onEncoded(EncodedVideoFrame(out, info.presentationTimeUs, isKeyFrame, isConfig))

                                if (isKeyFrame) {
                                    Log.d(TAG, "Emitted key frame: ${out.size} bytes at ${info.presentationTimeUs}us")
                                }
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                        }
                    }
                }
            }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!stopped) onError?.invoke(context.getString(R.string.fail_camera_configuration))
            }
        }
    }

}
