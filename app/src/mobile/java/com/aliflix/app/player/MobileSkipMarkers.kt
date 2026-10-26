package com.aliflix.app.player

import android.content.Context
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.isJapaneseAnime
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** Read timing metadata only. Streams, subtitles and their original clocks are untouched. */
internal class MobileSkipMarkers(private val context: Context) {
    suspend fun discover(selection: PlaybackSelection, duration: () -> Long, result: (List<IntroSegment>) -> Unit) = supervisorScope {
        if (selection.media.type != MediaType.TV) return@supervisorScope
        val sources = arrayOfNulls<List<IntroSegment>>(3)
        suspend fun publish(index: Int, markers: List<IntroSegment>) = withContext(Dispatchers.Main.immediate) {
            sources[index] = markers
            // Keep an entire source interval; never average two different cuts or join their endpoints.
            result(selectSkipMarkers(sources.map { it.orEmpty() }, duration()))
        }
        withTimeoutOrNull(12_000) {
            listOf(
                launch { publish(0, IntroDbRepository(context).segments(selection)) },
                launch {
                    if (!selection.media.isJapaneseAnime) return@launch
                    safely {
                        val mapped = AniListEpisodeMapping.map(context, selection)
                        val rawId = metadata("mal-${mapped.first}", "https://graphql.anilist.co", JSONObject()
                            .put("query", "query { Media(id: ${mapped.first}, type: ANIME) { id idMal } }").toString())
                        val media = JSONObject(rawId).getJSONObject("data").getJSONObject("Media")
                        if (media.optInt("id") != mapped.first || media.optInt("idMal") <= 0) return@safely
                        val raw = metadata("ani-${media.getInt("idMal")}-${mapped.second}",
                            "https://api.aniskip.com/v2/skip-times/${media.getInt("idMal")}/${mapped.second}?types%5B%5D=op&types%5B%5D=ed&episodeLength=0")
                        publish(1, parseAniSkipMarkers(raw, duration()))
                    }
                },
                launch { safely {
                    val id = selection.media.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }
                        ?: IntroDbRepository(context).imdbIdentity(selection) ?: return@safely
                    val video = "$id:${selection.seasonNumber ?: 1}:${selection.episodeNumber ?: 1}"
                    val raw = metadata("hater-$video", "https://introhater.com/api/segments/$video")
                    publish(2, parseIntroHaterMarkers(raw, video))
                } },
            ).joinAll()
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (error: Exception) { currentCoroutineContext().ensureActive() }
    }

    private suspend fun metadata(key: String, url: String, body: String? = null): String {
        val file = File(context.cacheDir, "mobile-skip-markers/$key.json")
        withContext(Dispatchers.IO) {
            if (file.exists()) {
                val raw = file.readText()
                val negative = raw.trim() == "[]" || runCatching { JSONObject(raw).optBoolean("found", true) == false }.getOrDefault(false)
                val ttl = if (negative) 5 * 60_000L else 6 * 60 * 60_000L
                raw.takeIf { it.isNotBlank() && System.currentTimeMillis() - file.lastModified() < ttl }
            } else null
        }?.let { return it }
        val raw = SkipMetadataHttp.read(url, body)
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeText(raw) }
        return raw
    }
}

internal fun selectSkipMarkers(sources: List<List<IntroSegment>>, duration: Long): List<IntroSegment> =
    IntroSegmentKind.entries.flatMap { kind ->
        sources.firstOrNull { source -> source.any { it.kind == kind && validSkipMarker(it, duration) } }
            .orEmpty().filter { it.kind == kind && validSkipMarker(it, duration) }
    }.distinct().sortedBy { it.startMs }

private fun validSkipMarker(marker: IntroSegment, duration: Long): Boolean = duration <= 0 ||
    marker.startMs < duration

internal fun parseAniSkipMarkers(raw: String, duration: Long = 0): List<IntroSegment> {
    val json = JSONObject(raw)
    if (!json.optBoolean("found")) return emptyList()
    val rows = json.optJSONArray("results") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val kind = when (row.optString("skipType")) { "op" -> IntroSegmentKind.INTRO; "ed" -> IntroSegmentKind.OUTRO; else -> return@mapNotNull null }
        val length = row.optDouble("episodeLength", 0.0) * 1000
        if (duration > 0 && length > 0 && abs(length - duration) > maxOf(20_000.0, duration * .02)) return@mapNotNull null
        val interval = row.optJSONObject("interval") ?: return@mapNotNull null
        marker(kind, interval.optDouble("startTime", Double.NaN), interval.optDouble("endTime", Double.NaN))
    }.distinct()
}

internal fun parseIntroHaterMarkers(raw: String, videoId: String): List<IntroSegment> {
    val rows = JSONArray(raw)
    return (0 until rows.length()).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        if (row.optString("videoId") != videoId) return@mapNotNull null
        val kind = when (row.optString("label").lowercase()) {
            "intro", "opening" -> IntroSegmentKind.INTRO
            "outro", "ending", "credits" -> IntroSegmentKind.OUTRO
            else -> return@mapNotNull null
        }
        marker(kind, row.optDouble("start", Double.NaN), row.optDouble("end", Double.NaN))
    }.distinct()
}

private fun marker(kind: IntroSegmentKind, start: Double, end: Double): IntroSegment? =
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end > 86_400) null
    else IntroSegment(kind, (start * 1000).toLong(), (end * 1000).toLong())

/** Losing metadata requests disconnect on cancellation; no SDK, shell CLI or proxy dependency. */
internal object SkipMetadataHttp {
    private val executor = Executors.newFixedThreadPool(3) { Thread(it, "aliflix-skip-metadata").apply { isDaemon = true } }
    suspend fun read(url: String, body: String?): String = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000; readTimeout = 4_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Aliflix/${BuildConfig.VERSION_NAME}")
            if (body != null) { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        val task = executor.submit {
            try {
                if (!continuation.isActive) return@submit
                body?.let { connection.outputStream.use { stream -> stream.write(it.toByteArray()) } }
                check(connection.responseCode == 200)
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 65_536) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= 65_536)
                if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8))
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { task.cancel(true); connection.disconnect() }
    }
}
