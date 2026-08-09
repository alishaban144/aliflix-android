package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSort
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSpec
import com.aliflix.app.ui.discover.AskAliflixRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAliflixWiringIntegrationTest {

    @Test
    fun testOmdbRecommendationSpecPreservesAllFieldsIntact() {
        val originalSpec = OmdbRecommendationSpec(
            mediaType = MediaType.MOVIE,
            includedGenres = setOf("Action", "Sci-Fi"),
            excludedGenres = setOf("Horror"),
            minimumYear = 2016,
            maximumYear = 2024,
            minimumRuntimeMinutes = 90,
            maximumRuntimeMinutes = 150,
            minimumImdbRating = 7.5,
            minimumImdbVotes = 50000,
            minimumRottenTomatoesRating = 80,
            minimumMetascore = 70,
            languages = setOf("English"),
            contentRatings = setOf("PG-13", "R"),
            minimumSeasons = 1,
            maximumSeasons = 5,
            sortMode = OmdbRecommendationSort.IMDB_RATING
        )

        val request = AskAliflixRequest.Filters(originalSpec)
        val specFromRequest = request.spec

        assertEquals(originalSpec.mediaType, specFromRequest.mediaType)
        assertEquals(originalSpec.includedGenres, specFromRequest.includedGenres)
        assertEquals(originalSpec.excludedGenres, specFromRequest.excludedGenres)
        assertEquals(originalSpec.minimumYear, specFromRequest.minimumYear)
        assertEquals(originalSpec.maximumYear, specFromRequest.maximumYear)
        assertEquals(originalSpec.minimumRuntimeMinutes, specFromRequest.minimumRuntimeMinutes)
        assertEquals(originalSpec.maximumRuntimeMinutes, specFromRequest.maximumRuntimeMinutes)
        assertEquals(originalSpec.minimumImdbRating, specFromRequest.minimumImdbRating)
        assertEquals(originalSpec.minimumImdbVotes, specFromRequest.minimumImdbVotes)
        assertEquals(originalSpec.minimumRottenTomatoesRating, specFromRequest.minimumRottenTomatoesRating)
        assertEquals(originalSpec.minimumMetascore, specFromRequest.minimumMetascore)
        assertEquals(originalSpec.languages, specFromRequest.languages)
        assertEquals(originalSpec.contentRatings, specFromRequest.contentRatings)
        assertEquals(originalSpec.minimumSeasons, specFromRequest.minimumSeasons)
        assertEquals(originalSpec.maximumSeasons, specFromRequest.maximumSeasons)
        assertEquals(originalSpec.sortMode, specFromRequest.sortMode)
    }

    @Test
    fun testSingleFieldFiltersAreValidRequests() {
        val yearOnly = OmdbRecommendationSpec(minimumYear = 2020)
        val imdbOnly = OmdbRecommendationSpec(minimumImdbRating = 8.0)
        val rtOnly = OmdbRecommendationSpec(minimumRottenTomatoesRating = 85)
        val runtimeOnly = OmdbRecommendationSpec(maximumRuntimeMinutes = 100)

        assertTrue(AskAliflixRequest.Filters(yearOnly).spec.minimumYear == 2020)
        assertTrue(AskAliflixRequest.Filters(imdbOnly).spec.minimumImdbRating == 8.0)
        assertTrue(AskAliflixRequest.Filters(rtOnly).spec.minimumRottenTomatoesRating == 85)
        assertTrue(AskAliflixRequest.Filters(runtimeOnly).spec.maximumRuntimeMinutes == 100)
    }

    @Test
    fun testSimilarRequestRetainsCanonicalAnchor() {
        val anchorMedia = Media(
            id = 1396,
            imdbId = "tt0903747",
            title = "Breaking Bad",
            overview = "A high school chemistry teacher diagnosed with inoperable lung cancer...",
            type = MediaType.TV,
            year = "2008"
        )
        val request = AskAliflixRequest.Similar(outputMediaType = MediaType.TV, anchor = anchorMedia)

        assertEquals("Breaking Bad", request.anchor.title)
        assertEquals("tt0903747", request.anchor.imdbId)
        assertEquals(1396, request.anchor.id)
        assertEquals(MediaType.TV, request.outputMediaType)
    }
}
