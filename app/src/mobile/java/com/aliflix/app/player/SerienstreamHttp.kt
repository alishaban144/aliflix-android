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
            require(isProviderSiteRedirect(target) || sameProviderOrigin(URI(next),target)) { "Unexpected provider redirect: ${target.host}, path segments=${target.path.split('/').filter { it.isNotBlank() }.map { it.length }}" }
            next = target.toString()
        }
        error("Too many provider redirects")
    }
    private data class Response(val bytes: ByteArray, val type: String, val redirect: String? = null)
    suspend fun post(url: String, referer: String, body: String, json: Boolean = false, csrf: String? = null): String {
        require(URI(url).scheme == "https" && URI(url).host == URI(referer).host)
        return String(request(url, referer, body, json, csrf).bytes, Charsets.UTF_8)
    }
    private suspend fun request(url: String, referer: String, body: String? = null, json: Boolean = false, csrf: String? = null): Response = suspendCancellableCoroutine { continuation ->
        val cookies = CookieManager.getInstance()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 4_000; readTimeout = 6_000
            setRequestProperty("User-Agent", SerienstreamNativeCatalog.USER_AGENT)
            setRequestProperty("Referer", referer)
            if (body != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Origin", "${URI(url).scheme}://${URI(url).host}")
                csrf?.let { setRequestProperty("X-CSRF-TOKEN", it) }
            }
            cookies.getCookie(url)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        val task = executor.submit {
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach {
                    cookies.setCookie(url, it)
                }
                // Retain the provider's own expiry; never extend or manufacture clearance.
                if (connection.headerFields.keys.any { it?.equals("Set-Cookie", true) == true }) cookies.flush()
                if (status in 300..399) continuation.resume(Response(byteArrayOf(), "", connection.getHeaderField("Location") ?: error("Missing redirect")))
                else {
                    if(status !in 200..299)throw ProviderSiteHttpException(status,URI(url).host)
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

internal class ProviderSiteHttpException(val status:Int,host:String):java.io.IOException("Provider HTTP $status on $host")

internal fun sameProviderOrigin(from:URI,to:URI):Boolean = to.scheme=="https" && from.scheme=="https" &&
    to.userInfo==null && from.userInfo==null && to.port in setOf(-1,443) && from.port in setOf(-1,443) &&
    !from.host.isNullOrBlank() && from.host.equals(to.host,true)

/** Video hosts use both public file documents and tokenized player routes.
 * Authorize the inspected HTTPS origins instead of guessing their URL paths;
 * sessions stay origin-scoped and unrelated redirect destinations remain closed. */
internal fun isProviderSiteRedirect(target:URI):Boolean = target.scheme=="https" && target.userInfo==null &&
    target.port in setOf(-1,443) && target.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.") in
        setOf("serienstream.to","serienstream.cx","filmo.to","aniworld.to","voe.sx","megankiddifference.com")
