package com.fieldnotes.recorder.cloud

import android.content.Context
import android.util.Log
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fieldnotes.recorder.network.PinnedHttpClient
import com.fieldnotes.recorder.util.Prefs
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Dedicated Nextcloud provider.
 *
 * Improves on the generic [WebDavProvider] for the Nextcloud use case:
 *  - **Streaming, chunked, resumable upload** via Nextcloud's chunked-upload v2
 *    endpoint (`remote.php/dav/uploads/USER/…`). Large segments over flaky Tor
 *    no longer restart from zero, and nothing is loaded fully into RAM.
 *  - **App-password auth** (Settings → Security → Devices & sessions): a
 *    revocable per-app credential, never the account password.
 *  - **Optional cert-fingerprint pinning** for a self-hosted server whose
 *    certificate is unknown at compile time (defeats state-CA MITM).
 *  - **.onion support**: a Nextcloud behind a Tor hidden service is the
 *    strongest setup (no exit node, self-authenticating address).
 *
 * Config lives in dedicated `nc_*` prefs so it is independent of the generic
 * WebDAV slots. Folder defaults to `/Documents` (audit B13: innocuous).
 *
 * Note on obfuscation: [CloudUploadManager] hands us a `remotePath` whose final
 * segment is already a random-UUID filename. The manager constructs its path
 * from [Prefs.ncFolder], including the Evidence subfolder for sidecars.
 */
class NextcloudProvider(private val context: Context) : CloudProvider {

    companion object {
        private const val TAG = "DW-Nextcloud"
        private const val MB = 1024 * 1024
        // Width to zero-pad chunk start-offsets so they sort lexicographically
        // in the order the server assembles them. 16 digits covers any file.
        private const val OFFSET_PAD = 16
    }

    // ── Configuration / URL derivation ──────────────────────────────────────

    private fun serverRoot(): String? {
        var raw = Prefs.ncBaseUrl(context)?.trim()?.removeSuffix("/") ?: return null
        if (raw.isEmpty()) return null
        // Tolerate a missing scheme (a bare host would otherwise fail URL parsing
        // and silently break uploads). Default to https:// for EVERYTHING,
        // including .onion hidden services. The previous code defaulted .onion
        // to http://, but network_security_config.xml disallows cleartext
        // globally, so those uploads always failed silently (audit H2).
        //
        // Tor hidden services can and should terminate TLS: the .onion address
        // is already self-authenticating, and TLS-on-onion adds a second
        // defense layer (and is required for the cert-pin feature to work).
        // A user with a genuinely plaintext-only onion service can still type
        // the explicit "http://" prefix — but that's now the deliberate choice,
        // not the silent default.
        if (!raw.contains("://")) {
            raw = "https://$raw"
        }
        // Tolerate a user pasting a full DAV URL — cut everything from the
        // well-known DAV prefix onward to recover the bare server root.
        val idx = raw.indexOf("/remote.php/")
        return if (idx >= 0) raw.substring(0, idx) else raw
    }

    /** `<server>/remote.php/dav/files/<user>/` as an [HttpUrl], or null if unconfigured. */
    private fun filesRoot(): HttpUrl? {
        val root = serverRoot() ?: return null
        val user = Prefs.ncUsername(context)?.trim() ?: return null
        if (user.isEmpty()) return null
        val base = root.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(user)
            .build()
    }

    /** `<server>/remote.php/dav/uploads/<user>/` as an [HttpUrl]. */
    private fun uploadsRoot(): HttpUrl? {
        val root = serverRoot() ?: return null
        val user = Prefs.ncUsername(context)?.trim() ?: return null
        if (user.isEmpty()) return null
        val base = root.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("remote.php/dav/uploads")
            .addPathSegment(user)
            .build()
    }

    private fun authHeader(): String? {
        val user = Prefs.ncUsername(context)?.trim()
        val pass = Prefs.ncAppPassword(context)
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        return Credentials.basic(user, pass)
    }

    // ── HTTP client (Tor-routed; optionally cert-pinned for self-hosted) ─────

