package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImdbRatingRepositoryTest {
    @Test
    fun primaryFailureContinuesToCachingHostWithRequiredHeaders() = runTest {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            pageLoader = { error("HTML fallback must not run") },
            graphQlTransport = ImdbGraphQlTransport { url, _, headers ->
                requests += url to headers
                if (requests.size == 1) throw IOException("403")
                ratedPayload("tt1375666", "Inception", 2010, "movie", 8.8, 2_800_000)
            },
        )

        val result = repository.ratingFor(
            media(
                imdbId = "tt1375666",
                title = "Inception",
                year = "2010",
            ),
        )

        assertEquals(RatingSourceState.VERIFIED, result.state)
        assertEquals(8.8, result.rating ?: 0.0, 0.001)
        assertEquals(2_800_000, result.voteCount)
        assertEquals(DefaultImdbRatingRepository.GRAPHQL_ENDPOINTS, requests.map { it.first })
        val headers = requests.last().second
        assertEquals("https://www.imdb.com", headers["Origin"])
        assertEquals("https://www.imdb.com/", headers["Referer"])
        assertEquals("imdb-web-next-localized", headers["x-imdb-client-name"])
        assertTrue(headers["Accept"].orEmpty().contains("graphql"))
        assertTrue(headers["User-Agent"].orEmpty().contains("Mozilla"))
    }

    @Test
    fun malformedGraphQlFallsBackToTitlePageJsonLd() = runTest {
        var graphCalls = 0
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                graphCalls += 1
                if (graphCalls == 1) """{"errors":[{"message":"blocked"}]}"""
                else "{malformed"
            },
            pageLoader = { url ->
                assertTrue(url.endsWith("/title/tt0468569/reference/"))
                """
                    <link rel="canonical" href="https://www.imdb.com/title/tt0468569/">
                    <script type="application/ld+json">
                    {"url":"https://www.imdb.com/title/tt0468569/","name":"The Dark Knight",
                     "aggregateRating":{"ratingValue":9.0,"ratingCount":3000000}}
                    </script>
                """.trimIndent()
            },
        )

        val result = repository.ratingFor(
            media("tt0468569", "The Dark Knight", "2008"),
        )

        assertEquals(2, graphCalls)
        assertEquals(RatingSourceState.VERIFIED, result.state)
        assertEquals(9.0, result.rating ?: 0.0, 0.001)
    }

    @Test
    fun mismatchedIdentityIsRejectedAndDoesNotBecomeAnImdbRating() = runTest {
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                ratedPayload("tt1375666", "A Different Film", 1995, "movie", 9.9, 10)
            },
            pageLoader = { throw IOException("offline") },
        )

        val result = repository.ratingFor(
            media("tt1375666", "Inception", "2010"),
        )

        assertEquals(RatingSourceState.UNAVAILABLE, result.state)
        assertEquals(null, result.rating)
    }

    @Test
    fun challengeHtmlCannotBeMisreportedAsARealUnratedTitle() = runTest {
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                throw IOException("blocked")
            },
            pageLoader = {
                "<html><title>Robot or human?</title><p>Verify this request</p></html>"
            },
        )

        val result = repository.ratingFor(
            media("tt1375666", "Inception", "2010"),
        )

        assertEquals(RatingSourceState.UNAVAILABLE, result.state)
        assertEquals(null, result.rating)
    }

    @Test
    fun verifiedTitleWithoutAggregateRatingIsNotRated() = runTest {
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                """
                    {"data":{"title":{
                      "id":"tt9999999",
                      "titleText":{"text":"Future Film"},
                      "releaseYear":{"year":2027},
                      "titleType":{"id":"movie"},
                      "ratingsSummary":{"aggregateRating":null,"voteCount":0}
                    }}}
                """.trimIndent()
            },
            pageLoader = { error("HTML fallback must not run") },
        )

        val result = repository.ratingFor(
            media("tt9999999", "Future Film", "2027"),
        )

        assertEquals(RatingSourceState.NOT_RATED, result.state)
        assertEquals(null, result.rating)
    }

    @Test(expected = CancellationException::class)
    fun cancellationAlwaysPropagates() = runTest {
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                throw CancellationException("closed")
            },
            pageLoader = { error("must not continue after cancellation") },
        )

        repository.ratingFor(media("tt1375666", "Inception", "2010"))
    }

    @Test
    fun featureFilmPreferredOverShortFilmInResolveIdentity() = runTest {
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            pageLoader = { url ->
                if (url.contains("suggestion")) {
                    """
                    {"d":[
                      {"id":"tt37287335","l":"Obsession","q":"feature","qid":"movie","rank":11,"tl":"2025","y":2025},
                      {"id":"tt39365308","l":"Obsession","q":"short","qid":"short","rank":35788,"tl":"2026 Short","y":2026}
                    ]}
                    """.trimIndent()
                } else {
                    error("Unexpected URL $url")
                }
            },
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                ratedPayload("tt37287335", "Obsession", 2025, "movie", 7.8, 325_953)
            },
        )

        val result = repository.ratingFor(
            media(
                imdbId = "",
                title = "Obsession",
                year = "2026",
            ),
        )

        assertEquals("tt37287335", result.identity.imdbId)
        assertEquals(RatingSourceState.VERIFIED, result.state)
        assertEquals(7.8, result.rating ?: 0.0, 0.001)
        assertEquals(325_953, result.voteCount)
    }

    @Test
    fun releaseYearDriftWithinTwoYearsIsAcceptedInGraphQL() = runTest {
        val repository = DefaultImdbRatingRepository(
            cacheStore = null,
            pageLoader = { error("HTML fallback must not run") },
            graphQlTransport = ImdbGraphQlTransport { _, _, _ ->
                ratedPayload("tt37287335", "Obsession", 2025, "movie", 7.8, 325_953)
            },
        )

        val result = repository.ratingFor(
            media(
                imdbId = "tt37287335",
                title = "Obsession",
                year = "2026",
            ),
        )

        assertEquals(RatingSourceState.VERIFIED, result.state)
        assertEquals(7.8, result.rating ?: 0.0, 0.001)
    }

    private fun media(
        imdbId: String,
        title: String,
        year: String,
    ) = Media(
        id = imdbId.hashCode(),
        type = MediaType.MOVIE,
        title = title,
        year = year,
        imdbId = imdbId,
    )

    private fun ratedPayload(
        imdbId: String,
        title: String,
        year: Int,
        type: String,
        rating: Double,
        votes: Int,
    ): String = """
        {"data":{"title":{
          "id":"$imdbId",
          "titleText":{"text":"$title"},
          "releaseYear":{"year":$year},
          "titleType":{"id":"$type"},
          "ratingsSummary":{"aggregateRating":$rating,"voteCount":$votes}
        }}}
    """.trimIndent()
}
