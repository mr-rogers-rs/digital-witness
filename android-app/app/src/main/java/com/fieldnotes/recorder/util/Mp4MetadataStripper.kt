package com.fieldnotes.recorder.util

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Strips identifying metadata from MP4 files while preserving playback integrity.
 *
 * MP4 files are structured as a tree of "boxes" (atoms). Some contain encoder
 * strings, creation dates, and device fingerprints that can identify a user:
 *
 *   - `©too` (encoder tool name, e.g. "Lavf58.76.100" or device-specific string)
 *   - `©day` (creation date)
 *   - `©xyz` (GPS coordinates embedded by some devices)
 *   - `udta` (user data box — contains most of the above)
 *
 * This stripper rewrites the file, keeping structural boxes (`ftyp`, `moov`, `mdat`)
 * and stripping the `udta` sub-box inside `moov`, plus any top-level `uuid`/`meta`
 * boxes. Track and sample data are untouched — playback is unaffected.
 */
object Mp4MetadataStripper {

    private const val TAG = "DW-Mp4Strip"

    /** Top-level boxes that carry identifying metadata and are blanked in place. */
    private val NEUTRALIZE = setOf("uuid", "meta")

    /** ASCII 'f','r','e','e' — the box type every demuxer skips. */
    private val FREE_TYPE = byteArrayOf(0x66, 0x72, 0x65, 0x65)

