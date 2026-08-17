package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
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

    private fun validPage(title: String, canonical: String, content: String) =
        """<html><head><title>$title - Rotten Tomatoes</title><link rel="canonical" href="$canonical"></head><body><main>$content</main></body></html>"""
}
