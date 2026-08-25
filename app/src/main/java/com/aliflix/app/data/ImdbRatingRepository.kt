package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Episode
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

data class ImdbEpisodeRatingSnapshot(
    val imdbId: String?,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val year: Int?,
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

    suspend fun ratingsForEpisodes(
        series: Media,
        seasonNumber: Int,
        episodes: List<Episode>,
    ): Map<Int, ImdbEpisodeRatingSnapshot> = emptyMap()
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
        cacheStore?.loadImdbRating(media.key, FRESH_CACHE_AGE_MS)?.takeIf {
            it.state == RatingSourceState.VERIFIED &&
                it.rating != null &&
                it.rating > 0.0 &&
                cachedIdentityMatches(media, it)
        }?.let { return it }
        val stale = cacheStore?.loadImdbRating(media.key, STALE_CACHE_AGE_MS)?.takeIf {
            it.state == RatingSourceState.VERIFIED &&
                it.rating != null &&
                it.rating > 0.0 &&
                cachedIdentityMatches(media, it)
        }

        val resolvedIdentity = try {
            resolveIdentity(media)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val identity = resolvedIdentity ?: media.imdbId
            ?.takeIf(IMDB_ID_PATTERN::matches)
            ?.let {
                ImdbTitleIdentity(
                    imdbId = it,
                    title = media.title,
                    year = media.year.take(4).toIntOrNull(),
                    type = media.type,
                )
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
                if (parsed != null && parsed.rating != null && parsed.rating > 0.0) {
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
            if (parsed != null && parsed.rating != null && parsed.rating > 0.0) {
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

    override suspend fun ratingsForEpisodes(
        series: Media,
        seasonNumber: Int,
        episodes: List<Episode>,
    ): Map<Int, ImdbEpisodeRatingSnapshot> {
        if (series.type != MediaType.TV || seasonNumber < 0) return emptyMap()
        val requested = episodes
            .filter { it.seasonNumber == seasonNumber && it.number > 0 }
            .distinctBy(Episode::number)
        if (requested.isEmpty()) return emptyMap()

        val fresh = linkedMapOf<Int, ImdbEpisodeRatingSnapshot>()
        val stale = linkedMapOf<Int, ImdbEpisodeRatingSnapshot>()
        for (episode in requested) {
            val key = episodeRatingCacheKey(series, episode)
            cacheStore?.loadImdbRating(key, FRESH_CACHE_AGE_MS)
                ?.takeIf { cachedEpisodeIdentityMatches(episode, it) }
                ?.toEpisodeSnapshot(seasonNumber, episode.number)
                ?.let { fresh[episode.number] = it }
            if (episode.number !in fresh) {
                cacheStore?.loadImdbRating(key, STALE_CACHE_AGE_MS)
                    ?.takeIf { cachedEpisodeIdentityMatches(episode, it) }
                    ?.toEpisodeSnapshot(seasonNumber, episode.number)
                    ?.let { stale[episode.number] = it }
            }
        }
        if (fresh.size == requested.size) return fresh

        val identityCandidates = buildList {
            try {
                resolveIdentity(series)?.let(::add)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A carried IMDb ID is still verified against the returned parent series below.
            }
            series.imdbId
                ?.takeIf(IMDB_ID_PATTERN::matches)
                ?.let { imdbId ->
                    add(
                        ImdbTitleIdentity(
                            imdbId = imdbId,
                            title = series.title,
                            year = series.year.take(4).toIntOrNull(),
                            type = MediaType.TV,
                        ),
                    )
                }
        }.distinctBy(ImdbTitleIdentity::imdbId)

        for (identity in identityCandidates) {
            for (endpoint in GRAPHQL_ENDPOINTS) {
                try {
                    val parsed = parseGraphQlEpisodeRatings(
                        payload = graphQlTransport.postJson(
                            endpoint,
                            episodeRatingsQuery(identity.imdbId, seasonNumber),
                            IMDB_WEB_HEADERS,
                        ),
                        expectedSeries = identity,
                        expectedSeason = seasonNumber,
                    )
                    val resolved = requested.associate { episode ->
                        val live = parsed[episode.number]
                            ?.takeIf { episodeIdentityMatches(episode, it) }
                        val snapshot = live
                            ?: fresh[episode.number]
                            ?: stale[episode.number]?.asStale()
                            ?: unavailableEpisodeSnapshot(episode)
                        if (live != null && live.state in setOf(
                                RatingSourceState.VERIFIED,
                                RatingSourceState.NOT_RATED,
                            )
                        ) {
                            cacheStore?.saveImdbRating(
                                episodeRatingCacheKey(series, episode),
                                live.toCachedSnapshot(),
                            )
                        }
                        episode.number to snapshot
                    }
                    return resolved
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Each IMDb GraphQL host is independent.
                }
            }
        }

        return requested.associate { episode ->
            episode.number to (
                fresh[episode.number]
                    ?: stale[episode.number]?.asStale()
                    ?: unavailableEpisodeSnapshot(episode)
                )
        }
    }

    internal fun cachedIdentityMatches(
        media: Media,
        snapshot: ImdbRatingSnapshot,
    ): Boolean {
        if (snapshot.identity.type != media.type) return false

        val titleMatches = titleIdentityScore(
            normalize(media.title.replace(Regex("\\(\\d{4}\\)"), "").trim()),
            normalize(snapshot.identity.title),
        ) >= 70
        if (!titleMatches) return false

        val mediaYear = media.year.take(4).toIntOrNull()
        val cachedYear = snapshot.identity.year
        return mediaYear == null || cachedYear == null || kotlin.math.abs(mediaYear - cachedYear) <= 3
    }

    internal suspend fun resolveIdentity(media: Media): ImdbTitleIdentity? {
        val cleanTitle = media.title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val wantedTitle = normalize(cleanTitle)
        val initial = wantedTitle.firstOrNull { it.isLetterOrDigit() } ?: 'a'
        val encoded = URLEncoder.encode(cleanTitle, StandardCharsets.UTF_8.toString())
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

    internal fun parseGraphQlEpisodeRatings(
        payload: String,
        expectedSeries: ImdbTitleIdentity,
        expectedSeason: Int,
    ): Map<Int, ImdbEpisodeRatingSnapshot> {
        val root = JSONObject(payload)
        if (root.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            throw IOException("IMDb GraphQL returned errors")
        }
        val title = root.optJSONObject("data")?.optJSONObject("title")
            ?: throw IOException("IMDb did not return the parent series")
        if (!matchesIdentity(title, expectedSeries)) {
            throw IOException("IMDb returned a different parent series")
        }
        val edges = title.optJSONObject("episodes")
            ?.optJSONObject("episodes")
            ?.optJSONArray("edges")
            ?: return emptyMap()
        val fetchedAt = nowMillis()
        return (0 until edges.length())
            .mapNotNull(edges::optJSONObject)
            .mapNotNull { edge ->
                val node = edge.optJSONObject("node") ?: return@mapNotNull null
                val numbering = node.optJSONObject("series")
                    ?.optJSONObject("episodeNumber")
                    ?: return@mapNotNull null
                val season = numbering.optInt("seasonNumber", -1)
                val episode = numbering.optInt("episodeNumber", -1)
                if (season != expectedSeason || episode <= 0) return@mapNotNull null
                val imdbId = node.optString("id").takeIf(IMDB_ID_PATTERN::matches)
                    ?: return@mapNotNull null
                val titleText = node.optJSONObject("titleText")
                    ?.optString("text")
                    ?.trim()
                    .orEmpty()
                if (titleText.isBlank()) return@mapNotNull null
                val summary = node.optJSONObject("ratingsSummary")
                val rating = summary?.optDouble("aggregateRating")
                    ?.takeIf { !it.isNaN() && it in 0.1..10.0 }
                val votes = summary?.optInt("voteCount")
                    ?.takeIf { summary.has("voteCount") && it >= 0 }
                val year = node.optJSONObject("releaseDate")
                    ?.optInt("year")
                    ?.takeIf { it > 0 }
                episode to ImdbEpisodeRatingSnapshot(
                    imdbId = imdbId,
                    title = titleText,
                    seasonNumber = season,
                    episodeNumber = episode,
                    year = year,
                    rating = rating,
                    voteCount = votes,
                    state = if (rating == null) {
                        RatingSourceState.NOT_RATED
                    } else {
                        RatingSourceState.VERIFIED
                    },
                    fetchedAtMillis = fetchedAt,
                )
            }
            .toMap()
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

    private fun episodeRatingsQuery(imdbId: String, seasonNumber: Int): String = JSONObject()
        .put(
            "query",
            "query AliflixEpisodeRatings { title(id: \"$imdbId\") { " +
                "id titleText { text } releaseYear { year } titleType { id } " +
                "episodes { episodes(first: 999, filter: { includeSeasons: [\"$seasonNumber\"] }) { " +
                "edges { node { id titleText { text } releaseDate { year month day } " +
                "series { episodeNumber { seasonNumber episodeNumber } } " +
                "ratingsSummary { aggregateRating voteCount } } } } } } }",
        )
        .toString()

    private fun episodeRatingCacheKey(series: Media, episode: Episode): String =
        "episode:${series.key}:s${episode.seasonNumber}:e${episode.number}"

    private fun cachedEpisodeIdentityMatches(
        episode: Episode,
        snapshot: ImdbRatingSnapshot,
    ): Boolean = snapshot.identity.imdbId.matches(IMDB_ID_PATTERN) &&
        episodeIdentityMatches(
            episode,
            snapshot.toEpisodeSnapshot(episode.seasonNumber, episode.number),
        )

    private fun episodeIdentityMatches(
        episode: Episode,
        snapshot: ImdbEpisodeRatingSnapshot,
    ): Boolean {
        if (snapshot.seasonNumber != episode.seasonNumber || snapshot.episodeNumber != episode.number) {
            return false
        }
        if (episode.title.equals("Episode ${episode.number}", ignoreCase = true)) return true
        return titleIdentityScore(normalize(episode.title), normalize(snapshot.title)) >= 65
    }

    private fun ImdbRatingSnapshot.toEpisodeSnapshot(
        seasonNumber: Int,
        episodeNumber: Int,
    ) = ImdbEpisodeRatingSnapshot(
        imdbId = identity.imdbId,
        title = identity.title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        year = identity.year,
        rating = rating,
        voteCount = voteCount,
        state = state,
        fetchedAtMillis = fetchedAtMillis,
    )

    private fun ImdbEpisodeRatingSnapshot.toCachedSnapshot() = ImdbRatingSnapshot(
        identity = ImdbTitleIdentity(
            imdbId = imdbId.orEmpty(),
            title = title,
            year = year,
            type = MediaType.TV,
        ),
        rating = rating,
        voteCount = voteCount,
        state = state,
        fetchedAtMillis = fetchedAtMillis,
    )

    private fun ImdbEpisodeRatingSnapshot.asStale() = if (state == RatingSourceState.VERIFIED) {
        copy(state = RatingSourceState.STALE)
    } else {
        this
    }

    private fun unavailableEpisodeSnapshot(episode: Episode) = ImdbEpisodeRatingSnapshot(
        imdbId = null,
        title = episode.title,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.number,
        year = null,
        rating = null,
        voteCount = null,
        state = RatingSourceState.UNAVAILABLE,
        fetchedAtMillis = nowMillis(),
    )

    private fun matchesIdentity(
        payload: JSONObject,
        expected: ImdbTitleIdentity,
    ): Boolean {
        if (payload.optString("id") != expected.imdbId) return false
        val returnedTitle = payload.optJSONObject("titleText")
            ?.optString("text")
            .orEmpty()
        val score = titleIdentityScore(normalize(expected.title), normalize(returnedTitle))
        if (score < 45 && !normalize(returnedTitle).contains(normalize(expected.title)) && !normalize(expected.title).contains(normalize(returnedTitle))) {
            return false
        }
        val returnedYear = payload.optJSONObject("releaseYear")
            ?.optInt("year")
            ?.takeIf { it > 0 }
        if (
            expected.year != null &&
            returnedYear != null &&
            kotlin.math.abs(expected.year - returnedYear) > 3
        ) {
            return false
        }
        val type = payload.optJSONObject("titleType")
            ?.optString("id")
            .orEmpty()
            .lowercase()
        return when (expected.type) {
            MediaType.MOVIE -> type in IMDB_MOVIE_TYPES || type.contains("movie") || type.contains("film")
            MediaType.TV -> type in IMDB_TV_TYPES || type.contains("tv") || type.contains("series")
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
