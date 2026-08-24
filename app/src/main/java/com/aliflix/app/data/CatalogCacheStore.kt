package com.aliflix.app.data

import android.content.Context
import android.util.AtomicFile
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private suspend fun <T> cacheLoadOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private inline fun <T> cacheValueOrNull(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

interface CatalogCacheStore {
    suspend fun loadHome(): HomeContent?
    suspend fun saveHome(content: HomeContent)
    suspend fun loadImdbRating(mediaKey: String, maxAgeMs: Long): ImdbRatingSnapshot? = null
    suspend fun saveImdbRating(mediaKey: String, snapshot: ImdbRatingSnapshot) = Unit
    suspend fun loadRottenTomatoesRating(
        mediaKey: String,
        maxAgeMs: Long,
    ): RottenTomatoesSnapshot? = null
    suspend fun saveRottenTomatoesRating(
        mediaKey: String,
        snapshot: RottenTomatoesSnapshot,
    ) = Unit
}

class AndroidCatalogCacheStore internal constructor(
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileReader: (File) -> String = File::readText,
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
    private val imdbRatingFile = File(cacheDir, "imdb-ratings-v2.json")
    private val rottenTomatoesRatingFile = File(cacheDir, "rotten-tomatoes-ratings-v3.json")
    private val mutex = Mutex()

    init {
        runCatching {
            listOf(
                "recommendations-v1.json",
                "recommendation-pages-v3.json",
                "recommendation-metadata-v1.json",
                "imdb-ratings-v1.json",
                "rotten-tomatoes-ratings-v1.json",
                "rotten-tomatoes-ratings-v2.json",
            ).map { File(cacheDir, it) }
                .filter(File::exists)
                .forEach(File::delete)
        }
    }

    override suspend fun loadHome(): HomeContent? = mutex.withLock {
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(homeFile) }
            withContext(computationDispatcher) {
                val json = JSONObject(value)
                val railsJson = json.getJSONArray("rails")
                HomeContent(
                    hero = Media.fromJson(json.getJSONObject("hero")),
                    rails = (0 until railsJson.length()).mapNotNull { index ->
                        val railJson = railsJson.optJSONObject(index) ?: return@mapNotNull null
                        val itemsJson = railJson.optJSONArray("items") ?: JSONArray()
                        val items = (0 until itemsJson.length()).mapNotNull { itemIndex ->
                            itemsJson.optJSONObject(itemIndex)?.let(Media::fromJson)
                        }
                        val title = railJson.optString("title").trim()
                        if (title.isBlank() || items.isEmpty()) null else ContentRail(title, items)
                    },
                )
            }
        }
    }

    override suspend fun saveHome(content: HomeContent) = mutex.withLock {
        val value = withContext(computationDispatcher) {
            JSONObject().apply {
                put("savedAt", System.currentTimeMillis())
                put("hero", content.hero.toJson())
                put("rails", JSONArray().apply {
                    content.rails.forEach { rail ->
                        put(JSONObject().apply {
                            put("title", rail.title)
                            put("items", JSONArray().apply {
                                rail.items.forEach { put(it.toJson()) }
                            })
                        })
                    }
                })
            }.toString()
        }
        withContext(ioDispatcher) { writeAtomically(homeFile, value) }
    }

    override suspend fun loadImdbRating(
        mediaKey: String,
        maxAgeMs: Long,
    ): ImdbRatingSnapshot? = mutex.withLock {
        cacheLoadOrNull {
            val value = withContext(ioDispatcher) { fileReader(imdbRatingFile) }
            withContext(computationDispatcher) decode@{
                val entries = JSONObject(value).optJSONArray("entries") ?: return@decode null
                val entry = (0 until entries.length())
                    .mapNotNull(entries::optJSONObject)
                    .firstOrNull { it.optString("key") == mediaKey }
                    ?: return@decode null
                val fetchedAt = entry.optLong("fetchedAt")
                val ageMs = System.currentTimeMillis() - fetchedAt
                val imdbId = entry.optString("imdbId")
                    .takeIf { it.matches(Regex("tt\\d+")) }
                    ?: return@decode null
                val state = RatingSourceState.entries
                    .firstOrNull { it.name == entry.optString("state") }
                    ?: return@decode null
                if (ageMs > cacheAgeFor(state, maxAgeMs)) return@decode null
                val rating = entry.optDouble("rating").takeIf {
                    entry.has("rating") && !it.isNaN() && it in 0.1..10.0
                }
                if (state == RatingSourceState.VERIFIED && rating == null) return@decode null
                ImdbRatingSnapshot(
                    identity = ImdbTitleIdentity(
                        imdbId = imdbId,
                        title = entry.optString("title"),
                        year = entry.optInt("year").takeIf { entry.has("year") },
                        type = MediaType.from(entry.optString("type")),
                    ),
                    rating = rating,
                    voteCount = entry.optInt("votes").takeIf { entry.has("votes") && it >= 0 },
                    state = state,
                    fetchedAtMillis = fetchedAt,
                )
            }
        }
    }

    override suspend fun saveImdbRating(mediaKey: String, snapshot: ImdbRatingSnapshot) {
        if (snapshot.state == RatingSourceState.UNAVAILABLE ||
            snapshot.state == RatingSourceState.LOADING
        ) return
        updateRatingFile(imdbRatingFile, mediaKey) {
            JSONObject().apply {
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
                val ageMs = System.currentTimeMillis() - fetchedAt
                val state = RatingSourceState.entries
                    .firstOrNull { it.name == entry.optString("state") }
                    ?: return@decode null
                if (ageMs > cacheAgeFor(state, maxAgeMs)) return@decode null
                RottenTomatoesSnapshot(
                    rating = entry.optInt("rating").takeIf { entry.has("rating") && it > 0 },
                    state = if (
                        state == RatingSourceState.VERIFIED && ageMs > VERIFIED_RT_FRESH_AGE_MS
                    ) RatingSourceState.STALE else state,
                )
            }
        }
    }

    override suspend fun saveRottenTomatoesRating(
        mediaKey: String,
        snapshot: RottenTomatoesSnapshot,
    ) {
        if (snapshot.state == RatingSourceState.UNAVAILABLE ||
            snapshot.state == RatingSourceState.LOADING
        ) return
        updateRatingFile(rottenTomatoesRatingFile, mediaKey) {
            JSONObject().apply {
                put("key", mediaKey)
                snapshot.rating?.let { put("rating", it) }
                put("state", snapshot.state.name)
                put("fetchedAt", System.currentTimeMillis())
            }
        }
    }

    private suspend fun updateRatingFile(
        target: File,
        mediaKey: String,
        updatedEntry: () -> JSONObject,
    ) = mutex.withLock {
        val previousValue = withContext(ioDispatcher) { cacheValueOrNull { fileReader(target) } }
        val value = withContext(computationDispatcher) {
            val previous = previousValue?.let { JSONObject(it).optJSONArray("entries") }
            val entries = JSONArray().put(updatedEntry())
            if (previous != null) {
                for (index in 0 until previous.length()) {
                    val entry = previous.optJSONObject(index) ?: continue
                    if (entry.optString("key") != mediaKey) entries.put(entry)
                }
            }
            JSONObject().put("entries", entries).toString()
        }
        withContext(ioDispatcher) { writeAtomically(target, value) }
    }

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

    private fun cacheAgeFor(state: RatingSourceState, requestedAgeMs: Long): Long =
        if (state == RatingSourceState.NOT_RATED) {
            minOf(requestedAgeMs, NOT_RATED_CACHE_AGE_MS)
        } else {
            requestedAgeMs
        }

    private companion object {
        const val NOT_RATED_CACHE_AGE_MS = 24 * 60 * 60 * 1000L
        const val VERIFIED_RT_FRESH_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
