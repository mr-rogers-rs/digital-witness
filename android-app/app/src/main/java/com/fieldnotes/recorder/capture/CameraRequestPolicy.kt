package com.fieldnotes.recorder.capture

import android.hardware.camera2.CameraCharacteristics
import android.util.Range

object CameraRequestPolicy {
    fun fpsRange(chars: CameraCharacteristics, target: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        return NativeCapturePolicy.range(ranges.map { it.lower to it.upper }, target)?.let { Range(it.first, it.second) }
    }
}
