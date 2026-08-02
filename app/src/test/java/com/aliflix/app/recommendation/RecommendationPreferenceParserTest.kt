package com.aliflix.app.recommendation

import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationPreferenceParserTest {
    @Test
    fun exposesStructuredIntentAndCanonicalAnchorResolution() {
        val parsed = RecommendationPreferenceParser.parse(
            "Series similar to Money Heist but darker",
        )
        val canonical = CanonicalTitleAnchor(
            identity = CanonicalMediaIdentity(MediaType.TV, 71446),
            canonicalTitle = "La casa de papel",
            alternativeTitles = setOf("Money Heist", "Haus des Geldes"),
            year = 2017,
        )

        val intent = RecommendationIntent.from(parsed.preferences)
        assertEquals(RecommendationContentType.TV, intent.requiredMediaType)
        assertEquals(RecommendationMood.DARK, intent.moods.single())
        val resolved = CanonicalTitleResolver.resolve(
            query = parsed.preferences.similarityTitle?.value.orEmpty(),
            requiredType = parsed.preferences.contentType?.value,
            candidates = listOf(canonical),
        )
        assertTrue(resolved is TitleAnchorResolution.Resolved)
        assertEquals(
            canonical.identity,
            (resolved as TitleAnchorResolution.Resolved).anchor.identity,
        )
    }

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
    fun scary120MinutePromptIsAHardMovieConstraint() {
        val preferences = RecommendationPreferenceParser.parse(
            "Something scary under 120 minutes",
        ).preferences

        assertEquals(
            RecommendationContentType.MOVIE,
            preferences.contentType?.value,
        )
        assertEquals(120, preferences.runtimeMaximumMinutes?.value)
        assertEquals(
            ConstraintStrength.HARD,
            preferences.runtimeMaximumMinutes?.strength,
        )
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

    @Test
    fun twoHundredFiftyHardConstraintPhrasesParseWithoutDroppingARequirement() {
        val ratingForms = listOf(
            "IMDb 7+",
            "7+ IMDb",
            "above 7 on IMDb",
            "IMDb at least 7",
            "rating 7 on IMDb",
        )
        val yearForms = listOf(
            "after 2015",
            "since 2016",
            "from 2016",
            "made after 2015",
            "released after 2015",
        )
        val runtimeForms = listOf(
            "under 120 minutes",
            "less than 120 minutes",
            "at most 120 minutes",
            "maximum 120 minutes",
            "no longer than 120 minutes",
        )
        val genres = listOf("thriller" to "Thriller", "crime" to "Crime")
        var checked = 0

        ratingForms.forEach { rating ->
            yearForms.forEach { year ->
                runtimeForms.forEach { runtime ->
                    genres.forEach { (genreText, canonicalGenre) ->
                        val parsed = RecommendationPreferenceParser.parse(
                            "A movie, $genreText, $rating, $year, $runtime",
                        ).preferences
                        assertEquals(RecommendationContentType.MOVIE, parsed.contentType?.value)
                        assertEquals(7.0, parsed.minimumImdb?.value ?: 0.0, 0.001)
                        assertEquals(2016, parsed.yearMinimum?.value)
                        assertEquals(120, parsed.runtimeMaximumMinutes?.value)
                        assertTrue(
                            parsed.includedGenres.any { it.value == canonicalGenre },
                        )
                        checked += 1
                    }
                }
            }
        }
        assertEquals(250, checked)
    }

    @Test
    fun nuancedTasteIsPreservedAsOntologyFacetsAndUnmatchedSignals() {
        val preferences = RecommendationPreferenceParser.parse(
            "A psychological slow-burn neo-noir thriller with moral ambiguity, " +
                "beautiful shadows, restrained dialogue, no supernatural elements",
        ).preferences

        val facets = preferences.semanticFacets.map { it.value.id }.toSet()
        assertTrue("psychological" in facets)
        assertTrue("slow_burn" in facets)
        assertTrue("neo_noir" in facets)
        assertTrue("morality" in facets)
        assertTrue(preferences.unmatchedPreferences.isNotEmpty())
        assertTrue(
            preferences.unmatchedPreferences.any {
                "supernatural" in it.text || "restrained" in it.text
            },
        )
    }

    @Test
    fun explicitCorrectionReplacesEarlierSubjectiveTaste() {
        val initial = RecommendationPreferenceParser.parse(
            "slow-burn, bleak and psychological",
        ).preferences
        val corrected = RecommendationPreferenceParser.parse(
            "actually make it fast paced and hopeful",
            initial,
        ).preferences

        val facets = corrected.semanticFacets.map { it.value.id }.toSet()
        assertTrue("fast_paced" in facets)
        assertTrue("hopeful" in facets)
        assertTrue("slow_burn" !in facets)
        assertTrue("bleak" !in facets)
    }

    @Test
    fun freeTextCannotSilentlyChangeTheMandatorySelectedMediaType() {
        val selectedMovie = RecommendationPreferences(
            contentType = PreferenceSignal(
                RecommendationContentType.MOVIE,
                PreferenceOrigin.EXPLICIT,
                ConstraintStrength.HARD,
            ),
        )

        val parsed = RecommendationPreferenceParser.parse(
            "A show-like movie or series with a procedural rhythm",
            selectedMovie,
        ).preferences

        assertEquals(RecommendationContentType.MOVIE, parsed.contentType?.value)
        assertEquals(ConstraintStrength.HARD, parsed.contentType?.strength)
    }

    @Test
    fun parsesFinishedSeriesAndNotFamousWithoutReversingIntent() {
        val selectedSeries = RecommendationPreferences(
            contentType = PreferenceSignal(
                RecommendationContentType.TV,
                PreferenceOrigin.EXPLICIT,
                ConstraintStrength.HARD,
            ),
        )

        val parsed = RecommendationPreferenceParser.parse(
            "A finished crime series that is not famous",
            selectedSeries,
        ).preferences

        assertEquals("Ended", parsed.requiredStatus?.value)
        assertEquals(ConstraintStrength.HARD, parsed.requiredStatus?.strength)
        assertEquals(FamiliarityPreference.OBSCURE, parsed.familiarity?.value)
    }
}
