package com.aliflix.app.recommendation

import com.aliflix.app.data.omdb.OmdbTitleMetadata
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.omdb.OmdbConstraintEvaluator
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmdbConstraintEvaluatorTest {

    @Test
    fun testActionSciFiImdb6PlusAfter2015Fixture() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.MOVIE,
            includedGenres = setOf("Action", "Sci-Fi"),
            minimumYear = 2016,
            minimumImdbRating = 6.0
        )

        // Candidate A: Action, Sci-Fi, 2019, IMDb 7.1 -> ACCEPT
        val candidateA = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Action", "Adventure", "Sci-Fi"),
            year = 2019,
            imdbRating = 7.1
        )
        val resultA = OmdbConstraintEvaluator.evaluate(spec, candidateA)
        assertTrue("Candidate A should be ACCEPTED", resultA.accepted)

        // Candidate B: Action, Thriller, 2019, IMDb 8.1 -> REJECT (missing Sci-Fi)
        val candidateB = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Action", "Thriller"),
            year = 2019,
            imdbRating = 8.1
        )
        val resultB = OmdbConstraintEvaluator.evaluate(spec, candidateB)
        assertFalse("Candidate B should be REJECTED (missing Sci-Fi)", resultB.accepted)

        // Candidate C: Sci-Fi, 2020, IMDb 7.3 -> REJECT (missing Action)
        val candidateC = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Sci-Fi", "Drama"),
            year = 2020,
            imdbRating = 7.3
        )
        val resultC = OmdbConstraintEvaluator.evaluate(spec, candidateC)
        assertFalse("Candidate C should be REJECTED (missing Action)", resultC.accepted)

        // Candidate D: Action, Sci-Fi, 2014, IMDb 8.5 -> REJECT (year < 2016)
        val candidateD = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Action", "Sci-Fi"),
            year = 2014,
            imdbRating = 8.5
        )
        val resultD = OmdbConstraintEvaluator.evaluate(spec, candidateD)
        assertFalse("Candidate D should be REJECTED (year 2014 < 2016)", resultD.accepted)

        // Candidate E: Action, Sci-Fi, 2018, IMDb 5.9 -> REJECT (IMDb < 6.0)
        val candidateE = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Action", "Sci-Fi"),
            year = 2018,
            imdbRating = 5.9
        )
        val resultE = OmdbConstraintEvaluator.evaluate(spec, candidateE)
        assertFalse("Candidate E should be REJECTED (IMDb 5.9 < 6.0)", resultE.accepted)

        // Candidate F: Action, Sci-Fi, 2018, IMDb null -> REJECT (IMDb unavailable)
        val candidateF = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Action", "Sci-Fi"),
            year = 2018,
            imdbRating = null
        )
        val resultF = OmdbConstraintEvaluator.evaluate(spec, candidateF)
        assertFalse("Candidate F should be REJECTED (IMDb null)", resultF.accepted)
    }

    @Test
    fun testRottenTomatoesHardFilter() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.MOVIE,
            includedGenres = setOf("Horror"),
            minimumRottenTomatoesRating = 80
        )

        val candidate1 = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Horror"),
            rottenTomatoesRating = 85
        )
        assertTrue(OmdbConstraintEvaluator.evaluate(spec, candidate1).accepted)

        val candidate2 = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Horror"),
            rottenTomatoesRating = 75
        )
        assertFalse(OmdbConstraintEvaluator.evaluate(spec, candidate2).accepted)

        val candidate3 = OmdbTitleMetadata(
            found = true,
            type = "movie",
            genres = listOf("Horror"),
            rottenTomatoesRating = null
        )
        assertFalse(OmdbConstraintEvaluator.evaluate(spec, candidate3).accepted)
    }

    @Test
    fun testPersonWriterFilterDoesNotBleedIntoActors() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.TV,
            includedGenres = setOf("Crime", "Drama"),
            writers = setOf("Vince Gilligan")
        )

        val candidate1 = OmdbTitleMetadata(
            found = true,
            type = "series",
            genres = listOf("Crime", "Drama"),
            writers = listOf("Vince Gilligan")
        )
        assertTrue(OmdbConstraintEvaluator.evaluate(spec, candidate1).accepted)

        val candidate2 = OmdbTitleMetadata(
            found = true,
            type = "series",
            genres = listOf("Crime", "Drama"),
            actors = listOf("Vince Gilligan"),
            writers = emptyList()
        )
        assertFalse(OmdbConstraintEvaluator.evaluate(spec, candidate2).accepted)
    }

    @Test
    fun testLanguageDoesNotConfuseWithCountry() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.MOVIE,
            languages = setOf("Spanish")
        )

        val candidate1 = OmdbTitleMetadata(
            found = true,
            type = "movie",
            languages = listOf("English", "Spanish"),
            countries = listOf("United States")
        )
        assertTrue(OmdbConstraintEvaluator.evaluate(spec, candidate1).accepted)

        val candidate2 = OmdbTitleMetadata(
            found = true,
            type = "movie",
            languages = listOf("English"),
            countries = listOf("Spain")
        )
        assertFalse(OmdbConstraintEvaluator.evaluate(spec, candidate2).accepted)
    }

    @Test
    fun testTvBroadGenreMappingVerification() {
        val spec = OmdbRecommendationSpec(
            mediaType = MediaType.TV,
            includedGenres = setOf("Sci-Fi")
        )

        // Title discovered via TMDB "Sci-Fi & Fantasy" but OMDb lists Fantasy only
        val candidateFantasyOnly = OmdbTitleMetadata(
            found = true,
            type = "series",
            genres = listOf("Fantasy", "Adventure")
        )
        assertFalse(OmdbConstraintEvaluator.evaluate(spec, candidateFantasyOnly).accepted)

        // Title discovered via TMDB "Sci-Fi & Fantasy" and OMDb lists Sci-Fi
        val candidateSciFi = OmdbTitleMetadata(
            found = true,
            type = "series",
            genres = listOf("Sci-Fi", "Drama")
        )
        assertTrue(OmdbConstraintEvaluator.evaluate(spec, candidateSciFi).accepted)
    }
}
