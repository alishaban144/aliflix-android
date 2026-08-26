package com.aliflix.app.data

import android.content.Context
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PersistedHomeSnapshot(
    val content: HomeContent,
    val editorialPicks: List<Media> = emptyList(),
    val savedAt: Long = System.currentTimeMillis(),
)

interface HomeSnapshotStore {
    suspend fun loadSnapshot(): PersistedHomeSnapshot?
    suspend fun saveSnapshot(snapshot: PersistedHomeSnapshot)
    suspend fun clearSnapshot()
}

class AndroidHomeSnapshotStore internal constructor(
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileReader: (File) -> String = File::readText,
    private val fileWriter: ((File, String) -> Unit)? = null,
) : HomeSnapshotStore {

    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        cacheDir = File(context.filesDir, "home-cache"),
        ioDispatcher = ioDispatcher,
        computationDispatcher = computationDispatcher,
    )

    private val snapshotFile = File(cacheDir, "home-snapshot-v1.json")
    private val mutex = Mutex()

    override suspend fun loadSnapshot(): PersistedHomeSnapshot? = mutex.withLock {
        try {
            if (!withContext(ioDispatcher) { snapshotFile.exists() }) {
                return null
            }
            val raw = withContext(ioDispatcher) { fileReader(snapshotFile) }
            if (raw.isBlank()) return null

            withContext(computationDispatcher) {
                val json = JSONObject(raw)
                val heroJson = json.optJSONObject("hero") ?: return@withContext null
                val hero = Media.fromJson(heroJson)

                val railsJson = json.optJSONArray("rails") ?: JSONArray()
                val rails = (0 until railsJson.length()).mapNotNull { railIdx ->
                    val railObj = railsJson.optJSONObject(railIdx) ?: return@mapNotNull null
                    val title = railObj.optString("title", "").trim()
                    if (title.isBlank()) return@mapNotNull null

                    val itemsJson = railObj.optJSONArray("items") ?: JSONArray()
                    val items = (0 until itemsJson.length()).mapNotNull { itemIdx ->
                        itemsJson.optJSONObject(itemIdx)?.let(Media::fromJson)
                    }
                    if (items.isEmpty()) null else ContentRail(title = title, items = items)
                }

                val editorialJson = json.optJSONArray("editorialPicks") ?: JSONArray()
                val editorialPicks = (0 until editorialJson.length()).mapNotNull { itemIdx ->
                    editorialJson.optJSONObject(itemIdx)?.let(Media::fromJson)
                }

                val savedAt = json.optLong("savedAt", System.currentTimeMillis())

                PersistedHomeSnapshot(
                    content = HomeContent(hero = hero, rails = rails),
                    editorialPicks = editorialPicks,
                    savedAt = savedAt,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun saveSnapshot(snapshot: PersistedHomeSnapshot) = mutex.withLock {
        try {
            val jsonString = withContext(computationDispatcher) {
                JSONObject().apply {
                    put("savedAt", snapshot.savedAt)
                    put("hero", snapshot.content.hero.toJson())
                    put(
                        "rails",
                        JSONArray().apply {
                            snapshot.content.rails.forEach { rail ->
                                put(
                                    JSONObject().apply {
                                        put("title", rail.title)
                                        put(
                                            "items",
                                            JSONArray().apply {
                                                rail.items.forEach { put(it.toJson()) }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "editorialPicks",
                        JSONArray().apply {
                            snapshot.editorialPicks.forEach { put(it.toJson()) }
                        },
                    )
                }.toString()
            }

            withContext(ioDispatcher) {
                writeAtomically(snapshotFile, jsonString)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Non-fatal cache failure
        }
    }

    private fun writeAtomically(target: File, value: String) {
        if (fileWriter != null) {
            fileWriter.invoke(target, value)
            return
        }
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val temp = File.createTempFile("home-snap", ".tmp", cacheDir)
        temp.writeText(value, StandardCharsets.UTF_8)
        if (!temp.renameTo(target)) {
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                target.writeText(value, StandardCharsets.UTF_8)
                temp.delete()
            }
        }
    }

    override suspend fun clearSnapshot() = mutex.withLock {
        withContext(ioDispatcher) {
            if (snapshotFile.exists()) {
                snapshotFile.delete()
            }
        }
    }
}
