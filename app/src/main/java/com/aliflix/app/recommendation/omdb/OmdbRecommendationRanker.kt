package com.aliflix.app.recommendation.omdb

import com.aliflix.app.data.omdb.OmdbTitleMetadata
import com.aliflix.app.model.Media

data class VerifiedRecommendationItem(
    val media: Media,
    val omdbMetadata: OmdbTitleMetadata,
    val evaluationResult: OmdbEvaluationResult,
    val matchExplanation: String,
    val rankScore: Double = 0.0,
)

object OmdbRecommendationRanker {

    fun rankAndSort(
        items: List<VerifiedRecommendationItem>,
        spec: OmdbRecommendationSpec,
    ): List<VerifiedRecommendationItem> {
        val scored = items.map { item ->
            val score = computeDefaultScore(item.omdbMetadata, item.evaluationResult)
            item.copy(rankScore = score)
        }

        return when (spec.sortMode) {
            OmdbRecommendationSort.BEST_MATCH -> scored.sortedByDescending { it.rankScore }
            OmdbRecommendationSort.IMDB_RATING -> scored.sortedWith(
                compareByDescending<VerifiedRecommendationItem> { it.omdbMetadata.imdbRating ?: -1.0 }
                    .thenByDescending { it.omdbMetadata.imdbVotes ?: 0 }
            )
            OmdbRecommendationSort.ROTTEN_TOMATOES -> scored.sortedWith(
                compareByDescending<VerifiedRecommendationItem> { it.omdbMetadata.rottenTomatoesRating ?: -1 }
                    .thenByDescending { it.omdbMetadata.imdbRating ?: -1.0 }
            )
            OmdbRecommendationSort.METASCORE -> scored.sortedWith(
                compareByDescending<VerifiedRecommendationItem> { it.omdbMetadata.metascore ?: -1 }
                    .thenByDescending { it.omdbMetadata.imdbRating ?: -1.0 }
            )
            OmdbRecommendationSort.NEWEST -> scored.sortedWith(
                compareByDescending<VerifiedRecommendationItem> { it.omdbMetadata.year ?: 0 }
                    .thenByDescending { it.omdbMetadata.imdbRating ?: -1.0 }
            )
            OmdbRecommendationSort.OLDEST -> scored.sortedWith(
                compareBy<VerifiedRecommendationItem> { it.omdbMetadata.year ?: 9999 }
                    .thenByDescending { it.omdbMetadata.imdbRating ?: -1.0 }
            )
            OmdbRecommendationSort.MOST_IMDB_VOTES -> scored.sortedWith(
                compareByDescending<VerifiedRecommendationItem> { it.omdbMetadata.imdbVotes ?: 0 }
                    .thenByDescending { it.omdbMetadata.imdbRating ?: -1.0 }
            )
        }
    }

    private fun computeDefaultScore(meta: OmdbTitleMetadata, eval: OmdbEvaluationResult): Double {
        var score = 100.0
        score += (eval.matchedConstraints.size * 10.0)
        score += ((meta.imdbRating ?: 5.0) * 15.0)
        meta.rottenTomatoesRating?.let { score += (it * 0.2) }
        meta.metascore?.let { score += (it * 0.1) }
        meta.imdbVotes?.let { score += (Math.log10(it.toDouble().coerceAtLeast(1.0)) * 5.0) }
        meta.year?.let { score += ((it - 1900) * 0.05) }
        return score
    }

    fun buildMatchExplanation(meta: OmdbTitleMetadata, eval: OmdbEvaluationResult): String {
        val parts = mutableListOf<String>()

        // 1. Genres
        val genresStr = meta.genres.take(3).joinToString(" · ")
        if (genresStr.isNotBlank()) parts.add(genresStr)

        // 2. People evidence if requested
        if (meta.director != null && meta.director.isNotBlank()) {
            parts.add(meta.director)
        } else if (meta.writers.isNotEmpty()) {
            parts.add(meta.writers.first())
        }

        // 3. IMDb rating
        meta.imdbRating?.let { parts.add("IMDb $it") }

        // 4. RT rating
        meta.rottenTomatoesRating?.let { parts.add("RT $it%") }

        // 5. Year
        meta.year?.let { parts.add("$it") }

        return if (parts.isNotEmpty()) parts.joinToString(" · ") else "OMDb verified match"
    }
}
