package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Deterministically ranks results returned by the catalogue provider.
 *
 * The provider remains responsible for finding candidates. This class makes the useful
 * candidate rise to the top, while keeping provider order as the final tie breaker.
 * Title relevance is intentionally worth much more than rating/popularity.
 */
object SearchRanker {
    /**
     * A compact, normalized representation of what the viewer asked for.
     *
     * [title] has only unambiguous trailing filters removed. For example,
     * "Dune tv 2021" becomes title="dune", year=2021, type=TV.
     * [providerTitle] keeps the viewer's original spelling and punctuation so
     * title words such as "I" and "V" are not sent to providers as numbers.
     */
    internal data class SearchIntent(
        val title: String,
        val providerTitle: String,
        val year: Int?,
        val type: MediaType?,
        internal val literalTitle: String,
        internal val removedQualifiers: Boolean,
    )

    /**
     * Useful to callers that need to decide whether a provider result is relevant enough
     * to display, rather than merely sorting every result they were given.
     */
    internal enum class SearchConfidence {
        NONE,
        WEAK,
        LIKELY,
        STRONG,
        EXACT,
    }

    fun rank(query: String, items: List<Media>): List<Media> {
        if (items.size < 2 || query.isBlank()) return items.toList()

        val intent = parseIntent(query)
        if (intent.title.isBlank()) return items.toList()

        return items
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Media>> { indexed ->
                    assess(intent, indexed.value).score
                }.thenBy { indexed ->
                    // Preserve the provider's relevance order when our signals truly tie.
                    indexed.index
                },
            )
            .map(IndexedValue<Media>::value)
    }

    fun rank(items: List<Media>, query: String): List<Media> = rank(query, items)

    internal fun parseIntent(rawQuery: String): SearchIntent {
        val literalTitle = normalize(rawQuery)
        val remaining = tokens(literalTitle).toMutableList()
        var requestedYear: Int? = null
        var requestedType: MediaType? = null
        var removedQualifier = false

        // Filters are consumed only at the end. That keeps title-like values such as
        // "Movie 43" and "2001 A Space Odyssey" intact.
        var consumed: Boolean
        do {
            consumed = false

            if (requestedType == null) {
                val compoundLength = COMPOUND_TYPE_QUALIFIERS.keys
                    .map { qualifier -> qualifier.split(' ').size }
                    .distinct()
                    .sortedDescending()
                    .firstOrNull { length ->
                        if (remaining.size <= length) {
                            false
                        } else {
                            val suffix = remaining.takeLast(length).joinToString(" ")
                            COMPOUND_TYPE_QUALIFIERS.containsKey(suffix) &&
                                hasMeaningfulTitle(remaining.dropLast(length))
                        }
                    }
                if (compoundLength != null) {
                    val suffix = remaining.takeLast(compoundLength).joinToString(" ")
                    requestedType = COMPOUND_TYPE_QUALIFIERS.getValue(suffix)
                    repeat(compoundLength) { remaining.removeAt(remaining.lastIndex) }
                    removedQualifier = true
                    consumed = true
                }
            }

            if (!consumed && remaining.size > 1 && hasMeaningfulTitle(remaining.dropLast(1))) {
                val last = remaining.last()
                val possibleYear = last.toIntOrNull()
                when {
                    requestedYear == null &&
                        possibleYear != null &&
                        possibleYear in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR -> {
                        requestedYear = possibleYear
                        remaining.removeAt(remaining.lastIndex)
                        removedQualifier = true
                        consumed = true
                    }

                    TYPE_QUALIFIERS[last] != null -> {
                        if (requestedType == null) {
                            requestedType = TYPE_QUALIFIERS.getValue(last)
                        }
                        remaining.removeAt(remaining.lastIndex)
                        removedQualifier = true
                        consumed = true
                    }
                }
            }
        } while (consumed)

        return SearchIntent(
            title = remaining.joinToString(" ").ifBlank { literalTitle },
            providerTitle = providerTitle(
                rawQuery = rawQuery,
                year = requestedYear,
                type = requestedType,
            ),
            year = requestedYear,
            type = requestedType,
            literalTitle = literalTitle,
            removedQualifiers = removedQualifier,
        )
    }

    internal fun confidence(query: String, item: Media): SearchConfidence =
        confidence(parseIntent(query), item)

    internal fun confidence(intent: SearchIntent, item: Media): SearchConfidence =
        assess(intent, item).confidence

    private fun assess(intent: SearchIntent, item: Media): Assessment {
        val normalizedTitle = normalize(item.title)
        val literalMatch = titleMatch(intent.literalTitle, normalizedTitle)
        val interpretedMatch = titleMatch(intent.title, normalizedTitle)
        val discountedInterpretedScore = interpretedMatch.score -
            if (intent.removedQualifiers) INTERPRETED_QUERY_COST else 0

        // A literal title such as "Blade Runner 2049" must not lose to a hypothetical
        // title "Blade Runner" released in 2049 merely because the suffix looks like a year.
        val useLiteralInterpretation = literalMatch.score >= discountedInterpretedScore
        val titleMatch = if (useLiteralInterpretation) {
            literalMatch
        } else {
            interpretedMatch.copy(score = discountedInterpretedScore)
        }

        val applyQualifiers = intent.removedQualifiers && !useLiteralInterpretation
        val itemYear = mediaYear(item.year)
        val yearMatch = if (applyQualifiers && intent.year != null && itemYear != null) {
            itemYear == intent.year
        } else {
            null
        }
        val typeMatch = if (applyQualifiers && intent.type != null) {
            item.type == intent.type
        } else {
            null
        }

        val yearScore = when (yearMatch) {
            true -> YEAR_MATCH_BONUS
            false -> YEAR_MISMATCH_PENALTY
            null -> 0
        }
        val typeScore = when (typeMatch) {
            true -> TYPE_MATCH_BONUS
            false -> TYPE_MISMATCH_PENALTY
            null -> 0
        }
        val ratingScore = if (item.rating.isFinite()) {
            (item.rating.coerceIn(0.0, 10.0) * RATING_MULTIPLIER).roundToInt()
        } else {
            0
        }

        var confidence = titleMatch.confidence
        if (yearMatch == false) confidence = confidence.downgraded()
        if (typeMatch == false) confidence = confidence.downgraded()

        return Assessment(
            score = titleMatch.score + yearScore + typeScore + ratingScore,
            confidence = confidence,
        )
    }

    private fun titleMatch(query: String, title: String): TitleMatch {
        if (query.isBlank() || title.isBlank()) return TitleMatch.NONE
        if (query == title) return TitleMatch(EXACT_SCORE, SearchConfidence.EXACT)

        val queryWithoutArticle = withoutLeadingArticle(query)
        val titleWithoutArticle = withoutLeadingArticle(title)
        if (
            queryWithoutArticle.isNotBlank() &&
            queryWithoutArticle == titleWithoutArticle
        ) {
            return TitleMatch(ARTICLE_INSENSITIVE_EXACT_SCORE, SearchConfidence.EXACT)
        }

        if (
            compact(query) == compact(title) ||
            (
                queryWithoutArticle.isNotBlank() &&
                    compact(queryWithoutArticle) == compact(titleWithoutArticle)
                )
        ) {
            return TitleMatch(PUNCTUATION_INSENSITIVE_EXACT_SCORE, SearchConfidence.EXACT)
        }

        if (startsWithPhrase(title, query)) {
            return TitleMatch(PHRASE_PREFIX_SCORE, SearchConfidence.STRONG)
        }
        if (
            queryWithoutArticle.isNotBlank() &&
            startsWithPhrase(titleWithoutArticle, queryWithoutArticle)
        ) {
            return TitleMatch(ARTICLE_INSENSITIVE_PREFIX_SCORE, SearchConfidence.STRONG)
        }

        val queryTokens = tokens(queryWithoutArticle.ifBlank { query })
        val titleTokens = tokens(titleWithoutArticle.ifBlank { title })
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return TitleMatch.NONE

        acronymMatch(queryTokens, tokens(title), titleTokens)?.let { return it }

        if (containsContiguous(titleTokens, queryTokens)) {
            return TitleMatch(
                CONTIGUOUS_TOKEN_SCORE + compactnessBonus(queryTokens, titleTokens),
                SearchConfidence.STRONG,
            )
        }

        if (sameTokenMultiset(queryTokens, titleTokens)) {
            return TitleMatch(REORDERED_EXACT_SCORE, SearchConfidence.STRONG)
        }

        if (containsOrdered(titleTokens, queryTokens)) {
            return TitleMatch(
                ORDERED_TOKEN_SCORE + compactnessBonus(queryTokens, titleTokens),
                SearchConfidence.STRONG,
            )
        }

        if (containsAllExactTokens(titleTokens, queryTokens)) {
            return TitleMatch(
                ALL_EXACT_TOKENS_SCORE + compactnessBonus(queryTokens, titleTokens),
                SearchConfidence.LIKELY,
            )
        }

        if (containsAllTokenPrefixes(titleTokens, queryTokens)) {
            return TitleMatch(
                ALL_TOKEN_PREFIXES_SCORE + compactnessBonus(queryTokens, titleTokens),
                SearchConfidence.STRONG,
            )
        }

        // Very short fuzzy queries generate noise. Exact and prefix cases have already
        // been handled above, so there is no benefit to guessing here.
        if (compact(queryWithoutArticle).length < MIN_FUZZY_QUERY_LENGTH) {
            return TitleMatch.NONE
        }

        val characterSimilarity = maxOf(
            editSimilarity(query, title),
            editSimilarity(queryWithoutArticle, titleWithoutArticle),
            editSimilarity(compact(queryWithoutArticle), compact(titleWithoutArticle)),
            ngramDice(queryWithoutArticle, titleWithoutArticle),
        )
        val tokenSimilarity = tokenAlignmentSimilarity(queryTokens, titleTokens)
        val fuzzySimilarity = maxOf(
            characterSimilarity,
            // Token matching is especially helpful for multi-word titles with one typo.
            tokenSimilarity * TOKEN_SIMILARITY_WEIGHT +
                characterSimilarity * CHARACTER_SIMILARITY_WEIGHT,
        ).coerceIn(0.0, 1.0)

        val confidence = when {
            fuzzySimilarity >= STRONG_FUZZY_THRESHOLD -> SearchConfidence.STRONG
            fuzzySimilarity >= LIKELY_FUZZY_THRESHOLD -> SearchConfidence.LIKELY
            fuzzySimilarity >= WEAK_FUZZY_THRESHOLD -> SearchConfidence.WEAK
            else -> SearchConfidence.NONE
        }
        if (confidence == SearchConfidence.NONE) return TitleMatch.NONE

        return TitleMatch(
            (fuzzySimilarity * FUZZY_SCORE_CEILING).roundToInt(),
            confidence,
        )
    }

    private fun acronymMatch(
        queryTokens: List<String>,
        fullTitleTokens: List<String>,
        titleTokensWithoutArticle: List<String>,
    ): TitleMatch? {
        val candidate = when {
            queryTokens.size == 1 -> queryTokens.single()
            queryTokens.all { token -> token.length == 1 } -> queryTokens.joinToString("")
            else -> return null
        }
        if (
            candidate.length !in MIN_ACRONYM_LENGTH..MAX_ACRONYM_LENGTH ||
            !candidate.all(Char::isLetterOrDigit)
        ) {
            return null
        }

        val initialisms = sequenceOf(fullTitleTokens, titleTokensWithoutArticle)
            .filter { it.size >= 2 }
            .map { titleTokens -> titleTokens.joinToString("") { it.first().toString() } }
            .distinct()
            .toList()

        return when {
            initialisms.any(candidate::equals) ->
                TitleMatch(ACRONYM_EXACT_SCORE, SearchConfidence.STRONG)

            initialisms.any { it.startsWith(candidate) } ->
                TitleMatch(ACRONYM_PREFIX_SCORE, SearchConfidence.LIKELY)

            else -> null
        }
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(
            value
                .replace("&", " and ")
                .replace(APOSTROPHES, ""),
            Normalizer.Form.NFD,
        )
        return decomposed
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(WHITESPACE, " ")
            .split(' ')
            .filter(String::isNotBlank)
            .joinToString(" ") { token -> ROMAN_NUMERALS[token] ?: token }
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

    private fun compact(value: String): String = value.replace(" ", "")

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

    private fun containsOrdered(
        titleTokens: List<String>,
        queryTokens: List<String>,
    ): Boolean {
        var queryIndex = 0
        titleTokens.forEach { titleToken ->
            if (queryIndex < queryTokens.size && titleToken == queryTokens[queryIndex]) {
                queryIndex += 1
            }
        }
        return queryIndex == queryTokens.size
    }

    private fun sameTokenMultiset(left: List<String>, right: List<String>): Boolean =
        left.size == right.size && tokenCounts(left) == tokenCounts(right)

    private fun containsAllExactTokens(
        titleTokens: List<String>,
        queryTokens: List<String>,
    ): Boolean {
        val available = tokenCounts(titleTokens).toMutableMap()
        return queryTokens.all { token ->
            val count = available[token] ?: 0
            if (count == 0) {
                false
            } else {
                available[token] = count - 1
                true
            }
        }
    }

    private fun containsAllTokenPrefixes(
        titleTokens: List<String>,
        queryTokens: List<String>,
    ): Boolean {
        val similarities = assignedTokenSimilarities(queryTokens, titleTokens) { query, title ->
            tokenPrefixSimilarity(query, title)
        }
        return similarities.size == queryTokens.size && similarities.all { it > 0.0 }
    }

    private fun tokenCounts(values: List<String>): Map<String, Int> =
        values.groupingBy { it }.eachCount()

    private fun compactnessBonus(
        queryTokens: List<String>,
        titleTokens: List<String>,
    ): Int {
        val ratio = queryTokens.size.toDouble() / titleTokens.size.coerceAtLeast(1)
        return (ratio * TOKEN_COMPACTNESS_BONUS).roundToInt()
    }

    private fun tokenAlignmentSimilarity(
        queryTokens: List<String>,
        titleTokens: List<String>,
    ): Double {
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0.0
        val similarities = assignedTokenSimilarities(queryTokens, titleTokens) { query, title ->
            val prefixSimilarity = tokenPrefixSimilarity(query, title)
            if (prefixSimilarity > 0.0) {
                PREFIX_TOKEN_BASE_SIMILARITY +
                    prefixSimilarity * PREFIX_TOKEN_LENGTH_WEIGHT
            } else {
                editSimilarity(query, title)
            }
        }
        return similarities.sum() / queryTokens.size
    }

    private fun tokenPrefixSimilarity(query: String, title: String): Double {
        if (query == title) return 1.0
        val shorterLength = minOf(query.length, title.length)
        val longerLength = maxOf(query.length, title.length)
        val ratio = shorterLength.toDouble() / longerLength.coerceAtLeast(1)
        return when {
            title.startsWith(query) &&
                query.length >= MIN_TOKEN_PREFIX_LENGTH &&
                ratio >= MIN_FORWARD_PREFIX_RATIO -> ratio

            query.startsWith(title) &&
                title.length >= MIN_REVERSE_PREFIX_LENGTH &&
                ratio >= MIN_REVERSE_PREFIX_RATIO -> ratio

            else -> 0.0
        }
    }

    private fun providerTitle(
        rawQuery: String,
        year: Int?,
        type: MediaType?,
    ): String {
        val original = rawQuery.trim()
        var value = original
        while (value.isNotBlank()) {
            val before = value
            if (year != null) {
                value = removeTrailingQualifier(value, year.toString()) ?: value
            }
            if (type != null) {
                val aliases = (TYPE_QUALIFIERS + COMPOUND_TYPE_QUALIFIERS)
                    .filterValues { it == type }
                    .keys
                    .sortedByDescending(String::length)
                value = aliases.firstNotNullOfOrNull { alias ->
                    removeTrailingQualifier(value, alias)
                } ?: value
            }
            if (value == before) break
        }
        return value.trim().ifBlank { original }
    }

    private fun removeTrailingQualifier(
        value: String,
        qualifier: String,
    ): String? {
        val qualifierPattern = qualifier
            .split(' ')
            .filter(String::isNotBlank)
            .joinToString("""[\s\p{Z}\p{P}\p{S}]+""") { Regex.escape(it) }
        val match = Regex(
            """^(.*?)[\s\p{Z}\p{P}\p{S}]+$qualifierPattern[\s\p{Z}\p{P}\p{S}]*$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(value) ?: return null
        return match.groupValues[1].trimEnd()
    }

    /**
     * Greedy maximum-pair assignment prevents one title token from satisfying duplicate
     * query tokens. Candidate ordering makes the result deterministic.
     */
    private fun assignedTokenSimilarities(
        queryTokens: List<String>,
        titleTokens: List<String>,
        similarity: (String, String) -> Double,
    ): List<Double> {
        data class Candidate(
            val queryIndex: Int,
            val titleIndex: Int,
            val similarity: Double,
        )

        val candidates = queryTokens.indices
            .flatMap { queryIndex ->
                titleTokens.indices.map { titleIndex ->
                    Candidate(
                        queryIndex = queryIndex,
                        titleIndex = titleIndex,
                        similarity = similarity(
                            queryTokens[queryIndex],
                            titleTokens[titleIndex],
                        ),
                    )
                }
            }
            .sortedWith(
                compareByDescending<Candidate>(Candidate::similarity)
                    .thenBy(Candidate::queryIndex)
                    .thenBy(Candidate::titleIndex),
            )

        val usedQueries = BooleanArray(queryTokens.size)
        val usedTitles = BooleanArray(titleTokens.size)
        val result = DoubleArray(queryTokens.size)
        candidates.forEach { candidate ->
            if (
                candidate.similarity > 0.0 &&
                !usedQueries[candidate.queryIndex] &&
                !usedTitles[candidate.titleIndex]
            ) {
                usedQueries[candidate.queryIndex] = true
                usedTitles[candidate.titleIndex] = true
                result[candidate.queryIndex] = candidate.similarity
            }
        }
        return result.toList()
    }

    /**
     * Optimal-string-alignment distance: Levenshtein edits plus adjacent transpositions.
     * The latter handles common queries such as "spdier man" without special cases.
     */
    private fun editSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0

        val distances = Array(left.length + 1) { leftIndex ->
            IntArray(right.length + 1) { rightIndex ->
                when {
                    leftIndex == 0 -> rightIndex
                    rightIndex == 0 -> leftIndex
                    else -> 0
                }
            }
        }
        for (leftIndex in 1..left.length) {
            for (rightIndex in 1..right.length) {
                val substitutionCost =
                    if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
                var distance = minOf(
                    distances[leftIndex - 1][rightIndex] + 1,
                    distances[leftIndex][rightIndex - 1] + 1,
                    distances[leftIndex - 1][rightIndex - 1] + substitutionCost,
                )
                if (
                    leftIndex > 1 &&
                    rightIndex > 1 &&
                    left[leftIndex - 1] == right[rightIndex - 2] &&
                    left[leftIndex - 2] == right[rightIndex - 1]
                ) {
                    distance = minOf(
                        distance,
                        distances[leftIndex - 2][rightIndex - 2] + 1,
                    )
                }
                distances[leftIndex][rightIndex] = distance
            }
        }

        val longestLength = maxOf(left.length, right.length)
        return 1.0 - distances[left.length][right.length].toDouble() / longestLength
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

    private fun hasMeaningfulTitle(values: List<String>): Boolean =
        values.any { it !in LEADING_ARTICLES }

    private fun SearchConfidence.downgraded(): SearchConfidence = when (this) {
        SearchConfidence.EXACT -> SearchConfidence.STRONG
        SearchConfidence.STRONG -> SearchConfidence.LIKELY
        SearchConfidence.LIKELY -> SearchConfidence.WEAK
        SearchConfidence.WEAK,
        SearchConfidence.NONE,
        -> SearchConfidence.NONE
    }

    private data class Assessment(
        val score: Int,
        val confidence: SearchConfidence,
    )

    private data class TitleMatch(
        val score: Int,
        val confidence: SearchConfidence,
    ) {
        companion object {
            val NONE = TitleMatch(0, SearchConfidence.NONE)
        }
    }

    private const val EXACT_SCORE = 150_000
    private const val ARTICLE_INSENSITIVE_EXACT_SCORE = 147_000
    private const val PUNCTUATION_INSENSITIVE_EXACT_SCORE = 144_000
    private const val PHRASE_PREFIX_SCORE = 135_000
    private const val ARTICLE_INSENSITIVE_PREFIX_SCORE = 132_000
    private const val ACRONYM_EXACT_SCORE = 128_000
    private const val ACRONYM_PREFIX_SCORE = 124_000
    private const val CONTIGUOUS_TOKEN_SCORE = 120_000
    private const val REORDERED_EXACT_SCORE = 116_000
    private const val ORDERED_TOKEN_SCORE = 112_000
    private const val ALL_EXACT_TOKENS_SCORE = 109_000
    private const val ALL_TOKEN_PREFIXES_SCORE = 104_000
    private const val FUZZY_SCORE_CEILING = 98_000
    private const val TOKEN_COMPACTNESS_BONUS = 3_000

    // Interpreting a suffix as metadata must never outrank a literal exact title.
    private const val INTERPRETED_QUERY_COST = 12_000
    private const val YEAR_MATCH_BONUS = 6_000
    private const val YEAR_MISMATCH_PENALTY = -2_500
    private const val TYPE_MATCH_BONUS = 3_500
    private const val TYPE_MISMATCH_PENALTY = -1_300

    private const val RATING_MULTIPLIER = 10.0
    private const val TOKEN_SIMILARITY_WEIGHT = 0.84
    private const val CHARACTER_SIMILARITY_WEIGHT = 0.16
    private const val PREFIX_TOKEN_BASE_SIMILARITY = 0.72
    private const val PREFIX_TOKEN_LENGTH_WEIGHT = 0.28
    private const val STRONG_FUZZY_THRESHOLD = 0.84
    private const val LIKELY_FUZZY_THRESHOLD = 0.68
    private const val WEAK_FUZZY_THRESHOLD = 0.52
    private const val MIN_FUZZY_QUERY_LENGTH = 3
    private const val MIN_TOKEN_PREFIX_LENGTH = 2
    private const val MIN_REVERSE_PREFIX_LENGTH = 4
    private const val MIN_FORWARD_PREFIX_RATIO = 0.34
    private const val MIN_REVERSE_PREFIX_RATIO = 0.55
    private const val MIN_ACRONYM_LENGTH = 2
    private const val MAX_ACRONYM_LENGTH = 10
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
        "miniseries" to MediaType.TV,
    )
    private val COMPOUND_TYPE_QUALIFIERS = mapOf(
        "tv show" to MediaType.TV,
        "tv series" to MediaType.TV,
        "television show" to MediaType.TV,
        "television series" to MediaType.TV,
        "mini series" to MediaType.TV,
        "feature film" to MediaType.MOVIE,
    )
    private val ROMAN_NUMERALS = mapOf(
        "i" to "1",
        "ii" to "2",
        "iii" to "3",
        "iv" to "4",
        "v" to "5",
        "vi" to "6",
        "vii" to "7",
        "viii" to "8",
        "ix" to "9",
        "x" to "10",
        "xi" to "11",
        "xii" to "12",
        "xiii" to "13",
        "xiv" to "14",
        "xv" to "15",
        "xvi" to "16",
        "xvii" to "17",
        "xviii" to "18",
        "xix" to "19",
        "xx" to "20",
    )
    private val APOSTROPHES = Regex("['’‘`]")
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
    private val FOUR_DIGIT_YEAR = Regex("\\b(?:18|19|20|21)\\d{2}\\b")
}
