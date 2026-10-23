package com.fieldnotes.recorder.capture

/** Process-local control, installed only for a live service; cannot accidentally start a recording. */
object CaptureControls {
    @Volatile var setTorch: ((Boolean) -> Unit)? = null
    /** Process-local preview-to-recorder handoff; never restored after a process restart. */
    @Volatile var requestedTorch = false
}
