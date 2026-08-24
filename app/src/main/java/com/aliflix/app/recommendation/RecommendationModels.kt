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
    val requiredStatus: String? = null,
    val countries: List<String> = emptyList(),
    val discoveryText: String = "",
)

/** UI-ready Worker result. TMDB-verified metadata lives on [media]. */
data class RecommendationCandidate(
    val media: Media,
)
