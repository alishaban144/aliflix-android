package com.aliflix.app.data

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationPageCursor
import com.aliflix.app.recommendation.RecommendationSourceHealth
import com.aliflix.app.recommendation.RecommendationSourceStatus
import com.aliflix.app.recommendation.RequiredMetadataFields
import com.aliflix.app.recommendation.RelatedContentEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
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

internal data class PlotCandidate(
    val title: String,
    val year: String? = null,
    val type: MediaType? = null,
    val evidence: String = title,
    val source: PlotSource = PlotSource.BRAVE,
    val position: Int = 0,
    val sourceCount: Int = 1,
    val sources: Set<PlotSource> = setOf(source),
) {
    val cacheKey: String
        get() = title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim() + ":${type?.routeName.orEmpty()}"
}

internal enum class PlotSource(val priority: Int) {
    IMDB(34),
    BRAVE(30),
    WIKIPEDIA(24),
    DUCKDUCKGO(20),
    REDDIT(12),
}

data class RecommendationDiscoveryItem(
    val media: Media,
    val metadata: CatalogVerifiedMetadata = CatalogVerifiedMetadata(),
    val evidence: String = "",
    val sources: Set<String> = emptySet(),
    val sourceCount: Int = 0,
    val sourcePosition: Int = 99,
)

data class CatalogRelatedItem(
    val media: Media,
    val directProviderRelation: Boolean,
    val sourceRank: Int,
)

data class CatalogVerifiedMetadata(
    val genresVerified: Boolean = false,
    val runtimeMinutes: Int? = null,
    val originalLanguage: String? = null,
    val status: String? = null,
    val director: String? = null,
    val seasonCount: Int? = null,
    val averageEpisodeRuntimeMinutes: Int? = null,
    val verifiedAtMillis: Long = System.currentTimeMillis(),
)

data class VerifiedRecommendationItem(
    val media: Media,
    val metadata: CatalogVerifiedMetadata,
)

data class RecommendationDiscoveryBatch(
    val items: List<RecommendationDiscoveryItem>,
    val webAvailable: Boolean,
)

data class CatalogRecommendationPage(
    val items: List<RecommendationDiscoveryItem>,
    val nextCursor: RecommendationPageCursor?,
    val hasMore: Boolean,
    val sourceHealth: RecommendationSourceHealth,
    val fromCache: Boolean = false,
)

enum class CatalogSource {
    TMDB,
    IMDB,
}

sealed interface CatalogPageOutcome {
    data class Results(
        val page: CatalogRecommendationPage,
    ) : CatalogPageOutcome

    data class Empty(
        val page: CatalogRecommendationPage,
    ) : CatalogPageOutcome

    data class Unavailable(
        val source: CatalogSource,
        val cause: Throwable? = null,
    ) : CatalogPageOutcome
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

class ImdbCatalogSourceException(
    message: String = "IMDb catalogue is unavailable.",
    cause: Throwable? = null,
) : CatalogSourceException(CatalogSource.IMDB, message, cause)

fun interface CatalogFormTransport {
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String>,
    ): String
}

internal data class ImdbAdvancedTitle(
    val imdbId: String,
    val title: String,
    val year: Int?,
    val rating: Double?,
    val voteCount: Int?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val overview: String,
    val position: Int,
)

internal data class ImdbAdvancedPage(
    val items: List<ImdbAdvancedTitle>,
    val endCursor: String?,
    val hasNextPage: Boolean,
)

private data class PlotDiscovery(
    val candidates: List<PlotCandidate>,
    val successfulSources: Int,
)

private data class ResolvedPlotCandidate(
    val candidate: PlotCandidate,
    val media: Media,
    val resolutionScore: Int,
)

private data class WikipediaSearchHit(
    val title: String,
    val snippet: String,
)

private data class GenreFetch(
    val items: List<Media>,
    val pagesLoaded: Int,
)

private data class WikipediaConceptGroup(
    val triggers: Set<String>,
    val searchTerms: List<String>,
)

