package com.fieldnotes.recorder.capture

/** Target video rate only; never changes camera framing, timing or audio. */
object VideoBitratePolicy {
    fun requestedBps(width: Int, height: Int, fps: Int, mode: String, targetKbps: Int, bppMillis: Int, quality: String): Int {
        return when (mode) {
            "target" -> (targetKbps.coerceIn(200, 50000) * 1000)
            "bpp" -> {
                val bpp = bppMillis.toDouble() / 1000.0
                (bpp * width * height * fps).toInt().coerceIn(200_000, 50_000_000)
            }
            else -> {
                // auto: use existing quality tiers
                val pixels = width * height
                // Comparable bits per captured frame; audio is independent.
                (calculateBitrate(pixels, quality).toLong() * fps / 30).toInt().coerceAtLeast(200_000)
            }
        }
    }

    /**
     * Calculate bitrate based on resolution and user quality preference
     */
    private fun calculateBitrate(pixels: Int, quality: String): Int {
        return when (quality) {
            "standard" -> when {
                pixels <= 480_000 -> 800_000     // 480p: 0.8Mbps (standard quality)
                pixels <= 720_000 -> 1_500_000   // ~720p: 1.5Mbps
                pixels <= 1_000_000 -> 2_500_000 // ~1MP: 2.5Mbps
                pixels <= 2_000_000 -> 4_000_000 // ~2MP: 4Mbps
                else -> 5_500_000                // Higher: 5.5Mbps
            }
            "high" -> when {
                pixels <= 480_000 -> 1_500_000   // 480p: 1.5Mbps (high quality - default)
                pixels <= 720_000 -> 3_000_000   // ~720p: 3Mbps
                pixels <= 1_000_000 -> 4_500_000 // ~1MP: 4.5Mbps
                pixels <= 2_000_000 -> 6_000_000 // ~2MP: 6Mbps
                else -> 8_000_000                // Higher: 8Mbps
            }
            "maximum" -> when {
                pixels <= 480_000 -> 2_500_000   // 480p: 2.5Mbps (maximum quality)
                pixels <= 720_000 -> 5_000_000   // ~720p: 5Mbps
                pixels <= 1_000_000 -> 7_500_000 // ~1MP: 7.5Mbps
                pixels <= 2_000_000 -> 10_000_000 // ~2MP: 10Mbps
                else -> 12_000_000               // Higher: 12Mbps
            }
            else -> {
                // Unknown quality, use "high" tier without modifying stored preference
                val p = pixels
                when {
                    p <= 480_000 -> 1_500_000
                    p <= 720_000 -> 3_000_000
                    p <= 1_000_000 -> 4_500_000
                    p <= 2_000_000 -> 6_000_000
                    else -> 8_000_000
                }
            }
        }
    }

}
