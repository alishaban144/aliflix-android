package com.aliflix.app.data.omdb

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

private const val VERIFIED_TTL_MS = 7L * 24 * 3600 * 1000 // 7 days
private const val NOT_FOUND_TTL_MS = 6L * 3600 * 1000    // 6 hours

data class CachedOmdbEntry(
    val key: String,
    val status: String, // "VERIFIED" | "NOT_FOUND"
    val metadata: OmdbTitleMetadata?,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean {
        val age = now - timestampMillis
        if (status == "NOT_FOUND") return age <= NOT_FOUND_TTL_MS
        if (status == "VERIFIED") return age <= VERIFIED_TTL_MS
        return false
    }
}

class OmdbCacheStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val cacheMap = mutableMapOf<String, CachedOmdbEntry>()
    private var loaded = false
    private val cacheFile: File
        get() = File(context.cacheDir, "omdb-metadata-v1.json")

    private fun normalizeKey(key: String): String =
        key.lowercase().trim().replace(Regex("[^a-z0-9:]+"), "-")

    suspend fun get(key: String): CachedOmdbEntry? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoaded()
            val norm = normalizeKey(key)
            val entry = cacheMap[norm] ?: return@withLock null
            if (entry.isFresh()) {
                OmdbDiagnostics.omdbAndroidCacheHits.incrementAndGet()
                entry
            } else {
                cacheMap.remove(norm)
                null
            }
        }
    }

    suspend fun put(key: String, status: String, metadata: OmdbTitleMetadata?) = withContext(ioDispatcher) {
        if (status != "VERIFIED" && status != "NOT_FOUND") return@withContext
        mutex.withLock {
            ensureLoaded()
            val norm = normalizeKey(key)
            val entry = CachedOmdbEntry(norm, status, metadata)
            cacheMap[norm] = entry
            saveToFile()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = cacheFile
        if (!file.exists()) return
        try {
            val atomicFile = AtomicFile(file)
            val text = atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (text.isBlank()) return
            val json = JSONObject(text)
            val now = System.currentTimeMillis()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = json.getJSONObject(k)
                val status = obj.optString("status", "NOT_FOUND")
                val timestamp = obj.optLong("timestampMillis", 0L)
                val metaObj = obj.optJSONObject("metadata")
                val meta = metaObj?.let { OmdbTitleMetadata.fromJson(it) }
                val entry = CachedOmdbEntry(k, status, meta, timestamp)
                if (entry.isFresh(now)) {
                    cacheMap[k] = entry
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Ignore corrupted cache file
        }
    }

    private fun saveToFile() {
        val file = cacheFile
        try {
            val root = JSONObject()
            cacheMap.forEach { (k, v) ->
                root.put(k, JSONObject().apply {
                    put("status", v.status)
                    put("timestampMillis", v.timestampMillis)
                    v.metadata?.let { put("metadata", it.toJson()) }
                })
            }
            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                stream.write(root.toString().toByteArray(StandardCharsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (e: Throwable) {
                atomicFile.failWrite(stream)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Ignore cache write failure
        }
    }
}
