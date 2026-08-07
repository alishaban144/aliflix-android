package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

class RottenTomatoesClient(
    private val pageLoader: suspend (String) -> String
) {
    suspend fun loadRating(item: Media): RottenTomatoesSnapshot {
        val slug = rottenTomatoesSlug(item.title)
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val year = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        
        val directPaths = buildList {
            add("$expectedPrefix$slug")
            year?.let { add("$expectedPrefix${slug}_$it") }
        }.distinct().take(2)
        
        val result = withTimeoutOrNull(3500) {
            val directRating = racePaths(directPaths, item)
            if (directRating != null) {
                return@withTimeoutOrNull RottenTomatoesSnapshot(directRating, RatingSourceState.VERIFIED)
            }
            
            val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
            val searchHtml = suspendOrNull { pageLoader("$ROTTEN_TOMATOES_URL/search?search=$query") } ?: ""
            val candidatePaths = parseCandidatePaths(searchHtml, item)
                .filterNot(directPaths::contains)
                .take(3)
                
            val fallbackRating = racePaths(candidatePaths, item)
            if (fallbackRating != null) {
                return@withTimeoutOrNull RottenTomatoesSnapshot(fallbackRating, RatingSourceState.VERIFIED)
            }
            
            RottenTomatoesSnapshot(null, RatingSourceState.NOT_RATED)
        }
        return result ?: RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
    }

    private suspend fun racePaths(paths: List<String>, item: Media): Int? {
        if (paths.isEmpty()) return null
        return coroutineScope {
            val deferreds = paths.map { path ->
                async {
                    val html = suspendOrNull { pageLoader("$ROTTEN_TOMATOES_URL$path") }
                    if (html != null && isIdentityVerified(html, item)) {
                        parseRating(html)
                    } else null
                }
            }
            
            var successfulResult: Int? = null
            val activeDeferreds = deferreds.toMutableList()
            while (activeDeferreds.isNotEmpty() && successfulResult == null) {
                val result = select<Int?> {
                    activeDeferreds.forEach { deferred ->
                        deferred.onAwait { res ->
                            activeDeferreds.remove(deferred)
                            res
                        }
                    }
                }
                if (result != null) {
                    successfulResult = result
                    activeDeferreds.forEach { it.cancel() }
                }
            }
            successfulResult
        }
    }

    private fun isIdentityVerified(html: String, item: Media): Boolean {
        if (html.isBlank()) return false
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val doc = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        val canonical = doc.select("link[rel=canonical]").attr("href")
        if (canonical.isNotBlank() && !canonical.contains(expectedPrefix)) return false
        val titleElement = doc.selectFirst("title")?.text() ?: ""
        val normalizedDocTitle = normalizeText(titleElement)
        val normalizedItemTitle = normalizeText(item.title)
        if (normalizedDocTitle.isNotBlank() && normalizedItemTitle.isNotBlank() && !normalizedDocTitle.contains(normalizedItemTitle)) {
            return false
        }
        return true
    }

    private fun parseRating(html: String): Int? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        
        document.select("script[type=application/ld+json]").forEach { script ->
            val data = script.data()
            if (data.isNotBlank()) {
                val rating = runCatching {
                    val json = JSONObject(data)
                    val aggregate = json.optJSONObject("aggregateRating")
                    val valFromAggregate = aggregate?.optInt("ratingValue", -1)?.takeIf { it in 0..100 }
                    val valDirect = json.optInt("tomatometerScore", -1).takeIf { it in 0..100 }
                    valFromAggregate ?: valDirect
                }.getOrNull()
                if (rating != null && rating in 0..100) return rating
            }
        }

        val scoreBoard = document.selectFirst("score-board")
        val attributeScore = sequenceOf(
            scoreBoard?.attr("tomatometerscore"),
            scoreBoard?.attr("tomatometerScore"),
            document.selectFirst("media-scorecard rt-text[slot=criticsScore], rt-text[slot=criticsScore]")?.text(),
            document.selectFirst("[data-qa=tomatometer], [data-qa=score-panel-critics-score], [data-qa=critics-score]")?.text(),
        ).filterNotNull().mapNotNull { scoreText.find(it)?.value?.toIntOrNull() }.firstOrNull()
        if (attributeScore != null && attributeScore in 0..100) return attributeScore
        
        val visibleScore = visibleTomatometerPattern.find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (visibleScore != null && visibleScore in 0..100) return visibleScore
        
        return rottenTomatoesPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }
        }
    }

    private fun parseCandidatePaths(html: String, item: Media): List<String> {
        if (html.isBlank()) return emptyList()
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val wantedTitle = normalizeText(item.title)
        val wantedSlug = rottenTomatoesSlug(item.title)
        val wantedTokens = wantedTitle.split(' ').filter(String::isNotBlank).toSet()
        val wantedYear = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val scores = linkedMapOf<String, Int>()

        fun addCandidate(rawPath: String, context: String) {
            val path = rawPath.substringBefore('?').substringBefore('#').trimEnd('/')
            if (!path.startsWith(expectedPrefix)) return
            val slug = path.substringAfter(expectedPrefix).substringBefore('/')
            if (slug.isBlank()) return
            val normalizedSlug = normalizeText(slug.replace('_', ' '))
            val slugTokens = normalizedSlug.split(' ').filter(String::isNotBlank).toSet()
            val normalizedContext = normalizeText(context)
            var score = 0
            if (slug == wantedSlug) score += 180
            if (normalizedSlug == wantedTitle) score += 160
            if (normalizedSlug.contains(wantedTitle) || wantedTitle.contains(normalizedSlug)) score += 70
            score += (slugTokens intersect wantedTokens).size * 18
            if (normalizedContext.contains(wantedTitle)) score += 110
            if (wantedYear != null && (wantedYear in path || wantedYear in context)) score += 24
            scores[path] = maxOf(scores[path] ?: Int.MIN_VALUE, score)
        }

        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        document.select("a[href^=\"$expectedPrefix\"]").forEach { link ->
            addCandidate(link.attr("href"), link.parent()?.text().orEmpty() + " " + link.text())
        }
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        rottenTomatoesPathPattern.findAll(unescaped).forEach { match ->
            addCandidate(match.value, "")
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun rottenTomatoesSlug(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("['’]"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun normalizeText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private suspend fun <T> suspendOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val ROTTEN_TOMATOES_URL = "https://www.rottentomatoes.com"
        val scoreText = Regex("\\d{1,3}")
        val visibleTomatometerPattern = Regex("""(\d{1,3})%\s*(?:Avg\.\s*)?Tomatometer""", RegexOption.IGNORE_CASE)
        val rottenTomatoesPathPattern = Regex("""/(?:m|tv)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""")
        val rottenTomatoesPatterns = listOf(
            Regex(""""tomatometerScore"\s*:\s*"?([0-9]+)"""),
            Regex("tomatometerscore=\"([0-9]+)\""),
            Regex("tomatometerScore=\"([0-9]+)\""),
        )
    }
}
