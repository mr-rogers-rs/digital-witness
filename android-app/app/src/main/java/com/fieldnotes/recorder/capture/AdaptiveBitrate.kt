package com.fieldnotes.recorder.capture

/** Queue pressure in seconds of measured media, with slow changes and offline protection. */
class AdaptiveBitrate(val ceilingBps: Int, private val startedMs: Long) {
    val minimumBps = maxOf(1_500_000, ceilingBps * 3 / 4).coerceAtMost(ceilingBps)
    var targetBps = ceilingBps; private set
    private var lastMs = startedMs
    private var lastProduced = 0L
    private var lastDelivered = 0L
    private var lastQueue = 0L
    private var deliveredAt = Long.MIN_VALUE
    private var highSince = -1L
    private var lowSince = -1L
    private var changedAt = startedMs
    private var bytesPerSecond = ceilingBps / 8.0

    init { require(ceilingBps > 0) }

    fun update(nowMs: Long, queuedBytes: Long, producedBytes: Long, deliveredBytes: Long): Int {
        require(queuedBytes >= 0 && producedBytes >= 0 && deliveredBytes >= 0)
        if (nowMs <= lastMs) return targetBps
        val elapsed = nowMs - lastMs
        val produced = producedBytes - lastProduced
        if (produced > 0) bytesPerSecond = .75 * bytesPerSecond + .25 * produced * 1000.0 / elapsed
        if (deliveredBytes > lastDelivered) deliveredAt = nowMs
        val progressing = deliveredAt != Long.MIN_VALUE && nowMs - deliveredAt <= 45_000
        val backlogSeconds = queuedBytes / bytesPerSecond.coerceAtLeast(1.0)
        val growing = queuedBytes > lastQueue
        highSince = if (backlogSeconds >= 90 && growing && progressing) {
            if (highSince < 0) nowMs else highSince
        } else -1L
        lowSince = if (backlogSeconds <= 20) {
            if (lowSince < 0) nowMs else lowSince
        } else -1L
        val step = maxOf(250_000, ceilingBps / 8)
        if (nowMs - startedMs >= 60_000 && highSince >= 0 && nowMs - highSince >= 30_000 && nowMs - changedAt >= 60_000) {
            val next = maxOf(minimumBps, targetBps - step)
            if (next != targetBps) { targetBps = next; changedAt = nowMs }
            highSince = nowMs
        } else if (lowSince >= 0 && nowMs - lowSince >= 120_000 && nowMs - changedAt >= 120_000) {
            val next = minOf(ceilingBps, targetBps + step)
            if (next != targetBps) { targetBps = next; changedAt = nowMs }
            lowSince = nowMs
        }
        lastMs = nowMs; lastQueue = queuedBytes; lastProduced = producedBytes; lastDelivered = deliveredBytes
        return targetBps
    }
}
