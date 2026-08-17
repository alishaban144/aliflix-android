package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.discover.AskAliflixRequest
import com.aliflix.app.ui.discover.AskAliflixRequestMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAliflixWiringIntegrationTest {
    @Test
    fun describeForwardsRawQueryAndSelectedTvType() {
        val mapped = AskAliflixRequestMapper.map(
            AskAliflixRequest.Describe(MediaType.TV, "  kids with supernatural powers  "),
            "00000000-0000-4000-8000-000000000001",
        )
        val json = mapped.workerRequest.toJson()
        assertEquals("kids with supernatural powers", json.getString("query"))
        assertEquals("tv", json.getString("mediaType"))
        assertEquals("describe", json.getString("mode"))
        assertFalse(json.getString("query").contains("Series —"))
    }

    @Test
    fun similarPreservesAnchorTmdbIdentityAndRequestedOutputType() {
        val anchor = Media(id = 1396, type = MediaType.TV, title = "Breaking Bad")
        val json = AskAliflixRequestMapper.map(
            AskAliflixRequest.Similar(outputMediaType = MediaType.TV, anchor = anchor),
            "00000000-0000-4000-8000-000000000002",
        ).workerRequest.toJson()
        assertEquals("similar", json.getString("mode"))
        assertEquals("tv", json.getString("mediaType"))
        assertEquals(1396, json.getJSONObject("anchor").getInt("tmdbId"))
        assertEquals("tv", json.getJSONObject("anchor").getString("mediaType"))
        assertTrue(json.getString("query").contains("Breaking Bad"))
    }

    @Test
    fun similarAnchorRequiresCanonicalTmdbIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            V3RecommendationAnchor(tmdbId = 0, title = "Breaking Bad", mediaType = "tv")
        }
    }

    @Test
    fun filtersSerializeEverySupportedTmdbConstraintWithoutOmdbFields() {
        val spec = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.SERIES,
            includedGenres = listOf("Crime"), excludedGenres = listOf("Comedy"),
            runtimeMinimumMinutes = 40, runtimeMaximumMinutes = 70,
            yearMinimum = 2021, yearMaximum = 2025, minimumTmdb = 7.5,
            originalLanguage = "ko", countries = listOf("KR"), discoveryText = "serial killers",
        )
        val filters = AskAliflixRequestMapper.map(
            AskAliflixRequest.Filters(spec), "00000000-0000-4000-8000-000000000003",
        ).workerRequest.toJson().getJSONObject("filters")
        assertEquals(2021, filters.getInt("minimumYear"))
        assertEquals("ko", filters.getString("originalLanguage"))
        assertEquals("KR", filters.getJSONArray("originCountries").getString(0))
        assertEquals(7.5, filters.getDouble("minimumTmdbRating"), 0.0)
        assertFalse(filters.has("minimumImdb"))
        assertFalse(filters.has("minimumRottenTomatoes"))
    }

    @Test
    fun responseMappingRetainsTmdbMetadataAndCursor() {
        val response = V3RecommendationResponse.fromJson(JSONObject("""
            {"requestId":"r","totalResults":137,"nextCursor":"cursor","hasMore":true,"results":[{
              "tmdbId":60059,"mediaType":"tv","title":"Better Call Saul","originalTitle":"Better Call Saul",
              "overview":"A lawyer's transformation","posterPath":"/better-call-saul.jpg","releaseDate":"2015-02-08","genres":["Crime","Drama"],
              "runtimeMinutes":47,"originalLanguage":"en","originCountries":["US"],"tmdbRating":8.7,
              "tmdbVoteCount":6000,"matchLevel":"Exceptional","finalScore":0.92,
              "matchReasons":["Recommended by TMDB"],"retrievalSources":["recommendations:page-1"]}
        ]} """))
        val item = response.results.single()
        assertEquals(listOf("Crime", "Drama"), item.genres)
        assertEquals(47, item.runtimeMinutes)
        assertEquals(8.7, item.tmdbRating!!, 0.0)
        assertEquals(listOf("Recommended by TMDB"), item.matchReasons)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/better-call-saul.jpg",
            Media(id = item.tmdbId, type = MediaType.TV, title = item.title, posterPath = item.posterPath).posterUrl,
        )
        assertEquals("cursor", response.nextCursor)
        assertEquals(137, response.totalResults)
        assertTrue(response.hasMore)
    }
}
