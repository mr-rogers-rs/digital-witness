package com.fieldnotes.recorder.upload

/** Stable, untranslated on-disk layout, independent of export encryption. */
object EvidenceLayout {
    const val FOLDER = "Evidence"

    fun folderFor(name: String): String {
        val original = name.removeSuffix(".enc").removeSuffix(".jwe")
        return if (original.endsWith(".mp4", ignoreCase = true)) "" else FOLDER
    }

    fun validate(folder: String): String {
        require(folder == "" || folder == FOLDER) { "Invalid evidence folder" }
        return folder
    }

    fun path(root: String, folder: String): String =
        listOf(root.trim('/'), validate(folder)).filter { it.isNotEmpty() }.joinToString("/", prefix = "/").trimEnd('/')
}
