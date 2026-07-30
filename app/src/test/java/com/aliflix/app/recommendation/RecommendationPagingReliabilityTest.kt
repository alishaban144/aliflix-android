package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPagingReliabilityTest {
    @Test
    fun broadThrillerScenarioKeepsAllOneHundredTwentyMatches() {
        val preferences = RecommendationPreferences(
            contentType = hard(RecommendationContentType.MOVIE),
            includedGenres = listOf(hard("Thriller")),
            yearMinimum = hard(2016),
            minimumImdb = hard(7.0),
        )
        val candidates = (1..120).map { index ->
            candidate(
                id = index,
                type = MediaType.MOVIE,
                genres = listOf("Thriller", if (index % 2 == 0) "Crime" else "Mystery"),
                year = (2016 + index % 10).toString(),
                imdb = 7.0 + (index % 20) / 10.0,
            )
        }

        val eligible = RecommendationRanker.hardFilter(preferences, candidates)
        val ranked = RecommendationRanker.rank(preferences, eligible)
        val pages = ranked.chunked(20)

        assertEquals(120, eligible.size)
        assertEquals(
            "Ranking must order valid titles without truncating the catalogue.",
            120,
            ranked.size,
        )
        assertEquals(6, pages.size)
        assertTrue(pages.all { it.size == 20 })
        assertEquals(120, ranked.map { it.media.key }.distinct().size)
        assertTrue(ranked.all { it.media.type == MediaType.MOVIE })
        assertTrue(ranked.all { it.media.year.toInt() >= 2016 })
        assertTrue(ranked.all { (it.media.imdbRating ?: 0.0) >= 7.0 })
        assertTrue(ranked.all { "Thriller" in it.media.genres })
    }

    @Test
    fun smallValidResultSetIsDisplayedWithoutAThreeItemMinimum() {
        val preferences = RecommendationPreferences(
            contentType = hard(RecommendationContentType.TV),
            includedGenres = listOf(hard("Mystery")),
        )
        val candidates = (1..7).map { index ->
            candidate(
                id = index,
                type = MediaType.TV,
                genres = listOf("Mystery"),
                year = "2024",
                imdb = 7.5,
            )
        }

        val ranked = RecommendationRanker.rank(preferences, candidates)

        assertEquals(7, ranked.size)
        assertEquals(7, ranked.map { it.media.key }.distinct().size)
    }

    @Test
    fun catalogFingerprintIsNormalizedAndNeverContainsRawConversationText() {
        val first = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.MOVIE,
            includedGenres = listOf(" Thriller ", "Crime"),
            excludedGenres = listOf("Horror"),
            yearMinimum = 2016,
            minimumImdb = 7.0,
            discoveryText = "I want a tense movie for tonight",
        )
        val equivalent = first.copy(
            includedGenres = listOf("crime", "thriller"),
            excludedGenres = listOf(" HORROR "),
            discoveryText = "This raw conversation must not enter the cache key",
        )

        assertEquals(first.fingerprint, equivalent.fingerprint)
        assertFalse(first.fingerprint.contains("tense movie", ignoreCase = true))
        assertFalse(equivalent.fingerprint.contains("raw conversation", ignoreCase = true))
    }

    @Test
    fun metadataPlanRequestsOnlyFieldsNeededByHardConstraints() {
        val preferences = RecommendationPreferences(
            contentType = hard(RecommendationContentType.MOVIE),
            minimumImdb = hard(7.0),
            minimumRottenTomatoes = soft(80),
            originalLanguage = soft("English"),
        )

        val fields = RequiredMetadataFields.from(preferences)

        assertTrue(fields.imdbRating)
        assertFalse(fields.runtime)
        assertFalse(fields.originalLanguage)
        assertFalse(fields.rottenTomatoesRating)
        assertFalse(fields.tmdbRating)
        assertFalse(fields.tvEpisodeRuntime)
        assertFalse(fields.needsTitlePage)
    }

    @Test
    fun finalPageCanContainFewerThanTwentyItemsWithoutInventingAnotherPage() {
        val candidates = (1..13).map { candidate(it) }
        val page = RecommendationPage(
            candidates = candidates,
            nextCursor = null,
            hasMore = false,
            sourceHealth = RecommendationSourceHealth(),
        )

        assertEquals(13, page.candidates.size)
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
    }

    private fun candidate(
        id: Int,
        type: MediaType = MediaType.MOVIE,
        genres: List<String> = listOf("Drama"),
        year: String = "2020",
        imdb: Double = 7.5,
    ) = RecommendationCandidate(
        media = Media(
            id = id,
            type = type,
            title = "Fixture Title $id",
            overview = "Fixture overview $id",
            year = year,
            rating = imdb,
            imdbRating = imdb,
            genres = genres,
        ),
        metadata = VerifiedMediaMetadata(
            runtimeMinutes = if (type == MediaType.MOVIE) 105 else null,
            averageEpisodeRuntimeMinutes = if (type == MediaType.TV) 48 else null,
            originalLanguage = "English",
        ),
        evidence = "A ${genres.joinToString()} story.",
        sources = setOf("TMDB"),
        sourceCount = 1,
        sourcePosition = id,
    )

    private fun <T> hard(value: T) = PreferenceSignal(
        value = value,
        origin = PreferenceOrigin.EXPLICIT,
        strength = ConstraintStrength.HARD,
    )

    private fun <T> soft(value: T) = PreferenceSignal(
        value = value,
        origin = PreferenceOrigin.EXPLICIT,
        strength = ConstraintStrength.SOFT,
    )
}
