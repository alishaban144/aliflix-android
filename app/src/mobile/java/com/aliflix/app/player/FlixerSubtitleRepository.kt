package com.aliflix.app.player

import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** The two original subtitle catalogues used by Flixer's own player. */
internal object FlixerSubtitleRepository {
    private data class Cached(val at: Long, val tracks: List<SubtitleTrack>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    suspend fun tracks(selection: PlaybackSelection): List<SubtitleTrack> = coroutineScope {
        val key = subtitleContentKey(selection)
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < 15 * 60_000 }?.let { return@coroutineScope it.tracks }
        val route = if (selection.media.type == MediaType.TV)
            "tv/${selection.media.id}/${selection.seasonNumber ?: 1}/${selection.episodeNumber ?: 1}"
        else "movie/${selection.media.id}"
        val tracks = listOf("v1", "v2").map { version -> async(Dispatchers.IO) {
            try {
                val connection = URL("https://sub.vdrk.site/$version/$route").openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                try { parse(connection.inputStream.bufferedReader().use { it.readText() }) }
                finally { connection.disconnect() }
            } catch (error: Exception) { currentCoroutineContext().ensureActive(); emptyList() }
        } }.awaitAll().flatten().distinctBy { it.downloadToken }
        if (tracks.isNotEmpty()) cache[key] = Cached(System.currentTimeMillis(), tracks)
        tracks
    }

    internal fun parse(raw: String): List<SubtitleTrack> {
        val json = JSONArray(raw)
        return (0 until json.length()).mapNotNull { index ->
            val item = json.getJSONObject(index)
            val label = item.optString("label")
            val name = label.replace(Regex("\\d+$"), "").replace(Regex("\\s*Hi\\d*$", RegexOption.IGNORE_CASE), "").trim()
            val language = SubtitleLanguage.entries.firstOrNull { it.displayName.equals(name, true) }
                ?: when (name) { "Portuguese (BR)" -> SubtitleLanguage.PORTUGUESE; "Spanish (LA)" -> SubtitleLanguage.SPANISH
                    "Chinese (simplified)", "Chinese (traditional)" -> SubtitleLanguage.CHINESE; else -> null }
            val url = item.optString("file").replace(" ", "%20")
            if (language == null || !url.startsWith("https://")) null else
                SubtitleTrack("flixer:$url", language.code, language.displayName, "Flixer · $label", "$label.vtt",
                    Regex("\\sHi\\d*$", RegexOption.IGNORE_CASE).containsMatchIn(label), "vtt", null, url)
        }
    }
}
