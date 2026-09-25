package com.aliflix.app.player

import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup

/** Uses AniKuro's public JSON catalogue over HTTPS, then hands HLS/MP4 to Media3.
 * No WebView, browser video, local history, progress, or account state owns native playback. */
internal class AniKuroNativeCatalog(private val activity: ComponentActivity) {

    suspend fun resolve(
        selection: PlaybackSelection, positionMs: Long, excluded: Set<String>, preferred: String?,
        strict: Boolean, onServers: (List<String>) -> Unit, onServer: (String) -> Unit, validate: Boolean,
    ): NativePlaybackRequest {
        val mapped = AniListEpisodeMapping.map(activity, selection)
        val candidates = withTimeout(CATALOGUE_TIMEOUT_MS) { candidates(mapped.first, mapped.second) }
        if (candidates.isEmpty()) throw NoNativeServersException()
        onServers(candidates.map { it.label })
        val ordered = candidates.filter { it.label !in excluded && (!strict || it.label == preferred || preferred?.startsWith(it.label + " / ") == true) }
            .sortedBy { if (it.label == preferred || preferred?.startsWith(it.label + " / ") == true) 0 else 1 }
        for (candidate in ordered) {
            currentCoroutineContext().ensureActive()
            onServer(candidate.label)
            val request = NativePlaybackRequest(
                url = candidate.source.url,
                mimeType = if (candidate.source.hls) "application/x-mpegURL" else "video/mp4",
                referer = candidate.source.referer,
                userAgent = USER_AGENT,
                cookie = CookieManager.getInstance().getCookie(candidate.source.url).orEmpty(),
                title = selection.media.title,
                positionMs = positionMs,
                playing = true,
                selectionJson = selection.nativeJson(),
            )
            try {
                if (validate) StartupStreamCache.awaitPlayable(activity, request)
                return request
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (strict && preferred == candidate.label) throw error
            }
        }
        throw NoNativeServersException()
    }

