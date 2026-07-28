package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun hardRuntimeFailsClosedWhenMetadataIsUnknown() {
        val preferences = RecommendationPreferences(
            runtimeMaximumMinutes = hard(100),
        )

        assertTrue(
            RecommendationRanker.hardFilter(
                preferences,
                listOf(candidate(1, runtime = null)),
            ).isEmpty(),
        )
    }

    @Test
    fun everySupportedHardFilterIsApplied() {
        val preferences = RecommendationPreferences(
            contentType = hard(RecommendationContentType.MOVIE),
            includedGenres = listOf(hard("Thriller")),
            excludedGenres = listOf(hard("Horror")),
            runtimeMinimumMinutes = hard(80),
            runtimeMaximumMinutes = hard(120),
            yearMinimum = hard(2015),
            yearMaximum = hard(2025),
            minimumImdb = hard(8.0),
            minimumRottenTomatoes = hard(75),
            minimumTmdb = hard(7.0),
            originalLanguage = hard("English"),
        )
        val valid = candidate(
            id = 1,
            genres = listOf("Thriller"),
            year = "2019",
            rating = 8.1,
            imdb = 8.4,
            rt = 90,
            runtime = 110,
            language = "English",
        )
        val wrongLanguage = candidate(
            id = 2,
            genres = listOf("Thriller"),
            year = "2019",
            rating = 8.1,
            imdb = 8.4,
            rt = 90,
            runtime = 110,
            language = "French",
        )

        assertEquals(
            listOf(valid.media.key),
            RecommendationRanker.hardFilter(
                preferences,
                listOf(valid, wrongLanguage),
            ).map { it.media.key },
        )
    }

    @Test
    fun tvRuntimeUsesVerifiedEpisodeLength() {
        val preferences = RecommendationPreferences(
            contentType = hard(RecommendationContentType.TV),
            runtimeMaximumMinutes = hard(45),
        )
        val shortSeries = candidate(
            1,
            type = MediaType.TV,
            runtime = 900,
            episodeRuntime = 42,
        )
        val longSeries = candidate(
            2,
            type = MediaType.TV,
            runtime = 30,
            episodeRuntime = 58,
        )

        assertEquals(
            listOf(shortSeries.media.key),
            RecommendationRanker.hardFilter(
                preferences,
                listOf(shortSeries, longSeries),
            ).map { it.media.key },
        )
    }

    @Test
    fun seenRecentlyPlayedShownAndRejectedTitlesNeverReturn() {
        val items = (1..5).map { candidate(it) }
        val preferences = RecommendationPreferences(
            shownKeys = setOf(items[0].media.key),
            rejectedKeys = setOf(items[1].media.key),
        )

        val result = RecommendationRanker.hardFilter(
            preferences = preferences,
            candidates = items,
            recentlyPlayedKeys = setOf(items[2].media.key),
            seenKeys = setOf(items[3].media.key),
        )

        assertEquals(listOf(items[4].media.key), result.map { it.media.key })
    }

    @Test
    fun rankingUsesOneHundredPointWeightsAndPenalizesMissingCoverage() {
        val preferences = RecommendationPreferences(
            moods = listOf(
                PreferenceSignal(
                    RecommendationMood.SCARY,
                    PreferenceOrigin.EXPLICIT,
                    ConstraintStrength.SOFT,
                ),
            ),
            includedGenres = listOf(soft("Horror")),
            minimumImdb = soft(7.0),
        )
        val complete = candidate(
            id = 1,
            genres = listOf("Horror"),
            overview = "A terrifying haunted nightmare.",
            rating = 8.0,
            imdb = 8.0,
            rt = 85,
            runtime = 95,
            language = "English",
            sourceCount = 3,
            position = 1,
        )
        val sparse = candidate(
            id = 2,
            genres = listOf("Horror"),
            overview = "A terrifying haunted nightmare.",
            rating = 8.0,
            runtime = null,
            language = null,
            sourceCount = 3,
            position = 1,
        )
        val ranked = RecommendationRanker.rank(preferences, listOf(sparse, complete))

        assertEquals(complete.media.key, ranked.first().media.key)
        assertTrue(ranked.first().score.total in 0.0..100.0)
        assertTrue(ranked.first().score.contentMatch <= 30.0)
        assertTrue(ranked.first().score.similarity <= 18.0)
        assertTrue(ranked.first().score.contextualFit <= 16.0)
        assertTrue(ranked.first().score.quality <= 16.0)
    }

    @Test
    fun mmrReturnsDistinctAlternativesAndWildcardAboveFloor() {
        val preferences = RecommendationPreferences(
            surpriseMe = true,
            includedGenres = listOf(soft("Drama")),
        )
        val candidates = listOf(
            candidate(1, genres = listOf("Drama"), rating = 9.0, imdb = 9.0, rt = 95),
            candidate(2, genres = listOf("Drama"), rating = 8.8, imdb = 8.8, rt = 93),
            candidate(3, genres = listOf("Drama", "Comedy"), rating = 8.5, imdb = 8.5, rt = 90),
            candidate(4, genres = listOf("Drama", "Crime"), rating = 8.2, imdb = 8.2, rt = 86),
        )
        val ranked = RecommendationRanker.rank(preferences, candidates)

        assertEquals(3, ranked.size)
        assertEquals(3, ranked.map { it.media.key }.distinct().size)
        assertTrue(ranked.last().score.total >= ranked.first().score.total * 0.75)
        assertNotEquals(ranked[0].media.key, ranked[1].media.key)
    }

    @Test
    fun questionSelectionRequiresCoverageAndStopsAfterFive() {
        val candidates = (1..10).map { index ->
            candidate(
                id = index,
                runtime = if (index <= 6) 80 + index else null,
                language = if (index <= 6) "English" else null,
            )
        }
        val noCoverageQuestion = RecommendationQuestionSelector.nextQuestion(
            RecommendationPreferences(
                moods = listOf(soft(RecommendationMood.INTENSE)),
                answeredDimensions = setOf(
                    RecommendationDimension.CONTENT_TYPE,
                    RecommendationDimension.GENRE,
                    RecommendationDimension.ERA,
                    RecommendationDimension.QUALITY,
                    RecommendationDimension.FAMILIARITY,
                    RecommendationDimension.VIEWING_CONTEXT,
                ),
            ),
            candidates,
        )
        val maxed = RecommendationQuestionSelector.nextQuestion(
            RecommendationPreferences(
                askedQuestionIds = listOf("1", "2", "3", "4", "5"),
            ),
            candidates,
        )

        assertNull(noCoverageQuestion)
        assertNull(maxed)
    }

    @Test
    fun entropyPrefersMeaningfulCandidateSeparation() {
        assertTrue(
            RecommendationQuestionSelector.informationGain(
                listOf("movie", "movie", "tv", "tv"),
            ) > 0.9,
        )
        assertEquals(
            0.0,
            RecommendationQuestionSelector.informationGain(
                listOf("movie", "movie", "movie"),
            ),
            0.001,
        )
    }

    @Test
    fun zeroValidCandidatesProducesRealRelaxationChoices() {
        val preferences = RecommendationPreferences(
            runtimeMaximumMinutes = hard(80),
            minimumImdb = hard(9.5),
            yearMinimum = hard(2024),
        )
        val candidates = listOf(
            candidate(1, year = "2024", imdb = 8.0, runtime = 75),
            candidate(2, year = "2020", imdb = 9.6, runtime = 75),
            candidate(3, year = "2025", imdb = 9.6, runtime = 110),
        )

        assertTrue(RecommendationRanker.hardFilter(preferences, candidates).isEmpty())
        val relaxations = RecommendationRanker.relaxationOptions(preferences, candidates)
        assertTrue(relaxations.isNotEmpty())
        assertTrue(relaxations.size <= 3)
        assertTrue(relaxations.all { it.recoveredCandidates > 0 })
    }

    @Test
    fun explanationUsesVerifiedFactsAndNeverUnsupportedClaims() {
        val preferences = RecommendationPreferences(
            includedGenres = listOf(soft("Thriller")),
            runtimeMaximumMinutes = hard(120),
            minimumImdb = hard(8.0),
            unverifiedTerms = listOf("ending type", "subtitle availability"),
        )
        val ranked = RecommendationRanker.rank(
            preferences,
            listOf(
                candidate(
                    1,
                    genres = listOf("Thriller"),
                    runtime = 110,
                    imdb = 8.4,
                ),
            ),
        ).single()

        assertTrue(ranked.explanation.contains("110 minutes"))
        assertTrue(ranked.explanation.contains("IMDb 8.4"))
        assertFalse(ranked.explanation.contains("ending", ignoreCase = true))
        assertFalse(ranked.explanation.contains("subtitle", ignoreCase = true))
    }

    @Test
    fun tasteConfidenceGrowsWithRepeatedExplicitBehavior() {
        val once = TasteSignal("genre:drama", positiveObservations = 1)
        val repeated = TasteSignal("genre:drama", positiveObservations = 4)
        val reset = TasteProfile()

        assertTrue(repeated.confidence > once.confidence)
        assertTrue(repeated.affinity > 0.0)
        assertTrue(reset.signals.isEmpty())
    }

    private fun candidate(
        id: Int,
        type: MediaType = MediaType.MOVIE,
        genres: List<String> = listOf("Drama"),
        overview: String = "A compelling story.",
        year: String = "2020",
        rating: Double = 7.5,
        imdb: Double? = 7.6,
        rt: Int? = 80,
        runtime: Int? = 105,
        episodeRuntime: Int? = null,
        language: String? = "English",
        sourceCount: Int = 2,
        position: Int = 3,
    ) = RecommendationCandidate(
        media = Media(
            id = id,
            type = type,
            title = "Title $id",
            overview = overview,
            year = year,
            rating = rating,
            imdbRating = imdb,
            rottenTomatoesRating = rt,
            genres = genres,
        ),
        metadata = VerifiedMediaMetadata(
            runtimeMinutes = runtime,
            originalLanguage = language,
            seasonCount = if (type == MediaType.TV) 3 else null,
            averageEpisodeRuntimeMinutes = episodeRuntime,
        ),
        evidence = overview,
        sources = setOf("BRAVE", "WIKIPEDIA"),
        sourceCount = sourceCount,
        sourcePosition = position,
    )

    private fun <T> hard(value: T) = PreferenceSignal(
        value,
        PreferenceOrigin.EXPLICIT,
        ConstraintStrength.HARD,
    )

    private fun <T> soft(value: T) = PreferenceSignal(
        value,
        PreferenceOrigin.EXPLICIT,
        ConstraintStrength.SOFT,
    )
}
