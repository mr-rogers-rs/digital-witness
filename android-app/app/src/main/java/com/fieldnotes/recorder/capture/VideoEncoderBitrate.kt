package com.fieldnotes.recorder.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.fieldnotes.recorder.R

class VideoBitrateException(message: String) : IllegalArgumentException(message)

/** Shared by real capture and the synthetic device test. Never silently changes the target. */
object VideoEncoderBitrate {
    fun configure(ctx: Context, codec: MediaCodec, format: MediaFormat, bps: Int, preferCbr: Boolean = false): Boolean {
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val range = checkNotNull(caps.videoCapabilities).bitrateRange
        if (!range.contains(bps)) throw VideoBitrateException(
            ctx.getString(R.string.bitrate_unsupported, bps / 1000, range.lower / 1000, range.upper / 1000))
        val encoder = checkNotNull(caps.encoderCapabilities)
        val mode = when {
            preferCbr && encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            else -> throw VideoBitrateException(ctx.getString(R.string.bitrate_mode_unsupported))
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, bps)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        return mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    }
}
