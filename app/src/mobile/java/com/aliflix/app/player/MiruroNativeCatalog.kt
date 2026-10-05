package com.aliflix.app.player

import androidx.activity.ComponentActivity
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

internal data class MiruroNativeStream(val label: String, val url: String, val hls: Boolean, val referer: String, val rules: String)

/** Miruro 1.15's actual catalogue protocol: stable catalogue IDs, a single play
 * payload containing all providers, XOR + gzip binary responses. No browser
 * boot, obsolete secure-pipe calls, browser account or session credentials.
 */
internal class MiruroNativeCatalog(private val activity: ComponentActivity) : AutoCloseable {
    suspend fun resolve(selection: PlaybackSelection, positionMs: Long, excluded: Set<String>, preferred: String?,
                        strict: Boolean, onServers: (List<String>) -> Unit, onServer: (String) -> Unit, validate: Boolean): NativePlaybackRequest {
        val mapped = AniListEpisodeMapping.map(activity, selection)
        val catalogue = api("v1/anime", JSONObject().put("anilist_id_in", mapped.first).put("limit", 100))
        val matches = catalogue.optJSONArray("data").objects().filter { anime ->
            anime.optJSONObject("external_ids")?.optJSONArray("anilist")?.let { ids ->
                (0 until ids.length()).any { ids.optString(it).toIntOrNull() == mapped.first }
            } == true
        }
        val id = matches.singleOrNull()?.optString("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{32}")) }
            ?: throw NoNativeServersException()
        val config = api("config")
        val play = api("v1/anime/$id/episodes/${mapped.second}/play")
        val streams = miruroNativeStreams(play, config)
        onServers(streams.map { it.label })
        val ordered = streams.filter { it.label !in excluded && (!strict || it.label == preferred) }
            .sortedBy { if (it.label == preferred) 0 else 1 }
        if (ordered.isEmpty()) throw NoNativeServersException()
        val winner = firstSuccessful(ordered.map { stream -> suspend {
            val request = NativePlaybackRequest(stream.url, if (stream.hls) "application/x-mpegURL" else "video/mp4",
                stream.referer, USER_AGENT, "", selection.media.title, positionMs, true,
                selectionJson = selection.nativeJson(), streamUrlRules = stream.rules)
            val resolved = request.copy(url = request.resolveStreamUrl(request.url))
            if (validate) try { StartupStreamCache.awaitPlayable(activity, resolved) }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.d("AliflixAnime", "miruro_candidate_failed:${stream.label}:${error.javaClass.simpleName}")
                throw error
            }
            stream.label to resolved
        } }, parallelism = if (strict) 1 else 2)
        onServer(winner.first)
        return winner.second
    }

    private suspend fun api(path: String, query: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val parameters = query.keys().asSequence().joinToString("&") {
            java.net.URLEncoder.encode(it, "UTF-8") + "=" + java.net.URLEncoder.encode(query.get(it).toString(), "UTF-8")
        }
        val response = AnimeCatalogueHttp.get(BASE + "api/" + path + if (parameters.isEmpty()) "" else "?$parameters", USER_AGENT, BASE)
        miruroCataloguePayload(response.bytes, response.contentType)
    }
    override fun close() = Unit
    private companion object {
        const val BASE = "https://www.miruro.tv/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}

internal fun miruroCataloguePayload(bytes: ByteArray, contentType: String): JSONObject {
    require(bytes.size <= 8_000_000)
    if (!contentType.startsWith("application/octet-stream")) return JSONObject(String(bytes, Charsets.UTF_8))
    // This public fixed transport mask is defined in Miruro's fa6 API module.
    val key = "miruro/catalog".toByteArray(Charsets.UTF_8)
    val decoded = ByteArray(bytes.size) { (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() }
    val output = java.io.ByteArrayOutputStream()
    GZIPInputStream(ByteArrayInputStream(decoded)).use { input ->
        val chunk = ByteArray(8192)
        while (true) { val count = input.read(chunk); if (count < 0) break
            require(output.size() + count <= 8_000_000); output.write(chunk, 0, count) }
    }
    return JSONObject(output.toString("UTF-8"))
}

internal fun miruroNativeStreams(play: JSONObject, config: JSONObject): List<MiruroNativeStream> {
    val order = config.optJSONArray("providerOrder").strings()
    val settings = config.optJSONObject("streaming") ?: JSONObject()
    return play.optJSONArray("tracks").objects().filter { it.optString("track") in setOf("ssub", "sub") }
        .sortedBy { if (it.optString("track") == "ssub") 0 else 1 }.flatMap { track ->
            val variant = track.getString("track")
            track.optJSONArray("providers").objects().sortedBy { order.indexOf(it.optString("provider")).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
                .flatMap providerLoop@ { provider ->
                    val name = provider.optString("provider")
                    val rules = settings.optJSONObject(name)?.optJSONObject("hls")?.toString().orEmpty()
                    if (settings.optJSONObject(name)?.optBoolean("visible", true) == false) return@providerLoop emptyList()
                    provider.optJSONArray("servers").objects().flatMap { server ->
                        val headers = server.optJSONObject("headers")
                        val referer = (headers?.optString("Referer").orEmpty().ifBlank { headers?.optString("referer").orEmpty() })
                            .takeIf(::isNativeStreamUrl) ?: "https://www.miruro.tv/"
                        server.optJSONArray("streams").objects().mapNotNull { stream ->
                            val format = stream.optString("format")
                            val url = stream.optString("url")
                            if (format !in setOf("hls", "mp4") || !isNativeStreamUrl(url)) return@mapNotNull null
                            MiruroNativeStream("Miruro / $name / $variant / ${server.optString("server")} / ${stream.optString("quality")}",
                                url, format == "hls", referer, rules)
                        }
                    }
                }
        }.distinctBy { it.label }
}
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
