package com.fieldnotes.recorder.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.crypto.RecipientEncryption
import com.fieldnotes.recorder.network.TorManager
import com.fieldnotes.recorder.util.Prefs

/** Shared by the preflight UI and uploader. Call on IO: Tor probes may block. */
object UploadReadiness {
    fun blocker(ctx: Context): Int? {
        when (Prefs.exportMode(ctx)) {
            "plaintext" -> Unit
            "encrypted" -> try { RecipientEncryption.parsePublicKey(Prefs.recipientPublicKey(ctx)) }
                catch (_: Exception) { return R.string.upload_block_key }
            else -> return R.string.upload_block_choice
        }
        val saf = Prefs.uploadMethod(ctx) == "saf"
        if (saf) {
            val uri = Prefs.cloudFolderUri(ctx)?.let(Uri::parse) ?: return R.string.upload_block_destination
            val grant = ctx.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            if (grant?.isReadPermission != true || !grant.isWritePermission) return R.string.upload_block_folder
            if (Prefs.isLocalSaf(ctx)) return null
            if (Prefs.isTorEnabled(ctx) || Prefs.isSecureNetworkRequired(ctx)) return R.string.upload_block_external_saf
        } else if (Prefs.uploadMethod(ctx) == "cloud") {
            val configured = when (Prefs.cloudProvider(ctx)) {
                "nextcloud" -> !Prefs.ncBaseUrl(ctx).isNullOrBlank() && !Prefs.ncUsername(ctx).isNullOrBlank() && !Prefs.ncAppPassword(ctx).isNullOrBlank()
                "webdav" -> !Prefs.cloudWebdavUrl(ctx).isNullOrBlank() && !Prefs.cloudUsername(ctx).isNullOrBlank() && !Prefs.cloudPassword(ctx).isNullOrBlank()
                "dropbox", "google_drive" -> !Prefs.cloudAccessToken(ctx).isNullOrBlank() ||
                    (!Prefs.cloudRefreshToken(ctx).isNullOrBlank() && !Prefs.cloudAppKey(ctx).isNullOrBlank())
                else -> false
            }
            if (!configured) return R.string.upload_block_destination
        } else return R.string.upload_block_destination
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.activeNetwork == null) return R.string.upload_block_offline
        if (Prefs.isTorEnabled(ctx)) {
            if (!TorManager.isProxyReachable()) return R.string.upload_block_tor
        } else if (Prefs.isSecureNetworkRequired(ctx) && !TorManager.isVpnActive(ctx)) return R.string.upload_block_vpn
        return null
    }
}

/** A live attempt is distinct from a verified delivery. No evidence identifiers stored. */
object UploadStatus {
    @Volatile var active = false
    @Volatile var timestampActive = false
    @Volatile var failure: Int? = null
    @Volatile var verifiedFiles = 0
    @Volatile var lastDeliveryWasSaf = false
    @Volatile var lastTarget: String? = null
}

class DestinationChangedException : IllegalStateException("Queued destination changed")

/** End this drain pass on a provider-wide transient failure instead of hammering every queued part. */
class UploadRetryLaterException : java.io.IOException("Upload service temporarily unavailable")
