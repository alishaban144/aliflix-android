package com.aliflix.app.data

import android.util.Log
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlin.system.measureTimeMillis

data class RtHttpResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val body: String,
    val elapsedMs: Long,
)

enum class FailureReason {
    NETWORK, TIMEOUT, HTTP_403, HTTP_429, HTTP_OTHER, BLOCKED_PAGE,
    IDENTITY_MISMATCH, EMPTY_RESPONSE, PARSER_FAILURE, SEARCH_FAILED,
}

sealed interface RottenTomatoesFetchResult {
    data class Verified(val rating: Int, val canonicalUrl: String, val elapsedMs: Long) : RottenTomatoesFetchResult
    data object ConfirmedNotRated : RottenTomatoesFetchResult
    data class Unavailable(
        val reason: FailureReason,
        val statusCode: Int? = null,
        val finalUrl: String? = null,
    ) : RottenTomatoesFetchResult
}

data class RtFetchDiagnostic(
    val requestedUrl: String,
    val statusCode: Int?,
    val finalUrl: String?,
    val networkMs: Long,
    val responseBytes: Int,
    val contentType: String?,
    val pageTitle: String?,
    val canonicalUrl: String?,
    val firstTomatometerMatch: String?,
    val identityVerified: Boolean,
    val ratingParsed: Int?,
    val finalState: RatingSourceState,
    val totalMs: Long,
    val failureReason: FailureReason? = null,
)

fun interface RottenTomatoesTransport {
    suspend fun fetch(url: String): RtHttpResponse
}

internal class AndroidRottenTomatoesTransport : RottenTomatoesTransport {
    override suspend fun fetch(url: String): RtHttpResponse = runInterruptible(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var response: RtHttpResponse? = null
        val elapsed = measureTimeMillis {
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection!!.connectTimeout = 1_500
            connection!!.readTimeout = 2_000
            connection!!.instanceFollowRedirects = true
            connection!!.requestMethod = "GET"
            connection!!.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection!!.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection!!.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection!!.setRequestProperty("Cache-Control", "no-cache")
            try {
                val code = connection!!.responseCode
                val stream = if (code in 200..399) connection!!.inputStream else connection!!.errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                response = RtHttpResponse(url, connection!!.url.toString(), code, connection!!.contentType, body, 0)
            } finally {
                connection!!.disconnect()
            }
        }
        response!!.copy(elapsedMs = elapsed)
    }

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }
}

