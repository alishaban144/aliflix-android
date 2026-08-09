package com.aliflix.app.recommendation

import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.omdb.OmdbCandidateDiscovery
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmdbCandidateDiscoveryTest {

    @Test
    fun testBuildTmdbDiscoverPathParamsForActionSciFiMovies2016Plus() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.MOVIE,
            includedGenres = setOf("Action", "Sci-Fi"),
            minimumYear = 2016,
            minimumImdbRating = 6.0
        )

        val pathParams = OmdbCandidateDiscovery.buildTmdbDiscoverPathParams(spec)

        // Must contain with_genres=28,878 (AND syntax with comma)
        assertTrue("Path params should contain with_genres=28,878", pathParams.contains("with_genres=28,878") || pathParams.contains("with_genres=878,28"))
        // Must NOT contain pipe '|' (which means OR)
        assertFalse("Path params must not contain OR pipe '|'", pathParams.contains("|"))
        // Must contain primary_release_date.gte=2016-01-01
        assertTrue("Path params should contain year limit", pathParams.contains("primary_release_date.gte=2016-01-01"))
    }

    @Test
    fun testBuildTmdbDiscoverPathParamsForTvSeries() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.TV,
            includedGenres = setOf("Sci-Fi"),
            minimumYear = 2018
        )

        val pathParams = OmdbCandidateDiscovery.buildTmdbDiscoverPathParams(spec)

        assertTrue("Path params should contain TV Sci-Fi & Fantasy genre 10765", pathParams.contains("with_genres=10765"))
        assertTrue("Path params should contain TV release date", pathParams.contains("first_air_date.gte=2018-01-01"))
    }
}
