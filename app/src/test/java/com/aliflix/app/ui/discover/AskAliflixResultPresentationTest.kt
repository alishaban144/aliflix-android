package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.AnimationFilter
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationSort
import com.aliflix.app.recommendation.ProductionCompanyFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertNull
import com.aliflix.app.recommendation.RecommendationCandidate

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
            productionCompanies = listOf(ProductionCompanyFilter(41077, "A24")),
        ).askFilterSummary()

        assertEquals(
            "Crime, Drama / Avoid Comedy / Years 2015-2024 / " +
                "Runtime 40-65 min / TMDB 7.5+ / Produced by A24 / Korean / South Korea",
            summary,
        )
    }

    @Test
    fun resultCardsPresentCanonicalMetadataAndRoundedMatchConfidence() {
        val item = RecommendationCandidate(
            media = Media(
                id = 10,
                type = MediaType.MOVIE,
                title = "Past Lives",
                year = "2023",
                runtime = "106 min",
            ),
            matchLevel = "Exceptional",
            matchScore = 0.936,
        )

        assertEquals("2023 · 106 min · Movie", askResultMetadata(item.media))
        assertEquals("Exceptional match · 94%", askResultMatchLabel(item))
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

        assertEquals("Japanese Animation / Sci-Fi & Fantasy", summary)
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

    @Test
    fun sortChoicesIncludeStandardDiscoveryOptions() {
        val sorts = RecommendationSort.entries.map { it.workerValue }
        assertEquals(
            listOf("most_popular", "highest_rated", "most_voted", "newest_first", "oldest_first"),
            sorts,
        )
    }

    @Test
    fun exactYearAndRuntimeRangesAreValidatedBeforeSubmission() {
        val valid = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.MOVIE,
            yearMinimum = 1995,
            yearMaximum = 2024,
            runtimeMinimumMinutes = 80,
            runtimeMaximumMinutes = 145,
        )

        assertNull(valid.askRangeValidationMessage(currentYear = 2026))
        assertEquals(
            "The starting year must come before the ending year.",
            valid.copy(yearMinimum = 2025, yearMaximum = 2020).askRangeValidationMessage(currentYear = 2026),
        )
        assertEquals(
            "Minimum runtime must be shorter than maximum runtime.",
            valid.copy(runtimeMinimumMinutes = 180, runtimeMaximumMinutes = 90).askRangeValidationMessage(currentYear = 2026),
        )
    }

    @Test
    fun mySpaceVisibilityIsLocalReversibleAndUsesFullMediaKeys() {
        val movie = RecommendationCandidate(Media(id = 10, type = MediaType.MOVIE, title = "Movie"))
        val seriesWithSameTmdbNumber = RecommendationCandidate(Media(id = 10, type = MediaType.TV, title = "Series"))
        val items = listOf(movie, seriesWithSameTmdbNumber)

        assertEquals(
            listOf(seriesWithSameTmdbNumber),
            visibleAskAliflixItems(items, hideMySpaceTitles = true, mySpaceKeys = setOf(movie.media.key)),
        )
        assertEquals(
            items,
            visibleAskAliflixItems(items, hideMySpaceTitles = false, mySpaceKeys = setOf(movie.media.key)),
        )
    }

    @Test
    fun resultCountExplainsLocallyHiddenMatches() {
        val item = RecommendationCandidate(Media(id = 10, type = MediaType.MOVIE, title = "Movie"))
        val state = AskAliflixUiState.Results(
            requestSummary = "Movies",
            spec = CatalogDiscoverySpec(RecommendationMediaKind.MOVIE),
            items = listOf(item, item.copy(media = item.media.copy(id = 11))),
        )

        assertEquals("1 shown · 1 hidden", resultCountLabel(state, visibleCount = 1, hiddenCount = 1))
    }
}
