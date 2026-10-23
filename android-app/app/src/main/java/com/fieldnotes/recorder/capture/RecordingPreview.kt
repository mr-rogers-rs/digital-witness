package com.fieldnotes.recorder.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.ArrayDeque

/** Optional display-only decoder. Neither display backpressure nor display failure can block capture.
 * Both camera backends keep their original, direct camera-to-encoder surface path.
 */
object RecordingPreview {
    private val handler = Handler(HandlerThread("DW-Preview").apply { start() }.looper)
    private var surface: Surface? = null
    private var format: MediaFormat? = null
    private var decoder: MediaCodec? = null
    private var waitingForKey = true
    private val frames = ArrayDeque<EncodedVideoFrame>()
    private var queuedBytes = 0
    private var onFailure: (() -> Unit)? = null
    private var onSize: ((Int, Int) -> Unit)? = null
    @Volatile var requestKeyFrame: (() -> Unit)? = null

    fun attach(target: Surface, sizeChanged: (Int, Int) -> Unit, failure: () -> Unit) = handler.post {
        releaseDecoder()
        surface = target
        onFailure = failure
        onSize = sizeChanged
        configure()
    }

    /** Release the caller-owned Surface only after all decoder work on it has ended. */
    fun detach(target: Surface, released: () -> Unit) = handler.post {
        if (surface === target) {
            releaseDecoder(); surface = null; onFailure = null; onSize = null
        }
        released()
    }

    fun onFormat(value: MediaFormat) = handler.post {
        format = value
        releaseDecoder()
        configure()
    }

    // Bound both queued bytes and runnable count on the encoder's thread.
    private val incoming = ArrayDeque<EncodedVideoFrame>()
    private var incomingBytes = 0
    private var posted = false
    @Volatile private var displaying = false

    fun onFrame(frame: EncodedVideoFrame) {
        if (!displaying || frame.isConfig) return
        if (frame.data.size > 16 * 1024 * 1024) return
        synchronized(incoming) {
            if (incoming.size >= 8 || incomingBytes + frame.data.size > 16 * 1024 * 1024) {
                incoming.clear(); incomingBytes = 0
                // A missing dependency requires a fresh keyframe in the display decoder only.
                incoming.add(EncodedVideoFrame(ByteArray(0), 0, false))
            }
            incoming.add(frame); incomingBytes += frame.data.size
            if (!posted) {
                posted = true
                handler.post {
                    val batch = synchronized(incoming) {
                        incoming.toList().also { incoming.clear(); incomingBytes = 0; posted = false }
                    }
                    if (decoder != null) for (sample in batch) {
                        if (sample.data.isEmpty() || frames.size >= 8 || queuedBytes + sample.data.size > 16 * 1024 * 1024) {
                            frames.clear(); queuedBytes = 0
                            waitingForKey = true
                            runCatching { decoder?.flush() }
                            requestKeyFrame?.invoke()
                        }
                        if (sample.data.isNotEmpty() && (!waitingForKey || sample.isKeyFrame)) {
                            waitingForKey = false
                            frames.add(sample); queuedBytes += sample.data.size
                        }
                    }
                }
            }
        }
    }

    fun end() {
        requestKeyFrame = null
        handler.post { releaseDecoder(); format = null }
    }

    /** Camera2 idle preview must not connect to a Surface still owned by a decoder. */
    fun prepareIdle(target: Surface, ready: () -> Unit) = handler.post {
        if (surface === target) { releaseDecoder(); format = null }
        ready()
    }

    private fun configure() {
        val target = surface ?: return
        val source = format ?: return
        try {
            val displayFormat = MediaFormat.createVideoFormat(source.getString(MediaFormat.KEY_MIME)!!,
                source.getInteger(MediaFormat.KEY_WIDTH), source.getInteger(MediaFormat.KEY_HEIGHT))
            for (key in listOf("csd-0", "csd-1")) source.getByteBuffer(key)?.let {
                displayFormat.setByteBuffer(key, it.duplicate())
            }
            val codec = MediaCodec.createDecoderByType(source.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(displayFormat, target, null, 0)
            codec.start()
            onSize?.invoke(source.getInteger(MediaFormat.KEY_WIDTH), source.getInteger(MediaFormat.KEY_HEIGHT))
            displaying = true
            requestKeyFrame?.invoke()
            handler.post(pump)
        } catch (_: Exception) { fail() }
    }

    private val pump = object : Runnable {
        override fun run() {
            val codec = decoder ?: return
            try {
                val info = MediaCodec.BufferInfo()
                var output = codec.dequeueOutputBuffer(info, 0)
                while (output >= 0) {
                    codec.releaseOutputBuffer(output, true)
                    output = codec.dequeueOutputBuffer(info, 0)
                }
                while (frames.isNotEmpty()) {
                    val input = codec.dequeueInputBuffer(0)
                    if (input < 0) break
                    val frame = frames.removeFirst(); queuedBytes -= frame.data.size
                    val buffer = checkNotNull(codec.getInputBuffer(input))
                    buffer.clear(); buffer.put(frame.data)
                    codec.queueInputBuffer(input, 0, frame.data.size, frame.presentationTimeUs, 0)
                }
                handler.postDelayed(this, 10)
            } catch (_: Exception) { fail() }
        }
    }

    private fun fail() { releaseDecoder(); onFailure?.invoke() }

    private fun releaseDecoder() {
        displaying = false
        handler.removeCallbacks(pump)
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        frames.clear(); queuedBytes = 0; waitingForKey = true
        synchronized(incoming) { incoming.clear(); incomingBytes = 0 }
    }
}
