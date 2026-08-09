package com.aliflix.app.data

import com.aliflix.app.model.Media

/** Shared lexical relevance helpers used by recommendation discovery. */
object PlotSearchRanker {
    private val stopWords = setOf(
        "a", "about", "after", "all", "an", "and", "are", "as", "at", "be", "but",
        "by", "film", "for", "from", "goes", "has", "he", "her", "his", "i", "in",
        "into", "is", "it", "man", "movie", "of", "on", "or", "other", "others",
        "people", "person", "protagonist", "series", "she", "show", "story", "that",
        "the", "their", "them", "they", "this", "to", "tv", "was", "where", "who",
        "with", "woman",
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

    fun relevanceScore(description: String, item: Media): Double {
        val queryTokens = expandedTokens(description)
        if (queryTokens.isEmpty()) return 0.0
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
        return titleMatches * 12.0 +
            overviewMatches * 7.0 +
            genreMatches * 4.0 +
            castMatches * 2.0 +
            coverage * 25.0
    }

    fun textRelevanceScore(description: String, evidence: String): Double {
        val queryTokens = expandedTokens(description)
        if (queryTokens.isEmpty() || evidence.isBlank()) return 0.0
        val evidenceTokens = expandedTokens(evidence)
        val matches = (queryTokens intersect evidenceTokens).size
        val coverage = matches.toDouble() / queryTokens.size.coerceAtLeast(1)
        return matches * 6.0 + coverage * 30.0
    }

    /**
     * Measures the literal story details that distinguish otherwise similar plots.
     *
     * Unlike [textRelevanceScore], this deliberately does not expand concepts:
     * "dream" can establish a broad theme, while exact details such as "thief",
     * "steal", and "secret" decide which dream story is the better answer.
     */
    fun literalTextRelevanceScore(description: String, evidence: String): Double {
        if (evidence.isBlank()) return 0.0
        val query = tokenList(description)
        val candidate = tokenList(evidence)
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        val wanted = query.toSet()
        val available = candidate.toSet()
        val matched = wanted intersect available
        val coverage = matched.size.toDouble() / wanted.size.coerceAtLeast(1)
        val queryPairs = query.windowed(2).map { pair -> pair.joinToString(" ") }.toSet()
        val candidatePairs = candidate.windowed(2)
            .map { pair -> pair.joinToString(" ") }
            .toSet()
        val adjacentMatches = (queryPairs intersect candidatePairs).size
        val discriminativeWeight = matched.sumOf { token ->
            (token.length - 3).coerceAtLeast(1)
        }
        return matched.size * 10.0 +
            coverage * 45.0 +
            adjacentMatches * 14.0 +
            discriminativeWeight * 1.5
    }

    private fun expandedTokens(value: String): Set<String> {
        val base = tokens(value).toMutableSet()
        concepts.forEach { group ->
            if (base.any(group::contains)) base.addAll(group.map(::stem))
        }
        return base
    }

    private fun tokens(value: String): Set<String> = tokenList(value).toSet()

    private fun tokenList(value: String): List<String> =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .asSequence()
            .map(String::trim)
            .filter { it.length > 2 && it !in stopWords }
            .map(::stem)
            .filter { it.length > 2 }
            .toList()

    private fun stem(value: String): String = when {
        value.endsWith("ies") && value.length > 4 -> value.dropLast(3) + "y"
        value.endsWith("ing") && value.length > 5 -> value.dropLast(3)
        value.endsWith("ed") && value.length > 4 -> value.dropLast(2)
        value.endsWith("s") && value.length > 3 -> value.dropLast(1)
        else -> value
    }
}
