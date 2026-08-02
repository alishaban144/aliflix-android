package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import kotlin.math.abs

/**
 * Ranks catalogue candidates by concrete similarity to the selected title.
 *
 * This intentionally does not use popularity or the user's taste profile:
 * "More Like This" should reflect the title itself, not the viewer.
 */
object RelatedContentEngine {
    fun rank(source: Media, candidates: List<Media>): List<Media> =
        rank(
            source = source,
            candidates = candidates,
            candidateScoreLimit = DEFAULT_CANDIDATE_SCORE_LIMIT,
            resultLimit = Int.MAX_VALUE,
        )

    /**
     * Keeps the comparatively expensive feature extraction and full ordering
     * bounded even when the in-memory catalogue has grown across many pages.
     * Catalogue order is deliberate: freshly retrieved, request-relevant
     * titles are inserted first by the catalogue repository.
     */
    internal fun rank(
        source: Media,
        candidates: List<Media>,
        candidateScoreLimit: Int,
        resultLimit: Int = Int.MAX_VALUE,
    ): List<Media> {
        if (candidateScoreLimit <= 0 || resultLimit <= 0) return emptyList()
        return candidates
            .asSequence()
            .filter { it.key != source.key && it.type == source.type }
            .distinctBy(Media::key)
            // This must stay before similarity(), sorting, and top-k. It is
            // the work bound for locally inferred title relationships.
            .take(candidateScoreLimit)
            .map { candidate -> candidate to similarity(source, candidate) }
            .sortedWith(
                compareByDescending<Pair<Media, Double>> { it.second }
                    .thenByDescending { it.first.rating }
                    .thenBy { it.first.title },
            )
            .take(resultLimit)
            .map { it.first }
            .toList()
    }

    private const val DEFAULT_CANDIDATE_SCORE_LIMIT = 180

    internal fun similarity(source: Media, candidate: Media): Double {
        if (source.type != candidate.type) return Double.NEGATIVE_INFINITY
        return signals(source, candidate).rawScore
    }

    internal fun signals(source: Media, candidate: Media): RelatedContentSignals {
        if (source.type != candidate.type) return RelatedContentSignals.incompatible()

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

        val sourceAnimation = sourceGenres.any { it == "animation" }
        val candidateAnimation = candidateGenres.any { it == "animation" }
        // Animation is evidence only when both titles share it. A mismatch is
        // not a global anti-animation rule and therefore carries no penalty.
        val formatScore = if (sourceAnimation && candidateAnimation) 10.0 else 0.0

        return RelatedContentSignals(
            sharedGenres = sharedGenres,
            sharedCast = sourceCast intersect candidateCast,
            sharedTitleTokens = sharedTitle,
            sharedStoryTokens = sharedStory,
            yearDistance = if (sourceYear != null && candidateYear != null) {
                abs(sourceYear - candidateYear)
            } else {
                null
            },
            sharesAnimationFormat = sourceAnimation && candidateAnimation,
            rawScore = genreScore + primaryGenreScore + castScore + franchiseScore +
                storyScore + eraScore + formatScore,
        )
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

internal data class RelatedContentSignals(
    val sharedGenres: Set<String>,
    val sharedCast: Set<String>,
    val sharedTitleTokens: Set<String>,
    val sharedStoryTokens: Set<String>,
    val yearDistance: Int?,
    val sharesAnimationFormat: Boolean,
    val rawScore: Double,
) {
    val normalizedScore: Double
        get() = (rawScore / 150.0).coerceIn(0.0, 1.0)

    companion object {
        fun incompatible() = RelatedContentSignals(
            sharedGenres = emptySet(),
            sharedCast = emptySet(),
            sharedTitleTokens = emptySet(),
            sharedStoryTokens = emptySet(),
            yearDistance = null,
            sharesAnimationFormat = false,
            rawScore = Double.NEGATIVE_INFINITY,
        )
    }
}
