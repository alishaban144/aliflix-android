package com.aliflix.app.data

import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationPageCursor
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationStructuredCatalogueContractTest {
    @Test
    fun imdbPopularityAndRatingStreamsAreInterleavedWithoutDuplicates() {
        val client = CatalogClient { error("Pure merge test cannot use network") }
        val popularity = listOf(
            imdbTitle("tt-pop-1", "Popular One"),
            imdbTitle("tt-shared", "Shared"),
            imdbTitle("tt-pop-3", "Popular Three"),
        )
        val rating = listOf(
            imdbTitle("tt-rate-1", "Rated One"),
            imdbTitle("tt-shared", "Shared"),
            imdbTitle("tt-rate-3", "Rated Three"),
        )

        val merged = client.interleaveImdbTitles(
            popularity = popularity,
            rating = rating,
            limit = 36,
        )

        assertEquals(
            listOf(
                "tt-pop-1",
                "tt-rate-1",
                "tt-shared",
                "tt-pop-3",
                "tt-rate-3",
            ),
            merged.map { it.imdbId },
        )
        assertEquals(merged.size, merged.map { it.imdbId }.distinct().size)
    }

    @Test
    fun tmdbDiscoverRequestCarriesEverySupportedHardConstraint() = runTest {
        val urls = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            pageLoader = { url ->
                urls += url
                "<main></main>"
            },
        )

        client.recommendationPage(
            spec = CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.MOVIE,
                includedGenres = listOf("Thriller"),
                excludedGenres = listOf("Horror"),
                runtimeMinimumMinutes = 80,
                runtimeMaximumMinutes = 140,
                yearMinimum = 2016,
                yearMaximum = 2024,
                minimumTmdb = 7.0,
                originalLanguage = "French",
            ),
            cursor = RecommendationPageCursor(page = 3),
        )

        val discoverUrls = urls.filter { URI(it).path == "/discover/movie" }
        assertEquals(2, discoverUrls.size)
        val queries = discoverUrls.map(::decodedQuery)
        assertTrue(queries.all { it["include_adult"] == "false" })
        assertTrue(queries.all { it["page"] == "3" })
        assertTrue(queries.all { it["with_genres"] == "53" })
        assertTrue(queries.all { it["without_genres"] == "27" })
        assertTrue(queries.all { it["primary_release_date.gte"] == "2016-01-01" })
        assertTrue(queries.all { it["primary_release_date.lte"] == "2024-12-31" })
        assertTrue(queries.all { it["with_runtime.gte"] == "80" })
        assertTrue(queries.all { it["with_runtime.lte"] == "140" })
        assertTrue(queries.all { it["vote_average.gte"] == "7.0" })
        assertTrue(queries.all { it["with_original_language"] == "fr" })
        assertEquals(
            setOf("popularity.desc", "vote_average.desc"),
            queries.mapNotNull { it["sort_by"] }.toSet(),
        )
    }

    @Test
    fun imdbGraphqlUsesTwoStreamsAndFailsAdultContentClosed() = runTest {
        val queries = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            jsonPoster = { url, body ->
                assertEquals("https://api.graphql.imdb.com/", url)
                queries += JSONObject(body).getString("query")
                """
                    {
                      "data": {
                        "advancedTitleSearch": {
                          "edges": [],
                          "pageInfo": {
                            "hasNextPage": false,
                            "endCursor": null
                          }
                        }
                      }
                    }
                """.trimIndent()
            },
            pageLoader = { error("An empty IMDb result must not need TMDB resolution") },
        )

        client.recommendationPage(
            CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.MOVIE,
                includedGenres = listOf("Thriller"),
                excludedGenres = listOf("Horror"),
                runtimeMinimumMinutes = 80,
                runtimeMaximumMinutes = 120,
                yearMinimum = 2016,
                yearMaximum = 2025,
                minimumImdb = 7.0,
                originalLanguage = "English",
            ),
        )

        assertEquals(2, queries.size)
        assertTrue(queries.all { "\"movie\"" in it })
        assertTrue(queries.all { "\"Thriller\"" in it })
        assertTrue(queries.all { "\"Horror\"" in it })
        assertTrue(queries.all { "start:\"2016-01-01\"" in it })
        assertTrue(queries.all { "end:\"2025-12-31\"" in it })
        assertTrue(queries.all { "aggregateRatingRange:{min:7.0}" in it })
        assertTrue(queries.all { "runtimeRangeMinutes:{min:80,max:120}" in it })
        assertTrue(queries.all { "explicitContentFilter:EXCLUDE_ADULT" in it })
        assertTrue(queries.all { "anyPrimaryLanguages:[\"en\"]" in it })
        assertTrue(queries.any { "sortBy:POPULARITY,sortOrder:ASC" in it })
        assertTrue(queries.any { "sortBy:USER_RATING,sortOrder:DESC" in it })
        assertTrue(queries.any { "ratingsCountRange:{min:0}" in it })
        assertTrue(queries.any { "ratingsCountRange:{min:250}" in it })
    }

    @Test
    fun exhaustedImdbStreamIsNotRestartedWhenTheOtherStreamPages() = runTest {
        val queries = CopyOnWriteArrayList<String>()
        var ratingRequest = 0
        val client = CatalogClient(
            jsonPoster = { _, body ->
                val query = JSONObject(body).getString("query")
                queries += query
                when {
                    "sortBy:POPULARITY" in query -> imdbEmptyPage(
                        hasNextPage = false,
                        cursor = null,
                    )
                    "sortBy:USER_RATING" in query -> {
                        ratingRequest += 1
                        imdbEmptyPage(
                            hasNextPage = ratingRequest == 1,
                            cursor = if (ratingRequest == 1) "rating-1" else null,
                        )
                    }
                    else -> error("Unexpected IMDb query")
                }
            },
            pageLoader = { "<html></html>" },
        )
        val spec = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.MOVIE,
            minimumImdb = 7.0,
        )

        val first = client.recommendationPage(spec)
        assertTrue(first.hasMore)
        assertEquals(setOf("imdb_popularity"), first.nextCursor?.exhaustedSources)
        assertEquals("rating-1", first.nextCursor?.imdbRatingCursor)

        val second = client.recommendationPage(spec, first.nextCursor!!)

        assertFalse(second.hasMore)
        assertEquals(1, queries.count { "sortBy:POPULARITY" in it })
        assertEquals(2, queries.count { "sortBy:USER_RATING" in it })
        assertTrue(
            queries.last { "sortBy:USER_RATING" in it }
                .contains("after:\"rating-1\""),
        )
    }

    @Test
    fun catalogueRejectsAdultWrongTypeAndDuplicateResultsBeforeEmission() = runTest {
        val client = CatalogClient(
            pageLoader = { filteredFixtureHtml() },
        )

        val page = client.recommendationPage(
            CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
        )

        assertEquals(listOf(101, 104), page.items.map { it.media.id })
        assertTrue(page.items.all { it.media.type == MediaType.MOVIE })
        assertTrue(page.items.all { isSafeTrendingItem(it.media) })
        assertEquals(
            page.items.size,
            page.items.map { it.media.key }.distinct().size,
        )
    }

    @Test
    fun cursorExcludesSeenTitlesAndCarriesVerifiedMetadataAcrossPages() = runTest {
        val requestedPages = CopyOnWriteArrayList<Int>()
        val client = CatalogClient(
            pageLoader = loader@{ url ->
                if (URI(url).path != "/discover/movie") {
                    return@loader "<main></main>"
                }
                val page = decodedQuery(url)["page"]?.toIntOrNull() ?: 1
                requestedPages += page
                val ids: Iterable<Int> = if (page == 1) {
                    1..20
                } else {
                    listOf(1) + (21..39)
                }
                movieFixtureHtml(ids)
            },
        )
        val spec = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.MOVIE,
            includedGenres = listOf("Thriller"),
        )

        val first = client.recommendationPage(spec)
        val firstCursor = first.nextCursor
        assertNotNull(firstCursor)
        assertEquals(20, first.items.size)
        assertEquals(2, firstCursor?.page)
        assertTrue(first.items.all { it.metadata.genresVerified })
        assertTrue(first.items.all { "Thriller" in it.media.genres })

        val second = client.recommendationPage(spec, firstCursor!!)
        assertEquals((21..39).toList(), second.items.map { it.media.id })
        assertFalse(second.items.any { it.media.id == 1 })
        assertEquals(39, second.nextCursor?.seenKeys?.size)
        assertEquals(3, second.nextCursor?.page)
        assertTrue(second.items.all { it.metadata.genresVerified })
        assertEquals(listOf(1, 1, 2, 2), requestedPages.sorted())
    }

    private fun filteredFixtureHtml(): String = """
        <main>
          ${movieCard(101, "Clean Thriller")}
          ${movieCard(101, "Clean Thriller")}
          ${movieCard(102, "XXX Adult Porn Collection", "Explicit content for adults only.")}
          ${tvCard(103, "Wrong Type Series")}
          ${movieCard(104, "Another Clean Film")}
        </main>
    """.trimIndent()

    private fun movieFixtureHtml(ids: Iterable<Int>): String = buildString {
        append("<main>")
        ids.forEach { id -> append(movieCard(id, "Fixture Movie $id")) }
        append("</main>")
    }

    private fun movieCard(
        id: Int,
        title: String,
        overview: String = "A safe suspense story.",
    ): String = """
        <div data-object-id="movie-$id">
          <a data-media-type="movie" href="/movie/$id-fixture">
            <img class="poster" alt="$title" src="/poster-$id.jpg" />
          </a>
          <a data-media-type="movie" href="/movie/$id-fixture">
            <h2>$title</h2>
          </a>
          <span class="release_date">May 4, 2024</span>
          <p>$overview</p>
        </div>
    """.trimIndent()

    private fun tvCard(
        id: Int,
        title: String,
    ): String = """
        <div data-object-id="tv-$id">
          <a data-media-type="tv" href="/tv/$id-fixture">
            <img class="poster" alt="$title" src="/poster-$id.jpg" />
          </a>
          <a data-media-type="tv" href="/tv/$id-fixture">
            <h2>$title</h2>
          </a>
          <span class="release_date">May 4, 2024</span>
          <p>A safe television story.</p>
        </div>
    """.trimIndent()

    private fun decodedQuery(url: String): Map<String, String> = URI(url)
        .rawQuery
        .orEmpty()
        .split('&')
        .mapNotNull { parameter ->
            val parts = parameter.split('=', limit = 2)
            val key = parts.firstOrNull()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            key to URLDecoder.decode(
                parts.getOrElse(1) { "" },
                StandardCharsets.UTF_8,
            )
        }
        .toMap()

    private fun imdbEmptyPage(
        hasNextPage: Boolean,
        cursor: String?,
    ): String = JSONObject()
        .put(
            "data",
            JSONObject().put(
                "advancedTitleSearch",
                JSONObject()
                    .put("edges", org.json.JSONArray())
                    .put(
                        "pageInfo",
                        JSONObject()
                            .put("hasNextPage", hasNextPage)
                            .put("endCursor", cursor),
                    ),
            ),
        )
        .toString()

    private fun imdbTitle(
        id: String,
        title: String,
    ) = ImdbAdvancedTitle(
        imdbId = id,
        title = title,
        year = 2024,
        rating = 8.0,
        voteCount = 1_000,
        runtimeMinutes = 105,
        genres = listOf("Thriller"),
        overview = "Fixture.",
        position = 0,
    )
}