class RottenTomatoesClient internal constructor(
    private val transport: RottenTomatoesTransport,
    private val diagnosticSink: (RtFetchDiagnostic) -> Unit = { diagnostic ->
        if (BuildConfig.DEBUG) Log.d("AliflixRT", diagnostic.toString())
    },
) {
    constructor() : this(AndroidRottenTomatoesTransport())

    /** Compatibility constructor for HTML parser tests; production never uses the catalogue page loader. */
    constructor(pageLoader: suspend (String) -> String) : this(
        RottenTomatoesTransport { url ->
            val started = System.currentTimeMillis()
            RtHttpResponse(url, url, 200, "text/html", pageLoader(url), System.currentTimeMillis() - started)
        },
        {},
    )

    suspend fun loadRating(item: Media): RottenTomatoesSnapshot = when (val result = loadFetchResult(item)) {
        is RottenTomatoesFetchResult.Verified -> RottenTomatoesSnapshot(result.rating, RatingSourceState.VERIFIED)
        RottenTomatoesFetchResult.ConfirmedNotRated -> RottenTomatoesSnapshot(null, RatingSourceState.NOT_RATED)
        is RottenTomatoesFetchResult.Unavailable -> RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
    }

    suspend fun loadFetchResult(item: Media): RottenTomatoesFetchResult {
        val started = System.currentTimeMillis()
        return try {
            withTimeout(ABSOLUTE_TIMEOUT_MS) { loadWithinDeadline(item, started) }
        } catch (cancelled: TimeoutCancellationException) {
            RottenTomatoesFetchResult.Unavailable(FailureReason.TIMEOUT).also {
                emitFailure(item, started, FailureReason.TIMEOUT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            RottenTomatoesFetchResult.Unavailable(FailureReason.NETWORK).also {
                emitFailure(item, started, FailureReason.NETWORK)
            }
        }
    }

    private suspend fun loadWithinDeadline(item: Media, started: Long): RottenTomatoesFetchResult {
        val slug = rottenTomatoesSlug(item.title)
        val prefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val year = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val yearInt = year?.toIntOrNull()
        val directPaths = buildList {
            add("$prefix$slug")
            year?.let { add("$prefix${slug}_$it") }
            yearInt?.let {
                add("$prefix${slug}_${it - 1}")
                add("$prefix${slug}_${it + 1}")
            }
        }.distinct().take(4)

        val direct = raceDirect(directPaths, item, started)
        if (direct is RottenTomatoesFetchResult.Verified || direct == RottenTomatoesFetchResult.ConfirmedNotRated) return direct
        if (direct is RottenTomatoesFetchResult.Unavailable && direct.reason in setOf(
                FailureReason.HTTP_403, FailureReason.HTTP_429, FailureReason.BLOCKED_PAGE, FailureReason.NETWORK,
            )) return direct

        val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
        val searchResponse = fetchOrUnavailable("$ROTTEN_TOMATOES_URL/search?search=$query")
        if (searchResponse is FetchOutcome.Failure) return searchResponse.result.copyReason(FailureReason.SEARCH_FAILED)
        val response = (searchResponse as FetchOutcome.Success).response
        if (isBlockedPage(response.body)) return unavailable(response, FailureReason.BLOCKED_PAGE, item, started)
        val candidatePaths = parseCandidatePaths(response.body, item)
        val bestPath = candidatePaths.firstOrNull()
            ?: return unavailable(response, FailureReason.SEARCH_FAILED, item, started)
        
        val searchCandidatesToTry = candidatePaths.take(2)
        val searchDirect = raceDirect(searchCandidatesToTry, item, started)
        if (searchDirect is RottenTomatoesFetchResult.Verified || searchDirect == RottenTomatoesFetchResult.ConfirmedNotRated) {
            return searchDirect
        }
        return evaluatePage(fetchOrUnavailable("$ROTTEN_TOMATOES_URL$bestPath"), item, started)
    }

    private suspend fun raceDirect(paths: List<String>, item: Media, started: Long): RottenTomatoesFetchResult = coroutineScope {
        val jobs = paths.map { path -> async { evaluatePage(fetchOrUnavailable("$ROTTEN_TOMATOES_URL$path"), item, started) } }
        val remaining = jobs.toMutableList()
        var bestFailure: RottenTomatoesFetchResult.Unavailable? = null
        var confirmedNotRated = false
        while (remaining.isNotEmpty()) {
            val (job, result) = select {
                remaining.forEach { candidate -> candidate.onAwait { candidate to it } }
            }
            remaining.remove(job)
            when (result) {
                is RottenTomatoesFetchResult.Verified -> {
                    remaining.forEach { it.cancel() }
                    return@coroutineScope result
                }
                RottenTomatoesFetchResult.ConfirmedNotRated -> confirmedNotRated = true
                is RottenTomatoesFetchResult.Unavailable -> {
                    val currentFailure = bestFailure
                    if (currentFailure == null || failurePriority(result.reason) > failurePriority(currentFailure.reason)) bestFailure = result
                }
            }
        }
        if (confirmedNotRated) RottenTomatoesFetchResult.ConfirmedNotRated
        else bestFailure ?: RottenTomatoesFetchResult.Unavailable(FailureReason.EMPTY_RESPONSE)
    }

    private suspend fun fetchOrUnavailable(url: String): FetchOutcome = try {
        FetchOutcome.Success(transport.fetch(url))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: java.net.SocketTimeoutException) {
        FetchOutcome.Failure(RottenTomatoesFetchResult.Unavailable(FailureReason.TIMEOUT, finalUrl = url))
    } catch (_: Throwable) {
        FetchOutcome.Failure(RottenTomatoesFetchResult.Unavailable(FailureReason.NETWORK, finalUrl = url))
    }

    private fun evaluatePage(outcome: FetchOutcome, item: Media, started: Long): RottenTomatoesFetchResult {
        if (outcome is FetchOutcome.Failure) {
            emitFailure(item, started, outcome.result.reason, outcome.result.statusCode, outcome.result.finalUrl)
            return outcome.result
        }
        val response = (outcome as FetchOutcome.Success).response
        val reason = when {
            response.statusCode == 403 -> FailureReason.HTTP_403
            response.statusCode == 429 -> FailureReason.HTTP_429
            response.statusCode !in 200..299 -> FailureReason.HTTP_OTHER
            response.body.isBlank() -> FailureReason.EMPTY_RESPONSE
            isBlockedPage(response.body) -> FailureReason.BLOCKED_PAGE
            !isIdentityVerified(response.body, item) -> FailureReason.IDENTITY_MISMATCH
            else -> null
        }
        if (reason != null) return unavailable(response, reason, item, started)

        val rating = parseRating(response.body)
        val document = Jsoup.parse(response.body, response.finalUrl)
        if (rating == null && document.text().contains("Tomatometer", ignoreCase = true)) {
            return unavailable(response, FailureReason.PARSER_FAILURE, item, started)
        }
        val canonical = document.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty().ifBlank { response.finalUrl }
        val total = System.currentTimeMillis() - started
        val state = if (rating != null) RatingSourceState.VERIFIED else RatingSourceState.NOT_RATED
        emitDiagnostic(response, item, total, state, rating, null)
        return rating?.let { RottenTomatoesFetchResult.Verified(it, canonical, total) }
            ?: RottenTomatoesFetchResult.ConfirmedNotRated
    }

    private fun unavailable(response: RtHttpResponse, reason: FailureReason, item: Media, started: Long): RottenTomatoesFetchResult.Unavailable {
        emitDiagnostic(response, item, System.currentTimeMillis() - started, RatingSourceState.UNAVAILABLE, null, reason)
        return RottenTomatoesFetchResult.Unavailable(reason, response.statusCode, response.finalUrl)
    }

    private fun emitFailure(item: Media, started: Long, reason: FailureReason, status: Int? = null, url: String? = null) {
        diagnosticSink(RtFetchDiagnostic(url ?: item.title, status, url, 0, 0, null, null, null, null, false, null, RatingSourceState.UNAVAILABLE, System.currentTimeMillis() - started, reason))
    }

    private fun emitDiagnostic(response: RtHttpResponse, item: Media, total: Long, state: RatingSourceState, rating: Int?, reason: FailureReason?) {
        val doc = Jsoup.parse(response.body, response.finalUrl)
        diagnosticSink(
            RtFetchDiagnostic(
                response.requestedUrl, response.statusCode, response.finalUrl, response.elapsedMs,
                response.body.toByteArray(StandardCharsets.UTF_8).size, response.contentType,
                doc.title().takeIf(String::isNotBlank), doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf(String::isNotBlank),
                visibleTomatometerPattern.find(doc.text())?.value, isIdentityVerified(response.body, item), rating,
                state, total, reason,
            )
        )
    }

    internal fun isIdentityVerified(html: String, item: Media): Boolean {
        if (html.isBlank()) return false
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val doc = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
        if (canonical.isNotBlank() && !canonical.contains(expectedPrefix)) return false
        val rawTitle = doc.title()
            .substringBefore(" | Rotten Tomatoes")
            .substringBefore(" - Rotten Tomatoes")
            .substringBefore(" - Movie Reviews")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .trim()
        val pageTitle = normalizeText(rawTitle)
        val wanted = normalizeText(item.title)
        val titleMatches = pageTitle.isNotBlank() && wanted.isNotBlank() &&
            (pageTitle == wanted || pageTitle.startsWith("$wanted ") || wanted.startsWith("$pageTitle ") ||
             titleIdentityScore(wanted, pageTitle) >= 65)
        if (!titleMatches) return false
        val expectedYear = item.year.take(4).toIntOrNull()
        val pageYear = dateCreatedPattern.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: yearInTitlePattern.find(doc.title())?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (expectedYear != null && pageYear != null) {
            if (kotlin.math.abs(expectedYear - pageYear) > 2) return false
        }
        return true
    }

    internal fun parseRating(html: String): Int? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        document.select("script[type=application/ld+json]").forEach { script ->
            val rating = runCatching {
                val json = JSONObject(script.data())
                val agg = json.optJSONObject("aggregateRating")
                val ratingVal = agg?.opt("ratingValue")
                val aggScore = when (ratingVal) {
                    is Number -> ratingVal.toInt()
                    is String -> ratingVal.toIntOrNull()
                    else -> null
                }
                aggScore?.takeIf { it in 0..100 }
                    ?: json.optInt("tomatometerScore", -1).takeIf { it in 0..100 }
            }.getOrNull()
            if (rating != null) return rating
        }
        val board = document.selectFirst("score-board")
        sequenceOf(
            board?.attr("tomatometerscore"), board?.attr("tomatometerScore"),
            document.selectFirst("media-scorecard rt-text[slot=criticsScore], rt-text[slot=criticsScore]")?.text(),
            document.selectFirst("[data-qa=tomatometer], [data-qa=score-panel-critics-score], [data-qa=critics-score]")?.text(),
        ).filterNotNull().mapNotNull { scoreText.find(it)?.value?.toIntOrNull() }.firstOrNull { it in 0..100 }?.let { return it }
        visibleTomatometerPattern.find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let { return it }
        return rottenTomatoesPatterns.firstNotNullOfOrNull { pattern -> pattern.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 } }
    }

    internal fun parseCandidatePaths(html: String, item: Media): List<String> {
        if (html.isBlank()) return emptyList()
        val prefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val wantedTitle = normalizeText(item.title)
        val wantedSlug = rottenTomatoesSlug(item.title)
        val wantedTokens = wantedTitle.split(' ').filter(String::isNotBlank).toSet()
        val wantedYear = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val scores = linkedMapOf<String, Int>()
        fun add(raw: String, context: String) {
            val path = raw.substringBefore('?').substringBefore('#').trimEnd('/')
            if (!path.startsWith(prefix)) return
            val slug = path.substringAfter(prefix).substringBefore('/')
            if (slug.isBlank()) return
            val normalizedSlug = normalizeText(slug.replace('_', ' '))
            var score = 0
            if (slug == wantedSlug) score += 180
            if (normalizedSlug == wantedTitle) score += 160
            score += (normalizedSlug.split(' ').toSet() intersect wantedTokens).size * 18
            if (normalizeText(context).contains(wantedTitle)) score += 110
            if (wantedYear != null && (wantedYear in path || wantedYear in context)) score += 24
            scores[path] = maxOf(scores[path] ?: Int.MIN_VALUE, score)
        }
        val doc = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        doc.select("a[href^=\"$prefix\"]").forEach { add(it.attr("href"), it.parent()?.text().orEmpty() + " " + it.text()) }
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        rottenTomatoesPathPattern.findAll(unescaped).forEach { add(it.value, "") }
        return scores.entries.sortedByDescending(Map.Entry<String, Int>::value).map(Map.Entry<String, Int>::key)
    }

    private fun isBlockedPage(html: String): Boolean {
        val normalized = html.lowercase()
        return blockedMarkers.any(normalized::contains)
    }
    private fun rottenTomatoesSlug(value: String) =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("['’‘`]"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    private fun normalizeText(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun titleIdentityScore(wanted: String, candidate: String): Int {
        if (wanted == candidate) return 100
        if (wanted.isBlank() || candidate.isBlank()) return 0
        val wantedTokens = wanted.split(' ').filter(String::isNotBlank).toSet()
        val candidateTokens = candidate.split(' ').filter(String::isNotBlank).toSet()
        val union = wantedTokens union candidateTokens
        val overlap = if (union.isEmpty()) 0.0 else (wantedTokens intersect candidateTokens).size.toDouble() / union.size
        val containment = wanted.contains(candidate) || candidate.contains(wanted)
        return (overlap * 82.0).toInt() + if (containment) 16 else 0
    }
    private fun failurePriority(reason: FailureReason) = when (reason) { FailureReason.HTTP_403, FailureReason.HTTP_429, FailureReason.BLOCKED_PAGE -> 3; FailureReason.NETWORK, FailureReason.TIMEOUT -> 2; else -> 1 }
    private fun RottenTomatoesFetchResult.Unavailable.copyReason(reason: FailureReason) = copy(reason = reason)

    private sealed interface FetchOutcome {
        data class Success(val response: RtHttpResponse) : FetchOutcome
        data class Failure(val result: RottenTomatoesFetchResult.Unavailable) : FetchOutcome
    }

    companion object {
        const val ROTTEN_TOMATOES_URL = "https://www.rottentomatoes.com"
        const val ABSOLUTE_TIMEOUT_MS = 4_000L
        val scoreText = Regex("\\d{1,3}")
        val visibleTomatometerPattern = Regex("""(\d{1,3})%\s*(?:Avg\.\s*)?Tomatometer""", RegexOption.IGNORE_CASE)
        val rottenTomatoesPathPattern = Regex("""/(?:m|tv)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""")
        val dateCreatedPattern = Regex(""""dateCreated"\s*:\s*"(\d{4})[^"]*"""", RegexOption.IGNORE_CASE)
        val yearInTitlePattern = Regex("""\((\d{4})\)""")
        val rottenTomatoesPatterns = listOf(
            Regex("""tomatometerscore\s*=\s*["']?(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex(""""criticsScore"\s*:\s*"?(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex(""""criticsScore"\s*:\s*\{[^{}]*"score"\s*:\s*"(\d{1,3})"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex(""""scorePercent"\s*:\s*"(\d{1,3})%""", RegexOption.IGNORE_CASE),
        )
        private val blockedMarkers = listOf(
            "captcha", "access denied", "request blocked", "challenge page", "security challenge",
            "verify you are human", "enable javascript to continue", "are you a robot", "robot check",
            "too many requests",
        )
    }
}
