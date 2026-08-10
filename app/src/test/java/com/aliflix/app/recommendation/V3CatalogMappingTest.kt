package com.aliflix.app.recommendation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V3CatalogMappingTest {
    @Test
    fun titleDetailsParserKeepsTmdbMetadataAndCreators() {
        val parsed = V3TitleDetails.fromJson(
            JSONObject(
                """
                {
                  "tmdbId": 1396,
                  "mediaType": "tv",
                  "title": "Breaking Bad",
                  "originalTitle": "Breaking Bad",
                  "overview": "A chemistry teacher changes course.",
                  "posterPath": "/poster.jpg",
                  "backdropPath": "/backdrop.jpg",
                  "releaseDate": "2008-01-20",
                  "genres": ["Drama", "Crime"],
                  "originalLanguage": "en",
                  "originCountries": ["US"],
                  "runtimeMinutes": 47,
                  "tmdbRating": 8.9,
                  "tmdbVoteCount": 15000,
                  "status": "Ended",
                  "creators": [{"tmdbId": 66633, "name": "Vince Gilligan", "profilePath": "/vince.jpg"}],
                  "cast": [{"tmdbId": 17419, "name": "Bryan Cranston"}]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("tv", parsed.media.mediaType)
        assertEquals(listOf("Drama", "Crime"), parsed.media.genres)
        assertEquals("en", parsed.media.originalLanguage)
        assertEquals("Ended", parsed.status)
        assertEquals(66633, parsed.creators.single().tmdbId)
        assertEquals("/vince.jpg", parsed.creators.single().profilePath)
        assertNull(parsed.cast.single().profilePath)
    }

    @Test
    fun personCreditsParserPreservesMixedMovieAndSeriesTypes() {
        val parsed = V3PersonCredits.fromJson(
            JSONObject(
                """
                {
                  "person": {"tmdbId": 66633, "name": "Vince Gilligan"},
                  "results": [
                    {"tmdbId": 1396, "mediaType": "tv", "title": "Breaking Bad", "genres": ["Drama"], "originCountries": ["US"]},
                    {"tmdbId": 37165, "mediaType": "movie", "title": "Hancock", "genres": ["Comedy"], "originCountries": ["US"]}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("tv", "movie"), parsed.results.map { it.mediaType })
        assertEquals(listOf(1396, 37165), parsed.results.map { it.tmdbId })
    }
}
