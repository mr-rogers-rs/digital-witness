package com.fieldnotes.recorder.secure

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A [SharedPreferences] implementation backed by a single [SecureFile]-encrypted
 * JSON blob. Replaces `EncryptedSharedPreferences` from the unmaintained
 * security-crypto alpha. The whole blob (keys and values) is encrypted at rest
 * with a Keystore-wrapped per-file key, so nothing is readable from disk without
 * the device's non-exportable wrapping key.
 *
 * Writes are synchronous (both `apply()` and `commit()`): the prefs blob is tiny,
 * so there is no benefit to a background writer, and synchronous writes avoid the
 * classic lost-update races of an async queue.
 */
class SecurePrefs private constructor(
    private val file: File
) : SharedPreferences {

    private val lock = Any()
    private var data: JSONObject = load()
    private val atomicFile get() = android.util.AtomicFile(file)

    private fun load(): JSONObject = synchronized(lock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) return JSONObject()
        return JSONObject(String(SecureFile.decryptBytes(atomicFile.readFully()), Charsets.UTF_8))
    }

    private fun persist(next: JSONObject) {
        val encrypted = SecureFile.encryptBytes(next.toString().toByteArray(Charsets.UTF_8))
        val out = atomicFile.startWrite()
        try {
            out.write(encrypted)
            out.fd.sync()
            atomicFile.finishWrite(out)
        } catch (e: Exception) {
            atomicFile.failWrite(out)
            throw e
        }
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        if (data.has(key) && !data.isNull(key)) data.optString(key, defValue ?: "") else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        if (!data.has(key) || data.isNull(key)) return defValues
        val arr = data.optJSONArray(key) ?: return defValues
        val set = LinkedHashSet<String>()
        for (i in 0 until arr.length()) set.add(arr.optString(i))
        set
    }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) {
        if (data.has(key)) data.optInt(key, defValue) else defValue
    }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) {
        if (data.has(key)) data.optLong(key, defValue) else defValue
    }

    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) {
        if (data.has(key)) data.optDouble(key, defValue.toDouble()).toFloat() else defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) {
        if (data.has(key)) data.optBoolean(key, defValue) else defValue
    }

    override fun contains(key: String): Boolean = synchronized(lock) { data.has(key) }

    override fun getAll(): MutableMap<String, Any?> = synchronized(lock) {
        val map = HashMap<String, Any?>()
        for (k in data.keys()) map[k] = data.opt(k)
        map
    }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    // Listener support is unused by this app; provide no-op registration.
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { puts[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { puts[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { puts[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { removes.add(key) }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            writeThrough()
            return true
        }

        override fun apply() { writeThrough() }

        private fun writeThrough() = synchronized(lock) {
            val next = if (clearAll) JSONObject() else JSONObject(data.toString())
            for (k in removes) next.remove(k)
            for ((k, v) in puts) {
                when (v) {
                    null -> next.remove(k)
                    is Set<*> -> next.put(k, JSONArray(v.toList()))
                    else -> next.put(k, v)
                }
            }
            persist(next)
            data = next
        }
    }

    companion object {
        /**
         * Open (or create) an encrypted prefs store named [name] in the app's
         * files dir. The backing file is `<name>.spf`.
         */
        fun create(ctx: Context, name: String): SecurePrefs =
            SecurePrefs(File(ctx.filesDir, "$name.spf"))
    }
}
