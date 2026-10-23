package com.fieldnotes.recorder.secure

/** One lock orders key generation and deletion. Existing cipher operations may still finish. */
object KeyLifecycle { val lock = Any() }
