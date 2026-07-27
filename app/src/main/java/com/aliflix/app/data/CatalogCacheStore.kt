package com.aliflix.app.data

import android.content.Context
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface CatalogCacheStore {
    suspend fun loadHome(): HomeContent?
    suspend fun saveHome(content: HomeContent)
    suspend fun loadPlot(queryKey: String, maxAgeMs: Long): List<Media>?
    suspend fun savePlot(queryKey: String, items: List<Media>)
}

class AndroidCatalogCacheStore(context: Context) : CatalogCacheStore {
    private val cacheDir = File(context.filesDir, "catalog-cache")
    private val homeFile = File(cacheDir, "home-v4.json")
    private val plotFile = File(cacheDir, "plot-v2.json")
    private val mutex = Mutex()

    override suspend fun loadHome(): HomeContent? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(homeFile.readText())
                val hero = Media.fromJson(json.getJSONObject("hero"))
                val railsJson = json.getJSONArray("rails")
                val rails = (0 until railsJson.length()).mapNotNull { index ->
                    val railJson = railsJson.optJSONObject(index) ?: return@mapNotNull null
                    val itemsJson = railJson.optJSONArray("items") ?: JSONArray()
                    val items = (0 until itemsJson.length()).mapNotNull { itemIndex ->
                        itemsJson.optJSONObject(itemIndex)?.let(Media::fromJson)
                    }
                    val title = railJson.optString("title").trim()
                    if (title.isBlank() || items.isEmpty()) null else ContentRail(title, items)
                }
                HomeContent(hero, rails)
            }.getOrNull()
        }
    }

    override suspend fun saveHome(content: HomeContent) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("savedAt", System.currentTimeMillis())
                put("hero", content.hero.toJson())
                put(
                    "rails",
                    JSONArray().apply {
                        content.rails.forEach { rail ->
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
            }
            writeAtomically(homeFile, json.toString())
        }
    }

    override suspend fun loadPlot(
        queryKey: String,
        maxAgeMs: Long,
    ): List<Media>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = JSONObject(plotFile.readText()).optJSONArray("entries")
                    ?: return@runCatching null
                (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("query") == queryKey }
                    ?.takeIf {
                        System.currentTimeMillis() - it.optLong("savedAt") <= maxAgeMs
                    }
                    ?.optJSONArray("items")
                    ?.let { items ->
                        (0 until items.length()).mapNotNull { index ->
                            items.optJSONObject(index)?.let(Media::fromJson)
                        }
                    }
            }.getOrNull()
        }
    }

    override suspend fun savePlot(queryKey: String, items: List<Media>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = runCatching {
                JSONObject(plotFile.readText()).optJSONArray("entries")
            }.getOrNull()
            val entries = buildList {
                if (previous != null) {
                    (0 until previous.length())
                        .mapNotNull(previous::optJSONObject)
                        .filterNot { it.optString("query") == queryKey }
                        .take(MAX_PLOT_CACHE_ENTRIES - 1)
                        .forEach(::add)
                }
                add(
                    0,
                    JSONObject().apply {
                        put("query", queryKey)
                        put("savedAt", System.currentTimeMillis())
                        put(
                            "items",
                            JSONArray().apply { items.forEach { put(it.toJson()) } },
                        )
                    },
                )
            }
            val payload = JSONObject().put("entries", JSONArray(entries)).toString()
            writeAtomically(plotFile, payload)
        }
    }

    private fun writeAtomically(target: File, value: String) {
        cacheDir.mkdirs()
        val temporary = File(cacheDir, "${target.name}.tmp")
        temporary.writeText(value)
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            target.writeText(value)
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_PLOT_CACHE_ENTRIES = 24
    }
}
