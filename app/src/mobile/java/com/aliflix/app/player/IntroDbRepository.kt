package com.aliflix.app.player

import android.content.Context
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.recommendation.RecommendationAiClient
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Optional metadata, fetched independently of stream preparation. No credentials or writes to IntroDB. */
internal class IntroDbRepository(context: Context) {
    private val directory = File(context.cacheDir, "introdb-v1")
    private val identities = context.getSharedPreferences("introdb-identities", Context.MODE_PRIVATE)

    suspend fun segments(selection: PlaybackSelection): List<IntroSegment> = withContext(Dispatchers.IO) {
        if (selection.media.type != MediaType.TV) return@withContext emptyList()
        try {
            val id = selection.media.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }
                ?: identities.getString(selection.media.id.toString(), null)
                ?: RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL).getTitleDetails("tv", selection.media.id).imdbId
                    ?.takeIf { it.matches(Regex("tt[0-9]+")) }?.also {
                        identities.edit().putString(selection.media.id.toString(), it).apply()
                    } ?: return@withContext emptyList()
            val season = selection.seasonNumber ?: 1
            val episode = selection.episodeNumber ?: 1
            val file = File(directory, "$id-$season-$episode.json")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000) {
                runCatching { parseIntroSegments(file.readText(), id, season, episode) }.getOrNull()?.let { return@withContext it }
            }
            val connection = URL("https://api.introdb.app/segments?imdb_id=$id&season=$season&episode=$episode").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Aliflix/${BuildConfig.VERSION_NAME}")
                if (connection.responseCode != 200) return@withContext emptyList()
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 32_768) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 32_768) return@withContext emptyList()
                ensureActive()
                val raw = bytes.toString(Charsets.UTF_8)
                val result = parseIntroSegments(raw, id, season, episode)
                directory.mkdirs(); file.writeText(raw)
                result
            } finally { connection.disconnect() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { emptyList() }
    }
}
