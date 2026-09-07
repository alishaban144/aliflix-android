

package com.aliflix.app.data

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaReview
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.RelatedContentEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private suspend fun <T> suspendOrNull(
    block: suspend () -> T,
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private suspend fun <T> suspendOrDefault(
    defaultValue: T,
    block: suspend () -> T,
): T = suspendOrNull(block) ?: defaultValue

enum class CatalogSource {
    TMDB,
    IMDB,
}

open class CatalogSourceException(
    val source: CatalogSource,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class TmdbCatalogSourceException(
    message: String = "TMDB catalogue is unavailable.",
    cause: Throwable? = null,
) : CatalogSourceException(CatalogSource.TMDB, message, cause)

fun interface CatalogFormTransport {
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String>,
    ): String
}

private data class GenreFetch(
    val items: List<Media>,
    val pagesLoaded: Int,
)

private sealed interface TmdbCatalogueResponse {
    data class Results(
        val items: List<Media>,
        val hasNextPage: Boolean?,
        val rawItemCount: Int,
    ) : TmdbCatalogueResponse
    data object Empty : TmdbCatalogueResponse
    data class Unavailable(val cause: Throwable? = null) : TmdbCatalogueResponse
}

private sealed interface TmdbSearchOutcome {
    data class Success(val items: List<Media>) : TmdbSearchOutcome
    data class Unavailable(val cause: Throwable? = null) : TmdbSearchOutcome
}

data class RottenTomatoesSnapshot(
    val rating: Int?,
    val state: com.aliflix.app.model.RatingSourceState,
)


internal fun allocateUniqueHomeRails(

    rails: List<ContentRail>,
    priorityTitles: List<String>,
    itemLimit: Int = 20,
): List<ContentRail> {
    if (rails.isEmpty() || itemLimit <= 0) return emptyList()
    val byTitle = rails.associateBy(ContentRail::title)
    val allocationOrder = (
        priorityTitles + rails.map(ContentRail::title)
        ).distinct()
    val usedKeys = mutableSetOf<String>()
    val selected = mutableMapOf<String, ContentRail>()

    allocationOrder.forEach { title ->
        val rail = byTitle[title] ?: return@forEach
        val items = rail.items
            .asSequence()
            .distinctBy(Media::key)
            .filter { item -> item.key !in usedKeys }
            .take(itemLimit)
            .toList()
        usedKeys += items.map(Media::key)
        selected[title] = rail.copy(items = items)
    }
    return rails.mapNotNull { rail -> selected[rail.title] }
}

private val explicitTrendingWords = setOf(
    "hentai",
    "onlyfans",
    "porn",
    "porno",
    "pornographic",
    "pornography",
    "xxx",
)
private val explicitTrendingTitleWords = setOf(
    "deseo",
    "desire",
    "desires",
    "erotica",
    "erotic",
    "hardcore",
    "lust",
    "playboy",
    "seduction",
    "sensual",
)
private val explicitTrendingPhrases = setOf(
    "18 plus",
    "adults only",
    "adult film",
    "adult movie",
    "explicit content",
    "porn star",
    "sex tape",
    "uncensored version",
)

internal fun isSafeTrendingItem(item: Media): Boolean {
    val normalizedTitle = item.title.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    val searchable = buildString {
        append(item.title)
        append(' ')
        append(item.overview)
        append(' ')
        append(item.genres.joinToString(" "))
    }.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    if (searchable.isBlank()) return true
    val words = searchable.split(' ').filterTo(hashSetOf(), String::isNotBlank)
    val titleWords = normalizedTitle.split(' ').filterTo(hashSetOf(), String::isNotBlank)
    return words.none(explicitTrendingWords::contains) &&
        titleWords.none(explicitTrendingTitleWords::contains) &&
        explicitTrendingPhrases.none(searchable::contains)
}

internal object ForegroundRequestPriorityKey :
    CoroutineContext.Key<ForegroundRequestPriorityElement>

internal object ForegroundRequestPriorityElement :
    AbstractCoroutineContextElement(ForegroundRequestPriorityKey)

/**
 * One bounded scheduler for all catalogue traffic. Foreground recommendation
 * requests announce themselves before waiting for a permit, so queued Home
 * refresh work yields instead of filling the next available slots.
 */
private class CatalogRequestScheduler {
    private val globalGate = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val backgroundGate = Semaphore(MAX_BACKGROUND_REQUESTS)
    private val hostGates = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> execute(
        url: String,
        foreground: Boolean,
        block: suspend () -> T,
    ): T {
        val host = runCatching { URL(url).host.lowercase() }
            .getOrDefault("unknown")
        val hostGate = hostGates.computeIfAbsent(host) {
            Semaphore(MAX_CONCURRENT_REQUESTS_PER_HOST)
        }
        
        suspend fun acquireGlobalAndHost(): T {
            return globalGate.withPermit {
                hostGate.withPermit { block() }
            }
        }
        
        return if (!foreground) {
            backgroundGate.withPermit { acquireGlobalAndHost() }
        } else {
            acquireGlobalAndHost()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_BACKGROUND_REQUESTS = 3
        const val MAX_CONCURRENT_REQUESTS_PER_HOST = 4
    }
}

private object HttpCatalogFormTransport : CatalogFormTransport {
    override suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        val payload = fields.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        return suspendCancellableCoroutine { continuation ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {

                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000

                instanceFollowRedirects = false
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
                headers.forEach(::setRequestProperty)
            }
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val response = stream?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    throw TmdbCatalogSourceException(
                        "TMDB catalogue request failed ($status).",
                    )
                }
                if (response.isBlank()) {
                    throw TmdbCatalogSourceException(
                        "TMDB catalogue response was empty.",
                    )
                }
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        when (error) {
                            is CatalogSourceException -> error
                            is IOException -> TmdbCatalogSourceException(
                                cause = error,
                            )
                            else -> error
                        },
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun formEncode(value: String): String = URLEncoder.encode(
        value,
        StandardCharsets.UTF_8.toString(),
    )
}

/**
 * Builds the native catalogue from public, server-rendered movie metadata pages.
 *
 * No TMDB account, API key, or copied third-party credential is needed. The
 * player remains separate and loads only after the user presses Play.
 */
class CatalogClient(
    private val cacheStore: CatalogCacheStore? = null,
    jsonPoster: suspend (String, String) -> String = ::postJson,
    formTransport: CatalogFormTransport = HttpCatalogFormTransport,
    imdbGraphQlTransport: ImdbGraphQlTransport? = null,
    pageLoader: suspend (String) -> String = ::downloadPage,
    rottenTomatoesClientOverride: RottenTomatoesClient? = null,
    omdbClientOverride: com.aliflix.app.data.omdb.OmdbMetadataClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(pageLoader: suspend (String) -> String) : this(
        pageLoader = pageLoader,
        ioDispatcher = Dispatchers.IO,
    )

    private val rawJsonPoster = jsonPoster
    private val rawFormTransport = formTransport
    private val rawPageLoader = pageLoader
    private val rawImdbGraphQlTransport = imdbGraphQlTransport
        ?: HttpImdbGraphQlTransport(ioDispatcher)
    private val requestScheduler = CatalogRequestScheduler()
    @Volatile
    private var catalogue: List<Media> = fallbackItems
    private val imdbRatingsCache = ConcurrentHashMap<String, Double>()
    private val rottenTomatoesRatingsCache = ConcurrentHashMap<String, RottenTomatoesSnapshot>()
    private val tmdbSearchCache = ConcurrentHashMap<String, List<Media>>()
    private val verifiedGenrePageCache = ConcurrentHashMap<String, List<Media>>()
    private val rottenTomatoesClient = rottenTomatoesClientOverride ?: RottenTomatoesClient()
    private val omdbClient = omdbClientOverride ?: com.aliflix.app.data.omdb.OmdbMetadataClient(
        baseUrl = com.aliflix.app.BuildConfig.RECOMMENDATION_AI_BASE_URL,
        ioDispatcher = ioDispatcher,
    )
    private val verifiedGenrePageLocks = ConcurrentHashMap<String, Mutex>()
    private val genreBrowsePageCursor = ConcurrentHashMap<String, Int>()
    private val genreBrowseSeenKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var homeShownKeys: Set<String> = emptySet()
    private val imdbRatingRepository: ImdbRatingRepository =
        DefaultImdbRatingRepository(
            cacheStore = cacheStore,
            pageLoader = { url -> this@CatalogClient.pageLoader(url) },
            graphQlTransport = ImdbGraphQlTransport { url, body, headers ->
                withContext(ioDispatcher) {
                    requestScheduler.execute(
                        url = url,
                        foreground =
                            coroutineContext[ForegroundRequestPriorityKey] != null,
                    ) {
                        rawImdbGraphQlTransport.postJson(url, body, headers)
                    }
                }
            },
        )

    private suspend fun pageLoader(url: String): String =
        withContext(ioDispatcher) {
            requestScheduler.execute(
                url = url,
                foreground =
                    coroutineContext[ForegroundRequestPriorityKey] != null,
            ) {
                rawPageLoader(url)
            }
        }

    private suspend fun jsonPoster(url: String, body: String): String =
        withContext(ioDispatcher) {
            requestScheduler.execute(
                url = url,
                foreground =
                    coroutineContext[ForegroundRequestPriorityKey] != null,
            ) {
                rawJsonPoster(url, body)
            }
        }

    private suspend fun formPoster(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String>,
    ): String = withContext(ioDispatcher) {
        requestScheduler.execute(
            url = url,
            foreground =
                coroutineContext[ForegroundRequestPriorityKey] != null,
        ) {
            rawFormTransport.postForm(url, fields, headers)
        }
    }

    suspend fun home(
        onProgress: suspend (HomeContent) -> Unit = {},
    ): HomeContent = supervisorScope {
        val orderedTitles = baseHomeRailSpecs.map(TmdbHomeRailSpec::title) +
            GenreCatalog.homeSpecs.map(GenreSpec::title)
        val cachedHome = cacheStore?.loadHome()
        val cachedByTitle = cachedHome?.rails
            .orEmpty()
            .associateBy(ContentRail::title)
        val genreTitles = GenreCatalog.homeSpecs.map(GenreSpec::title).toSet()
        val genreSpecsByTitle = GenreCatalog.homeSpecs.associateBy(GenreSpec::title)
        val baseSpecsByTitle = baseHomeRailSpecs.associateBy(TmdbHomeRailSpec::title)
        val trendingTitles = baseHomeRailSpecs
            .filter(TmdbHomeRailSpec::isTrending)
            .mapTo(linkedSetOf(), TmdbHomeRailSpec::title)
        val candidates = linkedMapOf<String, ContentRail>()
        cachedHome?.rails.orEmpty().forEach { rail ->
            val expectedType = genreSpecsByTitle[rail.title]?.type
                ?: baseSpecsByTitle[rail.title]?.expectedType
            val baseSpec = baseSpecsByTitle[rail.title]
            val items = rail.items
                .asSequence()
                .filter { item -> expectedType == null || item.type == expectedType }
                .filter { item -> baseSpec?.isTrending != true || isSafeTrendingItem(item) }
                .distinctBy(Media::key)
                .toList()
            val acceptable = if (rail.title in genreTitles) {
                items.size >= MIN_GENRE_RAIL_ITEMS
            } else {
                items.size >= (baseSpec?.minimumItems ?: 1)
            }
            if (acceptable) candidates[rail.title] = rail.copy(items = items)
        }

        suspend fun snapshot(): HomeContent {
            val orderedCandidates = orderedTitles.mapNotNull(candidates::get)
            val genrePriority = GenreCatalog.homeSpecs
                .sortedWith(
                    compareBy<GenreSpec> { spec ->
                        candidates[spec.title]?.items?.size ?: Int.MAX_VALUE
                    }.thenByDescending { spec ->
                        if (spec.matchMode == GenreMatchMode.ALL) spec.genreIds.size else 0
                    }.thenBy(GenreSpec::title),
                )
                .map(GenreSpec::title)
            val rails = allocateUniqueHomeRails(
                rails = orderedCandidates,
                priorityTitles = trendingTitles.toList() +
                    genrePriority +
                    baseHomeRailSpecs
                        .filterNot(TmdbHomeRailSpec::isTrending)
                        .map(TmdbHomeRailSpec::title),
                itemLimit = HOME_RAIL_LIMIT,
            ).filter { rail ->
                when (rail.title) {
                    in genreTitles -> rail.items.size >= MIN_GENRE_RAIL_ITEMS
                    in trendingTitles -> rail.items.size >= MIN_TRENDING_RAIL_ITEMS
                    else -> rail.items.isNotEmpty()
                }
            }
            val hero = rails.firstNotNullOfOrNull { rail ->
                rail.items.firstOrNull { it.backdropPath != null }
            } ?: rails.firstNotNullOfOrNull { it.items.firstOrNull() }
                ?: cachedHome?.hero
                ?: fallbackItems.first()
            return HomeContent(
                hero = hero,
                rails = rails.ifEmpty { fallbackRails },
            )
        }

        fun isCompleteGenreSnapshot(content: HomeContent): Boolean {
            val railsByTitle = content.rails.associateBy(ContentRail::title)
            val allGenresComplete = GenreCatalog.homeSpecs.all { spec ->
                railsByTitle[spec.title]?.items?.let { items ->
                    items.size >= MIN_GENRE_RAIL_ITEMS &&

                        items.all { item -> item.type == spec.type }
                } == true
            }
            val keys = content.rails.flatMap(ContentRail::items).map(Media::key)
            return allGenresComplete && keys.size == keys.distinct().size

        }

        fun recordShownHome(content: HomeContent) {
            homeShownKeys = buildSet {
                add(content.hero.key)
                content.rails.forEach { rail ->
                    rail.items.forEach { item -> add(item.key) }
                }
            }
        }

        suspend fun emitProgress(content: HomeContent) {
            recordShownHome(content)
            onProgress(content)
        }

        val cachedSnapshot = if (candidates.isNotEmpty()) snapshot() else null
        val completeCachedSnapshot = cachedSnapshot?.takeIf(::isCompleteGenreSnapshot)
        if (cachedSnapshot != null) emitProgress(cachedSnapshot)

        val progressMutex = Mutex()
        val requestGate = Semaphore(HOME_CONCURRENT_REQUESTS)
        val jobs = buildList {
            baseHomeRailSpecs.forEach { spec ->
                add(
                    async {
                        val rail = requestGate.withPermit {
                            fetchBaseRail(spec, cachedByTitle[spec.title])
                        } ?: return@async
                        progressMutex.withLock {
                            candidates[spec.title] = rail
                            val partial = snapshot()
                            emitProgress(
                                completeCachedSnapshot
                                    ?.takeUnless { isCompleteGenreSnapshot(partial) }
                                    ?: partial,
                            )
                        }
                    },
                )
            }
            GenreCatalog.homeSpecs.forEach { spec ->
                add(
                    async {
                        val rail = requestGate.withPermit {
                            fetchGenreRail(
                                spec = spec,
                                targetSize = if (
                                    spec.matchMode == GenreMatchMode.ALL &&
                                    spec.genreIds.size > 1
                                ) {
                                    HOME_COMPOUND_GENRE_CANDIDATE_TARGET
                                } else {
                                    HOME_GENRE_CANDIDATE_TARGET
                                },
                                minimumSize = MIN_GENRE_RAIL_ITEMS,
                                cached = cachedByTitle[spec.title]?.items.orEmpty(),
                            )
                        } ?: return@async
                        progressMutex.withLock {
                            candidates[spec.title] = rail
                            val partial = snapshot()
                            emitProgress(
                                completeCachedSnapshot
                                    ?.takeUnless { isCompleteGenreSnapshot(partial) }
                                    ?: partial,
                            )
                        }
                    },
                )
            }
        }
        jobs.awaitAll()

        val refreshedContent = snapshot()
        val content = completeCachedSnapshot
            ?.takeUnless { isCompleteGenreSnapshot(refreshedContent) }
            ?: refreshedContent
        recordShownHome(content)
        catalogue = content.rails
            .flatMap(ContentRail::items)
            .distinctBy(Media::key)
            .ifEmpty { fallbackItems }
        if (isCompleteGenreSnapshot(refreshedContent)) {
            cacheStore?.saveHome(refreshedContent)
        }
        content
    }

    private suspend fun fetchBaseRail(
        spec: TmdbHomeRailSpec,
        cached: ContentRail?,
    ): ContentRail? {
        val candidateTarget = if (spec.isTrending) {
            HOME_TRENDING_CANDIDATE_TARGET
        } else {
            HOME_BASE_CANDIDATE_TARGET
        }
        val pagesPerPath = if (spec.isTrending) {
            HOME_TRENDING_MAX_PAGES_PER_PATH
        } else {
            HOME_BASE_MAX_PAGES
        }
        val fresh = linkedMapOf<String, Media>()
        val paths = listOf(spec.path) + spec.alternatePaths
        paths.forEach { path ->
            if (fresh.size >= candidateTarget) return@forEach
            val pathPageLimit = if (path.startsWith("/discover/")) 1 else pagesPerPath
            for (page in 1..pathPageLimit) {
                val separator = if ("?" in path) "&" else "?"
                loadSearchPageWithRetry("${path}${separator}page=$page")
                    .asSequence()
                    .filter { item ->
                        spec.expectedType == null || item.type == spec.expectedType
                    }
                    .filter { item -> !spec.isTrending || isSafeTrendingItem(item) }
                    .forEach { item -> fresh.putIfAbsent(item.key, item) }
                if (fresh.size >= candidateTarget) break
            }
        }
        val cachedItems = cached?.items
            .orEmpty()
            .asSequence()
            .filter { item -> spec.expectedType == null || item.type == spec.expectedType }
            .filter { item -> !spec.isTrending || isSafeTrendingItem(item) }
            .toList()
        val items = (fresh.values + cachedItems)
            .distinctBy(Media::key)
            .take(candidateTarget)
        return items.takeIf { it.size >= spec.minimumItems }?.let {
            ContentRail(spec.title, it)
        }
    }


    private suspend fun fetchGenreRail(
        spec: GenreSpec,
        targetSize: Int,
        minimumSize: Int,
        cached: List<Media>,
    ): ContentRail? {

        val fetched = fetchGenreItemsWithRecovery(
            spec = spec,
            startPage = HOME_GENRE_START_PAGE,
            maxPages = if (
                spec.matchMode == GenreMatchMode.ALL && spec.genreIds.size > 1
            ) {
                HOME_COMPOUND_GENRE_MAX_PAGES
            } else {
                HOME_GENRE_MAX_PAGES
            },
            targetSize = targetSize,
            minimumSize = minimumSize,
            excludedKeys = emptySet(),
            cached = cached,
        ) ?: return null
        return ContentRail(spec.title, fetched.items)
    }

    private suspend fun loadSearchPageWithRetry(path: String): List<Media> {
        repeat(CATALOG_REQUEST_ATTEMPTS) { attempt ->
            try {
                val separator = if ("?" in path) "&" else "?"
                val parsed = parseSearchResults(
                    pageLoader("$TMDB_SITE_URL$path${separator}language=en-US"),
                )
                if (parsed.isNotEmpty()) return parsed
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Retry below.
            }
            if (attempt < CATALOG_REQUEST_ATTEMPTS - 1) {
                delay(CATALOG_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return emptyList()
    }

    private suspend fun fetchGenreItemsWithRecovery(
        spec: GenreSpec,
        startPage: Int,
        maxPages: Int,
        targetSize: Int,
        minimumSize: Int,
        excludedKeys: Set<String>,
        cached: List<Media> = emptyList(),
    ): GenreFetch? {
        repeat(GENRE_ASSEMBLY_ATTEMPTS) { attempt ->
            val result = fetchGenreItems(
                spec = spec,
                startPage = startPage,
                maxPages = maxPages,
                targetSize = targetSize,
                minimumSize = minimumSize,
                excludedKeys = excludedKeys,
                cached = cached,
            )
            if (result != null) return result
            if (attempt < GENRE_ASSEMBLY_ATTEMPTS - 1) {
                delay(GENRE_ASSEMBLY_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return null
    }

    private suspend fun fetchGenreItems(
        spec: GenreSpec,
        startPage: Int,
        maxPages: Int,
        targetSize: Int,
        minimumSize: Int,
        excludedKeys: Set<String>,
        cached: List<Media> = emptyList(),
    ): GenreFetch? {
        if (spec.genreIds.isEmpty()) return null
        val pools = spec.genreIds.associateWith { linkedMapOf<String, Media>() }
        var combined = emptyList<Media>()
        var pagesLoaded = 0

        for (page in startPage until startPage + maxPages) {
            spec.genreIds.forEach { genreId ->
                loadVerifiedGenrePageWithRetry(spec.type, genreId, page)
                    .forEach { item -> pools.getValue(genreId).putIfAbsent(item.key, item) }
            }
            pagesLoaded += 1
            combined = combineGenrePools(spec, pools)
                .filterNot { item -> item.key in excludedKeys }
                .distinctBy(Media::key)
            if (combined.size >= targetSize) break
        }
        val merged = (combined + cached)
            .asSequence()
            .filter { item -> item.type == spec.type && item.key !in excludedKeys }
            .distinctBy(Media::key)
            .take(targetSize)
            .toList()
        return merged
            .takeIf { items -> items.size >= minimumSize }
            ?.let { items -> GenreFetch(items, pagesLoaded) }
    }

    private fun combineGenrePools(
        spec: GenreSpec,
        pools: Map<Int, LinkedHashMap<String, Media>>,
    ): List<Media> {
        val labels = spec.genreIds.mapNotNull { genreId ->
            GenreCatalog.labelFor(genreId, spec.type)
        }
        return when (spec.matchMode) {
            GenreMatchMode.ALL -> {
                val first = pools[spec.genreIds.first()]?.values.orEmpty()
                val commonKeys = spec.genreIds.drop(1).fold(
                    first.mapTo(linkedSetOf(), Media::key),
                ) { common, genreId ->
                    common.apply {
                        retainAll(pools[genreId]?.keys.orEmpty())
                    }
                }
                first.filter { item -> item.key in commonKeys }
                    .map { item -> item.copy(genres = (labels + item.genres).distinct()) }
            }
            GenreMatchMode.ANY -> {
                val sourceItems = spec.genreIds.map { genreId ->
                    val label = GenreCatalog.labelFor(genreId, spec.type)
                    pools[genreId]?.values.orEmpty().map { item ->
                        if (label == null) item else item.copy(
                            genres = (listOf(label) + item.genres).distinct(),
                        )
                    }
                }
                val interleaved = linkedMapOf<String, Media>()
                val longestSource = sourceItems.maxOfOrNull(List<Media>::size) ?: 0
                repeat(longestSource) { index ->

                    sourceItems.forEach { items ->
                        val item = items.getOrNull(index) ?: return@forEach
                        val existing = interleaved[item.key]
                        interleaved[item.key] = if (existing == null) {
                            item
                        } else {
                            existing.copy(

                                genres = (existing.genres + item.genres).distinct(),
                            )
                        }
                    }
                }
                interleaved.values.toList()
            }
        }
    }

    private suspend fun loadVerifiedGenrePageWithRetry(
        type: MediaType,
        genreId: Int,
        page: Int,
    ): List<Media> {
        val path = GenreCatalog.pagePath(genreId, type) ?: return emptyList()
        val cacheKey = "${type.routeName}:$genreId:$page"
        verifiedGenrePageCache[cacheKey]?.let { return it }
        val pageLock = verifiedGenrePageLocks.computeIfAbsent(cacheKey) { Mutex() }
        return pageLock.withLock {
            verifiedGenrePageCache[cacheKey]?.let { return@withLock it }
            repeat(CATALOG_REQUEST_ATTEMPTS) { attempt ->
                try {
                    val html = pageLoader(
                        "$TMDB_SITE_URL$path?page=$page&language=en-US",
                    )
                    val document = Jsoup.parse(html, TMDB_SITE_URL)
                    val canonicalPath = document
                        .selectFirst("link[rel=canonical][href]")
                        ?.attr("abs:href")
                        ?.takeIf(String::isNotBlank)
                        ?.let { href -> runCatching { URL(href).path }.getOrNull() }
                    if (canonicalPath?.trimEnd('/') != path.trimEnd('/')) {
                        throw IOException("Genre source redirected to a generic catalogue page.")
                    }
                    val label = GenreCatalog.labelFor(genreId, type)
                    val parsed = parseSearchResults(html)
                        .asSequence()
                        .filter { item -> item.type == type }
                        .map { item ->
                            if (label == null) item else item.copy(
                                genres = (listOf(label) + item.genres).distinct(),
                            )
                        }
                        .distinctBy(Media::key)
                        .toList()
                    if (parsed.isEmpty()) {
                        throw IOException("Genre source returned no verified titles.")
                    }
                    verifiedGenrePageCache[cacheKey] = parsed
                    return@withLock parsed
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (attempt < CATALOG_REQUEST_ATTEMPTS - 1) {
                        delay(CATALOG_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
            emptyList()
        }
    }

    suspend fun search(query: String): List<Media> = withContext(computationDispatcher) {
        supervisorScope {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@supervisorScope emptyList()
            val online = searchTmdb(cleanQuery)

            val source = online.ifEmpty { localSearch(cleanQuery) }
            val sorted = CatalogueSearchRanker.rank(cleanQuery, source).take(80)
            if (online.isNotEmpty()) {
                catalogue = (online + catalogue).distinctBy(Media::key)
            }
            sorted
        }
    }

    /**
     * Retrieves one deterministic catalogue page. Structured providers are
     * authoritative; web and indexed Reddit results are supplemental only.
     */
    suspend fun browseGenre(
        genre: String,

        type: MediaType,
    ): List<Media> = withContext(computationDispatcher) {
        val spec = GenreCatalog.specFor(genre, type) ?: return@withContext emptyList()
        val cursorKey = "${type.routeName}:${spec.genreIds.sorted()}:${spec.matchMode}"
        val startPage = genreBrowsePageCursor.getOrDefault(
            cursorKey,
            GENRE_BROWSE_START_PAGE,
        )
        val excluded = buildSet {
            catalogue.forEach { item -> add(item.key) }
            addAll(homeShownKeys)
            addAll(genreBrowseSeenKeys)
        }
        val fetched = fetchGenreItemsWithRecovery(
            spec = spec,
            startPage = startPage,
            maxPages = GENRE_BROWSE_MAX_PAGES,
            targetSize = GENRE_PAGE_TARGET,
            minimumSize = GENRE_PAGE_TARGET,
            excludedKeys = excluded,
        ) ?: throw IOException(
            "No more unseen ${if (type == MediaType.MOVIE) "movie" else "series"} " +
            "titles are available in this genre yet.",
        )
        genreBrowsePageCursor[cursorKey] = startPage + fetched.pagesLoaded
        genreBrowseSeenKeys += fetched.items.map(Media::key)
        catalogue = (fetched.items + catalogue).distinctBy(Media::key)
        fetched.items
    }

    private suspend fun searchTmdb(
        query: String,
        types: List<String> = listOf("movie", "tv"),
        retryEmpty: Boolean = false,
    ): List<Media> = when (
        val outcome = searchTmdbOutcome(query, types, retryEmpty)
    ) {
        is TmdbSearchOutcome.Success -> outcome.items
        is TmdbSearchOutcome.Unavailable -> emptyList()
    }

    private suspend fun searchTmdbOutcome(
        query: String,
        types: List<String>,
        retryEmpty: Boolean,
    ): TmdbSearchOutcome = supervisorScope {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString(),
        )
        val typeOutcomes = types.map { type ->
            async {
                val cacheKey = "$type:${normalizeText(query)}"
                tmdbSearchCache[cacheKey]?.let {
                    return@async TmdbSearchOutcome.Success(it)
                }
                var requestSucceeded = false
                var lastFailure: Throwable? = null
                repeat(if (retryEmpty) PLOT_TMDB_SEARCH_ATTEMPTS else 1) { attempt ->
                    try {
                        val html = pageLoader(
                            "$TMDB_SITE_URL/search/$type" +
                                "?query=$encoded&language=en-US",
                        )
                        if (html.isBlank() ||
                            TMDB_WAF_MARKERS.any(html.lowercase()::contains)

                        ) {
                            throw TmdbCatalogSourceException(
                                "TMDB title search returned an invalid response.",
                            )
                        }
                        requestSucceeded = true
                        val parsed = parseSearchResults(
                            html,
                        ).filter { item -> item.type.routeName == type }
                        if (parsed.isNotEmpty()) {
                            tmdbSearchCache[cacheKey] = parsed
                            return@async TmdbSearchOutcome.Success(parsed)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: CatalogSourceException) {
                        lastFailure = error
                    } catch (error: IOException) {
                        lastFailure = TmdbCatalogSourceException(
                            "TMDB title search is unavailable.",
                            error,
                        )
                    }
                    if (retryEmpty && attempt < PLOT_TMDB_SEARCH_ATTEMPTS - 1) {
                        delay(PLOT_TMDB_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
                if (requestSucceeded) {
                    TmdbSearchOutcome.Success(emptyList())
                } else {
                    TmdbSearchOutcome.Unavailable(lastFailure)
                }
            }
        }.awaitAll()
        val successfulGroups = typeOutcomes
            .filterIsInstance<TmdbSearchOutcome.Success>()
            .map(TmdbSearchOutcome.Success::items)
        if (successfulGroups.isEmpty()) {
            return@supervisorScope TmdbSearchOutcome.Unavailable(
                typeOutcomes
                    .filterIsInstance<TmdbSearchOutcome.Unavailable>()
                    .firstNotNullOfOrNull(TmdbSearchOutcome.Unavailable::cause),
            )

        }
        val largestGroup = successfulGroups.maxOfOrNull(List<Media>::size) ?: 0
        TmdbSearchOutcome.Success(
            (0 until largestGroup)
            .flatMap { index ->
                successfulGroups.mapNotNull { results -> results.getOrNull(index) }
            }
                .distinctBy(Media::key),
        )
    }

    private suspend fun predictiveTitleSuggestion(
        query: String,
        intent: SearchRanker.SearchIntent,
    ): String? = suspendOrNull {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString(),
        )
        val candidates = JSONObject(
            pageLoader("$IMDB_SUGGESTION_URL/$encoded.json"),
        ).optJSONArray("d")

        candidates?.let { values ->
            (0 until values.length())
                .mapNotNull { index -> values.optJSONObject(index) }
                .asSequence()
                .filter { candidate -> candidate.optString("id").startsWith("tt") }
                .mapNotNull { candidate ->
                    val title = candidate.optString("l").trim()
                        .takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val qualifier = candidate.optString("q").lowercase()
                    val type = when {
                        "tv" in qualifier || "series" in qualifier -> MediaType.TV
                        "feature" in qualifier || "movie" in qualifier -> MediaType.MOVIE
                        else -> intent.type ?: MediaType.MOVIE
                    }
                    if (intent.type != null && type != intent.type) {
                        return@mapNotNull null
                    }
                    val year = candidate.optInt("y").takeIf { it > 0 }
                    if (intent.year != null && year != null && year != intent.year) {
                        return@mapNotNull null
                    }
                    val suggestion = Media(
                        id = candidate.optString("id").hashCode(),
                        type = type,
                        title = title,
                        year = year?.toString().orEmpty(),
                    )
                    title to SearchRanker.confidence(intent, suggestion)
                }
                .firstOrNull { (_, confidence) ->
                    confidence.ordinal >= SearchRanker.SearchConfidence.LIKELY.ordinal
                }
                ?.first
        }
    }

    private fun Media.matchesExplicitQualifiers(
        intent: SearchRanker.SearchIntent,
    ): Boolean {
        if (intent.type != null && type != intent.type) return false
        if (intent.year != null) {
            val itemYear = fourDigitYear.find(year)?.value?.toIntOrNull()
            if (itemYear != intent.year) return false
        }
        return true
    }

    suspend fun details(
        item: Media,
        nativeMetadata: Boolean = false,
        onProgress: suspend (Media, List<Media>?) -> Unit,
    ) = supervisorScope {
        val current = if (nativeMetadata) item else catalogue.firstOrNull { it.key == item.key } ?: item
        val pageHtml = if (nativeMetadata) null else suspendOrNull {
            pageLoader(
                "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
            )
        }
        val metadata = (pageHtml?.let {
            runCatching { parseTitleDetails(it, current) }.getOrDefault(current)
        } ?: current).let { parsed ->
            parsed.copy(imdbId = parsed.imdbId ?: current.imdbId ?: item.imdbId)
        }
        val metadataWithPendingRatings = metadata.copy(
            imdbRatingState = when {
                metadata.imdbRating != null -> metadata.imdbRatingState ?: RatingSourceState.VERIFIED
                metadata.imdbRatingState == RatingSourceState.NOT_RATED -> RatingSourceState.NOT_RATED
                else -> RatingSourceState.LOADING
            },
            rottenTomatoesState = when {
                metadata.rottenTomatoesRating != null -> metadata.rottenTomatoesState ?: RatingSourceState.VERIFIED
                metadata.rottenTomatoesState == RatingSourceState.NOT_RATED -> RatingSourceState.NOT_RATED
                else -> RatingSourceState.LOADING
            },

        )
        val pageRecommendations = pageHtml?.let {
            runCatching { parseRelatedResults(it) }.getOrDefault(emptyList())
        }.orEmpty()

        val locallyRelated = RelatedContentEngine.rank(
            source = metadataWithPendingRatings,
            candidates = catalogue,
            candidateScoreLimit = RECOMMENDATION_LOCAL_RELATED_SCORE_LIMIT,
            resultLimit = 18,
        )
        val recommendations = (pageRecommendations + locallyRelated)
            .filter { it.type == item.type && it.key != item.key }
            .distinctBy(Media::key)
            .take(18)

        catalogue = (listOf(metadataWithPendingRatings) + catalogue.filterNot { it.key == metadata.key })
        onProgress(metadataWithPendingRatings, recommendations)

        var omdbEnriched = metadataWithPendingRatings
        suspend fun enrichOmdb() {
            if (metadata.imdbId?.matches(Regex("tt\\d{5,12}")) == true) {
                try {
                    val req = com.aliflix.app.data.omdb.OmdbLookupRequest(
                        imdbId = metadata.imdbId,
                        title = metadata.title,
                        year = fourDigitYear.find(metadata.year)?.value?.toIntOrNull(),
                        mediaType = metadata.type.routeName,
                    )
                    val omdbMeta = omdbClient.lookup(req)
                    if (omdbMeta != null && omdbMeta.found) {
                        omdbEnriched = (if (nativeMetadata) catalogue.firstOrNull { it.key == metadata.key } ?: omdbEnriched else omdbEnriched).mergeWithOmdb(omdbMeta)
                        catalogue = listOf(omdbEnriched) + catalogue.filterNot { it.key == omdbEnriched.key }
                        onProgress(omdbEnriched, recommendations)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {}
            }

        }
        val omdbJob = if (nativeMetadata) async { enrichOmdb() } else { enrichOmdb(); null }

        val imdbJob = async {
            try {
                val current = catalogue.firstOrNull { it.key == metadata.key } ?: omdbEnriched

                val imdbSnapshot = imdbRatingRepository.ratingFor(current)
                val enriched = catalogue.firstOrNull { it.key == current.key }?.copy(
                    imdbId = imdbSnapshot.identity.imdbId.takeIf { it.isNotBlank() } ?: current.imdbId,
                    imdbRating = imdbSnapshot.rating,
                    imdbVoteCount = imdbSnapshot.voteCount,
                    imdbRatingState = imdbSnapshot.state,
                )
                if (enriched != null) {
                    catalogue = (listOf(enriched) + catalogue.filterNot { it.key == enriched.key })
                    onProgress(enriched, recommendations)
                    enriched.imdbRating?.let { imdbRatingsCache[enriched.key] = it }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                val current = catalogue.firstOrNull { it.key == metadata.key } ?: omdbEnriched
                val unavailable = current.copy(imdbRatingState = RatingSourceState.UNAVAILABLE)
                catalogue = listOf(unavailable) + catalogue.filterNot { it.key == unavailable.key }
                onProgress(unavailable, recommendations)
            }
        }

        val rtJob = async {
            try {
                val current = catalogue.firstOrNull { it.key == metadata.key } ?: omdbEnriched
                if (current.rottenTomatoesRating != null && current.rottenTomatoesState == RatingSourceState.VERIFIED) {
                    return@async
                }

                suspend fun publish(snapshot: RottenTomatoesSnapshot) {
                    val enriched = catalogue.firstOrNull { it.key == current.key }?.copy(
                        rottenTomatoesRating = snapshot.rating,
                        rottenTomatoesState = snapshot.state,
                    ) ?: return
                    catalogue = listOf(enriched) + catalogue.filterNot { it.key == enriched.key }
                    onProgress(enriched, recommendations)
                }

                val memory = rottenTomatoesRatingsCache[current.key]
                if (memory != null && memory.state in setOf(RatingSourceState.VERIFIED, RatingSourceState.NOT_RATED)) {
                    publish(memory)
                    return@async
                }
                val persisted = cacheStore?.loadRottenTomatoesRating(current.key, 30L * 24 * 60 * 60 * 1000)
                if (persisted != null) {
                    publish(persisted)
                    if (persisted.state != RatingSourceState.STALE) {
                        rottenTomatoesRatingsCache[current.key] = persisted
                        return@async
                    }
                }

                val rt = rottenTomatoesClient.loadRating(current)
                publish(rt)
                if (rt.state in setOf(RatingSourceState.VERIFIED, RatingSourceState.STALE, RatingSourceState.NOT_RATED)) {
                    rottenTomatoesRatingsCache[current.key] = rt
                    cacheStore?.saveRottenTomatoesRating(current.key, rt)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                val current = catalogue.firstOrNull { it.key == metadata.key } ?: omdbEnriched
                val unavailable = current.copy(rottenTomatoesState = RatingSourceState.UNAVAILABLE)
                catalogue = listOf(unavailable) + catalogue.filterNot { it.key == unavailable.key }
                onProgress(unavailable, recommendations)
            }
        }

        val reviewsJob = async {
            try {
                val reviewsHtml = pageLoader(
                    "$TMDB_SITE_URL/${item.type.routeName}/${item.id}/reviews?language=en-US",
                )
                val baseReviews = parseReviews(reviewsHtml).ifEmpty { metadata.reviews }
                if (baseReviews.isNotEmpty()) {
                    val fullReviews = baseReviews.map { review ->
                        async {
                            val fullHtml = suspendOrNull { pageLoader("$TMDB_SITE_URL/review/${review.id}") }
                            val fullText = fullHtml?.let { parseSingleReview(it) }
                            if (!fullText.isNullOrBlank()) {
                                review.copy(content = fullText)
                            } else {
                                review
                            }
                        }
                    }.awaitAll()

                    val current = catalogue.firstOrNull { it.key == metadata.key } ?: omdbEnriched
                    val enriched = current.copy(
                        reviews = (fullReviews + current.reviews).distinctBy { it.id },
                    )
                    catalogue = listOf(enriched) + catalogue.filterNot { it.key == enriched.key }
                    onProgress(enriched, recommendations)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {}
        }


        imdbJob.await()
        rtJob.await()
        reviewsJob.await()
        omdbJob?.await()
    }

    suspend fun mobileEpisodeRatings(item: Media, season: Int, episodes: List<Episode>): List<Episode> {
        val ratings = imdbRatingRepository.ratingsForEpisodes(item, season, episodes)
        return episodes.map { episode -> ratings[episode.number]?.let { rating ->
            episode.copy(imdbId = rating.imdbId, imdbRating = rating.rating, imdbVoteCount = rating.voteCount, imdbRatingState = rating.state)
        } ?: episode.copy(imdbRatingState = RatingSourceState.UNAVAILABLE) }
    }

    suspend fun seasons(item: Media): List<Season> {
        if (item.type != MediaType.TV) return emptyList()
        return suspendOrDefault(emptyList()) {
            parseSeasons(
                html = pageLoader("$TMDB_SITE_URL/tv/${item.id}/seasons?language=en-US"),
                mediaId = item.id,
            )
        }
    }

    suspend fun episodes(
        item: Media,
        seasonNumber: Int,
        onProgress: suspend (List<Episode>) -> Unit = {},
    ): List<Episode> = supervisorScope {
        if (item.type != MediaType.TV) return@supervisorScope emptyList()
        var result = suspendOrDefault(emptyList()) {
            parseEpisodes(
                html = pageLoader(
                    "$TMDB_SITE_URL/tv/${item.id}/season/$seasonNumber?language=en-US",
                ),
                mediaId = item.id,
                seasonNumber = seasonNumber,
            )
        }
        if (result.isEmpty()) {
            result = suspendOrDefault(emptyList()) {
                kotlinx.coroutines.delay(600L)
                parseEpisodes(
                    html = pageLoader(
                        "$TMDB_SITE_URL/tv/${item.id}/season/$seasonNumber?language=en-US",
                    ),
                    mediaId = item.id,
                    seasonNumber = seasonNumber,
                )
            }
        }
        if (result.isEmpty()) return@supervisorScope emptyList()

        var enriched = result.map { episode ->
            episode.copy(
                imdbRatingState = RatingSourceState.LOADING,
            )
        }
        onProgress(enriched)

        val imdbRatings = try {
            imdbRatingRepository.ratingsForEpisodes(item, seasonNumber, enriched)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyMap()
        }

        enriched = enriched.map { episode ->
            val rating = imdbRatings[episode.number]
            episode.copy(
                imdbId = rating?.imdbId,
                imdbRating = rating?.rating,
                imdbVoteCount = rating?.voteCount,
                imdbRatingState = rating?.state ?: RatingSourceState.UNAVAILABLE,
            )
        }
        onProgress(enriched)
        enriched
    }

    internal fun parsePage(html: String, titlePrefix: String = ""): ParsedPage {

        val document = Jsoup.parse(html, SITE_URL)
        val rails = document
            .select("section[aria-label=Carousel of shows]")
            .mapNotNull { section ->
                val title = section.selectFirst("h2")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val items = section.select("picture").mapNotNull { picture ->
                    val link = picture.selectFirst("a[href^=/movie/], a[href^=/tv/]")
                        ?: return@mapNotNull null
                    val route = link.attr("href")
                    val match = titleRoute.matchEntire(route) ?: return@mapNotNull null
                    val image = picture.selectFirst("img")
                    val mediaTitle = image?.attr("alt")?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: link.attr("aria-label").trim()
                    if (mediaTitle.isBlank()) return@mapNotNull null
                    Media(
                        id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                        type = MediaType.from(match.groupValues[1]),
                        title = mediaTitle,
                        posterPath = image?.attr("src")?.takeIf(String::isNotBlank),
                    )
                }.distinctBy(Media::key)
                ContentRail("$titlePrefix$title", items)
            }

        val allItems = rails.flatMap(ContentRail::items)
        return ParsedPage(
            hero = parseHero(document, allItems),
            rails = rails,
        )
    }

    private fun parseHero(document: Document, items: List<Media>): Media? {
        val section = document.selectFirst("section[aria-label=Hero]") ?: return null
        val route = section.selectFirst("a[href^=/watch/movie/], a[href^=/watch/tv/]")
            ?.attr("href")
            ?: return null
        val match = watchRoute.matchEntire(route) ?: return null
        val id = match.groupValues[2].toIntOrNull() ?: return null
        val type = MediaType.from(match.groupValues[1])
        val existing = items.firstOrNull { it.id == id && it.type == type }
        val title = section.selectFirst("h1")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: existing?.title
            ?: return null
        val backdrop = section.selectFirst("img[src]")?.attr("src")?.takeIf(String::isNotBlank)
        val paragraphs = section.select("p").map { it.text().trim() }
        return (existing ?: Media(id = id, type = type, title = title)).copy(
            title = title,
            overview = paragraphs.firstOrNull { it.length > 45 }.orEmpty(),
            backdropPath = backdrop ?: existing?.posterPath,
            year = paragraphs.firstOrNull { yearText.matches(it) }?.take(4).orEmpty(),
            rating = paragraphs.firstNotNullOfOrNull {
                matchPercent.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            }?.div(10.0) ?: existing?.rating ?: 0.0,
        )
    }

    private fun parseTmdbCatalogueResponse(
        html: String,
        expectedType: MediaType,
    ): TmdbCatalogueResponse {
        if (html.isBlank()) {
            return TmdbCatalogueResponse.Unavailable(
                TmdbCatalogSourceException("TMDB catalogue response was empty."),
            )
        }
        val normalized = html.lowercase()
        if (TMDB_WAF_MARKERS.any(normalized::contains)) {
            return TmdbCatalogueResponse.Unavailable(
                TmdbCatalogSourceException(
                    "TMDB catalogue request was blocked by an upstream challenge.",
                ),
            )
        }
        if (TMDB_EXPLICIT_EMPTY_MESSAGE.lowercase() in normalized) {
            return TmdbCatalogueResponse.Empty
        }
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val rawCards = document.select("div[data-object-id]")
        val hasNextPage = parseTmdbHasNextPage(document)
        val items = parseSearchResults(html)
            .filter { it.type == expectedType }
        if (items.isNotEmpty()) {
            return TmdbCatalogueResponse.Results(
                items = items,
                hasNextPage = hasNextPage,
                rawItemCount = rawCards.size,
            )
        }
        if (rawCards.isNotEmpty() && rawCards.all(::isAdultTmdbCard)) {
            return TmdbCatalogueResponse.Results(
                items = emptyList(),
                hasNextPage = hasNextPage,
                rawItemCount = rawCards.size,
            )
        }
        return TmdbCatalogueResponse.Unavailable(

            TmdbCatalogSourceException(
                "TMDB catalogue response did not contain valid " +
                    "${expectedType.routeName} cards.",
            ),
        )
    }

    internal fun parseSearchResults(html: String): List<Media> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        return document.select("div[data-object-id]")
            .filterNot(::isAdultTmdbCard)
            .mapNotNull { card ->
            val titleLink = card.select("a[href]").firstOrNull { link ->
                tmdbTitleRoute.find(link.attr("href")) != null &&
                    (
                        link.selectFirst("h2, h3") != null ||
                            link.hasAttr("data-media-type") ||
                            link.attr("class").split(' ').any {
                                it == "result" || it == "title"
                            }
                        )
            } ?: card.select("a[href]").firstOrNull { link ->
                tmdbTitleRoute.find(link.attr("href")) != null
            }
                ?: return@mapNotNull null
            val match = tmdbTitleRoute.find(titleLink.attr("href")) ?: return@mapNotNull null
            val heading = titleLink.selectFirst("h2, h3")
                ?: card.selectFirst("h2, h3")
            val title = sequenceOf(
                heading?.selectFirst("span")?.text(),
                heading?.text(),
                titleLink.attr("title"),
                card.selectFirst("img[alt]")?.attr("alt"),
            ).filterNotNull()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                ?: return@mapNotNull null
            val poster = sequenceOf(
                card.selectFirst("img.poster")?.attr("src"),
                card.select("img[alt]").firstOrNull {

                    it.attr("alt").equals(title, ignoreCase = true)
                }?.attr("src"),
                card.selectFirst("img[src]")?.attr("src"),
                card.selectFirst("img[data-src]")?.attr("data-src"),
            ).filterNotNull().firstOrNull(String::isNotBlank)
            val tmdbRating = sequenceOf(
                card.selectFirst(".user_score_chart[data-percent]")?.attr("data-percent"),
                card.selectFirst("[data-percent]")?.attr("data-percent"),
                Regex("""\b(\d{1,3})%""").find(card.text())
                    ?.groupValues
                    ?.getOrNull(1),
            ).filterNotNull()
                .mapNotNull(String::toDoubleOrNull)
                .firstOrNull { it in 1.0..100.0 }
                ?.div(10.0)
            Media(
                id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                type = MediaType.from(match.groupValues[1]),
                title = title,
                overview = card.selectFirst("p")?.text()?.trim().orEmpty(),
                posterPath = normalizeTmdbImage(poster),
                backdropPath = normalizeTmdbImage(poster),
                year = fourDigitYear.find(
                    card.selectFirst(".release_date")?.text().orEmpty(),
                )?.value.orEmpty(),
                rating = tmdbRating ?: 0.0,
            )
        }.distinctBy(Media::key)
    }

    private fun isAdultTmdbCard(card: org.jsoup.nodes.Element): Boolean =
        card.attr("data-media-adult").equals("true", ignoreCase = true) ||
            card.select("[data-media-adult=true]").isNotEmpty()

    private fun parseTmdbHasNextPage(document: Document): Boolean? {
        val candidates = document.select(
            "a.next_page, a[rel=next], a.load_more, button.load_more, " +
                ".load_more a, .load_more button, [data-next-page]",
        )
        val hasActiveNext = candidates.any { element ->
            val disabled = element.hasAttr("disabled") ||
                element.attr("aria-disabled").equals("true", ignoreCase = true) ||
                "disabled" in element.classNames()
            if (disabled) {
                false
            } else {
                val nextValue = element.attr("data-next-page").trim()
                when {
                    nextValue.isNotBlank() ->
                        nextValue != "0" &&
                            !nextValue.equals("false", ignoreCase = true)
                    element.tagName() == "a" -> element.attr("href").isNotBlank()
                    element.tagName() == "button" -> true
                    else -> element.text().contains("load more", ignoreCase = true)
                }
            }
        }
        if (hasActiveNext) return true
        val hasPaginationMarker = candidates.isNotEmpty() ||
            document.select(
                ".pagination, .pagination_wrapper, .pagination-container, " +
                    "[data-role=pagination]",
            ).isNotEmpty()
        return false.takeIf { hasPaginationMarker }
    }

    internal fun parseRelatedResults(html: String): List<Media> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        return document.select(
            "section.recommendations div.item, " +
                "section.recommendations div.card, " +
                "#recommendation_waypoint div.item, " +
                "div.recommendations div.item",
        ).mapNotNull { card ->
            val link = card.select("a[href^=/movie/], a[href^=/tv/]")
                .firstOrNull { tmdbTitleRoute.find(it.attr("href")) != null }
                ?: return@mapNotNull null
            val match = tmdbTitleRoute.find(link.attr("href")) ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = sequenceOf(
                link.attr("title"),
                image?.attr("alt")?.removePrefix("Poster for "),
                card.selectFirst(".title, .name, h2, h3, p.title")?.text(),
                link.text(),
            ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank)
                ?: return@mapNotNull null
            val artwork = sequenceOf(
                image?.attr("src"),
                image?.attr("data-src"),
                image?.attr("data-original"),
            ).filterNotNull().firstOrNull(String::isNotBlank)
            Media(
                id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                type = MediaType.from(match.groupValues[1]),
                title = title,
                posterPath = normalizeTmdbImage(artwork),
                backdropPath = normalizeTmdbImage(artwork),
                year = fourDigitYear.find(card.text())?.value.orEmpty(),
            )
        }.distinctBy(Media::key)

    }

    internal fun parseTitleDetails(
        html: String,
        fallback: Media,
    ): Media {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val exactTitleRoute = Regex(
            "^/${fallback.type.routeName}/${fallback.id}(?:-[^/?]+)?(?:\\?.*)?$",
        )
        val title = document
            .select("main h2 a[href], section.header h2 a[href]")
            .firstOrNull { exactTitleRoute.matches(it.attr("href")) }
            ?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: fallback.title
        val overview = document
            .selectFirst("main .overview p, main div.overview, section.header_info .overview")
            ?.text()
            ?.trim()
            ?.takeIf { it.length > 20 }
            ?: fallback.overview
        val poster = document
            .selectFirst("main img.poster, section.header img.poster")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTmdbImage)
            ?: fallback.posterPath
        val socialImage = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTmdbImage)
        val year = document
            .selectFirst("main h2 .release_date, section.header h2 .release_date")
            ?.text()
            ?.let { fourDigitYear.find(it)?.value }
            ?: fallback.year
        val score = document
            .selectFirst("[data-percent].user_score_chart, .user_score_chart[data-percent]")

            ?.attr("data-percent")
            ?.toDoubleOrNull()
            ?.div(10.0)
            ?: fallback.rating
        val genres = document
            .select(
                "main section.header a[href^=/genre/], " +
                    "main section.header_info a[href^=/genre/], " +
                    "main div.header_info a[href^=/genre/]",
            )
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(6)
            .ifEmpty { fallback.genres }
        val cast = document
            .select("main ol.people li.card p a[href^=/person/]")
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(10)
            .ifEmpty { fallback.cast }
        val imdbId = document
            .select("a[href*=\"imdb.com/title/tt\"], a[href^=\"https://www.imdb.com/title/tt\"], a[href*=\"/title/tt\"], a.social_link[href*=\"imdb.com\"]")
            .firstNotNullOfOrNull { link ->
                imdbTitleIdPattern.find(link.attr("href"))
                    ?.groupValues
                    ?.getOrNull(1)
            }
            ?: imdbTitleIdPattern.find(html)?.groupValues?.getOrNull(1)
            ?: fallback.imdbId
        val runtime = document.selectFirst(".runtime")?.text()?.trim().orEmpty().ifBlank { fallback.runtime }
        val reviews = parseReviews(html).ifEmpty { fallback.reviews }
        return fallback.copy(
            title = title,
            overview = overview,
            posterPath = poster,
            backdropPath = socialImage ?: fallback.backdropPath ?: poster,
            year = year,
            rating = score,
            imdbId = imdbId,
            genres = genres,
            cast = cast,
            runtime = runtime,
            reviews = reviews,
        )
    }

    internal fun parseReviews(html: String): List<MediaReview> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val reviewIdPattern = Regex("/review/([a-f0-9]+)")
        val ratingNumberPattern = Regex("(\\d{1,3})")
        val reviewLinks = document.select("a[href*=\"/review/\"]")
        val result = mutableListOf<MediaReview>()
        val seenIds = mutableSetOf<String>()

        for (link in reviewLinks) {
            val href = link.attr("href")
            val id = reviewIdPattern.find(href)?.groupValues?.getOrNull(1) ?: continue
            if (!seenIds.add(id)) continue

            val card = link.parents().firstOrNull { it.hasClass("card") }
                ?: link.parents().firstOrNull { it.hasClass("content") }
                ?: link.parent()

            val author = card?.selectFirst("h3 a")?.text()?.removePrefix("A review by")?.trim()
                ?.ifBlank { null }
                ?: card?.selectFirst("h5 a")?.text()?.trim()?.ifBlank { null }
                ?: "Anonymous"

            val date = card?.selectFirst("h5")?.text()?.let { h5Text ->
                Regex("on\\s+([A-Za-z]+\\s+\\d{1,2},\\s+\\d{4})").find(h5Text)?.groupValues?.getOrNull(1)
            }

            val avatarPath = card?.selectFirst("div.avatar img, img.avatar, img[src*=gravatar], img[src*=themoviedb]")?.attr("src")?.takeIf { it.isNotBlank() }

            val rating = card?.selectFirst(".rating_border.rating, .rating")?.text()?.let { text ->
                ratingNumberPattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { it / 10.0 }
            }

            val rawContent = card?.selectFirst(".teaser, .review_container")?.text()?.trim()
                ?.ifBlank { null }
                ?: card?.select("p")?.map { it.text().trim() }?.filter { it.isNotBlank() }?.joinToString("\n\n")
                ?: ""
            val content = rawContent
                .replace(Regex("(?i)\\.{2,}\\s*read the rest\\.?"), "")
                .replace(Regex("(?i)read the rest\\.?"), "")
                .trim()

            if (content.isNotBlank()) {
                result.add(
                    MediaReview(
                        id = id,
                        author = author,
                        avatarPath = avatarPath,
                        rating = rating,
                        content = content,
                        createdAt = date,
                        url = if (href.startsWith("http")) href else "$TMDB_SITE_URL$href",
                    )
                )

            }
        }
        return result
    }

    internal fun parseSingleReview(html: String): String? {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val contentContainer = document.selectFirst("div.flex-1 div.content, main div.content, div.review_container")
        val paragraphs = contentContainer?.select("p")?.map { it.text().trim() }?.filter { it.isNotBlank() }
        if (!paragraphs.isNullOrEmpty()) {
            return paragraphs.joinToString("\n\n")
        }
        return contentContainer?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    internal fun parseSeasons(html: String, mediaId: Int): List<Season> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val routePattern = Regex(
            "^/tv/$mediaId(?:-[^/]*)?/season/(\\d+)(?:\\?.*)?$",
        )
        return document.select("main a[href] img").mapNotNull { image ->
            val link = image.parents().firstOrNull { it.tagName() == "a" }
                ?: return@mapNotNull null
            val match = routePattern.matchEntire(link.attr("href")) ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val container = link.parents().firstOrNull { parent ->
                parent.selectFirst("h4") != null && episodeCount.find(parent.text()) != null
            }
            Season(
                number = number,
                title = image.attr("alt").trim().ifBlank { "Season $number" },
                episodeCount = container?.text()?.let {
                    episodeCount.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                } ?: 0,
                posterPath = normalizeTmdbImage(image.attr("src")),
            )
        }.distinctBy(Season::number).sortedBy(Season::number)

    }

    internal fun parseEpisodes(
        html: String,
        mediaId: Int,
        seasonNumber: Int,
    ): List<Episode> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val routePattern =
            Regex(
                "^/tv/$mediaId(?:-[^/]*)?/season/$seasonNumber/episode/(\\d+)(?:\\?.*)?$",
            )
        val episodeNumberPattern = Regex("(?:^|\\D)(\\d{1,3})(?:\\D|$)")
        val containers = document.select("main div.episode").ifEmpty {
            document.select("main div.card").filter { card ->
                card.selectFirst("a[href*=\"/episode/\"]") != null
            }
        }.ifEmpty {
            document.select("main [class*=episode]").filter { el ->
                el.selectFirst("a[href]") != null
            }
        }
        val fromContainers = containers.mapNotNull { container ->
            val link = container.selectFirst(
                "a[data-episode-number][href], a[href*=\"/episode/\"]",
            ) ?: return@mapNotNull null
            val href = link.attr("href")
            val number = routePattern.matchEntire(href)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?: link.attr("data-episode-number").toIntOrNull()
                ?: episodeNumberPattern.find(href)?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
                ?: return@mapNotNull null
            val image = container.selectFirst("img.backdrop, img[src]")
            val title = container
                .selectFirst("h3 a, .title a, a[data-episode-number], h4 a")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: image?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: "Episode $number"
            val overview = container
                .selectFirst(".overview p, div.overview, .info p")
                ?.text()
                ?.trim()
                ?.takeUnless { it.equals("Expand", ignoreCase = true) }
                ?: container.select("p")
                    .map { it.text().trim() }
                    .firstOrNull { it.length > 25 }
                    .orEmpty()
            Episode(
                seasonNumber = seasonNumber,
                number = number,
                title = title,
                overview = overview.replace(
                    Regex("\\s*(?:Read More|Expand)\\s*$", RegexOption.IGNORE_CASE),
                    "",
                ),
                stillPath = normalizeTmdbImage(image?.attr("src")),
                runtime = container.selectFirst(".runtime")?.text()?.trim().orEmpty(),
            )
        }
        if (fromContainers.isNotEmpty()) {
            return fromContainers.distinctBy(Episode::number).sortedBy(Episode::number)
        }
        val linkFallback = document.select("main a[href]").mapNotNull { link ->
            val match = routePattern.matchEntire(link.attr("href"))
                ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val text = link.text().trim().takeIf(String::isNotBlank)
                ?: "Episode $number"
            Episode(
                seasonNumber = seasonNumber,
                number = number,
                title = text,
                overview = "",
                stillPath = null,
                runtime = "",
            )
        }
        return linkFallback.distinctBy(Episode::number).sortedBy(Episode::number)
    }

    private suspend fun ratingsFor(item: Media): ExternalRatings {
        val cachedRottenTomatoes = rottenTomatoesRatingsCache[item.key]
            ?: cacheStore?.loadRottenTomatoesRating(item.key, 24 * 60 * 60 * 1000L)
                ?.also { rottenTomatoesRatingsCache[item.key] = it }
                
        val ratings = supervisorScope {
            val imdb = async {
                try {
                    imdbRatingRepository.ratingFor(item)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
            val rottenTomatoes = async {
                cachedRottenTomatoes
                    ?: rottenTomatoesClient.loadRating(item)
            }

            val imdbSnapshot = imdb.await()
            val rtSnapshot = rottenTomatoes.await()
            ExternalRatings(
                imdb = imdbSnapshot?.rating,
                imdbId = imdbSnapshot?.identity?.imdbId,
                imdbVoteCount = imdbSnapshot?.voteCount,
                imdbState = imdbSnapshot?.state ?: RatingSourceState.UNAVAILABLE,
                rottenTomatoes = rtSnapshot?.rating,
                rottenTomatoesState = rtSnapshot?.state ?: RatingSourceState.UNAVAILABLE,
            )
        }
        ratings.imdb?.let { imdbRatingsCache[item.key] = it }
        val rtSnapshot = RottenTomatoesSnapshot(ratings.rottenTomatoes, ratings.rottenTomatoesState!!)
        if (rtSnapshot.state in setOf(RatingSourceState.VERIFIED, RatingSourceState.STALE, RatingSourceState.NOT_RATED)) {
            rottenTomatoesRatingsCache[item.key] = rtSnapshot
            cacheStore?.saveRottenTomatoesRating(item.key, rtSnapshot)
        }
        return ratings
    }

    private suspend fun loadImdbRating(item: Media): Double? =
        imdbRatingRepository.ratingFor(item).rating

    internal fun parseImdbRating(html: String): Double? {
        val document = Jsoup.parse(html, "https://www.imdb.com")
        document.select("script[type=application/ld+json]").forEach { script ->
            val rating = runCatching {
                JSONObject(script.data())
                    .optJSONObject("aggregateRating")
                    ?.optDouble("ratingValue")
            }.getOrNull()
            if (rating != null && rating in 0.1..10.0) return rating
        }
        return imdbRatingPattern.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

            ?.takeIf { it in 0.1..10.0 }
    }

    private fun normalizeText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun localSearch(query: String): List<Media> {
        val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return catalogue.filter { item ->
            val searchable =
                "${item.title} ${item.year} ${item.genres.joinToString(" ")}".lowercase()
            terms.all(searchable::contains)
        }
    }

    data class ParsedPage(
        val hero: Media?,
        val rails: List<ContentRail>,
    )

    private data class ExternalRatings(
        val imdb: Double?,
        val imdbId: String?,
        val imdbVoteCount: Int?,
        val imdbState: RatingSourceState?,
        val rottenTomatoes: Int?,
        val rottenTomatoesState: RatingSourceState?,
    )

    private data class TmdbHomeRailSpec(
        val path: String,
        val title: String,
        val expectedType: MediaType? = null,
        val alternatePaths: List<String> = emptyList(),
        val minimumItems: Int = 1,
        val isTrending: Boolean = false,
    )

    private companion object {
        const val SITE_URL = "https://ramoflix.net"
        const val TMDB_SITE_URL = "https://www.themoviedb.org"
        const val IMDB_SUGGESTION_URL =
            "https://v3.sg.media-imdb.com/suggestion/x"
        const val IMDB_GRAPHQL_URL = "https://api.graphql.imdb.com/"
        const val IMDB_SITE_URL = "https://www.imdb.com"
        const val IMDB_ADVANCED_TITLE_URL = "$IMDB_SITE_URL/search/title/"
        const val BRAVE_SEARCH_URL = "https://search.brave.com/search"
        const val WIKIPEDIA_SEARCH_URL = "https://en.wikipedia.org/w/api.php"
        const val DUCKDUCKGO_HTML_URL = "https://html.duckduckgo.com/html"
        const val ROTTEN_TOMATOES_URL = "https://www.rottentomatoes.com"
        const val HOME_RAIL_LIMIT = 20
        const val MIN_GENRE_RAIL_ITEMS = 20
        const val MIN_TRENDING_RAIL_ITEMS = 20
        const val GENRE_PAGE_TARGET = 40
        const val HOME_BASE_CANDIDATE_TARGET = 60
        const val HOME_BASE_MAX_PAGES = 3
        const val HOME_TRENDING_CANDIDATE_TARGET = 100
        const val HOME_TRENDING_MAX_PAGES_PER_PATH = 5
        const val HOME_GENRE_CANDIDATE_TARGET = 80
        const val HOME_COMPOUND_GENRE_CANDIDATE_TARGET = 40
        const val HOME_GENRE_START_PAGE = 1
        const val HOME_GENRE_MAX_PAGES = 5
        const val HOME_COMPOUND_GENRE_MAX_PAGES = 20
        const val GENRE_BROWSE_START_PAGE = 6
        const val GENRE_BROWSE_MAX_PAGES = 12
        const val HOME_CONCURRENT_REQUESTS = 4
        const val CATALOG_REQUEST_ATTEMPTS = 3
        const val CATALOG_RETRY_DELAY_MS = 400L
        const val GENRE_ASSEMBLY_ATTEMPTS = 2
        const val GENRE_ASSEMBLY_RETRY_DELAY_MS = 750L
        const val PLOT_TMDB_SEARCH_ATTEMPTS = 3
        const val PLOT_TMDB_RETRY_DELAY_MS = 300L
        const val MIN_PLOT_TITLE_MATCH_SCORE = 62
        const val PLOT_EVIDENCE_LIMIT = 1_400
        const val WIKIPEDIA_RESULT_BATCH_SIZE = 24
        const val WIKIPEDIA_QUERY_LIMIT = 2
        const val WIKIPEDIA_QUERY_POSITION_PENALTY = 3
        const val WIKIPEDIA_REQUEST_ATTEMPTS = 3
        const val WIKIPEDIA_MIN_REQUEST_INTERVAL_MS = 650L
        const val WIKIPEDIA_RETRY_DELAY_MS = 900L
        const val WIKIPEDIA_CONTEXT_TERM_LIMIT = 3
        const val WIKIPEDIA_FALLBACK_TERM_LIMIT = 8
        const val RECOMMENDATION_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        const val RECOMMENDATION_STALE_CACHE_MAX_AGE_MS =
            7 * 24 * 60 * 60 * 1_000L
        const val RECOMMENDATION_METADATA_CACHE_MAX_AGE_MS =
            7 * 24 * 60 * 60 * 1_000L
        const val RECOMMENDATION_LOCAL_SEED_LIMIT = 28
        const val RECOMMENDATION_WEB_TITLE_LIMIT = 36
        const val RECOMMENDATION_RESULT_POOL = 60
        const val RECOMMENDATION_RESOLUTION_CONCURRENCY = 4
        const val RECOMMENDATION_METADATA_ATTEMPTS = 2
        const val RECOMMENDATION_METADATA_RETRY_DELAY_MS = 350L
        const val RECOMMENDATION_DDG_LIMIT = 24
        const val RECOMMENDATION_KNOWN_SEED_LIMIT = 120
        const val RECOMMENDATION_PAGE_CANDIDATE_LIMIT = 48
        const val RECOMMENDATION_SUPPLEMENTAL_START_PAGE = 1
        const val RECOMMENDATION_FIRST_PAGE_SUPPLEMENTAL_TIMEOUT_MS = 800L
        const val RECOMMENDATION_RELATED_LIMIT = 60

        const val RECOMMENDATION_LOCAL_RELATED_SCORE_LIMIT = 180
        const val RECOMMENDATION_SUPPLEMENTAL_TIMEOUT_MS = 2_500L
        const val RECOMMENDATION_OPTIONAL_SOURCE_TIMEOUT_MS = 8_000L
        const val RECOMMENDATION_SUPPLEMENT_CACHE_LIMIT = 16
        const val RECOMMENDATION_REDDIT_TITLE_LIMIT = 12
        const val RECOMMENDATION_EDITORIAL_TITLE_LIMIT = 18
        const val TMDB_PAGE_RESULT_FLOOR = 15
        const val IMDB_GRAPH_PAGE_SIZE = 18
        const val IMDB_HTML_PAGE_RESULT_FLOOR = 15
        const val IMDB_RESOLUTION_CANDIDATE_LIMIT = 36
        const val IMDB_RATING_STREAM_MIN_VOTES = 250
        const val IMDB_ADVANCED_REQUEST_ATTEMPTS = 3
        const val IMDB_ADVANCED_RETRY_DELAY_MS = 250L
        const val IMDB_GRAPH_REQUEST_TIMEOUT_MS = 3_000L
        const val IMDB_ADVANCED_TOTAL_TIMEOUT_MS = 8_000L
        const val IMDB_EARLIEST_YEAR = 1870
        const val IMDB_MAX_RUNTIME_MINUTES = 600
        const val TMDB_EXPLICIT_EMPTY_MESSAGE =
            "No items were found that match your query."
        val TMDB_FORM_HEADERS = linkedMapOf(
            "User-Agent" to (
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
                ),
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val TMDB_WAF_MARKERS = setOf(
            "captcha",
            "cloudflare",
            "challenge-platform",
            "x-amzn-waf",
        )
        val genericWebResultTerms = listOf(
            "best movies",

            "movies where",
            "movies about",
            "films about",
            "ranked",
            "find that movie",
            "what is that movie",
            "top 10",
            "top 20",
            "list of",
            "google",
            "bing",
            "moviepilot",
            "watch free movies",
        )
        val EDITORIAL_DOMAINS = listOf(
            "rogerebert.com",
            "bfi.org.uk",
            "theguardian.com",
            "indiewire.com",
            "slantmagazine.com",
            "avclub.com",
            "vulture.com",
        )
        val redditNonTitleTerms = setOf(
            "reddit",
            "movie suggestions",
            "movies",
            "television suggestions",
            "television",
            "what are you watching",
            "recommendations",
        )
        val plotQueryStopWords = setOf(
            "about", "after", "also", "and", "are", "film", "goes", "into", "movie",
            "other", "others", "people", "protagonist", "series", "show", "story",
            "that", "the", "their", "them", "they", "this", "where", "with",
        )
        val wikipediaPlotContextTerms = setOf(
            "cancer", "day", "enter", "entered", "entering", "enters",
            "relive", "relives", "repeatedly", "secret", "secrets", "steal", "steals",
        )
        val wikipediaTitleMediaQualifier = Regex(
            """\((?:(?:18|19|20|21)\d{2}\s+)?""" +
                """(?:film|TV series|television series|miniseries)\)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val wikipediaStrongMediaPatterns = listOf(
            Regex("""\bthe\s+(?:film|movie|series)\b""", RegexOption.IGNORE_CASE),
            Regex(
                """\b(?:is|was)\b.{0,70}\b""" +
                    """(?:film|movie|television series|tv series|miniseries)\b""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """\b(?:television series|tv series|miniseries|episode|screenplay|""" +
                    """directed by|starring)\b""",
                RegexOption.IGNORE_CASE,
            ),
        )
        val titleRoute = Regex("^/(movie|tv)/.*-(\\d+)$")
        val watchRoute = Regex("^/watch/(movie|tv)/(\\d+)$")
        val tmdbTitleRoute = Regex("^/(movie|tv)/(\\d+)(?:-|\\?|$)")
        val yearText = Regex("^\\d{4}(?:-\\d{2}-\\d{2})?$")
        val fourDigitYear = Regex("\\b(?:18|19|20|21)\\d{2}\\b")
        val imdbTitleIdPattern = Regex("""(?:/title/)?(tt\d+)""")
        val matchPercent = Regex("(\\d{1,3})%\\s*Match", RegexOption.IGNORE_CASE)
        val episodeCount = Regex("(\\d+)\\s+Episodes?", RegexOption.IGNORE_CASE)
        val rottenTomatoesPathPattern = Regex(
            """/(?:m|tv)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""",
        )
        val imdbRatingPattern = Regex(
            """"ratingValue"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE,
        )
        val rottenTomatoesPatterns = listOf(
            Regex(
                """tomatometerscore\s*=\s*["']?(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """"criticsScore"\s*:\s*"?(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """"criticsScore"\s*:\s*\{[^{}]*"score"\s*:\s*"(\d{1,3})"""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ),
            Regex(
                """"scorePercent"\s*:\s*"(\d{1,3})%"""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """"tomatometerScore"\s*:\s*"?(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """slot\s*=\s*["']criticsScore["'][^>]*>\s*(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
        )

        val baseHomeRailSpecs = listOf(
            TmdbHomeRailSpec(
                path = "/discover/movie?" +
                    "include_adult=false&sort_by=popularity.desc&vote_count.gte=50",
                title = "Trending Movies",
                expectedType = MediaType.MOVIE,
                alternatePaths = listOf(
                    "/movie?include_adult=false",
                    "/movie/now-playing?include_adult=false",
                    "/movie/top-rated?include_adult=false",
                ),
                minimumItems = MIN_TRENDING_RAIL_ITEMS,
                isTrending = true,
            ),
            TmdbHomeRailSpec(
                path = "/discover/tv?" +
                    "include_adult=false&sort_by=popularity.desc&vote_count.gte=25",
                title = "Trending Series",
                expectedType = MediaType.TV,
                alternatePaths = listOf(
                    "/tv?include_adult=false",
                    "/tv/on-the-air?include_adult=false",
                    "/tv/top-rated?include_adult=false",
                ),
                minimumItems = MIN_TRENDING_RAIL_ITEMS,
                isTrending = true,
            ),
            TmdbHomeRailSpec(
                "/movie/now-playing",
                "Now in Cinemas",
                MediaType.MOVIE,
            ),
            TmdbHomeRailSpec(
                "/tv/on-the-air",
                "Series Airing Now",
                MediaType.TV,
            ),

            TmdbHomeRailSpec(
                "/movie/top-rated",
                "All-Time Movie Greats",
                MediaType.MOVIE,
            ),
            TmdbHomeRailSpec(
                "/tv/top-rated",
                "Binge-Worthy Series",
                MediaType.TV,
            ),
        )

        @Suppress("unused")
        val tmdbHomeRailSpecs = listOf(
            TmdbHomeRailSpec("/movie", "Trending Movies"),
            TmdbHomeRailSpec("/tv", "Trending Series"),
            TmdbHomeRailSpec("/movie/now-playing", "Now in Cinemas"),
            TmdbHomeRailSpec("/tv/on-the-air", "Series Airing Now"),
            TmdbHomeRailSpec("/movie/top-rated", "All-Time Movie Greats"),
            TmdbHomeRailSpec("/tv/top-rated", "Binge-Worthy Series"),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=28&sort_by=popularity.desc",
                "Action Hits",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=16&sort_by=popularity.desc",
                "Animation & Animated Worlds",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=35&sort_by=popularity.desc",
                "Comedy Picks",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=18&sort_by=popularity.desc",

                "Gripping Dramas",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=27&sort_by=popularity.desc",
                "Horror After Dark",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=10749&sort_by=popularity.desc",
                "Romance & Love Stories",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=878&sort_by=popularity.desc",
                "Sci-Fi & Cyberpunk Worlds",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=53&sort_by=popularity.desc",
                "Edge-of-Your-Seat Thrillers",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=10759&sort_by=popularity.desc",
                "Action & Adventure Series",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=80&sort_by=popularity.desc",
                "Crime & Mystery Series",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=10765&sort_by=popularity.desc",
                "Fantasy & Sci-Fi Worlds",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=9648&sort_by=popularity.desc",
                "Mystery & Whodunit",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=10751&sort_by=popularity.desc",
                "Family Movie Night",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=12&sort_by=popularity.desc&page=2",
                "Epic Adventures",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=80&sort_by=vote_average.desc&vote_count.gte=200",
                "Crime Essentials",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=99&sort_by=popularity.desc",
                "Powerful Documentaries",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=14&sort_by=popularity.desc&page=2",
                "Fantasy Realms",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=36&sort_by=vote_average.desc&vote_count.gte=100",
                "History on Screen",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=10402&sort_by=popularity.desc",
                "Music & Performance",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=10752&sort_by=vote_average.desc&vote_count.gte=100",
                "War Stories",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=37&sort_by=popularity.desc",
                "Westerns",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=28,53&sort_by=popularity.desc&page=2",
                "Action Thrillers",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=12,878&sort_by=popularity.desc&page=2",
                "Sci-Fi Adventures",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=35,10749&sort_by=popularity.desc",
                "Romantic Comedies",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=80,53&sort_by=vote_average.desc&vote_count.gte=100",
                "Crime Thrillers",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=18,36&sort_by=popularity.desc",
                "Historical Dramas",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=16,10751&sort_by=popularity.desc&page=2",
                "Animated Family Adventures",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=27,35&sort_by=popularity.desc",
                "Horror Comedies",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=35&sort_by=popularity.desc&page=2",
                "Comedy Series",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=18&sort_by=vote_average.desc&vote_count.gte=100",
                "Acclaimed TV Dramas",
            ),

            TmdbHomeRailSpec(
                "/discover/tv?with_genres=99&sort_by=popularity.desc",
                "Documentary Series",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=10762&sort_by=popularity.desc",
                "Kids & Family Series",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=10764&sort_by=popularity.desc",
                "Reality Favorites",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=18,80&sort_by=popularity.desc&page=2",
                "Crime Dramas",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=18,9648&sort_by=vote_average.desc&vote_count.gte=100",
                "Mystery Dramas",
            ),
        )

        fun normalizeTmdbImage(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val filename = value.substringAfterLast('/').substringBefore('?')
            return if (
                filename.isNotBlank() &&
                (value.contains("themoviedb.org/t/p/") || value.contains("tmdb.org/t/p/"))
            ) {
                "https://image.tmdb.org/t/p/w500/$filename"
            } else {
                value
            }

        }

        val fallbackItems = listOf(
            Media(
                id = 27205,
                type = MediaType.MOVIE,
                title = "Inception",
                overview = "A skilled extractor enters dreams to steal secrets and is offered a chance to erase his past.",
                posterPath = "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg",
                backdropPath = "/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
                year = "2010",
                rating = 8.4,
                genres = listOf("Science Fiction", "Action", "Thriller"),
            ),
            Media(
                id = 496243,
                type = MediaType.MOVIE,
                title = "Parasite",
                posterPath = "/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg",
                backdropPath = "/TU9NIjwzjoKPwQHoHshkFcQUCG.jpg",
                year = "2019",
                rating = 8.5,
                genres = listOf("Thriller", "Drama"),
            ),
            Media(
                id = 155,
                type = MediaType.MOVIE,
                title = "The Dark Knight",
                posterPath = "/qJ2tW6WMUDux911r6m7haRef0WH.jpg",
                backdropPath = "/hqkIcbrOHL86UncnHIsHVcVmzue.jpg",
                year = "2008",
                rating = 8.5,
                genres = listOf("Action", "Crime", "Drama"),
            ),
            Media(
                id = 454639,
                type = MediaType.MOVIE,
                title = "Masters of the Universe",
                posterPath = "https://image.tmdb.org/t/p/w500/oRuyGUHdoaQxWP3SDfafGkStxTC.jpg",
            ),
            Media(
                id = 1275779,
                type = MediaType.MOVIE,
                title = "Disclosure Day",
                posterPath = "https://image.tmdb.org/t/p/w500/AnJ8IQJI23hNpYXVNaythu061Ru.jpg",
            ),
            Media(
                id = 1368337,
                type = MediaType.MOVIE,
                title = "The Odyssey",
                posterPath = "https://image.tmdb.org/t/p/w500/5rhTDKUhPYvpdQIijFIs5VoWsON.jpg",
            ),
            Media(
                id = 1396,
                type = MediaType.TV,
                title = "Breaking Bad",
                posterPath = "/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
                backdropPath = "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
                year = "2008",
                rating = 8.9,
                genres = listOf("Drama", "Crime"),
            ),
            Media(
                id = 66732,
                type = MediaType.TV,
                title = "Stranger Things",
                posterPath = "/uOOtwVbSr4QDjAGIifLDwpb2Pdl.jpg",
                backdropPath = "/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
                year = "2016",
                rating = 8.6,
                genres = listOf("Drama", "Mystery", "Sci-Fi"),
            ),
            Media(
                id = 94605,
                type = MediaType.TV,
                title = "Arcane",
                posterPath = "/fqldf2t8ztc9aiwn3k6mlX3tvRT.jpg",
                backdropPath = "/rkB4LyZHo1NHXFEDHl9vSD9r1lI.jpg",
                year = "2021",
                rating = 8.8,
                genres = listOf("Animation", "Drama", "Action"),
            ),
        )

        val fallbackRails = listOf(
            ContentRail("Trending Now", fallbackItems),
            ContentRail(
                "Popular Movies",
                fallbackItems.filter { it.type == MediaType.MOVIE },
            ),
            ContentRail(
                "Popular TV Shows",
                fallbackItems.filter { it.type == MediaType.TV },
            ),
        )

        suspend fun downloadPage(url: String): String {
            val isWikipediaApi = runCatching {
                URL(url).host.equals("en.wikipedia.org", ignoreCase = true)
            }.getOrDefault(false)
            return suspendCancellableCoroutine { continuation ->
                val extraHeaders = mapOf(
                    "User-Agent" to if (isWikipediaApi) {
                        "AliflixAndroid/2.7.8 (https://github.com/alishaban144/aliflix-android)"
                    } else {
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
                    },

                    "Accept" to if (isWikipediaApi) {
                        "application/json"
                    } else {
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                    },
                )
                val connection = SafeHttpTransport.openConnection(
                    urlString = url,
                    connectTimeoutMs = 12_000,
                    readTimeoutMs = 18_000,
                    headers = extraHeaders,
                )
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    val status = connection.responseCode
                    val response = SafeHttpTransport.readResponseText(connection)
                    if (status !in 200..299) {
                        throw IOException("Catalogue request failed ($status)")
                    }
                    if (response.isBlank()) {
                        throw IOException("Catalogue response was empty")
                    }
                    if (continuation.isActive) continuation.resume(response)
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }


        suspend fun postJson(url: String, body: String): String {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            return suspendCancellableCoroutine { continuation ->
                val extraHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                )
                val connection = SafeHttpTransport.openConnection(
                    urlString = url,
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 10_000,
                    headers = extraHeaders,
                ).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                }
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    connection.outputStream.use { it.write(payload) }
                    val status = connection.responseCode
                    val response = SafeHttpTransport.readResponseText(connection)
                    if (status !in 200..299) {
                        throw IOException("Metadata request failed ($status)")
                    }
                    if (response.isBlank()) {
                        throw IOException("Metadata response was empty")
                    }
                    if (continuation.isActive) continuation.resume(response)
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
