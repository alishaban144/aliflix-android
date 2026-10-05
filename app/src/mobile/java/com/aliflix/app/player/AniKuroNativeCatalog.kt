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

/** One stream AniKuro publishes, already carrying the origin its host demands. */
internal data class AniKuroSource(val url: String, val quality: String, val hls: Boolean, val referer: String)

/** One audio variant (sub or dub) and every mirror the provider offered for it. */
internal data class AniKuroVariant(val variant: String, val sources: List<AniKuroSource>)

/** A variant source flattened into the selectable server the player races on. */
internal data class AniKuroStream(val label: String, val url: String, val hls: Boolean, val referer: String)

/** Reads a provider payload into variants, preferring the shape the provider already normalised.
 * AniKuro's own hosts reject requests without a Referer, so [baseReferer] is the default origin. */
internal fun anikuroVariants(payload: JSONObject, baseReferer: String): List<AniKuroVariant> {
    val root = payload.optJSONObject("data") ?: payload
    val normalized = root.optJSONArray("normalized").objects().mapNotNull { anikuroVariant(it, null, baseReferer) }
    if (normalized.isNotEmpty()) return normalized
    val raw = root.optJSONObject("raw") ?: root
    return listOf("sub", "dub").mapNotNull { key ->
        anikuroVariant(raw.optJSONObject(key) ?: return@mapNotNull null, key, baseReferer)
    }
}

/** Flattens variants into selectable servers, Japanese audio first, with unique labels. */
internal fun anikuroStreams(provider: String, variants: List<AniKuroVariant>): List<AniKuroStream> =
    variants.sortedBy { if (it.variant.equals("sub", true)) 0 else 1 }
        .flatMap { variant -> variant.sources.map { variant to it } }
        .mapIndexed { index, (variant, source) ->
            AniKuroStream(
                label = "AniKuro / $provider / ${variant.variant} / " + anikuroSuffix(source.quality, index),
                url = source.url,
                hls = source.hls,
                referer = source.referer,
            )
        }
        .distinctBy { it.label }

private fun anikuroVariant(node: JSONObject, fallbackVariant: String?, baseReferer: String): AniKuroVariant? {
    val name = node.optString("variant").ifBlank { fallbackVariant.orEmpty() }.ifBlank { "sub" }
    val sources = anikuroSources(node, baseReferer)
    return if (sources.isEmpty()) null else AniKuroVariant(name, sources)
}

private fun anikuroSources(node: JSONObject, baseReferer: String): List<AniKuroSource> {
    val variantReferer = node.optJSONObject("headers")?.optString("Referer").orEmpty()
        .takeIf(::isNativeStreamUrl) ?: baseReferer
    val listed = node.optJSONArray("sources").objects().mapNotNull { entry ->
        val url = entry.optString("url").ifBlank { entry.optString("default") }
        if (!isNativeStreamUrl(url)) return@mapNotNull null
        val referer = entry.optJSONObject("headers")?.optString("Referer").orEmpty().takeIf(::isNativeStreamUrl)
            ?: entry.optString("upstreamReferer").takeIf(::isNativeStreamUrl)
            ?: variantReferer
        AniKuroSource(url, entry.optString("quality"), anikuroIsHls(url, entry), referer)
    }
    if (listed.isNotEmpty()) return listed.distinctBy { it.url }
    val default = node.optString("default")
    return if (isNativeStreamUrl(default)) listOf(AniKuroSource(default, "", true, variantReferer)) else emptyList()
}

private fun anikuroIsHls(url: String, node: JSONObject): Boolean {
    val type = node.optString("type").lowercase()
    if (type in setOf("mp4", "video/mp4")) return false
    if (type in setOf("hls", "application/x-mpegurl", "application/vnd.apple.mpegurl")) return true
    return url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}

private fun anikuroSuffix(quality: String, index: Int): String =
    quality.trim().takeIf { Regex("\\d{3,4}p?").matches(it) } ?: "Stream ${index + 1}"

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Uses AniKuro's public JSON catalogue over HTTPS, then hands HLS/MP4 to Media3.
 * No WebView, browser video, local history, progress, or account state owns native playback. */
internal class AniKuroNativeCatalog(private val activity: ComponentActivity) {

