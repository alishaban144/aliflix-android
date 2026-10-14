package com.aliflix.app.player

import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Provider cookies stay on their origin; redirects never forward a site's session to a video host. */
internal object SerienstreamHttp {
    private val executor = Executors.newFixedThreadPool(3) { Thread(it, "serienstream-http").apply { isDaemon = true } }
    fun persistSession() { executor.execute { CookieManager.getInstance().flush() } }
    suspend fun get(url: String, referer: String): AnimeCatalogueHttp.Response {
        var next = url
        repeat(5) {
            val response = request(next, referer)
            if (response.redirect == null) return AnimeCatalogueHttp.Response(response.bytes, response.type, next)
            val target = URI(next).resolve(response.redirect)
            require(target.scheme == "https" && target.userInfo == null && target.host != null)
            require(target.host in setOf("serienstream.to", "serienstream.cx") || target.path.matches(Regex("/e/[A-Za-z0-9]+"))) { "Unexpected provider redirect" }
            next = target.toString()
        }
        error("Too many provider redirects")
    }
    private data class Response(val bytes: ByteArray, val type: String, val redirect: String? = null)
    private suspend fun request(url: String, referer: String): Response = suspendCancellableCoroutine { continuation ->
        val cookies = CookieManager.getInstance()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 4_000; readTimeout = 6_000
            setRequestProperty("User-Agent", SerienstreamNativeCatalog.USER_AGENT)
            setRequestProperty("Referer", referer)
            cookies.getCookie(url)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        val task = executor.submit {
            try {
                val status = connection.responseCode
                connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach {
                    cookies.setCookie(url, it)
                }
                // Retain the provider's own expiry; never extend or manufacture clearance.
                if (connection.headerFields.keys.any { it?.equals("Set-Cookie", true) == true }) cookies.flush()
                if (status in 300..399) continuation.resume(Response(byteArrayOf(), "", connection.getHeaderField("Location") ?: error("Missing redirect")))
                else {
                    require(status in 200..299) { "Serienstream HTTP $status" }
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 8_000_000) { "Provider response too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(Response(bytes, connection.contentType.orEmpty()))
                }
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { task.cancel(true); connection.disconnect() }
    }
}
