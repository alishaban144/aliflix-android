package com.aliflix.app.data

import android.util.Log
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
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
                // A byte order mark ahead of the document stops the HTML parser from recognising the
                // page at all, which would silently cost every title its rating.
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
                    .removePrefix(RottenTomatoesClient.BYTE_ORDER_MARK)
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
    /**
     * Discovery is disabled unless a real index is supplied, so a test that injects its own
     * transport can only ever exercise parsing. Production supplies the live index explicitly.
     */
    private val searchIndex: RottenTomatoesSearchIndex = RottenTomatoesSearchIndex.disabled(),
) {
    private val canonicalTvPaths = ConcurrentHashMap<String, String>()

    constructor() : this(AndroidRottenTomatoesTransport(), searchIndex = RottenTomatoesSearchIndex())

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

    suspend fun loadEpisodeRatings(
        series: Media,
        episodes: List<Episode>,
    ): Map<Int, RottenTomatoesSnapshot> {
        val requested = episodes
            .filter { series.type == MediaType.TV && it.seasonNumber >= 0 && it.number > 0 }
            .distinctBy(Episode::number)
        if (requested.isEmpty()) return emptyMap()
        val seriesPath = resolveTvSeriesPath(series)
            ?: return requested.associate { episode ->
                episode.number to RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
            }

        val results = linkedMapOf<Int, RottenTomatoesSnapshot>()
        for (batch in requested.chunked(MAX_EPISODE_CONCURRENCY)) {
            coroutineScope {
                batch.map { episode ->
                    async {
                        episode.number to loadEpisodeRating(seriesPath, series, episode)
                    }
                }.awaitAll()
            }.forEach { (number, snapshot) -> results[number] = snapshot }
        }
        return results
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
        val directPaths = directPathsFor(item)
        val direct = raceDirect(directPaths, item, started)
        if (direct is RottenTomatoesFetchResult.Verified || direct == RottenTomatoesFetchResult.ConfirmedNotRated) return direct
        if (direct is RottenTomatoesFetchResult.Unavailable && direct.reason in HARD_BLOCKED_REASONS) return direct

        // Guessed slugs miss titles whose vanity cannot be derived from the title, so fall back to the
        // index the website itself searches. The discovered path is still fetched and verified below,
        // and the entry's own title and year travel with it so a differently named record still matches.
        val indexHits = searchIndex.resolve(item)
            .filter { it.path !in directPaths }
            .filter { hit -> item.year.take(4).toIntOrNull()?.let { year -> hit.year == null || hit.year == year } ?: true }
        indexHits.take(SEARCH_INDEX_CANDIDATE_LIMIT)
            .map { it.path }
            .takeIf { it.isNotEmpty() }
            ?.let { candidates ->
                val fromIndex = raceDirect(
                    paths = candidates,
                    item = item,
                    started = started,
                    // The catalogue returned this record as the answer to this exact title, so the
                    // requested name is an accepted name for the page. Identity still rests on the
                    // year: the candidates above are already filtered to the requested year and the
                    // page must state that same year itself.
                    aliasesByPath = indexHits.associate { hit ->
                        hit.path to listOfNotNull(hit.title.takeIf(String::isNotBlank)) + item.title
                    },
                )
                if (fromIndex is RottenTomatoesFetchResult.Verified ||
                    fromIndex == RottenTomatoesFetchResult.ConfirmedNotRated
                ) return fromIndex
                if (fromIndex is RottenTomatoesFetchResult.Unavailable &&
                    fromIndex.reason in HARD_BLOCKED_REASONS
                ) return fromIndex
            }

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

    private fun directPathsFor(item: Media): List<String> {
        val slug = rottenTomatoesSlug(item.title)
        val prefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val year = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val yearInt = year?.toIntOrNull()
        return buildList {
            add("$prefix$slug")
            year?.let { add("$prefix${slug}_$it") }
            yearInt?.let {
                add("$prefix${slug}_${it - 1}")
                add("$prefix${slug}_${it + 1}")
            }
        }.distinct().take(4)
    }

    private suspend fun resolveTvSeriesPath(series: Media): String? {
        canonicalTvPaths[series.key]?.let { return it }
        val resolved = withTimeoutOrNull(SERIES_PATH_TIMEOUT_MS) {
            val slug = rottenTomatoesSlug(series.title)
            val year = series.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
            val yearInt = year?.toIntOrNull()
            val directPaths = buildList {
                add("/tv/$slug")
                year?.let { add("/tv/${slug}_$it") }
                yearInt?.let {
                    add("/tv/${slug}_${it - 1}")
                    add("/tv/${slug}_${it + 1}")
                }
            }.distinct()

            // Series whose vanity is not derivable from the title, such as Detective Conan which is
            // filed as `case_closed_1996`, only resolve through the index the website searches. The
            // entry's own title is carried along so the differently named page still verifies.
            val indexHits = searchIndex.resolve(series)
                .filter { it.path !in directPaths }
                .filter { hit -> series.year.take(4).toIntOrNull()?.let { year -> hit.year == null || hit.year == year } ?: true }
                .take(SEARCH_INDEX_CANDIDATE_LIMIT)
            val indexPaths = indexHits.map { it.path }
            val aliasesByPath = indexHits.associate { hit ->
                hit.path to listOfNotNull(hit.title.takeIf(String::isNotBlank)) + series.title
            }
            for (path in directPaths + indexPaths) {
                when (val outcome = fetchOrUnavailable("$ROTTEN_TOMATOES_URL$path")) {
                    is FetchOutcome.Failure -> if (outcome.result.reason in setOf(
                            FailureReason.HTTP_403,
                            FailureReason.HTTP_429,
                            FailureReason.BLOCKED_PAGE,
                        )
                    ) return@withTimeoutOrNull null
                    is FetchOutcome.Success -> {
                        verifiedSeriesPath(outcome.response, series, aliasesByPath[path].orEmpty())?.let {
                            return@withTimeoutOrNull it
                        }
                    }
                }
            }

            val query = URLEncoder.encode(series.title, StandardCharsets.UTF_8.toString())
            val search = fetchOrUnavailable("$ROTTEN_TOMATOES_URL/search?search=$query")
            if (search !is FetchOutcome.Success || isBlockedPage(search.response.body)) {
                return@withTimeoutOrNull null
            }
            for (path in parseCandidatePaths(search.response.body, series)
                .mapNotNull(::rootTvPath)
                .distinct()
                .take(2)
            ) {
                val outcome = fetchOrUnavailable("$ROTTEN_TOMATOES_URL$path")
                if (outcome is FetchOutcome.Success) {
                    verifiedSeriesPath(outcome.response, series)?.let {
                        return@withTimeoutOrNull it
                    }
                }
            }
            null
        }
        if (resolved != null) canonicalTvPaths[series.key] = resolved
        return resolved
    }

    private fun verifiedSeriesPath(
        response: RtHttpResponse,
        series: Media,
        aliases: List<String> = emptyList(),
    ): String? {
        if (response.statusCode !in 200..299 || response.body.isBlank() || isBlockedPage(response.body)) {
            return null
        }
        if (!isIdentityVerified(response.body, series, aliases)) return null
        val document = Jsoup.parse(response.body, response.finalUrl)
        val canonical = document.selectFirst("link[rel=canonical]")
            ?.absUrl("href")
            .orEmpty()
            .ifBlank { response.finalUrl }
        return rootTvPath(canonical)
    }

    private fun rootTvPath(raw: String): String? {
        val path = runCatching {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                URI(raw).path
            } else {
                raw.substringBefore('?').substringBefore('#')
            }
        }.getOrNull()?.trimEnd('/') ?: return null
        return path.takeIf { it.matches(Regex("^/tv/[A-Za-z0-9_.-]+$")) }
    }

    private suspend fun loadEpisodeRating(
        seriesPath: String,
        series: Media,
        episode: Episode,
    ): RottenTomatoesSnapshot {
        val season = episode.seasonNumber.toString().padStart(2, '0')
        val number = episode.number.toString().padStart(2, '0')
        val url = "$ROTTEN_TOMATOES_URL$seriesPath/s$season/e$number"
        val started = System.currentTimeMillis()
        return try {
            withTimeout(ABSOLUTE_TIMEOUT_MS) {
                evaluateEpisodePage(fetchOrUnavailable(url), series, episode, started)
            }
        } catch (cancelled: TimeoutCancellationException) {
            emitFailure(series, started, FailureReason.TIMEOUT, url = url)
            RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emitFailure(series, started, FailureReason.NETWORK, url = url)
            RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
        }
    }

    private fun evaluateEpisodePage(
        outcome: FetchOutcome,
        series: Media,
        episode: Episode,
        started: Long,
    ): RottenTomatoesSnapshot {
        if (outcome is FetchOutcome.Failure) {
            emitFailure(
                series,
                started,
                outcome.result.reason,
                outcome.result.statusCode,
                outcome.result.finalUrl,
            )
            return RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
        }
        val response = (outcome as FetchOutcome.Success).response
        val reason = when {
            response.statusCode == 403 -> FailureReason.HTTP_403
            response.statusCode == 429 -> FailureReason.HTTP_429
            response.statusCode !in 200..299 -> FailureReason.HTTP_OTHER
            response.body.isBlank() -> FailureReason.EMPTY_RESPONSE
            isBlockedPage(response.body) -> FailureReason.BLOCKED_PAGE
            !isEpisodeIdentityVerified(response.body, response.finalUrl, series, episode) ->
                FailureReason.IDENTITY_MISMATCH
            else -> null
        }
        if (reason != null) {
            emitDiagnostic(
                response,
                series,
                System.currentTimeMillis() - started,
                RatingSourceState.UNAVAILABLE,
                null,
                reason,
            )
            return RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE)
        }

        val rating = parseRating(response.body)
        val state = when {
            rating != null -> RatingSourceState.VERIFIED
            hasConfirmedNoCriticRating(response.body) -> RatingSourceState.NOT_RATED
            else -> RatingSourceState.UNAVAILABLE
        }
        val failure = if (state == RatingSourceState.UNAVAILABLE) FailureReason.PARSER_FAILURE else null
        emitDiagnostic(
            response,
            series,
            System.currentTimeMillis() - started,
            state,
            rating,
            failure,
        )
        return RottenTomatoesSnapshot(rating, state)
    }

    internal fun isEpisodeIdentityVerified(
        html: String,
        finalUrl: String,
        series: Media,
        episode: Episode,
    ): Boolean {
        if (html.isBlank()) return false
        val document = Jsoup.parse(html, finalUrl)
        val canonical = document.selectFirst("link[rel=canonical]")
            ?.absUrl("href")
            .orEmpty()
            .ifBlank { finalUrl }
        val canonicalPath = runCatching { URI(canonical).path }.getOrNull().orEmpty().trimEnd('/')
        val expectedSuffix = "/s${episode.seasonNumber.toString().padStart(2, '0')}" +
            "/e${episode.number.toString().padStart(2, '0')}"
        if (!canonicalPath.endsWith(expectedSuffix)) return false

        var jsonIdentityMatches = false
        document.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull()
                ?: return@forEach
            if (!json.optString("@type").equals("TVEpisode", ignoreCase = true)) {
                return@forEach
            }
            val returnedNumber = json.opt("episodeNumber")?.toString()?.toIntOrNull()
            val returnedSeries = json.optJSONObject("partOfSeries")
                ?.optString("name")
                .orEmpty()
            val returnedTitle = json.optString("name")
            val seriesMatches = titleIdentityScore(
                normalizeText(series.title),
                normalizeText(returnedSeries),
            ) >= 65
            val episodeMatches = episode.title.equals("Episode ${episode.number}", ignoreCase = true) ||
                titleIdentityScore(normalizeText(episode.title), normalizeText(returnedTitle)) >= 55
            if (returnedNumber == episode.number && seriesMatches && episodeMatches) {
                jsonIdentityMatches = true
            }
        }
        if (jsonIdentityMatches) return true

        val pageTitle = normalizeText(document.title())
        return pageTitle.contains(normalizeText(series.title)) &&
            (
                episode.title.equals("Episode ${episode.number}", ignoreCase = true) ||
                    pageTitle.contains(normalizeText(episode.title))
                )
    }

    internal fun hasConfirmedNoCriticRating(html: String): Boolean {
        if (html.isBlank()) return false
        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        reviewScoreBlocks(document).forEach { json ->
            sequenceOf("criticsScore", "criticsAll", "criticsTop").forEach { key ->
                val critics = json.optJSONObject(key) ?: return@forEach
                val score = critics.optIntValue("score")
                val ratingCount = critics.optIntValue("ratingCount")
                val reviewCount = critics.optIntValue("reviewCount")
                if (score == null && (ratingCount == 0 || reviewCount == 0)) return true
            }
        }
        return Regex("Tomatometer\\s+0\\s+Reviews?", RegexOption.IGNORE_CASE)
            .containsMatchIn(document.text())
    }

    /**
     * Every embedded reviews payload on the page.
     *
     * The critic score is published in a JSON island the site marks up as a plain script, so both
     * script types have to be read. Scanning only the JSON-typed script missed the block entirely
     * and reported pages that genuinely carry no critic score as a parser failure.
     */
    private fun reviewScoreBlocks(document: Document): List<JSONObject> =
        sequenceOf(
            document.select("script[type=application/json]"),
            document.select("script[type=text/javascript]"),
        ).flatten()
            .mapNotNull { script -> runCatching { JSONObject(script.data()) }.getOrNull() }
            .toList()

    private fun JSONObject.optIntValue(key: String): Int? =
        opt(key)?.let { (it as? Number)?.toInt() ?: it.toString().trim().toIntOrNull() }

    private suspend fun raceDirect(
        paths: List<String>,
        item: Media,
        started: Long,
        aliasesByPath: Map<String, List<String>> = emptyMap(),
    ): RottenTomatoesFetchResult = coroutineScope {
        val jobs = paths.map { path ->
            async {
                evaluatePage(fetchOrUnavailable("$ROTTEN_TOMATOES_URL$path"), item, started, aliasesByPath[path].orEmpty())
            }
        }
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

    private fun evaluatePage(
        outcome: FetchOutcome,
        item: Media,
        started: Long,
        aliases: List<String> = emptyList(),
    ): RottenTomatoesFetchResult {
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
            !isIdentityVerified(response.body, item, aliases) -> FailureReason.IDENTITY_MISMATCH
            else -> null
        }
        if (reason != null) return unavailable(response, reason, item, started)

        val rating = parseRating(response.body)
        val document = Jsoup.parse(response.body, response.finalUrl)
        // A page that positively states it has no critic reviews is answered as "not rated" rather
        // than as a failure. The identity is already confirmed here, so the honest answer is the one
        // the site itself publishes.
        val confirmedUnrated = rating == null && hasConfirmedNoCriticRating(response.body)
        if (rating == null && !confirmedUnrated && document.text().contains("Tomatometer", ignoreCase = true)) {
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

    internal fun isIdentityVerified(
        html: String,
        item: Media,
        aliases: List<String> = emptyList(),
    ): Boolean {
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
        // A catalogue entry may be filed under a different name than the one requested, such as the
        // 1996 anime Detective Conan which the site files as Case Closed. The alternate titles the
        // catalogue publishes for the entry are the only names allowed to stand in for the page title.
        val titleMatches = aliases.any { normalizeText(it) == wanted } ||
            (pageTitle.isNotBlank() && wanted.isNotBlank() &&
                (pageTitle == wanted || pageTitle.startsWith("$wanted ") || wanted.startsWith("$pageTitle ") ||
                 titleIdentityScore(wanted, pageTitle) >= 65))
        if (!titleMatches) return false
        val expectedYear = item.year.take(4).toIntOrNull()
        if (expectedYear != null) {
            // A page must state a year and it must be the requested one. Rotten Tomatoes disambiguates
            // same-name titles inside the slug itself, so a slug year counts as a stated year; that is
            // what lets Caveat (2021) match its page, which is slugged `caveat_2021` yet publishes a
            // 2020 festival release year. An entirely undated page stays unverified so a remake can
            // never be accepted on title alone.
            val known = releaseYears(html, doc, canonical)
            if (expectedYear !in known) return false
        }
        return true
    }

    /**
     * Every release year the page states.
     *
     * `dateCreated` is deliberately excluded. Rotten Tomatoes sets it to the most recent release
     * event for a title rather than the original one, so Avatar (2009) publishes `2022-09-23` for
     * its 4K re-release and treating it as the release year rejected the correct page. The real
     * signals are the where-to-watch `releaseYear`, the structured start dates, the year shown in
     * the title, and the year Rotten Tomatoes embeds in the canonical slug.
     */
    private fun releaseYears(html: String, doc: Document, canonical: String): Set<Int> {
        val years = LinkedHashSet<Int>()
        fun add(candidate: Int?) {
            candidate?.takeIf { it in 1888..2100 }?.let(years::add)
        }
        // The release year is published inside a JSON island, so it is read from the markup itself:
        // rendered text never contains script contents.
        RELEASE_YEAR_PATTERN.findAll(html).forEach { match ->
            add(match.groupValues.getOrNull(1)?.toIntOrNull())
        }
        doc.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return@forEach
            add(json.optString("datePublished").take(4).toIntOrNull())
            add(json.optString("startDate").take(4).toIntOrNull())
            add(json.optJSONObject("partOfSeries")?.optString("startDate")?.take(4)?.toIntOrNull())
        }
        add(yearInTitlePattern.find(doc.title())?.groupValues?.getOrNull(1)?.toIntOrNull())
        doc.selectFirst("[slot=releaseYear], [data-qa=release-year]")?.text()?.take(4)?.toIntOrNull()?.let(::add)
        SLUG_YEAR_PATTERN.findAll(canonical.ifBlank { doc.location() }).forEach { match ->
            add(match.groupValues.getOrNull(1)?.toIntOrNull())
        }
        // `dateCreated` is only ever a last resort, and only for a page that states no other year.
        // It tracks the most recent release event, so trusting it ahead of a real release year made
        // Avatar (2009) fail against its own 4K re-release page, which is dated 2022-09-23.
        if (years.isEmpty()) {
            doc.select("script[type=application/ld+json]").forEach { script ->
                val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return@forEach
                add(json.optString("dateCreated").take(4).toIntOrNull())
            }
        }
        return years
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
        // The reviews payload is the most reliable structured source on the current pages: it
        // separates the critic score from the audience score, so a Popcornmeter value can never be
        // mistaken for a Tomatometer value.
        parseReviewsCriticScore(document)?.let { return it }
        visibleTomatometerPattern.find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }?.let { return it }
        return rottenTomatoesPatterns.firstNotNullOfOrNull { pattern -> pattern.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 } }
    }

    private fun parseReviewsCriticScore(document: Document): Int? {
        for (json in reviewScoreBlocks(document)) {
            val score = json.optJSONObject("criticsScore")?.optIntValue("score")
            if (score != null && score in 0..100) return score
        }
        return null
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
    private fun normalizeText(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        // An apostrophe sits inside a word, so it is removed rather than turned into a separator.
        // Otherwise "Marvel's the Avengers" normalizes to "marvel s the avengers" and scores as a
        // weak match against "the avengers".
        .replace(Regex("['’‘`´]"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()
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

        /**
         * A leading byte order mark stops the HTML parser from recognising the page at all, which
         * discards the title and every script on it. It is stripped from every response.
         */
        const val BYTE_ORDER_MARK = "\uFEFF"

        /** The catalogue index Rotten Tomatoes' own search queries. */
        const val INDEX_NAME = "content_rt"
        const val ABSOLUTE_TIMEOUT_MS = 4_000L
        const val SEARCH_INDEX_CANDIDATE_LIMIT = 4
        val HARD_BLOCKED_REASONS = setOf(
            FailureReason.HTTP_403,
            FailureReason.HTTP_429,
            FailureReason.BLOCKED_PAGE,
            FailureReason.NETWORK,
        )
        val scoreText = Regex("\\d{1,3}")
        val visibleTomatometerPattern = Regex("""(\d{1,3})%\s*(?:Avg\.\s*)?Tomatometer""", RegexOption.IGNORE_CASE)
        val rottenTomatoesPathPattern = Regex("""/(?:m|tv)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""")
        val dateCreatedPattern = Regex(""""dateCreated"\s*:\s*"(\d{4})[^"]*"""", RegexOption.IGNORE_CASE)
        val yearInTitlePattern = Regex("""\((\d{4})\)""")

        /** `"releaseYear":"2009"` as published in the where-to-watch block of a title page. */
        val RELEASE_YEAR_PATTERN = Regex(""""releaseYear"\s*:\s*"?(\d{4})""", RegexOption.IGNORE_CASE)

        /** A four-digit year that Rotten Tomatoes appends to a slug to disambiguate same-name titles. */
        val SLUG_YEAR_PATTERN = Regex("""[_-](\d{4})(?:$|[/?#])""")
        val rottenTomatoesPatterns = listOf(
            Regex("""tomatometerscore\s*=\s*["']?(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex(""""criticsScore"\s*:\s*"?(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex(""""criticsScore"\s*:\s*\{[^{}]*"score"\s*:\s*"(\d{1,3})"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        )
        const val SERIES_PATH_TIMEOUT_MS = 6_000L
        const val MAX_EPISODE_CONCURRENCY = 4
        private val blockedMarkers = listOf(
            "captcha", "access denied", "request blocked", "challenge page", "security challenge",
            "verify you are human", "enable javascript to continue", "are you a robot", "robot check",
            "too many requests",
        )
    }
}