private data class RecommendationSupplementBatch(
    val items: List<RecommendationDiscoveryItem>,
    val webStatus: RecommendationSourceStatus,
    val redditStatus: RecommendationSourceStatus,
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

private sealed interface ImdbTitleResolution {
    data class Resolved(
        val item: RecommendationDiscoveryItem,
    ) : ImdbTitleResolution

    data object NoMatch : ImdbTitleResolution
    data class Unavailable(val cause: Throwable? = null) : ImdbTitleResolution
}

private fun CatalogDiscoverySpec.requiredFieldsForCacheFallback():
    RequiredMetadataFields = RequiredMetadataFields(
    genres = includedGenres.isNotEmpty() || excludedGenres.isNotEmpty(),
    runtime = mediaKind == RecommendationMediaKind.MOVIE &&
        (runtimeMinimumMinutes != null || runtimeMaximumMinutes != null),
    originalLanguage = originalLanguage != null,
    imdbRating = minimumImdb != null,
    rottenTomatoesRating = minimumRottenTomatoes != null,
    tmdbRating = minimumTmdb != null,
    tvEpisodeRuntime = mediaKind == RecommendationMediaKind.SERIES &&
        (runtimeMinimumMinutes != null || runtimeMaximumMinutes != null),
    status = requiredStatus != null,
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

private object RecommendationRequestPriorityKey :
    CoroutineContext.Key<RecommendationRequestPriorityElement>

private object RecommendationRequestPriorityElement :
    AbstractCoroutineContextElement(RecommendationRequestPriorityKey)

/**
 * One bounded scheduler for all catalogue traffic. Foreground recommendation
 * requests announce themselves before waiting for a permit, so queued Home
 * refresh work yields instead of filling the next available slots.
 */
private class CatalogRequestScheduler {
    private val globalGate = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val hostGates = ConcurrentHashMap<String, Semaphore>()
    private val foregroundWaiters = AtomicInteger(0)

    suspend fun <T> execute(
        url: String,
        foreground: Boolean,
        block: suspend () -> T,
    ): T {
        if (foreground) foregroundWaiters.incrementAndGet()
        try {
            if (!foreground) {
                while (foregroundWaiters.get() > 0) {
                    delay(BACKGROUND_YIELD_DELAY_MS)
                }
            }
            val host = runCatching { URL(url).host.lowercase() }
                .getOrDefault("unknown")
            val hostGate = hostGates.computeIfAbsent(host) {
                Semaphore(MAX_CONCURRENT_REQUESTS_PER_HOST)
            }
            return globalGate.withPermit {
                hostGate.withPermit { block() }
            }
        } finally {
            if (foreground) foregroundWaiters.decrementAndGet()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_CONCURRENT_REQUESTS_PER_HOST = 4
        const val BACKGROUND_YIELD_DELAY_MS = 30L
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
    private val supplementSupervisor = SupervisorJob()
    private val supplementScope = CoroutineScope(supplementSupervisor + computationDispatcher)
    private val recommendationSupplementResults =
        ConcurrentHashMap<String, RecommendationSupplementBatch>()
    private val supplementGeneration = AtomicInteger(0)
    @Volatile
    private var supplementsClosed = false
    @Volatile
    private var activeSupplementFingerprint: String? = null
    @Volatile
    private var activeSupplementJob: Job? = null
    @Volatile
    private var catalogue: List<Media> = fallbackItems
    private val imdbRatingsCache = ConcurrentHashMap<String, Double>()
    private val rottenTomatoesRatingsCache = ConcurrentHashMap<String, Int>()
    private val recommendationMetadata =
        ConcurrentHashMap<String, CatalogVerifiedMetadata>()
    private val tmdbSearchCache = ConcurrentHashMap<String, List<Media>>()
    private val verifiedGenrePageCache = ConcurrentHashMap<String, List<Media>>()
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
                            coroutineContext[RecommendationRequestPriorityKey] != null,
                    ) {
                        rawImdbGraphQlTransport.postJson(url, body, headers)
                    }
                }
            },
        )

    suspend fun scrapeTmdbDiscoverPage(
        pathParams: String
    ): List<Media> = supervisorScope {
        val url = "https://www.themoviedb.org/discover/movie?$pathParams"
        val html = pageLoader(url)
        com.aliflix.app.recommendation.TmdbKeywordParser.parseDiscoverPage(html, MediaType.MOVIE)
    }

    suspend fun scrapeTmdbKeywordSearch(
        query: String
    ): List<com.aliflix.app.recommendation.ResolvedKeyword> = supervisorScope {
        val url = "https://www.themoviedb.org/search/keyword?query=${URLEncoder.encode(query, "UTF-8")}"
        val html = pageLoader(url)
        com.aliflix.app.recommendation.TmdbKeywordParser.parseKeywordSearchResults(html)
    }

    fun close() {
        val jobToCancel = synchronized(recommendationSupplementResults) {
            if (supplementsClosed) return
            supplementsClosed = true
            supplementGeneration.incrementAndGet()
            recommendationSupplementResults.clear()
            activeSupplementFingerprint = null
            activeSupplementJob.also { activeSupplementJob = null }
        }
        jobToCancel?.cancel(CancellationException("Catalogue client closed"))
        supplementSupervisor.cancel(CancellationException("Catalogue client closed"))
    }

    private suspend fun pageLoader(url: String): String =
        withContext(ioDispatcher) {
            requestScheduler.execute(
                url = url,
                foreground =
                    coroutineContext[RecommendationRequestPriorityKey] != null,
            ) {
                rawPageLoader(url)
            }
        }

    private suspend fun jsonPoster(url: String, body: String): String =
        withContext(ioDispatcher) {
            requestScheduler.execute(
                url = url,
                foreground =
                    coroutineContext[RecommendationRequestPriorityKey] != null,
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
                coroutineContext[RecommendationRequestPriorityKey] != null,
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
        val intent = SearchRanker.parseIntent(cleanQuery)
        val providerQuery = intent.providerTitle.ifBlank { cleanQuery }
        val requestedTypes = intent.type
            ?.let { listOf(it.routeName) }
            ?: listOf("movie", "tv")

        val direct = searchTmdb(providerQuery, requestedTypes)
        val local = localSearch(providerQuery).filter { item ->
            intent.type == null || item.type == intent.type
        }
        val bestKnown = SearchRanker.rank(
            cleanQuery,
            (direct + local).distinctBy(Media::key),
        ).firstOrNull()
        val knownConfidence = bestKnown
            ?.takeIf { item -> item.matchesExplicitQualifiers(intent) }
            ?.let { item -> SearchRanker.confidence(intent, item) }
            ?: SearchRanker.SearchConfidence.NONE
        val correctedTitle = if (
            knownConfidence.ordinal < SearchRanker.SearchConfidence.LIKELY.ordinal
        ) {
            predictiveTitleSuggestion(providerQuery, intent)
        } else {
            null
        }
        val corrected = correctedTitle
            ?.takeUnless { normalizeText(it) == normalizeText(providerQuery) }
            ?.let { searchTmdb(it, requestedTypes) }
            .orEmpty()

        val online = (direct + corrected).distinctBy(Media::key)
        val source = (online + local).distinctBy(Media::key)
        val ranked = SearchRanker.rank(cleanQuery, source)
        val relevant = ranked.filter { item ->
            SearchRanker.confidence(intent, item) != SearchRanker.SearchConfidence.NONE
        }
        val sorted = (relevant.ifEmpty { ranked }).take(80)
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
    fun knownRecommendationSeeds(
        spec: CatalogDiscoverySpec,
        requiredFields: RequiredMetadataFields,
    ): List<RecommendationDiscoveryItem> = catalogue
        .asSequence()
        .filter { it.type == spec.mediaKind.mediaType }
        .filter(::isSafeTrendingItem)
        .map { media ->
            RecommendationDiscoveryItem(
                media = media,
                metadata = recommendationMetadata[media.key]
                    ?: CatalogVerifiedMetadata(),
                sources = setOf("SESSION_CATALOG"),
                sourceCount = 1,
            )
        }
        .filter { it.satisfiesKnownRequirements(spec, requiredFields) }
        .distinctBy { it.media.key }
        .take(RECOMMENDATION_KNOWN_SEED_LIMIT)
        .toList()

    suspend fun recommendationPage(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor = RecommendationPageCursor(),
        requiredFields: RequiredMetadataFields =
            spec.requiredFieldsForCacheFallback(),
    ): CatalogRecommendationPage = when (
        val outcome = recommendationPageOutcome(spec, cursor, requiredFields)
    ) {
        is CatalogPageOutcome.Results -> outcome.page
        is CatalogPageOutcome.Empty -> outcome.page
        is CatalogPageOutcome.Unavailable -> throw (
            outcome.cause as? CatalogSourceException
                ?: CatalogSourceException(
                    source = outcome.source,
                    message = "${outcome.source.name} catalogue is unavailable.",
                    cause = outcome.cause,
                )
            )
    }

    suspend fun recommendationPageOutcome(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor = RecommendationPageCursor(),
        requiredFields: RequiredMetadataFields =
            spec.requiredFieldsForCacheFallback(),
    ): CatalogPageOutcome {
        val page = try {
            recommendationPageInternal(spec, cursor, requiredFields)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (sourceError: CatalogSourceException) {
            return CatalogPageOutcome.Unavailable(
                source = sourceError.source,
                cause = sourceError,
            )
        }
        return when {
            page.items.isNotEmpty() || page.hasMore ->
                CatalogPageOutcome.Results(page)
            page.sourceHealth.imdb == RecommendationSourceStatus.UNAVAILABLE ->
                CatalogPageOutcome.Unavailable(
                    source = CatalogSource.IMDB,
                    cause = ImdbCatalogSourceException(),
                )
            page.sourceHealth.catalogue == RecommendationSourceStatus.UNAVAILABLE ->
                CatalogPageOutcome.Unavailable(
                    source = CatalogSource.TMDB,
                    cause = TmdbCatalogSourceException(),
                )
            else -> CatalogPageOutcome.Empty(page)
        }
    }

    private suspend fun recommendationPageInternal(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor = RecommendationPageCursor(),
        requiredFields: RequiredMetadataFields,
    ): CatalogRecommendationPage = withContext(
        computationDispatcher + RecommendationRequestPriorityElement,
    ) {
        supervisorScope {
        cacheStore?.loadRecommendationCatalogPage(
            spec.fingerprint,
            cursor.page,
            RECOMMENDATION_CACHE_MAX_AGE_MS,
        )?.let { cached ->
            rememberRecommendationItems(cached.items)
            catalogue = (cached.items.map(RecommendationDiscoveryItem::media) + catalogue)
                .distinctBy(Media::key)
            return@supervisorScope CatalogRecommendationPage(
                items = cached.items.filterNot { it.media.key in cursor.seenKeys },
                nextCursor = cached.nextCursor,
                hasMore = cached.hasMore,
                sourceHealth = RecommendationSourceHealth(
                    catalogue = RecommendationSourceStatus.AVAILABLE,
                    imdb = if (spec.minimumImdb != null) {
                        RecommendationSourceStatus.AVAILABLE
                    } else {
                        RecommendationSourceStatus.NOT_REQUIRED
                    },
                ),
                fromCache = true,
            )
        }

        var liveFailure: CatalogSourceException? = null
        val live = try {
            if (spec.minimumImdb != null) {
                loadImdbRecommendationPage(spec, cursor)
            } else {
                try {
                    loadTmdbRecommendationPage(spec, cursor)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: TmdbCatalogSourceException) {
                    // IMDb's credential-free GraphQL catalogue is an
                    // independent structured fallback. It is intentionally
                    // available even when the user did not ask for an IMDb
                    // rating constraint.
                    val fallback = try {
                        loadImdbRecommendationPage(spec, cursor)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (fallbackFailure: CatalogSourceException) {
                        throw TmdbCatalogSourceException(
                            message = "Recommendation catalogues are unavailable.",
                            cause = fallbackFailure,
                        )
                    }
                    fallback.copy(
                        sourceHealth = fallback.sourceHealth.copy(
                            catalogue = RecommendationSourceStatus.DEGRADED,
                        ),
                    )
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (sourceError: CatalogSourceException) {
            liveFailure = sourceError
            null
        }

        if (live == null) {
            cacheStore?.loadRecommendationCatalogPage(
                spec.fingerprint,
                cursor.page,
                RECOMMENDATION_STALE_CACHE_MAX_AGE_MS,
            )?.let { stale ->
                return@supervisorScope CatalogRecommendationPage(
                    items = stale.items.filterNot { it.media.key in cursor.seenKeys },
                    nextCursor = stale.nextCursor,
                    hasMore = stale.hasMore,
                    sourceHealth = RecommendationSourceHealth(
                        catalogue = RecommendationSourceStatus.DEGRADED,
                        imdb = if (spec.minimumImdb != null) {
                            RecommendationSourceStatus.DEGRADED
                        } else {
                            RecommendationSourceStatus.NOT_REQUIRED
                        },
                    ),
                    fromCache = true,
                )
            }
            val lastGoodItems = cacheStore
                ?.loadLastGoodRecommendationItems(
                    mediaType = spec.mediaKind.mediaType,
                    maxAgeMs = RECOMMENDATION_STALE_CACHE_MAX_AGE_MS,
                    limit = RECOMMENDATION_KNOWN_SEED_LIMIT,
                )
                .orEmpty()
                .asSequence()
                .filter { item -> item.media.type == spec.mediaKind.mediaType }
                .filter { item -> isSafeTrendingItem(item.media) }
                .filterNot { item -> item.media.key in cursor.seenKeys }
                .filter { item ->
                    item.satisfiesKnownRequirements(spec, requiredFields)
                }
                .distinctBy { item -> item.media.key }
                .take(RECOMMENDATION_PAGE_CANDIDATE_LIMIT)
                .map { item ->
                    val sources = item.sources + "LAST_GOOD_CACHE"
                    item.copy(
                        sources = sources,
                        sourceCount = maxOf(item.sourceCount, sources.size),
                    )
                }
                .toList()
            if (lastGoodItems.isNotEmpty()) {
                rememberRecommendationItems(lastGoodItems)
                catalogue = (
                    lastGoodItems.map(RecommendationDiscoveryItem::media) +
                        catalogue
                    ).distinctBy(Media::key)
                return@supervisorScope CatalogRecommendationPage(
                    items = lastGoodItems,
                    nextCursor = null,
                    hasMore = false,
                    sourceHealth = RecommendationSourceHealth(
                        catalogue = RecommendationSourceStatus.DEGRADED,
                        imdb = if (requiredFields.imdbRating) {
                            RecommendationSourceStatus.DEGRADED
                        } else {
                            RecommendationSourceStatus.NOT_REQUIRED
                        },
                    ),
                    fromCache = true,
                )
            }
            throw liveFailure ?: CatalogSourceException(
                source = if (spec.minimumImdb != null) {
                    CatalogSource.IMDB
                } else {
                    CatalogSource.TMDB
                },
                message = "Recommendation catalogue is unavailable.",
            )
        }

        var items = live.items
        var health = live.sourceHealth
        startRecommendationSupplementDiscovery(spec)
        recommendationSupplementResults[spec.fingerprint]?.let { supplement ->
            health = health.copy(
                web = supplement.webStatus,
                reddit = supplement.redditStatus,
            )
            items = (items + supplement.items)
                .groupBy { it.media.key }
                .map { (_, matches) ->
                    matches.reduce(::mergeRecommendationDiscoveryItems)
                }
        }

        items = items
            .asSequence()
            .filter { it.media.type == spec.mediaKind.mediaType }
            .filter { isSafeTrendingItem(it.media) }
            .filterNot { it.media.key in cursor.seenKeys }
            .distinctBy { it.media.key }
            .take(RECOMMENDATION_PAGE_CANDIDATE_LIMIT)
            .toList()
        rememberRecommendationItems(items)
        catalogue = (items.map(RecommendationDiscoveryItem::media) + catalogue)
            .distinctBy(Media::key)

        val nextSeen = cursor.seenKeys + items.map { it.media.key }
        val nextCursor = live.nextCursor?.copy(seenKeys = nextSeen)
        val result = CatalogRecommendationPage(
            items = items,
            nextCursor = nextCursor,
            hasMore = live.hasMore,
            sourceHealth = health,
        )
        if (items.isNotEmpty()) {
            cacheStore?.saveRecommendationCatalogPage(
                spec.fingerprint,
                cursor.page,
                CachedRecommendationCatalogPage(
                    items = items,
                    nextCursor = nextCursor,
                    hasMore = result.hasMore,
                ),
            )
        }
            result
        }
    }

    private suspend fun loadTmdbRecommendationPage(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor,
    ): CatalogRecommendationPage = supervisorScope {
        val type = spec.mediaKind.mediaType
        val endpoint = buildTmdbRecommendationEndpoint(type, cursor.page)
        val requests = listOf("popularity.desc", "vote_average.desc").map { sort ->
            async {
                loadStructuredTmdbPage(
                    endpoint = endpoint,
                    fields = buildTmdbRecommendationFields(spec, cursor.page, sort),
                    expectedType = type,
                )
            }
        }
        val outcomes = requests.awaitAll()
        val groups = outcomes.filterIsInstance<TmdbCatalogueResponse.Results>()
        val failures = outcomes.filterIsInstance<TmdbCatalogueResponse.Unavailable>()
        if (failures.size == outcomes.size) {
            throw TmdbCatalogSourceException(
                cause = failures.firstNotNullOfOrNull(
                    TmdbCatalogueResponse.Unavailable::cause,
                ),
            )
        }
        val items = groups
            .flatMap(TmdbCatalogueResponse.Results::items)
            .asSequence()
            .map { item ->
                RecommendationDiscoveryItem(
                    media = item,
                    // The current TMDB form honours with_genres but silently
                    // ignores without_genres. Exclusions therefore force
                    // native title-page verification before a hard-valid
                    // candidate can be displayed.
                    metadata = CatalogVerifiedMetadata(
                        genresVerified = item.genres.isNotEmpty(),
                    ),
                    sources = setOf("TMDB"),
                    sourceCount = 1,
                )
            }
            .distinctBy { it.media.key }
            .toList()
        val hasMore = groups.any { group ->
            group.hasNextPage ?: (group.rawItemCount >= TMDB_PAGE_RESULT_FLOOR)
        }
        CatalogRecommendationPage(
            items = items,
            nextCursor = if (hasMore) cursor.copy(page = cursor.page + 1) else null,
            hasMore = hasMore,
            sourceHealth = RecommendationSourceHealth(
                catalogue = if (failures.isNotEmpty()) {
                    RecommendationSourceStatus.DEGRADED
                } else {
                    RecommendationSourceStatus.AVAILABLE
                },
            ),
        )
    }

    private suspend fun loadStructuredTmdbPage(
        endpoint: String,
        fields: Map<String, String>,
        expectedType: MediaType,
    ): TmdbCatalogueResponse {
        var lastFailure: Throwable? = null
        repeat(CATALOG_REQUEST_ATTEMPTS) { attempt ->
            try {
                val response = parseTmdbCatalogueResponse(
                    html = formPoster(
                        url = "$TMDB_SITE_URL$endpoint",
                        fields = fields,
                        headers = TMDB_FORM_HEADERS,
                    ),
                    expectedType = expectedType,
                )
                if (response !is TmdbCatalogueResponse.Unavailable) {
                    return response
                }
                lastFailure = response.cause
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: CatalogSourceException) {
                lastFailure = error
            } catch (error: IOException) {
                lastFailure = TmdbCatalogSourceException(cause = error)
            }
            if (attempt + 1 < CATALOG_REQUEST_ATTEMPTS) {
                delay(CATALOG_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return TmdbCatalogueResponse.Unavailable(lastFailure)
    }

    private fun buildTmdbRecommendationEndpoint(
        type: MediaType,
        page: Int,
    ): String = "/discover/${type.routeName}" +
        if (page.coerceAtLeast(1) == 1) "" else "/items"

    internal fun buildTmdbRecommendationFields(
        spec: CatalogDiscoverySpec,
        page: Int,
        sort: String,
    ): Map<String, String> {
        val type = spec.mediaKind.mediaType
        val includedIds = spec.includedGenres.flatMap { genre ->
            GenreCatalog.specFor(genre, type)?.genreIds.orEmpty()
        }.distinct()
        val datePrefix = if (type == MediaType.MOVIE) {
            "primary_release_date"
        } else {
            "first_air_date"
        }
        return linkedMapOf<String, String>().apply {
            put("include_adult", "false")
            put("sort_by", sort)
            put("page", page.coerceAtLeast(1).toString())
            if (includedIds.isNotEmpty()) {
                put("with_genres", includedIds.joinToString(","))
            }
            spec.yearMinimum?.let { put("$datePrefix.gte", "$it-01-01") }
            put("$datePrefix.lte", "${spec.yearMaximum ?: currentYear()}-12-31")
            spec.runtimeMinimumMinutes?.let {
                put("with_runtime.gte", it.toString())
            }
            spec.runtimeMaximumMinutes?.let {
                put("with_runtime.lte", it.toString())
            }
            spec.minimumTmdb?.let { put("vote_average.gte", it.toString()) }
            spec.originalLanguage?.let { language ->
                put("with_original_language", languageCode(language))
            }
            if (sort == "vote_average.desc") put("vote_count.gte", "50")
            put("language", "en-US")
        }
    }

    private suspend fun loadImdbRecommendationPage(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor,
    ): CatalogRecommendationPage = supervisorScope {
        if (cursor.imdbTmdbFallback) {
            return@supervisorScope loadTmdbImdbFallbackPage(spec, cursor)
        }
        val popularityExhausted = "imdb_popularity" in cursor.exhaustedSources
        val ratingExhausted = "imdb_rating" in cursor.exhaustedSources
        val popularityAttempted = !popularityExhausted
        val ratingAttempted = !ratingExhausted
        val popularity = async {
            if (!popularityAttempted) {
                null
            } else try {
                loadImdbAdvancedPage(
                    spec = spec,
                    after = cursor.imdbPopularityCursor,
                    sortBy = "POPULARITY",
                    sortOrder = "ASC",
                    minimumVotes = 0,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: CatalogSourceException) {
                null
            }
        }
        val rating = async {
            if (!ratingAttempted) {
                null
            } else try {
                loadImdbAdvancedPage(
                    spec = spec,
                    after = cursor.imdbRatingCursor,
                    sortBy = "USER_RATING",
                    sortOrder = "DESC",
                    minimumVotes = IMDB_RATING_STREAM_MIN_VOTES,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: CatalogSourceException) {
                null
            }
        }
        val graphPopularity = popularity.await()
        val graphRating = rating.await()
        val popularityFailed = popularityAttempted && graphPopularity == null
        val ratingFailed = ratingAttempted && graphRating == null
        val attemptedCount =
            listOf(popularityAttempted, ratingAttempted).count { it }
        val allAttemptedFailed = attemptedCount > 0 &&
            (!popularityAttempted || popularityFailed) &&
            (!ratingAttempted || ratingFailed)
        val graphPartial = popularityFailed || ratingFailed
        if (allAttemptedFailed) {
            return@supervisorScope loadTmdbImdbFallbackPage(
                spec = spec,
                cursor = cursor,
            )
        }
        val popularityPage = graphPopularity ?: ImdbAdvancedPage(
            items = emptyList(),
            endCursor = cursor.imdbPopularityCursor,
            hasNextPage = popularityFailed,
        )
        val ratingPage = graphRating ?: ImdbAdvancedPage(
            items = emptyList(),
            endCursor = cursor.imdbRatingCursor,
            hasNextPage = ratingFailed,
        )
        val pages = popularityPage to ratingPage
        val imdbSourceStatus = if (graphPartial) {
            RecommendationSourceStatus.DEGRADED
        } else {
            RecommendationSourceStatus.AVAILABLE
        }
        val imdbTitles = interleaveImdbTitles(
            popularity = pages.first.items,
            rating = pages.second.items,
            limit = IMDB_RESOLUTION_CANDIDATE_LIMIT,
        )
        val requestGate = Semaphore(RECOMMENDATION_RESOLUTION_CONCURRENCY)
        val resolutions = imdbTitles.map { title ->
            async {
                requestGate.withPermit {
                    when (
                        val search = searchTmdbOutcome(
                            query = title.title,
                            types = listOf(spec.mediaKind.mediaType.routeName),
                            retryEmpty = true,
                        )
                    ) {
                        is TmdbSearchOutcome.Unavailable ->
                            ImdbTitleResolution.Unavailable(search.cause)
                        is TmdbSearchOutcome.Success -> {
                            val match = selectResolvedPlotMatch(
                                PlotCandidate(
                                    title = title.title,
                                    year = title.year?.toString(),
                                    type = spec.mediaKind.mediaType,
                                    evidence = title.overview,
                                    source = PlotSource.IMDB,
                                    position = title.position,
                                ),
                                search.items,
                            ) ?: return@withPermit ImdbTitleResolution.NoMatch
                            ImdbTitleResolution.Resolved(
                                RecommendationDiscoveryItem(
                                    media = match.media.copy(
                                        overview = match.media.overview.ifBlank {
                                            title.overview
                                        },
                                        year = match.media.year.ifBlank {
                                            title.year?.toString().orEmpty()
                                        },
                                        imdbRating = title.rating,
                                        genres = (
                                            title.genres + match.media.genres
                                            ).distinct(),
                                    ),
                                    metadata = CatalogVerifiedMetadata(
                                        genresVerified = title.genres.isNotEmpty(),
                                        runtimeMinutes = title.runtimeMinutes
                                            .takeIf {
                                                spec.mediaKind ==
                                                    RecommendationMediaKind.MOVIE
                                            },
                                        averageEpisodeRuntimeMinutes =
                                            title.runtimeMinutes.takeIf {
                                                spec.mediaKind ==
                                                    RecommendationMediaKind.SERIES
                                            },
                                    ),
                                    evidence = title.overview,
                                    sources = setOf("IMDB"),
                                    sourceCount = 1,
                                    sourcePosition = title.position,
                                ),
                            )
                        }
                    }
                }
            }
        }.awaitAll()
        val resolved = resolutions
            .filterIsInstance<ImdbTitleResolution.Resolved>()
            .map(ImdbTitleResolution.Resolved::item)
        val resolverFailures = resolutions
            .filterIsInstance<ImdbTitleResolution.Unavailable>()
        if (
            resolved.isEmpty() &&
            resolverFailures.size * 2 > imdbTitles.size
        ) {
            throw TmdbCatalogSourceException(
                "TMDB title resolution is unavailable.",
                resolverFailures.firstNotNullOfOrNull(
                    ImdbTitleResolution.Unavailable::cause,
                ),
            )
        }
        val hasMore = pages.first.hasNextPage || pages.second.hasNextPage
        val exhausted = buildSet {
            addAll(cursor.exhaustedSources)
            if (
                popularityAttempted &&
                !popularityFailed &&
                !pages.first.hasNextPage
            ) {
                add("imdb_popularity")
            }
            if (
                ratingAttempted &&
                !ratingFailed &&
                !pages.second.hasNextPage
            ) {
                add("imdb_rating")
            }
        }
        CatalogRecommendationPage(
            items = resolved,
            nextCursor = if (hasMore) {
                cursor.copy(
                    page = cursor.page + 1,
                    imdbPopularityCursor = pages.first.endCursor,
                    imdbRatingCursor = pages.second.endCursor,
                    imdbHtmlFallback = false,
                    exhaustedSources = exhausted,
                )
            } else {
                null
            },
            hasMore = hasMore,
            sourceHealth = RecommendationSourceHealth(
                catalogue = if (resolverFailures.isEmpty()) {
                    RecommendationSourceStatus.AVAILABLE
                } else {
                    RecommendationSourceStatus.DEGRADED
                },
                imdb = imdbSourceStatus,
            ),
        )
    }

    internal fun interleaveImdbTitles(
        popularity: List<ImdbAdvancedTitle>,
        rating: List<ImdbAdvancedTitle>,
        limit: Int = IMDB_RESOLUTION_CANDIDATE_LIMIT,
    ): List<ImdbAdvancedTitle> {
        if (limit <= 0) return emptyList()
        val streamLength = maxOf(popularity.size, rating.size)
        return (0 until streamLength)
            .flatMap { index ->
                listOfNotNull(
                    popularity.getOrNull(index),
                    rating.getOrNull(index),
                )
            }
            .distinctBy(ImdbAdvancedTitle::imdbId)
            .take(limit)
    }

    /**
     * IMDb can challenge its public search surfaces. In that case keep walking
     * the structured TMDB catalogue and verify only the IMDb field needed by
     * the hard filter. Unknown ratings remain unknown and therefore fail
     * closed in RecommendationRanker.
     */
    private suspend fun loadTmdbImdbFallbackPage(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor,
    ): CatalogRecommendationPage = supervisorScope {
        val tmdbPage = try {
            loadTmdbRecommendationPage(spec, cursor)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (catalogueError: CatalogSourceException) {
            throw ImdbCatalogSourceException(
                "IMDb and TMDB catalogues are unavailable.",
                catalogueError,
            )
        }
        val gate = Semaphore(RECOMMENDATION_RESOLUTION_CONCURRENCY)
        val verified = tmdbPage.items.map { seed ->
            async {
                gate.withPermit {
                    try {
                        val item = clientSafeImdbVerification(seed.media)
                        seed.copy(
                            media = item?.media ?: seed.media,
                            sources = seed.sources + "IMDB_SCAN",
                            sourceCount = (seed.sources + "IMDB_SCAN").size,
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        seed
                    }
                }
            }
        }.awaitAll()
        val imdbStatus = if (verified.any { it.media.imdbRating != null }) {
            RecommendationSourceStatus.DEGRADED
        } else {
            RecommendationSourceStatus.UNAVAILABLE
        }
        tmdbPage.copy(
            items = verified,
            nextCursor = tmdbPage.nextCursor?.copy(
                imdbHtmlFallback = false,
                imdbTmdbFallback = true,
            ),
            sourceHealth = tmdbPage.sourceHealth.copy(imdb = imdbStatus),
        )
    }

    private suspend fun clientSafeImdbVerification(
        media: Media,
    ): VerifiedRecommendationItem? = verifyRecommendationItem(
        item = media,
        requiredFields = RequiredMetadataFields(imdbRating = true),
    ).takeIf { it.media.imdbRating != null }

    private suspend fun loadImdbAdvancedPage(
        spec: CatalogDiscoverySpec,
        after: String?,
        sortBy: String,
        sortOrder: String,
        minimumVotes: Int,
    ): ImdbAdvancedPage {
        val constraints = buildList {
            val titleTypes = if (spec.mediaKind == RecommendationMediaKind.MOVIE) {
                listOf("movie")
            } else {
                listOf("tvSeries", "tvMiniSeries")
            }
            add(
                "titleTypeConstraint:{anyTitleTypeIds:[" +
                    titleTypes.joinToString(",") { JSONObject.quote(it) } +
                    "]}",
            )
            val genres = spec.includedGenres
                .map(::canonicalImdbGenre)
                .filter(String::isNotBlank)
                .distinctBy(String::lowercase)
            val excludedGenres = spec.excludedGenres
                .map(::canonicalImdbGenre)
                .filter(String::isNotBlank)
                .distinctBy(String::lowercase)
            if (genres.isNotEmpty() || excludedGenres.isNotEmpty()) {
                add(
                    "genreConstraint:{" +
                        if (genres.isNotEmpty()) {
                            "allGenreIds:[" +
                                genres.joinToString(",") {
                                    JSONObject.quote(it)
                                } +
                                "]"
                        } else {
                            ""
                        } +
                        if (genres.isNotEmpty() && excludedGenres.isNotEmpty()) {
                            ","
                        } else {
                            ""
                        } +
                        if (excludedGenres.isNotEmpty()) {
                            "excludeGenreIds:[" +
                                excludedGenres.joinToString(",") {
                                    JSONObject.quote(it)
                                } +
                                "]"
                        } else {
                            ""
                        } +
                        "}",
                )
            }
            val start = spec.yearMinimum ?: IMDB_EARLIEST_YEAR
            val end = spec.yearMaximum ?: currentYear()
            add(
                "releaseDateConstraint:{releaseDateRange:{" +
                    "start:\"$start-01-01\",end:\"$end-12-31\"}}",
            )
            spec.minimumImdb?.let { minimum ->
                add(
                    "userRatingsConstraint:{aggregateRatingRange:{min:$minimum}," +
                        "ratingsCountRange:{min:$minimumVotes}}",
                )
            }
            if (spec.runtimeMinimumMinutes != null ||
                spec.runtimeMaximumMinutes != null
            ) {
                val minimum = spec.runtimeMinimumMinutes ?: 0
                val maximum = spec.runtimeMaximumMinutes ?: IMDB_MAX_RUNTIME_MINUTES
                add(
                    "runtimeConstraint:{runtimeRangeMinutes:{" +
                        "min:$minimum,max:$maximum}}",
                )
            }
            add(
                "explicitContentConstraint:{" +
                    "explicitContentFilter:EXCLUDE_ADULT}",
            )
            spec.originalLanguage?.let { language ->
                add(
                    "languageConstraint:{anyPrimaryLanguages:[" +
                        JSONObject.quote(languageCode(language)) +
                        "]}",
                )
            }
        }.joinToString(",")
        val afterArgument = after
            ?.takeIf(String::isNotBlank)
            ?.let { ",after:${JSONObject.quote(it)}" }
            .orEmpty()
        val query = """
            query {
              advancedTitleSearch(
                first:$IMDB_GRAPH_PAGE_SIZE$afterArgument,
                constraints:{$constraints},
                sort:{sortBy:$sortBy,sortOrder:$sortOrder}
              ) {
                edges {
                  node {
                    title {
                      id
                      titleText { text }
                      releaseYear { year }
                      runtime { seconds }
                      titleGenres { genres { genre { text } } }
                      ratingsSummary { aggregateRating voteCount }
                      plots(first:1) {
                        edges { node { plotText { plainText } } }
                      }
                    }
                  }
                }
                pageInfo { hasNextPage endCursor }
              }
            }
        """.trimIndent()
        val body = JSONObject().put("query", query).toString()
        var lastFailure: Throwable? = null
        val result = withTimeoutOrNull(IMDB_ADVANCED_TOTAL_TIMEOUT_MS) {
            repeat(IMDB_ADVANCED_REQUEST_ATTEMPTS) { attempt ->
                try {
                    return@withTimeoutOrNull parseImdbAdvancedGraphql(
                        withTimeout(IMDB_GRAPH_REQUEST_TIMEOUT_MS) {
                            jsonPoster(IMDB_GRAPHQL_URL, body)
                        },
                    )
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    lastFailure = timeout
                    if (attempt + 1 < IMDB_ADVANCED_REQUEST_ATTEMPTS) {
                        delay(IMDB_ADVANCED_RETRY_DELAY_MS * (attempt + 1))
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    lastFailure = error
                    if (attempt + 1 < IMDB_ADVANCED_REQUEST_ATTEMPTS) {
                        delay(IMDB_ADVANCED_RETRY_DELAY_MS * (attempt + 1))
                    }
                } catch (error: org.json.JSONException) {
                    lastFailure = error
                    if (attempt + 1 < IMDB_ADVANCED_REQUEST_ATTEMPTS) {
                        delay(IMDB_ADVANCED_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
            null
        }
        if (result != null) return result
        throw ImdbCatalogSourceException(
            "IMDb advanced-title search is unavailable.",
            lastFailure,
        )
    }

    internal fun parseImdbAdvancedGraphql(payload: String): ImdbAdvancedPage {
        val root = JSONObject(payload)
        val result = root.optJSONObject("data")
            ?.optJSONObject("advancedTitleSearch")
            ?: throw IOException(
                root.optJSONArray("errors")
                    ?.optJSONObject(0)
                    ?.optString("message")
                    .orEmpty()
                    .ifBlank { "IMDb returned no advanced-title response." },
            )
        val edges = result.optJSONArray("edges") ?: JSONArray()
        val items = (0 until edges.length()).mapNotNull { index ->
            val title = edges.optJSONObject(index)
                ?.optJSONObject("node")
                ?.optJSONObject("title")
                ?: return@mapNotNull null
            val id = title.optString("id").takeIf { it.startsWith("tt") }
                ?: return@mapNotNull null
            val text = title.optJSONObject("titleText")
                ?.optString("text")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val ratingSummary = title.optJSONObject("ratingsSummary")
            val runtimeSeconds = title.optJSONObject("runtime")
                ?.optInt("seconds")
                ?.takeIf { it > 0 }
            val genreArray = title.optJSONObject("titleGenres")
                ?.optJSONArray("genres")
            val genres = if (genreArray == null) {
                emptyList()
            } else {
                (0 until genreArray.length()).mapNotNull { genreIndex ->
                    genreArray.optJSONObject(genreIndex)
                        ?.optJSONObject("genre")
                        ?.optString("text")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
            }
            val overview = title.optJSONObject("plots")
                ?.optJSONArray("edges")
                ?.optJSONObject(0)
                ?.optJSONObject("node")
                ?.optJSONObject("plotText")
                ?.optString("plainText")
                .orEmpty()
            ImdbAdvancedTitle(
                imdbId = id,
                title = text,
                year = title.optJSONObject("releaseYear")
                    ?.optInt("year")
                    ?.takeIf { it > 0 },
                rating = ratingSummary?.optDouble("aggregateRating")
                    ?.takeIf { it in 0.1..10.0 },
                voteCount = ratingSummary?.optInt("voteCount")
                    ?.takeIf { it >= 0 },
                runtimeMinutes = runtimeSeconds
                    ?.let { seconds -> (seconds / 60.0).roundToInt() },
                genres = genres,
                overview = overview,
                position = index,
            )
        }
        val pageInfo = result.optJSONObject("pageInfo")
        return ImdbAdvancedPage(
            items = items,
            endCursor = pageInfo?.optString("endCursor")?.takeIf(String::isNotBlank),
            hasNextPage = pageInfo?.optBoolean("hasNextPage") == true,
        )
    }

    private suspend fun loadImdbAdvancedHtmlFallback(
        spec: CatalogDiscoverySpec,
        cursor: RecommendationPageCursor,
    ): Pair<ImdbAdvancedPage, ImdbAdvancedPage>? = supervisorScope {
        val requests = listOf(
            "moviemeter,asc" to 0,
            "user_rating,desc" to IMDB_RATING_STREAM_MIN_VOTES,
        ).map { (sort, minimumVotes) ->
            async {
                try {
                    parseImdbAdvancedHtml(
                        pageLoader(
                            buildImdbAdvancedHtmlUrl(
                                spec = spec,
                                page = cursor.page,
                                sort = sort,
                                minimumVotes = minimumVotes,
                            ),
                        ),
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
        }.awaitAll()
        val popularity = requests.getOrNull(0)
        val rating = requests.getOrNull(1)
        if (popularity == null && rating == null) return@supervisorScope null
        (popularity ?: ImdbAdvancedPage(emptyList(), null, false)) to
            (rating ?: ImdbAdvancedPage(emptyList(), null, false))
    }

    private fun buildImdbAdvancedHtmlUrl(
        spec: CatalogDiscoverySpec,
        page: Int,
        sort: String,
        minimumVotes: Int,
    ): String {
        val parameters = buildList {
            add(
                "title_type=" +
                    if (spec.mediaKind == RecommendationMediaKind.MOVIE) {
                        "feature"
                    } else {
                        "tv_series,tv_miniseries"
                    },
            )
            val genres = spec.includedGenres.map {
                it.trim().lowercase().replace(' ', '-')
            } + spec.excludedGenres.map {
                "!" + it.trim().lowercase().replace(' ', '-')
            }
            if (genres.isNotEmpty()) {
                add(
                    "genres=" + urlEncode(genres.joinToString(",")),
                )
            }
            val start = (spec.yearMinimum ?: IMDB_EARLIEST_YEAR).let { "$it-01-01" }
            val end = (spec.yearMaximum ?: currentYear()).let { "$it-12-31" }
            add("release_date=$start,$end")
            spec.minimumImdb?.let { add("user_rating=$it,10") }
            if (minimumVotes > 0) add("num_votes=$minimumVotes,")
            if (spec.runtimeMinimumMinutes != null ||
                spec.runtimeMaximumMinutes != null
            ) {
                add(
                    "runtime=${spec.runtimeMinimumMinutes?.toString().orEmpty()}," +
                        spec.runtimeMaximumMinutes?.toString().orEmpty(),
                )
            }
            add("adult=exclude")
            add("sort=$sort")
            add("count=$IMDB_GRAPH_PAGE_SIZE")
            add("start=${(page - 1).coerceAtLeast(0) * IMDB_GRAPH_PAGE_SIZE + 1}")
        }
        return "$IMDB_ADVANCED_TITLE_URL?${parameters.joinToString("&")}"
    }

    internal fun parseImdbAdvancedHtml(html: String): ImdbAdvancedPage {
        if (html.isBlank()) throw IOException("IMDb returned an empty response.")
        val document = Jsoup.parse(html, IMDB_SITE_URL)
        if (html.contains("x-amzn-waf-action", ignoreCase = true) ||
            html.contains("captcha", ignoreCase = true)
        ) {
            throw IOException("IMDb returned an anti-bot challenge.")
        }
        val cards = document.select(
            "li.ipc-metadata-list-summary-item, .lister-item, " +
                "[data-testid=advanced-search-title-result]",
        )
        if (cards.isEmpty() &&
            document.select("[data-testid=results-section-empty], .no-results").isEmpty() &&
            !document.text().contains("no results", ignoreCase = true)
        ) {
            throw IOException("IMDb returned no parseable title results.")
        }
        val items = cards.mapIndexedNotNull { index, card ->
            val link = card.selectFirst("a[href*=/title/tt]") ?: return@mapIndexedNotNull null
            val imdbId = imdbTitleIdPattern.find(link.attr("href"))
                ?.groupValues
                ?.getOrNull(1)
                ?: return@mapIndexedNotNull null
            val rawTitle = sequenceOf(
                card.selectFirst("h3")?.text(),
                card.selectFirst(".ipc-title__text")?.text(),
                link.text(),
            ).filterNotNull().firstOrNull(String::isNotBlank)
                ?.replace(Regex("^\\d+\\.\\s*"), "")
                ?.trim()
                ?: return@mapIndexedNotNull null
            val metadata = card.select(".dli-title-metadata-item, .lister-item-year")
                .map { it.text().trim() }
            val ratingText = card.selectFirst(
                "[data-testid=ratingGroup--imdb-rating] .ipc-rating-star--rating, " +
                    ".ratings-imdb-rating strong, .ipc-rating-star--rating",
            )?.text().orEmpty()
            val runtimeText = metadata.firstOrNull {
                it.contains("min", ignoreCase = true) ||
                    Regex("""\d+\s*h""", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }.orEmpty()
            ImdbAdvancedTitle(
                imdbId = imdbId,
                title = rawTitle,
                year = metadata.firstNotNullOfOrNull { value ->
                    fourDigitYear.find(value)?.value?.toIntOrNull()
                },
                rating = Regex("""\d+(?:\.\d+)?""").find(ratingText)
                    ?.value
                    ?.toDoubleOrNull()
                    ?.takeIf { it in 0.1..10.0 },
                voteCount = card.selectFirst(".ipc-rating-star--voteCount")
                    ?.text()
                    ?.let(::parseAbbreviatedCount),
                runtimeMinutes = parseDurationMinutes(runtimeText),
                genres = card.select(".ipc-chip__text")
                    .map { it.text().trim() }
                    .filter(String::isNotBlank),
                overview = card.selectFirst(
                    ".ipc-html-content-inner-div, .lister-item-content p.text-muted",
                )?.text()?.trim().orEmpty(),
                position = index,
            )
        }.distinctBy(ImdbAdvancedTitle::imdbId)
        return ImdbAdvancedPage(
            items = items,
            endCursor = null,
            hasNextPage = items.size >= IMDB_HTML_PAGE_RESULT_FLOOR,
        )
    }

    private suspend fun indexedRedditRecommendationItems(
        spec: CatalogDiscoverySpec,
    ): List<RecommendationDiscoveryItem> = supervisorScope {
        val communities = if (spec.mediaKind == RecommendationMediaKind.MOVIE) {
            listOf("MovieSuggestions", "movies")
        } else {
            listOf("televisionsuggestions", "television")
        }
        val requests = communities.flatMap { community ->
            val encoded = urlEncode(
                "site:reddit.com/r/$community ${spec.discoveryText}",
            )
            listOf(
                async {
                    try {
                        parseIndexedRedditCandidates(
                            html = pageLoader(
                                "$BRAVE_SEARCH_URL?q=$encoded&source=web&" +
                                    "spellcheck=1&safesearch=strict",
                            ),
                            source = PlotSource.BRAVE,
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                },
                async {
                    try {
                        parseIndexedRedditCandidates(
                            html = pageLoader(
                                "$DUCKDUCKGO_HTML_URL/?q=$encoded&kp=1",
                            ),
                            source = PlotSource.DUCKDUCKGO,
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                },
            )
        }
        val discovered = requests.awaitAll()
        if (discovered.all { it == null }) {
            throw IOException("Indexed Reddit search is unavailable.")
        }
        val candidates = discovered.filterNotNull().flatten()
            .distinctBy(PlotCandidate::cacheKey)
            .take(RECOMMENDATION_REDDIT_TITLE_LIMIT)
        val gate = Semaphore(RECOMMENDATION_RESOLUTION_CONCURRENCY)
        candidates.map { candidate ->
            async {
                gate.withPermit {
                    try {
                        val results = searchTmdb(
                            candidate.title,
                            listOf(spec.mediaKind.mediaType.routeName),
                            retryEmpty = true,
                        )
                        selectResolvedPlotMatch(candidate, results)?.let { match ->
                            RecommendationDiscoveryItem(
                                media = match.media,
                                evidence = "",
                                sources = setOf("REDDIT_INDEX"),
                                sourceCount = 1,
                                sourcePosition = candidate.position,
                            )
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun startRecommendationSupplementDiscovery(
        spec: CatalogDiscoverySpec,
    ) {
        val requestJob = coroutineContext[Job]
        val supplementJob = synchronized(recommendationSupplementResults) {
            if (
                supplementsClosed ||
                recommendationSupplementResults.containsKey(spec.fingerprint)
            ) {
                return@synchronized null
            }
            activeSupplementJob
                ?.takeIf {
                    activeSupplementFingerprint == spec.fingerprint && it.isActive
                }
                ?.let { return@synchronized it }

            activeSupplementJob?.cancel(
                CancellationException("A newer recommendation request started"),
            )
            val generation = supplementGeneration.incrementAndGet()
            activeSupplementFingerprint = spec.fingerprint
            supplementScope.launch {
                val batch = supervisorScope {
                    val redditRequest = async {
                        withTimeoutOrNull(RECOMMENDATION_OPTIONAL_SOURCE_TIMEOUT_MS) {
                            try {
                                indexedRedditRecommendationItems(spec)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                    val editorialRequest = async {
                        withTimeoutOrNull(RECOMMENDATION_OPTIONAL_SOURCE_TIMEOUT_MS) {
                            try {
                                indexedEditorialRecommendationItems(spec)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                    val webRequest = async {
                        withTimeoutOrNull(RECOMMENDATION_OPTIONAL_SOURCE_TIMEOUT_MS) {
                            try {
                                recommendationCandidates(
                                    request = spec.discoveryText,
                                    requestedType = spec.mediaKind.mediaType,
                                )
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                    val reddit = redditRequest.await()
                    val editorial = editorialRequest.await()
                    val web = webRequest.await()
                    val webItems = web?.items.orEmpty()
                        .filter { it.sources.isNotEmpty() }
                        .map { item ->
                            val sources = item.sources + "WEB_DISCOVERY"
                            item.copy(
                                sources = sources,
                                sourceCount = maxOf(item.sourceCount, sources.size),
                            )
                        }
                    RecommendationSupplementBatch(
                        items = (
                            reddit.orEmpty() + editorial.orEmpty() + webItems
                            )
                            .filter { it.media.type == spec.mediaKind.mediaType }
                            .filter { isSafeTrendingItem(it.media) }
                            .groupBy { it.media.key }
                            .map { (_, matches) ->
                                matches.reduce(::mergeRecommendationDiscoveryItems)
                            },
                        webStatus = when {
                            web?.webAvailable == true || editorial?.isNotEmpty() == true ->
                                RecommendationSourceStatus.AVAILABLE
                            web != null || editorial != null ->
                                RecommendationSourceStatus.DEGRADED
                            else -> RecommendationSourceStatus.UNAVAILABLE
                        },
                        redditStatus = when {
                            reddit == null -> RecommendationSourceStatus.UNAVAILABLE
                            reddit.isEmpty() -> RecommendationSourceStatus.DEGRADED
                            else -> RecommendationSourceStatus.AVAILABLE
                        },
                    )
                }
                synchronized(recommendationSupplementResults) {
                    if (
                        !supplementsClosed &&
                        supplementGeneration.get() == generation &&
                        activeSupplementFingerprint == spec.fingerprint
                    ) {
                        recommendationSupplementResults[spec.fingerprint] = batch
                        if (
                            recommendationSupplementResults.size >
                            RECOMMENDATION_SUPPLEMENT_CACHE_LIMIT
                        ) {
                            recommendationSupplementResults.keys
                                .filterNot { it == spec.fingerprint }
                                .take(
                                    recommendationSupplementResults.size -
                                        RECOMMENDATION_SUPPLEMENT_CACHE_LIMIT,
                                )
                                .forEach(recommendationSupplementResults::remove)
                        }
                    }
                }
            }.also { activeSupplementJob = it }
        } ?: return

        requestJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                synchronized(recommendationSupplementResults) {
                    if (activeSupplementJob === supplementJob) {
                        supplementGeneration.incrementAndGet()
                        activeSupplementJob = null
                        activeSupplementFingerprint = null
                        supplementJob.cancel(cause)
                    }
                }
            }
        }
        supplementJob.invokeOnCompletion {
            synchronized(recommendationSupplementResults) {
                if (activeSupplementJob === supplementJob) {
                    activeSupplementJob = null
                    activeSupplementFingerprint = null
                }
            }
        }
    }

    private suspend fun indexedEditorialRecommendationItems(
        spec: CatalogDiscoverySpec,
    ): List<RecommendationDiscoveryItem> = supervisorScope {
        val siteClause = EDITORIAL_DOMAINS.joinToString(" OR ") { "site:$it" }
        val encoded = urlEncode(
            "($siteClause) ${spec.discoveryText}",
        )
        val discovered = listOf(
            async {
                try {
                    parseIndexedEditorialCandidates(
                        pageLoader(
                            "$BRAVE_SEARCH_URL?q=$encoded&source=web&" +
                                "spellcheck=1&safesearch=strict",
                        ),
                        PlotSource.BRAVE,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            },
            async {
                try {
                    parseIndexedEditorialCandidates(
                        pageLoader("$DUCKDUCKGO_HTML_URL/?q=$encoded&kp=1"),
                        PlotSource.DUCKDUCKGO,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            },
        ).awaitAll()
        if (discovered.all { it == null }) {
            throw IOException("Editorial discovery is unavailable.")
        }
        val candidates = discovered
            .filterNotNull()
            .flatten()
            .distinctBy(PlotCandidate::cacheKey)
            .take(RECOMMENDATION_EDITORIAL_TITLE_LIMIT)
        val gate = Semaphore(RECOMMENDATION_RESOLUTION_CONCURRENCY)
        candidates.map { candidate ->
            async {
                gate.withPermit {
                    try {
                        val results = searchTmdb(
                            candidate.title,
                            listOf(spec.mediaKind.mediaType.routeName),
                            retryEmpty = true,
                        )
                        selectResolvedPlotMatch(candidate, results)?.let { match ->
                            RecommendationDiscoveryItem(
                                media = match.media,
                                evidence = candidate.evidence,
                                sources = setOf("EDITORIAL_INDEX"),
                                sourceCount = 1,
                                sourcePosition = candidate.position,
                            )
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    internal fun parseIndexedEditorialCandidates(
        html: String,
        source: PlotSource,
    ): List<PlotCandidate> {
        val baseUrl = if (source == PlotSource.DUCKDUCKGO) {
            DUCKDUCKGO_HTML_URL
        } else {
            BRAVE_SEARCH_URL
        }
        val document = Jsoup.parse(html, baseUrl)
        val blocks = if (source == PlotSource.DUCKDUCKGO) {
            document.select(".result")
        } else {
            document.select("div.snippet[data-type=web]")
        }
        return blocks.flatMapIndexed { index, block ->
            val link = if (source == PlotSource.DUCKDUCKGO) {
                block.selectFirst("a.result__a")
            } else {
                block.selectFirst("a[href]")
            }
            val url = unwrapSearchRedirect(
                link?.attr("abs:href").orEmpty()
                    .ifBlank { link?.attr("href").orEmpty() },
            )
            if (!isAllowedEditorialUrl(url)) {
                return@flatMapIndexed emptyList()
            }
            val heading = sequenceOf(
                block.selectFirst(
                    ".title, .snippet-title, .search-snippet-title, " +
                        "[data-testid=result-title], h3",
                )?.text(),
                link?.text(),
            ).filterNotNull().firstOrNull(String::isNotBlank).orEmpty()
            val snippet = block.selectFirst(
                ".snippet-description, .description, .snippet-content, " +
                    ".result__snippet, p",
            )?.text().orEmpty()
            webResultCandidates(
                heading = heading,
                url = url,
                snippet = snippet,
                source = source,
                position = index,
            )
        }
            .filterNot { isGenericPlotResult(it.title) }
            .distinctBy(PlotCandidate::cacheKey)
    }

    private fun isAllowedEditorialUrl(value: String): Boolean = runCatching {
        val host = URI(value).host.orEmpty().lowercase().removePrefix("www.")
        EDITORIAL_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }.getOrDefault(false)

    internal fun parseIndexedRedditCandidates(
        html: String,
        source: PlotSource,
    ): List<PlotCandidate> {
        val baseUrl = if (source == PlotSource.DUCKDUCKGO) {
            DUCKDUCKGO_HTML_URL
        } else {
            BRAVE_SEARCH_URL
        }
        val document = Jsoup.parse(html, baseUrl)
        val blocks = if (source == PlotSource.DUCKDUCKGO) {
            document.select(".result")
        } else {
            document.select("div.snippet[data-type=web]")
        }
        return blocks.flatMapIndexed { index, block ->
            val link = if (source == PlotSource.DUCKDUCKGO) {
                block.selectFirst("a.result__a")
            } else {
                block.selectFirst("a[href]")
            }
            val rawUrl = link?.attr("abs:href")
                ?.takeIf(String::isNotBlank)
                ?: link?.attr("href").orEmpty()
            val url = unwrapSearchRedirect(rawUrl)
            if (!isIndexedRedditUrl(url)) return@flatMapIndexed emptyList()
            val heading = sequenceOf(
                block.selectFirst(
                    ".title, .snippet-title, .search-snippet-title, " +
                        "[data-testid=result-title], h3",
                )?.text(),
                link?.text(),
            ).filterNotNull().firstOrNull(String::isNotBlank).orEmpty()
            val snippet = block.selectFirst(
                ".snippet-description, .description, .snippet-content, " +
                    ".result__snippet, p",
            )?.text().orEmpty()
            val named = (
                extractNamedTitles(block) +
                    extractCapitalizedTitles("$heading. $snippet")
                ).distinct()
                .mapNotNull { value ->
                    plotCandidateFromTitle(
                        value = value,
                        evidence = "$heading. $snippet",
                        source = PlotSource.REDDIT,
                        position = index,
                    )
                }
            val direct = webResultCandidates(
                heading = heading,
                url = url,
                snippet = snippet,
                source = PlotSource.REDDIT,
                position = index,
            )
            (named + direct).filterNot { candidate ->
                normalizeText(candidate.title) in redditNonTitleTerms
            }
        }.filterNot { isGenericPlotResult(it.title) }
            .distinctBy(PlotCandidate::cacheKey)
    }

    private fun unwrapSearchRedirect(value: String): String {
        if (value.isBlank()) return value
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }
            .getOrDefault(value)
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        return uddg ?: decoded
    }

    private fun isIndexedRedditUrl(value: String): Boolean = runCatching {
        val host = URL(value).host.lowercase()
        (host == "reddit.com" || host.endsWith(".reddit.com")) &&
            URL(value).path.startsWith("/r/")
    }.getOrDefault(false)

    private fun mergeRecommendationDiscoveryItems(
        first: RecommendationDiscoveryItem,
        second: RecommendationDiscoveryItem,
    ): RecommendationDiscoveryItem {
        val preferred = if (
            second.media.imdbRating != null && first.media.imdbRating == null
        ) {
            second
        } else {
            first
        }
        return preferred.copy(
            sources = first.sources + second.sources,
            sourceCount = (first.sources + second.sources).size,
            sourcePosition = minOf(first.sourcePosition, second.sourcePosition),
            evidence = "",
        )
    }

    private fun rememberRecommendationItems(
        items: List<RecommendationDiscoveryItem>,
    ) {
        items.forEach { item ->
            recommendationMetadata.merge(item.media.key, item.metadata) { current, update ->
                CatalogVerifiedMetadata(
                    genresVerified =
                        current.genresVerified || update.genresVerified,
                    runtimeMinutes =
                        update.runtimeMinutes ?: current.runtimeMinutes,
                    originalLanguage =
                        update.originalLanguage ?: current.originalLanguage,
                    status = update.status ?: current.status,
                    director = update.director ?: current.director,
                    seasonCount = update.seasonCount ?: current.seasonCount,
                    averageEpisodeRuntimeMinutes =
                        update.averageEpisodeRuntimeMinutes
                            ?: current.averageEpisodeRuntimeMinutes,
                    verifiedAtMillis = maxOf(
                        current.verifiedAtMillis,
                        update.verifiedAtMillis,
                    ),
                )
            }
        }
    }

    private fun RecommendationDiscoveryItem.satisfiesKnownRequirements(
        spec: CatalogDiscoverySpec,
        required: RequiredMetadataFields,
    ): Boolean {
        if (required.genres && !metadata.genresVerified) return false
        val normalizedGenres = media.genres.mapTo(hashSetOf(), ::normalizeText)
        if (spec.includedGenres.any { normalizeText(it) !in normalizedGenres }) {
            return false
        }
        if (spec.excludedGenres.any { normalizeText(it) in normalizedGenres }) {
            return false
        }
        val year = fourDigitYear.find(media.year)?.value?.toIntOrNull()
        if (spec.yearMinimum != null &&
            (year == null || year < spec.yearMinimum)
        ) {
            return false
        }
        if (spec.yearMaximum != null &&
            (year == null || year > spec.yearMaximum)
        ) {
            return false
        }
        val runtime = if (media.type == MediaType.TV) {
            metadata.averageEpisodeRuntimeMinutes
        } else {
            metadata.runtimeMinutes
        }
        if ((required.runtime || required.tvEpisodeRuntime) &&
            runtime == null
        ) {
            return false
        }
        if (spec.runtimeMinimumMinutes != null &&
            required.runtimeOrEpisodeRuntime() &&
            (runtime == null || runtime < spec.runtimeMinimumMinutes)
        ) {
            return false
        }
        if (spec.runtimeMaximumMinutes != null &&
            required.runtimeOrEpisodeRuntime() &&
            (runtime == null || runtime > spec.runtimeMaximumMinutes)
        ) {
            return false
        }
        if (required.originalLanguage) {
            val actualLanguage = metadata.originalLanguage
                ?.takeIf(String::isNotBlank)
                ?: return false
            val wanted = spec.originalLanguage ?: return false
            val matches = normalizeText(actualLanguage) == normalizeText(wanted) ||
                languageCode(actualLanguage) == languageCode(wanted)
            if (!matches) return false
        }
        if (
            required.status &&
            !metadata.status.equals(spec.requiredStatus, ignoreCase = true)
        ) {
            return false
        }
        if (required.imdbRating &&
            (media.imdbRating ?: return false) < (spec.minimumImdb ?: 0.0)
        ) {
            return false
        }
        if (required.rottenTomatoesRating &&
            (media.rottenTomatoesRating ?: return false) <
            (spec.minimumRottenTomatoes ?: 0)
        ) {
            return false
        }
        if (required.tmdbRating &&
            media.rating < (spec.minimumTmdb ?: 0.0)
        ) {
            return false
        }
        return true
    }

    private fun RequiredMetadataFields.runtimeOrEpisodeRuntime(): Boolean =
        runtime || tvEpisodeRuntime

    private fun parseAbbreviatedCount(value: String): Int? {
        val normalized = value.lowercase().replace(",", "").trim()
        val number = Regex("""\d+(?:\.\d+)?""").find(normalized)
            ?.value
            ?.toDoubleOrNull()
            ?: return null
        val multiplier = when {
            "m" in normalized -> 1_000_000
            "k" in normalized -> 1_000
            else -> 1
        }
        return (number * multiplier).roundToInt()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(
        value,
        StandardCharsets.UTF_8.toString(),
    )

    private fun languageCode(value: String): String = when (
        normalizeText(value)
    ) {
        "english" -> "en"
        "spanish" -> "es"
        "french" -> "fr"
        "german" -> "de"
        "italian" -> "it"
        "japanese" -> "ja"
        "korean" -> "ko"
        "chinese", "mandarin" -> "zh"
        "arabic" -> "ar"
        "hindi" -> "hi"
        "portuguese" -> "pt"
        else -> value.trim().lowercase().take(3)
    }

    private fun canonicalImdbGenre(value: String): String = when (
        normalizeText(value)
    ) {
        "sci fi", "science fiction", "science fiction and fantasy" -> "Sci-Fi"
        "reality", "reality tv" -> "Reality-TV"
        "action and adventure", "action adventure" -> "Action"
        else -> value.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.replaceFirstChar(Char::uppercase)
            }
    }

    private fun currentYear(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    suspend fun recommendationCandidates(
        request: String,
        requestedType: MediaType? = null,
    ): RecommendationDiscoveryBatch = withContext(computationDispatcher) {
        supervisorScope {
        val cleanRequest = request.trim()
        if (cleanRequest.isBlank()) {
            val local = catalogue
                .asSequence()
                .filter(::isSafeTrendingItem)
                .filter { requestedType == null || it.type == requestedType }
                .distinctBy(Media::key)
                .take(RECOMMENDATION_RESULT_POOL)
                .map { RecommendationDiscoveryItem(media = it) }
                .toList()
            return@supervisorScope RecommendationDiscoveryBatch(local, webAvailable = false)
        }
        val queryKey = "${requestedType?.routeName.orEmpty()}:${normalizeText(cleanRequest)}"
        cacheStore?.loadRecommendations(
            queryKey,
            RECOMMENDATION_CACHE_MAX_AGE_MS,
        )?.let { cached ->
            catalogue = (cached.map(RecommendationDiscoveryItem::media) + catalogue)
                .distinctBy(Media::key)
            return@supervisorScope RecommendationDiscoveryBatch(cached, webAvailable = true)
        }

        val local = catalogue
            .asSequence()
            .filter(::isSafeTrendingItem)
            .filter { requestedType == null || it.type == requestedType }
            .sortedByDescending { PlotSearchRanker.relevanceScore(cleanRequest, it) }
            .take(RECOMMENDATION_LOCAL_SEED_LIMIT)
            .map { RecommendationDiscoveryItem(media = it) }
            .toList()

        val discovery = discoverRecommendationTitles(cleanRequest)
        val requestGate = Semaphore(RECOMMENDATION_RESOLUTION_CONCURRENCY)
        val resolved = discovery.candidates
            .sortedWith(
                compareByDescending<PlotCandidate> {
                    plotCandidateDiscoveryScore(cleanRequest, it)
                }.thenBy(PlotCandidate::position),
            )
            .distinctBy(PlotCandidate::cacheKey)
            .take(RECOMMENDATION_WEB_TITLE_LIMIT)
            .map { candidate ->
                async {
                    requestGate.withPermit {
                        val types = candidate.type?.let { listOf(it.routeName) }
                            ?: requestedType?.let { listOf(it.routeName) }
                            ?: listOf("movie", "tv")
                        val results = try {
                            searchTmdb(
                                query = candidate.title,
                                types = types,
                                retryEmpty = true,
                            )
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        selectResolvedPlotMatch(candidate, results)?.let { match ->
                            RecommendationDiscoveryItem(
                                media = match.media,
                                evidence = candidate.evidence,
                                sources = candidate.sources.map(PlotSource::name).toSet(),
                                sourceCount = candidate.sourceCount,
                                sourcePosition = candidate.position,
                            )
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .filter { requestedType == null || it.media.type == requestedType }
            .filter { isSafeTrendingItem(it.media) }

        val items = (resolved + local)
            .groupBy { it.media.key }
            .map { (_, matches) ->
                matches.reduce(::mergeRecommendationDiscoveryItems)
            }
            .distinctBy { it.media.key }
            .take(RECOMMENDATION_RESULT_POOL)
        catalogue = (items.map(RecommendationDiscoveryItem::media) + catalogue)
            .distinctBy(Media::key)
        if (items.isNotEmpty() && discovery.successfulSources > 0) {
            cacheStore?.saveRecommendations(queryKey, items)
        }
            RecommendationDiscoveryBatch(
                items = items,
                webAvailable = discovery.successfulSources > 0,
            )
        }
    }

    suspend fun verifyRecommendationItem(
        item: Media,
    ): VerifiedRecommendationItem = verifyRecommendationItem(
        item = item,
        requiredFields = RequiredMetadataFields(
            runtime = true,
            originalLanguage = true,
            imdbRating = true,
            rottenTomatoesRating = true,
            tvEpisodeRuntime = item.type == MediaType.TV,
        ),
    )

    suspend fun verifyRecommendationItem(
        item: Media,
        requiredFields: RequiredMetadataFields,
    ): VerifiedRecommendationItem = withContext(computationDispatcher) {
        supervisorScope {
        cacheStore?.loadVerifiedMetadata(
            item.key,
            RECOMMENDATION_METADATA_CACHE_MAX_AGE_MS,
        )?.takeIf { cached -> cached.satisfies(requiredFields) }?.let { cached ->
            recommendationMetadata[cached.media.key] = cached.metadata
            catalogue = (listOf(cached.media) + catalogue.filterNot {
                it.key == cached.media.key
            })
            return@supervisorScope cached
        }

        if (!requiredFields.needsTitlePage &&
            (!requiredFields.imdbRating || item.imdbRating != null) &&
            (!requiredFields.rottenTomatoesRating ||
                item.rottenTomatoesRating != null)
        ) {
            return@supervisorScope VerifiedRecommendationItem(
                media = item,
                metadata = CatalogVerifiedMetadata(),
            )
        }

        val pageHtml = if (requiredFields.needsTitlePage) {
            loadTitlePageWithRetry(item)
        } else {
            null
        }
        val parsed = pageHtml?.let {
            runCatching { parseTitleDetails(it, item) }.getOrDefault(item)
        } ?: item
        val imdbRequest = async {
            if (requiredFields.imdbRating && parsed.imdbRating == null) {
                suspendOrNull { loadImdbRating(parsed) }
            } else {
                parsed.imdbRating
            }
        }
        val rottenTomatoesRequest = async {
            if (
                requiredFields.rottenTomatoesRating &&
                parsed.rottenTomatoesRating == null
            ) {
                suspendOrNull { loadRottenTomatoesRating(parsed) }
            } else {
                parsed.rottenTomatoesRating
            }
        }
        val seasonsRequest = async {
            if (item.type == MediaType.TV && requiredFields.tvEpisodeRuntime) {
                seasons(parsed)
            } else {
                emptyList()
            }
        }
        val seasons = seasonsRequest.await()
        val episodes = if (item.type == MediaType.TV && requiredFields.tvEpisodeRuntime) {
            val firstSeason = seasons.firstOrNull()?.number
            if (firstSeason != null) episodes(parsed, firstSeason) else emptyList()
        } else {
            emptyList()
        }
        val enriched = parsed.copy(
            imdbRating = imdbRequest.await() ?: parsed.imdbRating,
            rottenTomatoesRating = rottenTomatoesRequest.await()
                ?: parsed.rottenTomatoesRating,
        )
        val metadata = parseVerifiedRecommendationMetadata(
            html = pageHtml.orEmpty(),
            type = item.type,
            seasons = seasons,
            episodes = episodes,
        )
        val verified = VerifiedRecommendationItem(enriched, metadata)
        recommendationMetadata[enriched.key] = metadata
        catalogue = (listOf(enriched) + catalogue.filterNot { it.key == enriched.key })
        cacheStore?.saveVerifiedMetadata(verified)
            verified
        }
    }

    private fun VerifiedRecommendationItem.satisfies(
        required: RequiredMetadataFields,
    ): Boolean {
        if (required.genres && !metadata.genresVerified) return false
        if (required.runtime && media.type == MediaType.MOVIE &&
            metadata.runtimeMinutes == null
        ) {
            return false
        }
        if (required.tvEpisodeRuntime && media.type == MediaType.TV &&
            metadata.averageEpisodeRuntimeMinutes == null
        ) {
            return false
        }
        if (required.originalLanguage && metadata.originalLanguage.isNullOrBlank()) {
            return false
        }
        if (required.status && metadata.status.isNullOrBlank()) return false
        if (required.imdbRating && media.imdbRating == null) return false
        if (required.rottenTomatoesRating &&
            media.rottenTomatoesRating == null
        ) {
            return false
        }
        if (required.tmdbRating && media.rating <= 0.0) return false
        return true
    }

    suspend fun resolveRecommendationAnchor(
        title: String,
    ): Media? = withContext(computationDispatcher) {
        searchTmdb(title.trim(), retryEmpty = true)
            .maxByOrNull { result ->
                PlotSearchRanker.literalTextRelevanceScore(title, result.title)
            }
    }

    suspend fun recommendationAlternativeTitles(item: Media): Set<String> =
        withContext(computationDispatcher) {
            suspendOrDefault(emptySet()) {
                parseRecommendationAlternativeTitles(
                    html = pageLoader(
                        "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
                    ),
                    canonicalTitle = item.title,
                )
            }
        }

    internal fun parseRecommendationAlternativeTitles(
        html: String,
        canonicalTitle: String,
    ): Set<String> {
        if (html.isBlank()) return emptySet()
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val labels = setOf("original title", "original name", "also known as")
        return document.select("p")
            .asSequence()
            .mapNotNull { paragraph ->
                val label = paragraph.selectFirst("strong")?.text()
                    ?.trim()
                    ?.trimEnd(':')
                    ?.lowercase()
                    ?: return@mapNotNull null
                if (label !in labels) return@mapNotNull null
                paragraph.text()
                    .replaceFirst(
                        Regex("^${Regex.escape(label)}:?\\s*", RegexOption.IGNORE_CASE),
                        "",
                    )
                    .trim()
                    .takeIf(String::isNotBlank)
            }
            .flatMap { value -> value.split(';', '/', '|').asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals(canonicalTitle, ignoreCase = true) }
            .toSet()
    }

    suspend fun relatedRecommendationItems(
        item: Media,
    ): List<CatalogRelatedItem> = withContext(computationDispatcher) {
        val pageRecommendations = suspendOrDefault(emptyList()) {
            parseRelatedResults(
                pageLoader(
                    "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
                ),
            )
        }
        val localRecommendations = RelatedContentEngine.rank(
            source = item,
            candidates = catalogue,
            candidateScoreLimit = RECOMMENDATION_LOCAL_RELATED_SCORE_LIMIT,
            resultLimit = RECOMMENDATION_RELATED_LIMIT,
        )
        val direct = pageRecommendations.mapIndexed { index, media ->
            CatalogRelatedItem(
                media = media,
                directProviderRelation = true,
                sourceRank = index,
            )
        }
        val heuristic = localRecommendations.mapIndexed { index, media ->
            CatalogRelatedItem(
                media = media,
                directProviderRelation = false,
                sourceRank = index,
            )
        }
        (direct + heuristic)
            .filter { related ->
                related.media.type == item.type &&
                    related.media.key != item.key &&
                    isSafeTrendingItem(related.media)
            }
            .distinctBy { it.media.key }
            .take(RECOMMENDATION_RELATED_LIMIT)
    }

    internal fun parseVerifiedRecommendationMetadata(
        html: String,
        type: MediaType,
        seasons: List<Season> = emptyList(),
        episodes: List<Episode> = emptyList(),
    ): CatalogVerifiedMetadata {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val runtime = document.selectFirst(".runtime")
            ?.text()
            ?.let(::parseDurationMinutes)
        fun fact(label: String): String? = document.select("p")
            .firstOrNull { paragraph ->
                paragraph.selectFirst("strong")?.text()
                    ?.contains(label, ignoreCase = true) == true
            }
            ?.text()
            ?.replace(
                Regex("^${Regex.escape(label)}\\s*", RegexOption.IGNORE_CASE),
                "",
            )
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val director = document.select("li.profile, ol.people li")
            .firstOrNull { profile ->
                profile.text().contains("Director", ignoreCase = true)
            }
            ?.selectFirst("p a[href^=/person/], a[href^=/person/]")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val episodeRuntimes = episodes.mapNotNull { episode ->
            parseDurationMinutes(episode.runtime)
        }
        return CatalogVerifiedMetadata(
            genresVerified = document.select("a[href^=/genre/]").isNotEmpty(),
            runtimeMinutes = runtime.takeIf { type == MediaType.MOVIE },
            originalLanguage = fact("Original Language"),
            status = fact("Status"),
            director = director,
            seasonCount = seasons.size.takeIf { type == MediaType.TV && seasons.isNotEmpty() },
            averageEpisodeRuntimeMinutes = episodeRuntimes
                .takeIf(List<Int>::isNotEmpty)
                ?.average()
                ?.roundToInt(),
        )
    }

    private suspend fun loadTitlePageWithRetry(item: Media): String? {
        var lastFailure: Throwable? = null
        repeat(RECOMMENDATION_METADATA_ATTEMPTS) { attempt ->
            try {
                return pageLoader(
                    "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt + 1 < RECOMMENDATION_METADATA_ATTEMPTS) {
                    delay(RECOMMENDATION_METADATA_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        if (lastFailure is IOException) return null
        return null
    }

    private fun parseDurationMinutes(value: String): Int? {
        val hours = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
        val minutes = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private suspend fun discoverRecommendationTitles(
        request: String,
    ): PlotDiscovery {
        val result = WebTitleDiscovery(
            brave = {
                val query = URLEncoder.encode(
                    "best movie or tv show recommendations $request",
                    StandardCharsets.UTF_8.toString(),
                )
                parseBravePlotCandidates(
                    pageLoader(
                        "$BRAVE_SEARCH_URL?q=$query&source=web&spellcheck=1&safesearch=strict",
                    ),
                )
            },
            wikipedia = { wikipediaPlotCandidates(request) },
            duckDuckGo = { duckDuckGoRecommendationCandidates(request) },
            keyOf = PlotCandidate::cacheKey,
            merge = ::mergePlotCandidates,
            sortScore = { plotCandidateDiscoveryScore(request, it) },
            computationDispatcher = computationDispatcher,
        ).discover()
        return PlotDiscovery(
            candidates = result.items,
            successfulSources = result.successfulSources,
        )
    }

    private suspend fun duckDuckGoRecommendationCandidates(
        request: String,
    ): List<PlotCandidate> {
        val query = URLEncoder.encode(
            "best movies and tv shows $request",
            StandardCharsets.UTF_8.toString(),
        )
        val document = Jsoup.parse(
            pageLoader("$DUCKDUCKGO_HTML_URL/?q=$query&kp=1"),
            DUCKDUCKGO_HTML_URL,
        )
        val blocks = document.select(".result")
        val candidates = blocks.flatMapIndexed { index, result ->
            val link = result.selectFirst("a.result__a")
            webResultCandidates(
                heading = link?.text().orEmpty(),
                url = link?.attr("abs:href").orEmpty(),
                snippet = result.selectFirst(".result__snippet")?.text().orEmpty(),
                source = PlotSource.DUCKDUCKGO,
                position = index,
            )
        }
        if (candidates.isEmpty() && document.select(".no-results").isEmpty()) {
            throw IOException("DuckDuckGo returned no parseable recommendation results.")
        }
        return candidates
            .filterNot { isGenericPlotResult(it.title) }
            .distinctBy(PlotCandidate::cacheKey)
            .take(RECOMMENDATION_DDG_LIMIT)
    }

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

    internal fun parseBravePlotCandidates(html: String): List<PlotCandidate> {
        val document = Jsoup.parse(html, BRAVE_SEARCH_URL)
        if (
            document.select("div.snippet[data-type=web]").isEmpty() &&
            document.select(".search-snippet-title, a.result-header").isEmpty() &&
            (
                html.contains("captcha", ignoreCase = true) ||
                    html.contains("challenge", ignoreCase = true)
                )
        ) {
            throw IOException("Brave Search returned an anti-bot page.")
        }
        val webResults = document.select("div.snippet[data-type=web]")
            .flatMapIndexed { index, result ->
                val heading = result.selectFirst(
                    ".title, .snippet-title, .search-snippet-title, " +
                        "[data-testid=result-title], h3",
                )?.text().orEmpty()
                val link = result.selectFirst("a[href]")?.attr("abs:href").orEmpty()
                val snippet = result.selectFirst(
                    ".snippet-description, .description, .snippet-content, p",
                )?.text().orEmpty()
                webResultCandidates(
                    heading = heading,
                    url = link,
                    snippet = snippet,
                    source = PlotSource.BRAVE,
                    position = index,
                )
            }
        val directTitles = document.select(
            ".search-snippet-title, " +
                ".entity-infobox-header-title-row .line-clamp-2, " +
                ".entity-infobox-header-title, " +
                "a.result-header",
        ).mapIndexed { index, element ->
            val value = element.attr("title").takeIf(String::isNotBlank) ?: element.text()
            plotCandidateFromTitle(
                value = value,
                evidence = value,
                source = PlotSource.BRAVE,
                position = index,
            )
        }
        val answerTitles = document.select(
            ".inline-qa-answer, .entity-infobox-description, .generic-snippet",
        ).flatMapIndexed { index, element ->
            extractNamedTitles(element).map { title ->
                plotCandidateFromTitle(
                    value = title,
                    evidence = element.text(),
                    source = PlotSource.BRAVE,
                    position = index,
                )
            }
        }
        val resultElementsPresent = webResults.isNotEmpty() ||
            directTitles.isNotEmpty() ||
            document.select(".no-results, [data-testid=no-results]").isNotEmpty()
        if (!resultElementsPresent) {
            throw IOException("Brave Search returned no parseable result page.")
        }
        return (webResults + directTitles + answerTitles)
            .filterNotNull()
            .filterNot { candidate -> isGenericPlotResult(candidate.title) }
            .distinctBy(PlotCandidate::cacheKey)
    }

    private suspend fun wikipediaPlotCandidates(
        description: String,
    ): List<PlotCandidate> {
        val candidates = linkedMapOf<String, PlotCandidate>()
        var successfulQueries = 0
        var lastFailure: Throwable? = null
        buildWikipediaPlotQueries(description)
            .take(WIKIPEDIA_QUERY_LIMIT)
            .forEachIndexed { queryIndex, rawQuery ->
                val hits = try {
                    loadWikipediaSearchHits(rawQuery)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastFailure = error
                    return@forEachIndexed
                }
                successfulQueries += 1
                hits.forEachIndexed { index, hit ->
                    wikipediaCandidatesFromHit(
                        hit = hit,
                        queryIndex = queryIndex,
                        resultIndex = index,
                    ).forEach { candidate ->
                        if (isGenericPlotResult(candidate.title)) return@forEach
                        val existing = candidates[candidate.cacheKey]
                        candidates[candidate.cacheKey] = if (existing == null) {
                            candidate
                        } else {
                            mergePlotCandidates(existing, candidate)
                        }
                    }
                }
            }
        if (successfulQueries == 0) {
            throw IOException("Wikipedia search was unavailable.", lastFailure)
        }
        return candidates.values.toList()
    }

    private suspend fun loadWikipediaSearchHits(
        rawQuery: String,
    ): List<WikipediaSearchHit> = wikipediaRequestMutex.withLock {
        val elapsed = System.currentTimeMillis() - wikipediaLastRequestAt
        val waitBeforeRequest = WIKIPEDIA_MIN_REQUEST_INTERVAL_MS - elapsed
        if (waitBeforeRequest > 0) {
            delay(waitBeforeRequest)
        }
        val query = URLEncoder.encode(
            rawQuery,
            StandardCharsets.UTF_8.toString(),
        )
        var lastFailure: Throwable? = null
        repeat(WIKIPEDIA_REQUEST_ATTEMPTS) { attempt ->
            try {
                val payload = JSONObject(
                    pageLoader(
                        "$WIKIPEDIA_SEARCH_URL?action=query&list=search&format=json" +
                            "&utf8=1&srnamespace=0&srlimit=$WIKIPEDIA_RESULT_BATCH_SIZE" +
                            "&srprop=snippet&srsearch=$query",
                    ),
                )
                payload.optJSONObject("error")?.let { apiError ->
                    throw IOException(
                        apiError.optString("info").ifBlank {
                            "Wikipedia returned an API error."
                        },
                    )
                }
                val results = payload.optJSONObject("query")
                    ?.optJSONArray("search")
                    ?: throw IOException("Wikipedia returned no search response.")
                wikipediaLastRequestAt = System.currentTimeMillis()
                return@withLock (0 until results.length()).mapNotNull { index ->
                    val entry = results.optJSONObject(index) ?: return@mapNotNull null
                    WikipediaSearchHit(
                        title = entry.optString("title").trim(),
                        snippet = Jsoup.parse(entry.optString("snippet")).text(),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
                wikipediaLastRequestAt = System.currentTimeMillis()
                if (attempt + 1 < WIKIPEDIA_REQUEST_ATTEMPTS) {
                    delay(WIKIPEDIA_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw IOException("Wikipedia search was unavailable.", lastFailure)
    }

    private fun wikipediaCandidatesFromHit(
        hit: WikipediaSearchHit,
        queryIndex: Int,
        resultIndex: Int,
    ): List<PlotCandidate> {
        if (!isWikipediaMediaResult(hit.title, hit.snippet)) return emptyList()
        val evidence = "${hit.title}. ${hit.snippet}"
        val position = resultIndex + queryIndex * WIKIPEDIA_QUERY_POSITION_PENALTY
        val titles = buildList {
            add(hit.title)
            wikipediaSeasonTitle.find(hit.title)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            wikipediaParentheticalWork.find(hit.title)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { value ->
                    value.isNotBlank() &&
                        wikipediaParentheticalNonTitles.none { qualifier ->
                            value.contains(qualifier, ignoreCase = true)
                        }
                }
                ?.let(::add)
        }
        return titles
            .distinct()
            .mapNotNull { title ->
                plotCandidateFromTitle(
                    value = title,
                    evidence = evidence,
                    source = PlotSource.WIKIPEDIA,
                    position = position,
                )
            }
            .distinctBy(PlotCandidate::cacheKey)
    }

    private fun webResultCandidates(
        heading: String,
        url: String,
        snippet: String,
        source: PlotSource,
        position: Int,
    ): List<PlotCandidate> {
        val evidence = listOf(heading, snippet)
            .filter(String::isNotBlank)
            .joinToString(". ")
        val direct = plotCandidateFromTitle(
            value = heading,
            evidence = evidence,
            source = source,
            position = position,
        )
        val fromUrl = plotCandidateFromTrustedUrl(
            value = url,
            evidence = evidence,
            source = source,
            position = position,
        )
        return listOfNotNull(fromUrl, direct)
            .filterNot { candidate -> isGenericPlotResult(candidate.title) }
            .distinctBy(PlotCandidate::cacheKey)
    }

    private fun plotCandidateFromTrustedUrl(
        value: String,
        evidence: String,
        source: PlotSource,
        position: Int,
    ): PlotCandidate? {
        val unwrapped = unwrapSearchResultUrl(value)
        val uri = runCatching { URI(unwrapped) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val rawTitle = when {
            host.endsWith("wikipedia.org") && "/wiki/" in path -> path
                .substringAfter("/wiki/")
                .replace('_', ' ')
                .let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
            host.endsWith("themoviedb.org") -> Regex(
                """/(?:movie|tv)/\d+-([^/?#]+)""",
            ).find(path)?.groupValues?.getOrNull(1)?.replace('-', ' ')
            else -> null
        } ?: return null
        val type = when {
            "/movie/" in path -> MediaType.MOVIE
            "/tv/" in path -> MediaType.TV
            else -> null
        }
        return plotCandidateFromTitle(
            value = rawTitle,
            evidence = evidence,
            source = source,
            position = position,
            forcedType = type,
        )
    }

    private fun unwrapSearchResultUrl(value: String): String {
        val absolute = if (value.startsWith("//")) "https:$value" else value
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return absolute
        if (!uri.host.orEmpty().contains("duckduckgo.com", ignoreCase = true)) {
            return absolute
        }
        return uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val name = part.substringBefore('=')
                val rawValue = part.substringAfter('=', "")
                if (name == "uddg" && rawValue.isNotBlank()) {
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8.toString())
                } else {
                    null
                }
            }
            .firstOrNull()
            ?: absolute
    }

    private fun plotCandidateFromTitle(
        value: String,
        evidence: String = value,
        source: PlotSource = PlotSource.BRAVE,
        position: Int = 0,
        forcedType: MediaType? = null,
    ): PlotCandidate? {
        val year = fourDigitYear.find(value)?.value
            ?: fourDigitYear.find(evidence)?.value
        val qualifierText = "$value $evidence"
        val type = forcedType ?: when {
            qualifierText.contains("TV series", ignoreCase = true) ||
                qualifierText.contains("television series", ignoreCase = true) ||
                qualifierText.contains("miniseries", ignoreCase = true) -> MediaType.TV
            qualifierText.contains("film", ignoreCase = true) ||
                qualifierText.contains("movie", ignoreCase = true) -> MediaType.MOVIE
            else -> null
        }
        val quoted = Regex("""["“‘']([^"”’']{2,70})["”’']""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
        var candidate = quoted ?: value
            .substringBefore(" - Wikipedia")
            .substringBefore(" - IMDb")
            .substringBefore(" | ")
            .substringBefore(" - Rotten Tomatoes")
        val titledYear = Regex(
            """^(.+?)\s*\(((?:18|19|20|21)\d{2})(?:\s+(?:film|TV series|television series|miniseries))?\)(?:\s*[-–—|:].*)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(candidate.trim())
        if (titledYear != null) {
            candidate = titledYear.groupValues[1]
        }
        val title = candidate
            .substringBefore(" ⭐")
            .replace(
                Regex(
                    """\s*[-–—|:]\s*(?:plot|review|reviews|cast|trailer|ending|explained|movie|film|TV series|IMDb|Wikipedia).*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(
                Regex(
                    """\s*\((?:\d{4}\s+)?(?:film|TV series|television series|miniseries)\)\s*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim(' ', '-', '–', '—', ':', '"', '\'', '“', '”', '‘', '’')
        return title
            .takeIf { it.length in 2..90 && it.split(Regex("\\s+")).size <= 12 }
            ?.let {
                PlotCandidate(
                    title = it,
                    year = year,
                    type = type,
                    evidence = evidence,
                    source = source,
                    position = position,
                )
            }
    }

    private fun extractNamedTitles(element: org.jsoup.nodes.Element): List<String> {
        val emphasized = element.select("i, em, strong, b, a")
            .map { child -> child.text().trim() }
            .filter { title -> title.length in 2..90 }
        return (emphasized + extractCapitalizedTitles(element.text())).distinct()
    }

    private fun extractCapitalizedTitles(value: String): List<String> =
        value.split(Regex("""[?!;,]|\s+and\s+(?=[A-Z])"""))
            .asSequence()
            .flatMap { chunk -> capitalizedTitlePattern.findAll(chunk).map(MatchResult::value) }
            .map { it.trim(' ', '.', ',', ':', ';', '?', '!', '"', '\'') }
            .filter { it.length in 3..70 }
            .toList()

    private fun isGenericPlotResult(title: String): Boolean =
        genericWebResultTerms.any { term -> title.contains(term, ignoreCase = true) } ||
            title.startsWith("List of ", ignoreCase = true) ||
            title.startsWith("Category:", ignoreCase = true)

    private fun buildWikipediaPlotQueries(description: String): List<String> {
        val words = normalizeText(description)
            .split(' ')
            .asSequence()
            .filter { word -> word.length > 2 && word !in plotQueryStopWords }
            .distinct()
            .toList()
        val matchedGroups = wikipediaConceptGroups.filter { group ->
            group.triggers.any(words::contains)
        }
        val coveredWords = matchedGroups.flatMapTo(hashSetOf()) { group -> group.triggers }
        val conceptTerms = matchedGroups.flatMap { group ->
            val first = group.searchTerms.firstOrNull() ?: return@flatMap emptyList()
            val matchedTriggerCount = group.triggers.count(words::contains)
            val limit = if (' ' in first) 1 else matchedTriggerCount.coerceIn(1, 2)
            group.searchTerms.take(limit)
        }
        val contextTerms = words
            .filter { word -> word in wikipediaPlotContextTerms && word !in coveredWords }
            .take(WIKIPEDIA_CONTEXT_TERM_LIMIT)
        val fallbackTerms = if (matchedGroups.isEmpty()) {
            words.take(WIKIPEDIA_FALLBACK_TERM_LIMIT)
        } else {
            emptyList()
        }
        val focus = (conceptTerms + contextTerms + fallbackTerms)
            .distinct()
            .joinToString(" ")
            .ifBlank { words.take(WIKIPEDIA_FALLBACK_TERM_LIMIT).joinToString(" ") }
        val requestedTypes = SearchRanker.parseIntent(description).type
            ?.let(::listOf)
            ?: listOf(MediaType.MOVIE, MediaType.TV)
        return requestedTypes
            .map { type ->
                val mediaPrefix = if (type == MediaType.MOVIE) {
                    "movie"
                } else {
                    "television series"
                }
                "$mediaPrefix $focus"
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun isWikipediaMediaResult(title: String, snippet: String): Boolean {
        if (isGenericPlotResult(title)) return false
        val text = "$title $snippet".lowercase()
        val titleHasMediaQualifier = wikipediaTitleMediaQualifier.containsMatchIn(title)
        val hasMediaSignal = titleHasMediaQualifier ||
            wikipediaStrongMediaPatterns.any { pattern -> pattern.containsMatchIn(text) }
        val isBiography = wikipediaBiographyPattern.containsMatchIn(text) &&
            !titleHasMediaQualifier
        return hasMediaSignal && !isBiography
    }

    private fun mergePlotCandidates(
        first: PlotCandidate,
        second: PlotCandidate,
    ): PlotCandidate {
        val preferred = if (first.source.priority >= second.source.priority) first else second
        val other = if (preferred === first) second else first
        return preferred.copy(
            year = preferred.year ?: other.year,
            type = preferred.type ?: other.type,
            evidence = listOf(first.evidence, second.evidence)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(". ")
                .take(PLOT_EVIDENCE_LIMIT),
            position = minOf(first.position, second.position),
            sourceCount = first.sourceCount +
                if (first.source == second.source) 0 else second.sourceCount,
            sources = first.sources + second.sources,
        )
    }

    private fun plotCandidateDiscoveryScore(
        description: String,
        candidate: PlotCandidate,
    ): Double =
        PlotSearchRanker.textRelevanceScore(description, candidate.evidence) * 1.4 +
            candidate.source.priority +
            candidate.sourceCount * 8.0 -
            candidate.position.coerceAtMost(20) * 0.8

    private fun selectResolvedPlotMatch(
        candidate: PlotCandidate,
        results: List<Media>,
    ): ResolvedPlotCandidate? {
        val candidateTitle = normalizeText(candidate.title)
        return results
            .asSequence()
            .filter { candidate.type == null || it.type == candidate.type }
            .map { item ->
                val itemTitle = normalizeText(
                    item.title.replace(Regex("""\s*\([^()]*\)\s*$"""), ""),
                )
                val titleScore = when {
                    itemTitle == candidateTitle -> 130
                    itemTitle.startsWith(candidateTitle) ||
                        candidateTitle.startsWith(itemTitle) -> 78
                    else -> {
                        val expected = candidateTitle.split(' ').filter(String::isNotBlank).toSet()
                        val actual = itemTitle.split(' ').filter(String::isNotBlank).toSet()
                        if (expected.isEmpty()) 0 else (expected.intersect(actual).size * 70) /
                            expected.size
                    }
                }
                val yearScore = when {
                    candidate.year == null -> 0
                    item.year.startsWith(candidate.year) -> 15
                    else -> -12
                }
                item to titleScore + yearScore
            }
            .filter { (_, score) -> score >= MIN_PLOT_TITLE_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.let { (media, score) ->
                ResolvedPlotCandidate(candidate, media, score)
            }
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

    suspend fun details(item: Media): Pair<Media, List<Media>> = supervisorScope {
        val current = catalogue.firstOrNull { it.key == item.key } ?: item
        val pageRequest = async {
            suspendOrNull {
                pageLoader(
                    "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
                )
            }
        }
        val ratingsRequest = async {
            ratingsFor(current)
        }
        val pageHtml = pageRequest.await()
        val metadata = pageHtml?.let {
            runCatching { parseTitleDetails(it, current) }.getOrDefault(current)
        } ?: current
        val pageRecommendations = pageHtml?.let {
            runCatching { parseRelatedResults(it) }.getOrDefault(emptyList())
        }.orEmpty()
        var ratings = ratingsRequest.await()
        if (
            metadata.imdbId != null &&
            metadata.imdbId != current.imdbId &&
            ratings.imdbState != RatingSourceState.VERIFIED
        ) {
            ratings = ratingsFor(metadata)
        }
        val enriched = metadata.copy(
            imdbId = ratings.imdbId ?: metadata.imdbId,
            imdbRating = ratings.imdb ?: metadata.imdbRating,
            imdbVoteCount = ratings.imdbVoteCount ?: metadata.imdbVoteCount,
            imdbRatingState = ratings.imdbState ?: metadata.imdbRatingState,
            rottenTomatoesRating = ratings.rottenTomatoes
                ?: metadata.rottenTomatoesRating,
        )
        catalogue = (listOf(enriched) + catalogue.filterNot { it.key == enriched.key })

        val locallyRelated = RelatedContentEngine.rank(
            source = enriched,
            candidates = catalogue,
            candidateScoreLimit = RECOMMENDATION_LOCAL_RELATED_SCORE_LIMIT,
            resultLimit = 18,
        )
        val recommendations = (pageRecommendations + locallyRelated)
            .filter { it.type == item.type && it.key != item.key }
            .distinctBy(Media::key)
            .take(18)
        enriched to recommendations
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

    suspend fun episodes(item: Media, seasonNumber: Int): List<Episode> {
        if (item.type != MediaType.TV) return emptyList()
        val result = suspendOrDefault(emptyList()) {
            parseEpisodes(
                html = pageLoader(
                    "$TMDB_SITE_URL/tv/${item.id}/season/$seasonNumber?language=en-US",
                ),
                mediaId = item.id,
                seasonNumber = seasonNumber,
            )
        }
        if (result.isNotEmpty()) return result
        return suspendOrDefault(emptyList()) {
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
            .select("a[href*=\"imdb.com/title/tt\"], a[href^=\"https://www.imdb.com/title/tt\"]")
            .firstNotNullOfOrNull { link ->
                imdbTitleIdPattern.find(link.attr("href"))
                    ?.groupValues
                    ?.getOrNull(1)
            }
            ?: fallback.imdbId
        val runtime = document.selectFirst(".runtime")?.text()?.trim().orEmpty().ifBlank { fallback.runtime }
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
        )
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
                    ?: suspendOrNull { loadRottenTomatoesRating(item) }
            }
            val imdbSnapshot = imdb.await()
            ExternalRatings(
                imdb = imdbSnapshot?.rating,
                imdbId = imdbSnapshot?.identity?.imdbId,
                imdbVoteCount = imdbSnapshot?.voteCount,
                imdbState = imdbSnapshot?.state ?: RatingSourceState.UNAVAILABLE,
                rottenTomatoes = rottenTomatoes.await(),
            )
        }
        ratings.imdb?.let { imdbRatingsCache[item.key] = it }
        ratings.rottenTomatoes?.let { rottenTomatoesRatingsCache[item.key] = it }
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

    private suspend fun loadRottenTomatoesRating(item: Media): Int? {
        val slug = rottenTomatoesSlug(item.title)
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val year = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val directPaths = buildList {
            add("$expectedPrefix$slug")
            year?.let { add("$expectedPrefix${slug}_$it") }
        }.distinct()
        directPaths.forEach { path ->
            val direct = suspendOrNull {
                pageLoader("$ROTTEN_TOMATOES_URL$path")
            }
            parseRottenTomatoesRating(direct.orEmpty())?.let { return it }
        }

        val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
        val searchHtml = pageLoader("$ROTTEN_TOMATOES_URL/search?search=$query")
        val candidatePaths = parseRottenTomatoesCandidatePaths(searchHtml, item)
            .filterNot(directPaths::contains)
            .take(5)
        candidatePaths.forEach { path ->
            val page = suspendOrNull {
                pageLoader("$ROTTEN_TOMATOES_URL$path")
            }
            parseRottenTomatoesRating(page.orEmpty())?.let { return it }
        }
        return null
    }

    internal fun parseRottenTomatoesRating(html: String): Int? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        val scoreBoard = document.selectFirst("score-board")
        val attributeScore = sequenceOf(
            scoreBoard?.attr("tomatometerscore"),
            scoreBoard?.attr("tomatometerScore"),
            document.selectFirst(
                "media-scorecard rt-text[slot=criticsScore], " +
                    "rt-text[slot=criticsScore]",
            )?.text(),
            document.selectFirst(
                "[data-qa=tomatometer], [data-qa=score-panel-critics-score]",
            )?.text(),
        ).filterNotNull().mapNotNull { scoreText.find(it)?.value?.toIntOrNull() }.firstOrNull()
        if (attributeScore != null && attributeScore in 0..100) return attributeScore
        val visibleScore = visibleTomatometerPattern.find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (visibleScore != null && visibleScore in 0..100) return visibleScore
        return rottenTomatoesPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 0..100 }
        }
    }

    internal fun parseRottenTomatoesCandidatePaths(
        html: String,
        item: Media,
    ): List<String> {
        if (html.isBlank()) return emptyList()
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val wantedTitle = normalizeText(item.title)
        val wantedSlug = rottenTomatoesSlug(item.title)
        val wantedTokens = wantedTitle.split(' ').filter(String::isNotBlank).toSet()
        val wantedYear = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val scores = linkedMapOf<String, Int>()

        fun addCandidate(rawPath: String, context: String) {
            val path = rawPath.substringBefore('?').substringBefore('#').trimEnd('/')
            if (!path.startsWith(expectedPrefix)) return
            val slug = path.substringAfter(expectedPrefix).substringBefore('/')
            if (slug.isBlank()) return
            val normalizedSlug = normalizeText(slug.replace('_', ' '))
            val slugTokens = normalizedSlug.split(' ').filter(String::isNotBlank).toSet()
            val normalizedContext = normalizeText(context)
            var score = 0
            if (slug == wantedSlug) score += 180
            if (normalizedSlug == wantedTitle) score += 160
            if (
                normalizedSlug.contains(wantedTitle) ||
                wantedTitle.contains(normalizedSlug)
            ) {
                score += 70
            }
            score += (slugTokens intersect wantedTokens).size * 18
            if (normalizedContext.contains(wantedTitle)) score += 110
            if (wantedYear != null && (wantedYear in path || wantedYear in context)) score += 24
            scores[path] = maxOf(scores[path] ?: Int.MIN_VALUE, score)
        }

        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        document.select("a[href^=\"$expectedPrefix\"]").forEach { link ->
            addCandidate(link.attr("href"), link.parent()?.text().orEmpty() + " " + link.text())
        }
        val unescaped = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
        rottenTomatoesPathPattern.findAll(unescaped).forEach { match ->
            addCandidate(match.value, "")
        }
        return scores.entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .map(Map.Entry<String, Int>::key)
    }

    private fun rottenTomatoesSlug(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("['’]"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return normalized
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
        val wikipediaBiographyPattern = Regex(
            """\b(?:is|was|career as)\b.{0,45}\b""" +
                """(?:actor|actress|filmmaker|director|singer|musician|producer)\b""",
            RegexOption.IGNORE_CASE,
        )
        val wikipediaSeasonTitle = Regex(
            """^(.+?)\s+season\s+\d+\b.*$""",
            RegexOption.IGNORE_CASE,
        )
        val wikipediaParentheticalWork = Regex("""\(([^()]{2,70})\)\s*$""")
        val wikipediaParentheticalNonTitles = setOf(
            "film", "tv series", "television series", "miniseries", "episode",
            "character", "actor", "actress", "novel", "book", "game",
        )
        val wikipediaRequestMutex = Mutex()
        @Volatile
        var wikipediaLastRequestAt = 0L
        val wikipediaConceptGroups = listOf(
            WikipediaConceptGroup(
                triggers = setOf(
                    "dream", "dreams", "dreaming", "nightmare", "nightmares",
                    "subconscious", "sleep",
                ),
                searchTerms = listOf("dream", "subconscious", "sleep"),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "thief", "thieves", "steal", "steals", "stolen", "secret",
                    "secrets", "heist",
                ),
                searchTerms = listOf(
                    "thief", "steal", "infiltrate", "secret", "information",
                ),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "repeat", "repeats", "repeatedly", "relive", "relives",
                    "loop", "loops", "timeline",
                ),
                searchTerms = listOf("time loop", "repeat", "relive", "day"),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "chemistry", "teacher", "professor", "scientist",
                ),
                searchTerms = listOf("chemistry teacher", "teacher", "chemistry"),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "meth", "methamphetamine", "dealer", "drug", "drugs",
                ),
                searchTerms = listOf("drug dealer", "methamphetamine", "drug", "crime"),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "psychic", "experiment", "experiments", "government",
                ),
                searchTerms = listOf(
                    "psychic", "government", "experiment", "conspiracy",
                ),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "device", "machine", "technology", "invention",
                ),
                searchTerms = listOf("device", "technology", "machine"),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "therapist", "psychologist", "psychiatrist", "doctor",
                ),
                searchTerms = listOf(
                    "psychologist", "therapist", "psychiatrist", "doctor",
                ),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "space", "spaceship", "astronaut", "planet", "alien", "galaxy",
                ),
                searchTerms = listOf(
                    "space", "spaceship", "astronaut", "planet", "alien",
                ),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "memory", "memories", "amnesia", "forget", "forgets",
                ),
                searchTerms = listOf("memory", "amnesia", "forget"),
            ),
            WikipediaConceptGroup(
                triggers = setOf(
                    "robot", "android", "artificial", "intelligence", "machine",
                ),
                searchTerms = listOf(
                    "robot", "android", "artificial intelligence", "machine",
                ),
            ),
        )
        val capitalizedTitlePattern = Regex(
            """\b[A-Z][A-Za-z0-9:'-]*(?:\s+(?:(?:of|the|on|in|and|a|an|to)\s+)?[A-Z][A-Za-z0-9:'-]*){0,6}\b""",
        )
        val titleRoute = Regex("^/(movie|tv)/.*-(\\d+)$")
        val watchRoute = Regex("^/watch/(movie|tv)/(\\d+)$")
        val tmdbTitleRoute = Regex("^/(movie|tv)/(\\d+)(?:-|\\?|$)")
        val yearText = Regex("^\\d{4}(?:-\\d{2}-\\d{2})?$")
        val fourDigitYear = Regex("\\b(?:18|19|20|21)\\d{2}\\b")
        val imdbTitleIdPattern = Regex("""(?:/title/)?(tt\d+)""")
        val matchPercent = Regex("(\\d{1,3})%\\s*Match", RegexOption.IGNORE_CASE)
        val episodeCount = Regex("(\\d+)\\s+Episodes?", RegexOption.IGNORE_CASE)
        val scoreText = Regex("\\d{1,3}")
        val visibleTomatometerPattern = Regex(
            """(\d{1,3})%\s*(?:Avg\.\s*)?Tomatometer""",
            RegexOption.IGNORE_CASE,
        )
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
