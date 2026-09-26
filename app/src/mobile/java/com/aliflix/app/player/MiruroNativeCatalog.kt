package com.aliflix.app.player

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Uses Miruro's own catalogue API in its browser origin, then hands HLS/MP4 to Media3.
 * No browser video, local history, progress, or account state owns native playback. */
internal class MiruroNativeCatalog(
    private val activity: ComponentActivity,
    private val host: FrameLayout,
) : AutoCloseable {
    private var browser: WebView? = null
    private var reply: CompletableDeferred<JSONObject>? = null
    private var requestId = 0

    suspend fun resolve(
        selection: PlaybackSelection, positionMs: Long, excluded: Set<String>, preferred: String?,
        strict: Boolean, onServers: (List<String>) -> Unit, onServer: (String) -> Unit, validate: Boolean,
    ): NativePlaybackRequest {
        val mapped = AniListEpisodeMapping.map(activity, selection)
        open()
        val config = api("config")
        val streaming = config.optJSONObject("streaming") ?: JSONObject()
        val response = api("episodes", JSONObject().put("anilistId", mapped.first))
        val rawProviders = response.optJSONObject("providers") ?: throw NoNativeServersException()
        val providers = JSONObject().apply { rawProviders.keys().forEach { put(it.lowercase(), rawProviders.get(it)) } }
        data class Candidate(val provider: String, val category: String, val id: String) {
            val label get() = "Miruro / $provider / $category"
        }
        val order = config.optJSONArray("providerOrder").strings()
        val candidates = (order + providers.keys().asSequence().toList()).distinct().sortedBy { order.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            .flatMap { provider ->
                val settings = streaming.optJSONObject(provider)
                if (settings?.optBoolean("visible", true) == false || settings?.optString("player") == "iframe") return@flatMap emptyList()
                val episodes = (providers.optJSONObject(provider)?.optJSONObject("episodes")?.takeIf { (it.optJSONArray("sub")?.length() ?: 0) > 0 }
                    ?: providers.optJSONObject(settings?.optString("parent").orEmpty())?.optJSONObject("episodes"))
                    ?: return@flatMap emptyList()
                // Japanese audio with soft subtitles first; hard-subbed Japanese audio next.
                val categories = (settings?.optJSONArray("variantOrder").strings() + listOf("ssub", "sub")).distinct()
                    .filter { it == "ssub" || it == "sub" }
                categories.mapNotNull { category ->
                    val list = episodes.optJSONArray(category) ?: if (category == "ssub") episodes.optJSONArray("sub") else null
                    val episode = list.objects().firstOrNull { it.optDouble("number", -1.0) == mapped.second.toDouble() }
                    val id = episode?.optString("id").orEmpty()
                    if (id.isBlank() || settings?.optJSONObject("capabilities")?.optBoolean(category, true) == false) null
                    else Candidate(provider, category, id)
                }
            }.distinctBy { it.label }
        onServers(candidates.map { it.label })
        val ordered = candidates.filter { it.label !in excluded && (!strict || it.label == preferred || preferred?.startsWith(it.label + " / ") == true) }
            .sortedBy { if (it.label == preferred || preferred?.startsWith(it.label + " / ") == true) 0 else 1 }
        // A resolved stream is never discarded just because the quick health check did not pass.
        // The player, its own readiness gate and the surrounding retry loop decide the outcome.
        var unverified: NativePlaybackRequest? = null
        for (candidate in ordered) {
            currentCoroutineContext().ensureActive()
            onServer(candidate.label)
            try {
                val sources = withTimeout(10_000) {
                    api("sources", JSONObject().put("anilistId", mapped.first).put("episodeId", candidate.id)
                        .put("provider", candidate.provider).put("category", candidate.category))
                }
                val streams = sources.optJSONArray("streams").objects().filter {
                    it.optString("type").lowercase() in setOf("hls", "mp4", "video/mp4", "application/x-mpegurl", "application/vnd.apple.mpegurl") &&
                        isNativeStreamUrl(it.optString("url"))
                }
                val labeled = streams.mapIndexed { index, stream ->
                    val suffix = stream.optString("server").ifBlank { stream.optString("quality").ifBlank { "Stream ${index + 1}" } }
                    (candidate.label + " / " + suffix) to stream
                }.distinctBy { it.first }
                onServers(candidates.map { it.label } + labeled.map { it.first })
                for ((label, stream) in labeled.sortedBy { if (it.first == preferred) 0 else 1 }) {
                    if (label in excluded || (strict && preferred != candidate.label && preferred != label)) continue
                    onServer(label)
                    val hls = stream.optString("type").lowercase() != "mp4" && stream.optString("type") != "video/mp4"
                    val url = stream.getString("url")
                    val referer = stream.optString("referer").takeIf(::isNativeStreamUrl) ?: BASE
                    val request = NativePlaybackRequest(url, if (hls) "application/x-mpegURL" else "video/mp4", referer,
                        checkNotNull(browser).settings.userAgentString, CookieManager.getInstance().getCookie(url).orEmpty(),
                        selection.media.title, positionMs, true, selectionJson = selection.nativeJson(),
                        streamUrlRules = if (hls) streaming.optJSONObject(candidate.provider)?.optJSONObject("hls")?.toString().orEmpty() else "")
                    val resolved = request.copy(url = request.resolveStreamUrl(url))
                    try {
                        if (validate) StartupStreamCache.awaitPlayable(activity, resolved)
                        return resolved
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (strict && preferred == label) throw error
                        unverified = resolved
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (strict) throw error
            }
        }
        return unverified ?: throw NoNativeServersException()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun open() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val view = WebView(activity)
        browser = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && request.url.host !in setOf("www.miruro.tv", "miruro.tv")
        }
        WebViewCompat.addWebMessageListener(view, "AliflixMiruro", setOf("https://www.miruro.tv")) { _, message, origin, main, _ ->
            if (!main || origin.host != "www.miruro.tv") return@addWebMessageListener
            val data = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
            if (data.optInt("id") == requestId) reply?.complete(data)
        }
        host.addView(view, FrameLayout.LayoutParams(1, 1))
        view.loadUrl(BASE)
        withTimeout(12_000) {
            while (true) {
                val ready = CompletableDeferred<Boolean>()
                view.evaluateJavascript("Boolean(window.env && window.env.VITE_PIPE_OBF_KEY)") { ready.complete(it == "true") }
                if (ready.await()) break
                delay(200)
            }
        }
    }

    private suspend fun api(path: String, query: JSONObject = JSONObject()): JSONObject {
        val id = ++requestId
        val pending = CompletableDeferred<JSONObject>()
        reply = pending
        val payload = JSONObject().put("path", path).put("method", "GET").put("query", query).put("body", JSONObject.NULL)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        checkNotNull(browser).evaluateJavascript("""
            (async () => {
              try {
                const envelope = JSON.parse(atob('$encoded'.replace(/-/g, '+').replace(/_/g, '/')));
                try { envelope.version = JSON.parse(localStorage.getItem('miruro:cache:jwks'))?.jwk?.version; } catch (_) {}
                const encoded = btoa(JSON.stringify(envelope)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                const response = await fetch('/api/secure/pipe?e=' + encoded);
                if (!response.ok) throw new Error('Miruro HTTP ' + response.status);
                const text = await response.text();
                if (text.length > 8000000) throw new Error('Miruro response too large');
                AliflixMiruro.postMessage(JSON.stringify({id:$id, text,
                  obfuscated:response.headers.get('x-obfuscated'), key:window.env.VITE_PIPE_OBF_KEY}));
              } catch (e) { AliflixMiruro.postMessage(JSON.stringify({id:$id,error:String(e)})); }
            })();
        """.trimIndent(), null)
        val result = try { withTimeout(10_000) { pending.await() } } finally { reply = null }
        check(!result.has("error")) { result.optString("error") }
        return withContext(Dispatchers.Default) {
            val text = result.getString("text")
            val mode = result.optString("obfuscated")
            if (mode !in setOf("1", "2")) return@withContext JSONObject(text)
            val bytes = Base64.getUrlDecoder().decode(text)
            if (mode == "2") {
                val hex = result.getString("key")
                require(hex.matches(Regex("(?:[0-9a-fA-F]{2})+")))
                val key = hex.chunked(2).map { it.toInt(16) }
                bytes.indices.forEach { index -> bytes[index] = (bytes[index].toInt() xor key[index % key.size]).toByte() }
            }
            val inputBytes = ByteArrayInputStream(bytes)
            val first = bytes.getOrNull(0)?.toInt()?.and(255) ?: 0
            val second = bytes.getOrNull(1)?.toInt()?.and(255) ?: 0
            val inflater = if (first == 31 && second == 139) null else java.util.zip.Inflater(
                !((first and 15) == 8 && (first shr 4) <= 7 && ((first shl 8) + second) % 31 == 0))
            val inputStream = if (inflater == null) GZIPInputStream(inputBytes)
                else java.util.zip.InflaterInputStream(inputBytes, inflater)
            val decoded = try { inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8_000_000)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } } finally { inflater?.end() }
            require(decoded.size <= 8_000_000)
            JSONObject(String(decoded, Charsets.UTF_8))
        }
    }

    override fun close() {
        reply?.cancel(); reply = null
        browser?.let { view ->
            host.removeView(view)
            WebViewCompat.removeWebMessageListener(view, "AliflixMiruro")
            view.stopLoading(); view.destroy()
        }
        browser = null
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

    private companion object {
        const val BASE = "https://www.miruro.tv/"
    }
}

