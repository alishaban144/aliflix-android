package com.aliflix.app.player

import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AutomaticSubtitleResult(val track: SubtitleTrack, val cues: List<SubtitleCue>)

internal class AutomaticSubtitleLoader(
    private val sourceTracks: suspend () -> List<SubtitleTrack>,
    private val searchTracks: suspend () -> List<SubtitleTrack>,
    private val download: suspend (SubtitleTrack) -> List<SubtitleCue>,
) {
    suspend fun load(selection: PlaybackSelection, language: String, durationMs: Long, frameRate: Float,
        stillCurrent: () -> Boolean, onTracks: (List<SubtitleTrack>) -> Unit = {}): AutomaticSubtitleResult? {
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
                for (track in rankCompleteSubtitleCandidates(candidates, frameRate).take(6)) {
                    if (!attempted.add(track.downloadToken) || !stillCurrent()) continue
                    val cues = try { withTimeoutOrNull(3_500) { download(track) }.orEmpty() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                    currentCoroutineContext().ensureActive()
                    if (!stillCurrent()) return@withTimeoutOrNull null
                    if (cues.isNotEmpty() && subtitleLanguageIsPlausible(cues, language) &&
                        automaticCaptionCoversMovie(cues, selection, durationMs))
                        return@withTimeoutOrNull AutomaticSubtitleResult(track, cues)
                }
                null
            }
            if (result != null) return result
        }
        return null
    }
}
