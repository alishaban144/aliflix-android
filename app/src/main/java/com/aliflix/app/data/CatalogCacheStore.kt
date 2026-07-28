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
    suspend fun loadRecommendations(
        queryKey: String,
        maxAgeMs: Long,
    ): List<RecommendationDiscoveryItem>? = null
    suspend fun saveRecommendations(
        queryKey: String,
        items: List<RecommendationDiscoveryItem>,
    ) = Unit
    suspend fun loadVerifiedMetadata(
        mediaKey: String,
        maxAgeMs: Long,
    ): VerifiedRecommendationItem? = null
    suspend fun saveVerifiedMetadata(item: VerifiedRecommendationItem) = Unit
}

class AndroidCatalogCacheStore(context: Context) : CatalogCacheStore {
    private val cacheDir = File(context.filesDir, "catalog-cache")
    private val homeFile = File(cacheDir, "home-v4.json")
    private val plotFile = File(cacheDir, "plot-v2.json")
    private val recommendationFile = File(cacheDir, "recommendations-v1.json")
    private val metadataFile = File(cacheDir, "recommendation-metadata-v1.json")
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

    override suspend fun loadRecommendations(
        queryKey: String,
        maxAgeMs: Long,
    ): List<RecommendationDiscoveryItem>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = JSONObject(recommendationFile.readText()).optJSONArray("entries")
                    ?: return@runCatching null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("query") == queryKey }
                    ?: return@runCatching null
                if (System.currentTimeMillis() - entry.optLong("savedAt") > maxAgeMs) {
                    return@runCatching null
                }
                val items = entry.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { index ->
                    val json = items.optJSONObject(index) ?: return@mapNotNull null
                    val mediaJson = json.optJSONObject("media") ?: return@mapNotNull null
                    RecommendationDiscoveryItem(
                        media = Media.fromJson(mediaJson),
                        evidence = json.optString("evidence"),
                        sources = json.optJSONArray("sources")?.let { sources ->
                            (0 until sources.length()).mapNotNull { sourceIndex ->
                                sources.optString(sourceIndex).takeIf(String::isNotBlank)
                            }.toSet()
                        }.orEmpty(),
                        sourceCount = json.optInt("sourceCount"),
                        sourcePosition = json.optInt("sourcePosition", 99),
                    )
                }
            }.getOrNull()
        }
    }

    override suspend fun saveRecommendations(
        queryKey: String,
        items: List<RecommendationDiscoveryItem>,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = runCatching {
                JSONObject(recommendationFile.readText()).optJSONArray("entries")
            }.getOrNull()
            val entries = buildList {
                add(
                    JSONObject().apply {
                        put("query", queryKey)
                        put("savedAt", System.currentTimeMillis())
                        put(
                            "items",
                            JSONArray().apply {
                                items.forEach { item ->
                                    put(
                                        JSONObject()
                                            .put("media", item.media.toJson())
                                            .put("evidence", item.evidence)
                                            .put("sources", JSONArray(item.sources.toList()))
                                            .put("sourceCount", item.sourceCount)
                                            .put("sourcePosition", item.sourcePosition),
                                    )
                                }
                            },
                        )
                    },
                )
                if (previous != null) {
                    (0 until previous.length())
                        .mapNotNull(previous::optJSONObject)
                        .filterNot { it.optString("query") == queryKey }
                        .take(MAX_RECOMMENDATION_CACHE_ENTRIES - 1)
                        .forEach(::add)
                }
            }
            writeAtomically(
                recommendationFile,
                JSONObject().put("entries", JSONArray(entries)).toString(),
            )
        }
    }

    override suspend fun loadVerifiedMetadata(
        mediaKey: String,
        maxAgeMs: Long,
    ): VerifiedRecommendationItem? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = JSONObject(metadataFile.readText()).optJSONArray("entries")
                    ?: return@runCatching null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("key") == mediaKey }
                    ?: return@runCatching null
                if (System.currentTimeMillis() - entry.optLong("savedAt") > maxAgeMs) {
                    return@runCatching null
                }
                val metadata = entry.optJSONObject("metadata") ?: JSONObject()
                VerifiedRecommendationItem(
                    media = Media.fromJson(entry.getJSONObject("media")),
                    metadata = CatalogVerifiedMetadata(
                        runtimeMinutes = metadata.optInt("runtimeMinutes")
                            .takeIf { metadata.has("runtimeMinutes") && it > 0 },
                        originalLanguage = metadata.optString("originalLanguage")
                            .takeIf(String::isNotBlank),
                        status = metadata.optString("status").takeIf(String::isNotBlank),
                        director = metadata.optString("director").takeIf(String::isNotBlank),
                        seasonCount = metadata.optInt("seasonCount")
                            .takeIf { metadata.has("seasonCount") && it > 0 },
                        averageEpisodeRuntimeMinutes =
                            metadata.optInt("averageEpisodeRuntimeMinutes")
                                .takeIf {
                                    metadata.has("averageEpisodeRuntimeMinutes") && it > 0
                                },
                        verifiedAtMillis = entry.optLong("savedAt"),
                    ),
                )
            }.getOrNull()
        }
    }

    override suspend fun saveVerifiedMetadata(
        item: VerifiedRecommendationItem,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = runCatching {
                JSONObject(metadataFile.readText()).optJSONArray("entries")
            }.getOrNull()
            val entries = buildList {
                add(
                    JSONObject().apply {
                        put("key", item.media.key)
                        put("savedAt", item.metadata.verifiedAtMillis)
                        put("media", item.media.toJson())
                        put(
                            "metadata",
                            JSONObject().apply {
                                item.metadata.runtimeMinutes?.let {
                                    put("runtimeMinutes", it)
                                }
                                item.metadata.originalLanguage?.let {
                                    put("originalLanguage", it)
                                }
                                item.metadata.status?.let { put("status", it) }
                                item.metadata.director?.let { put("director", it) }
                                item.metadata.seasonCount?.let { put("seasonCount", it) }
                                item.metadata.averageEpisodeRuntimeMinutes?.let {
                                    put("averageEpisodeRuntimeMinutes", it)
                                }
                            },
                        )
                    },
                )
                if (previous != null) {
                    (0 until previous.length())
                        .mapNotNull(previous::optJSONObject)
                        .filterNot { it.optString("key") == item.media.key }
                        .take(MAX_METADATA_CACHE_ENTRIES - 1)
                        .forEach(::add)
                }
            }
            writeAtomically(
                metadataFile,
                JSONObject().put("entries", JSONArray(entries)).toString(),
            )
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
        const val MAX_RECOMMENDATION_CACHE_ENTRIES = 32
        const val MAX_METADATA_CACHE_ENTRIES = 240
    }
}
