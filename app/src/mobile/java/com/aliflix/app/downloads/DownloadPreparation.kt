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
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

internal data class DownloadQuality(val label: String, val height: Int, val bytes: Long, val estimated: Boolean,
    val keys: List<StreamKey>, val audioGroupId: String? = null, val hasOriginalSubtitles: Boolean? = null) {
    val sizeLabel get() = (if (estimated && bytes > 0) "≈ " else "") + downloadSize(bytes)
}
internal data class DownloadAudioTrack(val label: String, val language: String?, val groupId: String, val index: Int, val bytes: Long = 0,
    val original: Boolean = false)
internal data class PreparedDownload(val selection: PlaybackSelection, val playback: NativePlaybackRequest,
    val qualities: List<DownloadQuality>, val hasOriginalSubtitles: Boolean, val server: String = "",
    val audioTracks: List<DownloadAudioTrack> = emptyList(), val options: List<DownloadOption> = emptyList()) {
    fun owner(quality: DownloadQuality): PreparedDownload = options.firstOrNull { it.quality == quality }?.source ?: this
    fun audioTracksFor(quality: DownloadQuality) = owner(quality).audioTracks.filter { it.groupId == quality.audioGroupId }
}

internal fun downloadProviderOrder(selection: PlaybackSelection): List<PlaybackProvider> =
    (listOf(if (selection.media.isJapaneseAnime && selection.media.type==MediaType.TV) MobilePlaybackProvider.ANIWORLD
        else if (selection.media.type==MediaType.TV) MobilePlaybackProvider.SERIENSTREAM else MobilePlaybackProvider.FILMO,
        selection.source.identity) + mobileGeneralPlaybackProviders() + listOf(PlaybackProviderId.MIRURO,PlaybackProviderId.ANIKURO))
        .filter { it.isAvailableFor(selection.media) }.distinct()

internal fun downloadStreamKeys(quality: DownloadQuality, tracks: List<DownloadAudioTrack>, audioIndex: Int?): List<StreamKey> {
    if (audioIndex == null) return quality.keys
    val available = tracks.filter { it.groupId == quality.audioGroupId }
    val chosen = if (audioIndex == -1) available else available.filter { it.index == audioIndex }
    require(chosen.isNotEmpty()) { "The selected audio track is unavailable for this quality." }
    if (quality.audioGroupId == "serienstream-source") {
        require(audioIndex != -1) { "Separate language editions must be downloaded individually" }
        return quality.keys
    }
    return (quality.keys.filterNot { it.groupIndex == 1 } + chosen.map { StreamKey(1, it.index) }).sorted()
}

internal fun estimatedDownloadQuality(quality: DownloadQuality, tracks: List<DownloadAudioTrack>, audioIndex: Int?): DownloadQuality {
    if (audioIndex == null || tracks.isEmpty()) return quality
    if (quality.audioGroupId == "serienstream-source") return quality
    val available = tracks.filter { it.groupId == quality.audioGroupId }
    val selected = if (audioIndex == -1) available else available.filter { it.index == audioIndex }
    val defaults = available.filter { track -> quality.keys.any { it.groupIndex == 1 && it.streamIndex == track.index } }
    if (selected.map { it.index } == defaults.map { it.index }) return quality
    val bytes = if (quality.bytes <= 0 || (selected + defaults).any { it.bytes <= 0 }) 0
        else (quality.bytes - defaults.sumOf { it.bytes } + selected.sumOf { it.bytes }).coerceAtLeast(0)
    return quality.copy(bytes = bytes, estimated = true)
}