    /** Write a `free` box of exactly [size] bytes (8-byte header + zero payload). */
    private fun writeFreeBox(out: java.io.OutputStream, size: Int) {
        val header = ByteArray(8)
        ByteBuffer.wrap(header, 0, 4).putInt(size)
        System.arraycopy(FREE_TYPE, 0, header, 4, 4)
        out.write(header)
        val zeros = ByteArray(8192)
        var remaining = size - 8
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size)
            out.write(zeros, 0, n)
            remaining -= n
        }
    }

    /** Rewrite the box at [offset] as a same-size `free` box with a zeroed payload. */
    private fun neutralizeInPlace(data: ByteArray, offset: Int, size: Int) {
        System.arraycopy(FREE_TYPE, 0, data, offset + 4, 4)
        for (i in (offset + 8) until (offset + size)) data[i] = 0
    }

    /**
     * Strip identifying metadata from [input], writing a clean copy to [output].
     * Returns true on success, false if stripping failed (original file is untouched).
     */
    fun strip(input: File, output: File): Boolean {
        if (!input.exists() || input.length() < 8) return false

        return try {
            RandomAccessFile(input, "r").use { raf ->
                output.outputStream().buffered().use { out ->
                    val fileSize = raf.length()
                    var pos = 0L

                    while (pos < fileSize) {
                        raf.seek(pos)
                        if (fileSize - pos < 8) break

                        val sizeBytes = ByteArray(4)
                        val typeBytes = ByteArray(4)
                        raf.readFully(sizeBytes)
                        raf.readFully(typeBytes)

                        var boxSize = ByteBuffer.wrap(sizeBytes).int.toLong() and 0xFFFFFFFFL
                        val boxType = String(typeBytes, Charsets.US_ASCII)

                        // Handle extended size (size == 1 means 64-bit size follows)
                        if (boxSize == 1L) {
                            val extBytes = ByteArray(8)
                            raf.readFully(extBytes)
                            boxSize = ByteBuffer.wrap(extBytes).long
                        }

                        // size == 0 means box extends to end of file
                        if (boxSize == 0L) {
                            boxSize = fileSize - pos
                        }

                        if (boxSize < 8 || pos + boxSize > fileSize) break

                        when {
                            // Identifying metadata: neutralized IN PLACE, never removed.
                            //
                            // Removing a box shifts every byte after it, and `stco`
                            // (chunk offset) entries inside `moov` are ABSOLUTE file
                            // offsets that we do not rewrite. MediaMuxer lays the file
                            // out as ftyp → free → mdat → moov, reserving that `free`
                            // box ahead of the media data. Deleting it slid `mdat`
                            // ~1 KB earlier while the offsets kept pointing at the old
                            // positions, so every decoder read landed mid-frame: the
                            // container still declared the right duration, resolution
                            // and track layout, but no picture could be decoded from
                            // it. That was the black-video bug — and because it lived
                            // in the anonymous-mode path, it appeared the moment
                            // anonymous became the default and looked like a camera
                            // fault at every resolution.
                            //
                            // Rewriting the box as a same-size `free` (a box every
                            // demuxer skips) destroys the content while keeping the
                            // file byte-for-byte the same length. No offset moves, so
                            // this is correct regardless of box ordering.
                            boxType in NEUTRALIZE && boxSize in 8..Int.MAX_VALUE.toLong() -> {
                                Log.d(TAG, "Neutralizing top-level box: $boxType (${boxSize}B)")
                                writeFreeBox(out, boxSize.toInt())
                            }

                            // moov: same-size edit — udta neutralized, timestamps zeroed.
                            boxType == "moov" && boxSize <= Int.MAX_VALUE.toLong() -> {
                                val moovData = ByteArray(boxSize.toInt())
                                raf.seek(pos)
                                raf.readFully(moovData)
                                scrubMoovInPlace(moovData)
                                out.write(moovData)
                            }

                            // Everything else (ftyp, mdat, free, skip, wide, …): verbatim.
                            // `free`/`skip`/`wide` are padding and carry no identity, so
                            // there was never a reason to touch them.
                            else -> {
                                raf.seek(pos)
                                copyBytes(raf, out, boxSize)
                            }
                        }

                        pos += boxSize
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "MP4 metadata stripping failed", e)
            output.delete()
            false
        }
    }

    /**
     * Strips in-place: strips metadata from [file], replacing it with the clean version.
     * Returns true on success.
     *
     * Atomic-rename strategy: write the stripped copy to a temp file, fsync, then
     * atomically rename it over the original. POSIX rename() replaces the target
     * atomically, so the original is either fully present or fully replaced —
     * never half-deleted. The previous implementation deleted the original before
     * the rename and then deleted the temp in a `finally`, which on a failed
     * rename destroyed BOTH copies (silent total data loss in anonymous mode,
     * which runs this every segment).
     */
    fun stripInPlace(file: File): Boolean {
        val temp = File(file.parent, file.name + ".stripped")
        return try {
            if (!strip(file, temp)) {
                temp.delete()
                return false
            }
            // Atomic replace: temp → file. On POSIX (Android's storage), rename
            // over an existing file replaces it atomically; the old inode is
            // unlinked but any open file descriptor keeps its data.
            if (!temp.renameTo(file)) {
                // Same-filesystem rename should not fail; if it does, leave the
                // original untouched and surface failure rather than risk data loss.
                temp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "stripInPlace failed", e)
            temp.delete()
            false
        }
    }

    /**
     * Process a `moov` box: remove `udta`, zero timestamps in `mvhd` and `tkhd`.
     *
     * `mvhd` (Movie Header) and `tkhd` (Track Header) contain creation_time
     * and modification_time fields that leak the exact recording time even
     * after `udta` is stripped. We zero them to epoch (0 = 1904-01-01 in
     * MP4 time, effectively "unknown").
     */
    private fun scrubMoovInPlace(moovData: ByteArray) {
        var offset = 8

        while (offset + 8 <= moovData.size) {
            val subSize = ByteBuffer.wrap(moovData, offset, 4).int.toLong() and 0xFFFFFFFFL
            val subType = String(moovData, offset + 4, 4, Charsets.US_ASCII)

            if (subSize < 8 || offset + subSize.toInt() > moovData.size) break

            when (subType) {
                // Blank udta rather than removing it. The old code rebuilt moov at a
                // smaller size; that happened to be survivable only because
                // MediaMuxer puts moov last, so nothing downstream referenced it.
                // A fast-start file (moov before mdat) would have been silently
                // corrupted the same way the `free` removal corrupted every file.
                // Keeping the size fixed removes that whole class of failure.
                "udta" -> {
                    Log.d(TAG, "Neutralizing moov/udta (${subSize}B)")
                    neutralizeInPlace(moovData, offset, subSize.toInt())
                }
                "mvhd" -> zeroHeaderTimestamps(moovData, offset, subSize.toInt(), "mvhd")
                "trak" -> zeroTrakTimestamps(moovData, offset, offset + subSize.toInt())
            }

            offset += subSize.toInt()
        }
    }

    /**
     * Zero creation/modification timestamps in the header boxes of the `trak`
     * spanning [trakStart, trakEnd) within [data]: `tkhd` (track header)
     * directly, and `mdhd` (media header) nested under `mdia`. mvhd/tkhd/mdhd
     * share the same leading layout (version, flags, creation_time,
     * modification_time), so [zeroHeaderTimestamps] serves all three. Edits are
     * in place with no size change, so every box length stays valid (audit B4).
     */
    private fun zeroTrakTimestamps(data: ByteArray, trakStart: Int, trakEnd: Int) {
        var offset = trakStart + 8
        while (offset + 8 <= trakEnd) {
            val subSize = ByteBuffer.wrap(data, offset, 4).int.toLong() and 0xFFFFFFFFL
            val subType = String(data, offset + 4, 4, Charsets.US_ASCII)

            if (subSize < 8 || offset + subSize.toInt() > trakEnd) break

            when (subType) {
                "tkhd" -> zeroHeaderTimestamps(data, offset, subSize.toInt(), "tkhd")
                "mdia" -> {
                    // mdia is a container; descend to find and zero mdhd.
                    var inner = offset + 8
                    val mdiaEnd = offset + subSize.toInt()
                    while (inner + 8 <= mdiaEnd) {
                        val iSize = ByteBuffer.wrap(data, inner, 4).int.toLong() and 0xFFFFFFFFL
                        val iType = String(data, inner + 4, 4, Charsets.US_ASCII)
                        if (iSize < 8 || inner + iSize.toInt() > mdiaEnd) break
                        if (iType == "mdhd") zeroHeaderTimestamps(data, inner, iSize.toInt(), "mdhd")
                        inner += iSize.toInt()
                    }
                }
            }

            offset += subSize.toInt()
        }
    }

    /**
     * Zero the creation_time/modification_time of a full-box header (`tkhd`,
     * `mdhd`, …) located at [boxOffset] within [data]. Layout after the 8-byte
     * box header: version(1) flags(3), then v0: creation(4) modification(4) or
     * v1: creation(8) modification(8).
     */
    private fun zeroHeaderTimestamps(data: ByteArray, boxOffset: Int, boxSize: Int, label: String) {
        val version = data[boxOffset + 8].toInt() and 0xFF
        if (version == 0 && boxSize >= 20) {
            for (i in (boxOffset + 12)..(boxOffset + 19)) data[i] = 0
            Log.d(TAG, "Zeroed $label v0 timestamps")
        } else if (version == 1 && boxSize >= 28) {
            for (i in (boxOffset + 12)..(boxOffset + 27)) data[i] = 0
            Log.d(TAG, "Zeroed $label v1 timestamps")
        }
    }

    private fun copyBytes(raf: RandomAccessFile, out: java.io.OutputStream, count: Long) {
        val buf = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = raf.read(buf, 0, toRead)
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }
}
