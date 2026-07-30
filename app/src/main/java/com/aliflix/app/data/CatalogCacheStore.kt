package com.aliflix.app.data

import android.content.Context
import android.util.AtomicFile
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationPageCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    suspend fun loadRecommendationPage(
        fingerprint: String,
        page: Int,
        maxAgeMs: Long,
    ): List<RecommendationDiscoveryItem>? =
        loadRecommendations("$fingerprint:page:$page", maxAgeMs)
    suspend fun saveRecommendationPage(
        fingerprint: String,
        page: Int,
        items: List<RecommendationDiscoveryItem>,
    ) = saveRecommendations("$fingerprint:page:$page", items)
    suspend fun loadRecommendationCatalogPage(
        fingerprint: String,
        page: Int,
        maxAgeMs: Long,
    ): CachedRecommendationCatalogPage? = null
    suspend fun saveRecommendationCatalogPage(
        fingerprint: String,
        page: Int,
        value: CachedRecommendationCatalogPage,
    ) = Unit
    suspend fun loadLastGoodRecommendationItems(
        mediaType: MediaType,
        maxAgeMs: Long,
        limit: Int = 120,
    ): List<RecommendationDiscoveryItem> = emptyList()
    suspend fun loadVerifiedMetadata(
        mediaKey: String,
        maxAgeMs: Long,
    ): VerifiedRecommendationItem? = null
    suspend fun saveVerifiedMetadata(item: VerifiedRecommendationItem) = Unit
    suspend fun loadImdbRating(
        mediaKey: String,
        maxAgeMs: Long,
    ): ImdbRatingSnapshot? = null
    suspend fun saveImdbRating(
        mediaKey: String,
        snapshot: ImdbRatingSnapshot,
    ) = Unit
}

data class CachedRecommendationCatalogPage(
    val items: List<RecommendationDiscoveryItem>,
    val nextCursor: RecommendationPageCursor?,
    val hasMore: Boolean,
    val savedAtMillis: Long = System.currentTimeMillis(),
)

class AndroidCatalogCacheStore(context: Context) : CatalogCacheStore {
    private val cacheDir = File(context.filesDir, "catalog-cache")
    private val homeFile = File(cacheDir, "home-v4.json")
    private val plotFile = File(cacheDir, "plot-v2.json")
    private val recommendationFile = File(cacheDir, "recommendations-v1.json")
    private val recommendationPageFile = File(cacheDir, "recommendation-pages-v3.json")
    private val metadataFile = File(cacheDir, "recommendation-metadata-v1.json")
    private val imdbRatingFile = File(cacheDir, "imdb-ratings-v1.json")
    private val mutex = Mutex()
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMetadata = linkedMapOf<String, VerifiedRecommendationItem>()
    private var metadataFlushJob: Job? = null

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

