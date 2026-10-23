package com.fieldnotes.recorder.cloud

import java.io.File

/**
 * Interface for cloud storage providers.
 * Implementations handle authentication and file uploads for specific services.
 */
interface CloudProvider {
    /**
     * Authenticate with the cloud service.
     * @return true if authentication succeeded, false otherwise
     */
    suspend fun authenticate(): Boolean
    
    /**
     * Check if currently authenticated
     */
    fun isAuthenticated(): Boolean
    
    /**
     * Upload a file to the cloud storage
     * @param file The local file to upload
     * @param remotePath The path in cloud storage (e.g., "/Backups/file.mp4")
     * @return true if upload succeeded, false otherwise
     */
    suspend fun uploadFile(file: File, remotePath: String): Boolean
    
    /**
     * Create a folder in cloud storage if it doesn't exist
     * @param path The folder path to create
     * @return true if folder exists or was created successfully
     */
    suspend fun createFolder(path: String): Boolean
    
    /**
     * Get the provider name for display
     */
    fun getProviderName(): String
    
    /**
     * Check if this provider requires OAuth (vs username/password)
     */
    fun requiresOAuth(): Boolean
}

/**
 * Result of an upload operation
 */
data class UploadResult(
    val success: Boolean,
    val message: String? = null,
    val remotePath: String? = null
)

/**
 * Exception thrown when cloud operations fail
 */
class CloudProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

