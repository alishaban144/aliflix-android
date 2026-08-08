package com.aliflix.app.recommendation.omdb

import com.aliflix.app.model.MediaType

enum class OmdbRecommendationSort {
    BEST_MATCH,
    IMDB_RATING,
    ROTTEN_TOMATOES,
    METASCORE,
    NEWEST,
    OLDEST,
    MOST_IMDB_VOTES
}

data class OmdbRecommendationAnchor(
    val title: String,
    val imdbId: String? = null,
    val mediaType: MediaType = MediaType.MOVIE,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
)

data class OmdbRecommendationSpec(
    val mediaType: MediaType = MediaType.MOVIE,

    val includedGenres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),

    val minimumYear: Int? = null,
    val maximumYear: Int? = null,

    val minimumRuntimeMinutes: Int? = null,
    val maximumRuntimeMinutes: Int? = null,

    val minimumImdbRating: Double? = null,
    val minimumImdbVotes: Int? = null,

    val minimumRottenTomatoesRating: Int? = null,
    val minimumMetascore: Int? = null,

    val contentRatings: Set<String> = emptySet(),

    val languages: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),

    val actors: Set<String> = emptySet(),
    val directors: Set<String> = emptySet(),
    val writers: Set<String> = emptySet(),

    val minimumSeasons: Int? = null,
    val maximumSeasons: Int? = null,

    val plotRequirements: List<String> = emptyList(),
    val discoveryConcepts: List<String> = emptyList(),

    val similarityAnchor: OmdbRecommendationAnchor? = null,

    val sortMode: OmdbRecommendationSort = OmdbRecommendationSort.BEST_MATCH
) {
    val isFilterOnly: Boolean
        get() = plotRequirements.isEmpty() && similarityAnchor == null

    val normalizedIncludedGenres: Set<String>
        get() = includedGenres.mapTo(mutableSetOf()) { OmdbGenre.normalizeName(it) }

    val normalizedExcludedGenres: Set<String>
        get() = excludedGenres.mapTo(mutableSetOf()) { OmdbGenre.normalizeName(it) }

    fun summaryLabel(): String {
        val parts = mutableListOf<String>()
        parts.add(if (mediaType == MediaType.MOVIE) "Movies" else "Series")
        normalizedIncludedGenres.forEach { parts.add(it) }
        minimumImdbRating?.let { parts.add("IMDb ${if (it % 1.0 == 0.0) it.toInt() else it}+") }
        minimumRottenTomatoesRating?.let { parts.add("RT ${it}%+") }
        minimumMetascore?.let { parts.add("Metascore ${it}+") }
        if (minimumYear != null && maximumYear == null) {
            parts.add("$minimumYear+")
        } else if (minimumYear != null && maximumYear != null) {
            parts.add("$minimumYear–$maximumYear")
        } else if (maximumYear != null) {
            parts.add("Before ${maximumYear + 1}")
        }
        if (minimumRuntimeMinutes != null || maximumRuntimeMinutes != null) {
            val minR = minimumRuntimeMinutes ?: 0
            val maxR = maximumRuntimeMinutes
            if (maxR != null) parts.add("$minR–$maxR min") else parts.add("$minR+ min")
        }
        similarityAnchor?.let { parts.add("Similar to ${it.title}") }
        return parts.joinToString(" · ")
    }
}
