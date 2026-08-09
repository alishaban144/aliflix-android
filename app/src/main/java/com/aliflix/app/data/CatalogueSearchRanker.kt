package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dedicated ranker for normal Discover Catalogue searches.
 * Restores the earlier fuzzy title relevance behavior from commit d920657b64f7cb1375979f447664414b5690d4fb.
 */
object CatalogueSearchRanker {
    fun rank(query: String, items: List<Media>): List<Media> {
        if (items.size < 2 || query.isBlank()) return items.toList()

        val parsed = parseQuery(query)
        if (parsed.originalTitle.isBlank()) return items.toList()

        return items
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Media>> { indexed ->
                    score(parsed, indexed.value)
                }.thenBy { indexed ->
                    // Keep the provider's ordering whenever every meaningful signal ties.
                    indexed.index
                },
            )
            .map(IndexedValue<Media>::value)
    }

    fun rank(items: List<Media>, query: String): List<Media> = rank(query, items)

    private fun score(query: ParsedQuery, item: Media): Int {
        val normalizedTitle = normalize(item.title)
        val relevance = maxOf(
            titleRelevance(query.originalTitle, normalizedTitle),
            titleRelevance(query.title, normalizedTitle),
        )

        val yearScore = when {
            query.year == null -> 0
            mediaYear(item.year) == query.year -> YEAR_MATCH_BONUS
            mediaYear(item.year) == null -> 0
            else -> YEAR_MISMATCH_PENALTY
        }
        val typeScore = when {
            query.type == null -> 0
            item.type == query.type -> TYPE_MATCH_BONUS
            else -> TYPE_MISMATCH_PENALTY
        }
        val ratingScore = if (item.rating.isFinite()) {
            (item.rating.coerceIn(0.0, 10.0) * 10.0).roundToInt()
        } else {
            0
        }

        return relevance + yearScore + typeScore + ratingScore
    }

    private fun titleRelevance(query: String, title: String): Int {
        if (query.isBlank() || title.isBlank()) return 0
        if (query == title) return EXACT_SCORE

        val queryWithoutArticle = withoutLeadingArticle(query)
        val titleWithoutArticle = withoutLeadingArticle(title)
        if (
            queryWithoutArticle.isNotBlank() &&
            queryWithoutArticle == titleWithoutArticle
        ) {
            return ARTICLE_INSENSITIVE_EXACT_SCORE
        }

        if (startsWithPhrase(title, query)) return PREFIX_SCORE
        if (
            queryWithoutArticle.isNotBlank() &&
            startsWithPhrase(titleWithoutArticle, queryWithoutArticle)
        ) {
            return ARTICLE_INSENSITIVE_PREFIX_SCORE
        }

        val queryTokens = tokens(queryWithoutArticle.ifBlank { query })
        val titleTokens = tokens(titleWithoutArticle.ifBlank { title })
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0

        if (containsContiguous(titleTokens, queryTokens)) {
            return CONTIGUOUS_TOKEN_SCORE + compactnessBonus(queryTokens, titleTokens)
        }

        val exactTokenMatches = queryTokens.count { queryToken ->
            titleTokens.any(queryToken::equals)
        }
        if (exactTokenMatches == queryTokens.size) {
            return ALL_TOKENS_SCORE + compactnessBonus(queryTokens, titleTokens)
        }

        val prefixTokenMatches = queryTokens.count { queryToken ->
            titleTokens.any { titleToken ->
                titleToken.startsWith(queryToken) || queryToken.startsWith(titleToken)
            }
        }
        if (prefixTokenMatches == queryTokens.size) {
            return ALL_TOKEN_PREFIXES_SCORE + compactnessBonus(queryTokens, titleTokens)
        }

        val characterSimilarity = maxOf(
            editSimilarity(query, title),
            editSimilarity(queryWithoutArticle, titleWithoutArticle),
            ngramDice(queryWithoutArticle, titleWithoutArticle),
        )
        val tokenSimilarity = queryTokens
            .map { queryToken ->
                titleTokens.maxOfOrNull { titleToken ->
                    editSimilarity(queryToken, titleToken)
                } ?: 0.0
            }
            .average()
        val exactCoverage = exactTokenMatches.toDouble() / queryTokens.size
        val prefixCoverage = prefixTokenMatches.toDouble() / queryTokens.size
        val fuzzySimilarity = maxOf(
            characterSimilarity,
            tokenSimilarity * 0.82 + exactCoverage * 0.18,
            tokenSimilarity * 0.76 + prefixCoverage * 0.24,
        )

        return (fuzzySimilarity.coerceIn(0.0, 1.0) * FUZZY_SCORE_CEILING)
            .roundToInt()
    }

    private fun parseQuery(rawQuery: String): ParsedQuery {
        val original = normalize(rawQuery)
        val remaining = tokens(original).toMutableList()
        var requestedYear: Int? = null
        var requestedType: MediaType? = null

        var consumedQualifier: Boolean
        do {
            consumedQualifier = false
            if (remaining.size > 2 && requestedType == null) {
                val compoundType = COMPOUND_TYPE_QUALIFIERS[
                    remaining.takeLast(2).joinToString(" ")
                ]
                val meaningfulRemainder = remaining
                    .dropLast(2)
                    .any { it !in LEADING_ARTICLES }
                if (compoundType != null && meaningfulRemainder) {
                    requestedType = compoundType
                    repeat(2) { remaining.removeAt(remaining.lastIndex) }
                    consumedQualifier = true
                }
            }
            if (!consumedQualifier && remaining.size > 1) {
                val last = remaining.last()
                val meaningfulRemainder = remaining
                    .dropLast(1)
                    .any { it !in LEADING_ARTICLES }

                val possibleYear = last.toIntOrNull()
                if (
                    requestedYear == null &&
                    meaningfulRemainder &&
                    possibleYear != null &&
                    possibleYear in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR
                ) {
                    requestedYear = possibleYear
                    remaining.removeAt(remaining.lastIndex)
                    consumedQualifier = true
                } else {
                    val possibleType = TYPE_QUALIFIERS[last]
                    if (
                        meaningfulRemainder &&
                        possibleType != null
                    ) {
                        if (requestedType == null) requestedType = possibleType
                        remaining.removeAt(remaining.lastIndex)
                        consumedQualifier = true
                    }
                }
            }
        } while (consumedQualifier)

        return ParsedQuery(
            originalTitle = original,
            title = remaining.joinToString(" ").ifBlank { original },
            year = requestedYear,
            type = requestedType,
        )
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(
            value.replace("&", " and "),
            Normalizer.Form.NFD,
        )
        return decomposed
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(WHITESPACE, " ")
    }

    private fun withoutLeadingArticle(value: String): String {
        val valueTokens = tokens(value)
        return if (valueTokens.firstOrNull() in LEADING_ARTICLES) {
            valueTokens.drop(1).joinToString(" ")
        } else {
            value
        }
    }

    private fun tokens(value: String): List<String> =
        value.split(' ').filter(String::isNotBlank)

    private fun startsWithPhrase(value: String, prefix: String): Boolean =
        value == prefix || value.startsWith("$prefix ")

    private fun containsContiguous(
        titleTokens: List<String>,
        queryTokens: List<String>,
    ): Boolean {
        if (queryTokens.size > titleTokens.size) return false
        return (0..titleTokens.size - queryTokens.size).any { start ->
            queryTokens.indices.all { offset ->
                titleTokens[start + offset] == queryTokens[offset]
            }
        }
    }

    private fun compactnessBonus(
        queryTokens: List<String>,
        titleTokens: List<String>,
    ): Int {
        val ratio = queryTokens.size.toDouble() / titleTokens.size.coerceAtLeast(1)
        return (ratio * TOKEN_COMPACTNESS_BONUS).roundToInt()
    }

    private fun editSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        val longestLength = maxOf(left.length, right.length)
        return 1.0 - previous[right.length].toDouble() / longestLength
    }

    private fun ngramDice(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isBlank() || right.isBlank()) return 0.0

        val size = if (minOf(left.length, right.length) < 5) 2 else 3
        val leftNgrams = ngrams(left, size)
        val rightNgrams = ngrams(right, size)
        if (leftNgrams.isEmpty() || rightNgrams.isEmpty()) return 0.0

        val remaining = rightNgrams.groupingBy { it }
            .eachCount()
            .toMutableMap()
        var overlap = 0
        leftNgrams.forEach { ngram ->
            val count = remaining[ngram] ?: 0
            if (count > 0) {
                overlap += 1
                remaining[ngram] = count - 1
            }
        }
        return 2.0 * overlap / (leftNgrams.size + rightNgrams.size)
    }

    private fun ngrams(value: String, size: Int): List<String> {
        if (value.length < size) return listOf(value)
        return (0..value.length - size).map { index ->
            value.substring(index, index + size)
        }
    }

    private fun mediaYear(value: String): Int? =
        FOUR_DIGIT_YEAR.find(value)?.value?.toIntOrNull()

    private data class ParsedQuery(
        val originalTitle: String,
        val title: String,
        val year: Int?,
        val type: MediaType?,
    )

    private const val EXACT_SCORE = 120_000
    private const val ARTICLE_INSENSITIVE_EXACT_SCORE = 116_000
    private const val PREFIX_SCORE = 105_000
    private const val ARTICLE_INSENSITIVE_PREFIX_SCORE = 102_000
    private const val CONTIGUOUS_TOKEN_SCORE = 96_000
    private const val ALL_TOKENS_SCORE = 90_000
    private const val ALL_TOKEN_PREFIXES_SCORE = 84_000
    private const val FUZZY_SCORE_CEILING = 76_000
    private const val TOKEN_COMPACTNESS_BONUS = 3_000
    private const val YEAR_MATCH_BONUS = 4_000
    private const val YEAR_MISMATCH_PENALTY = -1_000
    private const val TYPE_MATCH_BONUS = 2_200
    private const val TYPE_MISMATCH_PENALTY = -500
    private const val MIN_RELEASE_YEAR = 1888
    private const val MAX_RELEASE_YEAR = 2100

    private val LEADING_ARTICLES = setOf("a", "an", "the")
    private val TYPE_QUALIFIERS = mapOf(
        "movie" to MediaType.MOVIE,
        "movies" to MediaType.MOVIE,
        "film" to MediaType.MOVIE,
        "films" to MediaType.MOVIE,
        "tv" to MediaType.TV,
        "show" to MediaType.TV,
        "shows" to MediaType.TV,
        "television" to MediaType.TV,
        "series" to MediaType.TV,
    )
    private val COMPOUND_TYPE_QUALIFIERS = mapOf(
        "tv show" to MediaType.TV,
        "tv series" to MediaType.TV,
        "television show" to MediaType.TV,
        "television series" to MediaType.TV,
    )
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
    private val FOUR_DIGIT_YEAR = Regex("\\b(?:18|19|20)\\d{2}\\b")
}
