package com.aliflix.app.data

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.RelatedContentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal data class PlotCandidate(
    val title: String,
    val year: String? = null,
    val type: MediaType? = null,
    val evidence: String = title,
    val source: PlotSource = PlotSource.BRAVE,
    val position: Int = 0,
    val sourceCount: Int = 1,
) {
    val cacheKey: String
        get() = title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim() + ":${type?.routeName.orEmpty()}"
}

internal enum class PlotSource(val priority: Int) {
    BRAVE(30),
    WIKIPEDIA(24),
    DUCKDUCKGO(20),
}

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

/**
 * Builds the native catalogue from public, server-rendered movie metadata pages.
 *
 * No TMDB account, API key, or copied third-party credential is needed. The
 * player remains separate and loads only after the user presses Play.
 */
class CatalogClient(
    private val cacheStore: CatalogCacheStore? = null,
    private val jsonPoster: suspend (String, String) -> String = ::postJson,
    private val pageLoader: suspend (String) -> String = ::downloadPage,
) {
    @Volatile
    private var catalogue: List<Media> = fallbackItems
    private val imdbRatingsCache = ConcurrentHashMap<String, Double>()
    private val rottenTomatoesRatingsCache = ConcurrentHashMap<String, Int>()
    private val tmdbSearchCache = ConcurrentHashMap<String, List<Media>>()
    private val verifiedGenrePageCache = ConcurrentHashMap<String, List<Media>>()
    private val verifiedGenrePageLocks = ConcurrentHashMap<String, Mutex>()
    private val genreBrowsePageCursor = ConcurrentHashMap<String, Int>()
    private val genreBrowseSeenKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var homeShownKeys: Set<String> = emptySet()

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

    suspend fun search(query: String): List<Media> = supervisorScope {
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

    /**
     * Finds likely titles from a natural-language plot description.
     *
     * Only titles discovered by an external web lookup may become candidates.
     * The in-memory home catalogue is never used as a fallback.
     */
    suspend fun searchByPlot(description: String): List<Media> = supervisorScope {
        val cleanDescription = description.trim()
        if (cleanDescription.isBlank()) return@supervisorScope emptyList()
        val queryKey = normalizeText(cleanDescription)
        cacheStore?.loadPlot(queryKey, PLOT_CACHE_MAX_AGE_MS)?.let { cached ->
            catalogue = (cached + catalogue).distinctBy(Media::key)
            return@supervisorScope cached
        }

        val intent = SearchRanker.parseIntent(cleanDescription)
        val discovery = discoverPlotCandidates(cleanDescription)
        if (discovery.successfulSources == 0) {
            throw IOException("Web lookup unavailable—try again.")
        }

        val requestGate = Semaphore(PLOT_RESOLUTION_CONCURRENCY)
        val candidatesToResolve = discovery.candidates
            .groupBy(PlotCandidate::source)
            .values
            .flatMap { sourceCandidates ->
                sourceCandidates
                    .sortedWith(
                        compareByDescending<PlotCandidate> { candidate ->
                            plotCandidateDiscoveryScore(
                                cleanDescription,
                                candidate,
                            )
                        }.thenBy(PlotCandidate::position),
                    )
                    .take(PLOT_CANDIDATES_PER_SOURCE_LIMIT)
            }
            .distinctBy(PlotCandidate::cacheKey)
            .take(PLOT_TITLE_SUGGESTION_LIMIT)
        val resolved = candidatesToResolve
            .map { candidate ->
                async {
                    requestGate.withPermit {
                        val types = candidate.type?.let { listOf(it.routeName) }
                            ?: intent.type?.let { listOf(it.routeName) }
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
                        selectResolvedPlotMatch(candidate, results)
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .filter { match -> intent.type == null || match.media.type == intent.type }

        val ranked = resolved
            .groupBy { match -> match.media.key }
            .map { (_, matches) ->
                matches.maxBy { match -> resolvedPlotScore(cleanDescription, match) }
            }
            .sortedByDescending { match -> resolvedPlotScore(cleanDescription, match) }
            .map(ResolvedPlotCandidate::media)
            .take(PLOT_RESULT_LIMIT)
        catalogue = (ranked + catalogue).distinctBy(Media::key)
        if (ranked.isNotEmpty()) cacheStore?.savePlot(queryKey, ranked)
        ranked
    }

    suspend fun browseGenre(
        genre: String,
        type: MediaType,
    ): List<Media> {
        val spec = GenreCatalog.specFor(genre, type) ?: return emptyList()
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
        return fetched.items
    }

    private suspend fun discoverPlotCandidates(
        description: String,
    ): PlotDiscovery {
        var successfulSources = 0
        val candidates = linkedMapOf<String, PlotCandidate>()

        fun collect(result: Result<List<PlotCandidate>>) {
            result.onSuccess { found ->
                successfulSources += 1
                found.forEach { candidate ->
                    val existing = candidates[candidate.cacheKey]
                    candidates[candidate.cacheKey] = if (existing == null) {
                        candidate
                    } else {
                        mergePlotCandidates(existing, candidate)
                    }
                }
            }
        }

        collect(
            capturePlotLookup {
                val query = URLEncoder.encode(
                    "what movie or tv show is this plot $description",
                    StandardCharsets.UTF_8.toString(),
                )
                parseBravePlotCandidates(
                    pageLoader("$BRAVE_SEARCH_URL?q=$query&source=web&spellcheck=1"),
                )
            },
        )
        collect(capturePlotLookup { wikipediaPlotCandidates(description) })
        collect(capturePlotLookup { duckDuckGoPlotCandidates(description) })
        return PlotDiscovery(
            candidates = candidates.values.sortedByDescending { candidate ->
                plotCandidateDiscoveryScore(description, candidate)
            },
            successfulSources = successfulSources,
        )
    }

    private suspend fun <T> capturePlotLookup(
        block: suspend () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
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
            withContext(Dispatchers.IO) { delay(waitBeforeRequest) }
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
                    withContext(Dispatchers.IO) {
                        delay(WIKIPEDIA_RETRY_DELAY_MS * (attempt + 1))
                    }
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

    private suspend fun duckDuckGoPlotCandidates(
        description: String,
    ): List<PlotCandidate> {
        val query = URLEncoder.encode(
            "movie or tv show where $description",
            StandardCharsets.UTF_8.toString(),
        )
        val document = Jsoup.parse(
            pageLoader("$DUCKDUCKGO_HTML_URL/?q=$query"),
            DUCKDUCKGO_HTML_URL,
        )
        val resultBlocks = document.select(".result")
        val fromBlocks = resultBlocks.flatMapIndexed { index, result ->
            val link = result.selectFirst("a.result__a")
            webResultCandidates(
                heading = link?.text().orEmpty(),
                url = link?.attr("abs:href").orEmpty(),
                snippet = result.selectFirst(".result__snippet")?.text().orEmpty(),
                source = PlotSource.DUCKDUCKGO,
                position = index,
            )
        }
        val legacy = if (resultBlocks.isEmpty()) {
            document.select("a.result__a").mapIndexedNotNull { index, result ->
                plotCandidateFromTitle(
                    value = result.text(),
                    evidence = result.text(),
                    source = PlotSource.DUCKDUCKGO,
                    position = index,
                )
            }
        } else {
            emptyList()
        }
        if (
            fromBlocks.isEmpty() &&
            legacy.isEmpty() &&
            document.select(".no-results, .no-results__message").isEmpty()
        ) {
            throw IOException("DuckDuckGo returned no parseable result page.")
        }
        return (fromBlocks + legacy)
            .filterNot { isGenericPlotResult(it.title) }
            .distinctBy(PlotCandidate::cacheKey)
            .take(20)
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

    private fun resolvedPlotScore(
        description: String,
        resolved: ResolvedPlotCandidate,
    ): Double {
        val resolvedMediaEvidence = listOf(
            resolved.media.title,
            resolved.media.overview,
            resolved.media.genres.joinToString(" "),
            resolved.media.cast.joinToString(" "),
        ).joinToString(" ")
        val score = PlotSearchRanker.relevanceScore(description, resolved.media) * 1.7 +
            PlotSearchRanker.textRelevanceScore(
                description,
                resolved.candidate.evidence,
            ) * 1.1 +
            PlotSearchRanker.literalTextRelevanceScore(
                description,
                resolvedMediaEvidence,
            ) * 2.4 +
            PlotSearchRanker.literalTextRelevanceScore(
                description,
                resolved.candidate.evidence,
            ) * 1.2 +
            resolved.resolutionScore * 0.25 +
            resolved.candidate.source.priority +
            resolved.candidate.sourceCount * 6.0 -
            resolved.candidate.position.coerceAtMost(20) * 0.5 +
            (
                PLOT_SOURCE_POSITION_WINDOW -
                    resolved.candidate.position.coerceAtMost(PLOT_SOURCE_POSITION_WINDOW)
                ) * PLOT_SOURCE_POSITION_WEIGHT
        return score
    }

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
    ): List<Media> = supervisorScope {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString(),
        )
        val resultGroups = types.map { type ->
            async {
                val cacheKey = "$type:${normalizeText(query)}"
                tmdbSearchCache[cacheKey]?.let { return@async it }
                repeat(if (retryEmpty) PLOT_TMDB_SEARCH_ATTEMPTS else 1) { attempt ->
                    try {
                        val parsed = parseSearchResults(
                            pageLoader(
                                "$TMDB_SITE_URL/search/$type" +
                                    "?query=$encoded&language=en-US",
                            ),
                        ).filter { item -> item.type.routeName == type }
                        if (parsed.isNotEmpty()) {
                            tmdbSearchCache[cacheKey] = parsed
                            return@async parsed
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Retry below for external plot candidates.
                    }
                    if (retryEmpty && attempt < PLOT_TMDB_SEARCH_ATTEMPTS - 1) {
                        delay(PLOT_TMDB_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
                emptyList()
            }
        }.awaitAll()
        val largestGroup = resultGroups.maxOfOrNull(List<Media>::size) ?: 0
        (0 until largestGroup)
            .flatMap { index ->
                resultGroups.mapNotNull { results -> results.getOrNull(index) }
            }
            .distinctBy(Media::key)
    }

    private suspend fun predictiveTitleSuggestion(
        query: String,
        intent: SearchRanker.SearchIntent,
    ): String? = runCatching {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString(),
        )
        val candidates = JSONObject(
            pageLoader("$IMDB_SUGGESTION_URL/$encoded.json"),
        ).optJSONArray("d") ?: return@runCatching null

        (0 until candidates.length())
            .mapNotNull { index -> candidates.optJSONObject(index) }
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
    }.getOrNull()

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
            runCatching {
                pageLoader(
                    "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
                )
            }.getOrNull()
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
        val ratings = ratingsRequest.await()
        val enriched = metadata.copy(
            imdbRating = ratings.imdb ?: metadata.imdbRating,
            rottenTomatoesRating = ratings.rottenTomatoes
                ?: metadata.rottenTomatoesRating,
        )
        catalogue = (listOf(enriched) + catalogue.filterNot { it.key == enriched.key })

        val sameType = catalogue.filter { it.type == item.type && it.key != item.key }
        val locallyRelated = RelatedContentEngine.rank(enriched, sameType)
        val recommendations = (pageRecommendations + locallyRelated)
            .filter { it.type == item.type && it.key != item.key }
            .distinctBy(Media::key)
            .take(18)
        enriched to recommendations
    }

    suspend fun seasons(item: Media): List<Season> {
        if (item.type != MediaType.TV) return emptyList()
        return runCatching {
            parseSeasons(
                html = pageLoader("$TMDB_SITE_URL/tv/${item.id}/seasons?language=en-US"),
                mediaId = item.id,
            )
        }.getOrDefault(emptyList())
    }

    suspend fun episodes(item: Media, seasonNumber: Int): List<Episode> {
        if (item.type != MediaType.TV) return emptyList()
        return runCatching {
            parseEpisodes(
                html = pageLoader(
                    "$TMDB_SITE_URL/tv/${item.id}/season/$seasonNumber?language=en-US",
                ),
                mediaId = item.id,
                seasonNumber = seasonNumber,
            )
        }.getOrDefault(emptyList())
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

    internal fun parseSearchResults(html: String): List<Media> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        return document.select("main div[data-object-id]").mapNotNull { card ->
            val titleLink = card.selectFirst("a[data-media-type][href] h2")?.parent()
                ?: return@mapNotNull null
            val match = tmdbTitleRoute.find(titleLink.attr("href")) ?: return@mapNotNull null
            val heading = titleLink.selectFirst("h2") ?: return@mapNotNull null
            val title = (heading.selectFirst("span")?.text() ?: heading.text()).trim()
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val poster = card.selectFirst("img.poster, img[alt=\"$title\"]")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
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
            )
        }.distinctBy(Media::key)
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
        return fallback.copy(
            title = title,
            overview = overview,
            posterPath = poster,
            backdropPath = socialImage ?: fallback.backdropPath ?: poster,
            year = year,
            rating = score,
            genres = genres,
            cast = cast,
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
        return document.select("main div.episode").mapNotNull { container ->
            val link = container.selectFirst(
                "a[data-episode-number][href], a[href*=\"/episode/\"]",
            ) ?: return@mapNotNull null
            val match = routePattern.matchEntire(link.attr("href")) ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val image = container.selectFirst("img.backdrop, img[src]")
            val title = container
                .selectFirst("h3 a, .title a, a[data-episode-number]")
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
        }.distinctBy(Episode::number).sortedBy(Episode::number)
    }

    private suspend fun ratingsFor(item: Media): ExternalRatings {
        val cachedImdb = imdbRatingsCache[item.key]
        val cachedRottenTomatoes = rottenTomatoesRatingsCache[item.key]
        if (cachedImdb != null && cachedRottenTomatoes != null) {
            return ExternalRatings(cachedImdb, cachedRottenTomatoes)
        }
        val ratings = supervisorScope {
            val imdb = async {
                cachedImdb ?: runCatching { loadImdbRating(item) }.getOrNull()
            }
            val rottenTomatoes = async {
                cachedRottenTomatoes
                    ?: runCatching { loadRottenTomatoesRating(item) }.getOrNull()
            }
            ExternalRatings(
                imdb = imdb.await(),
                rottenTomatoes = rottenTomatoes.await(),
            )
        }
        ratings.imdb?.let { imdbRatingsCache[item.key] = it }
        ratings.rottenTomatoes?.let { rottenTomatoesRatingsCache[item.key] = it }
        return ratings
    }

    private suspend fun loadImdbRating(item: Media): Double? {
        val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
        val suggestion = JSONObject(
            pageLoader("$IMDB_SUGGESTION_URL/$query.json"),
        )
        val candidates = suggestion.optJSONArray("d") ?: return null
        val wantedTitle = normalizeText(item.title)
        val wantedYear = item.year.take(4).toIntOrNull()
        val imdbId = (0 until candidates.length())
            .mapNotNull { index -> candidates.optJSONObject(index) }
            .filter { it.optString("id").startsWith("tt") }
            .maxByOrNull { candidate ->
                var confidence = 0
                val candidateTitle = normalizeText(candidate.optString("l"))
                if (candidateTitle == wantedTitle) confidence += 100
                if (
                    candidateTitle.contains(wantedTitle) ||
                    wantedTitle.contains(candidateTitle)
                ) {
                    confidence += 25
                }
                if (wantedYear != null && candidate.optInt("y") == wantedYear) confidence += 20
                val qualifier = candidate.optString("q").lowercase()
                if (item.type == MediaType.TV && "tv" in qualifier) confidence += 10
                if (item.type == MediaType.MOVIE && "feature" in qualifier) confidence += 10
                confidence
            }
            ?.optString("id")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val queryBody = JSONObject()
            .put(
                "query",
                "query { title(id: \"$imdbId\") { ratingsSummary { " +
                    "aggregateRating voteCount } } }",
            )
            .toString()
        val graph = JSONObject(
            jsonPoster("https://api.graphql.imdb.com/", queryBody),
        )
        val rating = graph
            .optJSONObject("data")
            ?.optJSONObject("title")
            ?.optJSONObject("ratingsSummary")
            ?.optDouble("aggregateRating")
        if (rating != null && rating in 0.1..10.0) return rating
        return runCatching {
            parseImdbRating(pageLoader("https://www.imdb.com/title/$imdbId/"))
        }.getOrNull()
    }

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
            val direct = runCatching {
                pageLoader("$ROTTEN_TOMATOES_URL$path")
            }.getOrNull()
            parseRottenTomatoesRating(direct.orEmpty())?.let { return it }
        }

        val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
        val searchHtml = pageLoader("$ROTTEN_TOMATOES_URL/search?search=$query")
        val candidatePaths = parseRottenTomatoesCandidatePaths(searchHtml, item)
            .filterNot(directPaths::contains)
            .take(5)
        candidatePaths.forEach { path ->
            val page = runCatching {
                pageLoader("$ROTTEN_TOMATOES_URL$path")
            }.getOrNull()
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
        const val PLOT_TITLE_SUGGESTION_LIMIT = 18
        const val PLOT_CANDIDATES_PER_SOURCE_LIMIT = 6
        const val PLOT_RESULT_LIMIT = 20
        const val PLOT_RESOLUTION_CONCURRENCY = 2
        const val PLOT_TMDB_SEARCH_ATTEMPTS = 3
        const val PLOT_TMDB_RETRY_DELAY_MS = 300L
        const val MIN_PLOT_TITLE_MATCH_SCORE = 62
        const val PLOT_EVIDENCE_LIMIT = 1_400
        const val PLOT_SOURCE_POSITION_WINDOW = 10
        const val PLOT_SOURCE_POSITION_WEIGHT = 40.0
        const val WIKIPEDIA_RESULT_BATCH_SIZE = 24
        const val WIKIPEDIA_QUERY_LIMIT = 2
        const val WIKIPEDIA_QUERY_POSITION_PENALTY = 3
        const val WIKIPEDIA_REQUEST_ATTEMPTS = 3
        const val WIKIPEDIA_MIN_REQUEST_INTERVAL_MS = 650L
        const val WIKIPEDIA_RETRY_DELAY_MS = 900L
        const val WIKIPEDIA_CONTEXT_TERM_LIMIT = 3
        const val WIKIPEDIA_FALLBACK_TERM_LIMIT = 8
        const val PLOT_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
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

        suspend fun downloadPage(url: String): String = withContext(Dispatchers.IO) {
            val isWikipediaApi = runCatching {
                URL(url).host.equals("en.wikipedia.org", ignoreCase = true)
            }.getOrDefault(false)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 18_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    if (isWikipediaApi) {
                        "AliflixAndroid/2.7.8 " +
                            "(https://github.com/alishaban144/aliflix-android)"
                    } else {
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
                    },
                )
                setRequestProperty(
                    "Accept",
                    if (isWikipediaApi) {
                        "application/json"
                    } else {
                        "text/html,application/xhtml+xml"
                    },
                )
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IOException("Catalogue request failed ($status)")
                if (body.isBlank()) throw IOException("Catalogue response was empty")
                body
            } finally {
                connection.disconnect()
            }
        }

        suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 18_000
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Aliflix/1.5 (personal Android app)")
            }
            try {
                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IOException("Metadata request failed ($status)")
                if (response.isBlank()) throw IOException("Metadata response was empty")
                response
            } finally {
                connection.disconnect()
            }
        }
    }
}
