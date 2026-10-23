package com.fieldnotes.recorder.integrity

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fieldnotes.recorder.network.PinnedHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client for the OpenTimestamps calendar server protocol.
 *
 * Submits a SHA-256 digest to public OTS calendar servers, which batch
 * multiple digests into a Merkle tree and anchor the root into a Bitcoin
 * transaction via OP_RETURN.  The returned binary blob is an incomplete
 * OTS proof (a "pending" timestamp).  Once the Bitcoin transaction is
 * confirmed (~1-2 hours), the calendar server upgrades the proof to a
 * complete timestamp that can be independently verified against the
 * Bitcoin blockchain.
 *
 * Flow:
 *   1. POST 32-byte SHA-256 digest to calendar server
 *   2. Receive binary OTS proof (incomplete / pending)
 *   3. Store proof alongside the segment proof JSON
 *   4. Later, upgrade the proof by GETting the commitment from the calendar
 *
 * Cost: free.  No wallet, no keys, no gas fees.
 *
 * Verification (offline, by anyone):
 *   - Install `ots-cli` (npm) or use opentimestamps.org
 *   - `ots verify proof.ots`  →  confirms file existed before block N
 */
object OpenTimestampsClient {

    private const val TAG = "DW-OTS"

    /**
     * Hard cap on a calendar response body. A real OTS proof is a few KB; this
     * bounds memory so a malicious or compromised calendar server can't OOM the
     * app (and disrupt the recording/upload pipeline) by streaming an enormous
     * body into `ResponseBody.bytes()`, which is otherwise unbounded (security
     * review MEDIUM).
     */
    private const val MAX_PROOF_BYTES = 1 * 1024 * 1024 // 1 MiB

    /**
     * Read up to [MAX_PROOF_BYTES] from the response body. Returns null if the
     * body is absent or exceeds the cap (treated as a failed/hostile response).
     * Also enforces a declared Content-Length under the cap before reading.
     */
    private fun readBoundedBody(resp: okhttp3.Response): ByteArray? {
        val body = resp.body ?: return null
        val declared = body.contentLength()
        if (declared > MAX_PROOF_BYTES) {
            Log.w(TAG, "OTS response too large (declared $declared bytes) — rejecting")
            return null
        }
        body.byteStream().use { stream ->
            // Read MAX_PROOF_BYTES + 1 so we can detect (and reject) overrun even
            // when Content-Length is absent or lies.
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_PROOF_BYTES) {
                    Log.w(TAG, "OTS response exceeded $MAX_PROOF_BYTES bytes — rejecting")
                    return null
                }
                buf.write(chunk, 0, n)
            }
            return buf.toByteArray()
        }
    }

    /**
     * Public calendar servers.  We try multiple for redundancy; any single
     * successful response is sufficient for a valid timestamp proof.
     */
    private val CALENDAR_URLS = listOf(
        "https://a.pool.opentimestamps.org",
        "https://b.pool.opentimestamps.org",
        "https://a.pool.eternitywall.com"
    )

    /**
     * Build a client on demand so it picks up the current Tor proxy setting.
     * OTS requests are infrequent (once per segment) so no need to cache.
     *
     * Context is required so Tor routing and SOCKS-remote DNS apply.
     * Calling without a Context would silently bypass Tor and leak the
     * commitment hash + IP to the calendar server.
     */
    private fun buildClient(ctx: Context): OkHttpClient {
        return PinnedHttpClient.pinnedBuilder(ctx)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Submit a 32-byte SHA-256 digest to OTS calendar servers.
     *
     * @param digest  exactly 32 bytes (raw SHA-256 hash)
     * @param ctx     required — used for Tor proxy routing and DNS-leak prevention
     * @return  the raw OTS proof bytes from the first server that responds,
     *          or null if all servers fail (non-fatal: recording continues)
     */
    fun stamp(digest: ByteArray, ctx: Context): ByteArray? {
        require(digest.size == 32) { "digest must be exactly 32 bytes (SHA-256)" }

        for (url in CALENDAR_URLS) {
            try {
                val fullUrl = "$url/digest"
                val body = digest.toRequestBody("application/octet-stream".toMediaType())
                val request = Request.Builder()
                    .url(fullUrl)
                    .post(body)
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "application/octet-stream")
                    .build()

                val response = buildClient(ctx).newCall(request).execute()
                val proofBytes = response.use { resp ->
                    if (resp.isSuccessful) {
                        readBoundedBody(resp)
                    } else {
                        Log.w(TAG, "OTS calendar $url returned ${resp.code}")
                        null
                    }
                }
                if (proofBytes != null && proofBytes.isNotEmpty()) {
                    Log.i(TAG, "OTS timestamp obtained from $url: ${proofBytes.size} bytes")
                    val detached = OtsProofParser.detached(digest, proofBytes)
                    if (OtsProofParser(detached).isValid()) return detached
                }
            } catch (e: Exception) {
                Log.w(TAG, "OTS calendar $url failed: ${e.message}")
            }
        }

        Log.w(TAG, "All OTS calendar servers unreachable — timestamp skipped (non-fatal)")
        return null
    }

    /**
     * Try to upgrade a pending OTS proof to a complete Bitcoin-anchored proof.
     * Call this periodically (e.g., during upload drain) for any pending proofs.
     *
     * Parses the proof to find pending attestations, then queries each calendar
     * server for the Bitcoin-anchored upgrade. A single successful upgrade is
     * sufficient — the proof only needs one path to a Bitcoin block.
     *
     * @param pendingProof  the original proof bytes from [stamp]
     * @param ctx           required — used for Tor proxy routing and DNS-leak prevention
     * @return  upgraded proof bytes, or null if not yet confirmed
     */
    fun upgrade(pendingProof: ByteArray, ctx: Context): ByteArray? {
        val parser = OtsProofParser(pendingProof)

        if (parser.isUpgraded()) {
            Log.d(TAG, "OTS proof already Bitcoin-anchored")
            return pendingProof
        }

        val pending = parser.findPendingAttestations()
        if (pending.isEmpty()) {
            Log.w(TAG, "OTS proof has no pending attestations and is not upgraded — may be corrupt")
            return null
        }

        val client = buildClient(ctx)

        for (att in pending.take(3)) {
            if (att.calendarUrl.trimEnd('/') !in CALENDAR_URLS) continue
            try {
                val commitmentHex = att.commitment.joinToString("") { "%02x".format(it) }
                val url = "${att.calendarUrl}/timestamp/$commitmentHex"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "application/octet-stream")
                    .build()

                val upgradeBytes = client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        readBoundedBody(resp)
                    } else {
                        Log.d(TAG, "OTS upgrade not ready at ${att.calendarUrl}: ${resp.code}")
                        null
                    }
                }

                if (upgradeBytes != null && upgradeBytes.isNotEmpty()) {
                    val upgraded = parser.spliceUpgrade(pendingProof, att, upgradeBytes)
                    Log.i(TAG, "OTS proof upgraded via ${att.calendarUrl}")
                    if (OtsProofParser(upgraded).isUpgraded()) return upgraded
                }
            } catch (e: Exception) {
                Log.w(TAG, "OTS upgrade failed for ${att.calendarUrl}: ${e.message}")
            }
        }

        Log.d(TAG, "OTS proof not yet upgradeable (${pending.size} pending)")
        return null
    }
}
