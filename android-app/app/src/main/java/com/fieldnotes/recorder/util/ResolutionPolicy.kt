package com.fieldnotes.recorder.util

import kotlin.math.abs

object ResolutionPolicy {
    /** Rank shape before pixel count; more pixels alone do not mean a wider view. */
    fun choose(sizes: List<Pair<Int, Int>>, sensorRatio: Double, maxHeight: Int): Pair<Int, Int> {
        require(sizes.isNotEmpty()) { "No compatible video resolution" }
        val bounded = if (maxHeight == 0) sizes else sizes.filter { it.second <= maxHeight }.ifEmpty { sizes }
        val closest = bounded.minOf { abs(it.first.toDouble() / it.second - sensorRatio) }
        return bounded.filter { abs(it.first.toDouble() / it.second - sensorRatio) <= closest + 0.01 }
            .maxBy { it.first.toLong() * it.second }
    }
}
