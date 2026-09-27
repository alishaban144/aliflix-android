package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A bounded content-affinity index, not a calibrated probability of enjoyment. */
data class PersonalMatch(val score: Int)

/**
 * Multi-interest, item-neighborhood content matching for the explicit Likes library.
 * Channels are normalized independently: missing credits are unknown, not negative feedback.
 * No population model, invented popularity floor, or IDF estimated from a user's likes.
 * See docs/personal-matching.md for the research, assumptions and calibration limits.
 */
object PersonalizationEngine {
    private data class Features(val channels: List<Set<String>>, val language: String)
    private data class Neighbor(val affinity: Double)
    private val channelWeights = listOf(.26, .32, .18, .10, .14)
    private val words = Regex("[^\\p{L}\\p{N}]+")
    private val accents = Regex("\\p{M}+")
    private val cache = object : LinkedHashMap<Media, Features>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Media, Features>?) = size > 600
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(accents, "").lowercase(Locale.ROOT).trim()

    private fun genres(media: Media): Set<String> = (media.genres + media.omdbGenres)
        .flatMap { value ->
            normalized(value).replace("science fiction", "sci-fi").replace("sci fi", "sci-fi")
                .replace("war & politics", "war").split(" & ", ",")
        }.map(String::trim).filter(String::isNotEmpty).toSet()

    @Synchronized private fun features(media: Media): Features = cache.getOrPut(media) {
        Features(
            channels = listOf(
                genres(media),
                media.keywords.filter { it.id > 0 }.map { it.id.toString() }.toSet(),
                media.creators.filter { it.tmdbId > 0 }.map { it.tmdbId.toString() }.toSet(),
                (media.castPeople.map { it.name } + media.cast).map(::normalized)
                    .filter(String::isNotEmpty).distinct().take(8).toSet(),
                normalized(media.overview.ifBlank { media.omdbFullPlot.orEmpty() }).split(words)
                    .filter { it.length >= 4 && it !in stopWords }.toSet(),
            ),
            language = media.originalLanguage.lowercase(Locale.ROOT),
        )
    }

    private fun compare(candidate: Features, anchor: Features): Neighbor? {
        var evidence = 0.0
        var similarity = 0.0
        candidate.channels.forEachIndexed { index, a ->
            val b = anchor.channels[index]
            // Text in different languages cannot be compared by lexical overlap.
            val comparableText = index != 4 || candidate.language.isEmpty() || anchor.language.isEmpty() ||
                candidate.language == anchor.language
            if (a.isNotEmpty() && b.isNotEmpty() && comparableText) {
                val weight = channelWeights[index]
                val overlap = a.count { it in b }.toDouble()
                val cosine = overlap / sqrt(a.size.toDouble() * b.size)
                similarity += weight * cosine
                evidence += weight
            }
        }
        if (evidence == 0.0) return null
        // A lone genre/actor match must not imply near certainty. Rich independent metadata
        // gradually releases this shrinkage; absent fields never enter the similarity denominator.
        val reliability = .45 + .55 * (evidence / .75).coerceAtMost(1.0)
        return Neighbor((similarity / evidence) * reliability)
    }

    fun match(item: Media, likes: List<Media>): PersonalMatch? {
        if (likes.any { it.key == item.key }) return PersonalMatch(98)
        if (likes.isEmpty()) return null
        val candidate = features(item)
        val neighbors = likes.distinctBy(Media::key).take(100)
            .mapNotNull { compare(candidate, features(it)) }
        if (neighbors.isEmpty()) return null
        val relevant = neighbors.filter { it.affinity > 0.0 }.sortedByDescending { it.affinity }.take(3)
        if (relevant.isEmpty()) return PersonalMatch(0)
        // Similarity-weighted nearest neighbors retain distinct tastes: adding an unrelated
        // favorite cannot dilute an existing match or alter inverse document frequencies.
        val totalWeight = relevant.sumOf { it.affinity * it.affinity }
        val affinity = relevant.sumOf { it.affinity * it.affinity * it.affinity } / totalWeight
        return PersonalMatch((100.0 * affinity).roundToInt().coerceIn(0, 99))
    }

    private val stopWords = setOf(
        "their", "there", "where", "about", "after", "before", "when", "with", "from", "that", "this",
        "they", "them", "into", "must", "will", "have", "been", "life", "young", "find", "finds", "becomes",
        "while", "story", "world", "through", "against", "which", "each", "over", "only", "also", "more", "than",
        "what", "some", "during", "between", "years", "year", "takes", "take", "make", "makes", "other",
        "himself", "herself", "together", "another", "first", "family", "friends", "newly", "based",
    )
}