internal suspend fun prepareDownload(activity: ComponentActivity, host: FrameLayout, selection: PlaybackSelection,
    language: String, onUpdate: (DownloadDiscovery) -> Unit = {}, requiredAudio: String = "Default", allowVerification:Boolean=true): PreparedDownload {
    val progress = (activity.application as AliflixApplication).playbackProgressStore
    val prefs = PlaybackProviderRepository(activity).preferences.value
    val providers = downloadProviderOrder(selection)
    var requiredVerification:SerienstreamVerificationRequired?=null
    suspend fun attempt(provider: PlaybackProvider): PreparedDownload {
        val candidate = selection.copy(source = if (provider == selection.source.identity) selection.source else prefs.sourceFor(selection.media, provider))
        var server = ""
        val request = try { NativeStreamResolver(activity, progress, host).use {
            it.resolve(candidate, 0, emptySet(), allowVerification=allowVerification, onServer = { name -> server = name })
        } } catch (verification: SerienstreamVerificationRequired) {
            val session = DownloadSession.get(activity)
            session.verifications[session.key(candidate, language)] = candidate to verification
            requiredVerification=verification
            throw verification
        }
        if (provider == MobilePlaybackProvider.SERIENSTREAM) DownloadSession.get(activity).let { it.verifications.remove(it.key(candidate, language)) }
        val readyRequest = request.copy(selectionJson = candidate.nativeJson(), subtitleLanguage = language.lowercase(), positionMs = 0)
        val inspected = inspectDownload(candidate, readyRequest, language).copy(server = server)
        val useful = inspected.qualities.filter { quality -> requiredAudio == "Default" ||
            inspected.audioTracksFor(quality).let { tracks -> if (requiredAudio == "All tracks") tracks.isNotEmpty() else tracks.any { it.label == requiredAudio } } }
        require(useful.isNotEmpty()) { "Selected audio unavailable" }
        return inspected.copy(qualities = useful)
    }
    val primary=providers.first()
    try {
        val preferred=withTimeout(60_000) { attempt(primary) }
        onUpdate(DownloadDiscovery(preferred.qualities.map { DownloadOption(it,preferred) },true))
        return preferred
    } catch (gate:SerienstreamVerificationRequired) { if(allowVerification)throw gate }
    catch (cancelled:CancellationException) {
        currentCoroutineContext().ensureActive()
        if (!mayAutomaticallyLeaveSource(primary, cancelled)) throw IllegalStateException("Source unavailable · Retry", cancelled)
    }
    catch (error:Exception) {
        currentCoroutineContext().ensureActive()
        if (!mayAutomaticallyLeaveSource(primary, error)) throw error
    }
    return try{discoverDownloadOptions(providers.drop(1), ::attempt, onUpdate).prepared()}
    catch(error:IllegalStateException){throw (requiredVerification?:error)}
}

internal suspend fun prepareDownloadBatch(activity: ComponentActivity, host: FrameLayout,
    selections: List<Pair<String, PlaybackSelection>>, language: String, cached: Map<String, PreparedDownload>,
    onPrepared: (String, PreparedDownload) -> Unit, onError: (String, String) -> Unit,
    pinnedAnchor: DownloadOption? = null, requiredAudio: String = "Default", preferredTier: DownloadQualityTier? = null): Unit = withContext(Dispatchers.Main.immediate) {
    val progress = (activity.application as AliflixApplication).playbackProgressStore
    val prefs = PlaybackProviderRepository(activity).preferences.value
    prepareDownloadBatchInternal(selections, language, cached,
        discover = { selection -> prepareDownload(activity, host, selection, language, requiredAudio = requiredAudio) },
        resolvePinned = { selection, server ->
            var actualServer = ""
            val request = NativeStreamResolver(activity, progress, host).use {
                it.resolve(selection, 0, emptySet(), preferredServer = server, strictPreferredServer = true,
                    onServer = { name -> actualServer = name })
            }
            check(actualServer == server) { "The selected server changed." }
            request.copy(selectionJson = selection.nativeJson(), subtitleLanguage = language.lowercase(), positionMs = 0)
        },
        inspect = ::inspectDownload,
        sourceIsCurrent = { selection ->
            selection.source == prefs.sourceFor(selection.media, selection.source.identity) ||
                selections.any { it.second.source == selection.source }
        },
        onPrepared = onPrepared, onError = onError, pinnedAnchor = pinnedAnchor, requiredAudio = requiredAudio, preferredTier = preferredTier)
}

private val preparationSlots = kotlinx.coroutines.sync.Semaphore(4)

