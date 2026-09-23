package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PersonalMatch(val score: Int)

/** Content affinity, not a calibrated probability of enjoyment. No popularity/rating floor. */
object PersonalizationEngine {
    private data class Features(val values: Map<String, Double>, val rich: Boolean)
    private val cache = object : LinkedHashMap<Media, Features>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Media, Features>?) = size > 600
    }
    @Synchronized private fun features(media: Media): Features = cache.getOrPut(media) {
        val values = mutableMapOf<String, Double>()
        fun add(prefix: String, items: List<String>, weight: Double) {
            val tokens = items.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
            if (tokens.isNotEmpty()) tokens.forEach { values["$prefix:$it"] = weight / sqrt(tokens.size.toDouble()) }
        }
        add("genre", media.genres + media.omdbGenres, 1.0)
        add("keyword", media.keywords.map { it.id.toString() }, 1.7)
        add("creator", media.creators.map { it.tmdbId.toString() }, 1.3)
        add("cast", media.cast.take(6), .55)
        add("plot", media.overview.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 && it !in stopWords }, .8)
        Features(values, media.keywords.isNotEmpty() || media.creators.isNotEmpty() || media.overview.length > 80)
    }
    fun match(item: Media, likes: List<Media>): PersonalMatch? {
        if (likes.any { it.key == item.key }) return PersonalMatch(100)
        val anchors = likes.distinctBy(Media::key).take(100).map(::features).filter { it.values.isNotEmpty() }
        val candidate = features(item)
        if (anchors.isEmpty() || !candidate.rich || anchors.none { it.rich }) return null
        val frequency = mutableMapOf<String, Int>()
        anchors.forEach { f -> f.values.keys.forEach { frequency[it] = (frequency[it] ?: 0) + 1 } }
        fun weighted(f: Features) = f.values.mapValues { (key, value) ->
            value * (1.0 + ln((anchors.size + 1.0) / ((frequency[key] ?: 0) + 1.0)))
        }
        val a = weighted(candidate)
        val norm = sqrt(a.values.sumOf { it * it })
        if (norm == 0.0) return null
        val similarities = anchors.map { anchor ->
            val b = weighted(anchor)
            val denominator = norm * sqrt(b.values.sumOf { it * it })
            if (denominator == 0.0) 0.0 else a.entries.sumOf { (key, v) -> v * (b[key] ?: 0.0) } / denominator
        }.sortedDescending().take(3)
        val weights = listOf(1.0, .35, .15).take(similarities.size)
        val affinity = similarities.zip(weights).sumOf { (value, weight) -> value * weight } / weights.sum()
        return PersonalMatch((affinity * 100).roundToInt().coerceIn(0, 99))
    }
    private val stopWords = setOf("their", "there", "where", "about", "after", "before", "when", "with", "from", "that", "this", "they", "them", "into", "must", "will", "have", "been", "life", "young", "find", "finds", "becomes", "while", "story", "world", "through", "against", "which", "each", "over", "only", "also", "more", "than", "what")
}
