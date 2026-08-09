package com.aliflix.app.recommendation

import kotlinx.coroutines.CancellationException
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
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class RecommendationAiClient(
    private val baseUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun getRecommendations(request: V3RecommendationRequest): V3RecommendationResponse =
        withContext(ioDispatcher) {
            val jsonReq = request.toJson()
            val responseText = postJson("$baseUrl/v3/recommendations", jsonReq)
            V3RecommendationResponse.fromJson(JSONObject(responseText))
        }

    private suspend fun postJson(url: String, jsonBody: JSONObject): String =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 40_000 // Complex recommendations may take some time
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                
                continuation.invokeOnCancellation { connection.disconnect() }

                val payload = jsonBody.toString().toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(payload) }

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                
                val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                
                if (status == 429) {
                    throw RecommendationAiClientException("Rate limit exceeded")
                }
                if (status == 503) {
                    throw RecommendationAiClientException("TMDB service error: $response")
                }
                if (status !in 200..299) {
                    throw RecommendationAiClientException("Worker request failed ($status): $response")
                }
                
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        if (error is RecommendationAiClientException) error else RecommendationAiClientException("Network error", error)
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }
}

// Models
data class V3RecommendationRequest(
    val requestId: String,
    val query: String,
    val mediaType: String, // "movie" or "tv"
    val filters: Map<String, Any> = emptyMap(),
    val pageSize: Int = 20,
    val cursor: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("query", query)
        put("mediaType", mediaType)
        put("filters", JSONObject(filters))
        put("pageSize", pageSize)
        cursor?.let { put("cursor", it) }
    }
}

data class V3RecommendationResponse(
    val requestId: String,
    val results: List<V3RecommendationResult>,
    val nextCursor: String?,
    val hasMore: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): V3RecommendationResponse {
            val resultsArr = json.optJSONArray("results") ?: JSONArray()
            val resultsList = mutableListOf<V3RecommendationResult>()
            for (i in 0 until resultsArr.length()) {
                val resObj = resultsArr.getJSONObject(i)
                resultsList.add(V3RecommendationResult.fromJson(resObj))
            }
            return V3RecommendationResponse(
                requestId = json.optString("requestId", ""),
                results = resultsList,
                nextCursor = if (json.isNull("nextCursor")) null else json.optString("nextCursor"),
                hasMore = json.optBoolean("hasMore", false)
            )
        }
    }
}

data class V3RecommendationResult(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val genres: List<String>,
    val voteAverage: Double?,
    val voteCount: Int?,
    val matchTier: String,
    val finalScore: Double,
    val retrievalSources: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): V3RecommendationResult {
            val genresArr = json.optJSONArray("genres") ?: JSONArray()
            val genresList = mutableListOf<String>()
            for (i in 0 until genresArr.length()) {
                genresList.add(genresArr.getString(i))
            }
            
            val sourcesArr = json.optJSONArray("retrievalSources") ?: JSONArray()
            val sourcesList = mutableListOf<String>()
            for (i in 0 until sourcesArr.length()) {
                sourcesList.add(sourcesArr.getString(i))
            }

            return V3RecommendationResult(
                tmdbId = json.getInt("tmdbId"),
                mediaType = json.optString("mediaType", "movie"),
                title = json.getString("title"),
                originalTitle = if (json.isNull("originalTitle")) null else json.optString("originalTitle"),
                overview = if (json.isNull("overview")) null else json.optString("overview"),
                posterPath = if (json.isNull("posterPath")) null else json.optString("posterPath"),
                backdropPath = if (json.isNull("backdropPath")) null else json.optString("backdropPath"),
                releaseDate = if (json.isNull("releaseDate")) null else json.optString("releaseDate"),
                genres = genresList,
                voteAverage = if (json.isNull("voteAverage")) null else json.optDouble("voteAverage"),
                voteCount = if (json.isNull("voteCount")) null else json.optInt("voteCount"),
                matchTier = json.optString("matchTier", "D"),
                finalScore = json.optDouble("finalScore", 0.0),
                retrievalSources = sourcesList
            )
        }
    }
}
