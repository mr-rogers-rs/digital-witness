package com.fieldnotes.recorder.integrity

import java.security.MessageDigest

/** Bounded structural parser. A Bitcoin tag is an anchor CLAIM, never blockchain verification. */
class OtsProofParser(private val data: ByteArray) {
    companion object {
        private val MAGIC = byteArrayOf(0,79,112,101,110,84,105,109,101,115,116,97,109,112,115,
            0,0,80,114,111,111,102,0,191.toByte(),137.toByte(),226.toByte(),232.toByte(),
            132.toByte(),232.toByte(),146.toByte(),148.toByte())
        val PENDING_TAG = byteArrayOf(131.toByte(),223.toByte(),227.toByte(),13,46,249.toByte(),12,142.toByte())
        val BITCOIN_TAG = byteArrayOf(5,136.toByte(),150.toByte(),13,115,215.toByte(),25,1)
        const val HEADER_SIZE = 32
        fun detached(digest: ByteArray, operations: ByteArray): ByteArray {
            require(digest.size == 32)
            return MAGIC + byteArrayOf(1,8) + digest + operations
        }
    }
    data class PendingAttestation(val calendarUrl: String, val commitment: ByteArray,
        val attestationOffset: Int, val attestationEnd: Int)
    private var pos = 0
    private var bitcoin = false
    private fun byte(): Int { require(pos < data.size); return data[pos++].toInt() and 255 }
    private fun bytes(n: Int): ByteArray {
        require(n >= 0 && n <= data.size - pos)
        return data.copyOfRange(pos, pos+n).also { pos += n }
    }
    private fun integer(): Int {
        var result = 0
        for (shift in 0..28 step 7) {
            val b = byte()
            require(shift != 28 || b <= 7)
            result = result or ((b and 127) shl shift)
            if (b and 128 == 0) return result
        }
        error("Oversized varint")
    }
    private fun variable(): ByteArray = bytes(integer())
    private fun parse(): List<PendingAttestation> {
        pos = 0; bitcoin = false
        require(data.size in 66..1048641)
        require(bytes(MAGIC.size).contentEquals(MAGIC))
        require(byte() == 1 && byte() == 8)
        val stack = ArrayDeque<ByteArray>()
        stack.addLast(bytes(32))
        val result = mutableListOf<PendingAttestation>()
        var operations = 0
        while (stack.isNotEmpty()) {
            require(++operations <= 4096 && stack.size <= 256)
            val msg = stack.removeLast()
            when (val op = byte()) {
                255 -> { stack.addLast(msg); stack.addLast(msg) }
                240,241 -> {
                    val arg = variable()
                    require(arg.isNotEmpty() && arg.size <= 4096 && msg.size + arg.size <= 4096)
                    stack.addLast(if (op == 240) msg + arg else arg + msg)
                }
                8,2 -> stack.addLast(MessageDigest.getInstance(if (op == 8) "SHA-256" else "SHA-1").digest(msg))
                0 -> {
                    val start = pos-1
                    val tag = bytes(8)
                    val payload = variable()
                    val end = pos
                    if (tag.contentEquals(PENDING_TAG)) {
                        // Pending payload itself contains a varbytes URI.
                        val outer = pos
                        pos = end-payload.size
                        val uri = variable()
                        require(pos == end && uri.size in 1..1000)
                        val url = uri.toString(Charsets.US_ASCII)
                        require(uri.all { (it.toInt() and 255) in 33..126 })
                        result.add(PendingAttestation(url,msg,start,end))
                        pos = outer
                    } else if (tag.contentEquals(BITCOIN_TAG)) {
                        val outer = pos
                        pos = end-payload.size
                        integer()
                        require(pos == end && msg.size == 32)
                        bitcoin = true
                        pos = outer
                    }
                }
                else -> error("Unsupported operation $op")
            }
        }
        require(pos == data.size)
        return result
    }
    fun isValid(): Boolean = try { parse(); true } catch (_: Exception) { false }
    fun findPendingAttestations(): List<PendingAttestation> =
        try { parse() } catch (_: Exception) { emptyList() }
    fun isUpgraded(): Boolean = try { parse(); bitcoin } catch (_: Exception) { false }
    fun spliceUpgrade(originalProof: ByteArray, att: PendingAttestation, upgradeOps: ByteArray): ByteArray =
        originalProof.copyOfRange(0,att.attestationOffset) + upgradeOps +
            originalProof.copyOfRange(att.attestationEnd,originalProof.size)
}