    suspend fun resolve(
        selection: PlaybackSelection, positionMs: Long, excluded: Set<String>, preferred: String?,
        strict: Boolean, onServers: (List<String>) -> Unit, onServer: (String) -> Unit, validate: Boolean,
    ): NativePlaybackRequest {
        val mapped = AniListEpisodeMapping.map(activity, selection)
        val providers = (listOf(ANIMEPOWER) + SECONDARY_PROVIDERS)
            .filter { !strict || preferred?.startsWith("AniKuro / $it / ") == true }
            .sortedBy { if (preferred?.startsWith("AniKuro / $it / ") == true) 0 else 1 }
        val labels = mutableListOf<String>()
        val winner = withTimeout(CATALOGUE_TIMEOUT_MS) {
            firstSuccessful(providers.map { provider -> suspend attempt@ {
                val streams = if (provider == ANIMEPOWER) animePowerStreams(mapped.first, mapped.second) else {
                    val payload = fetch(providerPath(provider, mapped.first, mapped.second)) ?: throw NoNativeServersException()
                    anikuroStreams(provider, anikuroVariants(payload, BASE).filter { it.variant.equals("sub", true) })
                }
                labels.addAll(streams.map { it.label }); onServers(labels.distinct())
                val ordered = streams.filter { it.label !in excluded && (!strict || it.label == preferred) }
                    .sortedBy { if (it.label == preferred) 0 else 1 }
                var failure: Exception? = null
                for (stream in ordered) {
                    currentCoroutineContext().ensureActive()
                    val request = request(selection, positionMs, stream)
                    try {
                        if (validate) StartupStreamCache.awaitPlayable(activity, request)
                        return@attempt stream.label to request
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive(); failure = error
                        android.util.Log.d("AliflixAnime", "anikuro_candidate_failed:${stream.label}:${error.javaClass.simpleName}")
                    }
                }
                throw failure ?: NoNativeServersException()
            } }, parallelism = if (strict) 1 else 2)
        }
        onServer(winner.first)
        return winner.second
    }

    private fun request(selection: PlaybackSelection, positionMs: Long, stream: AniKuroStream) = NativePlaybackRequest(
        url = stream.url,
        mimeType = if (stream.hls) "application/x-mpegURL" else "video/mp4",
        referer = stream.referer.ifBlank { BASE },
        userAgent = USER_AGENT,
        cookie = CookieManager.getInstance().getCookie(stream.url).orEmpty(),
        title = selection.media.title,
        positionMs = positionMs,
        playing = true,
        selectionJson = selection.nativeJson(),
    )

    private fun providerPath(provider: String, id: Int, episode: Int): String =
        if (provider in setOf("animegg", "anidb", "animedunya", "animeverse")) "/api/v1/$provider/video/$id/$episode"
        else "$SOURCES_PATH/$provider/$id:$episode"

    private suspend fun animePowerStreams(anilistId: Int, episode: Int): List<AniKuroStream> {
        var dubOnly: List<AniKuroStream> = emptyList()
        repeat(ANIMEPOWER_ATTEMPTS) {
            val payload = fetch("$ANIMEPOWER_PATH/$anilistId/$episode")
            if (payload == null) return@repeat
            val variants = anikuroVariants(payload, BASE)
            val japanese = variants.filter { it.sources.isNotEmpty() && it.variant.equals("sub", true) }
            val usable = japanese.ifEmpty { variants.filter { it.sources.isNotEmpty() } }
            if (usable.isEmpty()) return@repeat
            // animepower can answer with dubbed audio, so keep asking until Japanese audio arrives.
            if (japanese.isNotEmpty()) return anikuroStreams(ANIMEPOWER, japanese)
            if (dubOnly.isEmpty()) dubOnly = anikuroStreams(ANIMEPOWER, usable)
        }
        return dubOnly
    }

    private suspend fun fetch(path: String): JSONObject? = withContext(Dispatchers.IO) {
        try { JSONObject(String(AnimeCatalogueHttp.get(BASE.trimEnd('/') + path, USER_AGENT, BASE).bytes, Charsets.UTF_8)) }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); null }
    }

    private companion object {
        const val BASE = "https://anikuro.to/"
        const val ANIMEPOWER = "animepower"
        const val ANIMEPOWER_PATH = "/api/v1/animepower/video"
        const val SOURCES_PATH = "/api/v1/sources"
        const val HTTP_TIMEOUT_MS = 8_000
        const val CATALOGUE_TIMEOUT_MS = 40_000L
        const val SECONDARY_TIMEOUT_MS = 16_000L
        const val ANIMEPOWER_ATTEMPTS = 1
        const val SECONDARY_PARALLELISM = 3
        val SECONDARY_PROVIDERS = listOf("animepahe", "senshi", "animegg", "anidb", "animedunya", "animeverse", "anikoto", "reanime", "animedao", "allanime", "animix")
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
