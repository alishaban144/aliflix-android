package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.RecommendationMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AskAliflixResultPresentationTest {
    @Test
    fun similarResultsNameTheCanonicalAnchorInsteadOfMatches() {
        val editor = AskAliflixEditorState(
            mode = 1,
            mediaType = MediaType.TV,
            selectedAnchor = Media(
                id = 1396,
                type = MediaType.TV,
                title = "Breaking Bad",
            ),
        )

        assertEquals("Similar to \"Breaking Bad\"", editor.resultsHeading())
    }

    @Test
    fun filterSummaryIncludesEveryVisibleConstraint() {
        val summary = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.SERIES,
            includedGenres = listOf("Crime", "Drama"),
            excludedGenres = listOf("Comedy"),
            yearMinimum = 2015,
            yearMaximum = 2024,
            runtimeMinimumMinutes = 40,
            runtimeMaximumMinutes = 65,
            minimumTmdb = 7.5,
            originalLanguage = "ko",
            countries = listOf("KR"),
        ).askFilterSummary()

        assertEquals(
            "Crime, Drama / Avoid Comedy / Years 2015-2024 / " +
                "Runtime 40-65 min / TMDB 7.5+ / Korean / South Korea",
            summary,
        )
    }
}
