package com.fieldnotes.recorder.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import com.fieldnotes.recorder.capture.CaptureStatus
import com.fieldnotes.recorder.upload.UploadReadiness
import com.fieldnotes.recorder.upload.UploadStatus
import com.fieldnotes.recorder.upload.UploadScheduler
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import android.widget.FrameLayout
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import com.fieldnotes.recorder.R
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.fieldnotes.recorder.capture.SimplifiedCaptureService
import com.fieldnotes.recorder.capture.CaptureControls
import com.fieldnotes.recorder.crypto.CryptoEngine
import com.fieldnotes.recorder.upload.UploadQueue
import com.fieldnotes.recorder.util.PanicWipe
import com.fieldnotes.recorder.util.Prefs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DW-MainActivity"
    }

    private fun requiredPerms() = buildList {
        if (Prefs.isVideoEnabled(this@MainActivity)) add(Manifest.permission.CAMERA)
        if (Prefs.isAudioEnabled(this@MainActivity)) add(Manifest.permission.RECORD_AUDIO)
    }

    private lateinit var content: LinearLayout
    private lateinit var captureText: TextView
    private lateinit var captureDetail: TextView
    private lateinit var uploadText: TextView
    private lateinit var queueText: TextView
    private lateinit var recordButton: Button
    private lateinit var repairButton: Button
    private lateinit var audioToggle: CheckBox
    private lateinit var flashButton: Button
    private lateinit var lockHint: TextView
    private var cameraPreview: CameraPreviewView? = null
    private var statusJob: Job? = null
    private var startRequestedAt = 0L
    private var checkingStart = false
    private var recordingTorchRequested = false
    private var uploadBlocker: Int? = null
    private var banner: String? = null

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (hasPerms()) {
            requestCapture()
        } else {
            val denied = results.filter { !it.value && it.key in requiredPerms() }.keys.joinToString(", ") { getString(when (it) {
                Manifest.permission.CAMERA -> R.string.perm_camera
                Manifest.permission.RECORD_AUDIO -> R.string.perm_microphone
                Manifest.permission.POST_NOTIFICATIONS -> R.string.perm_notifications
                else -> R.string.perm_other
            }) }
            banner = getString(R.string.error_permissions_denied, denied)
            render()
        }
    }

    private val requestPreviewPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        try {
            setTaskDescription(ActivityManager.TaskDescription(com.fieldnotes.recorder.util.Disguise.taskLabel(this)))
        } catch (_: Exception) {}

        try { CryptoEngine.ensureSigningKey() } catch (e: Exception) { Log.e(TAG, "signing key init", e) }


        val (scroll, col) = Ui.screen(this)
        content = col
        setContentView(scroll)
        render()
    }

    private fun render() {
        cameraPreview?.dispose()
        cameraPreview = null
        content.removeAllViews()

        content.addView(Ui.h1(this, getString(R.string.app_name)))
        content.addView(Ui.caption(this, getString(if (Prefs.isAnonymousMode(this)) R.string.mode_anonymous else R.string.mode_evidence)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
        })

        // ── Recording card ────────────────────────────────────────────────
        val recCard = Ui.card(this)
        if (Prefs.isVideoEnabled(this)) {
            recCard.addView(Ui.sectionLabel(this, getString(R.string.camera_preview)))
            val frame = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(-1, dp(280))
            }
            val hint = Ui.caption(this, getString(R.string.preview_starting)).apply {
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                gravity = Gravity.CENTER
            }
            cameraPreview = CameraPreviewView(this, startRequestedAt != 0L) { message ->
                hint.visibility = if (message == null) View.GONE else View.VISIBLE
                if (message != null) hint.setText(message)
            }.also { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            frame.addView(hint, FrameLayout.LayoutParams(-1, -1))
            recCard.addView(frame)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                recCard.addView(Ui.button(this, getString(R.string.preview_allow_camera), Ui.Btn.SECONDARY).apply {
                    setOnClickListener { requestPreviewPermission.launch(Manifest.permission.CAMERA) }
                })
            }
        }
        audioToggle = Ui.checkbox(this, getString(R.string.set_enable_audio), Prefs.isAudioEnabled(this)).apply {
            setOnCheckedChangeListener { _, enabled ->
                if (!enabled && !Prefs.isVideoEnabled(this@MainActivity)) {
                    isChecked = true
                    Toast.makeText(this@MainActivity, R.string.capture_select_track, Toast.LENGTH_LONG).show()
                } else Prefs.setAudioEnabled(this@MainActivity, enabled)
            }
            isEnabled = !CaptureStatus.running && startRequestedAt == 0L
        }
        recCard.addView(audioToggle)
        recCard.addView(Ui.caption(this, getString(R.string.audio_option_help)))
        flashButton = Ui.button(this, getString(R.string.flash_on), Ui.Btn.SECONDARY).apply {
            visibility = if (Prefs.isVideoEnabled(this@MainActivity)) View.VISIBLE else View.GONE
            isEnabled = CaptureStatus.running && CaptureStatus.torchAvailable && !CaptureStatus.stopping
            setOnClickListener {
                if (CaptureStatus.running) {
                    CaptureControls.setTorch?.invoke(!CaptureStatus.torchEnabled)
                    // Give the camera callback time to report success before another tap.
                    isEnabled = false
                } else if (startRequestedAt == 0L && !checkingStart) {
                    val preview = cameraPreview
                    if (preview != null) preview.setTorch(!preview.torchEnabled)
                    updateFlashButton()
                    preview?.torchProblem?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                }
            }
        }
        recCard.addView(flashButton)
        captureText = Ui.body(this, getString(R.string.capture_checking))
        captureDetail = Ui.caption(this, "")
        recCard.addView(captureText)
        recCard.addView(captureDetail)
        recordButton = Ui.button(this, getString(R.string.action_start_recording)).apply {
            setOnClickListener { if (CaptureStatus.running) stopRecording() else onStartPressed() }
        }
        recCard.addView(recordButton)
        lockHint = Ui.body(this, getString(R.string.capture_lock_phone)).apply { visibility = View.GONE }
        recCard.addView(lockHint)
        content.addView(recCard)

        // ── Backup card ───────────────────────────────────────────────────
        val backupCard = Ui.card(this)
        backupCard.addView(Ui.sectionLabel(this, getString(R.string.section_backup)))
        val dest = backupDestination()
        backupCard.addView(Ui.body(this, dest ?: getString(R.string.backup_none)))
            backupCard.addView(Ui.caption(this, Prefs.exportStatus(this)))
        uploadText = Ui.body(this, getString(R.string.upload_checking))
        queueText = Ui.caption(this, "")
        backupCard.addView(uploadText)
        backupCard.addView(queueText)
        repairButton = Ui.button(this, getString(R.string.upload_fix_setup), Ui.Btn.SECONDARY).apply {
            setOnClickListener { fixUploadSetup() }
        }
        backupCard.addView(repairButton)
        backupCard.addView(Ui.button(this, getString(R.string.upload_retry), Ui.Btn.SECONDARY).apply {
            setOnClickListener {
                com.fieldnotes.recorder.capture.CaptureRecovery.request(this@MainActivity)
                UploadScheduler.retryNow(this@MainActivity)
            }
        })
        content.addView(backupCard)

        // ── Actions ───────────────────────────────────────────────────────
        content.addView(Ui.button(this, getString(R.string.action_settings), Ui.Btn.SECONDARY).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        content.addView(Ui.spacer(this, 8))
        content.addView(Ui.button(this, getString(R.string.action_panic_wipe), Ui.Btn.DANGER).apply {
            setOnClickListener { confirmPanic() }
        })
    }

    private fun backupDestination(): String? = when {
        !Prefs.ncBaseUrl(this).isNullOrBlank() && Prefs.uploadMethod(this) == "cloud" &&
            Prefs.cloudProvider(this) == "nextcloud" -> getString(R.string.backup_nextcloud)
        Prefs.cloudFolderUri(this) != null && Prefs.uploadMethod(this) == "saf" -> getString(R.string.backup_saf)
        Prefs.uploadMethod(this) == "cloud" -> getString(when (Prefs.cloudProvider(this)) {
            "dropbox" -> R.string.set_provider_dropbox
            "google_drive" -> R.string.set_provider_gdrive
            "webdav" -> R.string.set_provider_webdav
            "nextcloud" -> R.string.backup_nextcloud
            else -> R.string.backup_none
        })
        else -> null
    }

    private fun onStartPressed() {
        if (checkingStart || startRequestedAt != 0L) return
        if (com.fieldnotes.recorder.capture.CaptureRecovery.pending) {
            com.fieldnotes.recorder.capture.CaptureRecovery.request(this)
            return
        }

        checkingStart = true
        recordingTorchRequested = cameraPreview?.torchEnabled == true
        flashButton.isEnabled = false
        recordButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val blocker = withContext(Dispatchers.IO) { UploadReadiness.blocker(this@MainActivity) }
                if (blocker == null) requestCapture()
                else {
                    uploadBlocker = blocker
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.upload_blocked_title)
                        .setMessage(getString(blocker) + "\n\n" + getString(R.string.upload_local_warning))
                        .setPositiveButton(R.string.upload_fix_setup) { _, _ -> fixUploadSetup(resumeStart = true) }
                        .setNeutralButton(R.string.upload_record_local) { _, _ -> requestCapture() }
                        .setNegativeButton(R.string.action_cancel, null).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.upload_status_unknown), Toast.LENGTH_LONG).show()
            } finally {
                checkingStart = false
                recordButton.isEnabled = true
            }
        }
    }

    private fun requestCapture() {
        banner = null
        if (com.fieldnotes.recorder.capture.CaptureRecovery.pending) return
        if (!com.fieldnotes.recorder.capture.CaptureLimits.canStart(
                filesDir.usableSpace, com.fieldnotes.recorder.capture.CaptureLimits.reserveBytes(
                    if (Prefs.isVideoEnabled(this)) Prefs.videoTargetKbps(this) else 0,
                    if (Prefs.isAudioEnabled(this)) Prefs.audioKbps(this) else 0, Prefs.segmentDurationSeconds(this)))) {
            banner = getString(R.string.capture_storage_low); render(); return
        }
        if (!Prefs.isVideoEnabled(this) && !Prefs.isAudioEnabled(this)) {
            banner = getString(R.string.capture_select_track); render(); return
        }
        if (!hasPerms()) {
            requestPerms.launch((requiredPerms() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
            return
        }
        com.fieldnotes.recorder.util.CameraUtil.applyVideoPreset(this)
        if (Prefs.isVideoEnabled(this)) {
            val fps = Prefs.videoFps(this)
            val supported = runCatching { com.fieldnotes.recorder.util.CameraUtil.nativeFpsOptions(this) }.getOrDefault(emptyList())
            if (fps !in supported) {
                AlertDialog.Builder(this).setTitle(R.string.fail_camera_title)
                    .setMessage(getString(R.string.native_fps_unavailable, fps))
                    .setPositiveButton(R.string.upload_fix_setup) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                    .setNegativeButton(R.string.action_cancel, null).show()
                return
            }
        }
        beginRecording()
    }

    private fun fixUploadSetup(resumeStart: Boolean = false) {
        if (uploadBlocker == R.string.upload_block_choice || uploadBlocker == R.string.upload_block_key) {
            showUploadChoice(this) {
                render()
                if (resumeStart) onStartPressed()
            }
        } else if (CaptureStatus.running) {
            Toast.makeText(this, getString(R.string.settings_recording_active), Toast.LENGTH_LONG).show()
        } else startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun stopRecording() {
        if (!CaptureStatus.running || CaptureStatus.stopping) return
        startService(Intent(this, SimplifiedCaptureService::class.java).setAction(SimplifiedCaptureService.ACTION_STOP))
        // Keep showing STOPPING until the service finishes sealing, not IDLE on tap.
        CaptureStatus.stopping = true
    }

    private suspend fun refreshLiveStatus() {
        val now = SystemClock.elapsedRealtime()
        if (CaptureStatus.running) startRequestedAt = 0L
        if (startRequestedAt != 0L && now - startRequestedAt > 10_000) {
            startRequestedAt = 0L
            cameraPreview?.cancelRecordingRequest()
            banner = getString(R.string.fail_start_recording)
        }
        val video = if (CaptureStatus.running) CaptureStatus.videoEnabled else Prefs.isVideoEnabled(this)
        val audio = if (CaptureStatus.running) CaptureStatus.audioEnabled else Prefs.isAudioEnabled(this)
        val state = CaptureStatus.state(now, video, audio)
        if (CaptureStatus.running) cameraPreview?.recordingStarted()
        cameraPreview?.refresh()
        val label = when {
            startRequestedAt != 0L -> R.string.capture_starting
            else -> when (state) {
                CaptureStatus.State.IDLE -> R.string.status_idle
                CaptureStatus.State.STARTING -> R.string.capture_starting
                CaptureStatus.State.RECORDING -> when {
                    !video -> R.string.capture_live_audio
                    !audio -> R.string.capture_live_video
                    else -> R.string.capture_live_av
                }
                CaptureStatus.State.AUDIO_ONLY -> R.string.capture_missing_video
                CaptureStatus.State.VIDEO_ONLY -> R.string.capture_missing_audio
                CaptureStatus.State.STALLED -> R.string.capture_stalled
                CaptureStatus.State.STOPPING -> R.string.capture_stopping
            }
        }
        captureText.setText(label)
        val p = Ui.palette(this)
        captureText.setTextColor(if (state == CaptureStatus.State.RECORDING && CaptureStatus.problem == null) p.success else p.warning)
        captureDetail.text = listOfNotNull(banner, CaptureStatus.problem,
            com.fieldnotes.recorder.capture.CaptureRecovery.message()?.let { getString(it) },
            if (CaptureStatus.running) CaptureStatus.torchProblem else cameraPreview?.torchProblem?.let { getString(it) },
            if (CaptureStatus.running && Prefs.isVideoEnabled(this)) com.fieldnotes.recorder.capture.RecordingMeasurements.liveText(this) else null,
            if (CaptureStatus.running || CaptureStatus.savedSegments > 0) getString(R.string.capture_saved_segments, CaptureStatus.savedSegments) else null).joinToString("\n")
        captureDetail.visibility = if (captureDetail.text.isEmpty()) View.GONE else View.VISIBLE
        lockHint.visibility = if (state == CaptureStatus.State.RECORDING && CaptureStatus.problem == null) View.VISIBLE else View.GONE
        recordButton.setText(if (CaptureStatus.running) R.string.action_stop_recording else R.string.action_start_recording)
        recordButton.isEnabled = !checkingStart && startRequestedAt == 0L && !CaptureStatus.stopping &&
            !com.fieldnotes.recorder.capture.CaptureRecovery.pending
        audioToggle.isEnabled = !checkingStart && !CaptureStatus.running && startRequestedAt == 0L
        updateFlashButton()
        val snapshot = withContext(Dispatchers.IO) {
            Triple(UploadReadiness.blocker(this@MainActivity),
                UploadQueue.listPending(this@MainActivity).size, UploadQueue.queueSizeBytes(this@MainActivity))
        }
        val previousBlocker = uploadBlocker
        uploadBlocker = snapshot.first
        if (previousBlocker != null && uploadBlocker == null && snapshot.second > 0) {
            UploadScheduler.retryNow(this)
        }
        val confirmed = if (UploadStatus.lastTarget == com.fieldnotes.recorder.upload.DeliveryPolicy.target(this)) UploadStatus.verifiedFiles else 0
        val failure = snapshot.first ?: UploadStatus.failure
        val text = when {
            failure != null -> getString(failure)
            UploadStatus.active -> getString(R.string.upload_sending)
            snapshot.second > 0 -> getString(R.string.upload_waiting_worker)
            confirmed > 0 -> getString(if (UploadStatus.lastDeliveryWasSaf) R.string.upload_saved_saf else R.string.upload_verified)
            CaptureStatus.running -> getString(R.string.upload_waiting_segment)
            else -> getString(R.string.upload_no_receipt)
        }
        uploadText.text = text
        uploadText.setTextColor(if (failure != null) p.danger else p.textPrimary)
        queueText.text = getString(R.string.upload_queue_detail, snapshot.second, snapshot.third / (1024.0 * 1024),
            confirmed)
        repairButton.visibility = if (failure != null) View.VISIBLE else View.GONE
    }

    private fun updateFlashButton() {
        val recording = CaptureStatus.running
        val available = if (recording) CaptureStatus.torchAvailable else cameraPreview?.torchAvailable == true
        val enabled = if (recording) CaptureStatus.torchEnabled else cameraPreview?.torchEnabled == true
        flashButton.setText(when {
            recording && !available -> R.string.flash_unavailable
            enabled -> R.string.flash_off
            else -> R.string.flash_on
        })
        flashButton.isEnabled = available && !checkingStart && startRequestedAt == 0L && !CaptureStatus.stopping &&
            (if (recording) CaptureControls.setTorch != null else cameraPreview?.torchReady == true)
    }

    override fun onStart() {
        super.onStart()
        trackForegroundStart()
    }

    override fun onResume() {
        super.onResume()
        // Re-lock gate (audit H9): redirect to the lock UI if backgrounded past
        // the grace window; do NOT touch Prefs afterward on this path.
        if (enforceSessionLock()) return
        render()
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            while (isActive) {
                try { refreshLiveStatus() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { uploadText.setText(R.string.upload_status_unknown) }
                delay(2_000)
            }
        }
    }

    override fun onPause() {
        statusJob?.cancel()
        cameraPreview?.dispose()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        trackForegroundStop()
    }

    private fun beginRecording() {
        // Permission dialogs can close the idle camera before the service is started.
        CaptureControls.requestedTorch = Prefs.isVideoEnabled(this) && recordingTorchRequested
        startRequestedAt = SystemClock.elapsedRealtime()
        recordButton.isEnabled = false
        audioToggle.isEnabled = false
        lifecycleScope.launch {
            try {
                if (cameraPreview?.prepareRecording() == false) error("Preview camera did not close")
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, SimplifiedCaptureService::class.java))
                startRequestedAt = SystemClock.elapsedRealtime()
                banner = null
                cameraPreview?.refresh()
            } catch (e: CancellationException) {
                startRequestedAt = 0L
                cameraPreview?.cancelRecordingRequest()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "FAILED to start service", e)
                startRequestedAt = 0L
                banner = getString(R.string.fail_start_recording)
                render()
                Toast.makeText(this@MainActivity, getString(R.string.fail_start_recording), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasPerms(): Boolean = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun confirmPanic() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.panic_title))
            .setMessage(getString(R.string.panic_message))
            .setPositiveButton(getString(R.string.panic_confirm)) { _, _ -> executePanicWipe() }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun executePanicWipe() {
        val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val result = PanicWipe.executeBlocking(this)
            uiHandler.post {
                val message = if (result.keystoreCleared && result.recoveryRecorded && !result.artifactsRemain)
                    getString(R.string.panic_cleanup_done)
                else getString(R.string.panic_cleanup_incomplete)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.uninstall_title))
                    .setMessage(getString(R.string.uninstall_message))
                    .setPositiveButton(getString(R.string.uninstall_confirm)) { _, _ -> PanicWipe.promptSelfUninstall(this) }
                    .setNegativeButton(getString(R.string.uninstall_keep), null)
                    .show()
            }
        }.apply { isDaemon = true; name = "dw-panicwipe-ui" }.start()
    }

}
