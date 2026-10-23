package com.fieldnotes.recorder.upload

import android.content.Context
import android.util.AtomicFile
import com.fieldnotes.recorder.crypto.RecipientEncryption
import com.fieldnotes.recorder.integrity.IntegrityManager
import com.fieldnotes.recorder.secure.SecureFile
import com.fieldnotes.recorder.util.Prefs
import org.json.JSONObject
import java.io.File

/** Persisted per item: retries cannot silently change confidentiality or destination. */
object DeliveryPolicy {
    val activeDestination = ThreadLocal<String?>()
    fun checkActiveDestination(ctx: Context) {
        activeDestination.get()?.let { if (it != target(ctx)) throw DestinationChangedException() }
    }

    private fun stateFile(file: File) = File(file.parentFile, file.name + ".delivery")
    fun target(ctx: Context): String {
        val fields = when (Prefs.uploadMethod(ctx)) {
            "saf" -> listOf("saf", Prefs.cloudFolderUri(ctx))
            "cloud" -> when (Prefs.cloudProvider(ctx)) {
                "nextcloud" -> listOf("nextcloud", Prefs.ncBaseUrl(ctx), Prefs.ncUsername(ctx), Prefs.ncFolder(ctx))
                "webdav" -> listOf("webdav", Prefs.cloudWebdavUrl(ctx), Prefs.cloudUsername(ctx), Prefs.cloudFolderPath(ctx))
                // Token changes must not silently move an existing backlog to a different account.
                else -> listOf(Prefs.cloudProvider(ctx), Prefs.cloudRefreshToken(ctx) ?: Prefs.cloudAccessToken(ctx), Prefs.cloudFolderPath(ctx))
            }
            else -> error("Unknown delivery method")
        }
        return IntegrityManager.sha256Hex(org.json.JSONArray(fields).toString().toByteArray())
    }

    fun bind(ctx: Context, queued: File): JSONObject {
        checkActiveDestination(ctx)
        val file = stateFile(queued)
        val atomic = AtomicFile(file)
        val state = if (file.exists() || File(file.path + ".bak").exists()) {
            JSONObject(String(SecureFile.decryptBytes(atomic.readFully()), Charsets.UTF_8))
        } else {
            val mode = Prefs.exportMode(ctx)
            require(mode in listOf("plaintext", "encrypted")) { "Choose upload encryption in Settings" }
            val key = if (mode == "encrypted") Prefs.recipientPublicKey(ctx) else ""
            if (mode == "encrypted") RecipientEncryption.parsePublicKey(key)
            JSONObject().put("mode", mode).put("key", key).put("target", target(ctx))
                .put("subfolder", EvidenceLayout.folderFor(queued.name)).also { obj ->
                val out = atomic.startWrite()
                try {
                    out.write(SecureFile.encryptBytes(obj.toString().toByteArray()))
                    out.fd.sync()
                    atomic.finishWrite(out)
                } catch (e: Exception) {
                    atomic.failWrite(out)
                    throw e
                }
            }
        }
        if (state.getString("target") != target(ctx)) throw DestinationChangedException()
        return state
    }

    fun export(ctx: Context, queued: File, state: JSONObject): File {
        val name = queued.name.removeSuffix(".enc")
        if (state.getString("mode") == "plaintext") {
            return UploadQueue.decryptToTemp(ctx, queued) ?: error("Could not decrypt queued file")
        }
        require(state.getString("mode") == "encrypted")
        // Cache ciphertext so every retry has identical bytes and remote checksum.
        val dir = File(ctx.filesDir, "export_ciphertext").also { it.mkdirs() }
        val dest = File(dir, "$name.jwe")
        if (dest.exists() && state.has("exportSha256")) {
            check(IntegrityManager.toHex(IntegrityManager.sha256File(dest)) == state.getString("exportSha256")) {
                "Cached encrypted export is corrupt; original queue retained"
            }
            return dest
        }
        // A crash before the policy commit left unpublished ciphertext; regenerate it.
        if (dest.exists()) check(dest.delete())
        val tmp = File(dir, "$name.jwe.partial")
        try {
            tmp.outputStream().use { out ->
                RecipientEncryption.encrypt(state.getString("key"), out) { SecureFile.decryptTo(queued, it) }
                out.fd.sync()
            }
            check(tmp.renameTo(dest)) { "Could not commit encrypted export" }
            state.put("exportSha256", IntegrityManager.toHex(IntegrityManager.sha256File(dest)))
            val atomic = AtomicFile(stateFile(queued))
            val output = atomic.startWrite()
            try {
                output.write(SecureFile.encryptBytes(state.toString().toByteArray(Charsets.UTF_8)))
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            return dest
        } finally { tmp.delete() }
    }

    fun removeState(queued: File) { AtomicFile(stateFile(queued)).delete() }
}
