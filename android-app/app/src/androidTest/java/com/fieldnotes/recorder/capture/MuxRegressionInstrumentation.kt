package com.fieldnotes.recorder.capture

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import com.fieldnotes.recorder.util.Mp4MetadataStripper
import java.io.File
import kotlin.math.sin

/** Synthetic media only: does not open camera/microphone, change settings or upload. */
class MuxRegressionInstrumentation : Instrumentation() {
    private var bitrateOnly = false
    private var recoveryOnly = false
    private var adaptiveOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        bitrateOnly = arguments?.getString("bitrateOnly") == "true"
        recoveryOnly = arguments?.getString("recoveryOnly") == "true"
        adaptiveOnly = arguments?.getString("adaptiveOnly") == "true"
        start()
    }

    override fun onStart() {
        try {
            if (adaptiveOnly) {
                val video = encode(true, 2000, complex = true, adaptive = true)
                fun rate(fromUs: Long, toUs: Long) = video.second.filter { it.timeUs - baseUs in fromUs until toUs }.sumOf { it.data.size.toLong() } * 8.0 / ((toUs - fromUs) / 1_000_000.0)
                val high = rate(2_000_000, 5_000_000)
                val low = rate(9_000_000, 13_000_000)
                val restored = rate(17_000_000, 19_000_000)
                check(video.second.size == 200 && video.second.zipWithNext().all { (a, b) -> b.timeUs - a.timeUs == 100_000L }) { "Missing or discontinuous video frames" }
                check(high > 0 && low > 0 && restored > 0) { "Empty rate measurement window" }
                check(low < high * .95) { "Codec ignored lower bitrate: high=$high low=$low" }
                check(restored > low * 1.05) { "Codec did not restore bitrate: low=$low restored=$restored" }
                val out = File(targetContext.cacheDir, "mux-regression/adaptive.mp4")
                out.parentFile?.mkdirs()
                SegmentMuxer.write(out, null, video.first, emptyList(), video.second, 22_000_000, 0)
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: real CBR encoder changed 2000→1500→2000 kb/s without restart; measured high=$high low=$low restored=$restored b/s; all 200 frames continuous, muxed and playback validated. No camera/microphone/uploads.\n") })
                return
            }
            if (recoveryOnly) {
                runChecks()
                finish(Activity.RESULT_OK, Bundle().apply {
                    putString("stream", "PASS: synthetic encrypted journal restart, codec restoration, sample-exact MP4 replay and playback/boundary checks. No camera, microphone or uploads.\n")
                })
                return
            }
            bitrateUiChecks()
            val bitrateResult = bitrateChecks()
            if (bitrateOnly) {
                measurementChecks()
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", bitrateResult + "\n") })
                return
            }
            runChecks()
            calibrationChecks()
            measurementChecks()
            evidenceFolderChecks()
            finish(Activity.RESULT_OK, Bundle().apply {
                val normal = com.fieldnotes.recorder.util.CameraUtil.normalVideoResolution(targetContext)
                val rates = com.fieldnotes.recorder.util.CameraUtil.nativeFpsOptions(targetContext)
                putString("stream", "$bitrateResult\nPASS: synthetic playback, segments, clock mismatch rejection, encrypted previews/measurements, bilingual reports and evidence-folder conflict/retry checks. Native reference: ${normal.first}x${normal.second}; advertised FPS choices: $rates. No camera/microphone opened.\n")
            })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", e.stackTraceToString()) })
        }
    }

    private fun evidenceFolderChecks() {
        val directory = File(targetContext.cacheDir, "evidence-layout-regression").apply { mkdirs() }
        try {
            val root = checkNotNull(androidx.documentfile.provider.DocumentFile.fromFile(directory))
            val resolver = com.fieldnotes.recorder.upload.SafEvidenceFolder
            check(resolver.resolve(root, "")?.uri == root.uri)
            File(directory, "Evidence").writeText("unrelated file")
            check(resolver.resolve(root, "Evidence") == null)
            check(File(directory, "Evidence").readText() == "unrelated file")
            check(File(directory, "Evidence").delete())
            val created = checkNotNull(resolver.resolve(root, "Evidence"))
            check(created.isDirectory)
            check(resolver.resolve(root, "Evidence")?.uri == created.uri)
        } finally { directory.deleteRecursively() }
    }

    private val baseUs = 39L * 60 * 60 * 1_000_000

    private fun measurementChecks() {
        val root = File(targetContext.cacheDir, "measurement-regression").apply { mkdirs() }
        val ctx = object : android.content.ContextWrapper(targetContext) {
            override fun getFilesDir(): File = root
        }
        try {
            RecordingMeasurements.begin(ctx)
            RecordingMeasurements.videoFormat(640, 480, 1_000_000)
            for (i in 0..150) RecordingMeasurements.video(baseUs + Math.round(i * 1_000_000.0 / 15), 1000)
            RecordingMeasurements.audio(123)
            RecordingMeasurements.finish(ctx)
            val saved = root.resolve("recording_metrics").listFiles()!!.single()
            val encrypted = saved.readBytes()
            val json = org.json.JSONObject(String(com.fieldnotes.recorder.secure.SecureFile.decryptBytes(encrypted)))
            check(json.getInt("mediaBytes") == 151123 && json.getInt("videoFrames") == 151)
            check(json.getInt("targetVideoKbps") == 1000)
            check(kotlin.math.abs(json.getDouble("measuredVideoKbps") - 120.0) < 0.001)
            check(kotlin.math.abs(json.getDouble("measuredFps") - 15) < 0.0001)
            check(!String(encrypted).contains("measuredFps"))
            for (language in listOf("en", "sr-Latn")) {
                val configuration = android.content.res.Configuration(ctx.resources.configuration)
                configuration.setLocale(java.util.Locale.forLanguageTag(language))
                val localized = object : android.content.ContextWrapper(ctx.createConfigurationContext(configuration)) {
                    override fun getFilesDir(): File = root
                }
                val report = RecordingMeasurements.report(localized)
                val measured = String.format(java.util.Locale.forLanguageTag(language), "%.1f", 15.0)
                check(report.contains("640") && report.contains("480") && report.contains(measured))
                check(!report.contains("%1\$s"))
            }
        } finally { root.deleteRecursively() }
    }

    private fun calibrationChecks() {
        val root = File(targetContext.cacheDir, "calibration-store-regression").apply { mkdirs() }
        val ctx = object : android.content.ContextWrapper(targetContext) {
            override fun getFilesDir(): File = root
        }
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            val jpeg = java.io.ByteArrayOutputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it))
                it.toByteArray()
            }
            bitmap.recycle()
            val preview = CalibrationStore.Preview(jpeg, 72, 24.0, 500, "synthetic-test")
            CalibrationStore.save(ctx, "test-camera", 640 to 480, preview)
            val loaded = checkNotNull(CalibrationStore.load(ctx, "test-camera", 640 to 480))
            check(loaded.jpeg.contentEquals(jpeg) && loaded.frames == 72 && loaded.signature == "synthetic-test")
            val image = loaded.bitmap()
            check(image.width == 64 && image.height == 48)
            check(android.graphics.Color.green(image.getPixel(32, 24)) > 200)
            image.recycle()
            check(CalibrationStore.load(ctx, "different-camera", 640 to 480) == null)
            val disk = root.resolve("calibration").listFiles()!!.single().readBytes()
            check(!disk.contentEquals(jpeg) && !(disk[0] == 0xff.toByte() && disk[1] == 0xd8.toByte()))
            val camera = checkNotNull(com.fieldnotes.recorder.util.CameraUtil.getCameraInfo(targetContext))
            val resolution = com.fieldnotes.recorder.util.CameraUtil.getOptimalVideoResolution(targetContext,
                com.fieldnotes.recorder.util.Prefs.videoMaxHeight(targetContext))
            check(resolution in camera.supportedResolutions)
            android.util.Log.i("DW-CalibrationTest", "Selected ${resolution.first}x${resolution.second}; sensor ratio ${camera.sensorAspectRatio}")
        } finally { root.deleteRecursively() }
    }

    private fun encode(video: Boolean, targetKbps: Int = 500, complex: Boolean = false, adaptive: Boolean = false): Pair<MediaFormat, List<MuxSample>> {
        val mime = if (video) "video/avc" else "audio/mp4a-latm"
        val codec = MediaCodec.createEncoderByType(mime)
        val format = if (video) MediaFormat.createVideoFormat(mime, 320, 240).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val bps = VideoBitratePolicy.requestedBps(320, 240, 10, "target", targetKbps, 60, "high")
            val cbr = VideoEncoderBitrate.configure(targetContext, codec, this, bps, preferCbr = adaptive)
            check(!adaptive || cbr) { "CBR adaptation unavailable on this encoder" }
            check(getInteger(MediaFormat.KEY_BIT_RATE) == targetKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (android.os.Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        } else MediaFormat.createAudioFormat(mime, 32_000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val frames = if (video) { if (complex && !adaptive) 80 else 200 } else 625 // 20 s of video or 640,000 PCM samples
        val samples = mutableListOf<MuxSample>()
        var outputFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var fed = 0
            var eos = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 90_000_000_000L
            while (true) {
                check(System.nanoTime() < deadline) { "Encoder timeout" }
                if (!eos) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val pts = baseUs + if (video) fed * 100_000L else fed * 1024_000_000L / 32_000
                        if (fed == frames) {
                            codec.queueInputBuffer(input, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            if (adaptive && fed in listOf(60, 140)) codec.setParameters(Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, if (fed == 60) 1_500_000 else 2_000_000)
                            })
                            val size: Int
                            if (video) {
                                val image = checkNotNull(codec.getInputImage(input))
                                for ((index, plane) in image.planes.withIndex()) {
                                    val w = if (index == 0) 320 else 160
                                    val h = if (index == 0) 240 else 120
                                    for (y in 0 until h) for (x in 0 until w) {
                                        val value = if (complex && index == 0) {
                                            // Deterministic moving texture stresses rate control equally in both runs.
                                            val n = (x + fed * 11) * 73856093 xor (y * 19349663) xor (fed * 83492791)
                                            16 + ((n xor (n ushr 13)) and 0x7fffffff) % 220
                                        } else if (index == 0) 90 + (fed % 10) * 10 else if (index == 1) 90 else 180
                                        plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                                    }
                                }
                                image.close()
                                size = 320 * 240 * 3 / 2
                            } else {
                                val buffer = checkNotNull(codec.getInputBuffer(input)).apply { clear() }
                                for (i in 0 until 1024) {
                                    val value = (sin((fed * 1024 + i) * 2.0 * Math.PI * 440 / 32_000) * 12000).toInt()
                                    buffer.put(value.toByte()); buffer.put((value shr 8).toByte())
                                }
                                size = 2048
                            }
                            codec.queueInputBuffer(input, 0, size, pts, 0)
                            fed++
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = codec.outputFormat
                if (out >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(out))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buffer.get(bytes)
                        samples += MuxSample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
        } finally { codec.release() }
        return checkNotNull(outputFormat) to samples
    }

    private fun bitrateUiChecks() {
        var failure: Throwable? = null
        runOnMainSync {
            try {
                for (language in listOf("en", "sr-Latn")) {
                    val config = android.content.res.Configuration(targetContext.resources.configuration)
                    config.setLocale(java.util.Locale.forLanguageTag(language))
                    val ctx = targetContext.createConfigurationContext(config)
                    val choice = com.fieldnotes.recorder.ui.BitrateChoice(ctx)
                    fun children(view: android.view.ViewGroup) = (0 until view.childCount).map { view.getChildAt(it) }
                    val picker = children(choice).filterIsInstance<android.widget.Spinner>().single()
                    val input = children(choice).filterIsInstance<android.widget.EditText>().single()
                    picker.setSelection(6) // Custom value, shared by onboarding and Settings.
                    for (invalid in listOf("", "0", "199", "50001", "999999999999999999")) {
                        input.setText(invalid)
                        check(!choice.validate()) { "Invalid bitrate accepted: $invalid" }
                    }
                    input.setText("1234")
                    check(choice.validate())
                    val restored = com.fieldnotes.recorder.ui.BitrateChoice(ctx)
                    restored.restoreDraft(choice.draft())
                    check(restored.validate())
                    check(restored.draft().getString("input") == "1234" && restored.draft().getInt("selection") == 6)
                    picker.setSelection(0)
                    input.setText("")
                    check(choice.validate()) { "Hidden custom field must not block automatic mode" }
                    // Deliberately do not save: real user preferences remain untouched.
                }
            } catch (e: Throwable) { failure = e }
        }
        failure?.let { throw it }
    }

    private fun bitrateChecks(): String {
        val measurements = mutableListOf<Double>()
        val file = File.createTempFile("bitrate-synthetic-", ".mp4", targetContext.cacheDir)
        try {
            for (target in listOf(250, 2000)) {
                val (format, samples) = encode(true, target, complex = true)
                check(samples.size == 80 && samples.first().keyFrame) { "Dropped synthetic video frames" }
                check(format.getInteger(MediaFormat.KEY_WIDTH) == 320 && format.getInteger(MediaFormat.KEY_HEIGHT) == 240)
                check(samples.zipWithNext().all { (a, b) -> b.timeUs - a.timeUs == 100_000L }) { "Bitrate changed timing" }
                SegmentMuxer.write(file, null, format, emptyList(), samples, 10_000_000, orientation = 0)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    check(duration in 7800..8200) { "Wrong synthetic video duration: $duration" }
                    val frame = checkNotNull(retriever.getFrameAtTime(4_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
                    check(frame.width == 320 && frame.height == 240)
                    val colors = (0 until 16).map { frame.getPixel(it * 19, it * 13) }.toSet()
                    frame.recycle()
                    check(colors.size > 4) { "Synthetic recording decoded as a blank frame" }
                } finally { retriever.release() }
                measurements += samples.sumOf { it.data.size.toLong() } * 8.0 / 8000.0
            }
            check(measurements[1] > measurements[0] * 1.5) {
                "Encoder did not meaningfully respond to bitrate targets: $measurements kbps"
            }
            return "PASS: synthetic AVC targets 250/2000 kbps produced " +
                measurements.joinToString("/") { "%.1f".format(java.util.Locale.ROOT, it) } +
                " kbps; 80 frames each, same resolution/timing, decoded MP4s. No camera/microphone opened or settings changed."
        } finally { file.delete() }
    }

    private fun runChecks() {
        val (vf, video) = encode(true)
        val (af, audio) = encode(false)
        check(video.size == 200 && video.first().keyFrame)
        val folder = File(targetContext.cacheDir, "mux-regression").apply { mkdirs() }
        fun verify(name: String, a: List<MuxSample>, v: List<MuxSample>, audioFormat: MediaFormat? = af, videoFormat: MediaFormat? = vf) {
            val file = File(folder, "$name.mp4")
            SegmentMuxer.write(file, audioFormat, videoFormat, a, v, 22_000_000)
            check(Mp4MetadataStripper.stripInPlace(file))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (track in 0 until extractor.trackCount) {
                    val isVideo = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                    extractor.selectTrack(track)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var count = 0
                    var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        check(extractor.sampleTime > previous)
                        previous = extractor.sampleTime
                        count++
                        extractor.advance()
                    }
                    check(count == if (isVideo) v.size else a.size) { "Lost samples in $name: $count" }
                    extractor.unselectTrack(track)
                }
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                val first = listOfNotNull(a.firstOrNull()?.timeUs, v.firstOrNull()?.timeUs).min()
                val last = listOfNotNull(a.lastOrNull()?.timeUs, v.lastOrNull()?.timeUs).max()
                check(kotlin.math.abs(durationMs - (last - first) / 1000) < 300) { "$name duration $durationMs" }
                if (v.isNotEmpty()) {
                    for (time in listOf(0L, durationMs * 500, (durationMs - 200).coerceAtLeast(0) * 1000)) {
                        val bitmap = checkNotNull(retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)) { "No picture in $name" }
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        check(android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) > 80) { "Black decoded frame in $name" }
                        bitmap.recycle()
                    }
                }
            } finally { retriever.release() }
        }
        verify("full-20s", audio, video)
        val split = SegmentTimeline.splitIndex(video.take(115).map { it.keyFrame })
        check(split > 0)
        val boundary = video[split].timeUs
        verify("segment-one", audio.filter { it.timeUs < boundary }, video.take(split))
        verify("segment-two", audio.filter { it.timeUs >= boundary }, video.drop(split))
        verify("audio-only", audio, emptyList())
        verify("video-only", emptyList(), video)
        verify("short-stop", audio.take(10), video.take(3))
        val journalRoot = File(targetContext.cacheDir, "journal-regression").apply { mkdirs() }
        try {
            val journal = CaptureJournal.create(journalRoot, "synthetic-session", "synthetic-target", 90)
            journal.format(false, af); journal.format(true, vf)
            for (sample in audio) journal.append(false, sample.timeUs, false, sample.data)
            for (sample in video) journal.append(true, sample.timeUs, sample.keyFrame, sample.data)
            journal.flush()
            val reopened = CaptureJournal.open(journal.directory)
            val restored = reopened.remaining()
            val a = restored.filter { !it.video }.map { MuxSample(it.bytes, it.pts, false) }
            val v = restored.filter { it.video }.map { MuxSample(it.bytes, it.pts, it.key) }
            check(a.size == audio.size && v.size == video.size)
            check(a.zip(audio).all { (x, y) -> x.timeUs == y.timeUs && x.data.contentEquals(y.data) })
            check(v.zip(video).all { (x, y) -> x.timeUs == y.timeUs && x.keyFrame == y.keyFrame && x.data.contentEquals(y.data) })
            verify("journal-replay", a, v, CaptureJournal.restoreFormat(reopened.state.optJSONObject("audioFormat")),
                CaptureJournal.restoreFormat(reopened.state.optJSONObject("videoFormat")))
            reopened.beginSeal(java.util.UUID.randomUUID().toString(), audio.last().timeUs, video.last().timeUs)
            reopened.completeSeal()
            check(reopened.discardIfEmpty())
        } finally { journalRoot.deleteRecursively() }
        check(runCatching {
            SegmentMuxer.write(File(folder, "must-reject.mp4"), af, vf,
                audio.map { it.copy(timeUs = it.timeUs + baseUs) }, video, 22_000_000)
        }.isFailure) { "Clock mismatch was accepted" }
    }
}
