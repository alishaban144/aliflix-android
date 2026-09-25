package com.aliflix.app.player

import android.content.Context
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/** Maps TMDB identity and episode numbering onto AniList ids shared by every anime-native catalogue,
 * so racing sources always agree on which episode they resolve. */
internal object AniListEpisodeMapping {
    suspend fun map(context: Context, selection: PlaybackSelection): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val mappings = synchronized(AniListEpisodeMapping::class.java) {
            cachedMappings ?: GZIPInputStream(context.assets.open(ASSET)).bufferedReader().use { JSONObject(it.readText()) }
                .also { cachedMappings = it }
        }
        val movie = selection.media.type == MediaType.MOVIE
        val key = if (movie) "tmdb_movie:${selection.media.id}" else "tmdb_show:${selection.media.id}:s${selection.seasonNumber ?: 1}"
        val targets = mappings.optJSONObject(key)
        val episode = if (movie) 1 else selection.episodeNumber ?: 1
        val matches = targets?.keys()?.asSequence()?.mapNotNull { target ->
            val id = target.removePrefix("anilist:").toIntOrNull() ?: return@mapNotNull null
            val ranges = targets.optJSONObject(target) ?: return@mapNotNull null
            if (movie) return@mapNotNull id to 1
            ranges.keys().asSequence().mapNotNull { source ->
                mappedEpisode(source, ranges.getString(source), episode)?.let { id to it }
            }.firstOrNull()
        }?.toList().orEmpty().distinct()
        if (matches.size == 1) return@withContext matches.single()
        // Do not guess sequel/arc offsets, split episodes, specials, or an ambiguous mapping.
        if (targets != null || (!movie && (selection.seasonNumber ?: 1) != 1)) throw NoNativeServersException()
        exactTitleMatch(selection) to episode
    }

    private fun mappedEpisode(source: String, target: String, episode: Int): Int? {
        val sourceRange = range(source) ?: return null
        if (episode !in sourceRange) return null
        val ratio = target.substringAfter('|', "1").toIntOrNull() ?: return null
        if (ratio != 1) return null // A single native episode cannot represent a split/merged video.
        var offset = episode - sourceRange.first
        for (part in target.substringBefore('|').split(',')) {
            val destination = range(part) ?: return null
            val length = destination.last.toLong() - destination.first + 1
            if (offset.toLong() < length) return destination.first + offset
            offset -= length.toInt()
        }
        return null
    }

    private fun range(raw: String): IntRange? {
        if (!raw.matches(Regex("[0-9]+(?:-[0-9]*)?"))) return null
        val first = raw.substringBefore('-').toIntOrNull() ?: return null
        val last = if ('-' !in raw || raw.endsWith('-')) Int.MAX_VALUE else raw.substringAfter('-').toIntOrNull() ?: return null
        return if (last >= first) first..last else null
    }

    private fun exactTitleMatch(selection: PlaybackSelection): Int {
        val query = "query(\$search:String!){Page(perPage:25){media(search:\$search,type:ANIME,countryOfOrigin:\"JP\"){id format title{english romaji native} synonyms startDate{year}}}}"
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("search", selection.media.title))
        val data = JSONObject(Jsoup.connect(ANILIST_SEARCH_URL).ignoreContentType(true).timeout(8_000)
            .header("Content-Type", "application/json").requestBody(body.toString()).method(Connection.Method.POST).execute().body())
        val title = normalizedTitle(selection.media.title)
        val year = selection.media.year.take(4).toIntOrNull()
        val movie = selection.media.type == MediaType.MOVIE
        val matches = data.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media").objects().filter { item ->
            val names = item.optJSONObject("title")?.let { listOf(it.optString("english"), it.optString("romaji"), it.optString("native")) }.orEmpty() + item.optJSONArray("synonyms").strings()
            (item.optString("format") == "MOVIE") == movie && year != null && item.optJSONObject("startDate")?.optInt("year") == year &&
                names.any { normalizedTitle(it) == title }
        }
        return matches.singleOrNull()?.optInt("id")?.takeIf { it > 0 } ?: throw NoNativeServersException()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    private fun normalizedTitle(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private const val ASSET = "anime-tmdb-anilist.json.gz"
    private const val ANILIST_SEARCH_URL = "https://graphql.anilist.co"
    @Volatile private var cachedMappings: JSONObject? = null
}
