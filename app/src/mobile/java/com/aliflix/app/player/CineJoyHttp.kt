package com.aliflix.app.player

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancelling a losing server closes its socket instead of delaying the winning stream. */
internal object CineJoyHttp {
    private val executor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "cinejoy-catalogue").apply { isDaemon = true }
    }

    suspend fun request(url: String, userAgent: String, body: ByteArray? = null, json: Boolean = false): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Origin", CineJoyNativeCatalog.REFERER.trimEnd('/'))
                setRequestProperty("Referer", CineJoyNativeCatalog.REFERER)
                setRequestProperty("User-Agent", userAgent)
                if (json) setRequestProperty("Content-Type", "application/json")
                doOutput = body != null
            }
            val task = executor.submit {
                try {
                    if (!continuation.isActive) return@submit
                    body?.let { connection.outputStream.use { output -> output.write(it) } }
                    val code = connection.responseCode
                    check(code in 200..299) { "CineJoy catalogue HTTP $code" }
                    val bytes = connection.inputStream.use { it.readBytes() }
                    continuation.resume(bytes)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally {
                    connection.disconnect()
                }
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                connection.disconnect()
            }
        }
}
