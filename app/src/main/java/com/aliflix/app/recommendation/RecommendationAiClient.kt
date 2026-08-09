package com.aliflix.app.recommendation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RecommendationAiClientException(
    val code: String = "NETWORK_ERROR",
    val retryable: Boolean = true,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class RecommendationAiClient(
    private val baseUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun getRecommendations(request: V3RecommendationRequest): V3RecommendationResponse = withContext(ioDispatcher) {
        V3RecommendationResponse.fromJson(JSONObject(postJson("$baseUrl/v3/recommendations", request.toJson())))
    }

    private suspend fun postJson(url: String, jsonBody: JSONObject): String = suspendCancellableCoroutine { continuation ->
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            continuation.invokeOnCancellation { connection.disconnect() }
            connection.outputStream.use { it.write(jsonBody.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(response).optJSONObject("error") }.getOrNull()
                throw RecommendationAiClientException(
                    code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "WORKER_ERROR",
                    retryable = error?.optBoolean("retryable", status == 429 || status >= 500) ?: (status == 429 || status >= 500),
                    message = error?.optString("message")?.takeIf(String::isNotBlank) ?: "Recommendation request failed ($status)",
                )
            }
            if (continuation.isActive) continuation.resume(response)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(
                if (error is RecommendationAiClientException) error else RecommendationAiClientException(message = "Network error", cause = error),
            )
        } finally { connection?.disconnect() }
    }
}

data class V3RecommendationAnchor(val tmdbId: Int?, val title: String, val mediaType: String) {
    fun toJson() = JSONObject().apply { tmdbId?.let { put("tmdbId", it) }; put("title", title); put("mediaType", mediaType) }
}

data class V3RecommendationFilters(
    val minimumYear: Int? = null,
    val maximumYear: Int? = null,
    val originalLanguage: String? = null,
    val originCountries: List<String> = emptyList(),
    val minimumRuntimeMinutes: Int? = null,
    val maximumRuntimeMinutes: Int? = null,
    val includedGenres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val minimumTmdbRating: Double? = null,
    val excludedTmdbIds: List<Int> = emptyList(),
    val excludedTitles: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        minimumYear?.let { put("minimumYear", it) }; maximumYear?.let { put("maximumYear", it) }
        originalLanguage?.let { put("originalLanguage", it) }; put("originCountries", JSONArray(originCountries))
        minimumRuntimeMinutes?.let { put("minimumRuntimeMinutes", it) }; maximumRuntimeMinutes?.let { put("maximumRuntimeMinutes", it) }
        put("includedGenres", JSONArray(includedGenres)); put("excludedGenres", JSONArray(excludedGenres))
        minimumTmdbRating?.let { put("minimumTmdbRating", it) }; put("excludedTmdbIds", JSONArray(excludedTmdbIds)); put("excludedTitles", JSONArray(excludedTitles))
    }
}

data class V3RecommendationRequest(
    val requestId: String,
    val mode: String = "describe",
    val query: String,
    val mediaType: String,
    val anchor: V3RecommendationAnchor? = null,
    val filters: V3RecommendationFilters = V3RecommendationFilters(),
    val pageSize: Int = 20,
    val cursor: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId); put("mode", mode); put("query", query); put("mediaType", mediaType)
        anchor?.let { put("anchor", it.toJson()) }; put("filters", filters.toJson()); put("pageSize", pageSize); cursor?.let { put("cursor", it) }
    }
}

data class V3RecommendationResponse(val requestId: String, val results: List<V3RecommendationResult>, val nextCursor: String?, val hasMore: Boolean) {
    companion object {
        fun fromJson(json: JSONObject) = V3RecommendationResponse(
            requestId = json.optString("requestId"),
            results = json.optJSONArray("results").toStringList { V3RecommendationResult.fromJson(it) },
            nextCursor = json.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" },
            hasMore = json.optBoolean("hasMore"),
        )
    }
}

data class V3RecommendationResult(
    val tmdbId: Int, val mediaType: String, val title: String, val originalTitle: String?, val overview: String?,
    val posterPath: String?, val backdropPath: String?, val releaseDate: String?, val genres: List<String>, val runtimeMinutes: Int?,
    val originalLanguage: String?, val originCountries: List<String>, val tmdbRating: Double?, val tmdbVoteCount: Int?,
    val matchLevel: String, val finalScore: Double, val matchReasons: List<String>, val retrievalSources: List<String>,
) {
    companion object {
        fun fromJson(json: JSONObject) = V3RecommendationResult(
            tmdbId = json.getInt("tmdbId"), mediaType = json.getString("mediaType"), title = json.getString("title"),
            originalTitle = json.nullableString("originalTitle"), overview = json.nullableString("overview"), posterPath = json.nullableString("posterPath"),
            backdropPath = json.nullableString("backdropPath"), releaseDate = json.nullableString("releaseDate"), genres = json.optJSONArray("genres").stringValues(),
            runtimeMinutes = json.optInt("runtimeMinutes").takeIf { json.has("runtimeMinutes") && !json.isNull("runtimeMinutes") },
            originalLanguage = json.nullableString("originalLanguage"), originCountries = json.optJSONArray("originCountries").stringValues(),
            tmdbRating = json.optDouble("tmdbRating").takeIf { json.has("tmdbRating") && !json.isNull("tmdbRating") },
            tmdbVoteCount = json.optInt("tmdbVoteCount").takeIf { json.has("tmdbVoteCount") && !json.isNull("tmdbVoteCount") },
            matchLevel = json.optString("matchLevel", "Relevant"), finalScore = json.optDouble("finalScore"),
            matchReasons = json.optJSONArray("matchReasons").stringValues(), retrievalSources = json.optJSONArray("retrievalSources").stringValues(),
        )
    }
}

private fun JSONObject.nullableString(name: String) = optString(name).takeIf { has(name) && !isNull(name) && it.isNotBlank() && it != "null" }
private fun JSONArray?.stringValues(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
private fun <T> JSONArray?.toStringList(transform: (JSONObject) -> T): List<T> = if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }
