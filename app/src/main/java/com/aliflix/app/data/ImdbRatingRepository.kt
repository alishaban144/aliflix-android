package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImdbTitleIdentity(
    val imdbId: String,
    val title: String,
    val year: Int?,
    val type: MediaType,
)

data class ImdbRatingSnapshot(
    val identity: ImdbTitleIdentity,
    val rating: Double?,
    val voteCount: Int?,
    val state: RatingSourceState,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

fun interface ImdbGraphQlTransport {
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): String
}

interface ImdbRatingRepository {
    suspend fun ratingFor(media: Media): ImdbRatingSnapshot
}

class HttpImdbGraphQlTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImdbGraphQlTransport {
    override suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): String = withContext(ioDispatcher) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        suspendCancellableCoroutine { continuation ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                headers.forEach(::setRequestProperty)
            }
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val response = stream?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    throw IOException("IMDb metadata request failed ($status)")
                }
                if (response.isBlank()) {
                    throw IOException("IMDb metadata response was empty")
                }
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

class DefaultImdbRatingRepository(
    private val cacheStore: CatalogCacheStore?,
    private val pageLoader: suspend (String) -> String,
    private val graphQlTransport: ImdbGraphQlTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ImdbRatingRepository {
    override suspend fun ratingFor(media: Media): ImdbRatingSnapshot {
        cacheStore?.loadImdbRating(media.key, FRESH_CACHE_AGE_MS)?.let { return it }
        val stale = cacheStore?.loadImdbRating(media.key, STALE_CACHE_AGE_MS)

        val identity = media.imdbId
            ?.takeIf(IMDB_ID_PATTERN::matches)
            ?.let {
                ImdbTitleIdentity(
                    imdbId = it,
                    title = media.title,
                    year = media.year.take(4).toIntOrNull(),
                    type = media.type,
                )
            }
            ?: try {
                resolveIdentity(media)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }

        if (identity == null) {
            return stale?.copy(state = RatingSourceState.STALE)
                ?: unavailableSnapshot(media)
        }

        var providerResponded = false
        for (endpoint in GRAPHQL_ENDPOINTS) {
            try {
                val parsed = parseGraphQlRating(
                    graphQlTransport.postJson(
                        endpoint,
                        ratingQuery(identity.imdbId),
                        IMDB_WEB_HEADERS,
                    ),
                    identity,
                )
                providerResponded = true
                if (parsed != null) {
                    cacheStore?.saveImdbRating(media.key, parsed)
                    return parsed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Each host is independent. Continue to the next source.
            }
        }

        try {
            val html = pageLoader("https://www.imdb.com/title/${identity.imdbId}/reference/")
            if (!isVerifiedImdbTitlePage(html, identity)) {
                throw IOException("IMDb title page identity could not be verified")
            }
            providerResponded = true
            val parsed = parseImdbPageRating(html, identity, nowMillis())
            if (parsed != null) {
                cacheStore?.saveImdbRating(media.key, parsed)
                return parsed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Stale data is preferable to turning a transient outage into "not rated".
        }

        if (providerResponded) {
            val notRated = ImdbRatingSnapshot(
                identity = identity,
                rating = null,
                voteCount = null,
                state = RatingSourceState.NOT_RATED,
                fetchedAtMillis = nowMillis(),
            )
            cacheStore?.saveImdbRating(media.key, notRated)
            return notRated
        }
        return stale?.copy(state = RatingSourceState.STALE)
            ?: ImdbRatingSnapshot(
                identity = identity,
                rating = null,
                voteCount = null,
                state = RatingSourceState.UNAVAILABLE,
                fetchedAtMillis = nowMillis(),
            )
    }

    internal suspend fun resolveIdentity(media: Media): ImdbTitleIdentity? {
        val wantedTitle = normalize(media.title)
        val initial = wantedTitle.firstOrNull { it.isLetterOrDigit() } ?: 'a'
        val encoded = URLEncoder.encode(media.title, StandardCharsets.UTF_8.toString())
        val root = try {
            JSONObject(pageLoader("https://v3.sg.media-imdb.com/suggestion/$initial/$encoded.json"))
        } catch (_: Throwable) {
            try {
                JSONObject(pageLoader("$IMDB_SUGGESTION_URL/$encoded.json"))
            } catch (_: Throwable) {
                null
            }
        } ?: return null

        val entries = root.optJSONArray("d") ?: return null
        val wantedYear = media.year.take(4).toIntOrNull()
        return (0 until entries.length())
            .mapNotNull(entries::optJSONObject)
            .mapNotNull { candidate ->
                val id = candidate.optString("id")
                if (!IMDB_ID_PATTERN.matches(id)) return@mapNotNull null
                val title = candidate.optString("l").trim()
                if (title.isBlank()) return@mapNotNull null
                val qualifier = candidate.optString("q").lowercase()
                val qid = candidate.optString("qid").lowercase()
                val isFeatureMovie = "feature" in qualifier || "movie" in qualifier || "film" in qualifier || qid == "movie"
                val isShort = "short" in qualifier || qid == "short"
                val isTv = "tv" in qualifier || "series" in qualifier || "mini" in qualifier || "tv" in qid
                val candidateType = when {
                    isTv -> MediaType.TV
                    isFeatureMovie || isShort -> MediaType.MOVIE
                    else -> media.type
                }
                if (media.type == MediaType.MOVIE && isTv) return@mapNotNull null
                if (media.type == MediaType.TV && isFeatureMovie && !isTv) return@mapNotNull null

                val year = candidate.optInt("y").takeIf { it > 0 }
                val titleScore = titleIdentityScore(wantedTitle, normalize(title))
                val typeBonus = when {
                    media.type == MediaType.MOVIE && isFeatureMovie -> 30
                    media.type == MediaType.MOVIE && isShort -> -50
                    media.type == MediaType.TV && isTv -> 30
                    else -> 0
                }
                val yearScore = when {
                    wantedYear == null || year == null -> 0
                    wantedYear == year -> 25
                    kotlin.math.abs(wantedYear - year) == 1 -> 20
                    kotlin.math.abs(wantedYear - year) == 2 -> 10
                    else -> -40
                }
                val rank = candidate.optInt("rank", 999_999)
                val rankBonus = when {
                    rank in 1..1_000 -> 20
                    rank in 1_001..20_000 -> 10
                    else -> 0
                }
                val total = titleScore + yearScore + typeBonus + rankBonus
                Triple(
                    ImdbTitleIdentity(id, title, year, candidateType),
                    total,
                    titleScore,
                )
            }
            .filter { (_, total, titleScore) -> total >= 65 && titleScore >= 65 }
            .maxByOrNull { (_, total) -> total }
            ?.first
    }

    internal fun parseGraphQlRating(
        payload: String,
        identity: ImdbTitleIdentity,
    ): ImdbRatingSnapshot? {
        val root = JSONObject(payload)
        if (root.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            throw IOException("IMDb GraphQL returned errors")
        }
        val title = root.optJSONObject("data")?.optJSONObject("title") ?: return null
        if (!matchesIdentity(title, identity)) {
            throw IOException("IMDb returned a different title identity")
        }
        val summary = title.optJSONObject("ratingsSummary") ?: return null
        val rating = summary.optDouble("aggregateRating")
            .takeIf { !it.isNaN() && it in 0.1..10.0 }
        val votes = summary.optInt("voteCount")
            .takeIf { summary.has("voteCount") && it >= 0 }
        return ImdbRatingSnapshot(
            identity = identity,
            rating = rating,
            voteCount = votes,
            state = if (rating == null) {
                RatingSourceState.NOT_RATED
            } else {
                RatingSourceState.VERIFIED
            },
            fetchedAtMillis = nowMillis(),
        )
    }

    internal fun parseImdbPageRating(
        html: String,
        identity: ImdbTitleIdentity,
        fetchedAtMillis: Long = nowMillis(),
    ): ImdbRatingSnapshot? {
        val document = org.jsoup.Jsoup.parse(html, "https://www.imdb.com")
        document.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull()
                ?: return@forEach
            val aggregate = json.optJSONObject("aggregateRating") ?: return@forEach
            val rating = aggregate.optDouble("ratingValue")
                .takeIf { !it.isNaN() && it in 0.1..10.0 }
            val votes = aggregate.optInt("ratingCount")
                .takeIf { aggregate.has("ratingCount") && it >= 0 }
            if (rating != null) {
                return ImdbRatingSnapshot(
                    identity = identity,
                    rating = rating,
                    voteCount = votes,
                    state = RatingSourceState.VERIFIED,
                    fetchedAtMillis = fetchedAtMillis,
                )
            }
        }
        val rating = IMDB_RATING_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.1..10.0 }
            ?: return null
        return ImdbRatingSnapshot(
            identity = identity,
            rating = rating,
            voteCount = null,
            state = RatingSourceState.VERIFIED,
            fetchedAtMillis = fetchedAtMillis,
        )
    }

    internal fun isVerifiedImdbTitlePage(
        html: String,
        identity: ImdbTitleIdentity,
    ): Boolean {
        val document = org.jsoup.Jsoup.parse(html, "https://www.imdb.com")
        val canonicalMatches = document
            .selectFirst("link[rel=canonical]")
            ?.attr("href")
            ?.contains("/title/${identity.imdbId}")
            ?: false
        var jsonIdentityMatches = false
        document.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull()
                ?: return@forEach
            val url = json.optString("url")
            val sameAs = json.optString("sameAs")
            val name = json.optString("name")
            val idMatches =
                url.contains(identity.imdbId) || sameAs.contains(identity.imdbId)
            val titleMatches = name.isBlank() ||
                titleIdentityScore(normalize(identity.title), normalize(name)) >= 70
            if (idMatches && titleMatches) jsonIdentityMatches = true
        }
        return canonicalMatches || jsonIdentityMatches
    }

    private fun unavailableSnapshot(media: Media) = ImdbRatingSnapshot(
        identity = ImdbTitleIdentity(
            imdbId = media.imdbId.orEmpty(),
            title = media.title,
            year = media.year.take(4).toIntOrNull(),
            type = media.type,
        ),
        rating = null,
        voteCount = null,
        state = RatingSourceState.UNAVAILABLE,
        fetchedAtMillis = nowMillis(),
    )

    private fun titleIdentityScore(wanted: String, candidate: String): Int {
        if (wanted == candidate) return 100
        if (wanted.isBlank() || candidate.isBlank()) return 0
        val wantedTokens = wanted.split(' ').filter(String::isNotBlank).toSet()
        val candidateTokens = candidate.split(' ').filter(String::isNotBlank).toSet()
        val union = wantedTokens union candidateTokens
        val overlap = if (union.isEmpty()) {
            0.0
        } else {
            (wantedTokens intersect candidateTokens).size.toDouble() / union.size
        }
        val containment = wanted.contains(candidate) || candidate.contains(wanted)
        return (overlap * 82.0).toInt() + if (containment) 16 else 0
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun ratingQuery(imdbId: String): String = JSONObject()
        .put(
            "query",
            "query AliflixRating { title(id: \"$imdbId\") { " +
                "id titleText { text } releaseYear { year } titleType { id } " +
                "ratingsSummary { aggregateRating voteCount } } }",
        )
        .toString()

    private fun matchesIdentity(
        payload: JSONObject,
        expected: ImdbTitleIdentity,
    ): Boolean {
        if (payload.optString("id") != expected.imdbId) return false
        val returnedTitle = payload.optJSONObject("titleText")
            ?.optString("text")
            .orEmpty()
        if (titleIdentityScore(normalize(expected.title), normalize(returnedTitle)) < 65) {
            return false
        }
        val returnedYear = payload.optJSONObject("releaseYear")
            ?.optInt("year")
            ?.takeIf { it > 0 }
        if (
            expected.year != null &&
            returnedYear != null &&
            kotlin.math.abs(expected.year - returnedYear) > 2
        ) {
            return false
        }
        val type = payload.optJSONObject("titleType")
            ?.optString("id")
            .orEmpty()
            .lowercase()
        return when (expected.type) {
            MediaType.MOVIE -> type in IMDB_MOVIE_TYPES
            MediaType.TV -> type in IMDB_TV_TYPES
        }
    }

    companion object {
        const val FRESH_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        const val STALE_CACHE_AGE_MS = 30L * 24 * 60 * 60 * 1_000
        const val IMDB_SUGGESTION_URL = "https://v3.sg.media-imdb.com/suggestion/x"
        val GRAPHQL_ENDPOINTS = listOf(
            "https://api.graphql.imdb.com/",
            "https://caching.graphql.imdb.com/",
        )
        val IMDB_WEB_HEADERS = linkedMapOf(
            "Accept" to "application/graphql+json, application/json",
            "Origin" to "https://www.imdb.com",
            "Referer" to "https://www.imdb.com/",
            "User-Agent" to (
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
                ),
            "x-imdb-client-name" to "imdb-web-next-localized",
            "x-imdb-user-language" to "en-US",
            "x-imdb-user-country" to "US",
        )
        private val IMDB_ID_PATTERN = Regex("tt\\d{5,12}")
        private val IMDB_MOVIE_TYPES = setOf(
            "movie",
            "tvmovie",
            "short",
        )
        private val IMDB_TV_TYPES = setOf(
            "tvseries",
            "tvminiseries",
            "tvspecial",
        )
        private val IMDB_RATING_PATTERN = Regex(
            """"aggregateRating"\s*:\s*(?:\{[^}]*"ratingValue"\s*:\s*)?["']?(\d(?:\.\d)?)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
