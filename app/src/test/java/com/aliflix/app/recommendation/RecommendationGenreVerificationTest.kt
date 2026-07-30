package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationGenreVerificationTest {
    @Test
    fun hardExclusionFailsClosedUntilGenresAreVerified() {
        val preferences = RecommendationPreferences(
            contentType = PreferenceSignal(
                RecommendationContentType.MOVIE,
                PreferenceOrigin.EXPLICIT,
                ConstraintStrength.HARD,
            ),
            excludedGenres = listOf(
                PreferenceSignal(
                    "Horror",
                    PreferenceOrigin.EXPLICIT,
                    ConstraintStrength.HARD,
                ),
            ),
        )
        val media = Media(
            id = 1,
            type = MediaType.MOVIE,
            title = "A Thriller",
            genres = listOf("Thriller"),
        )
        val unknown = RecommendationCandidate(
            media = media,
            metadata = VerifiedMediaMetadata(genresVerified = false),
        )
        val verified = unknown.copy(
            metadata = VerifiedMediaMetadata(genresVerified = true),
        )

        assertTrue(
            RecommendationRanker.hardFilter(preferences, listOf(unknown)).isEmpty(),
        )
        assertEquals(
            listOf(media.key),
            RecommendationRanker.hardFilter(preferences, listOf(verified))
                .map { it.media.key },
        )
    }
}
