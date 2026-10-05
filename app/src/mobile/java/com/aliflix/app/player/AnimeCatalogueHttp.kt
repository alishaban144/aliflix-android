package com.aliflix.app.player

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A losing catalogue request closes immediately, so the winning video's
 * startup never waits for a blocked mirror's read timeout. No cookies/accounts.
 */
internal object AnimeCatalogueHttp {
    data class Response(val bytes: ByteArray, val contentType: String)
    private val executor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "aliflix-anime-catalogue").apply { isDaemon = true }
    }
    suspend fun get(url: String, userAgent: String, referer: String): Response {
        try { return request(url, userAgent, referer) }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!animeCatalogueShouldRetry(error)) throw error
            // One bounded retry recovers a transient catalogue failure without
            // discarding every server or asking the viewer to start again.
            android.util.Log.d("AliflixAnime", "catalogue_retry:${error.javaClass.simpleName}:${(error as? AnimeCatalogueHttpException)?.status ?: 0}")
            delay(200)
            return request(url, userAgent, referer)
        }
    }
    private suspend fun request(url: String, userAgent: String, referer: String): Response = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 8000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", referer)
            setRequestProperty("User-Agent", userAgent)
        }
        val task = executor.submit {
            try {
                if (!continuation.isActive) return@submit
                val status = connection.responseCode
                if (status !in 200..299) throw AnimeCatalogueHttpException(status)
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk); if (count < 0) break
                        check(output.size() + count <= 8_000_000) { "Anime catalogue response exceeds limit" }
                        output.write(chunk, 0, count)
                    }
                }
                continuation.resume(Response(output.toByteArray(), connection.contentType.orEmpty()))
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { task.cancel(true); connection.disconnect() }
    }
}

internal class AnimeCatalogueHttpException(val status: Int) : IOException("Anime catalogue HTTP $status")
internal fun animeCatalogueShouldRetry(error: Exception): Boolean = when (error) {
    is AnimeCatalogueHttpException -> error.status == 408 || error.status == 429 || error.status in 500..599
    is IOException -> true
    else -> false
}