    private suspend fun candidates(anilistId: Int, episode: Int): List<Candidate> {
        val primary = animePowerCandidates(anilistId, episode)
        if (primary.isNotEmpty()) return primary
        // The website races the remaining mirrors only when animepower has nothing for this episode.
        return try {
            withTimeout(SECONDARY_TIMEOUT_MS) {
                firstSuccessful(SECONDARY_PROVIDERS.map { provider -> suspend {
                    val found = toCandidates(provider, fetch("$SOURCES_PATH/$provider/$anilistId:$episode"))
                    if (found.isEmpty()) throw NoNativeServersException()
                    found
                } }, parallelism = SECONDARY_PARALLELISM)
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    private suspend fun animePowerCandidates(anilistId: Int, episode: Int): List<Candidate> {
        var dubOnly: List<Candidate> = emptyList()
        repeat(ANIMEPOWER_ATTEMPTS) {
            val variants = fetch("$ANIMEPOWER_PATH/$anilistId/$episode")
            if (variants.isEmpty()) return@repeat
            val japanese = variants.filter { it.sources.isNotEmpty() && it.variant.equals("sub", true) }
            val usable = japanese.ifEmpty { variants.filter { it.sources.isNotEmpty() } }
            if (usable.isEmpty()) return@repeat
            // animepower alternates sub and dub between requests, so keep asking until Japanese audio arrives.
            if (japanese.isNotEmpty()) return toCandidates(ANIMEPOWER, japanese)
            if (dubOnly.isEmpty()) dubOnly = toCandidates(ANIMEPOWER, usable)
        }
        return dubOnly
    }

    private suspend fun fetch(path: String): List<Variant> = withContext(Dispatchers.IO) {
        val text = runCatching {
            Jsoup.connect(BASE.trimEnd('/') + path).ignoreContentType(true).timeout(HTTP_TIMEOUT_MS)
                .userAgent(USER_AGENT).header("Accept", "application/json").header("Referer", BASE)
                .method(Connection.Method.GET).execute().body()
        }.getOrNull() ?: return@withContext emptyList()
        runCatching { normalize(JSONObject(text)) }.getOrNull().orEmpty()
    }

    private fun normalize(payload: JSONObject): List<Variant> {
        val root = payload.optJSONObject("data") ?: payload
        val normalized = root.optJSONArray("normalized").objects().mapNotNull { toVariant(it, null) }
        if (normalized.isNotEmpty()) return normalized
        val raw = root.optJSONObject("raw") ?: root
        return listOf("sub", "dub").mapNotNull { key ->
            val node = raw.optJSONObject(key) ?: return@mapNotNull null
            toVariant(node, key)
        }
    }

    private fun toVariant(node: JSONObject, fallbackVariant: String?): Variant? {
        val variant = node.optString("variant").ifBlank { fallbackVariant.orEmpty() }.ifBlank { "sub" }
        val sources = sourcesOf(node)
        return if (sources.isEmpty()) null else Variant(variant, sources)
    }

    private fun sourcesOf(node: JSONObject): List<Source> {
        val variantReferer = node.optJSONObject("headers")?.optString("Referer").orEmpty()
            .takeIf(::isNativeStreamUrl).orEmpty()
        val listed = node.optJSONArray("sources").objects().mapNotNull { entry ->
            val url = entry.optString("url").ifBlank { entry.optString("default") }
            if (!isNativeStreamUrl(url)) return@mapNotNull null
            val headers = entry.optJSONObject("headers")
            val referer = headers?.optString("Referer").orEmpty().takeIf(::isNativeStreamUrl)
                ?: entry.optString("upstreamReferer").takeIf(::isNativeStreamUrl)
                ?: variantReferer
            Source(url, entry.optString("quality"), isHls(url, entry), referer)
        }
        if (listed.isNotEmpty()) return listed.distinctBy { it.url }
        val default = node.optString("default")
        return if (isNativeStreamUrl(default)) listOf(Source(default, "", true, variantReferer)) else emptyList()
    }

    private fun isHls(url: String, node: JSONObject): Boolean {
        val type = node.optString("type").lowercase()
        if (type in setOf("mp4", "video/mp4")) return false
        if (type in setOf("hls", "application/x-mpegurl", "application/vnd.apple.mpegurl")) return true
        return url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    }

    private fun toCandidates(provider: String, variants: List<Variant>): List<Candidate> =
        variants.sortedBy { if (it.variant.equals("sub", true)) 0 else 1 }
            .flatMap { variant -> variant.sources.map { source -> variant to source } }
            .mapIndexed { index, (variant, source) ->
                Candidate(provider, variant.variant, suffixOf(source.quality, index), source)
            }
            .distinctBy { it.label }

    private fun suffixOf(quality: String, index: Int): String =
        quality.trim().takeIf { RESOLUTION.matches(it) } ?: "Stream ${index + 1}"

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private data class Variant(val variant: String, val sources: List<Source>)
    private data class Source(val url: String, val quality: String, val hls: Boolean, val referer: String)
    private data class Candidate(val provider: String, val variant: String, val suffix: String, val source: Source) {
        val label get() = "$PROVIDER_NAME / $provider / $variant / $suffix"
    }

    private companion object {
        const val PROVIDER_NAME = "AniKuro"
        const val BASE = "https://anikuro.to/"
        const val ANIMEPOWER = "animepower"
        const val ANIMEPOWER_PATH = "/api/v1/animepower/video"
        const val SOURCES_PATH = "/api/v1/sources"
        const val HTTP_TIMEOUT_MS = 8_000
        const val CATALOGUE_TIMEOUT_MS = 40_000L
        const val SECONDARY_TIMEOUT_MS = 16_000L
        const val ANIMEPOWER_ATTEMPTS = 3
        const val SECONDARY_PARALLELISM = 3
        val SECONDARY_PROVIDERS = listOf("animepahe", "anikoto", "reanime", "animedao", "allanime", "animix", "senshi")
        val RESOLUTION = Regex("\\d{3,4}p?")
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
