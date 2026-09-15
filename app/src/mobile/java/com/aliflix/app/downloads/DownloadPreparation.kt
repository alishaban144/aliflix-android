@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.offline.DownloadRequest
import com.aliflix.app.AliflixApplication
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal data class DownloadQuality(val label: String, val height: Int, val bytes: Long, val estimated: Boolean, val keys: List<StreamKey>) {
    val sizeLabel get() = (if (estimated && bytes > 0) "≈ " else "") + downloadSize(bytes)
}
internal data class PreparedDownload(val selection: PlaybackSelection, val playback: NativePlaybackRequest,
    val qualities: List<DownloadQuality>, val hasOriginalSubtitles: Boolean)

internal suspend fun prepareDownload(activity: ComponentActivity, host: FrameLayout, selection: PlaybackSelection,
    language: String): PreparedDownload {
    val progress = (activity.application as AliflixApplication).playbackProgressStore
    val prefs = PlaybackProviderRepository(activity).preferences.value
    var last: Exception? = null
    // The same selected-source-first fallback as streaming; each temporary resolver is always released.
    val providers = listOf(selection.source.provider) + mobileGeneralPlaybackProviders().filter { it != selection.source.provider }
    for (provider in providers) {
        currentCoroutineContext().ensureActive()
        val candidate = selection.copy(source = prefs.sourceFor(selection.media, provider))
        try {
            val request = NativeStreamResolver(activity, progress, host).use {
                it.resolve(candidate, 0, emptySet(), onServer = {})
            }.copy(selectionJson = candidate.nativeJson(), subtitleLanguage = language.lowercase(), positionMs = 0)
            return inspectDownload(candidate, request, language)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { last = error }
    }
    throw IllegalStateException("No downloadable video found. Try again.", last)
}

internal suspend fun inspectDownload(selection: PlaybackSelection, request: NativePlaybackRequest, language: String): PreparedDownload = withContext(Dispatchers.IO) {
    val factory = downloadHttpFactory(request)
    suspend fun originalTracks(): List<androidx.media3.common.Format> {
        val item = androidx.media3.common.MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build()
        return androidx.media3.inspector.MetadataRetriever.Builder(null, item)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory)).build().use { retriever ->
                val future = retriever.retrieveTrackGroups()
                val groups = runInterruptible { future.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                buildList { for (g in 0 until groups.length) for (t in 0 until groups[g].length) add(groups[g].getFormat(t)) }
            }
    }
    fun List<androidx.media3.common.Format>.hasSubtitles(): Boolean = any {
        androidx.media3.common.MimeTypes.isText(it.sampleMimeType) &&
            canonicalSubtitleLanguageCode(it.language.orEmpty()) == canonicalSubtitleLanguageCode(language)
    }
    fun playlist(url: String): HlsPlaylist {
        DataSourceInputStream(factory.createDataSource(), DataSpec(Uri.parse(url))).use {
            return HlsPlaylistParser().parse(Uri.parse(url), it)
        }
    }
    fun estimateSegments(playlist: HlsMediaPlaylist): Long {
        val segments = playlist.segments
        if (segments.isEmpty()) return 0
        val sampled = listOf(0, segments.size / 2, segments.lastIndex).distinct().mapNotNull { index ->
            val segment = segments[index]
            val length = if (segment.byteRangeLength > 0) segment.byteRangeLength else runCatching {
                val source = factory.createDataSource()
                try { source.open(DataSpec(androidx.media3.common.util.UriUtil.resolveToUri(playlist.baseUri, segment.url))) }
                finally { source.close() }
            }.getOrDefault(0)
            if (length > 0 && segment.durationUs > 0) length to segment.durationUs else null
        }
        val duration = sampled.sumOf { it.second }
        return if (duration > 0) (sampled.sumOf { it.first }.toDouble() / duration * playlist.durationUs * 1.03).toLong() else 0
    }
    if (!request.mimeType.contains("mpegurl", true) && !request.url.substringBefore('?').endsWith(".m3u8")) {
        val source = factory.createDataSource()
        val size = try { source.open(DataSpec(Uri.parse(request.url))) } finally { source.close() }
        val tracks = originalTracks()
        require(tracks.none { it.drmInitData != null }) { "This video is protected." }
        val height = tracks.maxOfOrNull { it.height }?.coerceAtLeast(0) ?: 0
        return@withContext PreparedDownload(selection, request,
            listOf(DownloadQuality(if (height > 0) "${height}p" else "Original", height, size.coerceAtLeast(0), false, emptyList())), tracks.hasSubtitles())
    }
    when (val manifest = playlist(request.url)) {
        is HlsMultivariantPlaylist -> {
            require(manifest.variants.isNotEmpty()) { "No downloadable qualities found." }
            val subtitle = manifest.subtitles.withIndex().firstOrNull {
                canonicalSubtitleLanguageCode(it.value.format.language.orEmpty()) == canonicalSubtitleLanguageCode(language)
            }
            val firstPlaylist = playlist(manifest.variants.first().url.toString()) as? HlsMediaPlaylist
            val duration = firstPlaylist?.let {
                require(it.hasEndTag) { "Live streams cannot be downloaded." }
                require(it.protectionSchemes == null) { "This video is protected." }
                it.durationUs / 1_000_000.0
            } ?: 0.0
            val qualities = manifest.variants.mapIndexed { index, variant ->
                require(variant.format.drmInitData == null) { "This video is protected." }
                val audio = manifest.audios.withIndex().filter { it.value.groupId == variant.audioGroupId }
                    .let { tracks -> tracks.firstOrNull { it.value.format.selectionFlags and 1 != 0 } ?: tracks.firstOrNull() }
                val keys = buildList {
                    add(StreamKey(0, index))
                    audio?.let { add(StreamKey(1, it.index)) }
                    subtitle?.takeIf { it.value.groupId == variant.subtitleGroupId }?.let { add(StreamKey(2, it.index)) }
                }
                val bitrate = variant.format.averageBitrate.takeIf { it > 0 } ?: variant.format.peakBitrate
                val bytes = if (bitrate > 0 && duration > 0) (bitrate * duration / 8 * 1.03).toLong()
                    else (if (index == 0) firstPlaylist else playlist(variant.url.toString()) as? HlsMediaPlaylist)?.let(::estimateSegments) ?: 0
                val height = variant.format.height.coerceAtLeast(0)
                DownloadQuality(if (height > 0) "${height}p" else "Original", height, bytes, true, keys)
            }.sortedByDescending { it.height }.distinctBy { it.height }
            val embedded = manifest.muxedCaptionFormats.orEmpty().any {
                canonicalSubtitleLanguageCode(it.language.orEmpty()) == canonicalSubtitleLanguageCode(language)
            }
            PreparedDownload(selection, request, qualities, subtitle != null || embedded)
        }
        is HlsMediaPlaylist -> {
            require(manifest.hasEndTag) { "Live streams cannot be downloaded." }
            require(manifest.protectionSchemes == null) { "This video is protected." }
            val tracks = originalTracks()
            val height = tracks.maxOfOrNull { it.height }?.coerceAtLeast(0) ?: 0
            PreparedDownload(selection, request, listOf(DownloadQuality(if (height > 0) "${height}p" else "Original", height, estimateSegments(manifest), true, emptyList())), tracks.hasSubtitles())
        }
        else -> error("No downloadable video found.")
    }
}

