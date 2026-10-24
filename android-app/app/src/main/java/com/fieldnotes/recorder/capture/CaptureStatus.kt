package com.fieldnotes.recorder.capture

/** Process-local observations, never a persisted claim that capture is running. */
object CaptureStatus {
    enum class State { IDLE, STARTING, RECORDING, AUDIO_ONLY, VIDEO_ONLY, STALLED, STOPPING }
    @Volatile var running = false
    @Volatile var stopping = false
    @Volatile var startedAt = 0L
    @Volatile var lastAudio = 0L
    @Volatile var lastVideo = 0L
    @Volatile var savedSegments = 0
    @Volatile var problem: String? = null
    @Volatile var audioEnabled = true
    @Volatile var videoEnabled = true
    @Volatile var torchAvailable = false
    @Volatile var torchEnabled = false
    @Volatile var torchProblem: String? = null

    /** Recovery/setup can block a new start, never the Stop control of a live session. */
    fun recordingControlEnabled(startPending: Boolean, recoveryPending: Boolean): Boolean =
        if (running) !stopping else !startPending && !stopping && !recoveryPending

    fun start(now: Long) {
        stopping = false; startedAt = now; lastAudio = 0; lastVideo = 0
        savedSegments = 0; problem = null; running = true
        torchAvailable = false; torchEnabled = false; torchProblem = null
    }

    @JvmOverloads
    fun state(now: Long, videoExpected: Boolean, audioExpected: Boolean = true): State {
        if (!running) return State.IDLE
        if (stopping) return State.STOPPING
        val audio = lastAudio > 0 && now - lastAudio < 5_000
        val video = lastVideo > 0 && now - lastVideo < 5_000
        if ((audioExpected || videoExpected) && (!audioExpected || audio) && (!videoExpected || video)) return State.RECORDING
        if (now - startedAt < 10_000) return State.STARTING
        if (audio) return State.AUDIO_ONLY
        if (videoExpected && video) return State.VIDEO_ONLY
        return State.STALLED
    }
}
