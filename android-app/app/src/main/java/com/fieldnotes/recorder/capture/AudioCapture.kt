package com.fieldnotes.recorder.capture

import android.media.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import java.nio.ByteBuffer

data class EncodedAudioFrame(val data: ByteArray, val presentationTimeUs: Long, val isConfig: Boolean = false)

class AudioCapture(
    private val onEncoded: (EncodedAudioFrame) -> Unit,
    private val sampleRateHz: Int,
    private val bitrateKbps: Int,
    private val aacProfileName: String, // "aac_lc" or "he_aac"
    private val onFormat: (MediaFormat) -> Unit = {},
    private val onError: () -> Unit = {},
    private val onTimingError: () -> Unit = onError
) {
    companion object {
        private const val TAG = "DW-AudioCapture"
    }

    private var job: Job? = null
    private var ascEmitted = false
    @Volatile private var stopping = false

    suspend fun stopAndDrain() {
        stopping = true
        if (kotlinx.coroutines.withTimeoutOrNull(2500) { job?.join(); true } != true) {
            job?.cancel()
            job?.join()
        }
        job = null
    }

    fun start() {
        if (job != null) return
        Log.i(TAG, "Starting AudioCapture")
        job = CoroutineScope(Dispatchers.Default).launch {
            captureLoop()
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping AudioCapture")
        job?.cancel()
        job = null
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun buildAudioRecord(sampleRate: Int, channelConfig: Int, audioFormat: Int): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        Log.d(TAG, "AudioRecord config sr=" + sampleRate + " chMask=" + channelConfig + " fmt=" + audioFormat + " minBuf=" + minBuf)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build()
            )
            // One second of mono PCM absorbs brief scheduling/storage pressure
            // without changing encoded sample rate, bitrate or audio quality.
            .setBufferSizeInBytes(maxOf(minBuf * 4, sampleRate * 2))
            .build()
    }

    private fun createAacEncoder(sampleRate: Int, channelCount: Int): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        val profile = if (aacProfileName == "he_aac") MediaCodecInfo.CodecProfileLevel.AACObjectHE else MediaCodecInfo.CodecProfileLevel.AACObjectLC
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
        Log.d(TAG, "Configuring AAC encoder sr=" + sampleRate + " ch=" + channelCount + " br=" + (bitrateKbps*1000) + " profile=" + aacProfileName)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Log.d(TAG, "AAC encoder started")
        return codec
    }

    private suspend fun captureLoop() {
        val sampleRate = sampleRateHz
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val channelCount = 1

        var recorder: AudioRecord? = null
        var encoder: MediaCodec? = null
        val inputBuf = ByteArray(2048)
        val ctx = currentCoroutineContext()

        try {
            recorder = buildAudioRecord(sampleRate, channelConfig, audioFormat)
            encoder = createAacEncoder(sampleRate, channelCount)
            Log.d(TAG, "Audio capture loop started sr=" + sampleRate + " ch=1 buf=" + inputBuf.size)
            recorder.startRecording()
            val clock = AudioSampleClock(sampleRate,
                recorder.bufferSizeInFrames * 1_000_000L / sampleRate + 500_000L)
            while (ctx.isActive && !stopping) {
                val read = recorder.read(inputBuf, 0, inputBuf.size, AudioRecord.READ_NON_BLOCKING)
                check(read >= 0) { "Microphone read failed" }
                if (read == 0) { kotlinx.coroutines.delay(5); continue }
                val pts = clock.next(read / 2, System.nanoTime() / 1000)
                val deadline = android.os.SystemClock.elapsedRealtime() + 500
                while (!feedEncoder(encoder, inputBuf, read, pts)) {
                    check(ctx.isActive && android.os.SystemClock.elapsedRealtime() < deadline) { "Audio encoder input stalled" }
                    drainEncoder(encoder, onEncoded)
                    kotlinx.coroutines.delay(2)
                }
                drainEncoder(encoder, onEncoded)
            }
            if (ctx.isActive) {
                val idx = encoder.dequeueInputBuffer(100_000)
                if (idx >= 0) encoder.queueInputBuffer(idx, 0, 0,
                    clock.endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = android.os.SystemClock.elapsedRealtime() + 1500
                while (ctx.isActive && android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (drainEncoder(encoder, onEncoded)) break
                    kotlinx.coroutines.delay(5)
                }
            }
        } catch (e: AudioTimingException) {
            Log.e(TAG, "Microphone sample continuity failed", e)
            onTimingError()
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission unavailable", e)
            onError()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error", e)
            onError()
        }
        finally {
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            Log.d(TAG, "Audio capture loop stopped")
        }
    }

    private fun feedEncoder(encoder: MediaCodec, data: ByteArray, len: Int, presTimeUs: Long): Boolean {
        val idx = encoder.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val ib: ByteBuffer? = encoder.getInputBuffer(idx)
            ib?.clear()
            ib?.put(data, 0, len)
            encoder.queueInputBuffer(idx, 0, len, presTimeUs, 0)
            return true
        }
        return false
    }

    private fun drainEncoder(encoder: MediaCodec, onFrame: (EncodedAudioFrame) -> Unit): Boolean {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormat(encoder.outputFormat)
                // Emit the Audio Specific Config (csd-0) as the first frame
                if (!ascEmitted) {
                    val fmt = encoder.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")?.duplicate()
                    if (csd0 != null) {
                        val asc = ByteArray(csd0.remaining())
                        csd0.get(asc)
                        Log.d(TAG, "Emitting AAC ASC: " + asc.size + " bytes - " + asc.joinToString(" ") { "%02X".format(it) })
                        onFrame(EncodedAudioFrame(asc, 0L, true))
                        ascEmitted = true
                    } else {
                        Log.w(TAG, "AAC output format changed but csd-0 missing")
                    }
                }
                continue
            }
            if (outIdx >= 0) {
                val ob = encoder.getOutputBuffer(outIdx)
                if (ob != null && bufferInfo.size > 0) {
                    val start = bufferInfo.offset
                    val end = start + bufferInfo.size
                    ob.position(start)
                    ob.limit(end)
                    val arr = ByteArray(bufferInfo.size)
                    ob.get(arr)
                    onFrame(EncodedAudioFrame(arr, bufferInfo.presentationTimeUs,
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0))
                }
                encoder.releaseOutputBuffer(outIdx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }
        return false
    }
}
