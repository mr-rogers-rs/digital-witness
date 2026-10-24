package com.fieldnotes.recorder.cloud

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.fieldnotes.recorder.network.PinnedHttpClient
import com.fieldnotes.recorder.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google Drive cloud storage provider using Google Drive API v3.
 *
 * Requires OAuth 2.0 access token.
 * For production, implement proper OAuth flow in the UI.
 *
 * Scopes needed:
 * - https://www.googleapis.com/auth/drive.file (access to files created by app)
 * or
 * - https://www.googleapis.com/auth/drive (full drive access)
 */
class GoogleDriveProvider(private val context: Context) : CloudProvider {

    companion object {
        private const val TAG = "DW-GDrive"
        private const val API_URL = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    }

    private val client = PinnedHttpClient.pinnedBuilder(context)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var rootFolderId: String? = null
    private val folderIds = HashMap<List<String>, String>()

    /**
     * Returns a live access token, refreshing via the stored refresh token if
     * needed. Google access tokens expire after 1 hour; the previous code held
     * a single token for the app's lifetime, so every upload after the first
     * hour failed silently with HTTP 401 (audit C9).
     *
     * Requires the OAuth client secret to be stored in the `cloud_app_key`
     * slot (same slot Dropbox uses for its app secret). Returns null if no
     * refresh material is configured.
     */
    private fun freshAccessToken(): String? {
        val current = Prefs.cloudAccessToken(context)
        if (current.isNullOrEmpty()) return null
        // We don't get token-expiry from Google in the implicit flow, so we
        // optimistically use the cached token and let a 401 trigger a refresh
        // via [withTokenRetry]. This avoids a network round-trip on every call
        // while still recovering from expiry.
        return current
    }

