package com.fieldnotes.recorder.cloud

import java.io.File
import java.security.MessageDigest

/** Dropbox content_hash: SHA256(concatenated SHA256 digests of 4 MiB blocks). */
object DropboxContentHash {
    fun of(file: File): String {
        val result = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(4 * 1024 * 1024)
            while (true) {
                var count = 0
                while (count < block.size) {
                    val n = input.read(block, count, block.size-count)
                    if (n < 0) break
                    count += n
                }
                if (count == 0) break
                val hash = MessageDigest.getInstance("SHA-256")
                hash.update(block, 0, count)
                result.update(hash.digest())
                if (count < block.size) break
            }
        }
        return result.digest().joinToString("") { "%02x".format(it) }
    }
}
