package com.aliflix.app.data

import com.aliflix.app.model.Media

/**
 * Ranks title candidates against a natural-language description.
 *
 * It deliberately works locally after candidate discovery so search remains
 * useful if one of the public suggestion endpoints is temporarily unavailable.
 */
object PlotSearchRanker {
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "film", "for",
        "from", "has", "he", "her", "his", "i", "in", "into", "is", "it", "movie",
        "of", "on", "or", "others", "series", "she", "show", "that", "the", "their",
        "them", "they", "this", "to", "tv", "was", "where", "who", "with",
    )

    private val concepts = listOf(
        setOf("dream", "dreams", "subconscious", "sleep", "mind"),
        setOf("space", "spaceship", "planet", "alien", "astronaut", "galaxy"),
        setOf("time", "timeline", "timetravel", "future", "past", "loop"),
        setOf("detective", "investigate", "investigation", "murder", "mystery", "crime"),
        setOf("robot", "android", "artificial", "intelligence", "ai", "machine"),
        setOf("zombie", "undead", "outbreak", "apocalypse"),
        setOf("magic", "wizard", "witch", "spell", "fantasy"),
        setOf("superhero", "hero", "powers", "vigilante"),
        setOf("war", "soldier", "army", "battle"),
        setOf("school", "student", "teacher", "teen"),
        setOf("love", "romance", "relationship", "couple"),
    )

    fun rank(description: String, candidates: List<Media>): List<Media> {
        val queryTokens = expandedTokens(description)
        if (queryTokens.isEmpty()) return candidates

        return candidates
            .distinctBy(Media::key)
            .map { item ->
                val titleTokens = tokens(item.title)
                val overviewTokens = expandedTokens(item.overview)
                val genreTokens = expandedTokens(item.genres.joinToString(" "))
                val castTokens = tokens(item.cast.joinToString(" "))
                val titleMatches = (queryTokens intersect titleTokens).size
                val overviewMatches = (queryTokens intersect overviewTokens).size
                val genreMatches = (queryTokens intersect genreTokens).size
                val castMatches = (queryTokens intersect castTokens).size
                val coverage = (queryTokens intersect (titleTokens + overviewTokens + genreTokens))
                    .size.toDouble() / queryTokens.size.coerceAtLeast(1)
                val score =
                    titleMatches * 12.0 +
                        overviewMatches * 7.0 +
                        genreMatches * 4.0 +
                        castMatches * 2.0 +
                        coverage * 25.0 +
                        item.rating.coerceAtLeast(0.0) * 0.2
                item to score
            }
            .sortedWith(
                compareByDescending<Pair<Media, Double>> { it.second }
                    .thenByDescending { it.first.rating },
            )
            .map(Pair<Media, Double>::first)
    }

    private fun expandedTokens(value: String): Set<String> {
        val base = tokens(value).toMutableSet()
        concepts.forEach { group ->
            if (base.any(group::contains)) base.addAll(group.map(::stem))
        }
        return base
    }

    private fun tokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .asSequence()
            .map(String::trim)
            .filter { it.length > 2 && it !in stopWords }
            .map(::stem)
            .filter { it.length > 2 }
            .toSet()

    private fun stem(value: String): String = when {
        value.endsWith("ies") && value.length > 4 -> value.dropLast(3) + "y"
        value.endsWith("ing") && value.length > 5 -> value.dropLast(3)
        value.endsWith("ed") && value.length > 4 -> value.dropLast(2)
        value.endsWith("s") && value.length > 3 -> value.dropLast(1)
        else -> value
    }
}
