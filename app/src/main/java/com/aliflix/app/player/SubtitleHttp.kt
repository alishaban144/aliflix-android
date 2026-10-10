package com.aliflix.app.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun readMobileSubtitleBytes(url: String,
    connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
): ByteArray = suspendCancellableCoroutine { continuation ->
    val active = AtomicReference<HttpURLConnection?>()
    continuation.invokeOnCancellation {
        active.getAndSet(null)?.let { connection ->
            Dispatchers.IO.dispatch(continuation.context, Runnable { connection.disconnect() })
        }
    }
    Dispatchers.IO.dispatch(continuation.context, Runnable {
        try {
            var target = url
            for (redirect in 0..5) {
                if (!continuation.isActive) return@Runnable
                val connection = connectionFactory(target).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "application/json, application/zip, text/plain, text/vtt, application/octet-stream, */*")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                active.set(connection)
                try {
                    if (!continuation.isActive) return@Runnable
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                        if (!location.isNullOrBlank()) {
                            target = URL(URL(target), location).toString()
                            continue
                        }
                    }
                    if (status !in 200..299) throw SubtitleException("Subtitles unavailable ($status)")
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (continuation.isActive) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 8 * 1024 * 1024) throw SubtitleException("Subtitle file is too large")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                    return@Runnable
                } finally {
                    active.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
            throw SubtitleException("Too many redirects downloading subtitle")
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    })
}
