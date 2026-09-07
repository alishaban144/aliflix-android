package com.aliflix.app.data

import android.content.Context
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.RecommendationAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** TMDB API metadata, cached independently of ratings and title details. */
class MobileEpisodeRepository(context: Context, private val api: RecommendationAiClient) {
    private val directory = File(context.cacheDir, "mobile-episodes-v1")

    private suspend fun document(id: Int, season: Int?, onCached: suspend (JSONObject) -> Unit): JSONObject {
        val file = File(directory, "$id-${season ?: "index"}.json")
        val cached = withContext(Dispatchers.IO) { runCatching { JSONObject(file.readText()) }.getOrNull() }
        if (cached != null) {
            onCached(cached)
            if (System.currentTimeMillis() - file.lastModified() < 6 * 60 * 60 * 1000) return cached
        }
        return try {
            api.getSeasonDocument(id, season).also { json ->
                withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val temporary = File(directory, "${file.name}-${java.util.UUID.randomUUID()}.tmp")
                    temporary.writeText(json.toString())
                    if (!temporary.renameTo(file)) { file.writeText(json.toString()); temporary.delete() }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) { cached ?: throw error }
    }

    suspend fun seasons(id: Int): List<Season> = parseMobileSeasons(document(id, null) {})

    suspend fun episodes(id: Int, season: Int, onCached: suspend (List<Episode>) -> Unit = {}): List<Episode> =
        parseMobileEpisodes(document(id, season) { onCached(parseMobileEpisodes(it)) })
}

internal fun parseMobileEpisodes(json: JSONObject): List<Episode> {
    val season = json.getInt("seasonNumber")
    val array = json.getJSONArray("episodes")
    return (0 until array.length()).mapNotNull { index -> array.getJSONObject(index).let { item ->
        if (item.getInt("seasonNumber") != season || item.getInt("number") < 1) null else
            Episode(season, item.getInt("number"), item.getString("title"), item.optString("overview"),
                item.optString("stillPath").takeIf { it.isNotBlank() && it != "null" },
                item.optInt("runtime").takeIf { it > 0 }?.let { "$it min" }.orEmpty())
    } }.distinctBy { it.number }.sortedBy { it.number }
}

internal fun parseMobileSeasons(json: JSONObject): List<Season> {
    val array = json.getJSONArray("seasons")
    return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.getInt("number") >= 0 }
        .map { Season(it.getInt("number"), it.getString("title"), it.optInt("episodeCount")) }.sortedBy { it.number }
}
