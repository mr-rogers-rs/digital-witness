package com.fieldnotes.recorder.capture

class AudioTimingException : IllegalStateException("Microphone samples fell behind the capture clock")

/** PCM duration cannot be used to hide dropped microphone input after a reader stall. */
class AudioSampleClock(private val sampleRate: Int, private val maximumLagUs: Long) {
    private var startUs = -1L
    private var samples = 0L
    val endUs: Long get() = if (startUs < 0) 0 else startUs + samples * 1_000_000L / sampleRate

    init { require(sampleRate > 0 && maximumLagUs > 0) }

    fun next(frameCount: Int, readFinishedUs: Long): Long {
        require(frameCount > 0)
        if (startUs < 0) startUs = readFinishedUs - frameCount * 1_000_000L / sampleRate
        val pts = endUs
        val end = startUs + (samples + frameCount) * 1_000_000L / sampleRate
        // AudioRecord may hold a bounded unread ring. A larger lag means lost
        // samples or a failed input clock, not valid continuous audio to retime.
        if (readFinishedUs - end > maximumLagUs || end - readFinishedUs > 500_000L)
            throw AudioTimingException()
        samples += frameCount
        return pts
    }
}
