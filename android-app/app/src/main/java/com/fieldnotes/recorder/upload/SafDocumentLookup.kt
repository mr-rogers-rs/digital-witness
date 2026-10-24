package com.fieldnotes.recorder.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/** One projected query, rather than DocumentFile.findFile's per-child metadata queries. */
object SafDocumentLookup {
    data class Entry(val uri: Uri, val isFile: Boolean)

    fun find(ctx: Context, parent: Uri, name: String): Entry? {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val cursor = ctx.contentResolver.query(children, columns, null, null, null)
            ?: throw IOException("Folder listing unavailable")
        cursor.use {
            // A partial/error listing cannot establish absence safely on a retry.
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                !it.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty()) throw UploadRetryLaterException()
            val idColumn = it.getColumnIndexOrThrow(columns[0])
            val nameColumn = it.getColumnIndexOrThrow(columns[1])
            val typeColumn = it.getColumnIndexOrThrow(columns[2])
            while (it.moveToNext()) {
                if (it.getString(nameColumn) != name) continue
                val mime = it.getString(typeColumn)
                return Entry(DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(idColumn)),
                    !mime.isNullOrEmpty() && mime != DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return null
    }
}
