package com.aliflix.app.data.omdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OmdbMetadataClientException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class OmdbMetadataClient(
    private val baseUrl: String,
    private val cacheStore: OmdbCacheStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val inFlightMap = ConcurrentHashMap<String, Deferred<OmdbTitleMetadata?>>()

    private fun cacheKey(req: OmdbLookupRequest): String {
        req.imdbId?.takeIf { it.isNotBlank() }?.let { return "id:${it.lowercase()}" }
        val normTitle = req.title?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim() ?: "unknown"
        val yearStr = req.year?.toString() ?: "any"
        return "${req.mediaType.lowercase()}:$normTitle:$yearStr"
    }

    suspend fun lookup(req: OmdbLookupRequest): OmdbTitleMetadata? = withContext(ioDispatcher) {
        val key = cacheKey(req)

        // 1. Check local Android cache
        cacheStore?.get(key)?.let { entry ->
            return@withContext entry.metadata
        }

        // 2. Coalesce in-flight requests for the same key
        coroutineScope {
            val deferred = inFlightMap.computeIfAbsent(key) {
                async(ioDispatcher) {
                    try {
                        val result = fetchSingleFromWorker(req)
                        if (result != null && result.found) {
                            OmdbDiagnostics.omdbVerified.incrementAndGet()
                            cacheStore?.put(key, "VERIFIED", result)
                            if (result.imdbId != null && key != "id:${result.imdbId.lowercase()}") {
                                cacheStore?.put("id:${result.imdbId.lowercase()}", "VERIFIED", result)
                            }
                            result
                        } else {
                            OmdbDiagnostics.omdbNotFound.incrementAndGet()
                            cacheStore?.put(key, "NOT_FOUND", null)
                            null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        if (e is OmdbMetadataClientException && e.message?.contains("503") == true) {
                            OmdbDiagnostics.omdbQuotaFailures.incrementAndGet()
                        } else {
                            OmdbDiagnostics.omdbTimeouts.incrementAndGet()
                        }
                        null
                    } finally {
                        inFlightMap.remove(key)
                    }
                }
            }
            deferred.await()
        }
    }

    suspend fun lookupBatch(candidates: List<OmdbLookupRequest>): Map<String, OmdbTitleMetadata?> =
        withContext(ioDispatcher) {
            if (candidates.isEmpty()) return@withContext emptyMap()
            val resultMap = ConcurrentHashMap<String, OmdbTitleMetadata?>()
            val toFetch = mutableListOf<OmdbLookupRequest>()

            // Check cache for each candidate
            candidates.forEach { cand ->
                val cid = cand.candidateId ?: return@forEach
                val key = cacheKey(cand)
                val cached = cacheStore?.get(key)
                if (cached != null) {
                    resultMap[cid] = cached.metadata
                } else {
                    toFetch.add(cand)
                }
            }

            if (toFetch.isEmpty()) return@withContext resultMap

            // Batch fetch up to 25 at a time
            val chunked = toFetch.chunked(25)
            for (chunk in chunked) {
                try {
                    OmdbDiagnostics.omdbBatchCalls.incrementAndGet()
                    OmdbDiagnostics.omdbCandidatesRequested.addAndGet(chunk.size)
                    val response = fetchBatchFromWorker(chunk)
                    response.results.forEach { item ->
                        resultMap[item.candidateId] = item.metadata
                        val req = chunk.firstOrNull { it.candidateId == item.candidateId }
                        if (req != null) {
                            val key = cacheKey(req)
                            if (item.status == "VERIFIED" && item.metadata != null) {
                                cacheStore?.put(key, "VERIFIED", item.metadata)
                                item.metadata.imdbId?.let { iid ->
                                    cacheStore?.put("id:${iid.lowercase()}", "VERIFIED", item.metadata)
                                }
                            } else if (item.status == "NOT_FOUND") {
                                cacheStore?.put(key, "NOT_FOUND", null)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Ignore chunk failures, individual candidates fall back safely
                }
            }

            resultMap
        }

    private suspend fun fetchSingleFromWorker(req: OmdbLookupRequest): OmdbTitleMetadata? =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/v1/metadata/omdb")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                continuation.invokeOnCancellation { connection.disconnect() }

                val payload = req.toJson().toString().toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(payload) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (status in 200..299) {
                    val json = JSONObject(responseText)
                    val metadata = OmdbTitleMetadata.fromJson(json)
                    if (continuation.isActive) continuation.resume(metadata)
                } else {
                    if (continuation.isActive) continuation.resume(null)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    if (error is CancellationException) {
                        continuation.resumeWithException(error)
                    } else {
                        continuation.resume(null)
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }

    private suspend fun fetchBatchFromWorker(chunk: List<OmdbLookupRequest>): OmdbBatchResponse =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/v1/metadata/omdb/batch")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                continuation.invokeOnCancellation { connection.disconnect() }

                val reqObj = JSONObject().apply {
                    put("titles", JSONArray(chunk.map { it.toJson() }))
                }
                val payload = reqObj.toString().toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(payload) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (status in 200..299) {
                    val json = JSONObject(responseText)
                    val batchResponse = OmdbBatchResponse.fromJson(json)
                    if (continuation.isActive) continuation.resume(batchResponse)
                } else {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            OmdbMetadataClientException("Batch lookup failed with HTTP $status")
                        )
                    }
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            } finally {
                connection?.disconnect()
            }
        }
}
