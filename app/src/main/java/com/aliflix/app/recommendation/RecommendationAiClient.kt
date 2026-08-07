package com.aliflix.app.recommendation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    suspend fun interpretIntent(request: InterpretationRequest): InterpretationResponse =
        withContext(ioDispatcher) {
            val jsonReq = request.toJson()
            val response = postJson("$baseUrl/v1/interpret", jsonReq)
            InterpretationResponse.fromJson(JSONObject(response))
        }

    suspend fun expandSearch(request: ExpansionRequest): ExpansionResponse =
        withContext(ioDispatcher) {
            val jsonReq = request.toJson()
            val response = postJson("$baseUrl/v1/expand", jsonReq)
            ExpansionResponse.fromJson(JSONObject(response))
        }

    suspend fun verifyCandidates(request: VerificationRequest): VerificationResponse =
        withContext(ioDispatcher) {
            val jsonReq = request.toJson()
            val response = postJson("$baseUrl/v1/verify", jsonReq)
            VerificationResponse.fromJson(JSONObject(response))
        }

    private suspend fun postJson(url: String, jsonBody: JSONObject): String =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 20_000 // Worker can take up to 12s
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

data class InterpretationRequest(
    val requestId: String,
    val query: String,
    val mediaType: String,
    val deterministicConstraints: DeterministicConstraints
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("query", query)
        put("mediaType", mediaType.lowercase())
        put("deterministicConstraints", JSONObject().apply {
            put("genres", JSONArray(deterministicConstraints.genres))
            put("moods", JSONArray(deterministicConstraints.moods))
            put("themes", JSONArray(deterministicConstraints.themes))
            put("yearRule", deterministicConstraints.yearRule ?: JSONObject.NULL)
            put("runtimeRule", deterministicConstraints.runtimeRule ?: JSONObject.NULL)
            put("minimumImdb", deterministicConstraints.minimumImdb ?: JSONObject.NULL)
            put("language", deterministicConstraints.language ?: JSONObject.NULL)
            put("status", deterministicConstraints.status ?: JSONObject.NULL)
            put("exclusions", JSONArray(deterministicConstraints.exclusions))
            put("similarityTitle", deterministicConstraints.similarityTitle ?: JSONObject.NULL)
        })
    }
}

data class DeterministicConstraints(
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val yearRule: String? = null,
    val runtimeRule: String? = null,
    val minimumImdb: Double? = null,
    val language: String? = null,
    val status: String? = null,
    val exclusions: List<String> = emptyList(),
    val similarityTitle: String? = null,
)

data class RequiredConceptGroup(
    val id: String,
    val label: String,
    val description: String,
    val alternatives: List<String>,
    val specificSubtypes: List<String>,
    val centralityRequired: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("description", description)
        put("alternatives", JSONArray(alternatives))
        put("specificSubtypes", JSONArray(specificSubtypes))
        put("centralityRequired", centralityRequired)
    }

    companion object {
        fun fromJson(json: JSONObject): RequiredConceptGroup = RequiredConceptGroup(
            id = json.getString("id"),
            label = json.getString("label"),
            description = json.getString("description"),
            alternatives = json.optJSONArray("alternatives")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            specificSubtypes = json.optJSONArray("specificSubtypes")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            centralityRequired = json.optBoolean("centralityRequired", false)
        )
    }
}

data class AiHardConstraints(
    val mediaType: String,
    val includedGenres: List<String>,
    val excludedGenres: List<String>,
    val minimumYear: Int?,
    val maximumYear: Int?,
    val maximumRuntimeMinutes: Int?,
    val minimumRating: Double?,
    val originalLanguage: String?
) {
    companion object {
        fun fromJson(json: JSONObject): AiHardConstraints = AiHardConstraints(
            mediaType = json.getString("mediaType"),
            includedGenres = json.optJSONArray("includedGenres")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            excludedGenres = json.optJSONArray("excludedGenres")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            minimumYear = if (json.isNull("minimumYear")) null else json.getInt("minimumYear"),
            maximumYear = if (json.isNull("maximumYear")) null else json.getInt("maximumYear"),
            maximumRuntimeMinutes = if (json.isNull("maximumRuntimeMinutes")) null else json.getInt("maximumRuntimeMinutes"),
            minimumRating = if (json.isNull("minimumRating")) null else json.getDouble("minimumRating"),
            originalLanguage = if (json.isNull("originalLanguage")) null else json.getString("originalLanguage")
        )
    }
}

