package com.fieldnotes.recorder.cloud

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File

/**
 * A streaming [RequestBody] that writes a file (or a contiguous byte range of
 * it) directly from disk to the network in fixed-size buffers, without ever
 * materializing the whole payload in heap.
 *
 * This replaces `file.readBytes().toRequestBody(...)`, which loaded entire
 * multi-MB→GB segments into RAM and risked OutOfMemoryError on large
 * recordings over flaky links. [contentLength] is reported precisely so the
 * request uses a fixed Content-Length (no chunked transfer-encoding), which
 * Nextcloud's WebDAV endpoint requires for `PUT`/`MOVE` integrity.
 *
 * @param file        source file on disk
 * @param contentType MIME type to advertise
 * @param offset      starting byte offset within [file] (default 0)
 * @param byteCount   number of bytes to send from [offset]; null = to EOF
 */
class FileRequestBody(
    private val file: File,
    private val contentType: MediaType = "application/octet-stream".toMediaType(),
    private val offset: Long = 0L,
    private val byteCount: Long? = null
) : RequestBody() {

    private val length: Long =
        byteCount ?: (file.length() - offset).coerceAtLeast(0L)

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        file.inputStream().use { input ->
            if (offset > 0) {
                var skipped = 0L
                while (skipped < offset) {
                    val s = input.skip(offset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
            input.source().use { source ->
                var remaining = length
                val chunk = 64 * 1024L
                while (remaining > 0) {
                    val read = source.read(sink.buffer, minOf(chunk, remaining))
                    if (read == -1L) break
                    remaining -= read
                    sink.flush()
                }
            }
        }
    }
}
