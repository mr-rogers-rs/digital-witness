package com.fieldnotes.recorder.cloud

import android.content.Context
import android.util.Log
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fieldnotes.recorder.network.PinnedHttpClient
import com.fieldnotes.recorder.util.Prefs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WebDAV cloud storage provider.
 * Compatible with:
 * - Nextcloud
 * - ownCloud
 * - Box.com
 * - Many other WebDAV-compatible services
 * 
 * Configuration:
 * - WebDAV URL: e.g., "https://nextcloud.example.com/remote.php/dav/files/username/"
 * - Username: Your account username
 * - Password: Your account password or app-specific password
 */
class WebDavProvider(private val context: Context) : CloudProvider {
    
    companion object {
        private const val TAG = "DW-WebDAV"
    }
    
    // WebDAV targets are user-specified — cannot pin at compile time.
    // Relies on system trust store + network_security_config (HTTPS only).
    // Still routes through Tor when enabled.
    private val client = PinnedHttpClient.unpinnedBuilder(context)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    override suspend fun authenticate(): Boolean {
        val webdavUrl = Prefs.cloudWebdavUrl(context)
        val username = Prefs.cloudUsername(context)
        val password = Prefs.cloudPassword(context)
        
        if (webdavUrl.isNullOrEmpty() || username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Log.w(TAG, "Missing WebDAV credentials")
            return false
        }
        
        return try {
            // Test connection with a PROPFIND request to the base URL
            val request = Request.Builder()
                .url(webdavUrl)
                .method("PROPFIND", ByteArray(0).toRequestBody())
                .addHeader("Authorization", Credentials.basic(username, password))
                .addHeader("Depth", "0")
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful || response.code == 207 // 207 Multi-Status is valid for PROPFIND
            
            if (success) {
                Log.i(TAG, "WebDAV authenticated: $webdavUrl")
            } else {
                Log.w(TAG, "WebDAV authentication failed: ${response.code} ${response.message}")
            }
            
            response.close()
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV authentication error", e)
            false
        }
    }
    
    override fun isAuthenticated(): Boolean {
        val webdavUrl = Prefs.cloudWebdavUrl(context)
        val username = Prefs.cloudUsername(context)
        val password = Prefs.cloudPassword(context)
        return !webdavUrl.isNullOrEmpty() && !username.isNullOrEmpty() && !password.isNullOrEmpty()
    }
    
    override suspend fun uploadFile(file: File, remotePath: String): Boolean {
        if (!file.exists()) {
            Log.w(TAG, "File does not exist: ${file.path}")
            return false
        }
        
        val webdavUrl = Prefs.cloudWebdavUrl(context) ?: return false
        val username = Prefs.cloudUsername(context) ?: return false
        val password = Prefs.cloudPassword(context) ?: return false
        
        return try {
            // Construct full URL (ensure no double slashes)
            val normalizedPath = remotePath.removePrefix("/")
            val baseUrl = webdavUrl.removeSuffix("/")
            val fullUrl = "$baseUrl/$normalizedPath"
            
            Log.d(TAG, "Uploading to WebDAV: $fullUrl (${file.length()} bytes)")

            // Stream the file from disk instead of loading it fully into RAM —
            // multi-MB→GB segments would otherwise risk OutOfMemoryError.
            val request = Request.Builder()
                .url(fullUrl)
                .put(FileRequestBody(file))
                .addHeader("Authorization", Credentials.basic(username, password))
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful || response.code == 201 || response.code == 204
            
            if (success) {
                Log.i(TAG, "Uploaded: ${file.name} -> $fullUrl")
            } else {
                val errorBody = response.body?.string()
                Log.w(TAG, "Upload failed: ${response.code} ${response.message} - $errorBody")
            }
            
            response.close()
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV upload error", e)
            false
        }
    }
    
    override suspend fun createFolder(path: String): Boolean {
        val webdavUrl = Prefs.cloudWebdavUrl(context) ?: return false
        val username = Prefs.cloudUsername(context) ?: return false
        val password = Prefs.cloudPassword(context) ?: return false
        
        return try {
            // Construct full URL
            val normalizedPath = path.removePrefix("/")
            val baseUrl = webdavUrl.removeSuffix("/")
            val fullUrl = "$baseUrl/$normalizedPath"
            
            val request = Request.Builder()
                .url(fullUrl)
                .method("MKCOL", ByteArray(0).toRequestBody())
                .addHeader("Authorization", Credentials.basic(username, password))
                .build()
            
            val response = client.newCall(request).execute()
            // 201 = created, 405 = already exists (method not allowed on existing resource)
            val success = response.isSuccessful || response.code == 201 || response.code == 405
            
            if (success) {
                Log.d(TAG, "Folder ready: $fullUrl")
            } else {
                Log.w(TAG, "Create folder failed: ${response.code} ${response.message}")
            }
            
            response.close()
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Create folder error", e)
            // Do NOT mask as success — a network failure or auth error here
            // means the folder may not exist, and the subsequent PUT would
            // then fail confusingly. Surface the failure (audit H13).
            false
        }
    }

    override fun getProviderName(): String = "WebDAV"

    override fun requiresOAuth(): Boolean = false
}

