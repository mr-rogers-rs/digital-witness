package com.fieldnotes.recorder.capture

/** Hardware requests only: no frame dropping, resampling or faster-range fallback. */
object NativeCapturePolicy {
    fun durationSupports(nanos: Long, fps: Int): Boolean =
        fps in 10..30 && nanos >= 0 && (nanos == 0L || nanos <= 1_000_000_000L / fps + 1L)

    fun range(ranges: List<Pair<Int, Int>>, requested: Int): Pair<Int, Int>? {
        if (requested !in 10..30) return null
        return ranges.filter { it.first >= 10 && it.first <= it.second && it.second == requested }
            .maxByOrNull { it.first } // Prefer fixed FPS, otherwise honor the advertised 10+ range.
    }

    fun normalSize(sizes: List<Pair<Int, Int>>, sensorRatio: Double): Pair<Int, Int> {
        require(sensorRatio > 0)
        return sizes.filter { kotlin.math.abs(it.first.toDouble() / it.second - sensorRatio) / sensorRatio < 0.02 }
            .maxByOrNull { it.first.toLong() * it.second }
            ?: error("No compatible full-aspect native video mode")
    }
}
