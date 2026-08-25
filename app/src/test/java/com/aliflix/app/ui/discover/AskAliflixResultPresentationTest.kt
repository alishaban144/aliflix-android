package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.AnimationFilter
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationSort
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
                "Runtime 40-65 min / TMDB 7.5+ / Korean / South Korea / Sort: Most Popular",
            summary,
        )
    }

    @Test
    fun genreChoicesAreTheCanonicalTmdbMovieAndTvTaxonomies() {
        assertEquals(
            listOf(
                28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
                99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
                27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
                878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
            ),
            askTmdbGenres(RecommendationMediaKind.MOVIE).map { it.id to it.name },
        )
        assertEquals(
            listOf(
                10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
                99 to "Documentary", 18 to "Drama", 10751 to "Family", 10762 to "Kids", 9648 to "Mystery",
                10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
                10767 to "Talk", 10768 to "War & Politics", 37 to "Western",
            ),
            askTmdbGenres(RecommendationMediaKind.SERIES).map { it.id to it.name },
        )
    }

    @Test
    fun animationSummaryKeepsAnimeSeparateFromTmdbGenreNames() {
        val summary = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.SERIES,
            includedGenres = listOf("Sci-Fi & Fantasy"),
            animationFilter = AnimationFilter.JAPANESE_ANIMATION,
            sortBy = RecommendationSort.HIGHEST_RATED,
        ).askFilterSummary()

        assertEquals("Japanese Animation / Sci-Fi & Fantasy / Sort: Highest Rated", summary)
    }

    @Test
    fun animationReplacesTheCanonicalAnimationChipInsideTheTmdbGenreChoices() {
        val choices = askGenreChoices(RecommendationMediaKind.SERIES)

        assertEquals(false, "Animation" in choices)
        assertEquals(
            listOf("Japanese Animation", "American Animation"),
            choices.filter { it.endsWith("Animation") },
        )
    }
}
