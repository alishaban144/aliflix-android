package com.aliflix.app.data

import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationSourceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RecommendationCatalogFallbackTest {
    @Test
    fun imdbAdvancedFailureScansTmdbAndVerifiesOnlyImdbRating() = runTest {
        val client = CatalogClient(
            jsonPoster = { _, body ->
                if ("advancedTitleSearch" in body) {
                    throw IOException("IMDb search challenged")
                }
                """
                    {"data":{"title":{"ratingsSummary":{
                      "aggregateRating":7.8,"voteCount":1000
                    }}}}
                """.trimIndent()
            },
            pageLoader = { url ->
                when {
                    "sg.media-imdb.com/suggestion" in url ->
                        """{"d":[{"id":"tt123","l":"Fallback","y":2020,"q":"feature"}]}"""
                    else -> throw IOException("Unexpected request: $url")
                }
            },
            formTransport = CatalogFormTransport { url, _, _ ->
                if (url.endsWith("/discover/movie")) {
                    tmdbResult()
                } else {
                    throw IOException("Unexpected request: $url")
                }
            },
        )

        val page = client.recommendationPage(
            CatalogDiscoverySpec(
                mediaKind = RecommendationMediaKind.MOVIE,
                includedGenres = listOf("Thriller"),
                yearMinimum = 2016,
                minimumImdb = 7.0,
            ),
        )

        assertEquals(listOf("Fallback"), page.items.map { it.media.title })
        assertEquals(7.8, page.items.single().media.imdbRating ?: 0.0, 0.001)
        assertEquals(
            RecommendationSourceStatus.DEGRADED,
            page.sourceHealth.imdb,
        )
        assertTrue("IMDB_SCAN" in page.items.single().sources)
    }

    private fun tmdbResult(): String = """
        <main>
          <div data-object-id="movie-100">
            <a data-media-type="movie" href="/movie/100-fallback">
              <img class="poster" alt="Fallback" src="/fallback.jpg" />
            </a>
            <a data-media-type="movie" href="/movie/100-fallback">
              <h2>Fallback</h2>
            </a>
            <span class="release_date">May 4, 2020</span>
            <p>A tense mystery.</p>
          </div>
        </main>
    """.trimIndent()
}
