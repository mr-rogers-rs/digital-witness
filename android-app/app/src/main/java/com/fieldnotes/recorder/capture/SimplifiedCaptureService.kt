package com.fieldnotes.recorder.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import com.fieldnotes.recorder.R
import android.content.Context
import android.content.Intent
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.fieldnotes.recorder.util.CameraUtil
import com.fieldnotes.recorder.util.Prefs
import com.fieldnotes.recorder.capture.EncodedVideoFrame
import com.fieldnotes.recorder.crypto.CryptoEngine
import com.fieldnotes.recorder.integrity.ProofManager
import com.fieldnotes.recorder.integrity.SensorTelemetry
import com.fieldnotes.recorder.upload.UploadQueue
import com.fieldnotes.recorder.upload.UploadScheduler
import com.fieldnotes.recorder.util.Mp4MetadataStripper
import com.fieldnotes.recorder.util.OemUtil
import java.io.File
import java.io.FileOutputStream
import android.content.pm.ServiceInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Captures codec samples, seals independently playable segments, and queues evidence for delivery. */
class SimplifiedCaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.fieldnotes.recorder.STOP_AND_SEAL"
        private const val TAG = "DW-SimpleCap"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "dw_simple_cap"
        private const val ALERT_CHANNEL_ID = "dw_alerts"
        private const val ALERT_NOTIFICATION_ID = 3
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val stopping = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var uploadJob: Job? = null
    private var healthJob: Job? = null
    private var sealJob: Job? = null
    private var journalJob: Job? = null
    @Volatile private var acceptingSamples = true
    @Volatile private var stopReason: String? = null
    private val liveUploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var journal: CaptureJournal? = null
    private val bufferedMediaBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var mediaFailure = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioCapture: AudioCapture? = null
    private var videoCapture: VideoCapture? = null
    @Volatile private var configuredVideoBitrateKbps = 0
    @Volatile private var adaptiveBitrate: AdaptiveBitrate? = null
    private var lastBitrateCheck = 0L
    private var audioEnabled = true
    private var videoEnabled = true

    private lateinit var sessionId: String
    private lateinit var workDir: File
    private lateinit var currentVideoFile: File
    private lateinit var currentAudioFile: File

    private var segmentStartTime = 0L
    private val segmentDurationMs get() = Prefs.segmentDurationSeconds(this) * 1000L
    private val isCapturing = AtomicBoolean(false)

    /**
     * Immutable snapshot of the per-segment timing clocks, swapped atomically
     * in [startNewSegment] and read once by encoder callbacks (audit H17).
     *
     * Why: the previous code kept [segmentStartTimeNanos] and
     * [segmentStartElapsedNanos] as bare mutable `var`s written by the upload
     * coroutine (in startNewSegment) and read by the audio/video encoder
     * callbacks on different threads. Without synchronization a callback
     * could read [segmentStartTimeNanos] from segment N while
     * [segmentStartElapsedNanos] was already advanced to segment N+1 — a torn
     * read that corrupts PTS / sensor-clock alignment for the whole segment.
     * An immutable snapshot swapped via a single `@Volatile` reference makes
     * the read all-or-nothing.
     */
    private data class SegmentClocks(val startNanos: Long, val startElapsedNanos: Long)
    @Volatile private var clocks: SegmentClocks = SegmentClocks(0L, 0L)

    /**
     * Guards the audio/video file-writer lifecycle (open / close / write)
     * against the segment-rotation race (audit H6). Without it,
     * [finishCurrentSegmentAndUpload] could close+null a writer on the upload
     * coroutine while an encoder callback on another thread was mid-`write`
     * (IOException) — or, after [startNewSegment] reopened writers, a late
     * old-segment frame could land in the new segment's raw file. All three
     * operations (callback write, finalize close, segment-start open) take
     * this lock so they are mutually exclusive.
     */
    private val writerLock = Any()
    private var recoveryLease: Any? = null

    // Audio/Video file writers
    private var audioWriter: FileOutputStream? = null
    private var videoWriter: FileOutputStream? = null
    private var audioFrameCount = 0
    private var videoFrameCount = 0
    // Alert de-dup so a persistent mic/AV stall warns once (not every segment).
    private var micStallAlerted = false
    private var fullStallAlerted = false

    private lateinit var proofManager: ProofManager
    private var telemetry: SensorTelemetry? = null
    private var isNewSession = true            // false when resuming a crashed recording
    private var beaconJob: Job? = null         // drand fetch; awaited (bounded) before H_0 seals
    private var attestJob: Job? = null         // evidence-mode hardware attestation, off the capture path
    private var firstSegmentSealed = false     // gate the one-time beacon await

    // Store individual audio frames for proper muxing (like the working CaptureService)
    private val audioFrames = mutableListOf<ByteArray>()
    private var aacConfigFrame: ByteArray? = null

    @Volatile private var audioOutputFormat: MediaFormat? = null
    @Volatile private var videoOutputFormat: MediaFormat? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SimplifiedCaptureService onCreate")
        if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) { stopSelf(); return }
        recoveryLease = CaptureRecovery.tryClaimCapture()
        if (recoveryLease == null) {
            CaptureStatus.problem = getString(R.string.recovery_running)
            stopSelf(); return
        }
        audioEnabled = Prefs.isAudioEnabled(this)
        videoEnabled = Prefs.isVideoEnabled(this)
        if (!audioEnabled && !videoEnabled) { stopSelf(); return }
        CaptureStatus.audioEnabled = audioEnabled
        CaptureStatus.videoEnabled = videoEnabled
        CaptureStatus.start(android.os.SystemClock.elapsedRealtime())
        CaptureControls.setTorch = { enabled -> if (!stopping.get()) videoCapture?.setTorch(enabled) }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                (if (videoEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
                    (if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        UploadScheduler.startLiveDrain(this, liveUploadScope)

        val anonymous = Prefs.isAnonymousMode(this)
        // Start an independent chain. Unresolved older journals are retained;
        // the lease prevents their recovery while this capture owns the writers.
        sessionId = Prefs.beginRecordingSession(this)
        isNewSession = true
        Log.i(TAG, "Capture session started")
        workDir = File(filesDir, "simple_capture").apply { mkdirs() }

            try {
                check(CaptureLimits.canStart(workDir.usableSpace, storageReserve()))
                journal = CaptureJournal.create(File(filesDir, CaptureJournal.ROOT), sessionId,
                    com.fieldnotes.recorder.upload.DeliveryPolicy.target(this), CameraUtil.getCameraInfo(this)?.orientation ?: 90,
                    Prefs.isAnonymousMode(this))
                CaptureRecovery.clearNotice()
            } catch (_: Exception) {
                alertFailure(getString(R.string.fail_recording_title), getString(R.string.capture_storage_low))
                Prefs.endRecordingSession(this)
                stopSelf(); return
            }


        // The ECDSA signing key is only used for evidence-mode proofs. Don't create
        // a persistent device-identifying key at all in anonymous mode.
        if (!anonymous) {
            try {
                CryptoEngine.ensureSigningKey()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize signing key", e)
                stopSelf()
                return
            }
        }
        proofManager = ProofManager(this, sessionId)

        // Sensor-fusion telemetry: continuously records IMU + environmental sensors (and
        // per-frame camera metadata) bound into each segment proof, so the footage can be
        // shown to be physically consistent (and thus not synthetic). Mode-aware.
        try {
            telemetry = SensorTelemetry(this, anonymous).also { t ->
                val (src, offset) = readCameraClock()
                t.setCameraClock(src, offset)
                t.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start sensor telemetry (non-fatal)", e)
        }

        com.fieldnotes.recorder.util.CameraUtil.applyVideoPreset(this)
        runCatching { RecordingMeasurements.begin(this) }

        Log.d(TAG, "Upload method: ${Prefs.uploadMethod(this)}")

        UploadScheduler.ensurePeriodicDrain(this)

        // Setup wakelock if enabled
        if (Prefs.isWakelockEnabled(this)) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dw:SimplifiedCapture")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire()
                Log.d(TAG, "Wakelock acquired")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to acquire wakelock", e)
            }
        }

        startCapture()
        healthJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                kotlinx.coroutines.delay(5_000)
                try {
                    if (workDir.usableSpace < storageReserve()) {
                        failAndStop(R.string.capture_storage_low)
                    }
                    if (CaptureStatus.state(android.os.SystemClock.elapsedRealtime(), videoEnabled, audioEnabled) == CaptureStatus.State.STALLED &&
                        android.os.SystemClock.elapsedRealtime() - CaptureStatus.startedAt >= 15_000) {
                        failAndStop(R.string.fail_stalled_body)
                    }
                    if (Prefs.isVideoEnabled(this@SimplifiedCaptureService) && RecordingMeasurements.belowMinimumFps() && CaptureStatus.problem == null)
                        alertFailure(getString(R.string.fail_camera_title), getString(R.string.native_fps_below_minimum))
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, createNotification())
                } catch (_: Exception) { /* UI and capture watchdog still expose failures. */ }
            }
        }
    }

    private fun storageReserve(): Long = CaptureLimits.reserveBytes(
        if (videoEnabled) maxOf(configuredVideoBitrateKbps, Prefs.videoTargetKbps(this)) else 0,
        if (audioEnabled) Prefs.audioKbps(this) else 0, Prefs.segmentDurationSeconds(this))

    private fun failAndStop(message: Int) {
        if (!mediaFailure) {
            mediaFailure = true
            alertFailure(getString(R.string.fail_recording_title), getString(message))
            stopAndSeal(resources.getResourceEntryName(message))
        }
    }

    /** Refuse additional samples visibly before an encoder or sealing stall exhausts the heap. */
    private fun acceptSample(video: Boolean, pts: Long, key: Boolean, data: ByteArray): Boolean {
        if (!acceptingSamples || com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return false
        val size = bufferedMediaBytes.addAndGet(data.size.toLong())
        if (size > CaptureLimits.MAX_BUFFER_BYTES) {
            bufferedMediaBytes.addAndGet(-data.size.toLong())
            failAndStop(R.string.capture_buffer_full)
            return false
        }
        return try {
            journal?.append(video, pts, key, data)
            true
        } catch (_: Exception) {
            bufferedMediaBytes.addAndGet(-data.size.toLong())
            failAndStop(R.string.capture_journal_failed)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Channel name/description follow the presentation stance (honest by
            // default, covert when the calculator disguise is opted in). See
            // util/Disguise. Lock-screen visibility is SECRET in both modes so
            // the body never appears to a passer-by or in cached lockscreen state.
            val chanName = com.fieldnotes.recorder.util.Disguise.channelName(this)
            val chanDesc = com.fieldnotes.recorder.util.Disguise.channelDescription(this)
            val channel = NotificationChannel(
                CHANNEL_ID,
                chanName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = chanDesc
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                chanName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = chanDesc
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    private var alertCounter = 0

    private fun alertFailure(title: String, message: String) {
        CaptureStatus.problem = message
        Log.e(TAG, "ALERT: $title - $message")

        // Vibrate: 3 short pulses
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 300, 200, 300), -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }

        // Show high-priority notification
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, ALERT_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
            }
            // Notification text follows the presentation stance: honest
            // ("Recording needs attention") by default, covert ("Sync paused")
            // when the calculator disguise is opted in. See util/Disguise.
            val notification = builder
                .setContentTitle(com.fieldnotes.recorder.util.Disguise.alertTitle(this))
                .setContentText(com.fieldnotes.recorder.util.Disguise.alertText(this))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .build()
            nm.notify(ALERT_NOTIFICATION_ID + alertCounter, notification)
            alertCounter = (alertCounter + 1) % 10 // cycle through 10 notification slots
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show alert notification", e)
        }
    }

    private fun createNotification(): Notification {
        val state = CaptureStatus.state(android.os.SystemClock.elapsedRealtime(), videoEnabled, audioEnabled)
        val captureLabel = when (state) {
            CaptureStatus.State.IDLE -> R.string.status_idle
            CaptureStatus.State.STARTING -> R.string.capture_starting
            CaptureStatus.State.RECORDING -> when {
                !videoEnabled -> R.string.capture_live_audio
                !audioEnabled -> R.string.capture_live_video
                else -> R.string.capture_live_av
            }
            CaptureStatus.State.AUDIO_ONLY -> R.string.capture_missing_video
            CaptureStatus.State.VIDEO_ONLY -> R.string.capture_missing_audio
            CaptureStatus.State.STALLED -> R.string.capture_stalled
            CaptureStatus.State.STOPPING -> R.string.capture_stopping
        }
        val uploadLabel = com.fieldnotes.recorder.upload.UploadStatus.failure
            ?: if (com.fieldnotes.recorder.upload.UploadStatus.active) R.string.upload_sending else R.string.upload_open_status
        val liveText = if (Prefs.isCalculatorDisguiseEnabled(this))
            com.fieldnotes.recorder.util.Disguise.serviceNotificationText(this)
            else getString(captureLabel) + "\n" + (CaptureStatus.problem ?: getString(uploadLabel))

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        // Foreground-service notification (required by Android). Its text
        // follows the presentation stance — honest "Field Recorder / Recording
        // in progress" by default, covert "System / Background sync" when the
        // calculator disguise is opted in (see util/Disguise). VISIBILITY_SECRET
        // hides the body on the lock screen in both modes.
        return builder
            .setContentTitle(com.fieldnotes.recorder.util.Disguise.serviceNotificationTitle(this))
            .setContentText(liveText)
            .setStyle(Notification.BigTextStyle().bigText(liveText))
            .setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                Intent(this, if (Prefs.isCalculatorDisguiseEnabled(this)) com.fieldnotes.recorder.ui.CalculatorActivity::class.java else com.fieldnotes.recorder.ui.LockScreenActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
                getString(R.string.action_stop_recording),
                android.app.PendingIntent.getService(this, 1,
                    Intent(this, SimplifiedCaptureService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            ).apply {
                if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true)
            }.build())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    private fun startCapture() {
        Log.i(TAG, "Starting capture session=$sessionId")

        isCapturing.set(true)
        if (journal != null) journalJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isCapturing.get()) {
                kotlinx.coroutines.delay(1_000)
                try { journal?.flush() } catch (_: Exception) { failAndStop(R.string.capture_journal_failed) }
            }
        }

        // For a fresh session, fetch the drand randomness beacon in parallel and persist
        // it before the first segment finalizes; it seeds H_0 with a verifiable lower time
        // bound. On resume we keep the previously-seeded chain, so skip it.
        if (isNewSession) {
            beaconJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val b = com.fieldnotes.recorder.network.DrandClient.fetchLatest(this@SimplifiedCaptureService)
                    // Only commit if still active: once the first segment seals H_0 this
                    // job is cancelled, and a late beacon must NOT change the seed (which
                    // would make later proofs' chainSeed inconsistent with H_0).
                    if (isActive && b != null) {
                        Prefs.setChainBeacon(this@SimplifiedCaptureService, b.toJson())
                        Log.i(TAG, "drand beacon stored for chain seed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Beacon fetch failed (non-fatal)", e)
                }
            }
        }

        // Evidence mode only: generate the hardware key-attestation chain OFF the capture
        // path (key generation can take up to ~1s and would otherwise stall segment
        // rotation, like inline OTS used to). We wait for the beacon job so the attestation
        // challenge uses the same drand randomness that seeds H_0, then stash the chain in
        // prefs for ProofManager to embed into the first proof and consume. Anonymous mode
        // never attests (the chain is device-identifying).
        if (isNewSession && !Prefs.isAnonymousMode(this)) {
            attestJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    beaconJob?.join() // so the challenge matches the committed chainSeed beacon
                    val randomnessHex = Prefs.chainBeacon(this@SimplifiedCaptureService)?.let {
                        runCatching { org.json.JSONObject(it).optString("randomness", "") }.getOrNull()
                    }?.takeIf { it.isNotEmpty() }
                    val challenge = java.security.MessageDigest.getInstance("SHA-256").apply {
                        update(sessionId.toByteArray(Charsets.UTF_8))
                        randomnessHex?.let { update(it.toByteArray(Charsets.UTF_8)) }
                    }.digest()
                    val chain = CryptoEngine.getAttestationChainBase64(challenge)
                    if (isActive && chain != null) {
                        val json = org.json.JSONObject().apply {
                            put("challengeAlgorithm", "SHA256(sessionId‖beaconRandomness)")
                            put("chain", org.json.JSONArray(chain))
                        }
                        Prefs.setChainAttestation(this@SimplifiedCaptureService, json.toString())
                        Log.i(TAG, "Hardware attestation chain stored for first proof")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Attestation generation failed (non-fatal)", e)
                }
            }
        }

        captureJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { CaptureDiagnostics.record(this@SimplifiedCaptureService, "recording_started", 0, 0) }
                startNewSegment()
                if (audioEnabled) startAudioCapture()
                if (videoEnabled) startVideoCapture()
            } catch (_: Exception) {
                failAndStop(R.string.fail_start_recording)
            }
        }

        uploadJob = CoroutineScope(Dispatchers.IO).launch {
            while (isCapturing.get()) {
                kotlinx.coroutines.delay(1000)
                if (isCapturing.get()) try {
                    journal?.flush()
                    checkForSegmentUpload()
                    adaptBitrate()
                } catch (_: Exception) { failAndStop(R.string.capture_journal_failed) }
            }
        }
    }

    private fun startNewSegment() {
        segmentStartTime = android.os.SystemClock.elapsedRealtime()
        val startNanos = System.nanoTime()
        val startElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        // Atomic swap of the whole clocks tuple so encoder callbacks reading
        // `clocks` see a consistent pair (audit H17).
        clocks = SegmentClocks(startNanos, startElapsedNanos)
        // Election capture uses a timestamped encrypted journal instead of raw elementary streams.
        if (journal != null) return
        // Note: frame lists and counts are cleared atomically in checkForSegmentUpload()
        // before this method is called, to prevent losing frames in the gap.
        // Reset first video frame tracker for each segment (under the video
        // lock so the encoder callback sees a consistent state).
        synchronized(videoFramesWithTimestamps) {
            firstVideoFrameTimeUs = null
        }
        // DON'T clear aacConfigFrame - we need it for each segment

        Log.d(TAG, "Segment start time captured: ${startNanos}ns for sync")

        // Opaque per-segment stem: never embed sessionId or a wall-clock timestamp in
        // on-disk filenames (audit A3). They would leak the capture time (and, in
        // evidence mode, link recordings) to a forensic examiner of the local files
        // AND to SAF-uploaded copies, which keep these names. A random stem also avoids
        // the collision the old second-resolution timestamp risked for fast segments.
        val stem = java.util.UUID.randomUUID().toString()
        currentAudioFile = File(workDir, "$stem.aac").apply {
            if (exists()) delete()
        }
        currentVideoFile = File(workDir, "$stem.h264").apply {
            if (exists()) delete()
        }

        try {
            // Open the new writers under writerLock so a late encoder write
            // from the previous segment can't race the close here (audit H6).
            synchronized(audioFrames) {
                synchronized(videoFramesWithTimestamps) {
                    synchronized(writerLock) {
                        audioWriter?.close()
                        videoWriter?.close()
                        audioWriter = if (audioEnabled) FileOutputStream(currentAudioFile).apply {
                            aacConfigFrame?.let { write(it) }
                            audioFramesWithTime.forEach { write(it.data) }
                        } else null
                        videoWriter = if (videoEnabled) FileOutputStream(currentVideoFile).apply {
                            for (key in listOf("csd-0", "csd-1")) {
                                videoOutputFormat?.getByteBuffer(key)?.duplicate()?.let { buffer ->
                                    val data = ByteArray(buffer.remaining())
                                    buffer.get(data)
                                    write(data)
                                }
                            }
                            videoFramesWithTimestamps.forEach { write(it.data) }
                        } else null
                    }
                }
            }

            Log.d(TAG, "Started new segment: audio=${currentAudioFile.name}, video=${currentVideoFile.name}")

            // DO NOT restart capture instances here - they should continue running
            // The existing instances will write to the new files through their callbacks

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start new segment", e)
        }
    }

    // Store audio frames with MONOTONIC presentation timestamps.
    data class TimestampedAudioFrame(val data: ByteArray, val captureTimeUs: Long)
    private val audioFramesWithTime = mutableListOf<TimestampedAudioFrame>()

    // Track first video frame timestamp for synchronization
    private var firstVideoFrameTimeUs: Long? = null

    private fun startAudioCapture() {
        audioCapture = AudioCapture(
            onEncoded = { audioFrame ->
            val encodedFrame = audioFrame.data
            if (!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) try {
                // Handle global frame counting (across all segments)
                val isFirstEverFrame = audioFrame.isConfig

                if (isFirstEverFrame) {
                    // First frame ever is AAC config (ASC) - capture it once
                    aacConfigFrame = encodedFrame
                    Log.d(TAG, "Got AAC config frame: ${encodedFrame.size} bytes")
                } else {
                    // Audio input and video surface PTS use the MONOTONIC clock.
                    val captureTimeUs = audioFrame.presentationTimeUs
                    RecordingMeasurements.audio(encodedFrame.size)

                    synchronized(audioFrames) {
                        if (!acceptSample(false, captureTimeUs, false, encodedFrame)) return@synchronized
                        audioFrameCount++
                        audioFrames.add(encodedFrame)
                        audioFramesWithTime.add(TimestampedAudioFrame(encodedFrame, captureTimeUs))
                        CaptureStatus.lastAudio = android.os.SystemClock.elapsedRealtime()
                    }
                    if (audioFrameCount <= 5) {
                        Log.d(TAG, "Got AAC frame $audioFrameCount: ${encodedFrame.size} bytes at wall-clock ${captureTimeUs}us")
                    }
                }

                // Still write to file for backup/debugging. Guard against the
                // segment-rotation race: finalize may close/null the writer on
                // another thread while we're here (audit H6).
                synchronized(writerLock) {
                    audioWriter?.write(encodedFrame)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to process audio frame", e)
            }
            },
            sampleRateHz = Prefs.audioSampleRate(this),
            bitrateKbps = Prefs.audioKbps(this),
            aacProfileName = Prefs.audioProfile(this),
            onFormat = {
                audioOutputFormat = it
                try { journal?.format(false, it) } catch (_: Exception) { failAndStop(R.string.capture_journal_failed) }
            },
            onError = { alertFailure(getString(R.string.fail_mic_title), getString(R.string.fail_mic_body)) },
            onTimingError = { failAndStop(R.string.capture_audio_timing_failed) }
        )
        audioCapture?.start()
    }

    // Video surface presentation timestamps use MONOTONIC; camera telemetry retains sensor timestamps.
    private val videoFramesWithTimestamps = mutableListOf<EncodedVideoFrame>()

    /**
     * Reads the back camera's SENSOR_INFO_TIMESTAMP_SOURCE so telemetry can record whether
     * camera-frame timestamps share the sensor clock (REALTIME → direct alignment) or use
     * an uptime clock (UNKNOWN → record an offset into the elapsedRealtime domain).
     */
    private fun readCameraClock(): Pair<String, Long?> {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return Pair("unknown", null)
            val src = cm.getCameraCharacteristics(backId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            when (src) {
                android.hardware.camera2.CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                    Pair("REALTIME", 0L)
                else ->
                    Pair("UNKNOWN", android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            }
        } catch (e: Exception) {
            Pair("unknown", null)
        }
    }

    private fun startVideoCapture() {
        configuredVideoBitrateKbps = 0
        adaptiveBitrate = null
        lastBitrateCheck = 0
        videoCapture = VideoCapture(this, onEncoded = onFrame@{ encodedFrame ->
            if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) return@onFrame
            RecordingPreview.onFrame(encodedFrame)
            try {
                // Always write raw data to backup file. Guard against the
                // segment-rotation race (audit H6): finalize may close/null
                // the writer on another thread while we're here.
                synchronized(writerLock) {
                    videoWriter?.write(encodedFrame.data)
                }

                // Codec configuration is never a timed media sample, even when
                // a vendor marks it as a keyframe or gives it a nonzero timestamp.
                if (encodedFrame.isConfig) return@onFrame
                RecordingMeasurements.video(encodedFrame.presentationTimeUs, encodedFrame.data.size)
                val frameWithSensorTime = encodedFrame

                synchronized(videoFramesWithTimestamps) {
                    if (!acceptSample(true, encodedFrame.presentationTimeUs, encodedFrame.isKeyFrame, encodedFrame.data)) return@onFrame
                    // Track first actual video frame for audio/video sync
                    if (firstVideoFrameTimeUs == null) {
                        firstVideoFrameTimeUs = encodedFrame.presentationTimeUs
                        Log.i(TAG, "First video frame arrived at ${encodedFrame.presentationTimeUs}us (monotonic clock)")
                    }

                    videoFramesWithTimestamps.add(frameWithSensorTime)
                    CaptureStatus.lastVideo = android.os.SystemClock.elapsedRealtime()
                    videoFrameCount++
                }

                // Log key frames
                if (videoFrameCount <= 3 || videoFrameCount % 50 == 0) {
                    Log.d(TAG, "Got video frame $videoFrameCount: ${encodedFrame.data.size} bytes at monotonic PTS ${encodedFrame.presentationTimeUs}us")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write video frame", e)
            }
        }, onFormat = {
            videoOutputFormat = it
            try { journal?.format(true, it) } catch (_: Exception) { failAndStop(R.string.capture_journal_failed) }
            RecordingPreview.onFormat(it)
            val targetBps = videoCapture?.configuredBitrateBps ?: 0
            configuredVideoBitrateKbps = targetBps / 1000
            RecordingMeasurements.videoFormat(it.getInteger(MediaFormat.KEY_WIDTH), it.getInteger(MediaFormat.KEY_HEIGHT), targetBps)
            if (adaptiveBitrate == null && Prefs.adaptiveVideoBitrate(this) && targetBps > 0) {
                adaptiveBitrate = AdaptiveBitrate(targetBps, android.os.SystemClock.elapsedRealtime())
                proofManager.videoBitratePolicy = "adaptive target: ceiling ${targetBps / 1000} kbps, floor ${adaptiveBitrate!!.minimumBps / 1000} kbps; target may vary within a segment; actual encoded bitrate may differ"
            }
        }, onError = { errorMsg ->
            alertFailure(getString(R.string.fail_camera_title), errorMsg)
        }, onCameraMeta = { m ->
            telemetry?.addCameraFrame(
                sensorTimestampNanos = m.sensorTimestampNanos,
                exposureTimeNanos = m.exposureTimeNanos,
                iso = m.iso,
                focalLengthMm = m.focalLengthMm,
                focusDistance = m.focusDistance,
                aeState = m.aeState,
                afState = m.afState,
                awbState = m.awbState,
                rollingShutterSkewNanos = m.rollingShutterSkewNanos,
                frameNumber = m.frameNumber
            )
        }, onBitrateStatus = { bps, available ->
            RecordingMeasurements.bitrateTarget(bps, Prefs.adaptiveVideoBitrate(this), available)
        }, onTorchStatus = { available, enabled, failed ->
            CaptureStatus.torchAvailable = available
            CaptureStatus.torchEnabled = enabled
            CaptureControls.requestedTorch = available && enabled
            CaptureStatus.torchProblem = if (failed) getString(R.string.flash_failed) else null
        })
        RecordingPreview.requestKeyFrame = { videoCapture?.requestKeyFrame() }
        videoCapture?.start()
    }

    private fun adaptBitrate() {
        val policy = adaptiveBitrate ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBitrateCheck < 15_000 || stopping.get()) return
        lastBitrateCheck = now
        val target = policy.update(now, UploadQueue.queueSizeBytes(this),
            RecordingMeasurements.producedBytes(), UploadQueue.deliveredBytes())
        videoCapture?.requestBitrate(target)
    }

    private suspend fun checkForSegmentUpload() {
        val elapsed = android.os.SystemClock.elapsedRealtime() - segmentStartTime
        if (elapsed < segmentDurationMs) return

        // Rotate the segment when we have EITHER audio or video. The previous
        // gate required audioFrameCount > 0, so a stalled mic (permission
        // revoked, mic held by another app, encoder error) meant segments never
        // rotated: nothing was ever persisted AND videoFramesWithTimestamps grew
        // without bound until the process OOM'd. Now video is still saved when
        // the mic dies, and a fully empty window resets the clock and alerts.
        val haveAudio = audioFrameCount > 0
        val haveVideo = videoFrameCount > 0

        if (!haveAudio && !haveVideo) {
            // Both encoders produced nothing this window. Reset the clock (via a
            // fresh segment) so we keep trying instead of spinning on an
            // ever-growing elapsed, and alert once until capture resumes.
            Log.w(TAG, "Segment timeout with no audio AND no video (elapsed=${elapsed}ms) — restarting segment")
            if (!fullStallAlerted) {
                alertFailure(getString(R.string.fail_stalled_title), getString(R.string.fail_stalled_body))
                fullStallAlerted = true
            }
            startNewSegment()
            return
        }
        fullStallAlerted = false

        if (audioEnabled && !haveAudio) {
            // Video-only segment: warn once that the mic is down. Evidence is
            // still being captured and uploaded, just without an audio track.
            if (!micStallAlerted) {
                alertFailure(getString(R.string.fail_mic_title), getString(R.string.fail_mic_body))
                micStallAlerted = true
            }
        } else {
            micStallAlerted = false
        }

        Log.i(TAG, "Segment ready for upload: elapsed=${elapsed}ms, audioFrames=$audioFrameCount, videoFrames=$videoFrameCount")
        val audioSnapshot: List<TimestampedAudioFrame>
        val videoSnapshot: List<EncodedVideoFrame>
        synchronized(audioFrames) {
            synchronized(videoFramesWithTimestamps) {
                val split = SegmentTimeline.splitIndex(videoFramesWithTimestamps.map { it.isKeyFrame })
                if (videoFramesWithTimestamps.isNotEmpty() && split == 0) {
                    // Do not cut through a GOP. Ask for a sync frame and bound
                    // the wait if an encoder fails to deliver one.
                    videoCapture?.requestKeyFrame()
                    if (elapsed > segmentDurationMs + 15_000) {
                        alertFailure(getString(R.string.fail_recording_title), getString(R.string.fail_mp4_creation))
                        stopAndSeal("keyframe_timeout")
                    }
                    return
                }
                val boundary = videoFramesWithTimestamps.getOrNull(split)?.presentationTimeUs
                val audioSplit = if (boundary == null) audioFramesWithTime.size else
                    audioFramesWithTime.indexOfFirst { it.captureTimeUs >= boundary }
                        .let { if (it < 0) audioFramesWithTime.size else it }
                audioSnapshot = audioFramesWithTime.take(audioSplit)
                videoSnapshot = videoFramesWithTimestamps.take(split)
                audioFrames.subList(0, audioSplit).clear()
                audioFramesWithTime.subList(0, audioSplit).clear()
                videoFramesWithTimestamps.subList(0, split).clear()
                audioFrameCount = audioFramesWithTime.size
                videoFrameCount = videoFramesWithTimestamps.size
                bufferedMediaBytes.addAndGet(-audioSnapshot.sumOf { it.data.size.toLong() } - videoSnapshot.sumOf { it.data.size.toLong() })
            }
        }
        val snapshotAudioFrameCount = audioSnapshot.size
        val snapshotVideoFrameCount = videoSnapshot.size
        if (finishCurrentSegmentAndUpload(audioSnapshot, videoSnapshot, snapshotAudioFrameCount, snapshotVideoFrameCount))
            startNewSegment()
        else failAndStop(R.string.fail_segment_processing)
    }

    private suspend fun finishCurrentSegmentAndUpload(
        audioSnapshot: List<TimestampedAudioFrame>,
        videoSnapshot: List<EncodedVideoFrame>,
        snapshotAudioFrameCount: Int,
        snapshotVideoFrameCount: Int
    ): Boolean {
        try {
            Log.i(TAG, "Finishing segment: audio=$snapshotAudioFrameCount frames, video=$snapshotVideoFrameCount frames")

            // Close the segment's writers under writerLock so concurrent
            // encoder writes can't land on a closing/closed stream (audit H6).
            // After this, encoder writes are no-ops (writer is null) until
            // startNewSegment reopens them.
            synchronized(writerLock) {
                audioWriter?.flush()
                videoWriter?.flush()
                audioWriter?.close()
                videoWriter?.close()
                audioWriter = null
                videoWriter = null
            }

            // Opaque filename (audit A3): no sessionId/timestamp on disk or in the
            // SAF-uploaded copy. The proof carries sessionId/segmentIndex; the verifier
            // pairs artifacts by content hash, so the name need not be meaningful.
            val stem = java.util.UUID.randomUUID().toString()
            journal?.beginSeal(stem, audioSnapshot.lastOrNull()?.captureTimeUs ?: -1L,
                videoSnapshot.lastOrNull()?.presentationTimeUs ?: -1L)
            val mp4File = File(workDir, "$stem.mp4")

            if (createMp4File(mp4File, audioSnapshot, videoSnapshot)) {
                // In anonymous mode, strip identifying MP4 metadata (encoder string,
                // creation date, GPS atoms) before hashing or uploading.
                if (Prefs.isAnonymousMode(this@SimplifiedCaptureService)) {
                    if (Mp4MetadataStripper.stripInPlace(mp4File)) {
                        Log.d(TAG, "MP4 metadata stripped (anonymous mode)")
                    } else {
                        Log.w(TAG, "MP4 metadata stripping failed — proceeding with original")
                    }
                }

                // New session: give the drand beacon a brief, bounded chance to land
                // before the first segment seals H_0, then cancel it so a late beacon can't
                // retroactively change the seed. Resumes already have a sealed H_0 and skip.
                if (isNewSession && !firstSegmentSealed) {
                    kotlinx.coroutines.withTimeoutOrNull(3000) { beaconJob?.join() }
                    beaconJob?.cancel()
                    firstSegmentSealed = true
                }

                // Snapshot this segment's sensor telemetry into a sidecar; its hash is
                // committed into the segment proof below. Read the immutable clocks
                // tuple once so the two values can't be torn by a concurrent segment
                // rotation (audit H17).
                val segClocks = clocks
                val segmentEndPtsUs = (android.os.SystemClock.elapsedRealtimeNanos() - segClocks.startElapsedNanos) / 1000
                val telemetryFile = try {
                    telemetry?.writeSegment(
                        File(mp4File.parent, mp4File.nameWithoutExtension + ".telemetry.json"),
                        segmentStartPtsUs = 0L,
                        segmentEndPtsUs = segmentEndPtsUs,
                        segmentStartElapsedNanos = segClocks.startElapsedNanos
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Telemetry snapshot failed (non-fatal)", e)
                    null
                }

                val optRes = CameraUtil.getOptimalVideoResolution(this@SimplifiedCaptureService, Prefs.videoMaxHeight(this@SimplifiedCaptureService))
                val proofFile = proofManager.generateProof(
                    mp4File = mp4File,
                    resolution = "${optRes.first}x${optRes.second}",
                    fps = Prefs.videoFps(this@SimplifiedCaptureService),
                    videoBitrateKbps = configuredVideoBitrateKbps,
                    audioSampleRate = if (audioEnabled) Prefs.audioSampleRate(this@SimplifiedCaptureService) else 0,
                    audioBitrateKbps = if (audioEnabled) Prefs.audioKbps(this@SimplifiedCaptureService) else 0,
                    telemetryFile = telemetryFile
                )

                // Note: the .ots is no longer produced inline — OpenTimestamps stamping is
                // deferred to the UploadWorker (off the capture path) which stamps the
                // recorded chain hash and enqueues the resulting .ots for upload itself.

                checkNotNull(proofFile) { "Proof creation failed; source retained for recovery" }
                check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress)

                // Always encrypt into the upload queue for cloud delivery / local retention.
                val sources = listOfNotNull(mp4File, proofFile, telemetryFile)
                val committed = UploadQueue.enqueueBundle(this@SimplifiedCaptureService, sources, if (journal != null) stem else null)

                // Raw intermediate streams are already muxed into the MP4 — always safe to drop.
                if (::currentAudioFile.isInitialized) currentAudioFile.delete()
                if (::currentVideoFile.isInitialized) currentVideoFile.delete()

                if (committed != null) {
                    journal?.completeSeal()
                    if (journal != null) UploadQueue.releasePublication(this@SimplifiedCaptureService, stem)
                    CaptureStatus.savedSegments++
                    Log.i(TAG, "Segment encrypted into upload queue")
                    // The encrypted copy is durable; delete each cleartext original
                    // only once its encrypted counterpart exists.
                    sources.forEach { it.delete() }
                } else {
                    // Encryption failed. Do NOT delete the only copy of the evidence —
                    // leave the originals in workDir (covered by PanicWipe) so they can
                    // be recovered on a later pass instead of being lost outright.
                    Log.e(TAG, "Failed to encrypt segment into queue — cleartext retained for recovery")
                    alertFailure(getString(R.string.fail_encrypt_title), getString(R.string.fail_encrypt_body))
                    return false
                }

                UploadScheduler.triggerDrain(this@SimplifiedCaptureService)
                return true
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to finish and upload segment", e)
            alertFailure(getString(R.string.fail_recording_title), getString(R.string.fail_segment_processing))
        }
        return false
    }

    private fun createMp4File(
        outputFile: File,
        audioTimedFrames: List<TimestampedAudioFrame>,
        videoFramesCopy: List<EncodedVideoFrame>
    ): Boolean = try {
        SegmentMuxer.write(
            outputFile,
            audioOutputFormat,
            videoOutputFormat,
            audioTimedFrames.map { MuxSample(it.data, it.captureTimeUs, false) },
            videoFramesCopy.filterNot { it.isConfig }.map {
                MuxSample(it.data, it.presentationTimeUs, it.isKeyFrame)
            },
            // Bound this clip, not the elapsed length of the entire session.
            // A later ten-second clip must not accept a minute of A/V clock skew.
            maxDurationUs = minOf(
                (android.os.SystemClock.elapsedRealtime() - CaptureStatus.startedAt + 10_000) * 1000,
                maxOf(audioTimedFrames.lastOrNull()?.captureTimeUs?.minus(audioTimedFrames.first().captureTimeUs) ?: 0,
                    videoFramesCopy.lastOrNull()?.presentationTimeUs?.minus(videoFramesCopy.first().presentationTimeUs) ?: 0) +
                    SegmentTimeline.MAX_START_SKEW_US),
            orientation = CameraUtil.getCameraInfo(this)?.orientation ?: 90
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create playable MP4; raw sources retained", e)
        alertFailure(getString(R.string.fail_recording_title), getString(R.string.fail_mp4_creation))
        false
    }

    private fun stopAndSeal(reason: String = "user_stop") {
        if (!stopping.compareAndSet(false, true)) return
        stopReason = reason
        CaptureStatus.stopping = true
        isCapturing.set(false)
        sealJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                captureJob?.join()
                runCatching { CaptureDiagnostics.record(this@SimplifiedCaptureService, reason, bufferedMediaBytes.get(), CaptureStatus.savedSegments) }
                uploadJob?.join() // let an already-started seal finish; never cancel it
                journalJob?.join()
                audioCapture?.stopAndDrain()
                videoCapture?.stopAndDrain()
                if (!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) {
                    journal?.flush()
                    val audio = synchronized(audioFrames) { audioFramesWithTime.toList() }
                    val video = synchronized(videoFramesWithTimestamps) { videoFramesWithTimestamps.toList() }
                    if ((audio.isNotEmpty() || video.isNotEmpty()) && journal?.state?.has("seal") != true) {
                        finishCurrentSegmentAndUpload(audio, video, audio.size, video.size)
                    }
                    journal?.discardIfEmpty()
                    Prefs.endRecordingSession(this@SimplifiedCaptureService)
                    UploadScheduler.triggerDrain(this@SimplifiedCaptureService)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop could not seal all evidence; retained for recovery", e)
                CaptureStatus.problem = getString(R.string.fail_segment_processing)
            } finally {
                runCatching { RecordingMeasurements.finish(this@SimplifiedCaptureService) }
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SimplifiedCaptureService onDestroy")
        stopping.set(true) // A cancelled seal must not start another stop/seal owner during teardown.
        CaptureRecovery.waitForWriters()
        synchronized(audioFrames) { synchronized(videoFramesWithTimestamps) { acceptingSamples = false } }
        CaptureStatus.running = false
        CaptureStatus.stopping = false
        CaptureStatus.torchAvailable = false
        CaptureStatus.torchEnabled = false
        CaptureControls.setTorch = null
        CaptureControls.requestedTorch = false
        liveUploadScope.cancel()
        // Durable WorkManager fallback finishes any tail after the recording service stops.
        if (!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) UploadScheduler.finishRecordingUploads(this)
        RecordingPreview.end()

        isCapturing.set(false)
        // Clean stop: mark the chain session inactive so the next launch begins a new
        // recording. A hard process kill skips onDestroy, leaving it active → resume.
        // Only stopAndSeal marks a clean stop. Unexpected destruction preserves recovery state.

        try {
            audioCapture?.stop()
            videoCapture?.stop()
            telemetry?.stop()
            telemetry = null
            synchronized(writerLock) {
                audioWriter?.close()
                videoWriter?.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping capture", e)
        }

        // Release encoder formats for a fresh start next time
        audioOutputFormat = null
        videoOutputFormat = null
        configuredVideoBitrateKbps = 0
        Log.d(TAG, "Cleared encoder formats on service destroy")

        healthJob?.cancel()
        captureJob?.cancel()
        uploadJob?.cancel()
        journalJob?.cancel()
        beaconJob?.cancel()
        attestJob?.cancel()
        if (!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) {
            // A service destruction is not necessarily a process death. Join its surviving writers
            // before handing the same journal to recovery in this process.
            val lease = recoveryLease
            recoveryLease = null
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    captureJob?.join(); uploadJob?.join(); journalJob?.join(); sealJob?.join()
                    if (!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) {
                    if (stopReason == null) runCatching {
                        CaptureDiagnostics.record(this@SimplifiedCaptureService, "service_destroyed", bufferedMediaBytes.get(), CaptureStatus.savedSegments)
                    }
                    runCatching { journal?.flush() }
                    runCatching { Prefs.endRecordingSession(this@SimplifiedCaptureService) }
                    }
                } finally {
                    lease?.let { CaptureRecovery.releaseCapture(it) }
                    if (!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress)
                        CaptureRecovery.request(this@SimplifiedCaptureService)
                }
            }
        } else {
            recoveryLease?.let { CaptureRecovery.releaseCapture(it) }
            recoveryLease = null
        }
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopAndSeal()
        return START_NOT_STICKY
    }


}
