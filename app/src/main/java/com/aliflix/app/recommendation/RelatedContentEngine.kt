package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import kotlin.math.abs

/**
 * Ranks catalogue candidates by concrete similarity to the selected title.
 *
 * This intentionally does not use popularity or the user's taste profile:
 * "More Like This" should describe the title itself, not the viewer.
 */
object RelatedContentEngine {
    fun rank(source: Media, candidates: List<Media>): List<Media> =
        candidates
            .asSequence()
            .filter { it.key != source.key && it.type == source.type }
            .distinctBy(Media::key)
            .sortedWith(
                compareByDescending<Media> { similarity(source, it) }
                    .thenByDescending(Media::rating)
                    .thenBy(Media::title),
            )
            .toList()

    internal fun similarity(source: Media, candidate: Media): Double {
        if (source.type != candidate.type) return Double.NEGATIVE_INFINITY

        val sourceGenres = source.genres.map(::normalize).filter(String::isNotBlank).toSet()
        val candidateGenres = candidate.genres.map(::normalize).filter(String::isNotBlank).toSet()
        val sharedGenres = sourceGenres intersect candidateGenres
        val genreUnion = sourceGenres union candidateGenres
        val genreScore = if (genreUnion.isEmpty()) {
            0.0
        } else {
            sharedGenres.size.toDouble() / genreUnion.size * 48.0
        }
        val primaryGenreScore = if (
            sourceGenres.firstOrNull() != null &&
            sourceGenres.firstOrNull() == candidateGenres.firstOrNull()
        ) {
            26.0
        } else {
            0.0
        }

        val sourceCast = source.cast.map(::normalize).filter(String::isNotBlank).toSet()
        val candidateCast = candidate.cast.map(::normalize).filter(String::isNotBlank).toSet()
        val castScore = (sourceCast intersect candidateCast).size.coerceAtMost(3) * 12.0

        val sourceTitle = meaningfulTokens(source.title)
        val candidateTitle = meaningfulTokens(candidate.title)
        val sharedTitle = sourceTitle intersect candidateTitle
        val franchiseScore = when {
            sharedTitle.size >= 2 -> 54.0
            sharedTitle.size == 1 -> 28.0
            else -> 0.0
        }

        val sourceStory = meaningfulTokens(source.overview)
        val candidateStory = meaningfulTokens(candidate.overview)
        val sharedStory = sourceStory intersect candidateStory
        val storyScore = if (sourceStory.isEmpty() || candidateStory.isEmpty()) {
            0.0
        } else {
            sharedStory.size.toDouble() /
                minOf(sourceStory.size, candidateStory.size).coerceAtLeast(1) * 24.0
        }

        val sourceYear = source.year.take(4).toIntOrNull()
        val candidateYear = candidate.year.take(4).toIntOrNull()
        val eraScore = if (sourceYear != null && candidateYear != null) {
            (12.0 - abs(sourceYear - candidateYear) * 0.6).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val sourceAnime = sourceGenres.any { it == "anime" || it == "animation" }
        val candidateAnime = candidateGenres.any { it == "anime" || it == "animation" }
        val formatScore = when {
            sourceAnime && candidateAnime -> 14.0
            sourceAnime != candidateAnime -> -18.0
            else -> 0.0
        }

        return genreScore + primaryGenreScore + castScore + franchiseScore +
            storyScore + eraScore + formatScore
    }

    private fun meaningfulTokens(value: String): Set<String> =
        normalize(value)
            .split(' ')
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "into", "that", "this", "their",
        "when", "after", "before", "while", "who", "his", "her", "its", "part",
        "season", "movie", "film", "series",
    )
}
