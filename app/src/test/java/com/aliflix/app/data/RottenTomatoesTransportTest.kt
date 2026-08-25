package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Episode
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RottenTomatoesTransportTest {
    private val movie = Media(1, MediaType.MOVIE, "The Godfather", year = "1972")

    @Test fun `403 is unavailable and never not rated`() = runBlocking {
        val client = clientReturning(403, "Access denied")
        val result = client.loadFetchResult(movie)
        assertEquals(FailureReason.HTTP_403, (result as RottenTomatoesFetchResult.Unavailable).reason)
        assertEquals(RatingSourceState.UNAVAILABLE, client.loadRating(movie).state)
    }

    @Test fun `429 is unavailable and never not rated`() = runBlocking {
        val result = clientReturning(429, "Too many requests").loadFetchResult(movie)
        assertEquals(FailureReason.HTTP_429, (result as RottenTomatoesFetchResult.Unavailable).reason)
    }

    @Test fun `challenge page is unavailable`() = runBlocking {
        val result = clientReturning(200, "<html><title>Challenge</title>Verify you are human</html>")
            .loadFetchResult(movie)
        assertEquals(FailureReason.BLOCKED_PAGE, (result as RottenTomatoesFetchResult.Unavailable).reason)
    }

    @Test fun `only verified identity with genuine page and no score is not rated`() = runBlocking {
        val html = validPage("The Godfather", "https://www.rottentomatoes.com/m/the_godfather", "No critic score is available.")
        assertEquals(RottenTomatoesFetchResult.ConfirmedNotRated, clientReturning(200, html).loadFetchResult(movie))
    }

    @Test fun `identity mismatch is unavailable rather than not rated`() = runBlocking {
        val html = validPage("The Dark Knight", "https://www.rottentomatoes.com/m/the_dark_knight", "94% Tomatometer")
        val result = clientReturning(200, html).loadFetchResult(movie)
        assertTrue(result is RottenTomatoesFetchResult.Unavailable)
        assertFalse(result == RottenTomatoesFetchResult.ConfirmedNotRated)
    }

    @Test fun `same title from a different known year is an identity mismatch`() = runBlocking {
        val html = validPage("The Godfather", "https://www.rottentomatoes.com/m/the_godfather", """<script type="application/ld+json">{"dateCreated":"1982"}</script>""")
        val result = clientReturning(200, html).loadFetchResult(movie)
        assertTrue(result is RottenTomatoesFetchResult.Unavailable)
    }

    @Test fun `verified visible tomatometer is returned`() = runBlocking {
        val html = validPage("The Godfather", "https://www.rottentomatoes.com/m/the_godfather", "97% Tomatometer")
        val result = clientReturning(200, html).loadFetchResult(movie) as RottenTomatoesFetchResult.Verified
        assertEquals(97, result.rating)
    }

    @Test fun `iso dateCreated and string ratingValue are verified`() = runBlocking {
        val obsession = Media(2, MediaType.MOVIE, "Obsession", year = "2026")
        val html = validPage(
            "Obsession (2025)",
            "https://www.rottentomatoes.com/m/obsession_2025",
            """<script type="application/ld+json">{"dateCreated":"2026-05-15","aggregateRating":{"ratingValue":"94","ratingCount":319}}</script>""",
        )
        val result = clientReturning(200, html).loadFetchResult(obsession) as RottenTomatoesFetchResult.Verified
        assertEquals(94, result.rating)
    }

    @Test fun `episode critic score is loaded only from the verified episode page`() = runBlocking {
        val series = Media(100, MediaType.TV, "The Last of Us", year = "2023")
        val episode = Episode(1, 3, "Long, Long Time")
        val client = episodeClient(
            seriesPath = "/tv/the_last_of_us",
            seriesTitle = series.title,
            episode = episode,
            episodeContent = """
                <script type="application/json">
                  {"criticsScore":{"score":"98","ratingCount":50,"reviewCount":50}}
                </script>
            """.trimIndent(),
        )

        val result = client.loadEpisodeRatings(series, listOf(episode))[3]

        assertEquals(98, result?.rating)
        assertEquals(RatingSourceState.VERIFIED, result?.state)
    }

    @Test fun `episode with zero critic reviews is confirmed not rated`() = runBlocking {
        val series = Media(1396, MediaType.TV, "Breaking Bad", year = "2008")
        val episode = Episode(5, 14, "Ozymandias")
        val client = episodeClient(
            seriesPath = "/tv/breaking_bad",
            seriesTitle = series.title,
            episode = episode,
            episodeContent = """
                <script type="application/json">
                  {"audienceScore":{"scorePercent":"99%"},
                   "criticsScore":{"ratingCount":0,"reviewCount":0}}
                </script>
                <p>Tomatometer 0 Reviews</p>
            """.trimIndent(),
        )

        val result = client.loadEpisodeRatings(series, listOf(episode))[14]

        assertEquals(null, result?.rating)
        assertEquals(RatingSourceState.NOT_RATED, result?.state)
    }

    @Test fun `audience score can never become an episode tomatometer score`() {
        val html = """
            <script type="application/json">
              {"audienceScore":{"scorePercent":"99%"},
               "criticsScore":{"ratingCount":0,"reviewCount":0}}
            </script>
        """.trimIndent()
        val client = clientReturning(200, html)

        assertEquals(null, client.parseRating(html))
        assertTrue(client.hasConfirmedNoCriticRating(html))
    }

    @Test fun `wrong episode route is unavailable rather than not rated`() = runBlocking {
        val series = Media(1396, MediaType.TV, "Breaking Bad", year = "2008")
        val requested = Episode(5, 14, "Ozymandias")
        val wrong = Episode(5, 13, "To'hajiilee")
        val client = episodeClient(
            seriesPath = "/tv/breaking_bad",
            seriesTitle = series.title,
            episode = wrong,
            requestedEpisode = requested,
            episodeContent = "<p>Tomatometer 0 Reviews</p>",
        )

        val result = client.loadEpisodeRatings(series, listOf(requested))[14]

        assertEquals(RatingSourceState.UNAVAILABLE, result?.state)
    }

    @Test fun `caller cancellation is never swallowed`() = runBlocking {
        val client = RottenTomatoesClient(RottenTomatoesTransport { awaitCancellation() }, {})
        var cancellationObserved = false
        val job = launch {
            try { client.loadFetchResult(movie) } catch (_: CancellationException) { cancellationObserved = true; throw CancellationException() }
        }
        yield()
        job.cancel()
        job.join()
        assertTrue(cancellationObserved)
    }

    private fun clientReturning(status: Int, body: String) = RottenTomatoesClient(
        RottenTomatoesTransport { url -> RtHttpResponse(url, url, status, "text/html", body, 12) },
        {},
    )

    private fun episodeClient(
        seriesPath: String,
        seriesTitle: String,
        episode: Episode,
        requestedEpisode: Episode = episode,
        episodeContent: String,
    ) = RottenTomatoesClient(
        RottenTomatoesTransport { url ->
            val body = if (url.endsWith(seriesPath)) {
                validPage(
                    seriesTitle,
                    "https://www.rottentomatoes.com$seriesPath",
                    "<main>Verified TV series</main>",
                )
            } else {
                val requestedSuffix = "/s${requestedEpisode.seasonNumber.toString().padStart(2, '0')}" +
                    "/e${requestedEpisode.number.toString().padStart(2, '0')}"
                val actualSuffix = "/s${episode.seasonNumber.toString().padStart(2, '0')}" +
                    "/e${episode.number.toString().padStart(2, '0')}"
                val canonical = "https://www.rottentomatoes.com$seriesPath$actualSuffix"
                """
                    <html><head><title>$seriesTitle - Season ${episode.seasonNumber}, Episode ${episode.number} ${episode.title} - Rotten Tomatoes</title>
                    <link rel="canonical" href="$canonical"></head><body>
                    <script type="application/ld+json">
                      {"@type":"TVEpisode","episodeNumber":"${episode.number}","name":"${episode.title}",
                       "partOfSeries":{"@type":"TVSeries","name":"$seriesTitle"}}
                    </script>
                    $episodeContent
                    <span data-requested="$requestedSuffix"></span>
                    </body></html>
                """.trimIndent()
            }
            RtHttpResponse(url, url, 200, "text/html", body, 12)
        },
        {},
    )

    private fun validPage(title: String, canonical: String, content: String) =
        """<html><head><title>$title - Rotten Tomatoes</title><link rel="canonical" href="$canonical"></head><body><main>$content</main></body></html>"""
}
