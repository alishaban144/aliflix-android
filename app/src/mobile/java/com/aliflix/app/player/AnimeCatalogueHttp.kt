package com.aliflix.app.player

import kotlinx.coroutines.suspendCancellableCoroutine
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
    suspend fun get(url: String, userAgent: String, referer: String): Response = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 8000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", referer)
            setRequestProperty("User-Agent", userAgent)
        }
        val task = executor.submit {
            try {
                if (!continuation.isActive) return@submit
                check(connection.responseCode in 200..299) { "Anime catalogue HTTP ${connection.responseCode}" }
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
