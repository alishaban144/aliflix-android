package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType

enum class RecommendationContentType {
    MOVIE,
    TV,
    EITHER;

    fun accepts(type: MediaType): Boolean = when (this) {
        MOVIE -> type == MediaType.MOVIE
        TV -> type == MediaType.TV
        EITHER -> true
    }
}

enum class RecommendationMediaKind {
    MOVIE,
    SERIES;

    val contentType: RecommendationContentType
        get() = when (this) {
            MOVIE -> RecommendationContentType.MOVIE
            SERIES -> RecommendationContentType.TV
        }

    val mediaType: MediaType
        get() = when (this) {
            MOVIE -> MediaType.MOVIE
            SERIES -> MediaType.TV
        }
}

enum class AnimationFilter(val label: String, val tmdbOriginCountry: String) {
    JAPANESE_ANIMATION("Japanese Animation", "JP"),
    AMERICAN_ANIMATION("American Animation", "US"),
}

enum class RecommendationSort(val label: String, val workerValue: String) {
    MOST_POPULAR("Most Popular", "most_popular"),
    HIGHEST_RATED("Highest Rated", "highest_rated"),
    MOST_VOTED("Most Voted", "most_voted"),
    NEWEST_FIRST("Newest First", "newest_first"),
    OLDEST_FIRST("Oldest First", "oldest_first"),
}

enum class GeminiRecommendationModel(
    val label: String,
    val workerValue: String,
    val supportingText: String,
) {
    GEMINI_3_5_FLASH(
        label = "Gemini 3.5 Flash",
        workerValue = "gemini-3.5-flash",
        supportingText = "Recommended",
    ),
    GEMINI_3_7_FLASH(
        label = "Gemini 3.7 Flash",
        workerValue = "gemini-3.7-flash",
        supportingText = "Newest",
    );

    companion object {
        fun fromWorkerValue(value: String?): GeminiRecommendationModel =
            entries.firstOrNull { it.workerValue == value } ?: GEMINI_3_5_FLASH
    }
}

/** The filter state shared by the Ask Aliflix editor and Worker request mapper. */
data class CatalogDiscoverySpec(
    val mediaKind: RecommendationMediaKind,
    val includedGenres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val runtimeMinimumMinutes: Int? = null,
    val runtimeMaximumMinutes: Int? = null,
    val yearMinimum: Int? = null,
    val yearMaximum: Int? = null,
    val minimumTmdb: Double? = null,
    val originalLanguage: String? = null,
    val animationFilter: AnimationFilter? = null,
    val requiredStatus: String? = null,
    val countries: List<String> = emptyList(),
    val sortBy: RecommendationSort = RecommendationSort.MOST_POPULAR,
    val discoveryText: String = "",
)

/** UI-ready Worker result. TMDB-verified metadata lives on [media]. */
data class RecommendationCandidate(
    val media: Media,
)
