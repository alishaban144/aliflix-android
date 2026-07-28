package com.aliflix.app.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPreferenceParserTest {
    @Test
    fun parsesTerrifyingMovieUnderOneHundredMinutes() {
        val result = RecommendationPreferenceParser.parse(
            "A terrifying movie under 100 minutes",
        )
        val preferences = result.preferences

        assertEquals(RecommendationContentType.MOVIE, preferences.contentType?.value)
        assertEquals(RecommendationMood.SCARY, preferences.moods.single().value)
        assertEquals(100, preferences.runtimeMaximumMinutes?.value)
        assertEquals(ConstraintStrength.HARD, preferences.runtimeMaximumMinutes?.strength)
        assertNull(result.confirmation)
    }

    @Test
    fun parsesFunnyWithFriendsAsSoftContext() {
        val preferences = RecommendationPreferenceParser.parse(
            "Something funny to watch with friends",
        ).preferences

        assertTrue(preferences.moods.any { it.value == RecommendationMood.FUNNY })
        assertEquals(ViewingContext.FRIENDS, preferences.viewingContext?.value)
        assertTrue(
            preferences.includedGenres.any {
                it.value == "Comedy" && it.strength == ConstraintStrength.SOFT
            },
        )
    }

    @Test
    fun parsesDarkerAndShorterThanAnchor() {
        val preferences = RecommendationPreferenceParser.parse(
            "Something darker and shorter than Interstellar",
        ).preferences

        assertTrue(preferences.moods.any { it.value == RecommendationMood.DARK })
        assertEquals("Interstellar", preferences.similarityTitle?.value)
        assertEquals(
            RelativeRuntimePreference.SHORTER_THAN_ANCHOR,
            preferences.relativeRuntime?.value,
        )
        assertEquals(ConstraintStrength.HARD, preferences.relativeRuntime?.strength)
    }

    @Test
    fun parsesNumericQualityEraAndRuntimeAsHardConstraints() {
        val preferences = RecommendationPreferenceParser.parse(
            "IMDb 8+ after 2015 under 2 hours",
        ).preferences

        assertEquals(8.0, preferences.minimumImdb?.value ?: 0.0, 0.001)
        assertEquals(2016, preferences.yearMinimum?.value)
        assertEquals(120, preferences.runtimeMaximumMinutes?.value)
        assertEquals(ConstraintStrength.HARD, preferences.minimumImdb?.strength)
        assertEquals(ConstraintStrength.HARD, preferences.yearMinimum?.strength)
    }

    @Test
    fun newerExplicitLimitReplacesOlderLimit() {
        val first = RecommendationPreferenceParser.parse(
            "under 90 minutes",
        ).preferences
        val second = RecommendationPreferenceParser.parse(
            "under 120 minutes",
            first,
        ).preferences

        assertEquals(120, second.runtimeMaximumMinutes?.value)
    }

    @Test
    fun explicitGenreExclusionIsHardAndRemovesContradictoryInclusion() {
        val included = RecommendationPreferenceParser.parse(
            "I would like comedy",
        ).preferences
        val excluded = RecommendationPreferenceParser.parse(
            "No comedy",
            included,
        ).preferences

        assertTrue(excluded.includedGenres.none { it.value == "Comedy" })
        assertTrue(
            excluded.excludedGenres.any {
                it.value == "Comedy" && it.strength == ConstraintStrength.HARD
            },
        )
    }

    @Test
    fun conflictingRuntimeProducesExactlyOneConfirmationQuestion() {
        val current = RecommendationPreferenceParser.parse(
            "at least 150 minutes",
        ).preferences
        val result = RecommendationPreferenceParser.parse(
            "under 90 minutes",
            current,
        )

        assertEquals("runtime_conflict", result.confirmation?.id)
        assertEquals(1, listOfNotNull(result.confirmation).size)
    }

    @Test
    fun unverifiableHardClaimIsOnlyOfferedAsSoftWebPreference() {
        val result = RecommendationPreferenceParser.parse(
            "It must have a happy ending and subtitles",
        )

        assertEquals(
            RecommendationDimension.UNSUPPORTED_CONFIRMATION,
            result.confirmation?.dimension,
        )
        assertTrue(result.preferences.unverifiedTerms.contains("ending type"))
        assertTrue(result.preferences.unverifiedTerms.contains("subtitle availability"))
    }

    @Test
    fun doesNotMatterAddsNoConstraint() {
        val preferences = RecommendationPreferenceParser.parse(
            "doesn't matter",
        ).preferences

        assertEquals(0, preferences.explicitSignalCount)
        assertTrue(preferences.answeredDimensions.isEmpty())
    }
}
