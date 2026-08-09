package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.HomeContent
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationPageCursor
import com.aliflix.app.recommendation.RecommendationSourceStatus
import com.aliflix.app.recommendation.RequiredMetadataFields
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationLastGoodCacheTest {
    @Test
    fun providerOutageUsesOnlyFullyVerifiedHardValidLastGoodItems() = runTest {
        val valid = item(
            id = 1,
            title = "Verified Thriller",
        )
        val cache = LastGoodCache(
            listOf(
                valid,
                item(id = 2, year = "2014"),
                item(id = 3, imdbRating = 6.9),
                item(id = 4, tmdbRating = 6.8),
                item(id = 5, runtimeMinutes = 145),
                item(id = 6, language = "French"),
                item(id = 7, genres = listOf("Thriller", "Horror")),
                item(id = 8, genresVerified = false),
                item(id = 9, title = "XXX Adult Porn Collection"),
                item(id = 10, type = MediaType.TV),
            ),
        )
        val client = unavailableClient(cache)

        val page = client.recommendationPage(
            spec = constrainedSpec,
            requiredFields = requiredFields,
        )

        assertEquals(listOf(valid.media.key), page.items.map { it.media.key })
        assertTrue(page.fromCache)
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
        assertEquals(
            RecommendationSourceStatus.DEGRADED,
            page.sourceHealth.catalogue,
        )
        assertEquals(
            RecommendationSourceStatus.DEGRADED,
            page.sourceHealth.imdb,
        )
        assertTrue("LAST_GOOD_CACHE" in page.items.single().sources)
        assertEquals(MediaType.MOVIE, cache.requestedType)
    }

    @Test
    fun invalidOrAlreadySeenLastGoodItemsPreserveProviderUnavailableOutcome() = runTest {
        val valid = item(id = 20, title = "Already Seen")
        val cache = LastGoodCache(
            listOf(
                valid,
                item(id = 21, runtimeMinutes = null),
                item(id = 22, imdbRating = null),
            ),
        )
        val client = unavailableClient(cache)

        val outcome = client.recommendationPageOutcome(
            spec = constrainedSpec,
            cursor = RecommendationPageCursor(seenKeys = setOf(valid.media.key)),
            requiredFields = requiredFields,
        )

        assertTrue(outcome is CatalogPageOutcome.Unavailable)
        assertFalse(outcome is CatalogPageOutcome.Empty)
    }

    private fun unavailableClient(cache: CatalogCacheStore) = CatalogClient(
        cacheStore = cache,
        jsonPoster = { _, _ -> throw IOException("IMDb unavailable") },
        formTransport = CatalogFormTransport { _, _, _ ->
            throw IOException("TMDB unavailable")
        },
        pageLoader = { throw IOException("Page unavailable") },
    )

    private fun item(
        id: Int,
        title: String = "Cached Movie $id",
        type: MediaType = MediaType.MOVIE,
        year: String = "2022",
        tmdbRating: Double = 8.1,
        imdbRating: Double? = 7.8,
        genres: List<String> = listOf("Thriller"),
        genresVerified: Boolean = true,
        runtimeMinutes: Int? = 105,
        language: String? = "English",
    ) = RecommendationDiscoveryItem(
        media = Media(
            id = id,
            type = type,
            title = title,
            year = year,
            rating = tmdbRating,
            imdbRating = imdbRating,
            genres = genres,
        ),
        metadata = CatalogVerifiedMetadata(
            genresVerified = genresVerified,
            runtimeMinutes = runtimeMinutes,
            averageEpisodeRuntimeMinutes = runtimeMinutes.takeIf {
                type == MediaType.TV
            },
            originalLanguage = language,
        ),
        sources = setOf("TMDB"),
        sourceCount = 1,
    )

    private class LastGoodCache(
        private val items: List<RecommendationDiscoveryItem>,
    ) : CatalogCacheStore {
        var requestedType: MediaType? = null

        override suspend fun loadHome(): HomeContent? = null

        override suspend fun saveHome(content: HomeContent) = Unit

        override suspend fun loadLastGoodRecommendationItems(
            mediaType: MediaType,
            maxAgeMs: Long,
            limit: Int,
        ): List<RecommendationDiscoveryItem> {
            requestedType = mediaType
            return items.take(limit)
        }
    }

    private companion object {
        val constrainedSpec = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.MOVIE,
            includedGenres = listOf("Thriller"),
            excludedGenres = listOf("Horror"),
            runtimeMaximumMinutes = 120,
            yearMinimum = 2016,
            minimumImdb = 7.0,
            minimumTmdb = 7.0,
            originalLanguage = "English",
        )
        val requiredFields = RequiredMetadataFields(
            genres = true,
            runtime = true,
            originalLanguage = true,
            imdbRating = true,
            tmdbRating = true,
        )
    }
}
