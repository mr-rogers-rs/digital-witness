package com.fieldnotes.recorder.upload

import android.content.Context
import androidx.documentfile.provider.DocumentFile

object SafEvidenceFolder {
    /** Production path avoids per-child name queries when finding the evidence directory too. */
    fun resolve(ctx: Context, root: DocumentFile, subfolder: String): DocumentFile? {
        EvidenceLayout.validate(subfolder)
        if (!root.isDirectory || !root.canWrite()) return null
        val directory = if (subfolder.isEmpty()) root else {
            val existing = SafDocumentLookup.find(ctx, root.uri, subfolder)
            if (existing == null) root.createDirectory(subfolder) ?: return null
            else DocumentFile.fromSingleUri(ctx, existing.uri) ?: return null
        }
        return directory.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
    }

    fun resolve(root: DocumentFile, subfolder: String): DocumentFile? {
        EvidenceLayout.validate(subfolder)
        if (!root.isDirectory || !root.canWrite()) return null
        val directory = if (subfolder.isEmpty()) root else
            root.findFile(subfolder) ?: root.createDirectory(subfolder) ?: return null
        return directory.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
    }
}
