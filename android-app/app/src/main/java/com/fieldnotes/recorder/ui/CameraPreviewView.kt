package com.fieldnotes.recorder.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.fieldnotes.recorder.capture.CaptureStatus
import com.fieldnotes.recorder.capture.CaptureControls
import com.fieldnotes.recorder.capture.RecordingPreview
import com.fieldnotes.recorder.util.CameraUtil
import com.fieldnotes.recorder.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Idle camera view; while recording the same view displays the actual encoded video. */
class CameraPreviewView @JvmOverloads constructor(context: Context, recordingPending: Boolean = false, private val message: (Int?) -> Unit = {}) : TextureView(context), TextureView.SurfaceTextureListener {
    companion object {
        // Main-thread handoff between old/new activity views, including rotation and permission dialogs.
        private var lastIdleClosed = CompletableDeferred<Unit>().apply { complete(Unit) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var target: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest.Builder? = null
    var torchAvailable = false; private set
    var torchEnabled = false; private set
    var torchProblem: Int? = null; private set
    val torchReady: Boolean get() = torchAvailable && session != null && previewRequest != null &&
        !disposed && !recordingRequested && !CaptureStatus.running
    private var closed = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var opening = false
    private var disposed = false
    private var recordingRequested = recordingPending
    private var bufferWidth = 640
    private var bufferHeight = 480
    private var sensorRotation = 90
    private var idleWidth = 640
    private var idleHeight = 480
    private var decodedPreview = false
    var onAspectRatioChanged: ((Int, Int) -> Unit)? = null
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { if (display?.displayId == displayId) transform() }
    }

    init { surfaceTextureListener = this; contentDescription = context.getString(R.string.camera_preview) }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val dimensions = runCatching { CameraUtil.getOptimalVideoResolution(context, com.fieldnotes.recorder.util.Prefs.videoMaxHeight(context)) }
            .getOrDefault(640 to 480)
        bufferWidth = dimensions.first; bufferHeight = dimensions.second
        // Camera2 encoder sizes need not be valid TextureView preview sizes.
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sizes = manager.getCameraCharacteristics(CameraUtil.backCameraId(context))
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java).toList()
            val ratio = bufferWidth.toDouble() / bufferHeight
            val matching = sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - ratio) < 0.02 }
            val candidates = matching.ifEmpty { sizes }
            val size = (candidates.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { candidates })
                .maxBy { it.width.toLong() * it.height }
            bufferWidth = size.width; bufferHeight = size.height
        }
        sensorRotation = CameraUtil.getCameraInfo(context)?.orientation ?: 90
        idleWidth = bufferWidth; idleHeight = bufferHeight
        texture.setDefaultBufferSize(bufferWidth, bufferHeight)
        target = Surface(texture).also { surface ->
            RecordingPreview.attach(surface, { w, h -> handler.post {
                if (!disposed && CaptureStatus.running) { decodedPreview = true; bufferWidth = w; bufferHeight = h; transform() }
            } }) { handler.post { if (!disposed) message(R.string.preview_unavailable) } }
        }
        transform()
        refresh()
    }

    fun refresh() {
        if (disposed || target == null) return
        if (CaptureStatus.running || recordingRequested) {
            closeIdle()
        } else if (!opening && camera == null && closed.isCompleted) openIdle()
    }

    /** Wait for camera ownership to return before the recording service opens it. */
    suspend fun prepareRecording(): Boolean {
        recordingRequested = true
        message(R.string.preview_starting)
        closeIdle()
        return withTimeoutOrNull(3_000) { closed.await(); true } == true
    }

    fun cancelRecordingRequest() { recordingRequested = false; refresh() }

    fun recordingStarted() { recordingRequested = false }

    fun dispose() {
        disposed = true
        if (!recordingRequested && !CaptureStatus.running) CaptureControls.requestedTorch = false
        closeIdle()
        target?.let { RecordingPreview.detach(it) {} }
    }

    fun setTorch(enabled: Boolean): Boolean {
        if (!torchReady) return false
        val request = previewRequest ?: return false
        val value = session ?: return false
        val previous = request.get(CaptureRequest.FLASH_MODE) ?: CaptureRequest.FLASH_MODE_OFF
        return try {
            request.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            value.setRepeatingRequest(request.build(), null, handler)
            torchEnabled = enabled
            CaptureControls.requestedTorch = enabled
            torchProblem = null
            true
        } catch (_: Exception) {
            request.set(CaptureRequest.FLASH_MODE, previous)
            torchProblem = R.string.flash_failed
            false
        }
    }

    private fun openIdle() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message(R.string.preview_permission); return
        }
        val output = target ?: return
        opening = true
        val previous = lastIdleClosed
        // Every CameraDevice callback owns its own completion. A delayed close
        // from an older device must not release a newer camera's handoff wait.
        val completion = CompletableDeferred<Unit>()
        closed = completion
        lastIdleClosed = completion
        RecordingPreview.prepareIdle(output) {
            previous.invokeOnCompletion { handler.post { openIdleAfterRelease(completion) } }
        }
    }

    private fun openIdleAfterRelease(completion: CompletableDeferred<Unit>) {
        if (closed !== completion) { completion.complete(Unit); return }
        if (disposed || recordingRequested || CaptureStatus.running || target == null) {
            opening = false; completion.complete(Unit); return
        }
        // Permission can change while waiting for a previous view/camera to close.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            opening = false; completion.complete(Unit); message(R.string.preview_permission); return
        }
        try {
            decodedPreview = false
            bufferWidth = idleWidth; bufferHeight = idleHeight
            surfaceTexture?.setDefaultBufferSize(idleWidth, idleHeight)
            transform()
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(CameraUtil.backCameraId(context), object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed === completion) opening = false
                    if (closed !== completion || disposed || recordingRequested || CaptureStatus.running || target == null) { device.close(); return }
                    camera = device
                    val output = target!!
                    device.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (camera !== device || disposed || recordingRequested || CaptureStatus.running) { value.close(); return }
                            session = value
                            try {
                                val characteristics = manager.getCameraCharacteristics(device.id)
                                torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                                val requestedTorch = torchAvailable && CaptureControls.requestedTorch
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(output)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.FLASH_MODE, if (requestedTorch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    CameraUtil.applyFraming(context, characteristics, this)
                                }
                                value.setRepeatingRequest(request.build(), null, handler)
                                previewRequest = request
                                torchEnabled = requestedTorch
                                CaptureControls.requestedTorch = requestedTorch
                                torchProblem = null
                                message(null)
                            } catch (_: Exception) {
                                if (idleOwnsView(device, completion)) {
                                    message(R.string.preview_unavailable)
                                    closeIdle()
                                }
                            }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) {
                            value.close()
                            if (idleOwnsView(device, completion)) {
                                message(R.string.preview_unavailable)
                                closeIdle()
                            }
                        }
                    }, handler)
                }
                override fun onClosed(device: CameraDevice) { completion.complete(Unit) }
                override fun onDisconnected(device: CameraDevice) { failed(device) }
                override fun onError(device: CameraDevice, error: Int) { failed(device) }
                private fun failed(device: CameraDevice) {
                    val showFailure = closed === completion && !disposed && !recordingRequested && !CaptureStatus.running
                    if (closed === completion) opening = false
                    device.close()
                    if (camera === device) { camera = null; closeIdle() }
                    if (showFailure) message(R.string.preview_unavailable)
                }
            }, handler)
        } catch (_: Exception) {
            if (closed === completion) {
                opening = false
                if (!disposed && !recordingRequested && !CaptureStatus.running) message(R.string.preview_unavailable)
            }
            completion.complete(Unit)
        }
    }

    private fun idleOwnsView(device: CameraDevice, completion: CompletableDeferred<Unit>): Boolean =
        closed === completion && camera === device && !disposed && !recordingRequested && !CaptureStatus.running

    private fun closeIdle() {
        previewRequest = null; torchAvailable = false; torchEnabled = false
        runCatching { session?.close() }; session = null
        camera?.close(); camera = null
    }

    private fun transform() {
        if (width == 0 || height == 0) return
        val displayDegrees = when (display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        // Camera2 supplies TextureView's sensor transform. The display decoder
        // supplies raw encoded pixels instead, so only it needs sensor rotation.
        val automaticSensorRotation = !decodedPreview && sensorRotation % 180 != 0
        val inputWidth = if (automaticSensorRotation) bufferHeight else bufferWidth
        val inputHeight = if (automaticSensorRotation) bufferWidth else bufferHeight
        val rotation = ((if (decodedPreview) sensorRotation else 0) - displayDegrees + 360) % 360
        val rotated = rotation % 180 != 0
        val sourceWidth = if (rotated) inputHeight else inputWidth
        val sourceHeight = if (rotated) inputWidth else inputHeight
        onAspectRatioChanged?.invoke(sourceWidth, sourceHeight)
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val dest = RectF((width - sourceWidth * scale) / 2, (height - sourceHeight * scale) / 2,
            (width + sourceWidth * scale) / 2, (height + sourceHeight * scale) / 2)
        val matrix = Matrix()
        // TextureView starts by stretching the buffer to its bounds. Undo that, rotate, then fit.
        matrix.setScale(inputWidth.toFloat() / width, inputHeight.toFloat() / height)
        matrix.postRotate(rotation.toFloat())
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        matrix.postScale(scale, scale)
        matrix.postTranslate(dest.left, dest.top)
        setTransform(matrix)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { transform() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayListener, handler)
    }
    override fun onDetachedFromWindow() {
        context.getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onDetachedFromWindow()
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { if (!disposed) message(null) }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        disposed = true; closeIdle()
        if (!recordingRequested && !CaptureStatus.running) CaptureControls.requestedTorch = false
        val output = target; target = null
        // Keep both resources alive until Camera2 and the optional decoder have relinquished them.
        closed.invokeOnCompletion {
            if (output != null) RecordingPreview.detach(output) { output.release(); texture.release() }
            else texture.release()
        }
        return false
    }
}
