package com.aliflix.app.data

import android.content.Context
import android.util.AtomicFile
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationPageCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

private suspend fun <T> cacheLoadOrNull(
    block: suspend () -> T,
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private suspend fun <T> cacheLoadOrDefault(
    defaultValue: T,
    block: suspend () -> T,
): T = cacheLoadOrNull(block) ?: defaultValue

private inline fun <T> cacheValueOrNull(
    block: () -> T,
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

interface CatalogCacheStore {
    suspend fun loadHome(): HomeContent?
    suspend fun saveHome(content: HomeContent)
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
    suspend fun loadRottenTomatoesRating(
        mediaKey: String,
        maxAgeMs: Long,
    ): RottenTomatoesSnapshot? = null
    suspend fun saveRottenTomatoesRating(
        mediaKey: String,
        snapshot: RottenTomatoesSnapshot,
    ) = Unit
}

data class CachedRecommendationCatalogPage(
    val items: List<RecommendationDiscoveryItem>,
    val nextCursor: RecommendationPageCursor?,
    val hasMore: Boolean,
    val savedAtMillis: Long = System.currentTimeMillis(),
)

class AndroidCatalogCacheStore internal constructor(
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileReader: (File) -> String = { file -> file.readText() },
    private val fileWriter: ((File, String) -> Unit)? = null,
) : CatalogCacheStore {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        cacheDir = File(context.filesDir, "catalog-cache"),
        ioDispatcher = ioDispatcher,
        computationDispatcher = computationDispatcher,
    )

    private val homeFile = File(cacheDir, "home-v4.json")
    private val recommendationFile = File(cacheDir, "recommendations-v1.json")
    private val recommendationPageFile = File(cacheDir, "recommendation-pages-v3.json")
    private val metadataFile = File(cacheDir, "recommendation-metadata-v1.json")
    private val imdbRatingFile = File(cacheDir, "imdb-ratings-v2.json")
    private val rottenTomatoesRatingFile = File(cacheDir, "rotten-tomatoes-ratings-v3.json")
    private val mutex = Mutex()
    private val cacheScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val pendingMetadata = linkedMapOf<String, VerifiedRecommendationItem>()
    private var metadataFlushJob: Job? = null

    init {
        try {
            listOf(
                "imdb-ratings-v1.json",
                "rotten-tomatoes-ratings-v1.json",
                "rotten-tomatoes-ratings-v2.json",
            )
                .map { File(cacheDir, it) }
                .filter(File::exists)
                .forEach(File::delete)
        } catch (_: Throwable) {}
    }

    override suspend fun loadHome(): HomeContent? = mutex.withLock {
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(homeFile) }
            withContext(computationDispatcher) {
                val json = JSONObject(value)
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
            }
        }
    }

    override suspend fun saveHome(content: HomeContent) = mutex.withLock {
        val value = withContext(computationDispatcher) {
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
            json.toString()
        }
        withContext(ioDispatcher) { writeAtomically(homeFile, value) }
    }

    override suspend fun loadRecommendations(
        queryKey: String,
        maxAgeMs: Long,
    ): List<RecommendationDiscoveryItem>? = mutex.withLock {
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(recommendationFile) }
            withContext(computationDispatcher) decode@{
                val entries = JSONObject(value).optJSONArray("entries")
                    ?: return@decode null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("query") == queryKey }
                    ?: return@decode null
                if (System.currentTimeMillis() - entry.optLong("savedAt") > maxAgeMs) {
                    return@decode null
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
            }
        }
    }

    override suspend fun saveRecommendations(
        queryKey: String,
        items: List<RecommendationDiscoveryItem>,
    ) = mutex.withLock {
        val previousValue = withContext(ioDispatcher) {
            cacheValueOrNull { fileReader(recommendationFile) }
        }
        val value = withContext(computationDispatcher) {
            val previous = previousValue?.let {
                runCatching { JSONObject(it).optJSONArray("entries") }.getOrNull()
            }
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
            JSONObject().put("entries", JSONArray(entries)).toString()
        }
        withContext(ioDispatcher) { writeAtomically(recommendationFile, value) }
    }

    override suspend fun loadRecommendationCatalogPage(
        fingerprint: String,
        page: Int,
        maxAgeMs: Long,
    ): CachedRecommendationCatalogPage? = mutex.withLock {
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(recommendationPageFile) }
            withContext(computationDispatcher) decode@{
                val entries = JSONObject(value)
                    .optJSONArray("entries") ?: return@decode null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull {
                        it.optString("fingerprint") == fingerprint &&
                            it.optInt("page") == page
                    } ?: return@decode null
                val savedAt = entry.optLong("savedAt")
                if (System.currentTimeMillis() - savedAt > maxAgeMs) {
                    return@decode null
                }
                val itemArray = entry.optJSONArray("items") ?: JSONArray()
                val items = recommendationItemsFromJson(itemArray)
                CachedRecommendationCatalogPage(
                    items = items,
                    nextCursor = entry.optJSONObject("nextCursor")?.let(::cursorFromJson),
                    hasMore = entry.optBoolean("hasMore"),
                    savedAtMillis = savedAt,
                )
            }
        }
    }

    override suspend fun saveRecommendationCatalogPage(
        fingerprint: String,
        page: Int,
        value: CachedRecommendationCatalogPage,
    ) = mutex.withLock {
        val previousValue = withContext(ioDispatcher) {
            cacheValueOrNull { fileReader(recommendationPageFile) }
        }
        val encoded = withContext(computationDispatcher) {
            val previous = previousValue?.let {
                runCatching { JSONObject(it).optJSONArray("entries") }.getOrNull()
            }
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
            JSONObject().put("entries", JSONArray(entries)).toString()
        }
        withContext(ioDispatcher) { writeAtomically(recommendationPageFile, encoded) }
    }

    override suspend fun loadLastGoodRecommendationItems(
        mediaType: MediaType,
        maxAgeMs: Long,
        limit: Int,
    ): List<RecommendationDiscoveryItem> = mutex.withLock {
        if (limit <= 0) return@withLock emptyList()
        cacheLoadOrDefault(emptyList()) {
            val (pageValue, metadataValue) = withContext(ioDispatcher) {
                val pages = fileReader(recommendationPageFile)
                val metadata = cacheValueOrNull { fileReader(metadataFile) }
                pages to metadata
            }
            val pendingSnapshot = pendingMetadata.values.toList()
            withContext(computationDispatcher) decode@{
                val now = System.currentTimeMillis()
                val fingerprintPrefix = when (mediaType) {
                    MediaType.MOVIE -> "MOVIE|"
                    MediaType.TV -> "SERIES|"
                }
                val pageEntries = JSONObject(pageValue)
                    .optJSONArray("entries")
                    ?: return@decode emptyList()
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
                val metadataEntries = metadataValue?.let {
                    runCatching { JSONObject(it).optJSONArray("entries") }.getOrNull()
                }
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
                pendingSnapshot
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
            }
        }
    }

    override suspend fun loadVerifiedMetadata(
        mediaKey: String,
        maxAgeMs: Long,
    ): VerifiedRecommendationItem? = mutex.withLock {
        pendingMetadata[mediaKey]?.takeIf { item ->
            System.currentTimeMillis() - item.metadata.verifiedAtMillis <= maxAgeMs
        }?.let { return@withLock it }
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(metadataFile) }
            withContext(computationDispatcher) decode@{
                val entries = JSONObject(value).optJSONArray("entries")
                    ?: return@decode null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("key") == mediaKey }
                    ?: return@decode null
                if (System.currentTimeMillis() - entry.optLong("savedAt") > maxAgeMs) {
                    return@decode null
                }
                verifiedItemFromJson(entry)
            }
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
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(imdbRatingFile) }
            withContext(computationDispatcher) decode@{
                val entries = JSONObject(value)
                    .optJSONArray("entries") ?: return@decode null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("key") == mediaKey }
                    ?: return@decode null
                val fetchedAt = entry.optLong("fetchedAt")
                if (System.currentTimeMillis() - fetchedAt > maxAgeMs) {
                    return@decode null
                }
                val imdbId = entry.optString("imdbId")
                    .takeIf { it.matches(Regex("tt\\d+")) }
                    ?: return@decode null
                val type = MediaType.from(entry.optString("type"))
                val state = com.aliflix.app.model.RatingSourceState.entries
                    .firstOrNull { it.name == entry.optString("state") }
                    ?: return@decode null
                val ageMs = System.currentTimeMillis() - fetchedAt
                if (state == com.aliflix.app.model.RatingSourceState.NOT_RATED) {
                    if (ageMs > 24 * 60 * 60 * 1000L) {
                        return@decode null
                    }
                } else if (ageMs > maxAgeMs) {
                    return@decode null
                }
                val rating = entry.optDouble("rating").takeIf {
                    entry.has("rating") && !it.isNaN() && it in 0.1..10.0
                }
                if (state == com.aliflix.app.model.RatingSourceState.VERIFIED && rating == null) {
                    return@decode null
                }
                ImdbRatingSnapshot(
                    identity = ImdbTitleIdentity(
                        imdbId = imdbId,
                        title = entry.optString("title"),
                        year = entry.optInt("year").takeIf { entry.has("year") },
                        type = type,
                    ),
                    rating = rating,
                    voteCount = entry.optInt("votes").takeIf {
                        entry.has("votes") && it >= 0
                    },
                    state = state,
                    fetchedAtMillis = fetchedAt,
                )
            }
        }
    }

    override suspend fun saveImdbRating(mediaKey: String, snapshot: ImdbRatingSnapshot) {
        if (snapshot.state == com.aliflix.app.model.RatingSourceState.UNAVAILABLE ||
            snapshot.state == com.aliflix.app.model.RatingSourceState.LOADING) {
            return
        }
        mutex.withLock {
            val value = withContext(computationDispatcher) {
                val previous = cacheValueOrNull { fileReader(imdbRatingFile) }?.let { JSONObject(it) }
                    ?.optJSONArray("entries")
                val entries = JSONArray()
                val updated = JSONObject().apply {
                    put("key", mediaKey)
                    put("imdbId", snapshot.identity.imdbId)
                    put("title", snapshot.identity.title)
                    put("type", snapshot.identity.type.routeName)
                    snapshot.identity.year?.let { put("year", it) }
                    snapshot.rating?.let { put("rating", it) }
                    snapshot.voteCount?.let { put("votes", it) }
                    put("state", snapshot.state.name)
                    put("fetchedAt", snapshot.fetchedAtMillis)
                }
                entries.put(updated)
                if (previous != null) {
                    for (i in 0 until previous.length()) {
                        val entry = previous.optJSONObject(i) ?: continue
                        if (entry.optString("key") != mediaKey) {
                            entries.put(entry)
                        }
                    }
                }
                JSONObject().put("entries", entries).toString()
            }
            withContext(ioDispatcher) { writeAtomically(imdbRatingFile, value) }
        }
    }
    override suspend fun loadRottenTomatoesRating(
        mediaKey: String,
        maxAgeMs: Long,
    ): RottenTomatoesSnapshot? = mutex.withLock {
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(rottenTomatoesRatingFile) }
            withContext(computationDispatcher) decode@{
                val entries = JSONObject(value).optJSONArray("entries") ?: return@decode null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("key") == mediaKey }
                    ?: return@decode null
                val fetchedAt = entry.optLong("fetchedAt")
                val state = com.aliflix.app.model.RatingSourceState.entries
                    .firstOrNull { it.name == entry.optString("state") }
                    ?: return@decode null
                val ageMs = System.currentTimeMillis() - fetchedAt
                if (state == com.aliflix.app.model.RatingSourceState.NOT_RATED) {
                    if (ageMs > 24 * 60 * 60 * 1000L) {
                        return@decode null
                    }
                } else if (ageMs > maxAgeMs) {
                    return@decode null
                }
                RottenTomatoesSnapshot(
                    rating = entry.optInt("rating").takeIf { entry.has("rating") && it > 0 },
                    state = if (
                        state == com.aliflix.app.model.RatingSourceState.VERIFIED &&
                        ageMs > 7 * 24 * 60 * 60 * 1000L
                    ) com.aliflix.app.model.RatingSourceState.STALE else state,
                )
            }
        }
    }

    override suspend fun saveRottenTomatoesRating(mediaKey: String, snapshot: RottenTomatoesSnapshot) {
        if (snapshot.state == com.aliflix.app.model.RatingSourceState.UNAVAILABLE ||
            snapshot.state == com.aliflix.app.model.RatingSourceState.LOADING) return
        mutex.withLock {
            val value = withContext(computationDispatcher) {
                val previous = cacheValueOrNull { fileReader(rottenTomatoesRatingFile) }?.let { JSONObject(it) }
                    ?.optJSONArray("entries")
                val entries = JSONArray()
                val updated = JSONObject().apply {
                    put("key", mediaKey)
                    snapshot.rating?.let { put("rating", it) }
                    put("state", snapshot.state.name)
                    put("fetchedAt", System.currentTimeMillis())
                }
                entries.put(updated)
                if (previous != null) {
                    for (i in 0 until previous.length()) {
                        val entry = previous.optJSONObject(i) ?: continue
                        if (entry.optString("key") != mediaKey) {
                            entries.put(entry)
                        }
                    }
                }
                JSONObject().put("entries", entries).toString()
            }
            withContext(ioDispatcher) { writeAtomically(rottenTomatoesRatingFile, value) }
        }
    }

    private suspend fun flushPendingMetadata() = mutex.withLock {
        if (pendingMetadata.isEmpty()) return@withLock
        val batch = pendingMetadata.values.toList()
        pendingMetadata.clear()
        val previousValue = withContext(ioDispatcher) {
            cacheValueOrNull { fileReader(metadataFile) }
        }
        val value = withContext(computationDispatcher) {
            val previous = previousValue?.let {
                runCatching { JSONObject(it).optJSONArray("entries") }.getOrNull()
            }
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
            JSONObject().put("entries", JSONArray(entries)).toString()
        }
        withContext(ioDispatcher) { writeAtomically(metadataFile, value) }
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
        fileWriter?.let { writer ->
            writer(target, value)
            return
        }
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
        const val MAX_RECOMMENDATION_CACHE_ENTRIES = 80
        const val MAX_RECOMMENDATION_PAGE_CACHE_ENTRIES = 72
        const val MAX_IMDB_RATING_ENTRIES = 600
        const val MAX_METADATA_CACHE_ENTRIES = 600
        const val METADATA_WRITE_DEBOUNCE_MS = 250L
    }
}
