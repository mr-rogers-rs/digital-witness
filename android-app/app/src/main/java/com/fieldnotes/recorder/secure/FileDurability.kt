package com.fieldnotes.recorder.secure

/** fsync the directory entry as well as file data before retiring the previous recoverable copy. */
object FileDurability {
    fun syncDirectory(directory: java.io.File) {
        val descriptor = android.system.Os.open(directory.absolutePath,
            android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }
}
