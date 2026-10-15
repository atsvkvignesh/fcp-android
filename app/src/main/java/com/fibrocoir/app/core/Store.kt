package com.fibrocoir.app.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Saved copies of what each screen last showed, kept on the phone, so every screen opens with
 * data straight away and then refreshes. One small JSON file per key; also kept in memory.
 */
class Store(private val dir: File) {

    data class Saved<T>(val at: Long, val value: T)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mem = ConcurrentHashMap<String, Pair<Long, Any?>>()

    init { dir.mkdirs() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, serializer: KSerializer<T>): Saved<T>? {
        mem[key]?.let { return Saved(it.first, it.second as T) }
        val f = file(key)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            val at = text.substringBefore('\n').toLong()
            val value = json.decodeFromString(serializer, text.substringAfter('\n'))
            mem[key] = at to value
            Saved(at, value)
        } catch (_: Exception) {
            f.delete()          // written by an older version or broken: just forget it
            null
        }
    }

    fun <T> put(key: String, serializer: KSerializer<T>, value: T, at: Long = System.currentTimeMillis()) {
        mem[key] = at to value
        try {
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(at.toString() + "\n" + json.encodeToString(serializer, value))
            tmp.renameTo(f)
        } catch (_: Exception) { /* storage full: the memory copy still works */ }
    }

    /** Forget saved copies whose key starts with `prefix` (all of them if empty). */
    fun forget(prefix: String = "") {
        mem.keys.filter { it.startsWith(prefix) }.forEach { mem.remove(it) }
        val safe = prefix.replace(Regex("[^A-Za-z0-9._-]"), "_")
        dir.listFiles()?.filter { it.name.startsWith(safe) }?.forEach { it.delete() }
    }
}
