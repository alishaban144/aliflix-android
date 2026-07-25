package com.aliflix.app.player

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal class AniListResolver(
    private val postJson: suspend (String) -> String = { body ->
        withContext(Dispatchers.IO) { postToAniList(body) }
    },
) {
    private val cache = ConcurrentHashMap<String, Resolution>()

    suspend fun resolveWatchUrl(selection: PlaybackSelection): String {
        val cacheKey = buildString {
            append(selection.media.key)
            append(':')
            append(selection.media.title)
            append(':')
            append(selection.media.year)
            append(":s")
            append(selection.seasonNumber ?: 1)
        }
        val resolved = cache[cacheKey] ?: resolve(selection).also {
            cache[cacheKey] = it
        }
        val baseUrl = selection.source.baseUrl.trimEnd('/')
        val episode = selection.episodeNumber ?: 1
        return "$baseUrl/watch/${resolved.id}?ep=$episode"
    }

    private suspend fun resolve(selection: PlaybackSelection): Resolution {
        val season = selection.seasonNumber ?: 1
        val searches = buildList {
            if (
                selection.media.type == MediaType.TV &&
                season > 1 &&
                !selection.media.title.contains("season $season", ignoreCase = true)
            ) {
                add("${selection.media.title} Season $season")
            }
            add(selection.media.title)
        }.distinct()

        val candidates = searches.flatMapIndexed { searchIndex, search ->
            search(search).map { candidate ->
                ScoredCandidate(
                    candidate = candidate,
                    score = score(
                        selection = selection,
                        candidate = candidate,
                        search = search,
                        searchIndex = searchIndex,
                    ),
                )
            }
        }
        val best = candidates
            .filter { it.candidate.countryOfOrigin == "JP" }
            .maxByOrNull(ScoredCandidate::score)
            ?.takeIf { it.score >= MINIMUM_MATCH_SCORE }
            ?.candidate
            ?: error("Miruro could not match this Japanese anime on AniList.")
        return Resolution(
            id = best.id,
            title = best.englishTitle
                ?: best.romajiTitle
                ?: best.nativeTitle
                ?: selection.media.title,
        )
    }

    private suspend fun search(title: String): List<Candidate> {
        val variables = JSONObject().put("search", title)
        val body = JSONObject()
            .put("query", SEARCH_QUERY)
            .put("variables", variables)
            .toString()
        val root = JSONObject(postJson(body))
        val apiErrors = root.optJSONArray("errors")
        if (apiErrors != null && apiErrors.length() > 0) {
            error(
                apiErrors.optJSONObject(0)
                    ?.optString("message")
                    ?.takeIf(String::isNotBlank)
                    ?: "AniList rejected the anime lookup.",
            )
        }
        val media = root
            .optJSONObject("data")
            ?.optJSONObject("Page")
            ?.optJSONArray("media")
            ?: return emptyList()
        return (0 until media.length()).mapNotNull { index ->
            val item = media.optJSONObject(index) ?: return@mapNotNull null
            val titles = item.optJSONObject("title")
            Candidate(
                id = item.optInt("id"),
                englishTitle = titles?.nullableString("english"),
                romajiTitle = titles?.nullableString("romaji"),
                nativeTitle = titles?.nullableString("native"),
                synonyms = item.optJSONArray("synonyms")?.let { synonyms ->
                    (0 until synonyms.length()).mapNotNull { synonymIndex ->
                        synonyms.optString(synonymIndex)
                            .takeIf(String::isNotBlank)
                    }
                }.orEmpty(),
                year = item.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 },
                format = item.nullableString("format"),
                countryOfOrigin = item.nullableString("countryOfOrigin"),
            ).takeIf { it.id > 0 }
        }
    }

    private fun score(
        selection: PlaybackSelection,
        candidate: Candidate,
        search: String,
        searchIndex: Int,
    ): Int {
        val wanted = normalize(search)
        val originalWanted = normalize(selection.media.title)
        val titles = buildList {
            candidate.englishTitle?.let(::add)
            candidate.romajiTitle?.let(::add)
            candidate.nativeTitle?.let(::add)
            addAll(candidate.synonyms)
        }.map(::normalize).filter(String::isNotBlank)
        var score = when {
            wanted in titles -> 140
            originalWanted in titles -> 125
            titles.any { it.contains(wanted) || wanted.contains(it) } -> 92
            titles.any { it.contains(originalWanted) || originalWanted.contains(it) } -> 82
            else -> titles.maxOfOrNull { tokenSimilarity(wanted, it) } ?: 0
        }
        score -= searchIndex * 4
        val expectedYear = selection.media.year.take(4).toIntOrNull()
        if (expectedYear != null && candidate.year == expectedYear) score += 14
        score += when (selection.media.type) {
            MediaType.MOVIE -> if (candidate.format == "MOVIE") 24 else -24
            MediaType.TV -> if (
                candidate.format in setOf("TV", "TV_SHORT", "ONA", "OVA", "SPECIAL")
            ) {
                12
            } else {
                -16
            }
        }
        if (candidate.countryOfOrigin == "JP") score += 20
        return score
    }

    private data class Candidate(
        val id: Int,
        val englishTitle: String?,
        val romajiTitle: String?,
        val nativeTitle: String?,
        val synonyms: List<String>,
        val year: Int?,
        val format: String?,
        val countryOfOrigin: String?,
    )

    private data class ScoredCandidate(
        val candidate: Candidate,
        val score: Int,
    )

    private data class Resolution(
        val id: Int,
        val title: String,
    )

    private companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        const val MINIMUM_MATCH_SCORE = 72
        const val SEARCH_QUERY = """
            query AliflixAnimeSearch(${'$'}search: String) {
              Page(page: 1, perPage: 8) {
                media(search: ${'$'}search, type: ANIME, isAdult: false) {
                  id
                  format
                  countryOfOrigin
                  startDate { year }
                  title { romaji english native }
                  synonyms
                }
              }
            }
        """

        fun postToAniList(body: String): String {
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 15_000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty(
                    "User-Agent",
                    "Aliflix-Android/2.0 (+https://github.com/alishaban144/aliflix-android)",
                )
            }
            return try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
                val status = connection.responseCode
                val response = (
                    if (status in 200..299) connection.inputStream else connection.errorStream
                    )
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    error("AniList lookup failed (HTTP $status).")
                }
                response
            } finally {
                connection.disconnect()
            }
        }

        fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        fun tokenSimilarity(left: String, right: String): Int {
            val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
            val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0
            val intersection = leftTokens.intersect(rightTokens).size
            val union = leftTokens.union(rightTokens).size
            return (intersection * 75) / union
        }

        fun JSONObject.nullableString(key: String): String? =
            optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
