package com.fieldnotes.recorder.upload

import androidx.documentfile.provider.DocumentFile

object SafEvidenceFolder {
    fun resolve(root: DocumentFile, subfolder: String): DocumentFile? {
        EvidenceLayout.validate(subfolder)
        if (!root.isDirectory || !root.canWrite()) return null
        val directory = if (subfolder.isEmpty()) root else
            root.findFile(subfolder) ?: root.createDirectory(subfolder) ?: return null
        return directory.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
    }
}
