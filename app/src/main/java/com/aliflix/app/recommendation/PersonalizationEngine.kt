package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import kotlin.math.abs
import kotlin.math.roundToInt

data class PersonalMatch(
    val score: Int,
)

/**
 * A deliberately small, on-device taste model.
 *
 * It never uses a global popularity or critic score. The percentage changes
 * only when the user's Likes change.
 */
object PersonalizationEngine {
    fun match(
        item: Media,
        likes: List<Media>,
    ): PersonalMatch? {
        val signals = likes.map { WeightedSignal(it, 1.0) }
        if (signals.isEmpty()) return null

        val totalWeight = signals.sumOf(WeightedSignal::weight).coerceAtLeast(1.0)
        val typeAffinity = signals
            .filter { it.media.type == item.type }
            .sumOf(WeightedSignal::weight) / totalWeight

        val candidateGenres = item.genres.map(::normalize).filter(String::isNotBlank).toSet()
        val genreAffinity = if (candidateGenres.isEmpty()) {
            0.0
        } else {
            signals.sumOf { signal ->
                val overlap = signal.media.genres
                    .map(::normalize)
                    .count(candidateGenres::contains)
                val ratio = overlap.toDouble() / candidateGenres.size
                signal.weight * ratio
            } / totalWeight
        }

        val candidateYear = item.year.take(4).toIntOrNull()
        val eraAffinity = if (candidateYear == null) {
            0.0
        } else {
            signals.sumOf { signal ->
                val signalYear = signal.media.year.take(4).toIntOrNull()
                val closeness = signalYear?.let {
                    (1.0 - abs(candidateYear - it) / 25.0).coerceIn(0.0, 1.0)
                } ?: 0.0
                signal.weight * closeness
            } / totalWeight
        }

        val candidateTokens = titleTokens(item.title)
        val titleAffinity = if (candidateTokens.isEmpty()) {
            0.0
        } else {
            signals.sumOf { signal ->
                val overlap = titleTokens(signal.media.title).count(candidateTokens::contains)
                signal.weight * (overlap.toDouble() / candidateTokens.size)
            } / totalWeight
        }

        val likedBoost = if (likes.any { it.key == item.key }) 7.0 else 0.0
        val score = (
            42.0 +
                typeAffinity * 22.0 +
                genreAffinity * 22.0 +
                eraAffinity * 8.0 +
                titleAffinity * 6.0 +
                likedBoost
            ).roundToInt().coerceIn(52, 98)

        return PersonalMatch(score = score)
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun titleTokens(value: String): Set<String> =
        normalize(value)
            .split(' ')
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

    private data class WeightedSignal(
        val media: Media,
        val weight: Double,
    )

    private val stopWords = setOf("the", "and", "for", "with", "from", "part", "season")
}
