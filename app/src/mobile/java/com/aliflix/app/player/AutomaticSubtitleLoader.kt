package com.aliflix.app.player

import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

internal data class AutomaticSubtitleResult(val track: SubtitleTrack, val cues: List<SubtitleCue>)

internal class AutomaticSubtitleLoader(
    private val sourceTracks: suspend () -> List<SubtitleTrack>,
    private val searchTracks: suspend () -> List<SubtitleTrack>,
    private val download: suspend (SubtitleTrack) -> List<SubtitleCue>,
) {
    suspend fun load(selection: PlaybackSelection, language: String, durationMs: Long, frameRate: Float,
        stillCurrent: () -> Boolean, onTracks: (List<SubtitleTrack>) -> Unit = {},
        latestFrameRate: () -> Float = { frameRate }, latestDurationMs: () -> Long = { durationMs }): AutomaticSubtitleResult? {
        val attempted = hashSetOf<String>()
        for ((phase, lookup) in listOf(sourceTracks, searchTracks).withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) return null
            // Reserve time for SubDL even when several source files stall.
            val result = withTimeoutOrNull(if (phase == 0) 12_000L else 16_000L) {
                val tracks = try { withTimeoutOrNull(6_000) { normalizeMobileSubtitleTracks(lookup()) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                if (!stillCurrent()) return@withTimeoutOrNull null
                onTracks(tracks)
                val candidates = mobileSubtitleCandidates(tracks, language, selection.seasonNumber, selection.episodeNumber, selection.media.title)
                if (candidates.isNotEmpty() && latestFrameRate() <= 0) {
                    // HLS can omit FPS. Allow the first decoded frames to arrive
                    // while playback continues; never wait indefinitely when paused.
                    withTimeoutOrNull(1_000) {
                        while (latestFrameRate() <= 0 && stillCurrent()) delay(50)
                    }
                }
                for (track in rankCompleteSubtitleCandidates(candidates, latestFrameRate()).take(6)) {
                    if (!attempted.add(track.downloadToken) || !stillCurrent()) continue
                    val cues = try { withTimeoutOrNull(3_500) { download(track) }.orEmpty() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                    currentCoroutineContext().ensureActive()
                    if (!stillCurrent()) return@withTimeoutOrNull null
                    if (cues.isNotEmpty() && subtitleLanguageIsPlausible(cues, language) &&
                        automaticCaptionCoversMovie(cues, selection, latestDurationMs()))
                        return@withTimeoutOrNull AutomaticSubtitleResult(track, cues)
                }
                null
            }
            if (result != null) return result
        }
        return null
    }
}