internal suspend fun prepareDownloadBatchInternal(
    selections: List<Pair<String, PlaybackSelection>>, language: String, cached: Map<String, PreparedDownload>,
    discover: suspend (PlaybackSelection) -> PreparedDownload,
    resolvePinned: suspend (PlaybackSelection, String) -> NativePlaybackRequest,
    inspect: suspend (PlaybackSelection, NativePlaybackRequest, String) -> PreparedDownload,
    sourceIsCurrent: (PlaybackSelection) -> Boolean = { true },
    onPrepared: (String, PreparedDownload) -> Unit, onError: (String, String) -> Unit,
    pinnedAnchor: DownloadOption? = null, requiredAudio: String = "Default", preferredTier: DownloadQualityTier? = null,
) {
    fun group(selection: PlaybackSelection) = selection.media.key to
        if (selection.media.type == MediaType.TV) selection.seasonNumber ?: 1 else null
    fun sameContent(first: PlaybackSelection, second: PlaybackSelection) =
        first.media.key == second.media.key && first.seasonNumber == second.seasonNumber && first.episodeNumber == second.episodeNumber
    val snapshot = cached.filterValues { it.qualities.isNotEmpty() && sourceIsCurrent(it.selection) }
    coroutineScope {
        val anchors = snapshot.values.filter { it.server.isNotBlank() }.groupBy { group(it.selection) }.mapValues { it.value.first() }.toMutableMap()
        pinnedAnchor?.let { anchors[group(it.source.selection)] = it.source }
        fun requireAudio(item: PreparedDownload): PreparedDownload {
            val available = item.qualities.filter { quality -> (pinnedAnchor?.quality?.height?.let { it <= 0 } != false || quality.height > 0) && (requiredAudio == "Default" || item.audioTracksFor(quality).let { tracks ->
                if (requiredAudio == "All tracks") tracks.isNotEmpty() else tracks.any { it.label == requiredAudio }
            }) }
            require(available.isNotEmpty()) { "Selected audio unavailable" }
            val height = pinnedAnchor?.quality?.height ?: item.qualities.first().height
            val quality = if (preferredTier != null) requireNotNull(preferredDownloadQuality(item.copy(qualities = available), preferredTier))
                else closestDownloadQuality(available, height)
            if (requiredAudio != "Default") require(item.audioTracksFor(quality).let { tracks ->
                if (requiredAudio == "All tracks") tracks.isNotEmpty() else tracks.any { it.label == requiredAudio }
            }) { "Selected audio unavailable" }
            return if (requiredAudio == "Default") item else item.copy(qualities = available)
        }
        val discoveries = mutableMapOf<String, CompletableDeferred<PreparedDownload>>()
        suspend fun discoverOnce(selection: PlaybackSelection): Pair<PreparedDownload, Boolean> {
            var leader = false
            val key = group(selection).toString()
            val deferred = synchronized(discoveries) {
                discoveries[key] ?: CompletableDeferred<PreparedDownload>().also {
                    discoveries[key] = it
                    leader = true
                }
            }
            if (leader) {
                try {
                    deferred.complete(discover(selection))
                } catch (cancelled: CancellationException) {
                    deferred.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    deferred.completeExceptionally(error)
                    throw error
                }
            }
            return deferred.await() to leader
        }
        suspend fun inspectFromAnchor(selection: PlaybackSelection, anchor: PreparedDownload): PreparedDownload {
            val candidate = selection.copy(source = anchor.selection.source)
            return try {
                withTimeout(30_000) {
                    requireAudio(inspect(candidate, resolvePinned(candidate, anchor.server), language).copy(server = anchor.server))
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                return requireAudio(discover(selection))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return requireAudio(discover(selection))
            }
        }
        selections.distinctBy { it.first }.map { (key, selection) -> async {
            preparationSlots.withPermit {
                try {
                    val saved = snapshot[key]?.takeIf { sameContent(it.selection, selection) }
                    val result = if (saved != null) {
                        if (saved.playback.subtitleLanguage == language.lowercase()) saved
                        else inspect(saved.selection, saved.playback.copy(subtitleLanguage = language.lowercase(),
                            subtitlesVtt = "", preferEmbeddedSubtitles = false), language).copy(server = saved.server)
                    } else {
                        val existingAnchor = anchors[group(selection)]
                        if (existingAnchor != null) {
                            inspectFromAnchor(selection, existingAnchor)
                        } else {
                            val (discovered, leader) = discoverOnce(selection)
                            if (leader) {
                                anchors[group(selection)] = discovered
                                discovered
                            } else {
                                inspectFromAnchor(selection, anchors[group(selection)] ?: discovered)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    require(result.qualities.isNotEmpty())
                    val accepted = requireAudio(result)
                    if (result.server.isNotBlank() && group(selection) !in anchors) anchors[group(selection)] = result
                    onPrepared(key, accepted)
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); onError(key, "The episode timed out. Retry this episode.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    val message = error.message.orEmpty().ifBlank { "Unavailable" }
                    val retry = if (message.contains("No playable", ignoreCase = true)) {
                        "$message. Retry."
                    } else {
                        "$message. Retry this episode."
                    }
                    onError(key, retry)
                }
            }
        } }.awaitAll()
    }
}

internal suspend fun inspectDownload(selection: PlaybackSelection, request: NativePlaybackRequest, language: String): PreparedDownload {
    val inspected = inspectDownloadMedia(selection, request, language)
    if (!selection.source.identity.hasSourceEditions) return inspected
    // Actual multiaudio masters retain their native rendition groups and stream keys.
    if (inspected.audioTracks.size > 1) return inspected
    val tracks = request.sourceAudioChoices().mapIndexed { index, audio ->
        DownloadAudioTrack(audio.label, audio.language, "serienstream-source", index,
            original = audio.language.equals(selection.media.originalLanguage, true))
    }
    return inspected.copy(qualities = inspected.qualities.map { it.copy(audioGroupId = "serienstream-source") }, audioTracks = tracks)
}

private suspend fun inspectDownloadMedia(selection: PlaybackSelection, request: NativePlaybackRequest, language: String): PreparedDownload = withContext(Dispatchers.IO) {
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
    suspend fun playlist(url: String): HlsPlaylist {
        val source = factory.createDataSource()
        return inspectDownloadSource(source) { DataSourceInputStream(source, DataSpec(Uri.parse(url))).use {
            HlsPlaylistParser().parse(Uri.parse(url), it)
        } }
    }
    suspend fun estimateSegments(playlist: HlsMediaPlaylist): Long {
        val segments = playlist.segments
        if (segments.isEmpty()) return 0
        val sampled = listOf(0, segments.size / 2, segments.lastIndex).distinct().mapNotNull { index ->
            val segment = segments[index]
            val length = if (segment.byteRangeLength > 0) segment.byteRangeLength else runCatching {
                val source = factory.createDataSource()
                inspectDownloadSource(source) { source.open(DataSpec(androidx.media3.common.util.UriUtil.resolveToUri(playlist.baseUri, segment.url))) }
            }.getOrDefault(0)
            if (length > 0 && segment.durationUs > 0) length to segment.durationUs else null
        }
        val duration = sampled.sumOf { it.second }
        return if (duration > 0) (sampled.sumOf { it.first }.toDouble() / duration * playlist.durationUs * 1.03).toLong() else 0
    }
    if (!request.mimeType.contains("mpegurl", true) && !request.url.substringBefore('?').endsWith(".m3u8")) {
        val source = factory.createDataSource()
        val size = inspectDownloadSource(source) { source.open(DataSpec(Uri.parse(request.url))) }
        val tracks = originalTracks()
        require(tracks.none { it.drmInitData != null }) { "This video is protected." }
        val height = tracks.maxOfOrNull { it.height }?.coerceAtLeast(0) ?: 0
        return@withContext PreparedDownload(selection, request,
            listOf(DownloadQuality(if (height > 0) "${height}p" else "Original", height, size.coerceAtLeast(0), false, emptyList())), tracks.hasSubtitles())
    }
    suspend fun downloadable(media: HlsMediaPlaylist): Boolean {
        if (!media.hasEndTag || media.protectionSchemes != null || media.segments.isEmpty()) return false
        val segment = media.segments.first()
        val source = factory.createDataSource()
        return try {
            inspectDownloadSource(source) {
                val uri = androidx.media3.common.util.UriUtil.resolveToUri(media.baseUri, segment.url)
                DataSourceInputStream(source, DataSpec.Builder().setUri(uri).setPosition(segment.byteRangeOffset.coerceAtLeast(0)).setLength(segment.byteRangeLength.takeIf { it > 0 } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()).build())
                    .use { it.read() >= 0 }
            }
        } catch (_: Exception) { currentCoroutineContext().ensureActive(); false }
    }
    when (val manifest = playlist(request.url)) {
        is HlsMultivariantPlaylist -> {
            require(manifest.variants.isNotEmpty()) { "No downloadable qualities found." }
            val subtitle = manifest.subtitles.withIndex().firstOrNull {
                canonicalSubtitleLanguageCode(it.value.format.language.orEmpty()) == canonicalSubtitleLanguageCode(language)
            }
            val audioTracks = manifest.audios.withIndex().mapNotNull { (index, rendition) ->
                val uri = rendition.url ?: return@mapNotNull null
                val media = try { playlist(uri.toString()) as? HlsMediaPlaylist }
                    catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
                if (media == null || !downloadable(media)) return@mapNotNull null
                DownloadAudioTrack(formatAudioTrackLabel(rendition.format.language, rendition.format.label),
                    rendition.format.language, rendition.groupId, index, estimateSegments(media),
                    original = !selection.media.originalLanguage.isBlank() && canonicalSubtitleLanguageCode(rendition.format.language.orEmpty()) == canonicalSubtitleLanguageCode(selection.media.originalLanguage))
            }
            val embedded = manifest.muxedCaptionFormats.orEmpty().any {
                canonicalSubtitleLanguageCode(it.language.orEmpty()) == canonicalSubtitleLanguageCode(language)
            }
            val qualities = manifest.variants.mapIndexedNotNull { index, variant ->
                currentCoroutineContext().ensureActive()
                if (variant.format.drmInitData != null) return@mapIndexedNotNull null
                val mediaPlaylist = try { playlist(variant.url.toString()) as? HlsMediaPlaylist }
                    catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
                if (mediaPlaylist == null || !downloadable(mediaPlaylist))
                    return@mapIndexedNotNull null
                val duration = mediaPlaylist.durationUs / 1_000_000.0
                val audio = manifest.audios.withIndex().filter { it.value.groupId == variant.audioGroupId && audioTracks.any { track -> track.index == it.index } }
                    .let { tracks -> tracks.firstOrNull { it.value.format.selectionFlags and 1 != 0 } ?: tracks.firstOrNull() }
                if (variant.audioGroupId != null && audio == null && manifest.audios.any { it.groupId == variant.audioGroupId && it.url != null })
                    return@mapIndexedNotNull null
                val variantSubtitle = manifest.subtitles.withIndex().firstOrNull {
                    it.value.groupId == variant.subtitleGroupId && canonicalSubtitleLanguageCode(it.value.format.language.orEmpty()) == canonicalSubtitleLanguageCode(language)
                }
                val keys = buildList {
                    add(StreamKey(0, index))
                    audio?.let { add(StreamKey(1, it.index)) }
                    variantSubtitle?.let { add(StreamKey(2, it.index)) }
                }
                val bitrate = variant.format.averageBitrate.takeIf { it > 0 } ?: variant.format.peakBitrate
                val bytes = if (bitrate > 0 && duration > 0) (bitrate * duration / 8 * 1.03).toLong()
                    else estimateSegments(mediaPlaylist)
                val height = variant.format.height.coerceAtLeast(0)
                DownloadQuality(if (height > 0) "${height}p" else "Original", height, bytes, true, keys, variant.audioGroupId,
                    embedded || variantSubtitle != null)
            }.sortedByDescending { it.height }
            require(qualities.isNotEmpty()) { "No downloadable qualities found." }
            PreparedDownload(selection, request, qualities, subtitle != null || embedded, audioTracks = audioTracks)
        }
        is HlsMediaPlaylist -> {
            require(downloadable(manifest)) { "No downloadable video found." }
            require(manifest.protectionSchemes == null) { "This video is protected." }
            val tracks = originalTracks()
            val height = tracks.maxOfOrNull { it.height }?.coerceAtLeast(0) ?: 0
            PreparedDownload(selection, request, listOf(DownloadQuality(if (height > 0) "${height}p" else "Original", height, estimateSegments(manifest), true, emptyList())), tracks.hasSubtitles())
        }
        else -> error("No downloadable video found.")
    }
}

internal suspend fun PreparedDownload.downloadRequest(quality: DownloadQuality, language: String, autoSubtitles: Boolean,
    audioIndex: Int? = null, resolveAudio: suspend (PlaybackSelection,String)->NativePlaybackRequest = { _,_->error("Audio edition needs a running download picker") }, approveProvider: suspend (SerienstreamVerificationRequired) -> Boolean = { false }): DownloadRequest {
    val source = owner(quality)
    if (source !== this) return source.downloadRequest(quality, language, autoSubtitles, audioIndex, resolveAudio, approveProvider)
    if (quality.audioGroupId == "serienstream-source" && audioIndex != null) {
        require(audioIndex >= 0) { "Download one language edition at a time" }
        val audio = audioTracks.firstOrNull { it.index == audioIndex } ?: error("Selected audio unavailable")
        if (audio.language != playback.sourceAudioLanguage) {
            suspend fun resolveEdition() = if (selection.source.identity == MobilePlaybackProvider.SERIENSTREAM)
                SerienstreamNativeCatalog.resolve(selection, 0, emptySet(), null, false, {}, {}, audio.language)
                else resolveAudio(selection,requireNotNull(audio.language))
            val request = try { resolveEdition() } catch (gate: SerienstreamVerificationRequired) {
                if (!approveProvider(gate)) throw gate
                resolveEdition()
            }
            val refreshed = inspectDownload(selection, request, language)
            return refreshed.downloadRequest(closestDownloadQuality(refreshed.qualities, quality.height), language, autoSubtitles, resolveAudio = resolveAudio, approveProvider = approveProvider)
        }
    }
    val originalSubtitles = quality.hasOriginalSubtitles ?: hasOriginalSubtitles
    var vtt = playback.subtitlesVtt
    if (selection.source.identity.hasSourceEditions && language.isNotBlank()) {
        val caption = playback.sourceCaptionTracks().firstOrNull { canonicalSubtitleLanguageCode(it.languageCode) == canonicalSubtitleLanguageCode(language) }
        if (caption != null) {
            val cues = SubdlSubtitleRepository().download(caption, selection).getOrThrow()
            require(cues.isNotEmpty() && subtitleLanguageIsPlausible(cues, language)) { "Subtitles unavailable" }
            vtt = nativeSubtitlesVtt(subtitleCuesJson(cues), 0.0)
        }
    }
    if (selection.source.identity == MobilePlaybackProvider.FLIXER && language.isNotBlank()) {
        val originals = FlixerSubtitleRepository.tracks(selection).filter { it.languageCode.equals(language, true) }.sortedBy { it.hearingImpaired }
        for (original in originals.take(3)) {
            val cues = withTimeoutOrNull(5_000) { SubdlSubtitleRepository().download(original, selection).getOrNull() }.orEmpty()
            if (cues.isEmpty() || !subtitleLanguageIsPlausible(cues, language)) continue
            vtt = nativeSubtitlesVtt(JSONArray().apply { cues.forEach {
                put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text))
            } }.toString(), 0.0)
            break
        }
    }
    if (!originalSubtitles && vtt.isBlank() && language.isNotBlank()) {
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
        preferEmbeddedSubtitles = originalSubtitles && language.isNotBlank(), offlineDownloadId = playbackProgressKey(selection),
        offlineAutoSubtitles = autoSubtitles)
    return DownloadRequest.Builder(playbackProgressKey(selection), Uri.parse(playback.url))
        .setMimeType(playback.mimeType).setStreamKeys(downloadStreamKeys(quality, audioTracks, audioIndex)
            .filter { language.isNotBlank() || it.groupIndex != 2 })
        .setData(JSONObject().put("playback", saved.toJson()).put("quality", quality.label)
            .put("estimate", estimatedDownloadQuality(quality, audioTracks, audioIndex).bytes).toString().toByteArray(Charsets.UTF_8)).build()
}