    /**
     * Refresh the access token using the stored refresh token + client secret.
     * On success, persists the new access token and returns it. Returns null
     * on any failure (no refresh token, no client secret, network error, or
     * Google rejected the refresh — e.g. user revoked consent).
     */
    @Synchronized
    private fun refreshAccessToken(staleToken: String? = null): String? {
        val current = Prefs.cloudAccessToken(context)
        if (staleToken != null && !current.isNullOrEmpty() && current != staleToken) return current
        val refreshToken = Prefs.cloudRefreshToken(context) ?: run {
            Log.w(TAG, "Cannot refresh: no refresh token stored")
            return null
        }
        val clientSecret = Prefs.cloudAppKey(context) ?: run {
            Log.w(TAG, "Cannot refresh: no client secret stored")
            return null
        }

        return try {
            val form = "grant_type=refresh_token" +
                "&refresh_token=${java.net.URLEncoder.encode(refreshToken, "UTF-8")}" +
                "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}"
            // client_id is stored in the username slot (OAuth reuse).
            val clientId = Prefs.cloudUsername(context)
            val fullForm = if (!clientId.isNullOrEmpty()) {
                form + "&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}"
            } else form

            val req = Request.Builder()
                .url(TOKEN_URL)
                .post(fullForm.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Token refresh failed: ${resp.code} ${resp.message}")
                    return null
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val newToken = json.optString("access_token", "")
                if (newToken.isEmpty()) {
                    Log.w(TAG, "Token refresh response missing access_token")
                    return null
                }
                Prefs.setCloudAccessTokenFor(context, "google_drive", newToken)
                // Google may rotate the refresh token; persist it if present.
                val newRefresh = json.optString("refresh_token", "")
                if (newRefresh.isNotEmpty()) {
                    Prefs.setCloudRefreshToken(context, newRefresh)
                }
                Log.i(TAG, "Access token refreshed")
                newToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            null
        }
    }

    /**
     * Runs [block] with the current access token. If the call returns 401,
     * refreshes the token once and retries. Propagates the [TokenCallResult]
     * so callers can distinguish auth-failure from network-failure.
     */
    private fun withTokenRetry(block: (String) -> TokenCallResult): TokenCallResult {
        val token = freshAccessToken() ?: return TokenCallResult.AuthFailed
        val first = block(token)
        if (first !is TokenCallResult.Unauthorized) return first
        // 401 → refresh once, retry once.
        val refreshed = refreshAccessToken(token) ?: return TokenCallResult.AuthFailed
        return block(refreshed)
    }

    private sealed class TokenCallResult {
        /** HTTP 2xx. */
        data class Ok(val code: Int, val body: String?) : TokenCallResult()
        /** HTTP 401 — caller should refresh and retry. */
        object Unauthorized : TokenCallResult()
        /** Any other HTTP status, or a network/IO failure. */
        data class Failed(val code: Int, val body: String?) : TokenCallResult()
        object AuthFailed : TokenCallResult()
    }

    /** Execute a request, classifying 401 for retry. */
    private fun execClassified(req: Request): TokenCallResult {
        return try {
            client.newCall(req).execute().use { resp ->
                val body = if (resp.code == 401) null else resp.body?.string()
                if (resp.code == 401) TokenCallResult.Unauthorized
                else if (resp.isSuccessful) TokenCallResult.Ok(resp.code, body)
                else TokenCallResult.Failed(resp.code, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error", e)
            TokenCallResult.Failed(-1, null)
        }
    }

    override suspend fun authenticate(): Boolean {
        // freshAccessToken() returns null if no token is configured at all.
        freshAccessToken() ?: run {
            Log.w(TAG, "No access token available")
            return false
        }

        // Verify token (with refresh+retry) by getting user info.
        return try {
            val result = withTokenRetry { token ->
                val req = Request.Builder()
                    .url("$API_URL/about?fields=user")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                execClassified(req)
            }
            when (result) {
                is TokenCallResult.Ok -> {
                    val json = JSONObject(result.body ?: "{}")
                    val email = json.optJSONObject("user")?.optString("emailAddress", "unknown")
                    Log.i(TAG, "Authenticated as: $email")
                    true
                }
                is TokenCallResult.Unauthorized -> {
                    Log.w(TAG, "Authentication failed: token expired and refresh did not recover")
                    false
                }
                is TokenCallResult.Failed -> {
                    Log.w(TAG, "Authentication failed: ${result.code}")
                    false
                }
                TokenCallResult.AuthFailed -> {
                    Log.w(TAG, "Authentication failed: no usable token")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            false
        }
    }

    override fun isAuthenticated(): Boolean {
        return !Prefs.cloudAccessToken(context).isNullOrEmpty()
    }

    override suspend fun uploadFile(file: File, remotePath: String): Boolean {
        if (!file.exists()) {
            Log.w(TAG, "File does not exist: ${file.path}")
            return false
        }

        freshAccessToken() ?: run {
            Log.w(TAG, "No access token for upload")
            return false
        }

        return try {
            // Parse path to get folder structure and filename
            val pathParts = remotePath.trim('/').split('/')
            val fileName = pathParts.last()
            val folderPath = pathParts.dropLast(1)

            // Get or create folder
            val folderId = if (folderPath.isEmpty()) {
                "root"
            } else {
                getOrCreateFolder(folderPath) ?: run {
                    Log.w(TAG, "Failed to create folder structure")
                    return false
                }
            }

            // Upload file using multipart upload
            uploadFileToFolder(file, fileName, folderId)

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${file.name}", e)
            false
        }
    }

    private fun uploadFileToFolder(file: File, fileName: String, folderId: String): Boolean {
        try {
            // Create metadata
            val metadata = JSONObject().apply {
                put("name", fileName)
                put("parents", JSONArray().put(folderId))
                put("mimeType", "video/mp4")
            }

            // Build multipart request
            val metadataBody = metadata.toString()
                .toRequestBody("application/json; charset=UTF-8".toMediaType())

            val fileBody = file.asRequestBody("video/mp4".toMediaType())

            val buildRequest = { token: String ->
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("metadata", null, metadataBody)
                    .addFormDataPart("file", file.name, fileBody)
                    .build()

                Request.Builder()
                    .url("$UPLOAD_URL/files?uploadType=multipart&fields=id,name,size")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            }

            val result = withTokenRetry { token -> execClassified(buildRequest(token)) }
            return when (result) {
                is TokenCallResult.Ok -> {
                    val json = JSONObject(result.body ?: "{}")
                    val fileId = json.optString("id", "unknown")
                    val uploadedName = json.optString("name", fileName)
                    val size = json.optLong("size", 0)
                    Log.i(TAG, "Uploaded: $uploadedName (ID: $fileId, $size bytes)")
                    true
                }
                is TokenCallResult.Unauthorized -> {
                    Log.w(TAG, "Upload failed: token expired and refresh did not recover")
                    false
                }
                is TokenCallResult.Failed -> {
                    Log.w(TAG, "Upload failed: ${result.code} - ${result.body}")
                    false
                }
                TokenCallResult.AuthFailed -> {
                    Log.w(TAG, "Upload failed: no usable token")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "File upload error", e)
            return false
        }
    }

    @Synchronized
    private fun getOrCreateFolder(pathParts: List<String>): String? {
        folderIds[pathParts]?.let { return it }
        var currentFolderId = "root"

        for (folderName in pathParts) {
            // Search for folder
            val existingId = findFolder(folderName, currentFolderId)
            currentFolderId = if (existingId != null) {
                existingId
            } else {
                // Create folder
                createFolderInParent(folderName, currentFolderId) ?: return null
            }
        }

        folderIds[pathParts.toList()] = currentFolderId
        return currentFolderId
    }

    /**
     * Escape a literal value for a Drive v3 `q` search term. Drive uses
     * backslash escaping inside single-quoted strings, so a folder name
     * containing an apostrophe (e.g. "Anna's Backups") would otherwise produce a
     * malformed query: findFolder would return null and every upload would
     * create a *duplicate* folder (scattering evidence), and a crafted name
     * could alter the query's meaning. Escape backslash first, then the quote.
     */
    private fun escapeDriveQueryValue(v: String): String =
        v.replace("\\", "\\\\").replace("'", "\\'")

    private fun findFolder(name: String, parentId: String): String? {
        try {
            val safeName = escapeDriveQueryValue(name)
            val query = "name='$safeName' and '$parentId' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

            val buildReq = { token: String ->
                Request.Builder()
                    .url("$API_URL/files?q=$encodedQuery&fields=files(id,name)&spaces=drive")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            }

            val result = withTokenRetry { token -> execClassified(buildReq(token)) }
            return when (result) {
                is TokenCallResult.Ok -> {
                    val json = JSONObject(result.body ?: "{}")
                    val files = json.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        val folderId = files.getJSONObject(0).optString("id")
                        Log.d(TAG, "Found existing folder: $name (ID: $folderId)")
                        folderId
                    } else null
                }
                is TokenCallResult.Unauthorized -> {
                    Log.w(TAG, "Find folder: token expired and refresh did not recover")
                    null
                }
                is TokenCallResult.Failed -> {
                    Log.w(TAG, "Find folder failed: ${result.code}")
                    null
                }
                TokenCallResult.AuthFailed -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Find folder error", e)
            return null
        }
    }

    private fun createFolderInParent(name: String, parentId: String): String? {
        try {
            val metadata = JSONObject().apply {
                put("name", name)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", JSONArray().put(parentId))
            }

            val buildReq = { token: String ->
                Request.Builder()
                    .url("$API_URL/files?fields=id,name")
                    .post(metadata.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            }

            val result = withTokenRetry { token -> execClassified(buildReq(token)) }
            return when (result) {
                is TokenCallResult.Ok -> {
                    val json = JSONObject(result.body ?: "{}")
                    val folderId = json.optString("id")
                    Log.d(TAG, "Created folder: $name (ID: $folderId)")
                    folderId.ifEmpty { null }
                }
                is TokenCallResult.Unauthorized -> {
                    Log.w(TAG, "Create folder: token expired and refresh did not recover")
                    null
                }
                is TokenCallResult.Failed -> {
                    Log.w(TAG, "Create folder failed: ${result.code}")
                    null
                }
                TokenCallResult.AuthFailed -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create folder error", e)
            return null
        }
    }

    override suspend fun createFolder(path: String): Boolean {
        freshAccessToken() ?: return false
        val pathParts = path.trim('/').split('/').filter { it.isNotEmpty() }
        return getOrCreateFolder(pathParts) != null
    }

    override fun getProviderName(): String = "Google Drive"

    override fun requiresOAuth(): Boolean = true
}