internal suspend fun PreparedDownload.downloadRequest(quality: DownloadQuality, language: String, autoSubtitles: Boolean): DownloadRequest {
    var vtt = playback.subtitlesVtt
    if (!hasOriginalSubtitles && vtt.isBlank() && language.isNotBlank()) {
        val repo = SubdlSubtitleRepository()
        val tracks = withTimeout(20_000) { repo.search(selection, language).getOrThrow() }
        val track = mobileSubtitleCandidates(normalizeMobileSubtitleTracks(tracks), language,
            selection.seasonNumber, selection.episodeNumber, selection.media.title).firstOrNull()
        if (track != null) {
            val cues = withTimeout(15_000) { repo.download(track, selection).getOrThrow() }
            require(cues.isNotEmpty() && subtitleLanguageIsPlausible(cues, language)) { "Subtitles unavailable. Choose another language." }
            vtt = nativeSubtitlesVtt(JSONArray().apply { cues.forEach {
                put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text))
            } }.toString(), 0.0)
        } else throw IllegalStateException("Subtitles unavailable. Choose another language or None.")
    }
    val saved = playback.copy(subtitlesVtt = if (language.isBlank()) "" else vtt, subtitleLanguage = language.lowercase(),
        preferEmbeddedSubtitles = hasOriginalSubtitles && language.isNotBlank(), offlineDownloadId = playbackProgressKey(selection),
        offlineAutoSubtitles = autoSubtitles)
    return DownloadRequest.Builder(playbackProgressKey(selection), Uri.parse(playback.url))
        .setMimeType(playback.mimeType).setStreamKeys(quality.keys.filter { language.isNotBlank() || it.groupIndex != 2 })
        .setData(JSONObject().put("playback", saved.toJson()).put("quality", quality.label)
            .put("estimate", quality.bytes).toString().toByteArray(Charsets.UTF_8)).build()
}
