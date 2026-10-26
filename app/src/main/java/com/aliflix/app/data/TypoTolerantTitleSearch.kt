package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class TitleSearchResult(val items: List<Media>, val correctedTitle: String? = null)

/** Original retrieval first; at most three recovery requests, cached across both entry points. */
class TypoTolerantTitleSearch(
    private val knownTitles: () -> List<Media> = { emptyList() },
    private val retrieve: suspend (String) -> List<Media>,
) {
    private data class Cached(val at: Long, val result: TitleSearchResult)
    private val cache = LinkedHashMap<String, Cached>(64, .75f, true)
    private val rawCache = LinkedHashMap<String, Cached>(64, .75f, true)
    private val lock = Mutex()

    suspend fun search(query: String, initialResults: List<Media>? = null, cacheScope: String = ""): TitleSearchResult {
        val normalizedQuery = normalize(query)
        val key = "$cacheScope:$normalizedQuery"
        if (normalizedQuery.isBlank()) return TitleSearchResult(emptyList())
        fun inScope(item: Media) = when (cacheScope) {
            "Movies" -> item.type == MediaType.MOVIE
            "Series" -> item.type == MediaType.TV
            else -> true
        }
        return lock.withLock {
            cache[key]?.takeIf { System.currentTimeMillis() - it.at < 900_000 }?.let { return@withLock it.result }
            val original = (initialResults ?: retrieveCached(query.trim())).filter(::inScope).distinctBy(Media::key)
            if (original.any { normalize(it.title) == normalizedQuery || titleKey(it.title) == titleKey(query) ||
                titleKey(it.title).contains(titleKey(query)) } || normalizedQuery.length < 4 || normalizedQuery.length > 160) {
                return@withLock remember(key, TitleSearchResult(CatalogueSearchRanker.rank(query, original)))
            }
            val known = knownTitles().filter { inScope(it) && isCorrection(query, it.title) }.distinctBy { titleKey(it.title) }
            val nearestKnown = known.groupBy { correctionDistance(query, it.title) }.minByOrNull { it.key }?.value.orEmpty()
            val correctionQueries = if (nearestKnown.size == 1) listOf(nearestKnown.single().title) else emptyList()
            val recovered = mutableListOf<Media>()
            withTimeoutOrNull(4_500) {
                for (fallback in (correctionQueries + fallbackQueries(query)).distinct().take(3)) {
                    val batch = try { retrieveCached(fallback) } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { emptyList() }
                    recovered += batch.filter { inScope(it) && isCorrection(query, it.title) }
                    if (recovered.isNotEmpty()) break
                }
            }
            val candidates = (original.filter { isCorrection(query, it.title) } + recovered).distinctBy(Media::key)
            val nearest = candidates.groupBy { correctionDistance(query, it.title) }.minByOrNull { it.key }?.value.orEmpty()
            val uniqueTitle = nearest.map { titleKey(it.title) }.distinct().size == 1
            val accepted = if (uniqueTitle) nearest else emptyList()
            val ranked = CatalogueSearchRanker.rank(query, (if (accepted.isNotEmpty()) accepted else original).distinctBy(Media::key))
                .sortedBy { if (normalize(it.title) == normalizedQuery) 0 else if (it in accepted) 1 else 2 }
            remember(key, TitleSearchResult(ranked, nearest.firstOrNull()?.title?.takeIf { uniqueTitle && accepted.isNotEmpty() }))
        }
    }

    private suspend fun retrieveCached(query: String): List<Media> {
        val key = normalize(query)
        rawCache[key]?.takeIf { System.currentTimeMillis() - it.at < 900_000 }?.let { return it.result.items }
        val items = retrieve(query)
        if (rawCache.size >= 80) rawCache.remove(rawCache.keys.first())
        rawCache[key] = Cached(System.currentTimeMillis(), TitleSearchResult(items))
        return items
    }

    private fun remember(key: String, result: TitleSearchResult): TitleSearchResult {
        if (cache.size >= 80) cache.remove(cache.keys.first())
        cache[key] = Cached(System.currentTimeMillis(), result)
        return result
    }

    companion object {
        fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

        private fun titleKey(raw: String): String = normalize(raw).replace(Regex("^(the|an|a) "), "")

        fun isCorrection(query: String, title: String): Boolean {
            val a = titleKey(query); val b = titleKey(title)
            if (a == b) return true
            if (a == b + "s" || b == a + "s" || a == b + "es" || b == a + "es") return false
            if (minOf(a.length, b.length) < 4) return false
            val budget = if (maxOf(a.length, b.length) >= 7) 2 else 1
            if (minOf(kotlin.math.abs(a.length - b.length),
                kotlin.math.abs(normalize(query).length - normalize(title).length)) > budget) return false
            val edits = correctionDistance(query, title)
            return edits <= budget && edits.toDouble() / maxOf(a.length, b.length) <= .29
        }

        private fun correctionDistance(query: String, title: String): Int =
            minOf(distance(titleKey(query), titleKey(title)), distance(normalize(query), normalize(title)))

        fun fallbackQueries(query: String): List<String> {
            val words = titleKey(query).split(' ').filter { it.length > 1 }
            if (words.isEmpty()) return emptyList()
            val queries = mutableListOf<String>()
            val first = words.first()
            if (first.length <= 3 && words.size > 1) queries += words.drop(1).joinToString(" ")
            // A stable prefix retrieves a damaged final token; other intact tokens recover
            // substitutions/transpositions earlier in a multi-word title.
            if (words.last().length >= 5) queries += (words.dropLast(1) + words.last().dropLast(2)).joinToString(" ")
            if (words.size > 1) queries += words.dropLast(1).joinToString(" ")
            queries += words.first().take(maxOf(3, words.first().length / 2))
            if (words.size > 1) queries += words.drop(1).joinToString(" ")
            if (words.size == 1) queries += words.first().takeLast(maxOf(3, (words.first().length + 1) / 2))
            return queries.filter { it.length >= 3 && it != titleKey(query) }.distinct().take(3)
        }

        /** Full Damerau-Levenshtein permits insertion/deletion beside transposed letters. */
        fun distance(a: String, b: String): Int {
            val maximum = a.length + b.length
            val matrix = Array(a.length + 2) { IntArray(b.length + 2) }
            matrix[0][0] = maximum
            for (i in 0..a.length) { matrix[i + 1][0] = maximum; matrix[i + 1][1] = i }
            for (j in 0..b.length) { matrix[0][j + 1] = maximum; matrix[1][j + 1] = j }
            val seen = mutableMapOf<Char, Int>()
            for (i in 1..a.length) {
                var lastMatch = 0
                for (j in 1..b.length) {
                    val previousRow = seen[b[j - 1]] ?: 0
                    val previousColumn = lastMatch
                    val cost = if (a[i - 1] == b[j - 1]) { lastMatch = j; 0 } else 1
                    matrix[i + 1][j + 1] = minOf(matrix[i][j] + cost, matrix[i + 1][j] + 1,
                        matrix[i][j + 1] + 1, matrix[previousRow][previousColumn] +
                            (i - previousRow - 1) + 1 + (j - previousColumn - 1))
                }
                seen[a[i - 1]] = i
            }
            return matrix[a.length + 1][b.length + 1]
        }

        fun correctionLabel(query: String, items: List<Media>): String? = items.firstOrNull()?.title
            ?.takeIf { normalize(it) != normalize(query) && isCorrection(query, it) &&
                correctionDistance(query, it) > 0 && items.none { item -> titleKey(item.title).contains(titleKey(query)) } }
    }
}
