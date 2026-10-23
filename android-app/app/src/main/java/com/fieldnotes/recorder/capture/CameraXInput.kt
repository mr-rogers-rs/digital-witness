package com.fieldnotes.recorder.capture

import android.content.Context
import android.hardware.camera2.*
import android.os.Build
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.util.Prefs
import kotlinx.coroutines.CompletableDeferred

/** Experimental camera-session backend. The continuous codec/muxer remains shared with Camera2. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraXInput(private val context: Context, private val onError: (String) -> Unit,
    private val onTorchStatus: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val initialTorch: Boolean = false) {
    private val executor = ContextCompat.getMainExecutor(context)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var surfaceProvided = false
    private var activeCamera: androidx.camera.core.Camera? = null
    private var torchEnabled = false

    fun setTorch(enabled: Boolean) = executor.execute {
        val camera = activeCamera ?: return@execute
        if (closed || !camera.cameraInfo.hasFlashUnit()) return@execute
        val result = camera.cameraControl.enableTorch(enabled)
        result.addListener({
            if (!closed) try {
                result.get(); torchEnabled = enabled
                onTorchStatus(true, enabled, false)
            } catch (_: Exception) { onTorchStatus(true, torchEnabled, true) }
        }, executor)
    }
    @Volatile private var closed = false
    private val released = CompletableDeferred<Unit>()

    /** Call on main. The supplied surface stays borrowed until close() completes. */
    fun start(surface: Surface, size: Size, cameraId: String, metadata: CameraCaptureSession.CaptureCallback?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        owner.registry.currentState = Lifecycle.State.CREATED
        val future = ProcessCameraProvider.getInstance(context.applicationContext)
        future.addListener({
            if (closed) return@addListener
            try {
                val cameras = future.get().also { provider = it }
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val chars = cm.getCameraCharacteristics(cameraId)
                val builder = Preview.Builder().setTargetRotation(Surface.ROTATION_0)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        // Never silently change the dimensions of an already-configured encoder.
                        .setResolutionFilter { sizes, _ -> sizes.filter { it == size } }.build())
                val interop = Camera2Interop.Extender(builder)
                // CameraX owns Preview's request template. Use the public request option
                // to tell the camera's 3A routines that this stream is recording video.
                interop.setCaptureRequestOption(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                val nativeRange = CameraRequestPolicy.fpsRange(chars, Prefs.videoFps(context))
                    ?: error(context.getString(R.string.native_fps_unavailable, Prefs.videoFps(context)))
                nativeRange.let {
                    builder.setTargetFrameRate(it)
                    interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                val stabilization = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                if (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(stabilization) == true)
                    interop.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilization)
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                    interop.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, it)
                }
                if (Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1f) == true)
                    interop.setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                if (Build.VERSION.SDK_INT >= 31 && chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                        ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
                    interop.setCaptureRequestOption(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
                metadata?.let { interop.setSessionCaptureCallback(it) }
                val useCase = builder.build().also { preview = it }
                useCase.setSurfaceProvider(executor) { request ->
                    if (closed || request.resolution != size || surfaceProvided) {
                        request.willNotProvideSurface()
                        if (!closed) onError(context.getString(R.string.camera_backend_failed))
                    } else {
                        surfaceProvided = true
                        request.provideSurface(surface, executor) {
                            released.complete(Unit)
                            if (!closed) onError(context.getString(R.string.camera_backend_failed))
                        }
                    }
                }
                val selector = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }.build()
                // Service-owned lifecycle: screen-off/activity backgrounding does not end capture.
                owner.registry.currentState = Lifecycle.State.STARTED
                val camera = cameras.bindToLifecycle(owner, selector, useCase)
                activeCamera = camera
                onTorchStatus(camera.cameraInfo.hasFlashUnit(), false, false)
                if (initialTorch && camera.cameraInfo.hasFlashUnit()) setTorch(true)
                camera.cameraInfo.cameraState.observe(owner) { state ->
                    if (!closed && state.error != null) onError(context.getString(R.string.camera_backend_failed))
                }
            } catch (e: Exception) {
                if (!closed) onError(context.getString(R.string.camera_backend_failed))
                close()
            }
        }, executor)
    }

    fun close(): CompletableDeferred<Unit> {
        closed = true
        onTorchStatus(false, false, false)
        val action = Runnable {
            preview?.let { useCase ->
                useCase.setSurfaceProvider(null)
                provider?.unbind(useCase)
            }
            preview = null
            activeCamera = null
            owner.registry.currentState = Lifecycle.State.DESTROYED
            if (!surfaceProvided) released.complete(Unit)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else executor.execute(action)
        return released
    }
}
