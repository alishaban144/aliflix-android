package com.aliflix.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

data class AniListCatalogItem(
    val aniListId: Int,
    val englishTitle: String?,
    val romajiTitle: String?,
    val nativeTitle: String?,
    val synonyms: List<String>,
    val format: String?,
    val year: Int?,
    val episodes: Int?,
    val description: String,
    val coverImageUrl: String?,
    val bannerImageUrl: String?,
    val genres: List<String>,
    val averageScore: Int?,
    val countryOfOrigin: String,
) {
    val preferredTitle: String
        get() = englishTitle ?: romajiTitle ?: nativeTitle ?: "Untitled"

    val aliases: List<String>
        get() = buildList {
            englishTitle?.let(::add)
            romajiTitle?.let(::add)
            nativeTitle?.let(::add)
            addAll(synonyms)
        }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeAniListAlias)

    val normalizedAliases: List<String>
        get() = aliases
            .map(::normalizeAniListAlias)
            .filter(String::isNotBlank)
            .distinct()
}

class AniListCatalogClient(
    private val postJson: suspend (String) -> String = ::postToAniListCatalog,
) {
    suspend fun search(query: String): List<AniListCatalogItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        return request(
            search = cleanQuery,
            sort = listOf("SEARCH_MATCH", "POPULARITY_DESC"),
        )
    }

    suspend fun trending(): List<AniListCatalogItem> = request(
        search = null,
        sort = listOf("TRENDING_DESC", "POPULARITY_DESC"),
    )

    private suspend fun request(
        search: String?,
        sort: List<String>,
    ): List<AniListCatalogItem> {
        val variables = JSONObject()
            .put("search", search ?: JSONObject.NULL)
            .put("sort", JSONArray(sort))
            .put("countryOfOrigin", JAPAN_COUNTRY_CODE)
        val requestBody = JSONObject()
            .put("query", CATALOG_QUERY)
            .put("variables", variables)
            .toString()
        val root = JSONObject(postJson(requestBody))
        root.optJSONArray("errors")
            ?.takeIf { it.length() > 0 }
            ?.let { errors ->
                val message = errors.optJSONObject(0)
                    ?.nullableString("message")
                    ?: "AniList rejected the catalogue request."
                throw IOException("AniList catalogue request failed: $message")
            }

        val media = root
            .optJSONObject("data")
            ?.optJSONObject("Page")
            ?.optJSONArray("media")
            ?: return emptyList()
        return (0 until media.length())
            .mapNotNull { index ->
                media.optJSONObject(index)?.toCatalogItem()
            }
            .distinctBy(AniListCatalogItem::aniListId)
    }

    private fun JSONObject.toCatalogItem(): AniListCatalogItem? {
        val id = optInt("id").takeIf { it > 0 } ?: return null
        val country = nullableString("countryOfOrigin") ?: return null
        if (!country.equals(JAPAN_COUNTRY_CODE, ignoreCase = true)) return null
        if (optBoolean("isAdult", false)) return null

        val titles = optJSONObject("title")
        val coverImage = optJSONObject("coverImage")
        return AniListCatalogItem(
            aniListId = id,
            englishTitle = titles?.nullableString("english"),
            romajiTitle = titles?.nullableString("romaji"),
            nativeTitle = titles?.nullableString("native"),
            synonyms = optJSONArray("synonyms").stringValues(),
            format = nullableString("format"),
            year = optJSONObject("startDate")
                ?.optInt("year")
                ?.takeIf { it > 0 },
            episodes = optInt("episodes").takeIf { it > 0 },
            description = nullableString("description")
                ?.let(::plainText)
                .orEmpty(),
            coverImageUrl = coverImage?.nullableString("extraLarge")
                ?: coverImage?.nullableString("large"),
            bannerImageUrl = nullableString("bannerImage"),
            genres = optJSONArray("genres").stringValues(),
            averageScore = optInt("averageScore").takeIf { it in 1..100 },
            countryOfOrigin = country.uppercase(Locale.ROOT),
        )
    }

    private companion object {
        const val JAPAN_COUNTRY_CODE = "JP"
        const val CATALOG_QUERY = """
            query AliflixAnimeCatalog(
              ${'$'}search: String,
              ${'$'}sort: [MediaSort],
              ${'$'}countryOfOrigin: CountryCode
            ) {
              Page(page: 1, perPage: 30) {
                media(
                  search: ${'$'}search,
                  type: ANIME,
                  isAdult: false,
                  countryOfOrigin: ${'$'}countryOfOrigin,
                  sort: ${'$'}sort
                ) {
                  id
                  isAdult
                  format
                  countryOfOrigin
                  startDate { year }
                  episodes
                  title { romaji english native }
                  synonyms
                  description(asHtml: false)
                  coverImage { extraLarge large }
                  bannerImage
                  genres
                  averageScore
                }
              }
            }
        """
    }
}

internal fun normalizeAniListAlias(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
    val withoutLatinDiacritics = latinDiacritics.replace(decomposed) { match ->
        match.groupValues[1]
    }
    return Normalizer.normalize(withoutLatinDiacritics, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

private val latinDiacritics = Regex("(\\p{IsLatin})\\p{M}+")

private fun plainText(value: String): String =
    Jsoup.parseBodyFragment(value).body().text().trim()

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return (0 until length())
        .mapNotNull { index ->
            optString(index)
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
        }
        .distinct()
}

private fun JSONObject.nullableString(key: String): String? =
    optString(key)
        .trim()
        .takeIf { it.isNotBlank() && it != "null" }

private suspend fun postToAniListCatalog(body: String): String =
    withContext(Dispatchers.IO) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(ANILIST_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 15_000
            doOutput = true
            instanceFollowRedirects = false
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty(
                "User-Agent",
                "Aliflix-Android/2.3 (+https://github.com/alishaban144/aliflix-android)",
            )
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(payload)
            }
            val status = connection.responseCode
            val response = (
                if (status in 200..299) connection.inputStream else connection.errorStream
                )
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IOException("AniList catalogue request failed (HTTP $status).")
            }
            if (response.isBlank()) {
                throw IOException("AniList catalogue response was empty.")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
