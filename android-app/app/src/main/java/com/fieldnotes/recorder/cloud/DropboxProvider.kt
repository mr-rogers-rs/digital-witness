package com.fieldnotes.recorder.cloud

import android.content.Context
import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fieldnotes.recorder.network.PinnedHttpClient
import com.fieldnotes.recorder.util.Prefs
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dropbox cloud storage provider using Dropbox HTTP API v2.
 * Supports both OAuth access tokens and short-lived tokens.
 *
 * For production use, implement proper OAuth flow in the UI.
 * For testing, you can generate an access token from:
 * https://www.dropbox.com/developers/apps -> Your App -> Generate Access Token
 */
class DropboxProvider(private val context: Context) : CloudProvider {

    companion object {
        private const val TAG = "DW-Dropbox"
        private const val API_URL = "https://api.dropboxapi.com/2"
        private const val CONTENT_URL = "https://content.dropboxapi.com/2"
        private const val OAUTH_TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
        private const val CHUNK_SIZE = 4 * 1024 * 1024 // 4MB chunks for large files
    }

    /**
     * Refresh the short-lived access token using the stored refresh token + app key.
     * Returns the new access token or null on failure.
     * See: https://developers.dropbox.com/oauth-guide#refresh-tokens
     */
    private fun refreshAccessToken(): String? {
        val refreshToken = Prefs.cloudRefreshToken(context)
        val appKey = Prefs.cloudAppKey(context)
        if (refreshToken.isNullOrEmpty() || appKey.isNullOrEmpty()) {
            Log.w(TAG, "Cannot refresh: missing refresh token or app key")
            return null
        }
        return try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", appKey)
                .build()
            val req = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Refresh failed")
                resp.close()
                return null
            }
            resp.close()
            val json = JSONObject(respBody ?: "{}")
            val newToken = json.optString("access_token", "")
            if (newToken.isEmpty()) {
                Log.w(TAG, "Refresh response missing access_token")
                return null
            }
            Prefs.setCloudAccessTokenFor(context, "dropbox", newToken)
            Log.i(TAG, "Access token refreshed successfully")
            newToken
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            null
        }
    }

    /**
     * Execute a request with automatic token refresh on 401.
     * The requestBuilder is called to (re)build the request with the provided token.
     * Returns the Response (caller must close it). The response body is NOT consumed here.
     */
    private fun executeWithRefresh(
        requestBuilder: (token: String) -> Request
    ): okhttp3.Response? {
        var token = Prefs.cloudAccessToken(context)
        if (token.isNullOrEmpty()) {
            // No access token at all — try refreshing immediately
            token = refreshAccessToken() ?: return null
        }
        val firstResp = client.newCall(requestBuilder(token)).execute()
        if (firstResp.code != 401) return firstResp
        // 401 — try refreshing once and retry
        Log.i(TAG, "Got 401, attempting token refresh")
        firstResp.close()
        val newToken = refreshAccessToken() ?: return null
        return client.newCall(requestBuilder(newToken)).execute()
    }

    private val client = PinnedHttpClient.pinnedBuilder(context)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun authenticate(): Boolean {
        if (Prefs.cloudAccessToken(context).isNullOrEmpty() &&
            (Prefs.cloudRefreshToken(context).isNullOrEmpty() || Prefs.cloudAppKey(context).isNullOrEmpty())) {
            Log.w(TAG, "No access token and no refresh credentials available")
            return false
        }

        // Verify token by making a simple API call (with auto-refresh on 401)
        try {
            val response = executeWithRefresh { token ->
                Request.Builder()
                    .url("$API_URL/users/get_current_account")
                    .post("null".toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } ?: return false

            val success = response.isSuccessful
            if (success) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val email = json.optJSONObject("email")?.optString("email") ?: "unknown"
                Log.i(TAG, "Authenticated as: $email")
            } else {
                Log.w(TAG, "Authentication failed: ${response.code} ${response.message}")
            }
            response.close()
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            return false
        }
    }

    override fun isAuthenticated(): Boolean {
        // We are considered "authenticated" if we have either a current access token
        // or the refresh credentials needed to mint one.
        if (!Prefs.cloudAccessToken(context).isNullOrEmpty()) return true
        return !Prefs.cloudRefreshToken(context).isNullOrEmpty() &&
               !Prefs.cloudAppKey(context).isNullOrEmpty()
    }

    override suspend fun uploadFile(file: File, remotePath: String): Boolean {
        if (!file.exists()) {
            Log.w(TAG, "File does not exist: ${file.path}")
            return false
        }

        if (Prefs.cloudAccessToken(context).isNullOrEmpty() &&
            (Prefs.cloudRefreshToken(context).isNullOrEmpty() || Prefs.cloudAppKey(context).isNullOrEmpty())) {
            Log.w(TAG, "No access token and no refresh credentials for upload")
            return false
        }

        return try {
            // Normalize path - remove /Apps/ prefix if present (it's automatic for app folder access)
            val cleanPath = remotePath.removePrefix("/Apps")
            val normalizedPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"

            Log.d(TAG, "Uploading file: ${file.name} to $normalizedPath (${file.length()} bytes)")

            // For small files, use simple upload. For large files, use upload session
            if (file.length() < CHUNK_SIZE) {
                uploadSmallFile(file, normalizedPath)
            } else {
                // Pre-refresh the token if we can, so it stays valid for the whole chunked session.
                if (!Prefs.cloudRefreshToken(context).isNullOrEmpty() &&
                    !Prefs.cloudAppKey(context).isNullOrEmpty()) {
                    refreshAccessToken()
                }
                val accessToken = Prefs.cloudAccessToken(context) ?: return false
                uploadLargeFile(file, normalizedPath, accessToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${file.name}", e)
            false
        }
    }

    private fun matchesReceipt(response: okhttp3.Response, file: File): Boolean { return response.use {
        if (!it.isSuccessful) return false
        val source = it.body?.source() ?: return false
        source.request(65537)
        if (source.buffer.size > 65536) return false
        val metadata = JSONObject(source.buffer.readUtf8())
        metadata.optLong("size", -1) == file.length() &&
            metadata.optString("content_hash") == DropboxContentHash.of(file)
    } }

    private fun uploadSmallFile(file: File, remotePath: String): Boolean {
        try {
            val fileBytes = file.readBytes()

            // Dropbox API parameter format. Every upload here targets a fresh
            // random-UUID filename (assigned by CloudUploadManager), so a name
            // collision is effectively impossible — autorename=false (with
            // overwrite) avoids Dropbox appending " (1)" / " (2)" suffixes,
            // which would leak that a prior upload of this UUID occurred and
            // when (audit H4).
            val apiArg = JSONObject().apply {
                put("path", remotePath)
                put("mode", "overwrite")
                put("autorename", false)
                put("mute", true) // mute = no Dropbox notifications = less metadata
            }

            val response = executeWithRefresh { token ->
                Request.Builder()
                    .url("$CONTENT_URL/files/upload")
                    .post(fileBytes.toRequestBody("application/octet-stream".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Dropbox-API-Arg", apiArg.toString())
                    .addHeader("Content-Type", "application/octet-stream")
                    .build()
            } ?: return false

            return matchesReceipt(response, file)


        } catch (e: Exception) {
            Log.e(TAG, "Small file upload error", e)
            return false
        }
    }

    private fun uploadLargeFile(file: File, remotePath: String, accessToken: String): Boolean {
        try {
            Log.d(TAG, "Large file upload: ${file.name} (${file.length()} bytes)")

            var offset = 0L
            var sessionId: String? = null

            file.inputStream().use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                var isFirstChunk = true

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = if (bytesRead < CHUNK_SIZE) buffer.copyOf(bytesRead) else buffer

                    if (isFirstChunk) {
                        // Step 1: Start upload session with first chunk
                        val startArg = JSONObject().apply {
                            put("close", false)
                        }

                        val startRequest = Request.Builder()
                            .url("$CONTENT_URL/files/upload_session/start")
                            .post(chunk.toRequestBody("application/octet-stream".toMediaType()))
                            .addHeader("Authorization", "Bearer $accessToken")
                            .addHeader("Dropbox-API-Arg", startArg.toString())
                            .addHeader("Content-Type", "application/octet-stream")
                            .build()

                        val startResponse = client.newCall(startRequest).execute()
                        val startBody = startResponse.body?.string()

                        if (!startResponse.isSuccessful) {
                            Log.w(TAG, "Failed to start upload session: ${startResponse.code}")
                            Log.w(TAG, "Response: $startBody")
                            startResponse.close()
                            return false
                        }

                        sessionId = JSONObject(startBody ?: "{}").optString("session_id")
                        startResponse.close()

                        if (sessionId.isNullOrEmpty()) {
                            Log.w(TAG, "No session ID received")
                            return false
                        }

                        offset += bytesRead
                        isFirstChunk = false
                        Log.d(TAG, "Started session $sessionId: $offset/${file.length()} bytes")

                    } else {
                        // Step 2: Append subsequent chunks
                        val appendArg = JSONObject().apply {
                            put("cursor", JSONObject().apply {
                                put("session_id", sessionId)
                                put("offset", offset)
                            })
                        }

                        val appendRequest = Request.Builder()
                            .url("$CONTENT_URL/files/upload_session/append_v2")
                            .post(chunk.toRequestBody("application/octet-stream".toMediaType()))
                            .addHeader("Authorization", "Bearer $accessToken")
                            .addHeader("Dropbox-API-Arg", appendArg.toString())
                            .addHeader("Content-Type", "application/octet-stream")
                            .build()

                        val appendResponse = client.newCall(appendRequest).execute()

                        if (!appendResponse.isSuccessful) {
                            val errorBody = appendResponse.body?.string()
                            Log.w(TAG, "Failed to append chunk at offset $offset: ${appendResponse.code}")
                            Log.w(TAG, "Upload request rejected")
                            appendResponse.close()
                            return false
                        }
                        appendResponse.close()

                        offset += bytesRead
                        Log.d(TAG, "Uploaded chunk: $offset/${file.length()} bytes")
                    }
                }
            }

            if (sessionId == null) {
                Log.w(TAG, "No session ID - file might be empty")
                return false
            }

            // Step 3: Finish upload session
            val finishArg = JSONObject().apply {
                put("cursor", JSONObject().apply {
                    put("session_id", sessionId)
                    put("offset", offset)
                })
                put("commit", JSONObject().apply {
                    put("path", remotePath)
                    put("mode", "overwrite")
                    put("autorename", false) // see uploadSmallFile: UUID names never collide
                    put("mute", true)
                })
            }

            val finishRequest = Request.Builder()
                .url("$CONTENT_URL/files/upload_session/finish")
                .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Dropbox-API-Arg", finishArg.toString())
                .addHeader("Content-Type", "application/octet-stream")
                .build()

            val finishResponse = client.newCall(finishRequest).execute()
            return matchesReceipt(finishResponse, file)


        } catch (e: Exception) {
            Log.e(TAG, "Large file upload error", e)
            return false
        }
    }

    override suspend fun createFolder(path: String): Boolean {
        try {
            // Normalize path - remove /Apps/ prefix if present (it's automatic for app folder access)
            val cleanPath = path.removePrefix("/Apps")
            val normalizedPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"

            val json = JSONObject().apply {
                put("path", normalizedPath)
                put("autorename", false)
            }

            val response = executeWithRefresh { token ->
                Request.Builder()
                    .url("$API_URL/files/create_folder_v2")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()
            } ?: return false

            // 200/201 = created. 409 = already exists (idempotent success).
            // The previous code ALSO treated 400 as success ("app folder
            // compatibility"), which masked real errors — a 400 here means the
            // request was malformed or the path was invalid, and pretending it
            // succeeded would let uploads proceed against a missing folder and
            // then fail confusingly (audit H13).
            val success = response.isSuccessful || response.code == 409

            if (success) {
                Log.i(TAG, "Folder ready: $normalizedPath")
            } else {
                val errorBody = response.body?.string()
                Log.w(TAG, "Create folder failed: ${response.code} ${response.message}")
                Log.w(TAG, "Error details: $errorBody")
            }

            response.close()
            return success

        } catch (e: Exception) {
            Log.e(TAG, "Create folder error", e)
            return false
        }
    }

    override fun getProviderName(): String = "Dropbox"

    override fun requiresOAuth(): Boolean = true
}
