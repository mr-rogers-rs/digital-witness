package com.fieldnotes.recorder.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fieldnotes.recorder.util.Prefs
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manager for cloud storage uploads.
 * Handles provider selection, authentication, and file uploads.
 */
class CloudUploadManager(private val context: Context) {

    companion object {
        private const val TAG = "DW-CloudUpload"
    }

    private var currentProvider: CloudProvider? = null
    private val folderLock = Mutex()
    private val preparedFolders = HashSet<String>()
    private fun destinationRoot(): String = if (Prefs.cloudProvider(context) == "nextcloud")
        Prefs.ncFolder(context) else Prefs.cloudFolderPath(context)

    /**
     * Initialize the cloud provider based on user settings
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val providerType = Prefs.cloudProvider(context)

            currentProvider = when (providerType) {
                "dropbox" -> DropboxProvider(context)
                "google_drive" -> GoogleDriveProvider(context)
                "mega" -> MegaProvider(context)
                "webdav" -> WebDavProvider(context)
                "nextcloud" -> NextcloudProvider(context)
                else -> {
                    Log.w(TAG, "Unknown provider type")
                    return@withContext false
                }
            }

            Log.i(TAG, "Initialized provider: ${currentProvider?.getProviderName()}")

            // Authenticate with the provider
            val authenticated = currentProvider?.authenticate() ?: false

            if (!authenticated) {
                Log.w(TAG, "Failed to authenticate with ${currentProvider?.getProviderName()}")
                return@withContext false
            }

            // Ensure destination folder exists
            val folderPath = destinationRoot()
            val folderCreated = folderPath.trim('/').isEmpty() || currentProvider?.createFolder(folderPath) == true
            if (folderCreated) preparedFolders.add(folderPath)

            if (!folderCreated) {
                Log.w(TAG, "Failed to create/verify folder: $folderPath")
                return@withContext false
            }

            authenticated

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.fieldnotes.recorder.upload.UploadRetryLaterException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize cloud provider", e)
            false
        }
    }

    /**
     * Upload a file to cloud storage
     * @param file The local file to upload
     * @return true if upload succeeded
     *
     * Logging discipline (audit H4): the original filename encodes session +
     * timestamp and is exactly what the UUID obfuscation below is meant to
     * hide. We therefore NEVER log the original name or local path — only the
     * byte count and (for correlation) the obfuscated remote name, which is a
     * fresh random UUID carrying no session/timing information.
     */
    suspend fun uploadFile(file: File, subfolder: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            if (currentProvider == null) {
                Log.w(TAG, "No provider initialized, attempting initialization")
                if (!initialize()) {
                    return@withContext false
                }
            }

            if (!file.exists()) {
                // No path/name in the log — existence failure is enough.
                Log.w(TAG, "Upload target does not exist")
                return@withContext false
            }

            // Obfuscate filename — cloud provider sees a random UUID, not the
            // original session/timestamp-based name which leaks timing info.
            val obfuscatedName = file.name // queue names are random and stable across retries
            val folderPath = com.fieldnotes.recorder.upload.EvidenceLayout.path(destinationRoot(), subfolder)
            if (subfolder.isNotEmpty() && !folderLock.withLock {
                    com.fieldnotes.recorder.upload.DeliveryPolicy.checkActiveDestination(context)
                    if (folderPath in preparedFolders) true
                    else (currentProvider?.createFolder(folderPath) == true).also { if (it) preparedFolders.add(folderPath) }
                }) return@withContext false
            val remotePath = "$folderPath/$obfuscatedName"

            // Log only size + the UUID (UUID is random per upload; safe).
            Log.d(TAG, "Uploading ${file.length()} bytes as $obfuscatedName")

            val result = currentProvider?.uploadFile(file, remotePath) ?: false

            if (result) {
                Log.i(TAG, "Upload OK: $obfuscatedName (${file.length()} bytes)")
            } else {
                Log.w(TAG, "Upload FAILED: $obfuscatedName")
            }

            result

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.fieldnotes.recorder.upload.UploadRetryLaterException) {
            throw e
        } catch (e: Exception) {
            // Don't include file.name in the message — Exception.toString can
            // embed it in crash logs / logcat dumps.
            Log.e(TAG, "Upload error", e)
            false
        }
    }

    /**
     * Check if cloud provider is ready for uploads
     */
    fun isReady(): Boolean {
        return currentProvider?.isAuthenticated() ?: false
    }

    /**
     * Get the current provider name
     */
    fun getProviderName(): String {
        return currentProvider?.getProviderName() ?: "None"
    }

    /**
     * Test the cloud connection
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (currentProvider == null) {
                initialize()
            }
            currentProvider?.authenticate() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * Get configuration status for UI display
     */
    fun getConfigurationStatus(): ConfigurationStatus {
        val uploadMethod = Prefs.uploadMethod(context)
        val providerType = Prefs.cloudProvider(context)

        return when (uploadMethod) {
            "saf" -> ConfigurationStatus(
                method = "SAF",
                provider = "Storage Access Framework",
                configured = !Prefs.cloudFolderUri(context).isNullOrEmpty(),
                ready = !Prefs.cloudFolderUri(context).isNullOrEmpty()
            )
            "cloud" -> {
                val provider = when (providerType) {
                    "dropbox" -> "Dropbox"
                    "google_drive" -> "Google Drive"
                    "mega" -> "Mega"
                    "webdav" -> "WebDAV"
                    "nextcloud" -> "Nextcloud"
                    else -> "Unknown"
                }

                val configured = when (providerType) {
                    "dropbox" -> !Prefs.cloudAccessToken(context).isNullOrEmpty() ||
                        (!Prefs.cloudRefreshToken(context).isNullOrEmpty() && !Prefs.cloudAppKey(context).isNullOrEmpty())
                    "google_drive" -> !Prefs.cloudAccessToken(context).isNullOrEmpty()
                    "webdav" -> {
                        !Prefs.cloudWebdavUrl(context).isNullOrEmpty() &&
                        !Prefs.cloudUsername(context).isNullOrEmpty() &&
                        !Prefs.cloudPassword(context).isNullOrEmpty()
                    }
                    "mega" -> {
                        !Prefs.cloudUsername(context).isNullOrEmpty() &&
                        !Prefs.cloudPassword(context).isNullOrEmpty()
                    }
                    "nextcloud" -> {
                        !Prefs.ncBaseUrl(context).isNullOrEmpty() &&
                        !Prefs.ncUsername(context).isNullOrEmpty() &&
                        !Prefs.ncAppPassword(context).isNullOrEmpty()
                    }
                    else -> false
                }

                ConfigurationStatus(
                    method = "Cloud",
                    provider = provider,
                    configured = configured,
                    ready = configured && (currentProvider?.isAuthenticated() ?: false)
                )
            }
            else -> ConfigurationStatus(
                method = "Unknown",
                provider = "Not configured",
                configured = false,
                ready = false
            )
        }
    }
}

/**
 * Configuration status for display in UI
 */
data class ConfigurationStatus(
    val method: String,
    val provider: String,
    val configured: Boolean,
    val ready: Boolean
)
