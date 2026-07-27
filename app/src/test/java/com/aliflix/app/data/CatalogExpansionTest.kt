package com.aliflix.app.data

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CatalogExpansionTest {
    @Test
    fun homeLoads28VerifiedRowsWith20GloballyUniqueCorrectlyTypedItems() = runTest {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            pageLoader = { url ->
                requestedUrls += url
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                try {
                    delay(3)
                    catalogueHtml(url)
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val home = client.home()
        val genreTypes = GenreCatalog.homeSpecs.associate { it.title to it.type }
        val genreRails = home.rails.filter { it.title in genreTypes }
        val appearances = home.rails
            .flatMap(ContentRail::items)
            .groupingBy(Media::key)
            .eachCount()

        assertEquals(28, GenreCatalog.homeSpecs.size)
        assertEquals(28, genreRails.size)
        assertTrue(genreRails.all { it.items.size == 20 })
        assertTrue(
            genreRails.all { rail ->
                rail.items.all { item -> item.type == genreTypes.getValue(rail.title) }
            },
        )
        assertTrue(appearances.values.all { count -> count == 1 })
        assertTrue(maximumActive.get() <= 4)
        assertTrue(requestedUrls.none { "/discover/" in it })
        assertTrue(requestedUrls.any { "/genre/28-action/movie" in it })
        assertTrue(requestedUrls.any { "/genre/10765-sci-fi-fantasy/tv" in it })
    }

    @Test
    fun anyGenreRowsInterleaveEveryConfiguredSource() = runTest {
        val home = CatalogClient(
            pageLoader = { url -> catalogueHtml(url) },
        ).home()

        val familyAndKids = home.rails.first { it.title == "Family & Kids Series" }
        val mysteryAndScienceFiction =
            home.rails.first { it.title == "Mystery & Sci-Fi Series" }

        assertTrue(familyAndKids.items.count { "Family" in it.genres } >= 8)
        assertTrue(familyAndKids.items.count { "Kids" in it.genres } >= 8)
        assertTrue(mysteryAndScienceFiction.items.count { "Mystery" in it.genres } >= 8)
        assertTrue(
            mysteryAndScienceFiction.items.count { "Sci-Fi & Fantasy" in it.genres } >= 8,
        )
    }

    @Test
    fun failedAndShortStableGenrePagesRetryAndRefillFromDeeperPages() = runTest {
        val actionPageOneAttempts = AtomicInteger(0)
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            pageLoader = { url ->
                requestedUrls += url
                if (
                    "/genre/28-action/movie" in url &&
                    "page=1" in url &&
                    actionPageOneAttempts.incrementAndGet() <= 2
                ) {
                    throw IOException("temporary")
                }
                catalogueHtml(
                    url = url,
                    countOverride = if (
                        "/genre/28-action/movie" in url && "page=1" in url
                    ) {
                        8
                    } else {
                        null
                    },
                )
            },
        )

        val action = client.home().rails.first { it.title == "Action" }

        assertEquals(3, actionPageOneAttempts.get())
        assertEquals(20, action.items.size)
        assertTrue(action.items.all { it.type == MediaType.MOVIE })
        assertTrue(
            requestedUrls.any { url ->
                "/genre/28-action/movie" in url && "page=2" in url
            },
        )
    }

    @Test
    fun genericRedirectIsRejectedInsteadOfBeingStampedWithWrongGenre() {
        val client = CatalogClient(
            pageLoader = { url ->
                catalogueHtml(
                    url = url,
                    canonicalOverride = "https://www.themoviedb.org/movie",
                )
            },
        )

        val error = assertThrows(IOException::class.java) {
            runTest { client.browseGenre("Action", MediaType.MOVIE) }
        }

        assertTrue(error.message.orEmpty().contains("unseen"))
    }

    @Test
    fun detailGenreReturns40FreshTypedItemsAndAdvancesToAnotherDisjointBatch() = runTest {
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            pageLoader = { url ->
                requestedUrls += url
                catalogueHtml(url)
            },
        )
        val home = client.home()
        val homeKeys = home.rails.flatMap(ContentRail::items).mapTo(hashSetOf(), Media::key)

        val first = client.browseGenre("Action", MediaType.MOVIE)
        val second = client.browseGenre("Action", MediaType.MOVIE)

        assertEquals(40, first.size)
        assertEquals(40, second.size)
        assertTrue(first.all { it.type == MediaType.MOVIE && "Action" in it.genres })
        assertTrue(second.all { it.type == MediaType.MOVIE && "Action" in it.genres })
        assertTrue(homeKeys.intersect(first.mapTo(hashSetOf(), Media::key)).isEmpty())
        assertTrue(first.map(Media::key).intersect(second.map(Media::key).toSet()).isEmpty())
        val browsePages = requestedUrls
            .filter { "/genre/28-action/movie" in it }
            .mapNotNull(::pageNumber)
            .filter { it >= 6 }
            .toSet()
        assertTrue(setOf(6, 7, 8, 9).all { it in browsePages })
        assertTrue(requestedUrls.none { "/discover/" in it })
    }

    @Test
    fun movieAndSeriesGenreDestinationsNeverCrossMediaTypes() = runTest {
        val client = CatalogClient(pageLoader = { url -> catalogueHtml(url) })

        val movies = client.browseGenre("Comedy", MediaType.MOVIE)
        val series = client.browseGenre("Comedy", MediaType.TV)

        assertEquals(40, movies.size)
        assertEquals(40, series.size)
        assertTrue(movies.all { it.type == MediaType.MOVIE })
        assertTrue(series.all { it.type == MediaType.TV })
        assertTrue(movies.map(Media::key).intersect(series.map(Media::key).toSet()).isEmpty())
    }

    @Test
    fun completeCachedGenreRowsRemainAvailableWhenRefreshRequestsFail() = runTest {
        val cache = MemoryCatalogCache(home = cachedHome())
        val progress = mutableListOf<HomeContent>()
        val client = CatalogClient(
            cacheStore = cache,
            pageLoader = { throw IOException("offline") },
        )

        val result = client.home { progress += it }
        val appearances = result.rails
            .flatMap(ContentRail::items)
            .groupingBy(Media::key)
            .eachCount()

        assertTrue(progress.isNotEmpty())
        assertEquals(28, result.rails.size)
        assertTrue(result.rails.all { it.items.size == 20 })
        assertTrue(appearances.values.all { it == 1 })
    }

    @Test
    fun completeCachedSnapshotIsNotDowngradedByCollidingFreshAllocation() = runTest {
        val expected = cachedHome()
        val client = CatalogClient(
            cacheStore = MemoryCatalogCache(home = expected),
            pageLoader = { url -> collidingCatalogueHtml(url) },
        )

        val result = client.home()

        assertEquals(expected.rails, result.rails)
        assertEquals(28, result.rails.size)
        assertTrue(result.rails.all { it.items.size == 20 })
    }

    @Test
    fun genreBrowseDuringProgressExcludesAlreadyShownHomeCards() = runTest {
        var shownActionKeys = emptySet<String>()
        var browseDuringProgress: List<Media>? = null
        val client = CatalogClient(
            pageLoader = { url ->
                if ("/genre/28-action/movie" in url) {
                    actionOverlapCatalogueHtml(url)
                } else {
                    catalogueHtml(url)
                }
            },
        )

        client.home { partial ->
            if (browseDuringProgress == null) {
                val action = partial.rails.firstOrNull { it.title == "Action" }
                if (action?.items?.size == 20) {
                    shownActionKeys = action.items.mapTo(hashSetOf(), Media::key)
                    browseDuringProgress = client.browseGenre("Action", MediaType.MOVIE)
                }
            }
        }

        val genreItems = requireNotNull(browseDuringProgress)
        assertEquals(40, genreItems.size)
        assertTrue(shownActionKeys.intersect(genreItems.mapTo(hashSetOf(), Media::key)).isEmpty())
    }

    @Test
    fun globalBrowseHistorySurvivesHomeRefreshAcrossDifferentGenres() = runTest {
        val client = CatalogClient(pageLoader = { url -> catalogueHtml(url) })

        val action = client.browseGenre("Action", MediaType.MOVIE)
        client.home()
        val thriller = client.browseGenre("Thriller", MediaType.MOVIE)

        assertEquals(40, action.size)
        assertEquals(40, thriller.size)
        assertTrue(action.map(Media::key).intersect(thriller.map(Media::key).toSet()).isEmpty())
    }

    @Test
    fun tvMovieDetailGenreHasAStableMovieRoute() {
        val spec = GenreCatalog.specFor("TV Movie", MediaType.MOVIE)

        assertEquals(listOf(10770), spec?.genreIds)
        assertEquals(
            "/genre/10770-tv-movie/movie",
            GenreCatalog.pagePath(10770, MediaType.MOVIE),
        )
    }

    @Test
    fun plotSearchReturnsOnlyExternallyDiscoveredTitlesOutsideHomeCatalogue() = runTest {
        val external = listOf(
            Triple("Inception", "2010", MediaType.MOVIE),
            Triple("Paprika", "2006", MediaType.MOVIE),
            Triple("Dreamscape", "1984", MediaType.MOVIE),
            Triple("Groundhog Day", "1993", MediaType.MOVIE),
            Triple("Breaking Bad", "2008", MediaType.TV),
            Triple("The Matrix", "1999", MediaType.MOVIE),
            Triple("Arrival", "2016", MediaType.MOVIE),
            Triple("Dark", "2017", MediaType.TV),
        )
        val client = CatalogClient(
            pageLoader = { url ->
                when {
                    "search.brave.com" in url -> braveHtml(external)
                    "wikipedia.org/w/api.php" in url -> emptyWikipedia()
                    "duckduckgo.com" in url -> """<div class="no-results"></div>"""
                    "/search/" in url -> tmdbResolvedTitleHtml(url, external)
                    else -> error("Unexpected request: $url")
                }
            },
        )

        val results = client.searchByPlot(
            "stories involving dreams and a chemistry teacher becoming a drug dealer",
        )
        val titles = results.map(Media::title)

        assertTrue("Inception" in titles)
        assertTrue("Paprika" in titles)
        assertTrue("Dreamscape" in titles)
        assertTrue("Groundhog Day" in titles)
        assertTrue("Breaking Bad" in titles)
        assertFalse("Parasite" in titles)
    }

    @Test
    fun realShapedDuckDuckGoHeadingResolvesCleanTitleYearAndType() = runTest {
        val client = CatalogClient(
            pageLoader = { url ->
                when {
                    "search.brave.com" in url -> """<html>captcha challenge</html>"""
                    "wikipedia.org/w/api.php" in url -> emptyWikipedia()
                    "duckduckgo.com" in url -> """
                        <div class="result">
                          <a class="result__a"
                             href="https://www.imdb.com/title/tt1375666/">
                            Inception (2010) - Plot - IMDb
                          </a>
                          <a class="result__snippet">
                            A thief enters shared dreams to steal secrets.
                          </a>
                        </div>
                    """.trimIndent()
                    "/search/movie" in url -> searchHtml(
                        type = MediaType.MOVIE,
                        items = listOf(
                            FixtureItem(
                                id = 27205,
                                title = "Inception",
                                year = "2010",
                                overview = "A thief enters shared dreams to steal secrets.",
                            ),
                        ),
                    )
                    "/search/tv" in url -> "<main></main>"
                    else -> error("Unexpected request: $url")
                }
            },
        )

        val results = client.searchByPlot("a thief enters dreams to steal secrets")

        assertEquals(listOf("Inception"), results.map(Media::title))
    }

    @Test
    fun normalizedPlotQueryUsesThe24HourCacheWithoutAnotherWebLookup() = runTest {
        val cache = MemoryCatalogCache()
        val webCalls = AtomicInteger(0)
        val external = listOf(Triple("Breaking Bad", "2008", MediaType.TV))
        val client = CatalogClient(
            cacheStore = cache,
            pageLoader = { url ->
                when {
                    "search.brave.com" in url -> {
                        webCalls.incrementAndGet()
                        braveHtml(external)
                    }
                    "wikipedia.org/w/api.php" in url -> emptyWikipedia()
                    "duckduckgo.com" in url -> """<div class="no-results"></div>"""
                    "/search/" in url -> tmdbResolvedTitleHtml(url, external)
                    else -> error("Unexpected request: $url")
                }
            },
        )

        val first = client.searchByPlot("A chemistry teacher becomes a drug dealer")
        val callsAfterFirst = webCalls.get()
        val second = client.searchByPlot("  a CHEMISTRY teacher becomes a drug dealer  ")

        assertEquals(listOf("Breaking Bad"), first.map(Media::title))
        assertEquals(first, second)
        assertEquals(callsAfterFirst, webCalls.get())
    }

    @Test
    fun braveAnswerTextExtractsMultipleNamedMoviesAndKnowledgePanelShow() {
        val client = CatalogClient(
            pageLoader = { error("Parser test must stay offline") },
        )
        val html = """
            <div class="inline-qa-answer">
              <span>Inception? Paprika and Nightmare on Elm Street are the other two.</span>
            </div>
            <div class="entity-infobox-header-title">Breaking Bad</div>
        """.trimIndent()

        val titles = client.parseBravePlotCandidates(html).map(PlotCandidate::title)

        assertTrue("Inception" in titles)
        assertTrue("Paprika" in titles)
        assertTrue("Nightmare on Elm Street" in titles)
        assertTrue("Breaking Bad" in titles)
    }

    @Test
    fun plotSearchNeverFallsBackToLocalTitlesWhenEveryWebSourceFails() {
        val client = CatalogClient(
            pageLoader = { throw IOException("blocked") },
        )

        val error = assertThrows(IOException::class.java) {
            runTest {
                client.searchByPlot("a family moves into a house with a secret")
            }
        }

        assertEquals("Web lookup unavailable—try again.", error.message)
    }

    private fun cachedHome(): HomeContent {
        val rails = GenreCatalog.homeSpecs.mapIndexed { railIndex, spec ->
            ContentRail(
                title = spec.title,
                items = (0 until 20).map { itemIndex ->
                    Media(
                        id = 70_000_000 + railIndex * 100 + itemIndex,
                        type = spec.type,
                        title = "${spec.title} $itemIndex",
                    )
                },
            )
        }
        return HomeContent(rails.first().items.first(), rails)
    }

    private fun braveHtml(items: List<Triple<String, String, MediaType>>): String =
        buildString {
            append("<main>")
            items.forEach { (title, year, type) ->
                val qualifier = if (type == MediaType.TV) "TV series" else "film"
                append(
                    """<div class="search-snippet-title" title="$title ($year $qualifier)">""" +
                        "$title ($year $qualifier)</div>",
                )
            }
            append("</main>")
        }

    private fun emptyWikipedia(): String = """{"query":{"search":[]}}"""

    private fun tmdbResolvedTitleHtml(
        url: String,
        external: List<Triple<String, String, MediaType>>,
    ): String {
        val title = queryParameter(url, "query")
        val match = external.firstOrNull { it.first.equals(title, ignoreCase = true) }
            ?: return "<main></main>"
        val requestedType = if ("/search/tv" in url) MediaType.TV else MediaType.MOVIE
        if (requestedType != match.third) return "<main></main>"
        return searchHtml(
            type = match.third,
            items = listOf(
                FixtureItem(
                    id = external.indexOf(match) + 50_000,
                    title = match.first,
                    year = match.second,
                    overview = plotFor(match.first),
                ),
            ),
        )
    }

    private fun collidingCatalogueHtml(url: String): String {
        val uri = URI(url)
        val type = if (
            uri.path.endsWith("/tv") ||
            uri.path == "/tv" ||
            uri.path.startsWith("/tv/")
        ) {
            MediaType.TV
        } else {
            MediaType.MOVIE
        }
        val page = pageNumber(url) ?: 1
        val items = (0 until 25).map { index ->
            FixtureItem(
                id = 620_000_000 + type.ordinal * 10_000_000 + page * 100 + index,
                title = "${type.routeName} colliding page $page item $index",
            )
        }
        return searchHtml(
            type = type,
            items = items,
            canonical = uri.path
                .takeIf { path -> path.startsWith("/genre/") }
                ?.let { path -> "https://www.themoviedb.org$path" },
        )
    }

    private fun actionOverlapCatalogueHtml(url: String): String {
        val uri = URI(url)
        val page = pageNumber(url) ?: 1
        val shared = (0 until 20).map { index ->
            FixtureItem(
                id = 660_000_000 + index,
                title = "Action shared item $index",
            )
        }
        val unique = (0 until 20).map { index ->
            FixtureItem(
                id = 670_000_000 + page * 100 + index,
                title = "Action page $page item $index",
            )
        }
        return searchHtml(
            type = MediaType.MOVIE,
            items = shared + unique,
            canonical = "https://www.themoviedb.org${uri.path}",
        )
    }

    private fun catalogueHtml(
        url: String,
        countOverride: Int? = null,
        canonicalOverride: String? = null,
    ): String {
        val uri = URI(url)
        val type = if (
            uri.path.endsWith("/tv") ||
            uri.path == "/tv" ||
            uri.path.startsWith("/tv/")
        ) {
            MediaType.TV
        } else {
            MediaType.MOVIE
        }
        val page = pageNumber(url) ?: 1
        val genreId = Regex("/genre/(\\d+)-")
            .find(uri.path)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        val items = if (genreId == null) {
            val seed = (
                uri.path.fold(0) { total, character ->
                    (total * 31 + character.code) and 0xffff
                } + page * 100
                )
            (0 until (countOverride ?: 25)).map { index ->
                FixtureItem(
                    id = 90_000_000 + type.ordinal * 10_000_000 + seed * 100 + index,
                    title = "${type.routeName} base $seed $index",
                )
            }
        } else {
            genreFixtureItems(type, genreId, page).let { generated ->
                countOverride?.let(generated::take) ?: generated
            }
        }
        return searchHtml(
            type = type,
            items = items,
            canonical = canonicalOverride ?: if (genreId == null) {
                null
            } else {
                "https://www.themoviedb.org${uri.path}"
            },
        )
    }

    private fun genreFixtureItems(
        type: MediaType,
        genreId: Int,
        page: Int,
    ): List<FixtureItem> {
        val typeOffset = type.ordinal * 200_000_000
        val groups = compoundGroups[type].orEmpty()
            .filter { (_, ids) -> genreId in ids }
        val own = (0 until 10).map { index ->
            val id = 1_000_000 + typeOffset + genreId * 1_000 + page * 20 + index
            FixtureItem(id, "${type.routeName} genre $genreId unique p$page-$index")
        }
        val shared = (0 until 15).map { index ->
            if (groups.isEmpty()) {
                val id = 10_000_000 + typeOffset + genreId * 1_000 + page * 30 + index
                FixtureItem(id, "${type.routeName} genre $genreId extra p$page-$index")
            } else {
                val (groupId, _) = groups[index % groups.size]
                val groupIndex = index / groups.size
                val id = 50_000_000 + typeOffset + groupId * 10_000 + page * 100 + groupIndex
                FixtureItem(id, "${type.routeName} compound $groupId p$page-$groupIndex")
            }
        }
        return own + shared
    }

    private fun searchHtml(
        type: MediaType,
        items: List<FixtureItem>,
        canonical: String? = null,
    ): String = buildString {
        append("<html><head>")
        canonical?.let { append("""<link rel="canonical" href="$it" />""") }
        append("</head><body><main>")
        items.forEach { item ->
            append(
                """
                <div data-object-id="${type.routeName}-${item.id}">
                  <a data-media-type="${type.routeName}"
                     href="/${type.routeName}/${item.id}-test">
                    <img class="poster" alt="${item.title}"
                      src="https://media.themoviedb.org/t/p/w94_and_h141_face/p${item.id}.jpg" />
                    <h2>${item.title}</h2>
                  </a>
                  <span class="release_date">January 1, ${item.year}</span>
                  <p>${item.overview}</p>
                </div>
                """.trimIndent(),
            )
        }
        append("</main></body></html>")
    }

    private fun pageNumber(url: String): Int? =
        Regex("[?&]page=(\\d+)")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun queryParameter(url: String, name: String): String {
        val raw = url.substringAfter("$name=").substringBefore('&')
        return URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
    }

    private fun plotFor(title: String): String = when (title) {
        "Inception" -> "A thief enters other people's dreams to steal secrets."
        "Paprika" -> "A therapist uses a device to enter dreams."
        "Dreamscape" -> "A man enters and manipulates people's dreams."
        "Groundhog Day" -> "A man repeatedly relives the same day."
        "Breaking Bad" -> "A chemistry teacher becomes a drug dealer."
        else -> "A science fiction mystery."
    }

    private data class FixtureItem(
        val id: Int,
        val title: String,
        val year: String = "2024",
        val overview: String = "A distinctive story with memorable characters.",
    )

    private class MemoryCatalogCache(
        private var home: HomeContent? = null,
    ) : CatalogCacheStore {
        private val plots = mutableMapOf<String, List<Media>>()

        override suspend fun loadHome(): HomeContent? = home

        override suspend fun saveHome(content: HomeContent) {
            home = content
        }

        override suspend fun loadPlot(
            queryKey: String,
            maxAgeMs: Long,
        ): List<Media>? = plots[queryKey]

        override suspend fun savePlot(queryKey: String, items: List<Media>) {
            plots[queryKey] = items
        }
    }

    private companion object {
        val compoundGroups = mapOf(
            MediaType.MOVIE to listOf(
                1 to setOf(28, 53),
                2 to setOf(35, 10749),
                3 to setOf(80, 53),
                4 to setOf(18, 36),
                5 to setOf(16, 10751, 12),
            ),
            MediaType.TV to listOf(
                101 to setOf(18, 80),
                102 to setOf(18, 9648),
                103 to setOf(18, 35),
            ),
        )
    }
}
