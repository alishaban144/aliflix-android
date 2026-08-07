package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import com.aliflix.app.model.HomeContent
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RottenTomatoesDetailPipelineTest {
    private val item = Media(238, MediaType.MOVIE, "The Godfather", year = "1972")

    @Test fun `warm detail reopen publishes cached verified score without another RT request`() = runBlocking {
        val calls = AtomicInteger()
        val client = catalogClient(calls) { validRatedPage() }
        val firstStates = mutableListOf<RatingSourceState?>()
        client.details(item) { media, _ -> firstStates += media.rottenTomatoesState }
        val callsAfterCold = calls.get()
        val secondStates = mutableListOf<RatingSourceState?>()
        client.details(item) { media, _ -> secondStates += media.rottenTomatoesState }

        assertTrue(RatingSourceState.LOADING in firstStates)
        assertTrue(RatingSourceState.VERIFIED in firstStates)
        assertTrue(RatingSourceState.VERIFIED in secondStates)
        assertEquals(callsAfterCold, calls.get())
    }

    @Test fun `offline unavailable is not cached and online reopen recovers`() = runBlocking {
        val calls = AtomicInteger()
        var offline = true
        val client = catalogClient(calls) {
            if (offline) throw IOException("offline") else validRatedPage()
        }
        val offlineStates = mutableListOf<RatingSourceState?>()
        client.details(item) { media, _ -> offlineStates += media.rottenTomatoesState }
        offline = false
        val onlineStates = mutableListOf<RatingSourceState?>()
        client.details(item) { media, _ -> onlineStates += media.rottenTomatoesState }

        assertTrue(RatingSourceState.UNAVAILABLE in offlineStates)
        assertTrue(RatingSourceState.LOADING in onlineStates)
        assertTrue(RatingSourceState.VERIFIED in onlineStates)
        assertTrue(calls.get() >= 3)
    }

    private fun catalogClient(calls: AtomicInteger, response: () -> String): CatalogClient {
        val cache = MemoryRtCache()
        val rtClient = RottenTomatoesClient(
            RottenTomatoesTransport { url ->
                calls.incrementAndGet()
                RtHttpResponse(url, "https://www.rottentomatoes.com/m/the_godfather", 200, "text/html", response(), 10)
            },
            {},
        )
        return CatalogClient(
            cacheStore = cache,
            pageLoader = { "<html><head><title>The Godfather</title></head><body></body></html>" },
            rottenTomatoesClientOverride = rtClient,
            ioDispatcher = Dispatchers.Unconfined,
            computationDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun validRatedPage() = """
        <html><head><title>The Godfather - Rotten Tomatoes</title>
        <link rel="canonical" href="https://www.rottentomatoes.com/m/the_godfather"></head>
        <body><main>97% Tomatometer</main></body></html>
    """.trimIndent()

    private class MemoryRtCache : CatalogCacheStore {
        private val ratings = mutableMapOf<String, RottenTomatoesSnapshot>()
        override suspend fun loadHome(): HomeContent? = null
        override suspend fun saveHome(content: HomeContent) = Unit
        override suspend fun loadRottenTomatoesRating(mediaKey: String, maxAgeMs: Long) = ratings[mediaKey]
        override suspend fun saveRottenTomatoesRating(mediaKey: String, snapshot: RottenTomatoesSnapshot) {
            if (snapshot.state !in setOf(RatingSourceState.UNAVAILABLE, RatingSourceState.LOADING)) ratings[mediaKey] = snapshot
        }
    }
}
