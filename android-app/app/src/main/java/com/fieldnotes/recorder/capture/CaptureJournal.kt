package com.fieldnotes.recorder.capture

import android.media.MediaFormat
import android.util.AtomicFile
import android.util.Base64
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.PanicWipe
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID

/** Encrypted, atomic one-second packet batches. A kill can lose the unflushed batch, never a sealed batch. */
class CaptureJournal private constructor(val directory: File, val state: JSONObject) {
    data class Packet(val video: Boolean, val pts: Long, val key: Boolean, val bytes: ByteArray)
    // Encoder callbacks must never acquire the disk/state transaction monitor.
    // Otherwise an fsync/Keystore stall stops AudioRecord reads and its PCM ring overruns.
    private val packetLock = Any()
    private val buffered = ArrayList<Packet>()
    private var bufferedBytes = 0L
    private var flushingBytes = 0L
    private var nextBlock = (blocks().maxOfOrNull { it.name.substringBefore('.').toLong() } ?: -1) + 1

    companion object {
        const val ROOT = "capture_journal"
        const val MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_PACKET = 8 * 1024 * 1024
        private const val STATE = "state.spf"
        @JvmOverloads fun create(root: File, session: String, target: String, orientation: Int, anonymous: Boolean = false): CaptureJournal {
            val dir = File(root, UUID.randomUUID().toString())
            check(dir.mkdirs())
            val journal = CaptureJournal(dir, JSONObject().put("version", 1).put("session", session)
                .put("target", target).put("orientation", orientation).put("anonymous", anonymous).put("audioThrough", -1L).put("videoThrough", -1L))
            journal.save()
            return journal
        }
        fun open(dir: File): CaptureJournal {
            val state = JSONObject(String(SecureFile.decryptBytes(AtomicFile(File(dir, STATE)).readFully()), Charsets.UTF_8))
            require(state.getInt("version") == 1)
            return CaptureJournal(dir, state)
        }
        private fun write(file: File, data: ByteArray) {
            check(!PanicWipe.wipeInProgress)
            val ciphertext = SecureFile.encryptBytes(data)
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(ciphertext); out.fd.sync()
                check(!PanicWipe.wipeInProgress)
                atomic.finishWrite(out)
                com.fieldnotes.recorder.secure.FileDurability.syncDirectory(file.parentFile!!)
            } catch (error: Exception) { atomic.failWrite(out); throw error }
        }
        fun restoreFormat(json: JSONObject?): MediaFormat? = json?.let {
            MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, it.getString("mime"))
                for (key in listOf("width", "height", "sample-rate", "channel-count", "aac-profile"))
                    if (it.has(key)) setInteger(key, it.getInt(key))
                for (index in 0..2) {
                    val key = "csd-$index"
                    if (it.has(key)) setByteBuffer(key, ByteBuffer.wrap(Base64.decode(it.getString(key), Base64.NO_WRAP)))
                }
            }
        }
    }

    private fun blocks() = directory.listFiles()?.filter { it.name.matches(Regex("[0-9]{12}\\.spf")) }
        ?.sortedBy { it.name } ?: emptyList()
    private fun save() = write(File(directory, STATE), state.toString().toByteArray(Charsets.UTF_8))

    @Synchronized fun format(video: Boolean, format: MediaFormat) {
        val json = JSONObject().put("mime", format.getString(MediaFormat.KEY_MIME))
        for (key in listOf("width", "height", "sample-rate", "channel-count", "aac-profile"))
            if (format.containsKey(key)) json.put(key, format.getInteger(key))
        for (index in 0..2) {
            val key = "csd-$index"
            format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                json.put(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        state.put(if (video) "videoFormat" else "audioFormat", json)
        save()
    }

    fun append(video: Boolean, pts: Long, key: Boolean, bytes: ByteArray) = synchronized(packetLock) {
        check(!PanicWipe.wipeInProgress)
        require(pts >= 0 && bytes.size in 1..MAX_PACKET)
        check(bufferedBytes + flushingBytes + bytes.size <= MAX_BYTES) { "Capture journal buffer limit reached" }
        buffered.add(Packet(video, pts, key, bytes))
        bufferedBytes += bytes.size
    }

    @Synchronized fun flush() {
        val (batch, size) = synchronized(packetLock) {
            if (buffered.isEmpty()) return
            val snapshot = buffered.toList() to bufferedBytes
            flushingBytes += bufferedBytes
            buffered.clear(); bufferedBytes = 0
            snapshot
        }
        var committed = false
        try {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(1); out.writeInt(batch.size)
                batch.forEach { p ->
                    out.writeBoolean(p.video); out.writeLong(p.pts); out.writeBoolean(p.key)
                    out.writeInt(p.bytes.size); out.write(p.bytes)
                }
            }
            write(File(directory, "%012d.spf".format(java.util.Locale.US, nextBlock)), bytes.toByteArray())
            nextBlock++
            committed = true
        } finally {
            synchronized(packetLock) {
                // Retry the exact failed batch before packets captured during its write.
                if (!committed) { buffered.addAll(0, batch); bufferedBytes += size }
                flushingBytes -= size
            }
        }
    }

    private fun read(file: File): List<Packet> {
        require(file.length() <= MAX_BYTES + 1024 * 1024)
        val bytes = SecureFile.decryptBytes(file.readBytes())
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val count = input.readInt(); require(count in 1..100_000)
            val packets = ArrayList<Packet>()
            repeat(count) {
                val video = input.readBoolean(); val pts = input.readLong(); val key = input.readBoolean()
                val length = input.readInt(); require(pts >= 0 && length in 1..MAX_PACKET && length <= input.available())
                packets.add(Packet(video, pts, key, ByteArray(length).also { input.readFully(it) }))
            }
            require(input.available() == 0)
            packets
        }
    }

    @Synchronized fun remaining(): List<Packet> {
        var size = 0L
        val pending = ArrayList<Packet>()
        for (file in blocks()) for (p in read(file)) {
            if (p.pts > state.getLong(if (p.video) "videoThrough" else "audioThrough")) {
                size += p.bytes.size
                check(size <= MAX_BYTES) { "Recovery memory limit reached" }
                pending.add(p)
            }
        }
        return pending
    }

    /** Persist the exact cut before mux/proof work, so replay uses the same filename and sample boundaries. */
    @Synchronized fun beginSeal(stem: String, audioThrough: Long, videoThrough: Long) {
        check(!state.has("seal")) { "An earlier segment still needs recovery" }
        require(UUID.fromString(stem).toString() == stem)
        flush()
        state.put("seal", JSONObject().put("stem", stem).put("audio", audioThrough).put("video", videoThrough))
        save()
    }

    @Synchronized fun completeSeal() {
        val seal = state.getJSONObject("seal")
        state.put("audioThrough", maxOf(state.getLong("audioThrough"), seal.getLong("audio")))
        state.put("videoThrough", maxOf(state.getLong("videoThrough"), seal.getLong("video")))
        state.put("lastPublication", seal.getString("stem"))
        state.remove("seal")
        save() // Cursor is durable before any journal batch is removed.
        blocks().forEach { file ->
            if (read(file).all { p -> p.pts <= state.getLong(if (p.video) "videoThrough" else "audioThrough") })
                check(file.delete())
        }
    }

    @Synchronized fun discardIfEmpty(): Boolean {
        flush()
        if (state.has("seal") || remaining().isNotEmpty()) return false
        check(directory.deleteRecursively())
        return true
    }
}
