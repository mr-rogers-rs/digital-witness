package com.fieldnotes.recorder.capture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteBuffer

data class MuxSample(val data: ByteArray, val timeUs: Long, val keyFrame: Boolean)

/** Pure checks shared by the live writer and regression tests. All PTS use MONOTONIC. */
object SegmentTimeline {
    const val MAX_START_SKEW_US = 5_000_000L
    fun baseline(audio: List<MuxSample>, video: List<MuxSample>, maxDurationUs: Long): Long {
        require(audio.isNotEmpty() || video.isNotEmpty()) { "No media samples" }
        require(video.isEmpty() || video.first().keyFrame) { "Video must begin at a keyframe" }
        for (track in listOf(audio, video)) {
            require(track.all { it.data.isNotEmpty() && it.timeUs >= 0 }) { "Invalid media sample" }
            require(track.zipWithNext().all { (a, b) -> b.timeUs > a.timeUs }) { "Non-increasing media timestamps" }
        }
        if (audio.isNotEmpty() && video.isNotEmpty())
            require(kotlin.math.abs(audio.first().timeUs - video.first().timeUs) <= MAX_START_SKEW_US) {
                "Audio/video timestamps are too far apart; retain sources for investigation"
            }
        val first = listOfNotNull(audio.firstOrNull()?.timeUs, video.firstOrNull()?.timeUs).min()
        val last = listOfNotNull(audio.lastOrNull()?.timeUs, video.lastOrNull()?.timeUs).max()
        require(last - first <= maxDurationUs) { "Media timestamps exceed recording duration (clock mismatch)" }
        return first
    }

    /** Keep the final GOP for the next clip, so neither clip starts with dependent frames. */
    fun splitIndex(keyFrames: List<Boolean>): Int = keyFrames.indexOfLast { it }.takeIf { it > 0 } ?: 0
}

/** Uses MediaCodec's output formats and bytes, as required by MediaMuxer. */
object SegmentMuxer {
    fun write(
        output: File,
        audioFormat: MediaFormat?,
        videoFormat: MediaFormat?,
        audio: List<MuxSample>,
        video: List<MuxSample>,
        maxDurationUs: Long,
        orientation: Int = 90
    ) {
        check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress)
        val base = SegmentTimeline.baseline(audio, video, maxDurationUs)
        require(audio.isEmpty() || audioFormat != null) { "Missing audio encoder format" }
        require(video.isEmpty() || videoFormat != null) { "Missing video encoder format" }
        // The orphan-recovery sweep queues *.mp4 after a crash. Keep unfinished
        // or rejected output under a different suffix until validation passes.
        val pending = File(output.parentFile, output.name + ".muxing")
        val muxer = MediaMuxer(pending.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (video.isNotEmpty()) muxer.setOrientationHint(orientation)
            val audioTrack = if (audio.isNotEmpty()) muxer.addTrack(audioFormat!!) else -1
            val videoTrack = if (video.isNotEmpty()) muxer.addTrack(videoFormat!!) else -1
            muxer.start()
            var ai = 0
            var vi = 0
            // Interleave tracks by timestamp. Preserve their real relative start;
            // earlier audio is evidence too and must not be silently trimmed.
            while (ai < audio.size || vi < video.size) {
                check(!com.fieldnotes.recorder.util.PanicWipe.wipeInProgress)
                val useAudio = ai < audio.size && (vi == video.size || audio[ai].timeUs <= video[vi].timeUs)
                val sample = if (useAudio) audio[ai++] else video[vi++]
                val info = MediaCodec.BufferInfo().apply {
                    set(0, sample.data.size, sample.timeUs - base,
                        if (sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                }
                muxer.writeSampleData(if (useAudio) audioTrack else videoTrack, ByteBuffer.wrap(sample.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
            if (com.fieldnotes.recorder.util.PanicWipe.wipeInProgress) {
                pending.delete()
                output.delete()
            }
        }
        // Reject malformed output before it is hashed, queued or presented as saved.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pending.absolutePath)
            require(extractor.trackCount == (if (audio.isEmpty()) 0 else 1) + (if (video.isEmpty()) 0 else 1))
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val duration = format.getLong(MediaFormat.KEY_DURATION)
                require(duration in 1..(maxDurationUs + 1_000_000)) { "Invalid MP4 track duration" }
                extractor.selectTrack(i)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                require(extractor.sampleTime >= 0) { "Empty MP4 track" }
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "Missing initial video keyframe" }
                }
                extractor.unselectTrack(i)
            }
        } finally {
            extractor.release()
        }
        if (video.isNotEmpty()) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pending.absolutePath)
                // Check decodability, not brightness: a dark scene can be valid evidence.
                val timeUs = video.first().timeUs - base
                val decoded = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                val frame = checkNotNull(decoded) { "MP4 video cannot be decoded" }
                frame.recycle()
            } finally {
                retriever.release()
            }
        }
        java.io.FileOutputStream(pending, true).use { it.fd.sync() }
        check(pending.renameTo(output)) { "Could not publish validated MP4" }
        com.fieldnotes.recorder.secure.FileDurability.syncDirectory(output.parentFile!!)
    }
}
