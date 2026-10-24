package com.fieldnotes.recorder.upload

/** Used only by the drain coordinator. Counts verified bytes, never changes media encoding. */
class TransferTuning {
    companion object {
        /** Per-transfer signal survives provider helpers that return false instead of throwing. */
        val serviceBackoff = ThreadLocal<java.util.concurrent.atomic.AtomicBoolean?>()
    }
    var limit = 1; private set
    private var baseline = 0.0
    private var cooldownUntil = 0L
    private var windowStart = 0L
    private var bytes = 0L
    private var mediaFiles = 0
    private var slowWindows = 0

    fun begin(now: Long) { resetWindow(now) }

    fun failed(now: Long) {
        limit = 1
        cooldownUntil = now + 10 * 60_000L
        slowWindows = 0
        resetWindow(now)
    }

    fun completed(size: Long, now: Long) {
        bytes += size
        if (size >= 256 * 1024) mediaFiles++
        val elapsed = now - windowStart
        if (elapsed <= 0) return
        if (limit == 1 && mediaFiles >= 1 && elapsed >= 500) {
            val rate = bytes * 1000.0 / elapsed
            baseline = if (baseline == 0.0 || now >= cooldownUntil) rate else .75 * baseline + .25 * rate
            if (now >= cooldownUntil) limit = 2
            resetWindow(now)
        } else if (limit == 2 && elapsed >= 15_000 && mediaFiles >= 2) {
            // Two slow windows avoid reacting to a single long file or radio fluctuation.
            val rate = bytes * 1000.0 / elapsed
            slowWindows = if (rate < baseline * .85) slowWindows + 1 else 0
            if (slowWindows >= 2) failed(now) else resetWindow(now)
        }
    }

    private fun resetWindow(now: Long) {
        windowStart = now
        bytes = 0
        mediaFiles = 0
    }
}