    override suspend fun loadRecommendationCatalogPage(
        fingerprint: String,
        page: Int,
        maxAgeMs: Long,
    ): CachedRecommendationCatalogPage? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = JSONObject(recommendationPageFile.readText())
                    .optJSONArray("entries") ?: return@runCatching null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull {
                        it.optString("fingerprint") == fingerprint &&
                            it.optInt("page") == page
                    } ?: return@runCatching null
                val savedAt = entry.optLong("savedAt")
                if (System.currentTimeMillis() - savedAt > maxAgeMs) {
                    return@runCatching null
                }
                val itemArray = entry.optJSONArray("items") ?: JSONArray()
                val items = recommendationItemsFromJson(itemArray)
                CachedRecommendationCatalogPage(
                    items = items,
                    nextCursor = entry.optJSONObject("nextCursor")?.let(::cursorFromJson),
                    hasMore = entry.optBoolean("hasMore"),
                    savedAtMillis = savedAt,
                )
            }.getOrNull()
        }
    }

    override suspend fun saveRecommendationCatalogPage(
        fingerprint: String,
        page: Int,
        value: CachedRecommendationCatalogPage,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = runCatching {
                JSONObject(recommendationPageFile.readText()).optJSONArray("entries")
            }.getOrNull()
            val entries = buildList {
                add(
                    JSONObject().apply {
                        put("fingerprint", fingerprint)
                        put("page", page)
                        put("savedAt", value.savedAtMillis)
                        put("hasMore", value.hasMore)
                        value.nextCursor?.let { put("nextCursor", cursorToJson(it)) }
                        put(
                            "items",
                            JSONArray().apply {
                                value.items.forEach { item ->
                                    put(
                                        JSONObject()
                                            .put("media", item.media.toJson())
                                            .put("metadata", metadataToJson(item.metadata))
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
                        .filterNot {
                            it.optString("fingerprint") == fingerprint &&
                                it.optInt("page") == page
                        }
                        .take(MAX_RECOMMENDATION_PAGE_CACHE_ENTRIES - 1)
                        .forEach(::add)
                }
            }
            writeAtomically(
                recommendationPageFile,
                JSONObject().put("entries", JSONArray(entries)).toString(),
            )
        }
    }

    override suspend fun loadLastGoodRecommendationItems(
        mediaType: MediaType,
        maxAgeMs: Long,
        limit: Int,
    ): List<RecommendationDiscoveryItem> = mutex.withLock {
        if (limit <= 0) return@withLock emptyList()
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val fingerprintPrefix = when (mediaType) {
                    MediaType.MOVIE -> "MOVIE|"
                    MediaType.TV -> "SERIES|"
                }
                val pageEntries = JSONObject(recommendationPageFile.readText())
                    .optJSONArray("entries")
                    ?: return@runCatching emptyList()
                val items = (0 until pageEntries.length())
                    .mapNotNull(pageEntries::optJSONObject)
                    .filter { entry ->
                        entry.optString("fingerprint").startsWith(fingerprintPrefix) &&
                            now - entry.optLong("savedAt") <= maxAgeMs
                    }
                    .sortedByDescending { it.optLong("savedAt") }
                    .flatMap { entry ->
                        recommendationItemsFromJson(
                            entry.optJSONArray("items") ?: JSONArray(),
                        )
                    }
                    .filter { it.media.type == mediaType }
                    .distinctBy { it.media.key }
                    .take(limit)

                val verifiedByKey = linkedMapOf<String, VerifiedRecommendationItem>()
                val metadataEntries = runCatching {
                    JSONObject(metadataFile.readText()).optJSONArray("entries")
                }.getOrNull()
                if (metadataEntries != null) {
                    (0 until metadataEntries.length())
                        .mapNotNull(metadataEntries::optJSONObject)
                        .filter { entry ->
                            now - entry.optLong("savedAt") <= maxAgeMs
                        }
                        .mapNotNull(::verifiedItemFromJson)
                        .forEach { item ->
                            verifiedByKey.putIfAbsent(item.media.key, item)
                        }
                }
                pendingMetadata.values
                    .filter { item ->
                        now - item.metadata.verifiedAtMillis <= maxAgeMs
                    }
                    .forEach { item -> verifiedByKey[item.media.key] = item }

                items.map { item ->
                    val verified = verifiedByKey[item.media.key]
                        ?: return@map item
                    item.copy(
                        media = verified.media,
                        metadata = mergeMetadata(item.metadata, verified.metadata),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun loadVerifiedMetadata(
        mediaKey: String,
        maxAgeMs: Long,
    ): VerifiedRecommendationItem? = mutex.withLock {
        pendingMetadata[mediaKey]?.takeIf { item ->
            System.currentTimeMillis() - item.metadata.verifiedAtMillis <= maxAgeMs
        }?.let { return@withLock it }
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
                verifiedItemFromJson(entry)
            }.getOrNull()
        }
    }

    override suspend fun saveVerifiedMetadata(
        item: VerifiedRecommendationItem,
    ) = mutex.withLock {
        pendingMetadata[item.media.key] = item
        if (metadataFlushJob?.isActive != true) {
            metadataFlushJob = cacheScope.launch {
                delay(METADATA_WRITE_DEBOUNCE_MS)
                flushPendingMetadata()
            }
        }
    }

    override suspend fun loadImdbRating(
        mediaKey: String,
        maxAgeMs: Long,
    ): ImdbRatingSnapshot? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = JSONObject(imdbRatingFile.readText())
                    .optJSONArray("entries") ?: return@runCatching null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("key") == mediaKey }
                    ?: return@runCatching null
                val fetchedAt = entry.optLong("fetchedAt")
                if (System.currentTimeMillis() - fetchedAt > maxAgeMs) {
                    return@runCatching null
                }
                val imdbId = entry.optString("imdbId")
                    .takeIf { it.matches(Regex("tt\\d+")) }
                    ?: return@runCatching null
                val type = MediaType.from(entry.optString("type"))
                val state = com.aliflix.app.model.RatingSourceState.entries
                    .firstOrNull { it.name == entry.optString("state") }
                    ?: return@runCatching null
                ImdbRatingSnapshot(
                    identity = ImdbTitleIdentity(
                        imdbId = imdbId,
                        title = entry.optString("title"),
                        year = entry.optInt("year").takeIf { entry.has("year") },
                        type = type,
                    ),
                    rating = entry.optDouble("rating").takeIf {
                        entry.has("rating") && !it.isNaN() && it in 0.1..10.0
                    },
                    voteCount = entry.optInt("votes").takeIf {
                        entry.has("votes") && it >= 0
                    },
                    state = state,
                    fetchedAtMillis = fetchedAt,
                )
            }.getOrNull()
        }
    }

    override suspend fun saveImdbRating(
        mediaKey: String,
        snapshot: ImdbRatingSnapshot,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = runCatching {
                JSONObject(imdbRatingFile.readText()).optJSONArray("entries")
            }.getOrNull()
            val entries = buildList {
                add(
                    JSONObject()
                        .put("key", mediaKey)
                        .put("imdbId", snapshot.identity.imdbId)
                        .put("title", snapshot.identity.title)
                        .put("type", snapshot.identity.type.routeName)
                        .put("state", snapshot.state.name)
                        .put("fetchedAt", snapshot.fetchedAtMillis)
                        .apply {
                            snapshot.identity.year?.let { put("year", it) }
                            snapshot.rating?.let { put("rating", it) }
                            snapshot.voteCount?.let { put("votes", it) }
                        },
                )
                if (previous != null) {
                    (0 until previous.length())
                        .mapNotNull(previous::optJSONObject)
                        .filterNot { it.optString("key") == mediaKey }
                        .sortedByDescending { it.optLong("fetchedAt") }
                        .take(MAX_IMDB_RATING_ENTRIES - 1)
                        .forEach(::add)
                }
            }
            writeAtomically(
                imdbRatingFile,
                JSONObject().put("entries", JSONArray(entries)).toString(),
            )
        }
    }

    private suspend fun flushPendingMetadata() = mutex.withLock {
        if (pendingMetadata.isEmpty()) return@withLock
        val batch = pendingMetadata.values.toList()
        pendingMetadata.clear()
        withContext(Dispatchers.IO) {
            val previous = runCatching {
                JSONObject(metadataFile.readText()).optJSONArray("entries")
            }.getOrNull()
            val batchKeys = batch.mapTo(hashSetOf()) { it.media.key }
            val entries = buildList {
                batch.asReversed().forEach { item ->
                    add(metadataEntryToJson(item))
                }
                if (previous != null) {
                    (0 until previous.length())
                        .mapNotNull(previous::optJSONObject)
                        .filterNot { it.optString("key") in batchKeys }
                        .take((MAX_METADATA_CACHE_ENTRIES - batch.size).coerceAtLeast(0))
                        .forEach(::add)
                }
            }.take(MAX_METADATA_CACHE_ENTRIES)
            writeAtomically(
                metadataFile,
                JSONObject().put("entries", JSONArray(entries)).toString(),
            )
        }
    }

    private fun metadataEntryToJson(
        item: VerifiedRecommendationItem,
    ): JSONObject = JSONObject().apply {
        put("key", item.media.key)
        put("savedAt", item.metadata.verifiedAtMillis)
        put("media", item.media.toJson())
        put("metadata", metadataToJson(item.metadata))
    }

    private fun recommendationItemsFromJson(
        items: JSONArray,
    ): List<RecommendationDiscoveryItem> =
        (0 until items.length()).mapNotNull { index ->
            val json = items.optJSONObject(index) ?: return@mapNotNull null
            val mediaJson = json.optJSONObject("media") ?: return@mapNotNull null
            RecommendationDiscoveryItem(
                media = Media.fromJson(mediaJson),
                metadata = json.optJSONObject("metadata")
                    ?.let(::metadataFromJson)
                    ?: CatalogVerifiedMetadata(),
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

    private fun verifiedItemFromJson(
        entry: JSONObject,
    ): VerifiedRecommendationItem? {
        val media = entry.optJSONObject("media")?.let(Media::fromJson)
            ?: return null
        val metadata = entry.optJSONObject("metadata")
            ?.let(::metadataFromJson)
            ?: CatalogVerifiedMetadata(
                verifiedAtMillis = entry.optLong("savedAt"),
            )
        return VerifiedRecommendationItem(
            media = media,
            metadata = metadata.copy(
                verifiedAtMillis = entry.optLong(
                    "savedAt",
                    metadata.verifiedAtMillis,
                ),
            ),
        )
    }

    private fun mergeMetadata(
        cached: CatalogVerifiedMetadata,
        verified: CatalogVerifiedMetadata,
    ): CatalogVerifiedMetadata = CatalogVerifiedMetadata(
        genresVerified = cached.genresVerified || verified.genresVerified,
        runtimeMinutes = verified.runtimeMinutes ?: cached.runtimeMinutes,
        originalLanguage = verified.originalLanguage ?: cached.originalLanguage,
        status = verified.status ?: cached.status,
        director = verified.director ?: cached.director,
        seasonCount = verified.seasonCount ?: cached.seasonCount,
        averageEpisodeRuntimeMinutes =
            verified.averageEpisodeRuntimeMinutes
                ?: cached.averageEpisodeRuntimeMinutes,
        verifiedAtMillis = maxOf(
            cached.verifiedAtMillis,
            verified.verifiedAtMillis,
        ),
    )

    private fun writeAtomically(target: File, value: String) {
        cacheDir.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun cursorToJson(cursor: RecommendationPageCursor): JSONObject =
        JSONObject()
            .put("page", cursor.page)
            .put("seenKeys", JSONArray(cursor.seenKeys.toList()))
            .put("imdbPopularityCursor", cursor.imdbPopularityCursor)
            .put("imdbRatingCursor", cursor.imdbRatingCursor)
            .put("imdbHtmlFallback", cursor.imdbHtmlFallback)
            .put("imdbTmdbFallback", cursor.imdbTmdbFallback)
            .put("exhaustedSources", JSONArray(cursor.exhaustedSources.toList()))

    private fun cursorFromJson(json: JSONObject): RecommendationPageCursor =
        RecommendationPageCursor(
            page = json.optInt("page", 1),
            seenKeys = json.optJSONArray("seenKeys")?.let { values ->
                (0 until values.length()).mapNotNull { index ->
                    values.optString(index).takeIf(String::isNotBlank)
                }.toSet()
            }.orEmpty(),
            imdbPopularityCursor = json.optString("imdbPopularityCursor")
                .takeIf(String::isNotBlank),
            imdbRatingCursor = json.optString("imdbRatingCursor")
                .takeIf(String::isNotBlank),
            imdbHtmlFallback = json.optBoolean("imdbHtmlFallback"),
            imdbTmdbFallback = json.optBoolean("imdbTmdbFallback"),
            exhaustedSources = json.optJSONArray("exhaustedSources")?.let { values ->
                (0 until values.length()).mapNotNull { index ->
                    values.optString(index).takeIf(String::isNotBlank)
                }.toSet()
            }.orEmpty(),
        )

    private fun metadataToJson(metadata: CatalogVerifiedMetadata): JSONObject =
        JSONObject().apply {
            put("genresVerified", metadata.genresVerified)
            metadata.runtimeMinutes?.let { put("runtimeMinutes", it) }
            metadata.originalLanguage?.let { put("originalLanguage", it) }
            metadata.status?.let { put("status", it) }
            metadata.director?.let { put("director", it) }
            metadata.seasonCount?.let { put("seasonCount", it) }
            metadata.averageEpisodeRuntimeMinutes?.let {
                put("averageEpisodeRuntimeMinutes", it)
            }
            put("verifiedAtMillis", metadata.verifiedAtMillis)
        }

    private fun metadataFromJson(json: JSONObject): CatalogVerifiedMetadata =
        CatalogVerifiedMetadata(
            genresVerified = json.optBoolean("genresVerified"),
            runtimeMinutes = json.optInt("runtimeMinutes")
                .takeIf { json.has("runtimeMinutes") && it > 0 },
            originalLanguage = json.optString("originalLanguage")
                .takeIf(String::isNotBlank),
            status = json.optString("status").takeIf(String::isNotBlank),
            director = json.optString("director").takeIf(String::isNotBlank),
            seasonCount = json.optInt("seasonCount")
                .takeIf { json.has("seasonCount") && it > 0 },
            averageEpisodeRuntimeMinutes =
                json.optInt("averageEpisodeRuntimeMinutes")
                    .takeIf {
                        json.has("averageEpisodeRuntimeMinutes") && it > 0
                    },
            verifiedAtMillis = json.optLong(
                "verifiedAtMillis",
                System.currentTimeMillis(),
            ),
        )

    private companion object {
        const val MAX_PLOT_CACHE_ENTRIES = 24
        const val MAX_RECOMMENDATION_CACHE_ENTRIES = 80
        const val MAX_RECOMMENDATION_PAGE_CACHE_ENTRIES = 72
        const val MAX_IMDB_RATING_ENTRIES = 600
        const val MAX_METADATA_CACHE_ENTRIES = 600
        const val METADATA_WRITE_DEBOUNCE_MS = 250L
    }
}