data class InterpretationResponse(
    val requestId: String,
    val normalizedRequest: String,
    val summary: String,
    val requiredConceptGroups: List<RequiredConceptGroup>,
    val optionalConcepts: List<String>,
    val excludedConcepts: List<String>,
    val genreHypotheses: List<String>,
    val keywordSearchPhrases: List<String>,
    val broadSearchPhrases: List<String>,
    val anchorTitle: String?,
    val anchorModifiers: List<String>,
    val contradictions: List<String>,
    val hardConstraints: AiHardConstraints
) {
    companion object {
        fun fromJson(json: JSONObject): InterpretationResponse = InterpretationResponse(
            requestId = json.getString("requestId"),
            normalizedRequest = json.getString("normalizedRequest"),
            summary = json.getString("summary"),
            requiredConceptGroups = json.optJSONArray("requiredConceptGroups")?.let { arr -> List(arr.length()) { RequiredConceptGroup.fromJson(arr.getJSONObject(it)) } } ?: emptyList(),
            optionalConcepts = json.optJSONArray("optionalConcepts")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            excludedConcepts = json.optJSONArray("excludedConcepts")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            genreHypotheses = json.optJSONArray("genreHypotheses")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            keywordSearchPhrases = json.optJSONArray("keywordSearchPhrases")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            broadSearchPhrases = json.optJSONArray("broadSearchPhrases")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            anchorTitle = if (json.isNull("anchorTitle")) null else json.getString("anchorTitle"),
            anchorModifiers = json.optJSONArray("anchorModifiers")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            contradictions = json.optJSONArray("contradictions")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            hardConstraints = json.optJSONObject("hardConstraints")?.let { AiHardConstraints.fromJson(it) } ?: AiHardConstraints(
                mediaType = "movie",
                includedGenres = emptyList(),
                excludedGenres = emptyList(),
                minimumYear = null,
                maximumYear = null,
                maximumRuntimeMinutes = null,
                minimumRating = null,
                originalLanguage = null
            )
        )
    }
}

data class ExpansionRequest(
    val requestId: String,
    val originalQuery: String,
    val interpretation: JSONObject,
    val coveredConcepts: List<String>,
    val underrepresentedConcepts: List<String>,
    val successfulSearchPhrases: List<String>,
    val failedSearchPhrases: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("originalQuery", originalQuery)
        put("interpretation", interpretation)
        put("coveredConcepts", JSONArray(coveredConcepts))
        put("underrepresentedConcepts", JSONArray(underrepresentedConcepts))
        put("successfulSearchPhrases", JSONArray(successfulSearchPhrases))
        put("failedSearchPhrases", JSONArray(failedSearchPhrases))
    }
}

data class PairwiseSearch(val leftConcept: String, val rightConcept: String) {
    companion object {
        fun fromJson(json: JSONObject): PairwiseSearch = PairwiseSearch(
            leftConcept = json.getString("leftConcept"),
            rightConcept = json.getString("rightConcept")
        )
    }
}

data class ExpansionResponse(
    val requestId: String,
    val additionalKeywordPhrases: List<String>,
    val additionalPairwiseSearches: List<PairwiseSearch>,
    val additionalBroadPhrases: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): ExpansionResponse = ExpansionResponse(
            requestId = json.getString("requestId"),
            additionalKeywordPhrases = json.optJSONArray("additionalKeywordPhrases")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            additionalPairwiseSearches = json.optJSONArray("additionalPairwiseSearches")?.let { arr -> List(arr.length()) { PairwiseSearch.fromJson(arr.getJSONObject(it)) } } ?: emptyList(),
            additionalBroadPhrases = json.optJSONArray("additionalBroadPhrases")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        )
    }
}

