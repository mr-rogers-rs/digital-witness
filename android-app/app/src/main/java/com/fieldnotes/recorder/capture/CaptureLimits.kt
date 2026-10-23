package com.fieldnotes.recorder.capture

/** Leave enough disk for mux, encrypted publication and an upload temp without deleting queued evidence. */
object CaptureLimits {
    const val MAX_BUFFER_BYTES = 24L * 1024 * 1024
    const val MIN_FREE_BYTES = 128L * 1024 * 1024
    fun reserveBytes(videoKbps: Int, audioKbps: Int, segmentSeconds: Int): Long =
        maxOf(MIN_FREE_BYTES, (videoKbps.coerceIn(0, 100_000).toLong() + audioKbps.coerceIn(0, 1000)) *
            125L * segmentSeconds.coerceIn(5, 600) * 5L)
    fun canStart(availableBytes: Long, reserveBytes: Long): Boolean = availableBytes >= reserveBytes
}