    private val client: OkHttpClient by lazy {
        val host = serverRoot()?.toHttpUrlOrNull()?.host
        val pin = Prefs.ncCertPin(context)
        val builder = if (!host.isNullOrEmpty() && !pin.isNullOrEmpty()) {
            PinnedHttpClient.pinnedBuilderForHost(context, host, pin)
        } else {
            PinnedHttpClient.unpinnedBuilder(context)
        }
        builder
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ── CloudProvider ───────────────────────────────────────────────────────

    override suspend fun authenticate(): Boolean {
        val root = filesRoot() ?: run { Log.w(TAG, "Missing Nextcloud config"); return false }
        val auth = authHeader() ?: return false
        return try {
            val request = Request.Builder()
                .url(root)
                .method("PROPFIND", ByteArray(0).toRequestBody())
                .addHeader("Authorization", auth)
                .addHeader("Depth", "0")
                .build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful || response.code == 207
                if (!ok) Log.w(TAG, "Auth failed: ${response.code}")
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth error", e)
            false
        }
    }

    override fun isAuthenticated(): Boolean {
        return !Prefs.ncBaseUrl(context).isNullOrEmpty() &&
            !Prefs.ncUsername(context).isNullOrEmpty() &&
            !Prefs.ncAppPassword(context).isNullOrEmpty()
    }

    override suspend fun createFolder(path: String): Boolean {
        // The manager passes this provider's configured root, plus any evidence subfolder.
        return ensureDestFolder(path)
    }

    /** MKCOL the configured destination folder (idempotent). */
    private fun ensureDestFolder(path: String = Prefs.ncFolder(context)): Boolean {
        val root = filesRoot() ?: return false
        val auth = authHeader() ?: return false
        val folder = path.trim('/')
        if (folder.isEmpty()) return true
        return try {
            val url = root.newBuilder().addPathSegments(folder).build()
            val request = Request.Builder()
                .url(url)
                .method("MKCOL", ByteArray(0).toRequestBody())
                .addHeader("Authorization", auth)
                .build()
            client.newCall(request).execute().use { response ->
                // 201 created, 405 already exists. Anything else (401, 403,
                // 409, 5xx, network) is a real failure and must propagate —
                // returning true here would let uploads proceed against a
                // server where the dest folder doesn't exist, then fail
                // confusingly at the MOVE step (audit H13).
                response.isSuccessful || response.code == 201 || response.code == 405
            }
        } catch (e: Exception) {
            // Surface the failure; do NOT mask it as success.
            Log.e(TAG, "MKCOL error", e)
            false
        }
    }

    /** Destination [HttpUrl] for the final assembled file (folder + filename). */
    private fun destUrl(remotePath: String): HttpUrl? {
        val root = filesRoot() ?: return null
        return root.newBuilder().addPathSegments(remotePath.trim('/')).build()
    }

    override suspend fun uploadFile(file: File, remotePath: String): Boolean {
        if (!file.exists()) {
            Log.w(TAG, "File does not exist")
            return false
        }
        val auth = authHeader() ?: return false
        val dest = destUrl(remotePath) ?: return false
        val fullSize = file.length()

        ensureDestFolder(remotePath.substringBeforeLast('/', "")).let { folderOk ->
            if (!folderOk) {
                Log.w(TAG, "Destination folder unavailable — aborting upload")
                return false
            }
        }

        val chunkSize = chunkSizeFor(file, fullSize)
        return try {
            if (fullSize <= chunkSize) {
                simplePut(file, dest, auth)
            } else {
                chunkedUpload(file, dest, auth, fullSize, chunkSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            false
        }
    }

    // ── Simple (single PUT) path for small files ─────────────────────────────

    private fun simplePut(file: File, dest: HttpUrl, auth: String): Boolean {
        val request = Request.Builder()
            .url(dest)
            .put(FileRequestBody(file))
            .addHeader("Authorization", auth)
            .build()
        client.newCall(request).execute().use { response ->
            val ok = response.isSuccessful || response.code in listOf(200, 201, 204)
            if (!ok) Log.w(TAG, "PUT failed: ${response.code}")
            if (!ok) return false
        }
        return verifyAssembledFile(dest, auth, file.length(), clientFileSha256(file))
    }

    // ── Chunked / resumable path ─────────────────────────────────────────────

    private fun chunkedUpload(
        file: File,
        dest: HttpUrl,
        auth: String,
        fullSize: Long,
        chunkSize: Long
    ): Boolean {
        val uploadsRoot = uploadsRoot() ?: return false
        // Stable, content-derived upload id so a resumed run (after process
        // death / Tor drop) reuses the same server-side upload directory.
        val uploadId = stableUploadId(file, fullSize)
        val uploadDir = uploadsRoot.newBuilder().addPathSegment(uploadId).build()

        // MKCOL the upload session dir (idempotent: 405 = already exists/resuming).
        if (!mkcol(uploadDir, auth, dest)) return false

        // Discover which chunks already landed (by start-offset → byte length).
        val existing = listExistingChunks(uploadDir, auth)

        var offset = 0L
        while (offset < fullSize) {
            val thisLen = minOf(chunkSize, fullSize - offset)
            val index = offset / chunkSize + 1
            require(index <= 10000) { "Too many chunks" }
            val name = index.toString().padStart(5, '0')
            if (existing[index] == thisLen) {
                offset += thisLen
                continue // already uploaded fully
            }
            val chunkUrl = uploadDir.newBuilder().addPathSegment(name).build()
            val request = Request.Builder()
                .url(chunkUrl)
                .put(FileRequestBody(file, offset = offset, byteCount = thisLen))
                .addHeader("Authorization", auth)
                .addHeader("Destination", dest.toString())
                .addHeader("OC-Total-Length", fullSize.toString())
                .build()
            client.newCall(request).execute().use { response ->
                if (!(response.isSuccessful || response.code in listOf(200, 201, 204))) {
                    Log.w(TAG, "Chunk PUT failed at $offset: ${response.code}")
                    return false
                }
            }
            offset += thisLen
        }

        // Assemble: MOVE the virtual `.file` to the destination.
        val moveSrc = uploadDir.newBuilder().addPathSegment(".file").build()
        // Send the client-computed SHA-256 so the server can verify assembly
        // (Nextcloud ≥ 21 supports the X-Hash header on MOVE for this). If the
        // server doesn't support it, the post-MOVE PROPFIND below still
        // catches a length mismatch. Without either check, a corrupted or
        // tampered assembly would be accepted silently (audit H3).
        val clientSha256 = clientFileSha256(file)
        val moveReq = Request.Builder()
            .url(moveSrc)
            .method("MOVE", null)
            .addHeader("Authorization", auth)
            .addHeader("Destination", dest.toString())
            .addHeader("OC-Total-Length", fullSize.toString())
            .addHeader("Overwrite", "T")
            .apply { if (clientSha256 != null) addHeader("X-Hash", "sha256:$clientSha256") }
            .build()
        client.newCall(moveReq).execute().use { response ->
            val ok = response.isSuccessful || response.code in listOf(201, 204)
            if (!ok) {
                Log.w(TAG, "MOVE failed: ${response.code}")
                return false
            }
        }

        // Verify the assembled file server-side. A successful MOVE only means
        // the server accepted the chunks — it does NOT mean the assembled bytes
        // match what we uploaded (a flaky backend, a tampering middlebox, or a
        // partial-chunk resume bug could all produce a wrong result that 201s).
        // PROPFIND the destination and compare length (always) + checksum (if
        // the server exposes oc:checksums). Mismatch → hard fail (audit H3).
        return verifyAssembledFile(dest, auth, fullSize, clientSha256)
    }

    /**
     * PROPFIND the assembled destination and confirm its length (and, when the
     * server advertises it, SHA-256 checksum) match what we uploaded. Returns
     * false on any mismatch so a corrupted/tampered upload is rejected rather
     * than silently accepted.
     */
    private fun verifyAssembledFile(
        dest: HttpUrl, auth: String, expectedLen: Long, expectedSha256: String?
    ): Boolean {
        if (expectedSha256 == null) return false
        return try {
            val req = Request.Builder().url(dest).get().addHeader("Authorization", auth).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                if (body.contentLength() >= 0 && body.contentLength() != expectedLen) return false
                var total = 0L
                val hash = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > expectedLen) return false
                        hash.update(buffer, 0, n)
                    }
                }
                total == expectedLen && hash.digest().joinToString("") { "%02x".format(it) } == expectedSha256
            }
        } catch (e: Exception) { false }
    }

    /** SHA-256 of the local file, hex; null on IO error. */
    private fun clientFileSha256(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "clientFileSha256 failed", e)
            null
        }
    }

    private fun mkcol(url: HttpUrl, auth: String, dest: HttpUrl): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .method("MKCOL", ByteArray(0).toRequestBody())
                .addHeader("Authorization", auth)
                .addHeader("Destination", dest.toString())
                .build()
            client.newCall(request).execute().use { it.code == 201 || it.code == 405 }
        } catch (e: Exception) {
            Log.e(TAG, "MKCOL(upload dir) error", e)
            false
        }
    }

    /** PROPFIND the upload dir; return map of chunk start-offset → content length. */
    private fun listExistingChunks(uploadDir: HttpUrl, auth: String): Map<Long, Long> {
        return try {
            val request = Request.Builder()
                .url(uploadDir)
                .method("PROPFIND", ByteArray(0).toRequestBody())
                .addHeader("Authorization", auth)
                .addHeader("Depth", "1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!(response.isSuccessful || response.code == 207)) return emptyMap()
                val body = response.body?.string() ?: return emptyMap()
                parseChunkListing(body)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun parseChunkListing(xml: String): Map<Long, Long> {
        val result = HashMap<Long, Long>()
        // Tolerant of namespace prefixes (d:, D:, …).
        val responseRe = Regex("(?is)<[a-z0-9]*:?response[ >].*?</[a-z0-9]*:?response>")
        val hrefRe = Regex("(?is)<[a-z0-9]*:?href>\\s*([^<]+?)\\s*</[a-z0-9]*:?href>")
        val lenRe = Regex("(?is)<[a-z0-9]*:?getcontentlength>\\s*(\\d+)\\s*</[a-z0-9]*:?getcontentlength>")
        for (block in responseRe.findAll(xml)) {
            val seg = block.value
            val href = hrefRe.find(seg)?.groupValues?.get(1) ?: continue
            val last = href.trimEnd('/').substringAfterLast('/')
            // Only numeric chunk files (skip the dir itself and ".file").
            val offset = last.toLongOrNull() ?: continue
            val len = lenRe.find(seg)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            result[offset] = len
        }
        return result
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Per-file deterministic chunk size. Starts from the user's base size and
     * adds a content-derived jitter of 0–2 MB (audit B16: vary chunk sizes so
     * they aren't a constant traffic fingerprint), while staying deterministic
     * across restarts so resume reconstructs identical chunk boundaries.
     */
    private fun chunkSizeFor(file: File, fullSize: Long): Long {
        val baseMb = Prefs.ncChunkMb(context).toLong()
        val base = baseMb * MB
        val digest = stableDigest(file, fullSize)
        // 24-bit content-derived value → jitter spans the full 0..2 MB range.
        val raw = (digest[0].toInt() and 0xFF) or
            ((digest[1].toInt() and 0xFF) shl 8) or
            ((digest[2].toInt() and 0xFF) shl 16)
        val jitter = raw % (2 * MB)
        return base + jitter
    }

    private fun stableUploadId(file: File, fullSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(stableDigest(file, fullSize) + Prefs.ncChunkMb(context).toString().toByteArray())
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append("%02x".format(digest[i]))
        return sb.toString()
    }

    /** SHA-256 over the file's absolute path + size — stable across process death. */
    private fun stableDigest(file: File, fullSize: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(file.name.toByteArray())
        md.update((clientFileSha256(file) ?: error("Could not hash source")).toByteArray())
        md.update(":".toByteArray())
        md.update(fullSize.toString().toByteArray())
        return md.digest()
    }

    /**
     * Connects to the configured server and returns the leaf certificate's
     * SHA-256 SPKI fingerprint (base64, no "sha256/" prefix) so the user can
     * pin it after verifying out-of-band. Returns null for .onion / plaintext
     * (no TLS) or on failure. Uses an unpinned client so it can observe
     * whatever certificate is actually presented.
     */
    suspend fun fetchCertFingerprint(): String? {
        val root = filesRoot() ?: return null
        return try {
            val c = PinnedHttpClient.unpinnedBuilder(context)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val builder = Request.Builder()
                .url(root)
                .method("PROPFIND", ByteArray(0).toRequestBody())
                .addHeader("Depth", "0")
            // Certificate enrollment must never send credentials before trust is established.
            c.newCall(builder.build()).execute().use { response ->
                val leaf = response.handshake?.peerCertificates?.firstOrNull()
                    as? java.security.cert.X509Certificate ?: return null
                val spki = leaf.publicKey.encoded
                val sha = MessageDigest.getInstance("SHA-256").digest(spki)
                android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fingerprint fetch failed", e)
            null
        }
    }

    override fun getProviderName(): String = "Nextcloud"

    override fun requiresOAuth(): Boolean = false
}
