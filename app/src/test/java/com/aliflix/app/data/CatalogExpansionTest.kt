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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CatalogExpansionTest {
    @Test
    fun homeLoadsAll28GenreRowsWith20ItemsAndAtMostFourRequestsAtOnce() = runTest {
        val nextBase = AtomicInteger(10_000)
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val bases = ConcurrentHashMap<String, Int>()
        val client = CatalogClient(
            pageLoader = { url ->
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                try {
                    delay(5)
                    val type = if ("/tv" in url) MediaType.TV else MediaType.MOVIE
                    searchHtml(
                        type = type,
                        baseId = bases.computeIfAbsent(url) {
                            nextBase.getAndAdd(100)
                        },
                        count = 20,
                    )
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val home = client.home()
        val genreRails = home.rails.filter { rail ->
            GenreCatalog.homeSpecs.any { it.title == rail.title }
        }

        assertEquals(28, GenreCatalog.homeSpecs.size)
        assertEquals(28, genreRails.size)
        assertTrue(genreRails.all { it.items.size == 20 })
        assertTrue(maximumActive.get() <= 4)
    }

    @Test
    fun shortGenreResponseIsRefilledByAlternateSortAndFailedRequestRetries() = runTest {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val bases = ConcurrentHashMap<String, Int>()
        val nextBase = AtomicInteger(20_000)
        val client = CatalogClient(
            pageLoader = { url ->
                val call = calls.computeIfAbsent(url) { AtomicInteger(0) }.incrementAndGet()
                val type = if ("/tv" in url) MediaType.TV else MediaType.MOVIE
                val isActionPrimary =
                    "with_genres=28" in url && "sort_by=popularity.desc" in url
                if (isActionPrimary && call == 1) throw IOException("temporary")
                val count = if (isActionPrimary) 10 else 20
                searchHtml(
                    type = type,
                    baseId = bases.computeIfAbsent(url) {
                        nextBase.getAndAdd(100)
                    },
                    count = count,
                )
            },
        )

        val action = client.home().rails.first { it.title == "Action" }
        val primary = calls.entries.first { (url, _) ->
            "with_genres=28" in url && "sort_by=popularity.desc" in url
        }

        assertEquals(20, action.items.size)
        assertEquals(2, primary.value.get())
    }

    @Test
    fun genrePageFetches40ItemsAndKeepsTheOriginatingMediaType() = runTest {
        val nextBase = AtomicInteger(30_000)
        val bases = ConcurrentHashMap<String, Int>()
        val client = CatalogClient(
            pageLoader = { url ->
                searchHtml(
                    type = if ("/discover/tv" in url) MediaType.TV else MediaType.MOVIE,
                    baseId = bases.computeIfAbsent(url) {
                        nextBase.getAndAdd(100)
                    },
                    count = 20,
                )
            },
        )

        val results = client.browseGenre("Action", MediaType.MOVIE)

        assertEquals(40, results.size)
        assertTrue(results.all { it.type == MediaType.MOVIE })
    }

    @Test
    fun cachedGenreRowsRemainAvailableWhenRefreshRequestsFail() = runTest {
        val cache = MemoryCatalogCache(
            home = cachedHome(),
        )
        val progress = mutableListOf<HomeContent>()
        val client = CatalogClient(
            cacheStore = cache,
            pageLoader = { throw IOException("offline") },
        )

        val result = client.home { progress += it }

        assertTrue(progress.isNotEmpty())
        assertEquals(28, result.rails.size)
        assertTrue(result.rails.all { it.items.size == 20 })
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
                    "/search/" in url -> {
                        val title = queryParameter(url, "query")
                        val match = external.firstOrNull {
                            it.first.equals(title, ignoreCase = true)
                        }
                        if (match == null) {
                            "<main></main>"
                        } else {
                            val requestedType = if ("/search/tv" in url) {
                                MediaType.TV
                            } else {
                                MediaType.MOVIE
                            }
                            if (requestedType != match.third) {
                                "<main></main>"
                            } else {
                                searchHtml(
                                    type = match.third,
                                    baseId = external.indexOf(match) + 50_000,
                                    count = 1,
                                    title = match.first,
                                    year = match.second,
                                    overview = plotFor(match.first),
                                )
                            }
                        }
                    }
                    else -> error("Unexpected request: $url")
                }
            },
        )
        assertEquals(
            external.map { it.first },
            client.parseBravePlotCandidates(braveHtml(external)).map { it.title },
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
    fun plotSearchNeverFallsBackToLocalTitlesWhenWebLookupFails() {
        val client = CatalogClient(
            pageLoader = { throw IOException("blocked") },
        )

        val error = assertThrows(IOException::class.java) {
            runTest {
                client.searchByPlot("a family moves into a house with a secret")
            }
        }

        assertTrue(error.message.orEmpty().contains("Web lookup unavailable"))
    }

    private fun cachedHome(): HomeContent {
        val rails = GenreCatalog.homeSpecs.mapIndexed { railIndex, spec ->
            ContentRail(
                title = spec.title,
                items = (0 until 20).map { itemIndex ->
                    Media(
                        id = 70_000 + railIndex * 100 + itemIndex,
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

    private fun searchHtml(
        type: MediaType,
        baseId: Int,
        count: Int,
        title: String? = null,
        year: String = "2024",
        overview: String = "A distinctive story with memorable characters.",
    ): String = buildString {
        append("<main>")
        repeat(count) { index ->
            val itemTitle = title ?: "${type.routeName} title ${baseId + index}"
            append(
                """
                <div data-object-id="${type.routeName}-${baseId + index}">
                  <a data-media-type="${type.routeName}"
                     href="/${type.routeName}/${baseId + index}-test">
                    <img class="poster" alt="$itemTitle"
                      src="https://media.themoviedb.org/t/p/w94_and_h141_face/p$index.jpg" />
                    <h2>$itemTitle</h2>
                  </a>
                  <span class="release_date">January 1, $year</span>
                  <p>$overview</p>
                </div>
                """.trimIndent(),
            )
        }
        append("</main>")
    }

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
}
