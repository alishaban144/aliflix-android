package com.aliflix.app.data

import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationPageCursor
import com.aliflix.app.recommendation.RecommendationSourceStatus
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
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
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val client = CatalogClient(
            formTransport = CatalogFormTransport { url, fields, _ ->
                requests += url to fields
                "No items were found that match your query."
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

        assertEquals(2, requests.size)
        assertTrue(requests.all { it.first.endsWith("/discover/movie/items") })
        val fields = requests.map(Pair<String, Map<String, String>>::second)
        assertTrue(fields.all { it["include_adult"] == "false" })
        assertTrue(fields.all { it["page"] == "3" })
        assertTrue(fields.all { it["with_genres"] == "53" })
        assertTrue(fields.all { "without_genres" !in it })
        assertTrue(fields.all { it["primary_release_date.gte"] == "2016-01-01" })
        assertTrue(fields.all { it["primary_release_date.lte"] == "2024-12-31" })
        assertTrue(fields.all { it["with_runtime.gte"] == "80" })
        assertTrue(fields.all { it["with_runtime.lte"] == "140" })
        assertTrue(fields.all { it["vote_average.gte"] == "7.0" })
        assertTrue(fields.all { it["with_original_language"] == "fr" })
        assertEquals(
            setOf("popularity.desc", "vote_average.desc"),
            fields.mapNotNull { it["sort_by"] }.toSet(),
        )
        assertEquals("50", fields.single { it["sort_by"] == "vote_average.desc" }["vote_count.gte"])
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
            formTransport = CatalogFormTransport { _, _, _ -> filteredFixtureHtml() },
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
        val requestedUrls = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            formTransport = CatalogFormTransport { url, fields, _ ->
                val page = fields["page"]?.toIntOrNull() ?: 1
                requestedUrls += url
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
        assertTrue(requestedUrls.take(2).all { it.endsWith("/discover/movie") })
        assertTrue(requestedUrls.drop(2).all { it.endsWith("/discover/movie/items") })
    }

    @Test
    fun seriesFieldsUseFirstAirDateContract() {
        val client = CatalogClient { error("Pure field test cannot use network") }

        val fields = client.buildTmdbRecommendationFields(
            spec = CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.SERIES,
                yearMinimum = 2018,
                yearMaximum = 2022,
                runtimeMaximumMinutes = 55,
            ),
            page = 2,
            sort = "popularity.desc",
        )

        assertEquals("2018-01-01", fields["first_air_date.gte"])
        assertEquals("2022-12-31", fields["first_air_date.lte"])
        assertEquals("55", fields["with_runtime.lte"])
        assertFalse(fields.keys.any { it.startsWith("primary_release_date") })
    }

    @Test
    fun firstPageUsesBasePostAndParsesFragmentWithoutMain() = runTest {
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val headerSnapshots = CopyOnWriteArrayList<Map<String, String>>()
        val client = CatalogClient(
            formTransport = CatalogFormTransport { url, fields, headers ->
                requests += url to fields
                headerSnapshots += headers
                movieCard(501, "Fragment Movie")
            },
        )

        val page = client.recommendationPage(
            CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
        )

        assertEquals(listOf(501), page.items.map { it.media.id })
        assertTrue(requests.all { it.first.endsWith("/discover/movie") })
        assertTrue(requests.none { it.first.endsWith("/items") })
        assertTrue(headerSnapshots.all { it["X-Requested-With"] == "XMLHttpRequest" })
        assertTrue(headerSnapshots.all { "Android" in it.getValue("User-Agent") })
    }

    @Test
    fun explicitEmptyResponseIsNotReportedAsProviderFailure() = runTest {
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, _, _ ->
                "<div>No items were found that match your query.</div>"
            },
        )

        val outcome = client.recommendationPageOutcome(
            CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.SERIES),
        )

        assertTrue(outcome is CatalogPageOutcome.Empty)
        assertFalse((outcome as CatalogPageOutcome.Empty).page.hasMore)
        assertEquals(
            RecommendationSourceStatus.AVAILABLE,
            outcome.page.sourceHealth.catalogue,
        )
    }

    @Test
    fun malformedAndWafResponsesRemainUnavailableInsteadOfTerminalEmpty() = runTest {
        val attempts = CopyOnWriteArrayList<String>()
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, fields, _ ->
                attempts += fields.getValue("sort_by")
                if (fields["sort_by"] == "popularity.desc") {
                    "<html><body>generic redirected page</body></html>"
                } else {
                    "<html><body>x-amzn-waf challenge</body></html>"
                }
            },
            jsonPoster = { _, _ -> throw IOException("IMDb unavailable") },
        )

        val outcome = client.recommendationPageOutcome(
            CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
        )

        assertTrue(outcome is CatalogPageOutcome.Unavailable)
        assertEquals(
            CatalogSource.TMDB,
            (outcome as CatalogPageOutcome.Unavailable).source,
        )
        assertTrue(attempts.count { it == "popularity.desc" } >= 3)
        assertTrue(attempts.count { it == "vote_average.desc" } >= 3)
    }

    @Test
    fun oneSuccessfulSortSurvivesOtherSortFailureAsDegradedResults() = runTest {
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, fields, _ ->
                if (fields["sort_by"] == "popularity.desc") {
                    movieCard(601, "Available Result")
                } else {
                    "<html><body>cloudflare challenge-platform</body></html>"
                }
            },
        )

        val page = client.recommendationPage(
            CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
        )

        assertEquals(listOf(601), page.items.map { it.media.id })
        assertEquals(
            RecommendationSourceStatus.DEGRADED,
            page.sourceHealth.catalogue,
        )
    }

    @Test
    fun explicitEmptyPlusFailedSortIsEmptyAndDegraded() = runTest {
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, fields, _ ->
                if (fields["sort_by"] == "popularity.desc") {
                    "No items were found that match your query."
                } else {
                    "<html><body>x-amzn-waf challenge</body></html>"
                }
            },
        )

        val outcome = client.recommendationPageOutcome(
            CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
        )

        assertTrue(outcome is CatalogPageOutcome.Empty)
        assertEquals(
            RecommendationSourceStatus.DEGRADED,
            (outcome as CatalogPageOutcome.Empty).page.sourceHealth.catalogue,
        )
    }

    @Test
    fun unexpectedTransportFailurePropagatesInsteadOfUsingProviderFallback() = runTest {
        val expected = IllegalStateException("Programming failure")
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, _, _ -> throw expected },
        )
        var actual: Throwable? = null

        try {
            client.recommendationPageOutcome(
                CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
            )
        } catch (error: Throwable) {
            actual = error
        }

        assertTrue(actual is IllegalStateException)
        assertTrue(actual === expected || actual?.cause === expected)
    }

    @Test
    fun imdbCandidatesWithMostlyFailedTmdbResolutionReportTmdbUnavailable() = runTest {
        val client = CatalogClient(
            jsonPoster = { _, _ ->
                imdbGraphPage(
                    "tt8101" to "Resolver Failure One",
                    "tt8102" to "Resolver Failure Two",
                    "tt8103" to "Successful No Match",
                )
            },
            pageLoader = { url ->
                if ("Successful+No+Match" in url) {
                    "<main><h2>There were no results that matched your query.</h2></main>"
                } else {
                    throw IOException("TMDB title search unavailable")
                }
            },
        )

        val outcome = client.recommendationPageOutcome(
            CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.MOVIE,
                minimumImdb = 7.0,
            ),
        )

        assertTrue(outcome is CatalogPageOutcome.Unavailable)
        assertEquals(
            CatalogSource.TMDB,
            (outcome as CatalogPageOutcome.Unavailable).source,
        )
    }

    @Test
    fun successfulTmdbNoMatchesRemainARealEmptyResult() = runTest {
        val client = CatalogClient(
            jsonPoster = { _, _ ->
                imdbGraphPage(
                    "tt8201" to "Absent Fixture One",
                    "tt8202" to "Absent Fixture Two",
                )
            },
            pageLoader = {
                "<main><h2>There were no results that matched your query.</h2></main>"
            },
        )

        val outcome = client.recommendationPageOutcome(
            CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.MOVIE,
                minimumImdb = 7.0,
            ),
        )

        assertTrue(outcome is CatalogPageOutcome.Empty)
    }

    @Test
    fun paginationMarkersOverrideRawCardCountAndRawCountIsFallback() = runTest {
        val activeClient = CatalogClient(
            formTransport = CatalogFormTransport { _, _, _ ->
                movieCard(901, "Small Page") +
                    """<div class="load_more"><a href="?page=2">Load More</a></div>"""
            },
        )
        val disabledClient = CatalogClient(
            formTransport = CatalogFormTransport { _, _, _ ->
                movieFixtureHtml(1..20) +
                    """
                        <div class="pagination">
                          <a class="next_page disabled" aria-disabled="true">Next</a>
                        </div>
                    """.trimIndent()
            },
        )
        val rawCountClient = CatalogClient(
            formTransport = CatalogFormTransport { _, _, _ ->
                buildString {
                    append(movieCard(950, "Only Matching Type"))
                    (951..964).forEach { id ->
                        append(tvCard(id, "Filtered Series $id"))
                    }
                }
            },
        )
        val spec = CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE)

        assertTrue(activeClient.recommendationPage(spec).hasMore)
        assertFalse(disabledClient.recommendationPage(spec).hasMore)
        val rawFallbackPage = rawCountClient.recommendationPage(spec)
        assertEquals(listOf(950), rawFallbackPage.items.map { it.media.id })
        assertTrue(rawFallbackPage.hasMore)
    }

    @Test
    fun excludedGenresAreNotSentOrMarkedVerifiedByTmdb() = runTest {
        val fieldsSeen = CopyOnWriteArrayList<Map<String, String>>()
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, fields, _ ->
                fieldsSeen += fields
                movieCard(701, "Needs Genre Verification")
            },
        )

        val page = client.recommendationPage(
            CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.MOVIE,
                includedGenres = listOf("Thriller"),
                excludedGenres = listOf("Horror"),
            ),
        )

        assertTrue(fieldsSeen.all { "without_genres" !in it })
        assertFalse(page.items.single().metadata.genresVerified)
    }

    @Test
    fun cancellingCatalogueRequestCancelsInFlightFormPosts() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicInteger()
        val client = CatalogClient(
            formTransport = CatalogFormTransport { _, _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            },
        )

        val request = launch {
            client.recommendationPage(
                CatalogDiscoverySpec(mediaKind = RecommendationMediaKind.MOVIE),
            )
        }
        started.await()
        request.cancelAndJoin()

        assertTrue(request.isCancelled)
        assertTrue(cancelled.get() >= 1)
    }

    private fun filteredFixtureHtml(): String = """
        <main>
          ${movieCard(101, "Clean Thriller")}
          ${movieCard(101, "Clean Thriller")}
          ${
              movieCard(102, "Hidden Explicit Card")
                  .replace(
                      "data-object-id=\"movie-102\"",
                      "data-object-id=\"movie-102\" data-media-adult=\"true\"",
                  )
          }
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

    private fun imdbGraphPage(
        vararg titles: Pair<String, String>,
    ): String {
        val edges = JSONArray()
        titles.forEach { (id, title) ->
            edges.put(
                JSONObject().put(
                    "node",
                    JSONObject().put(
                        "title",
                        JSONObject()
                            .put("id", id)
                            .put(
                                "titleText",
                                JSONObject().put("text", title),
                            )
                            .put(
                                "releaseYear",
                                JSONObject().put("year", 2022),
                            )
                            .put(
                                "runtime",
                                JSONObject().put("seconds", 6_300),
                            )
                            .put(
                                "ratingsSummary",
                                JSONObject()
                                    .put("aggregateRating", 8.0)
                                    .put("voteCount", 5_000),
                            )
                            .put(
                                "titleGenres",
                                JSONObject().put(
                                    "genres",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "genre",
                                            JSONObject().put("text", "Thriller"),
                                        ),
                                    ),
                                ),
                            ),
                    ),
                ),
            )
        }
        return JSONObject().put(
            "data",
            JSONObject().put(
                "advancedTitleSearch",
                JSONObject()
                    .put("edges", edges)
                    .put(
                        "pageInfo",
                        JSONObject()
                            .put("hasNextPage", false)
                            .put("endCursor", JSONObject.NULL),
                    ),
            ),
        ).toString()
    }

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