data class VerificationCandidate(
    val candidateId: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val genres: List<String>,
    val keywords: List<String>,
    val releaseYear: Int?,
    val directorOrCreators: List<String>,
    val principalCast: List<String>,
    val omdbVerified: Boolean = false,
    val omdbGenres: List<String> = emptyList(),
    val fullPlot: String? = null,
    val runtimeMinutes: Int? = null,
    val imdbRating: Double? = null,
    val imdbVotes: Int? = null,
    val rottenTomatoesRating: Int? = null,
    val metascore: Int? = null,
    val director: String? = null,
    val actors: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("candidateId", candidateId)
        put("tmdbId", tmdbId)
        put("mediaType", mediaType.lowercase())
        put("title", title)
        put("originalTitle", originalTitle)
        put("overview", overview)
        put("genres", JSONArray(genres))
        put("keywords", JSONArray(keywords))
        put("releaseYear", releaseYear ?: JSONObject.NULL)
        put("directorOrCreators", JSONArray(directorOrCreators))
        put("principalCast", JSONArray(principalCast))
        put("omdbVerified", omdbVerified)
        put("omdbGenres", JSONArray(omdbGenres))
        fullPlot?.let { put("fullPlot", it) }
        runtimeMinutes?.let { put("runtimeMinutes", it) }
        imdbRating?.let { put("imdbRating", it) }
        imdbVotes?.let { put("imdbVotes", it) }
        rottenTomatoesRating?.let { put("rottenTomatoesRating", it) }
        metascore?.let { put("metascore", it) }
        director?.let { put("director", it) }
        put("actors", JSONArray(actors))
    }
}

data class VerificationRequest(
    val requestId: String,
    val originalQuery: String,
    val mediaType: String,
    val requiredConceptGroups: List<RequiredConceptGroup>,
    val excludedConcepts: List<String>,
    val hardConstraints: JSONObject,
    val candidates: List<VerificationCandidate>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("originalQuery", originalQuery)
        put("mediaType", mediaType.lowercase())
        put("requiredConceptGroups", JSONArray(requiredConceptGroups.map { it.toJson() }))
        put("excludedConcepts", JSONArray(excludedConcepts))
        put("hardConstraints", hardConstraints)
        put("candidates", JSONArray(candidates.map { it.toJson() }))
    }
}

/**
 * The decision values that the Cloudflare verification Worker returns.
 *
 * Android previously compared against the nonexistent value "ACCEPT",
 * which silently rejected every candidate.  This typed enum makes the
 * contract explicit.
 */
enum class VerificationDecision {
    DEFINITE_MATCH,
    PROBABLE_MATCH,
    INSUFFICIENT_EVIDENCE,
    REJECT;

    /** True when the candidate should be accepted into the ranking pool. */
    fun isAccepted(confidence: Double): Boolean = when (this) {
        DEFINITE_MATCH -> true
        PROBABLE_MATCH -> confidence >= 0.70
        INSUFFICIENT_EVIDENCE -> false
        REJECT -> false
    }

    companion object {
        fun parse(raw: String): VerificationDecision =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: REJECT
    }
}

data class VerifiedConceptEvidence(
    val groupId: String,
    val status: String,
    val evidence: String
) {
    companion object {
        fun fromJson(json: JSONObject): VerifiedConceptEvidence = VerifiedConceptEvidence(
            groupId = json.getString("groupId"),
            status = json.getString("status"),
            evidence = json.getString("evidence")
        )
    }
}

data class VerificationResult(
    val candidateId: String,
    val decision: VerificationDecision,
    val confidence: Double,
    val centralityScore: Double,
    val requiredGroupAssessments: List<VerifiedConceptEvidence>,
    val matchedConcepts: List<String>,
    val evidenceSummary: String,
    val rejectionReason: String?
) {
    companion object {
        fun fromJson(json: JSONObject): VerificationResult = VerificationResult(
            candidateId = json.getString("candidateId"),
            decision = VerificationDecision.parse(json.getString("decision")),
            confidence = json.getDouble("confidence"),
            centralityScore = json.getDouble("centralityScore"),
            requiredGroupAssessments = json.optJSONArray("requiredGroupAssessments")?.let { arr -> List(arr.length()) { VerifiedConceptEvidence.fromJson(arr.getJSONObject(it)) } } ?: emptyList(),
            matchedConcepts = json.optJSONArray("matchedConcepts")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            evidenceSummary = json.getString("evidenceSummary"),
            rejectionReason = if (json.isNull("rejectionReason")) null else json.getString("rejectionReason")
        )
    }
}

data class VerificationResponse(
    val requestId: String,
    val results: List<VerificationResult>
) {
    companion object {
        fun fromJson(json: JSONObject): VerificationResponse = VerificationResponse(
            requestId = json.getString("requestId"),
            results = json.optJSONArray("results")?.let { arr -> List(arr.length()) { VerificationResult.fromJson(arr.getJSONObject(it)) } } ?: emptyList()
        )
    }
}
